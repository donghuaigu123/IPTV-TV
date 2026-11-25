package com.iptv.tv.core

import android.content.Context
import com.iptv.tv.data.Prefs
import com.iptv.tv.model.Channel
import com.iptv.tv.model.CollectProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 直播源可用性检测：并发发起 Range 请求，记录延迟、下载速度与响应特征。
 */
class ChannelChecker(private val context: Context) {

    suspend fun check(
        channels: List<Channel>,
        onProgress: (CollectProgress) -> Unit
    ): List<Channel> = withContext(Dispatchers.IO) {
        val timeoutSec = Prefs.getInt(context, Prefs.KEY_TIMEOUT, 8).coerceIn(3, 30)
        val concurrency = Prefs.getInt(context, Prefs.KEY_CONCURRENCY, 24).coerceIn(4, 64)
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .callTimeout((timeoutSec + 3).toLong(), TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val sem = Semaphore(concurrency)
        val total = channels.size
        var done = 0

        val results = channels.map { ch ->
            async {
                sem.acquire()
                try {
                    val checked = checkOne(ch, client)
                    done++
                    if (done % 5 == 0 || done == total) {
                        onProgress(CollectProgress("可用性检测", done, total, "${ch.name} · ${if (checked.isOk) "可用" else "不可用"}"))
                    }
                    checked
                } finally {
                    sem.release()
                }
            }
        }.awaitAll()

        onProgress(CollectProgress("检测完成", total, total, "可用 ${results.count { it.isOk }} / $total"))
        results
    }

    private fun checkOne(ch: Channel, client: OkHttpClient): Channel {
        val t0 = System.currentTimeMillis()
        try {
            val probeBytes = 8192
            val ua = ch.userAgent.ifBlank { SourceCollector.DEFAULT_UA }
            val request = Request.Builder()
                .url(ch.url)
                .header("Range", "bytes=0-$probeBytes")
                .header("User-Agent", ua)
                .header("Accept", "*/*")
                .build()
            client.newCall(request).execute().use { resp ->
                val code = resp.code()
                val contentType = resp.header("Content-Type") ?: ""
                val body = resp.body()
                val bytes = body?.byteStream()?.use { stream ->
                    val buf = ByteArray(probeBytes)
                    val n = stream.read(buf)
                    if (n > 0) String(buf, 0, n, Charsets.ISO_8859_1) else ""
                } ?: ""

                val latency = System.currentTimeMillis() - t0
                val speed = if (latency > 0) (bytes.length * 1000L / latency) / 1024 else 0

                // 判定：2xx 响应且有实际内容，或 Content-Type 为流媒体类型
                val ok = code in 200..299 &&
                    (bytes.length > 64 || contentType.contains("mpegurl", true) ||
                     contentType.contains("video", true) || contentType.contains("stream", true) ||
                     ch.url.contains(".m3u8", true))

                return if (ok) {
                    ch.copy(
                        status = Channel.STATUS_OK,
                        latencyMs = latency,
                        speedKBps = speed,
                        failReason = ""
                    )
                } else {
                    ch.copy(
                        status = Channel.STATUS_FAIL,
                        latencyMs = latency,
                        failReason = when {
                            code !in 200..299 -> "HTTP $code"
                            bytes.length <= 64 -> "空响应"
                            else -> "无效内容"
                        }
                    )
                }
            }
        } catch (e: Exception) {
            return ch.copy(
                status = Channel.STATUS_FAIL,
                latencyMs = System.currentTimeMillis() - t0,
                failReason = e.message?.take(60) ?: "连接失败"
            )
        }
    }
}
