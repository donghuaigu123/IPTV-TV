package com.iptv.tv.core

import com.iptv.tv.model.Channel

/**
 * 来源分类与显示：把频道按来源大类（网络采集源 / 网络直播源 / TVBox源）分组，
 * 同一大类下有多个订阅时用 ①②③ 后缀编号区分，并生成可读的来源标签。
 *
 * 设计要点：
 * - 每条频道持久化 originKind（大类）+ originKey（订阅实例唯一标识），label 由 [decorate] 动态计算
 * - 编号稳定：按该大类下订阅首次出现的顺序分配，不依赖存储，重排后结果一致
 * - 兼容老数据：originKey 为空时按 source 文本推断归类
 */
object SourceOrigin {

    private val CIRCLED = charArrayOf(
        '①','②','③','④','⑤','⑥','⑦','⑧','⑨','⑩',
        '⑪','⑫','⑬','⑭','⑮','⑯','⑰','⑱','⑲','⑳'
    )

    private fun suffix(index: Int): String =
        if (index in CIRCLED.indices) CIRCLED[index].toString() else "(${index + 1})"

    /** 大类中文名 */
    fun kindName(kind: String): String = when (kind) {
        Channel.ORIGIN_COLLECT -> "网络采集源"
        Channel.ORIGIN_LIVE -> "网络直播源"
        Channel.ORIGIN_TVBOX -> "TVBox源"
        else -> Channel.ORIGIN_NONE
    }

    /** 大类排序权重：采集 / 直播 / TVBox / 其他 */
    fun kindOrder(kind: String): Int = when (kind) {
        Channel.ORIGIN_COLLECT -> 0
        Channel.ORIGIN_LIVE -> 1
        Channel.ORIGIN_TVBOX -> 2
        else -> 9
    }

    /** 老数据推断：根据 source 文本判断大类 */
    fun inferKind(source: String): String = when {
        source.startsWith("TVBox", true) || source.contains("TVBox:", true) -> Channel.ORIGIN_TVBOX
        source.startsWith("导入:", true) || source.startsWith("文件导入", true) ||
            source.startsWith("手动导入", true) -> Channel.ORIGIN_LIVE
        else -> Channel.ORIGIN_COLLECT
    }

    /** 确保每条频道都有来源标记；老数据按 source 推断补齐 */
    fun ensure(channels: List<Channel>): List<Channel> = channels.map { ch ->
        if (ch.originKind.isNotBlank()) ch
        else {
            val kind = inferKind(ch.source)
            ch.copy(originKind = kind, originKey = ch.source.ifBlank { kind })
        }
    }

    /**
     * 计算每个订阅实例的显示标签。
     * 同一大类下只有一个订阅时不加后缀；多个订阅时按首次出现顺序加 ①②③。
     * 返回 Map<"kind|originKey", 标签>。
     */
    fun labelMap(channels: List<Channel>): Map<String, String> {
        val ensured = ensure(channels)
        // kind -> 该大类下不同 originKey 的首次出现顺序
        val orderByKind = mutableMapOf<String, MutableList<String>>()
        ensured.forEach { ch ->
            val kind = ch.originKind
            val key = ch.originKey.ifBlank { kind }
            val list = orderByKind.getOrPut(kind) { mutableListOf() }
            if (!list.contains(key)) list.add(key)
        }
        val out = mutableMapOf<String, String>()
        orderByKind.forEach { (kind, keys) ->
            val multi = keys.size > 1
            keys.forEachIndexed { idx, key ->
                val label = if (multi) "${kindName(kind)}${suffix(idx)}" else kindName(kind)
                out["$kind|$key"] = label
            }
        }
        return out
    }

    /**
     * 重新计算并写回所有频道的 originLabel，便于列表展示与导出都带上正确来源标签。
     */
    fun decorate(channels: List<Channel>): List<Channel> {
        val ensured = ensure(channels)
        val labels = labelMap(ensured)
        return ensured.map { ch ->
            val key = ch.originKey.ifBlank { ch.originKind }
            val label = labels["${ch.originKind}|$key"] ?: kindName(ch.originKind)
            if (ch.originLabel == label) ch else ch.copy(originLabel = label)
        }
    }

    /** 给一批新频道打上来源标记（写入点调用） */
    fun tag(channels: List<Channel>, kind: String, originKey: String): List<Channel> =
        channels.map { ch ->
            ch.copy(
                originKind = kind,
                originKey = originKey.ifBlank { kind },
                originLabel = kindName(kind)
            )
        }

    /** 按大类 + 订阅实例分组，供频道页来源 chip 使用。返回 (kind, key, label, count) 列表，已排序 */
    fun groups(channels: List<Channel>): List<OriginGroup> {
        val decorated = decorate(channels)
        val labels = labelMap(decorated)
        val grouped = decorated.groupBy { "${it.originKind}|${it.originKey.ifBlank { it.originKind }}" }
        return grouped.map { (k, list) ->
            val first = list.first()
            OriginGroup(
                kind = first.originKind,
                key = first.originKey.ifBlank { first.originKind },
                label = labels[k] ?: kindName(first.originKind),
                count = list.size
            )
        }.sortedWith(compareBy({ kindOrder(it.kind) }, { it.label }))
    }

    data class OriginGroup(
        val kind: String,
        val key: String,
        val label: String,
        val count: Int
    ) {
        val id get() = "$kind|$key"
    }
}
