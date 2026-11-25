package com.iptv.tv.core

import android.content.Context
import com.iptv.tv.data.Prefs
import com.iptv.tv.model.Channel
import com.iptv.tv.model.CollectProgress
import com.iptv.tv.model.TvBoxLive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.IDN
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * TVBox 配置导入器（v1.6 核心修复）。
 *
 * 旧版失败根因：把 lives[].url（订阅列表地址）直接当成可播放频道塞给播放器。
 * 本类正确做法：抓取每个订阅地址 → 解析出真实频道 → 继承该线路 UA/分组/台标，
 * 这样导入后的频道才是真正可播放的媒体流地址。
 */
class TvBoxImporter(private val context: Context) {

    data class ImportResult(
        val channels: List<Channel>,
        val liveCount: Int,          // 配置中 lives 条数
        val expandedCount: Int,      // 成功展开的订阅数
        val directCount: Int,        // 直接流地址数
        val failedUrls: List<String>
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 从配置文本导入。configUrl 为配置来源地址（可能为空，如手动粘贴），用于解析相对路径。
     */
    suspend fun importFromText(
        text: String,
        configUrl: String?,
        label: String,
        onProgress: (CollectProgress) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {
        val lives = TvBoxParser.parseLives(text)
        if (lives.isEmpty()) {
            return@withContext ImportResult(emptyList(), 0, 0, 0, emptyList())
        }
        expandLives(lives, configUrl, label, onProgress)
    }

    /**
     * 从配置 URL 导入：先抓取配置文本，再展开。
     */
    suspend fun importFromUrl(
        configUrl: String,
        label: String,
        onProgress: (CollectProgress) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {
        val text = fetch(configUrl, SourceCollector.DEFAULT_UA)
            ?: return@withContext ImportResult(emptyList(), 0, 0, 0, listOf(configUrl))
        if (!TvBoxParser.isTvBoxConfig(text)) {
            // 也可能用户填的本身就是订阅列表或 m3u，直接当普通源解析
            val chans = Parsers.parseAny(text, label)
            return@withContext ImportResult(chans, 0, 0, 0, emptyList())
        }
        val lives = TvBoxParser.parseLives(text)
        if (lives.isEmpty()) {
            return@withContext ImportResult(emptyList(), 0, 0, 0, emptyList())
        }
        expandLives(lives, configUrl, label, onProgress)
    }

    /** 展开 lives：并发抓取每个订阅地址，解析成真实频道 */
    private suspend fun expandLives(
        lives: List<TvBoxLive>,
        configUrl: String?,
        label: String,
        onProgress: (CollectProgress) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {
        val sem = Semaphore(8)
        val total = lives.size
        var done = 0
        val failed = java.util.Collections.synchronizedList(mutableListOf<String>())

        // each: 第一条 = 频道列表，第二条 = 是否为展开的订阅(true)/直接流(false)
        val results = lives.map { live ->
            async {
                sem.acquire()
                try {
                    val resolved = TvBoxParser.resolveUrl(live.url, configUrl)
                    if (resolved == null) {
                        failed.add(live.url)
                        Pair(emptyList<Channel>(), false)
                    } else {
                        val ua = live.ua.ifBlank { SourceCollector.DEFAULT_UA }
                        // 先取一小段样本判断类型（ DIRECT 直接流 / PLAYLIST 订阅列表 ）
                        val sample = fetchSample(resolved, ua)
                        val kind = TvBoxParser.detectContentKind(resolved, sample)
                        val pair: Pair<List<Channel>, Boolean> = when (kind) {
                            TvBoxParser.ContentKind.DIRECT ->
                                Pair(listOf(toChannel(live, resolved, label, ua)), false)
                            TvBoxParser.ContentKind.PLAYLIST -> {
                                val full = sample?.let { if (it.length >= 4096) it else fetch(resolved, ua) } ?: fetch(resolved, ua)
                                if (full.isNullOrBlank()) {
                                    failed.add(live.url)
                                    Pair(emptyList(), true)
                                } else {
                                    val chans = Parsers.parseAny(full, label).map { ch ->
                                        ch.copy(
                                            group = ch.group.ifBlank { live.group.ifBlank { live.name } },
                                            logo = ch.logo.ifBlank { live.logo },
                                            epg = ch.epg.ifBlank { live.epg },
                                            source = "$label·${live.name}",
                                            userAgent = ua
                                        )
                                    }
                                    Pair(chans, true)
                                }
                            }
                            TvBoxParser.ContentKind.UNKNOWN -> {
                                // 拿不准时按 URL 后缀兜底：像流就直接建频道，让用户可尝试
                                if (resolved.lowercase().let {
                                        it.contains(".m3u8") || it.contains(".flv") ||
                                        it.contains(".mp4") || it.startsWith("rtmp")
                                    }) {
                                    Pair(listOf(toChannel(live, resolved, label, ua)), false)
                                } else {
                                    failed.add(live.url)
                                    Pair(emptyList(), true)
                                }
                            }
                        }
                        done++
                        if (done % 2 == 0 || done == total) {
                            onProgress(CollectProgress("展开 TVBox 订阅", done, total, "${live.name} · ${pair.first.size} 个频道"))
                        }
                        pair
                    }
                } catch (_: Exception) {
                    failed.add(live.url)
                    done++
                    Pair(emptyList(), false)
                } finally {
                    sem.release()
                }
            }
        }.awaitAll()

        val flat = results.flatMap { it.first }
            .filter { it.url.contains("://") }
            .distinctBy { it.url }

        onProgress(CollectProgress("导入完成", total, total, "共 ${flat.size} 个可播放频道"))
        ImportResult(
            channels = flat,
            liveCount = total,
            expandedCount = results.count { it.second && it.first.isNotEmpty() },
            directCount = results.count { !it.second && it.first.isNotEmpty() },
            failedUrls = failed.toList()
        )
    }

    private fun toChannel(live: TvBoxLive, url: String, label: String, ua: String): Channel = Channel(
        name = live.name,
        url = url,
        group = live.group.ifBlank { "TVBox" },
        logo = live.logo,
        epg = live.epg,
        source = "$label·${live.name}",
        userAgent = ua
    )

    /** 抓取前若干字节做类型判断（避免整篇下载） */
    private fun fetchSample(url: String, ua: String): String? = try {
        val req = Request.Builder()
            .url(normalizeUrl(url))
            .header("User-Agent", ua)
            .header("Range", "bytes=0-4095")
            .header("Accept", "*/*")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body()?.byteStream()?.use { stream ->
                val buf = ByteArray(4096)
                val n = stream.read(buf)
                if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
            }
        }
    } catch (_: Exception) { null }

    /** 抓取完整内容 */
    private fun fetch(url: String, ua: String): String? = try {
        val req = Request.Builder()
            .url(normalizeUrl(url))
            .header("User-Agent", ua)
            .header("Accept", "*/*")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body()?.string()
        }
    } catch (_: Exception) { null }

    /** 中文域名转 punycode，避免 okhttp 解析失败 */
    private fun normalizeUrl(url: String): String = try {
        val u = java.net.URL(url)
        val host = u.host
        val asciiHost = IDN.toASCII(host)
        if (host == asciiHost) url
        else url.replace(host, asciiHost, ignoreCase = true)
    } catch (_: Exception) {
        url
    }
}
