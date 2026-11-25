package com.iptv.tv.core

import com.google.gson.JsonParser
import com.iptv.tv.model.TvBoxLive
import com.iptv.tv.model.TvBoxSite

/**
 * TVBox / 影视仓配置解析器（v1.6 重写）。
 *
 * 真实配置的关键特征（务必理解，否则会导入后无法播放）：
 * 1. 配置文本常带 `//` 行注释头，Gson 无法直接解析，需先清洗。
 * 2. `lives[].url` 绝大多数是【订阅列表】地址（.txt/.m3u/.m3u8 播放列表），
 *    而不是可直接播放的媒体流 —— 必须二次抓取展开成真实频道。
 * 3. `lives[].url` 可能是相对路径（如 ./libs/tv/tvlive.txt），需基于配置地址解析。
 * 4. `lives[].ua` 是该线路专用的 User-Agent，播放/检测时必须使用，否则 403。
 * 5. `sites` 是点播爬虫 API（api 字段为 jar/js），不是直播播放地址，绝不能当频道。
 */
object TvBoxParser {

    /** 判断一段文本是不是 TVBox 配置 JSON */
    fun isTvBoxConfig(text: String): Boolean {
        if (text.isBlank()) return false
        val clean = stripComments(text)
        val head = clean.trimStart()
        if (!head.startsWith("{") && !head.startsWith("[")) return false
        // 典型 TVBox 配置都有 sites / lives / spider / wallpaper 之一
        return clean.contains("\"sites\"") || clean.contains("\"lives\"") ||
                clean.contains("\"spider\"") || clean.contains("\"wallpaper\"")
    }

