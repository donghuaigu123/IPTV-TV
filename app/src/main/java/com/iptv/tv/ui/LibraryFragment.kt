package com.iptv.tv.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.animation.AccelerateDecelerateInterpolator
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.iptv.tv.R
import com.iptv.tv.core.SourceCollector
import com.iptv.tv.core.SourceOrigin
import com.iptv.tv.core.TvBoxImporter
import com.iptv.tv.data.PlaylistBus
import com.iptv.tv.data.Prefs
import com.iptv.tv.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class LibraryFragment : Fragment() {

    private lateinit var etSearch: EditText
    private lateinit var recycler: RecyclerView
    private lateinit var chipGroups: ChipGroup
    private lateinit var chipOrigins: ChipGroup
    private lateinit var adapter: ChannelAdapter
    private lateinit var emptyState: View
    private var allChannels: List<Channel> = emptyList()
    private var visibleChannels: List<Channel> = emptyList()
    private var currentGroup = "全部"
    private var currentOrigin = "all"   // all 或 SourceOrigin.OriginGroup.id
    private var manageMode = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_library, container, false)
        etSearch = v.findViewById(R.id.etSearch)
        recycler = v.findViewById(R.id.recyclerChannels)
        chipGroups = v.findViewById(R.id.chipGroups)
        chipOrigins = v.findViewById(R.id.chipOrigins)
        emptyState = v.findViewById(R.id.emptyState)

        adapter = ChannelAdapter(
            mutableListOf(),
            onPlay = { ch ->
                val idx = visibleChannels.indexOfFirst { it.identity == ch.identity }
                openPlayer(visibleChannels, if (idx >= 0) idx else 0)
            },
            onLongClick = { ch -> showChannelMenu(ch) },
            onDelete = { ch -> confirmDelete(ch) }
        )
        // TV 横屏：网格列数按可用宽度动态计算，小盒子 2 列、大屏电视最多 6 列
        recycler.layoutManager = GridLayoutManager(requireContext(), 2)
        recycler.adapter = adapter
        recycler.post { updateSpanCount() }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filter() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        v.findViewById<MaterialButton>(R.id.btnImport).setOnClickListener { showImportDialog() }
        v.findViewById<MaterialButton>(R.id.btnImportTvbox).setOnClickListener { showTvBoxImportDialog() }
        v.findViewById<View>(R.id.fabPlayAll).setOnClickListener {
            if (manageMode) { toggleManageMode(v); return@setOnClickListener }
            if (visibleChannels.isNotEmpty()) openPlayer(visibleChannels, 0)
            else Toast.makeText(requireContext(), "当前筛选下暂无频道", Toast.LENGTH_SHORT).show()
        }

        v.findViewById<MaterialButton>(R.id.btnManage).setOnClickListener { toggleManageMode(v) }
        v.findViewById<MaterialButton>(R.id.btnRecycle).setOnClickListener { showRecycleBin() }
        v.findViewById<MaterialButton>(R.id.btnSelectAll).setOnClickListener { adapter.selectAll() }
        v.findViewById<MaterialButton>(R.id.btnInvert).setOnClickListener { adapter.invertSelection() }
        v.findViewById<MaterialButton>(R.id.btnKeepSelected).setOnClickListener { keepSelected() }
        v.findViewById<MaterialButton>(R.id.btnDeleteSelected).setOnClickListener { deleteSelected() }

        reload()
        return v
    }

    override fun onResume() {
        super.onResume()
        reload()
        if (::recycler.isInitialized) recycler.post { updateSpanCount() }
    }

    /**
     * 按 RecyclerView 实际可用宽度与卡片最小宽度动态计算列数。
     * 优先使用测量后的真实宽度；未测量时用「屏宽 - 导航宽度」估算。
     * 列数限制在 2~6，避免卡片过窄（遥控器难聚焦）或过宽（浪费大屏空间）。
     */
    private fun updateSpanCount() {
        if (!::recycler.isInitialized || context == null) return
        val minWidth = resources.getDimensionPixelSize(R.dimen.grid_min_width)
        val available = if (recycler.width > 0) {
            recycler.width
        } else {
            val dm = resources.displayMetrics
            (dm.widthPixels - resources.getDimensionPixelSize(R.dimen.nav_width))
                .coerceAtLeast(minWidth)
        }
        val span = (available / minWidth).coerceIn(2, 6)
        (recycler.layoutManager as? GridLayoutManager)?.let { lm ->
            if (lm.spanCount != span) lm.spanCount = span
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded) reload()
    }

    private fun reload() {
        allChannels = Prefs.getChannels(requireContext())
        buildOriginChips()
        buildChips()
        filter()
    }

    /** 按来源大类 + 订阅实例生成 chip，可自由切换；同一大类多订阅带 ①②③ 后缀 */
    private fun buildOriginChips() {
        chipOrigins.removeAllViews()
        val groups = SourceOrigin.groups(allChannels)
        // 若当前选中的来源已不存在，回退到全部
        if (currentOrigin != "all" && groups.none { it.id == currentOrigin }) currentOrigin = "all"

        val allChip = Chip(requireContext()).apply {
            text = getString(R.string.all_sources)
            isCheckable = true
            isChecked = currentOrigin == "all"
            setOnClickListener { currentOrigin = "all"; buildOriginChips(); filter() }
        }
        applyTvFocus(allChip)
        chipOrigins.addView(allChip)

        groups.forEach { g ->
            val chip = Chip(requireContext()).apply {
                text = "${g.label} (${g.count})"
                isCheckable = true
                isChecked = g.id == currentOrigin
                setOnClickListener { currentOrigin = g.id; buildOriginChips(); filter() }
            }
            applyTvFocus(chip)
            chipOrigins.addView(chip)
        }
    }

    /** TV 遥控器：控件获得焦点时放大并描边，给出明确反馈 */
    private fun applyTvFocus(view: View) {
        view.isFocusable = true
        view.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.08f else 1.0f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
        }
    }

    private fun buildChips() {
        chipGroups.removeAllViews()
        // 分组只统计当前来源下的频道，切换来源后分组随之更新
        val scoped = if (currentOrigin == "all") allChannels
        else allChannels.filter { "${it.originKind}|${it.originKey.ifBlank { it.originKind }}" == currentOrigin }
        val groups = listOf("全部") + scoped.map { it.group }.distinct().sorted().take(15)
        if (groups.none { it == currentGroup }) currentGroup = "全部"
        groups.forEach { g ->
            val chip = Chip(requireContext()).apply {
                text = g
                isCheckable = true
                isChecked = g == currentGroup
                setOnClickListener { currentGroup = g; buildChips(); filter() }
            }
            applyTvFocus(chip)
            chipGroups.addView(chip)
        }
    }

    private fun filter() {
        val q = etSearch.text.toString().trim()
        val list = allChannels.filter { ch ->
            (currentOrigin == "all" ||
                "${ch.originKind}|${ch.originKey.ifBlank { ch.originKind }}" == currentOrigin) &&
            (currentGroup == "全部" || ch.group == currentGroup) &&
            (q.isBlank() || ch.name.contains(q, true) || ch.url.contains(q, true))
        }
        visibleChannels = list
        adapter.submit(list)
        emptyState.visibility = if (allChannels.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showChannelMenu(ch: Channel) {
        val ctx = requireContext()
        val favs = Prefs.getFavorites(ctx)
        val isFav = favs.contains(ch.url)
        AlertDialog.Builder(ctx)
            .setTitle(ch.name)
            .setItems(
                arrayOf("播放", if (isFav) "取消收藏" else "收藏", "复制地址", "在外部播放器打开", "删除此频道")
            ) { _, i ->
                when (i) {
                    0 -> openPlayer(listOf(ch), 0)
                    1 -> {
                        if (isFav) favs.remove(ch.url) else favs.add(ch.url)
                        Prefs.saveFavorites(ctx, favs)
                    }
                    2 -> copyText(ch.url)
                    3 -> {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ch.url))
                        intent.setDataAndType(Uri.parse(ch.url), "video/*")
                        try { startActivity(intent) } catch (e: Exception) { Toast.makeText(ctx, "没有可用的外部播放器", Toast.LENGTH_SHORT).show() }
                    }
                    4 -> confirmDelete(ch)
                }
            }.show()
    }

    // ---------------- 保留 / 删除 / 回收站 ----------------

    private fun toggleManageMode(v: View) {
        manageMode = !manageMode
        adapter.setManageMode(manageMode)
        v.findViewById<View>(R.id.manageBar).visibility = if (manageMode) View.VISIBLE else View.GONE
        val btn = v.findViewById<MaterialButton>(R.id.btnManage)
        btn.setText(if (manageMode) R.string.done else R.string.manage)
        v.findViewById<View>(R.id.fabPlayAll).visibility = if (manageMode) View.GONE else View.VISIBLE
    }

    /** 单个频道删除：二次确认后移入回收站 */
    private fun confirmDelete(ch: Channel) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.delete_channel_title)
            .setMessage(getString(R.string.delete_channel_msg, ch.name))
            .setPositiveButton(R.string.delete) { _, _ -> deleteChannels(listOf(ch)) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 把频道从列表移除并存入回收站（持久化，采集/导入不会再加回） */
    private fun deleteChannels(channels: List<Channel>, showToast: Boolean = true) {
        if (channels.isEmpty()) return
        val ctx = requireContext()
        val keys = channels.map { it.identity }.toSet()
        val bin = Prefs.getRecycleBin(ctx)
        bin.addAll(channels.filterNot { bin.any { b -> b.identity == it.identity } })
        Prefs.saveRecycleBin(ctx, bin)
        val remain = Prefs.getChannels(ctx).filterNot { keys.contains(it.identity) }
        Prefs.saveChannels(ctx, remain)
        reload()
        if (showToast) Toast.makeText(ctx, getString(R.string.delete_done, channels.size), Toast.LENGTH_SHORT).show()
    }

    /** 批量：只保留勾选的频道，其余移入回收站 */
    private fun keepSelected() {
        val keep = adapter.selectedOnes()
        if (keep.isEmpty()) {
            Toast.makeText(requireContext(), "请先勾选要保留的频道", Toast.LENGTH_SHORT).show()
            return
        }
        val remove = adapter.unselectedOnes()
        if (remove.isEmpty()) {
            Toast.makeText(requireContext(), "当前已全部勾选", Toast.LENGTH_SHORT).show()
            return
        }
        deleteChannels(remove, showToast = false)
        Toast.makeText(requireContext(), getString(R.string.keep_done, keep.size, remove.size), Toast.LENGTH_SHORT).show()
    }

    /** 批量：删除勾选的频道 */
    private fun deleteSelected() {
        val selected = adapter.selectedOnes()
        if (selected.isEmpty()) {
            Toast.makeText(requireContext(), "请先勾选要删除的频道", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_channel_title)
            .setMessage("确定删除选中的 ${selected.size} 个频道吗？可在回收站恢复。")
            .setPositiveButton(R.string.delete) { _, _ -> deleteChannels(selected) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 回收站：列出已删除频道，可单个恢复、全部恢复或清空 */
    private fun showRecycleBin() {
        val ctx = requireContext()
        val bin = Prefs.getRecycleBin(ctx)
        if (bin.isEmpty()) {
            Toast.makeText(ctx, R.string.recycle_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val names = bin.map { "${it.name}  ·  ${it.group.ifBlank { "未分组" }}" }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.recycle_bin) + "（${bin.size}）")
            .setItems(names) { _, which ->
                restoreChannels(listOf(bin[which]))
            }
            .setPositiveButton(R.string.recycle_restore_all) { _, _ -> restoreChannels(bin.toList()) }
            .setNegativeButton(R.string.recycle_clear) { _, _ ->
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.recycle_clear)
                    .setMessage(R.string.recycle_clear_confirm)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        Prefs.saveRecycleBin(ctx, emptyList())
                        Toast.makeText(ctx, "回收站已清空", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .show()
    }

    /** 从回收站恢复频道：加回列表并从回收站移除 */
    private fun restoreChannels(channels: List<Channel>) {
        if (channels.isEmpty()) return
        val ctx = requireContext()
        val keys = channels.map { it.identity }.toSet()
        val bin = Prefs.getRecycleBin(ctx).filterNot { keys.contains(it.identity) }
        Prefs.saveRecycleBin(ctx, bin)
        val merged = (Prefs.getChannels(ctx) + channels).distinctBy { it.identity }
        Prefs.saveChannels(ctx, merged)
        reload()
        Toast.makeText(ctx, getString(R.string.recycle_restored, channels.size), Toast.LENGTH_SHORT).show()
    }

    private fun copyText(text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("IPTV", text))
        Toast.makeText(requireContext(), "已复制", Toast.LENGTH_SHORT).show()
    }

    /**
     * 合并新导入频道到现有列表，并过滤已在回收站中的频道（删了就不会被导入加回）。
     * 同时给新频道打来源标记，使频道页可按来源分类切换显示。
     */
    private fun mergeChannels(
        ctx: Context,
        incoming: List<Channel>,
        kind: String = Channel.ORIGIN_LIVE,
        originKey: String = "手动导入"
    ): List<Channel> {
        val removed = Prefs.getRemovedKeys(ctx)
        val tagged = SourceOrigin.tag(incoming, kind, originKey)
        return (Prefs.getChannels(ctx) + tagged)
            .filterNot { removed.contains(it.identity) }
            .distinctBy { it.identity }
    }

    private fun showImportDialog() {
        val ctx = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_import, null)
        val etUrl = view.findViewById<EditText>(R.id.etImportUrl)
        val etPaste = view.findViewById<EditText>(R.id.etPaste)
        val dlg = AlertDialog.Builder(ctx).setTitle("导入直播源").setView(view).create()

        view.findViewById<Button>(R.id.btnImportUrl).setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (!url.contains("://")) { Toast.makeText(ctx, "请输入有效地址", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            dlg.dismiss()
            lifecycleScope.launch {
                try {
                    val text = withContext(Dispatchers.IO) { fetchText(url) }
                    if (text.isNullOrBlank()) {
                        Toast.makeText(ctx, "导入失败：无法获取内容", Toast.LENGTH_LONG).show()
                    } else {
                        val chans = SourceCollector(ctx).parseImported(text, "导入:${url.take(30)}")
                        if (chans.isEmpty()) {
                            Toast.makeText(ctx, "导入失败：未识别到有效频道", Toast.LENGTH_LONG).show()
                        } else {
                            val merged = mergeChannels(ctx, chans, Channel.ORIGIN_LIVE, url)
                            Prefs.saveChannels(ctx, merged)
                            reload()
                            Toast.makeText(ctx, getString(R.string.playlist_imported, chans.size), Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(ctx, "导入异常：${e.message?.take(50)}", Toast.LENGTH_LONG).show()
                }
            }
        }

        view.findViewById<Button>(R.id.btnImportFile).setOnClickListener {
            dlg.dismiss()
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
            startActivityForResult(intent, REQ_IMPORT_FILE)
        }

        view.findViewById<Button>(R.id.btnImportPaste).setOnClickListener {
            var text = etPaste.text.toString()
            if (text.isBlank()) {
                try {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                } catch (_: Exception) {}
            }
            if (text.isBlank()) { Toast.makeText(ctx, "没有可导入的内容", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            try {
                val chans = SourceCollector(ctx).parseImported(text, "手动导入")
                if (chans.isEmpty()) { Toast.makeText(ctx, "未识别到有效频道", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                val merged = mergeChannels(ctx, chans, Channel.ORIGIN_LIVE, "剪贴板·${System.currentTimeMillis()}")
                Prefs.saveChannels(ctx, merged)
                reload()
                dlg.dismiss()
                Toast.makeText(ctx, getString(R.string.playlist_imported, chans.size), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "导入异常：${e.message?.take(50)}", Toast.LENGTH_SHORT).show()
            }
        }
        dlg.show()
    }

    /** TVBox / 影视仓配置导入：自动展开 lives 订阅为可播放频道 */
    private fun showTvBoxImportDialog() {
        val ctx = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_tvbox_import, null)
        val etUrl = view.findViewById<EditText>(R.id.etTvBoxUrl)
        val etPaste = view.findViewById<EditText>(R.id.etTvBoxPaste)
        val dlg = AlertDialog.Builder(ctx).setTitle(R.string.import_tvbox).setView(view).create()

        view.findViewById<MaterialButton>(R.id.btnTvBoxUrl).setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (!url.contains("://")) {
                Toast.makeText(ctx, "请输入有效的配置订阅地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dlg.dismiss()
            doTvBoxImport(url, null, "TVBox:${url.take(24)}")
        }

        view.findViewById<MaterialButton>(R.id.btnTvBoxPaste).setOnClickListener {
            var text = etPaste.text.toString()
            if (text.isBlank()) {
                try {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                } catch (_: Exception) {}
            }
            if (text.isBlank()) {
                Toast.makeText(ctx, "没有可导入的配置内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dlg.dismiss()
            doTvBoxImport(null, text, "TVBox:手动导入")
        }
        dlg.show()
    }

    /** 执行 TVBox 导入（URL 或文本），带进度提示 */
    private fun doTvBoxImport(url: String?, text: String?, label: String) {
        val ctx = requireContext()
        val progress = AlertDialog.Builder(ctx).setTitle("正在导入 TVBox")
            .setMessage("正在展开直播订阅，请稍候…").setCancelable(false).create()
        progress.show()
        lifecycleScope.launch {
            try {
                val importer = TvBoxImporter(ctx.applicationContext)
                val result = if (url != null) {
                    importer.importFromUrl(url, label) { p ->
                        progress.setMessage("${p.stage} ${p.done}/${p.total}\n${p.message}")
                    }
                } else {
                    importer.importFromText(text ?: "", url, label) { p ->
                        progress.setMessage("${p.stage} ${p.done}/${p.total}\n${p.message}")
                    }
                }
                withContext(Dispatchers.Main) {
                    try { progress.dismiss() } catch (_: Exception) {}
                    if (result.channels.isEmpty()) {
                        Toast.makeText(ctx, "导入失败：未展开出可播放频道\n请检查配置地址是否有效", Toast.LENGTH_LONG).show()
                    } else {
                        val merged = mergeChannels(
                            ctx, result.channels, Channel.ORIGIN_TVBOX,
                            url ?: "TVBox粘贴·${System.currentTimeMillis()}"
                        )
                        Prefs.saveChannels(ctx, merged)
                        reload()
                        Toast.makeText(
                            ctx,
                            getString(R.string.tvbox_imported, result.channels.size, result.expandedCount),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    try { progress.dismiss() } catch (_: Exception) {}
                    Toast.makeText(ctx, "TVBox 导入异常：${e.message?.take(60)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT_FILE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            val ctx = requireContext().applicationContext
            lifecycleScope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }
                    if (!text.isNullOrBlank()) {
                        val chans = SourceCollector(ctx).parseImported(text, "文件导入")
                        if (chans.isEmpty()) {
                            Toast.makeText(ctx, "未识别到有效频道", Toast.LENGTH_SHORT).show()
                        } else {
                            val merged = mergeChannels(ctx, chans, Channel.ORIGIN_LIVE, "文件·${uri.lastPathSegment ?: System.currentTimeMillis()}")
                            Prefs.saveChannels(ctx, merged)
                            reload()
                            Toast.makeText(ctx, getString(R.string.playlist_imported, chans.size), Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(ctx, "文件导入失败：${e.message?.take(50)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun fetchText(url: String): String? = try {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        val req = Request.Builder().url(url).header("User-Agent", SourceCollector.DEFAULT_UA).build()
        client.newCall(req).execute().use { if (it.isSuccessful) it.body()?.string() else null }
    } catch (e: Exception) { null }

    private fun openPlayer(channels: List<Channel>, index: Int) {
        if (channels.isEmpty()) {
            Toast.makeText(requireContext(), "暂无可播放的频道", Toast.LENGTH_SHORT).show()
            return
        }
        // 通过进程内总线传递，避免超长 JSON 撑爆 Intent Binder 事务（TransactionTooLargeException）
        val safeIndex = index.coerceIn(0, channels.size - 1)
        PlaylistBus.set(channels)
        val intent = Intent(requireContext(), PlayerActivity::class.java)
        intent.putExtra("index", safeIndex)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "无法打开播放器：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val REQ_IMPORT_FILE = 2002
    }
}
