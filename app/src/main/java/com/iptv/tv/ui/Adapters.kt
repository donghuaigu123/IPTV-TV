package com.iptv.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.CheckBox
import androidx.core.content.ContextCompat
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.iptv.tv.R
import com.iptv.tv.model.Channel
import com.iptv.tv.model.SourceConfig

class ChannelAdapter(
    private val items: MutableList<Channel>,
    private val onPlay: (Channel) -> Unit,
    private val onLongClick: (Channel) -> Unit = {},
    private val onDelete: (Channel) -> Unit = {}
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    var darkTheme = false
    /** 管理模式：显示勾选框、隐藏播放/删除按钮，点击条目切换勾选 */
    private var manageMode = false
    private val selected = mutableSetOf<String>()

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        init {
            // TV 遥控器：卡片获得焦点时放大并高亮，给出明确的焦点反馈
            v.setOnFocusChangeListener { view, hasFocus ->
                val scale = if (hasFocus) 1.03f else 1.0f
                view.animate()
                    .scaleX(scale).scaleY(scale)
                    .setDuration(140)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
                view.elevation = if (hasFocus) 8f else 0f
            }
        }
        val name: TextView = v.findViewById(R.id.tvName)
        val group: TextView = v.findViewById(R.id.tvGroup)
        val origin: TextView = v.findViewById(R.id.tvOrigin)
        val url: TextView = v.findViewById(R.id.tvUrl)
        val status: TextView = v.findViewById(R.id.tvStatus)
        val play: ImageButton = v.findViewById(R.id.btnPlay)
        val del: ImageButton = v.findViewById(R.id.btnDelete)
        val keep: CheckBox = v.findViewById(R.id.cbKeep)
        val logoText: TextView? = v.findViewById(R.id.tvLogoText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.name.text = ch.name
        holder.group.text = buildString {
            append(ch.group)
            if (ch.latencyMs > 0) append(" · ${ch.latencyMs}ms")
            if (ch.speedKBps > 0) append(" · ${ch.speedKBps}KB/s")
        }
        holder.url.text = ch.url
        // 来源标签：网络采集源 / 网络直播源 / TVBox源（多订阅带 ①②③ 后缀）
        val originText = ch.originLabel.ifBlank { com.iptv.tv.core.SourceOrigin.kindName(ch.originKind) }
        holder.origin.text = originText
        holder.origin.visibility = if (originText.isBlank() || originText == com.iptv.tv.model.Channel.ORIGIN_NONE)
            View.GONE else View.VISIBLE
        val ctx = holder.itemView.context
        // 状态徽章
        val (statusText, statusColor, statusBg) = when (ch.status) {
            Channel.STATUS_OK -> Triple(
                if (ch.latencyMs > 0) "可用 ${ch.latencyMs}ms" else "可用",
                ContextCompat.getColor(ctx, R.color.success), R.drawable.bg_status_ok
            )
            Channel.STATUS_FAIL -> Triple("失效", ContextCompat.getColor(ctx, R.color.error), R.drawable.bg_status_fail)
            else -> Triple("未检测", ContextCompat.getColor(ctx, R.color.text_secondary), R.drawable.bg_status_unknown)
        }
        holder.status.text = statusText
        holder.status.setTextColor(statusColor)
        holder.status.setBackgroundResource(statusBg)
        // 台标首字
        holder.logoText?.text = ch.name.trim().take(2).ifBlank { "TV" }
        // 深色主题（播放器抽屉）下调整文字
        if (darkTheme) {
            val white = ContextCompat.getColor(ctx, android.R.color.white)
            holder.name.setTextColor(white)
            holder.url.setTextColor(white)
        }
        // 管理模式：显示勾选框，隐藏播放/删除按钮；普通模式反之
        holder.keep.visibility = if (manageMode) View.VISIBLE else View.GONE
        holder.play.visibility = if (manageMode) View.GONE else View.VISIBLE
        holder.del.visibility = if (manageMode) View.GONE else View.VISIBLE
        holder.keep.setOnCheckedChangeListener(null)
        holder.keep.isChecked = selected.contains(ch.identity)
        holder.keep.setOnCheckedChangeListener { _, checked ->
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos < items.size) {
                val key = items[pos].identity
                if (checked) selected.add(key) else selected.remove(key)
            }
        }
        holder.play.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos < items.size) onPlay(items[pos])
        }
        holder.del.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos < items.size) onDelete(items[pos])
        }
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION || pos >= items.size) return@setOnClickListener
            if (manageMode) {
                holder.keep.isChecked = !holder.keep.isChecked
            } else {
                onPlay(items[pos])
            }
        }
        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos < items.size) onLongClick(items[pos])
            true
        }
    }

    override fun getItemCount() = items.size

    fun submit(list: List<Channel>) {
        items.clear(); items.addAll(list)
        // 清除已不在列表中的勾选，避免残留
        val valid = list.map { it.identity }.toSet()
        selected.retainAll(valid)
        notifyDataSetChanged()
    }

    fun setManageMode(on: Boolean) {
        manageMode = on
        if (!on) selected.clear()
        notifyDataSetChanged()
    }

    fun selectedCount() = selected.size

    fun selectedOnes(): List<Channel> = items.filter { selected.contains(it.identity) }

    fun unselectedOnes(): List<Channel> = items.filter { !selected.contains(it.identity) }

    fun selectAll() {
        selected.addAll(items.map { it.identity }); notifyDataSetChanged()
    }

    fun selectNone() {
        selected.clear(); notifyDataSetChanged()
    }

    fun invertSelection() {
        val next = items.map { it.identity }.filter { !selected.contains(it) }.toSet()
        selected.clear(); selected.addAll(next); notifyDataSetChanged()
    }
}

class SourceAdapter(
    private val items: MutableList<SourceConfig>,
    private val onChanged: () -> Unit,
    private val onDelete: (SourceConfig) -> Unit,
    private val onEdit: (SourceConfig) -> Unit
) : RecyclerView.Adapter<SourceAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cb: CheckBox = v.findViewById(R.id.cbEnabled)
        val name: TextView = v.findViewById(R.id.tvName)
        val url: TextView = v.findViewById(R.id.tvUrl)
        val del: ImageButton = v.findViewById(R.id.btnDelete)
        val edit: ImageButton = v.findViewById(R.id.btnEdit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_source, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.name.text = s.name
        holder.url.text = s.url
        // 先移除旧监听，避免复用时 setChecked 触发上一次绑定的监听
        holder.cb.setOnCheckedChangeListener(null)
        holder.cb.isChecked = s.enabled
        holder.cb.setOnCheckedChangeListener { _, b ->
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos < items.size) {
                items[pos] = s.copy(enabled = b)
                onChanged()
            }
        }
        holder.del.setOnClickListener { onDelete(s) }
        holder.edit.setOnClickListener { onEdit(s) }
    }

    override fun getItemCount() = items.size

    fun submit(list: List<SourceConfig>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }
}
