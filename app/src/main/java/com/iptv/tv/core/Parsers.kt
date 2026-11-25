package com.iptv.tv.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.iptv.tv.data.SourceCatalog
import com.iptv.tv.model.Channel

object Parsers {

    private val attrRegex = Regex("([a-zA-Z0-9_-]+)=\"([^\"]*)\"")
    private val timestampRegex = Regex("^\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}(\\s+\\d{1,2}:\\d{2}(:\\d{2})?)?$")

    /** 判断标题是否是"YYYY-MM-DD HH:mm:ss"这类时间戳（部分源误把它当频道名） */
    private fun isTimestampLike(s: String): Boolean {
        val t = s.trim()
        return t.length <= 25 && timestampRegex.matches(t)
    }

    /** 解析 M3U / M3U8 文本 */
    fun parseM3u(text: String, source: String = ""): List<Channel> {
        val lines = text.lineSequence().map { it.trim() }.toList()
        val result = mutableListOf<Channel>()
        var pendingName = ""
        var pendingGroup = ""
        var pendingLogo = ""
        var pendingEpg = ""
        var pendingId = ""
        var lastGroup = ""

        for (line in lines) {
            when {
                line.startsWith("#EXTINF") -> {
                    val attrs = attrRegex.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    var title = line.substringAfterLast(",").trim()
                    // 部分源（如 Guovin）会把更新时间戳放在标题位，识别出来并回退到 tvg-name
                    if (isTimestampLike(title)) {
                        title = attrs["tvg-name"] ?: attrs["name"] ?: ""
                    }
                    pendingName = title.ifBlank { attrs["tvg-name"] ?: attrs["name"] ?: "" }
                    pendingGroup = attrs["group-title"] ?: ""
                    pendingLogo = attrs["tvg-logo"] ?: ""
                    pendingEpg = attrs["tvg-url"] ?: attrs["x-tvg-url"] ?: ""
                    pendingId = attrs["tvg-id"] ?: ""
                }
                line.startsWith("#EXTGRP", ignoreCase = true) -> {
                    lastGroup = line.removePrefix("#EXTGRP").removePrefix(":").trim()
                }
                line.startsWith("#KODIPROP") || line.startsWith("#EXTVLCOPT") -> {
                    // 保留给高级用户：这里不做 UA 解析，播放器端统一设置 UA
                }
                line.startsWith("#") -> Unit
                line.contains("://") -> {
                    val group = pendingGroup.ifBlank { lastGroup }
                    val finalName = pendingName.ifBlank { "未命名-${result.size + 1}" }
                    result += Channel(
                        name = finalName.trim(),
                        url = line,
                        group = SourceCatalog.categoryOf(finalName, group),
                        logo = pendingLogo,
                        epg = pendingEpg,
                        tvgId = pendingId,
                        source = source,
                        rawLine = line
                    )
                    pendingName = ""; pendingGroup = ""; pendingLogo = ""
                    pendingEpg = ""; pendingId = ""
                }
            }
        }
        return result
    }

    /** 解析 TXT：支持 "频道名,URL"、"频道名#genre,URL" 及纯 URL 行 */
    fun parseTxt(text: String, source: String = ""): List<Channel> {
        val result = mutableListOf<Channel>()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { line ->
                when {
                    line.contains(",") -> {
                        val parts = line.split(",", limit = 2)
                        val rawName = parts[0].trim()
                        val url = parts[1].trim()
                        if (url.contains("://")) {
                            val group = rawName.substringAfterLast("#", "").ifBlank { "" }
                            val name = rawName.substringBefore("#").trim()
                            result += Channel(
                                name = name,
                                url = url,
                                group = SourceCatalog.categoryOf(name, group),
                                source = source,
                                rawLine = line
                            )
                        }
                    }
                    line.contains("://") -> {
                        result += Channel(
                            name = "频道-${result.size + 1}",
                            url = line,
                            group = "其他",
                            source = source,
                            rawLine = line
                        )
                    }
                }
            }
        return result
    }

    /** 自动识别格式 */
    fun parseAny(text: String, source: String = ""): List<Channel> {
        val head = text.take(400)
        return when {
            head.contains("#EXTM3U", ignoreCase = true) -> parseM3u(text, source)
            head.trimStart().startsWith("{") || head.trimStart().startsWith("[") -> parseJsonPlaylist(text, source)
            else -> parseTxt(text, source)
        }
    }

    /** 解析 JSON 播放列表（兼容部分 API 输出） */
    private fun parseJsonPlaylist(text: String, source: String): List<Channel> {
        val result = mutableListOf<Channel>()
        try {
            val root = JsonParser.parseString(text)
            val arr = when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject && root.asJsonObject.has("channels") -> root.asJsonObject.getAsJsonArray("channels")
                root.isJsonObject && root.asJsonObject.has("data") -> root.asJsonObject.getAsJsonArray("data")
                else -> null
            } ?: return result
            arr.forEach { el ->
                if (!el.isJsonObject) return@forEach
                val o = el.asJsonObject
                val name = o.str("name") ?: o.str("title") ?: o.str("channel_name") ?: return@forEach
                val url = o.str("url") ?: o.str("play_url") ?: o.str("address") ?: return@forEach
                val group = o.str("group") ?: o.str("category") ?: ""
                result += Channel(name, url, SourceCatalog.categoryOf(name, group), source = source)
            }
        } catch (_: Exception) { }
        return result
    }

    private fun com.google.gson.JsonObject.str(key: String): String? =
        if (has(key) && !get(key).isJsonNull) get(key).asString else null

    /** 合并、归一化、去重：相同频道保留多条线路，按名称分组 */
    fun mergeAndNormalize(channels: List<Channel>, retainDuplicate: Boolean): List<Channel> {
        val grouped = LinkedHashMap<String, MutableList<Channel>>()
        channels.forEach { ch ->
            if (ch.url.isBlank()) return@forEach
            val key = SourceCatalog.canonicalName(ch.name)
            val g = grouped.getOrPut(key) { mutableListOf() }
            if (g.none { it.url == ch.url }) g += ch
        }
        val out = mutableListOf<Channel>()
        grouped.forEach { (_, list) ->
            list.sortWith(
                compareBy<Channel> { it.status != Channel.STATUS_OK }
                    .thenBy { it.latencyMs }
                    .thenBy { it.url }
            )
            if (retainDuplicate) {
                out.addAll(list.take(8))
            } else {
                out.add(list.first())
            }
        }
        return out
    }
}
