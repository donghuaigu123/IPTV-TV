package com.iptv.tv.core

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.iptv.tv.data.Prefs
import com.iptv.tv.model.Channel
import com.iptv.tv.model.ExportFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 将频道列表导出为多种通用格式，写入用户选择的目录（SAF）。
 */
class Exporter(private val context: Context) {

    suspend fun export(channels: List<Channel>, format: ExportFormat): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val base = "IPTV_${stamp}"
            val fileName = if (format == ExportFormat.TVBOX) "${base}_tvbox.json" else "$base.${format.ext}"
            val content = when (format) {
                ExportFormat.M3U, ExportFormat.M3U8 -> buildM3u(channels, format == ExportFormat.M3U8)
                ExportFormat.TXT -> buildTxt(channels)
                ExportFormat.JSON -> buildJson(channels)
                ExportFormat.TVBOX -> buildTvBox(channels)
                ExportFormat.XSPF -> buildXspf(channels)
                ExportFormat.CSV -> buildCsv(channels)
            }
            val bytes = content.toByteArray(Charsets.UTF_8)

            val treeUri = Prefs.getTreeUri(context)
            if (treeUri.isNullOrBlank()) {
                // Android 4.4 / 未选目录：降级写入应用外部存储 Download/IPTV
                val dir = java.io.File(context.getExternalFilesDir(null), "IPTV").apply { mkdirs() }
                val file = java.io.File(dir, fileName)
                file.writeBytes(bytes)
                return@withContext Result.success(Uri.fromFile(file))
            }

            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                ?: return@withContext Result.failure(IllegalStateException("目录不可用，请重新选择"))
            val existing = tree.findFile(fileName)
            existing?.delete()
            val file = tree.createFile(format.mime, fileName)
                ?: return@withContext Result.failure(IllegalStateException("无法创建文件"))
            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: return@withContext Result.failure(IllegalStateException("无法写入文件"))

            Result.success(file.uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun buildM3u(channels: List<Channel>, hls: Boolean): String = buildString {
        append("#EXTM3U")
        if (hls) append(" m3u8")
        append(" x-tvg-url=\"https://epg.pw/xmltv/epg_zh.xml\"\n")
        channels.forEach { ch ->
            val logo = ch.logo.ifBlank { "" }
            val id = ch.tvgId.ifBlank { ch.name }
            append("#EXTINF:-1 tvg-id=\"$id\" tvg-name=\"${ch.name}\" tvg-logo=\"$logo\" group-title=\"${ch.group}\",${ch.name}\n")
            append("${ch.url}\n")
        }
    }

    fun buildTxt(channels: List<Channel>): String = buildString {
        append("#IPTV 直播源 TXT 格式\n")
        append("#频道名,地址\n")
        channels.forEach { ch -> append("${ch.name},${ch.url}\n") }
    }

    fun buildJson(channels: List<Channel>): String {
        val data = channels.map {
            mapOf(
                "name" to it.name,
                "url" to it.url,
                "group" to it.group,
                "logo" to it.logo,
                "source" to it.source,
                "status" to it.status,
                "latency_ms" to it.latencyMs,
                "speed_kbps" to it.speedKBps
            )
        }
        return Gson().toJson(mapOf("generated_at" to System.currentTimeMillis(), "count" to data.size, "channels" to data))
    }

    /** 生成 TVBox 可用的订阅配置：把有效频道直接写入 lives，并携带频道 UA，导入后即可播放 */
    fun buildTvBox(channels: List<Channel>): String {
        val valid = channels.filter { it.status != Channel.STATUS_FAIL }
        val lives = valid.take(3000).map { ch ->
            val m = mutableMapOf<String, Any>(
                "name" to ch.name,
                "url" to ch.url,
                "group" to ch.group.ifBlank { "IPTV" },
                "type" to 0
            )
            if (ch.userAgent.isNotBlank()) m["ua"] = ch.userAgent
            if (ch.logo.isNotBlank()) m["logo"] = ch.logo
            m.toMap()
        }
        return Gson().toJson(
            mapOf(
                "name" to "IPTV 聚合（有效源）",
                "lives" to lives
            )
        )
    }

    fun buildXspf(channels: List<Channel>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<playlist version=\"1\" xmlns=\"http://xspf.org/ns/0/\">\n<title>IPTV</title>\n<trackList>\n")
        channels.forEach { ch ->
            append("  <track>\n")
            append("    <title>${escapeXml(ch.name)}</title>\n")
            append("    <album>${escapeXml(ch.group)}</album>\n")
            append("    <location>${escapeXml(ch.url)}</location>\n")
            append("  </track>\n")
        }
        append("</trackList>\n</playlist>\n")
    }

    fun buildCsv(channels: List<Channel>): String = buildString {
        append("频道名称,分组,地址,状态,延迟(ms),速度(KB/s),来源\n")
        channels.forEach { ch ->
            val row = listOf(ch.name, ch.group, ch.url,
                if (ch.isOk) "可用" else if (ch.status == Channel.STATUS_FAIL) "不可用" else "未检测",
                ch.latencyMs.toString(), ch.speedKBps.toString(), ch.source)
            append(row.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }).append("\n")
        }
    }

    private fun escapeXml(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
}
