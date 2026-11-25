package com.iptv.tv.core

import android.content.Context
import com.iptv.tv.data.Prefs
import com.iptv.tv.data.SourceCatalog
import com.iptv.tv.model.Channel
import com.iptv.tv.model.CollectProgress
import com.iptv.tv.model.SourceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class SourceCollector(private val context: Context) {

    /** 单源采集结果，用于诊断反馈 */
    data class SourceResult(
        val name: String,
        val count: Int,
        val success: Boolean,
        val detail: String,
        val usedMirror: String
    )

    private var lastResults = mutableListOf<SourceResult>()
    fun getLastResults(): List<SourceResult> = lastResults.toList()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 并发抓取所有启用的订阅源，GitHub Raw 源自动尝试镜像回退。
     */
    suspend fun collect(
        sources: List<SourceConfig>,
        onProgress: (CollectProgress) -> Unit
    ): List<Channel> = withContext(Dispatchers.IO) {
        val enabled = sources.filter { it.enabled }
        val total = enabled.size
        var done = 0
        lastResults.clear()
        onProgress(CollectProgress("正在连接数据源", 0, total, "共 $total 个订阅源"))

        val lists = enabled.map { src ->
            async {
                val result = fetchWithFallback(src)
                val info = result.result
                synchronized(lastResults) { lastResults.add(info) }
                done++
                val short = if (info.success) "+${info.count}" else "失败"
                onProgress(
                    CollectProgress(
                        "正在抓取订阅源", done, total,
                        "${src.name} $short${if (info.usedMirror.isNotBlank()) " · ${info.usedMirror}" else ""}"
                    )
                )
                // 给该来源的频道打上"网络采集源"标记，originKey 用源名称+URL区分不同订阅
                val kind = if (src.type == SourceConfig.TYPE_TVBOX) Channel.ORIGIN_TVBOX else Channel.ORIGIN_COLLECT
                SourceOrigin.tag(result.channels, kind, "${src.name}|${src.url}")
            }
        }.awaitAll()

        val all = lists.flatten()
        val okSources = lastResults.count { it.success }
        onProgress(CollectProgress("正在整理频道", total, total, "成功源 $okSources/$total · 原始条目 ${all.size} 条"))

        val merged = Parsers.mergeAndNormalize(
            all,
            Prefs.getBool(context, Prefs.KEY_RETAIN_DUP, true)
        )
        onProgress(CollectProgress("整理完成", total, total, "整理后 ${merged.size} 个频道"))
        merged
    }

    /** 带镜像回退的单源抓取：依次尝试原始地址与各加速镜像 */
    private fun fetchWithFallback(src: SourceConfig): SourceResultWithChannels {
        val candidates = SourceCatalog.candidateUrls(src.url.trim())
        var lastError = ""
        candidates.forEachIndexed { index, url ->
            val label = if (index == 0) "直连" else "镜像$index"
            try {
                val channels = fetchOne(src.copy(url = url))
                if (channels.isNotEmpty()) {
                    return SourceResultWithChannels(
                        SourceResult(src.name, channels.size, true, "OK", label),
                        channels
                    )
                }
                lastError = "空内容"
            } catch (e: Exception) {
                lastError = e.message?.take(40) ?: "连接失败"
            }
        }
        return SourceResultWithChannels(
            SourceResult(src.name, 0, false, lastError, ""),
            emptyList()
        )
    }

    private data class SourceResultWithChannels(val result: SourceResult, val channels: List<Channel>)

    /** 抓取并解析单个源（具体 URL） */
    private fun fetchOne(src: SourceConfig): List<Channel> {
        val url = src.url.trim()
        if (url.isBlank()) return emptyList()
        val ua = src.userAgent.ifBlank { Prefs.getString(context, Prefs.KEY_UA, DEFAULT_UA) }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "*/*")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code()}")
            val body = resp.body()?.string() ?: return emptyList()
            if (body.isBlank() || body.length < 50) return emptyList()

            return when (src.type) {
                SourceConfig.TYPE_TXT -> Parsers.parseTxt(body, src.name)
                SourceConfig.TYPE_TVBOX -> expandTvBoxSync(body, url, src.name, ua)
                else -> Parsers.parseAny(body, src.name)
            }
        }
    }

    /**
     * 同步展开 TVBox 配置（采集流程在 IO 线程调用）。
     * 关键：lives[].url 是订阅列表地址，必须二次抓取解析；直接流地址则直接建频道。
     */
    private fun expandTvBoxSync(
        text: String, configUrl: String, label: String, defaultUa: String
    ): List<Channel> {
        val lives = TvBoxParser.parseLives(text)
        if (lives.isEmpty()) return emptyList()
        val out = mutableListOf<Channel>()
        lives.forEach { live ->
            try {
                val resolved = TvBoxParser.resolveUrl(live.url, configUrl) ?: return@forEach
                val ua = live.ua.ifBlank { defaultUa }
                val sample = fetchHead(resolved, ua)
                when (TvBoxParser.detectContentKind(resolved, sample)) {
                    TvBoxParser.ContentKind.DIRECT ->
                        out += Channel(live.name, resolved, live.group.ifBlank { "TVBox" },
                            logo = live.logo, epg = live.epg, source = "$label·${live.name}",
                            userAgent = ua)
                    TvBoxParser.ContentKind.PLAYLIST -> {
                        val full = if (sample != null && sample.length >= 4096) sample else fetchFull(resolved, ua)
                        if (!full.isNullOrBlank()) {
                            Parsers.parseAny(full, label).forEach { ch ->
                                out += ch.copy(
                                    group = ch.group.ifBlank { live.group.ifBlank { live.name } },
                                    logo = ch.logo.ifBlank { live.logo },
                                    epg = ch.epg.ifBlank { live.epg },
                                    source = "$label·${live.name}",
                                    userAgent = ua
                                )
                            }
                        }
                    }
                    TvBoxParser.ContentKind.UNKNOWN -> {
                        if (resolved.lowercase().let {
                                it.contains(".m3u8") || it.contains(".flv") ||
                                it.contains(".mp4") || it.startsWith("rtmp")
                            }) {
                            out += Channel(live.name, resolved, live.group.ifBlank { "TVBox" },
                                logo = live.logo, epg = live.epg, source = "$label·${live.name}",
                                userAgent = ua)
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        return out.distinctBy { it.url }
    }

    /** 抓取头部样本（Range 请求），返回前 4096 字节文本 */
    private fun fetchHead(url: String, ua: String): String? = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua).header("Range", "bytes=0-4095").header("Accept", "*/*").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body()?.byteStream()?.use { s ->
                val buf = ByteArray(4096); val n = s.read(buf)
                if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
            }
        }
    } catch (_: Exception) { null }

    /** 抓取完整文本 */
    private fun fetchFull(url: String, ua: String): String? = try {
        val req = Request.Builder().url(url).header("User-Agent", ua).header("Accept", "*/*").build()
        client.newCall(req).execute().use { resp -> if (resp.isSuccessful) resp.body()?.string() else null }
    } catch (_: Exception) { null }

    /** 导入用户提供的文本（本地文件或粘贴内容）；TVBox 配置会走二次展开 */
    fun parseImported(text: String, label: String, configUrl: String? = null): List<Channel> {
        if (TvBoxParser.isTvBoxConfig(text)) {
            return expandTvBoxSync(text, configUrl ?: "", label, DEFAULT_UA)
        }
        return Parsers.parseAny(text, label)
    }

    companion object {
        const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 IPTV/1.1"
    }
}