    /**
     * 清洗配置文本：移除 // 行注释与 /* */ 块注释，保留字符串内部的 //（如 http://）。
     * 逐字符扫描，跟踪是否在字符串内。
     */
    fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var inString = false
        var escaped = false
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (inString) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                i++
                continue
            }
            if (c == '"') { inString = true; out.append(c); i++; continue }
            // 块注释
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                val end = src.indexOf("*/", i + 2)
                i = if (end < 0) src.length else end + 2
                continue
            }
            // 行注释：前面只允许是行首空白
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                val lineStart = out.lastIndexOf('\n')
                val before = if (lineStart < 0) out.toString() else out.substring(lineStart + 1)
                if (before.isBlank()) {
                    val end = src.indexOf('\n', i)
                    i = if (end < 0) src.length else end // 保留换行由下一轮 append
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** 解析 lives 字段，得到直播订阅列表（url 可能是订阅地址，需二次展开） */
    fun parseLives(text: String): List<TvBoxLive> {
        val out = mutableListOf<TvBoxLive>()
        try {
            val root = JsonParser.parseString(stripComments(text))
            if (!root.isJsonObject) return out
            val obj = root.asJsonObject
            val livesEl = listOf("lives", "live")
                .firstOrNull { obj.has(it) && obj.get(it).isJsonArray }
                ?.let { obj.getAsJsonArray(it) } ?: return out

            livesEl.forEach { el ->
                if (!el.isJsonObject) return@forEach
                val o = el.asJsonObject
                val name = o.str("name") ?: o.str("title") ?: return@forEach
                val urlEl = o.get("url") ?: o.get("play_url") ?: o.get("url_live") ?: return@forEach
                val urls = when {
                    urlEl.isJsonPrimitive -> listOfNotNull(urlEl.asString?.takeIf { it.isNotBlank() })
                    urlEl.isJsonArray -> urlEl.asJsonArray.mapNotNull {
                        if (it.isJsonPrimitive) it.asString?.takeIf { s -> s.isNotBlank() } else null
                    }
                    else -> emptyList()
                }
                val ua = o.str("ua") ?: o.str("userAgent") ?: ""
                val logo = o.str("logo") ?: ""
                val epg = o.str("epg") ?: ""
                val group = o.str("group") ?: o.str("group_name") ?: ""
                urls.forEach { u ->
                    out += TvBoxLive(name, u.trim(), ua, logo, epg, group)
                }
            }
        } catch (_: Exception) { }
        return out
    }

    /** 解析 sites 字段（信息展示用，不作为播放地址） */
    fun parseSites(text: String): List<TvBoxSite> {
        val out = mutableListOf<TvBoxSite>()
        try {
            val root = JsonParser.parseString(stripComments(text))
            if (!root.isJsonObject) return out
            val obj = root.asJsonObject
            val sitesEl = listOf("sites", "site", "urls")
                .firstOrNull { obj.has(it) && obj.get(it).isJsonArray }
                ?.let { obj.getAsJsonArray(it) } ?: return out
            sitesEl.forEach { el ->
                if (!el.isJsonObject) return@forEach
                val o = el.asJsonObject
                val name = o.str("name") ?: return@forEach
                out += TvBoxSite(
                    name = name,
                    url = o.str("url") ?: "",
                    type = o.int("type"),
                    api = o.str("api") ?: "",
                    searchable = o.int("searchable"),
                    categories = o.str("categories")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                )
            }
        } catch (_: Exception) { }
        return out
    }

    /**
     * 解析 url：把相对路径（./libs/tv/x.txt、/tv/x.m3u）基于配置地址转成绝对地址。
     * 内置 jar 路径（./libs/xxx.jar、assets://）无法作为订阅抓取，返回 null。
     */
    fun resolveUrl(raw: String, configUrl: String?): String? {
        val u = raw.trim()
        if (u.isBlank()) return null
        if (u.contains("://")) return u
        // 明显是 jar/js 脚本资源，不是订阅列表
        if (u.contains(".jar", true) || u.contains(".js", true) || u.startsWith("assets://")) return null
        if (configUrl.isNullOrBlank() || !configUrl.contains("://")) return null
        return try {
            val base = java.net.URI(configUrl)
            val resolved = base.resolve(u).toString()
            if (resolved.contains("://")) resolved else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 判断订阅内容类型：
     * - PLAYLIST: 是 m3u/txt 播放列表，需要展开
     * - DIRECT: 是单个可播放流地址（m3u8/mp4/rtmp 等）
     * - UNKNOWN: 无法识别（如 HTML 错误页）
     */
    enum class ContentKind { PLAYLIST, DIRECT, UNKNOWN }

    fun detectContentKind(url: String, sample: String?): ContentKind {
        val lower = url.lowercase()
        if (lower.endsWith(".m3u8") || lower.contains(".m3u8?") ||
            lower.endsWith(".flv") || lower.endsWith(".mp4") ||
            lower.startsWith("rtmp://") || lower.startsWith("rtmps://") ||
            lower.startsWith("rtsp://")) {
            return ContentKind.DIRECT
        }
        val head = sample?.trimStart()?.take(200) ?: ""
        if (head.contains("#EXTM3U", true)) return ContentKind.PLAYLIST
        if (head.startsWith("{") || head.startsWith("[")) return ContentKind.UNKNOWN // XML/JSON 点播接口
        if (head.startsWith("<", ignoreCase = false)) return ContentKind.UNKNOWN // HTML 错误页
        // txt 订阅常见 "频道名,地址" 或 "频道名#genre,地址"
        if (head.lineSequence().take(5).any { it.contains(",") && it.contains("://") }) return ContentKind.PLAYLIST
        if (lower.endsWith(".m3u") || lower.endsWith(".txt") || lower.contains(".m3u?") || lower.contains(".txt?")) {
            return ContentKind.PLAYLIST
        }
        return ContentKind.UNKNOWN
    }

    private fun com.google.gson.JsonObject.str(key: String): String? =
        try { if (has(key) && !get(key).isJsonNull) get(key).asString?.takeIf { it.isNotBlank() } else null }
        catch (_: Exception) { null }

    private fun com.google.gson.JsonObject.int(key: String): Int =
        try { if (has(key) && !get(key).isJsonNull) get(key).asInt else 0 } catch (_: Exception) { 0 }
}
