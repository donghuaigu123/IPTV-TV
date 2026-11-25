package com.iptv.tv.model

/** 一个直播频道 / 播放地址 */
data class Channel(
    val name: String,
    val url: String,
    val group: String = "",
    val logo: String = "",
    val epg: String = "",
    val source: String = "",
    val tvgId: String = "",
    val rawLine: String = "",
    val userAgent: String = "",
    /** 来源大类：网络采集源 / 网络直播源 / TVBox源 */
    val originKind: String = "",
    /** 来源实例标识：区分同一来源类型下的多个订阅（如多个 TVBox 配置） */
    val originKey: String = "",
    val originLabel: String = "",
    var status: Int = STATUS_UNKNOWN,
    var latencyMs: Long = 0,
    var speedKBps: Long = 0,
    var resolution: String = "",
    var failReason: String = ""
) {
    companion object {
        const val STATUS_UNKNOWN = 0
        const val STATUS_OK = 1
        const val STATUS_FAIL = 2

        // 来源大类
        const val ORIGIN_COLLECT = "collect"   // 网络采集源（采集页抓取）
        const val ORIGIN_LIVE = "live"         // 网络直播源（直播源导入）
        const val ORIGIN_TVBOX = "tvbox"       // TVBox 源（TVBox 导入）

        const val ORIGIN_NONE = "其他"
    }

    val isOk get() = status == STATUS_OK
    /** 频道唯一标识：名称+地址，用于删除/收藏/去重的稳定判断 */
    val identity get() = "$name\u0000$url"
}

/** 内置 / 用户自定义的订阅源 */
data class SourceConfig(
    val name: String,
    val url: String,
    val type: String = TYPE_M3U,
    val enabled: Boolean = true,
    val userAgent: String = "",
    val builtIn: Boolean = true
) {
    companion object {
        const val TYPE_M3U = "m3u"
        const val TYPE_TXT = "txt"
        const val TYPE_TVBOX = "tvbox"
        const val TYPE_JSON = "json"
    }
}

/** 采集进度与统计 */
data class CollectProgress(
    val stage: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val message: String = ""
)

/** 导出格式 */
enum class ExportFormat(val ext: String, val label: String, val mime: String) {
    M3U("m3u", "M3U 播放列表", "application/vnd.apple.mpegurl"),
    M3U8("m3u8", "M3U8 播放列表", "application/vnd.apple.mpegurl"),
    TXT("txt", "TXT 通用格式", "text/plain"),
    JSON("json", "JSON 数据", "application/json"),
    TVBOX("json", "TVBox 配置", "application/json"),
    XSPF("xspf", "XSPF 播放列表", "application/xspf+xml"),
    CSV("csv", "CSV 表格", "text/csv")
}

/** TVBox 配置中的一条直播订阅（url 可能为直接流地址，也可能是需展开的订阅列表） */
data class TvBoxLive(
    val name: String,
    val url: String,
    val ua: String = "",
    val logo: String = "",
    val epg: String = "",
    val group: String = ""
)

/** TVBox 站点配置 */
data class TvBoxSite(
    val name: String,
    val url: String,
    val type: Int = 0,
    val api: String = "",
    val searchable: Int = 0,
    val categories: List<String> = emptyList()
)
