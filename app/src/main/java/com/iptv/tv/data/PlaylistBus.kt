package com.iptv.tv.data

import com.iptv.tv.model.Channel

/**
 * 进程内播放列表传递总线。
 *
 * 背景：频道列表采集后可达数千条，序列化为 JSON 后往往超过 1MB，
 * 若通过 Intent.putExtra 传递会触发 Binder 事务上限（TransactionTooLargeException），
 * 导致点击播放时直接闪退。这里改为进程内单例持有，Intent 只传一个下标。
 */
object PlaylistBus {
    private var channels: List<Channel> = emptyList()

    fun set(list: List<Channel>) {
        channels = list
    }

    fun get(): List<Channel> = channels

    fun clear() {
        channels = emptyList()
    }

    /** 外部以 m3u/m3u8 链接唤起时，待导入的地址 */
    var pendingImportUrl: String? = null
}
