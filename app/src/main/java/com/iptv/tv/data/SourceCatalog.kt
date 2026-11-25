package com.iptv.tv.data

import com.iptv.tv.model.SourceConfig

/**
 * 内置直播源清单 v2。
 *
 * 采集策略（针对国内网络优化，2026-11 实测）：
 * 1. 优先使用国内可直连的聚合源（live.zbds.top / github.io Pages / Guovin 结果页）
 * 2. GitHub Raw 类源自动附带多个加速镜像回退（ghproxy / jsdelivr / ghfast 等）
 * 3. 覆盖面参考 iptv-org（全球最大合集）、iptv-sources、iptv-api、vbskycn、peterhchina 等项目
 *
 * 内置清单版本号：修改源清单时必须递增，触发用户端自动刷新缓存。
 */
object SourceCatalog {

    const val CATALOG_VERSION = 2

    /** GitHub Raw 加速镜像模板：{URL} 会被替换为原始 raw 地址 */
    val rawMirrors = listOf(
        "https://ghproxy.net/{URL}",
        "https://gh-proxy.com/{URL}",
        "https://cdn.jsdelivr.net/gh/{GH_PATH}",
        "https://fastly.jsdelivr.net/gh/{GH_PATH}",
        "https://ghfast.top/{URL}"
    )

    /** 内置源清单——全部经过 2026-11 实测可访问 */
    val builtInSources = listOf(
        // ===== 国内直连聚合源（最稳定，优先）=====
        SourceConfig("zbds 聚合(IPv4)", "https://live.zbds.top/tv/iptv4.m3u"),
        SourceConfig("zbds 聚合(TXT)", "https://live.zbds.top/tv/iptv4.txt", SourceConfig.TYPE_TXT),
        SourceConfig("Guovin 成品源", "https://raw.githubusercontent.com/Guovin/iptv-api/gd/output/ipv4/result.m3u"),
        SourceConfig("peterH 全量V4", "https://peterhchina.github.io/iptv/IPTV-V4.m3u"),
        SourceConfig("peterH 央视卫视V4", "https://peterhchina.github.io/iptv/CNTV-V4.m3u"),
        SourceConfig("peterH 全量V6", "https://peterhchina.github.io/iptv/IPTV-V6.m3u"),
        SourceConfig("m3u.ibert 综合", "https://m3u.ibert.me/"),

        // ===== iptv-org 全球合集（分类齐全，需镜像）=====
        SourceConfig("iptv-org 中国", "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/cn.m3u"),
        SourceConfig("iptv-org 央视", "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/cn_cctv.m3u"),
        SourceConfig("iptv-org 香港", "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/hk.m3u"),
        SourceConfig("iptv-org 台湾", "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/tw.m3u"),
        SourceConfig("iptv-org 日本", "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/jp.m3u"),
        SourceConfig("iptv-org 韩国", "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/kr.m3u"),
        SourceConfig("iptv-org 美国", "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/us.m3u"),
        SourceConfig("iptv-org 英国", "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/gb.m3u"),

        // ===== 社区优质聚合源（需镜像）=====
        SourceConfig("fanmingming IPv4", "https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/itv.m3u"),
        SourceConfig("fanmingming IPv6", "https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/ipv6.m3u"),
        SourceConfig("YueChan/Live", "https://raw.githubusercontent.com/YueChan/Live/main/IPTV.m3u"),
        SourceConfig("YanG 聚合Gather", "https://raw.githubusercontent.com/YanG-1989/m3u/main/Gather.m3u"),
        SourceConfig("YanG 咪咕Migu", "https://raw.githubusercontent.com/YanG-1989/m3u/main/Migu.m3u"),
        SourceConfig("YYKM 云影空蒙", "https://raw.githubusercontent.com/yaoxieyoulei/YYKM_assets/main/webview.m3u")
    )

    /** 常见 EPG 数据源 */
    val epgSources = listOf(
        "https://epg.pw/xmltv/epg_zh.xml",
        "https://peterhchina.github.io/iptv/EPG.xml"
    )

    /** 判断是否为 GitHub Raw 链接（需要镜像回退） */
    fun isGithubRaw(url: String): Boolean =
        url.contains("raw.githubusercontent.com", ignoreCase = true)

    /** 从 raw 地址解析出 user/repo/branch/path，用于 jsdelivr 模板 */
    fun githubPath(url: String): String? {
        val regex = Regex("raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.*)")
        val m = regex.find(url) ?: return null
        return "${m.groupValues[1]}/${m.groupValues[2]}@${m.groupValues[3]}/${m.groupValues[4]}"
    }

    /** 为一个源生成候选地址列表：原始地址 + 各镜像 */
    fun candidateUrls(url: String): List<String> {
        val out = mutableListOf(url)
        if (isGithubRaw(url)) {
            val ghPath = githubPath(url)
            rawMirrors.forEach { tpl ->
                val candidate = when {
                    tpl.contains("{GH_PATH}") && ghPath != null -> tpl.replace("{GH_PATH}", ghPath)
                    tpl.contains("{URL}") -> tpl.replace("{URL}", url)
                    else -> return@forEach
                }
                if (candidate !in out) out += candidate
            }
        }
        return out
    }

    /** 频道别名归一化词表：把同一频道的不同写法统一 */
    val aliasMap: Map<String, String> = buildMap {
        val groups = listOf(
            listOf("CCTV1", "CCTV-1", "央视一套", "CCTV1综合", "CCTV 1", "中央1台", "CCTV1综合频道"),
            listOf("CCTV2", "CCTV-2", "央视二套", "CCTV2财经", "中央2台"),
            listOf("CCTV3", "CCTV-3", "央视三套", "CCTV3综艺", "中央3台"),
            listOf("CCTV4", "CCTV-4", "央视四套", "CCTV4国际", "中央4台"),
            listOf("CCTV5", "CCTV-5", "央视五套", "CCTV5体育", "中央5台"),
            listOf("CCTV5+", "CCTV-5+", "CCTV5plus", "央视体育赛事"),
            listOf("CCTV6", "CCTV-6", "央视六套", "CCTV6电影", "中央6台"),
            listOf("CCTV7", "CCTV-7", "央视七套", "CCTV7国防军事"),
            listOf("CCTV8", "CCTV-8", "央视八套", "CCTV8电视剧"),
            listOf("CCTV9", "CCTV-9", "央视九套", "CCTV9纪录"),
            listOf("CCTV10", "CCTV-10", "央视十套", "CCTV10科教"),
            listOf("CCTV11", "CCTV-11", "央视十一套", "CCTV11戏曲"),
            listOf("CCTV12", "CCTV-12", "央视十二套", "CCTV12社会与法"),
            listOf("CCTV13", "CCTV-13", "央视十三套", "CCTV13新闻"),
            listOf("CCTV14", "CCTV-14", "央视十四套", "CCTV14少儿"),
            listOf("CCTV15", "CCTV-15", "央视十五套", "CCTV15音乐"),
            listOf("湖南卫视", "湖南TV", "HunanTV", "Hunan Television"),
            listOf("浙江卫视", "浙江TV", "ZhejiangTV", "Zhejiang Television"),
            listOf("江苏卫视", "江苏TV", "JiangsuTV", "Jiangsu Television"),
            listOf("东方卫视", "上海东方卫视", "DragonTV", "Shanghai Television"),
            listOf("广东卫视", "广东TV", "GuangdongTV", "Guangdong Television"),
            listOf("北京卫视", "北京TV", "BeijingTV", "Beijing Television"),
            listOf("山东卫视", "山东TV", "ShandongTV", "Shandong Television"),
            listOf("凤凰卫视", "凤凰卫视中文台", "PhoenixTV", "Phoenix Chinese")
        )
        groups.forEach { names ->
            val canonical = names.first()
            names.forEach { put(normalizeKey(it), canonical) }
        }
    }

    private fun normalizeKey(s: String): String =
        s.trim().uppercase().replace(Regex("[\\s\\-_·.（）()【】\\[\\]]"), "")

    /** 返回频道的标准名称，用于合并去重 */
    fun canonicalName(raw: String): String {
        val key = normalizeKey(raw)
        if (key.isEmpty()) return raw.trim()
        return aliasMap[key] ?: key
    }

    /** 频道分类，用于列表分组与导出分组 */
    fun categoryOf(name: String, group: String): String {
        val n = normalizeKey(name)
        val g = normalizeKey(group)
        return when {
            n.contains("CCTV") || g.contains("央视") || g.contains("CCTV") -> "央视"
            n.contains("卫视") || g.contains("卫视") -> "卫视"
            n.contains("凤凰") -> "港澳台"
            g.contains("HK") || n.contains("香港") || g.contains("港") -> "港澳台"
            g.contains("TW") || n.contains("台湾") || g.contains("台") -> "港澳台"
            n.contains("体育") || g.contains("SPORT") -> "体育"
            n.contains("电影") || g.contains("MOVIE") -> "电影"
            n.contains("纪录") || g.contains("DOCUMENT") -> "纪录"
            n.contains("少儿") || n.contains("卡通") || g.contains("少儿") -> "少儿"
            n.contains("新闻") || g.contains("NEWS") -> "新闻"
            else -> group.ifBlank { "其他" }
        }
    }
}
