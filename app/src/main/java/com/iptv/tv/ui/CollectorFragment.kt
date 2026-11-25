package com.iptv.tv.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.iptv.tv.R
import com.iptv.tv.core.ChannelChecker
import com.iptv.tv.core.Exporter
import com.iptv.tv.core.SourceCollector
import com.iptv.tv.data.Prefs
import com.iptv.tv.data.SourceCatalog
import com.iptv.tv.model.Channel
import com.iptv.tv.model.CollectProgress
import com.iptv.tv.model.ExportFormat
import com.iptv.tv.model.SourceConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CollectorFragment : Fragment() {

    private lateinit var btnCollect: MaterialButton
    private lateinit var btnCheck: MaterialButton
    private lateinit var tvProgress: TextView
    private lateinit var tvSummary: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerSources: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var tvDir: TextView
    private lateinit var tvStatChannels: TextView
    private lateinit var tvStatOk: TextView
    private lateinit var tvStatSources: TextView
    private lateinit var sourceAdapter: SourceAdapter
    private var collector: SourceCollector? = null
    private var job: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_collect, container, false)
        btnCollect = v.findViewById(R.id.btnCollect)
        btnCheck = v.findViewById(R.id.btnCheck)
        tvProgress = v.findViewById(R.id.tvProgress)
        tvSummary = v.findViewById(R.id.tvSummary)
        progressBar = v.findViewById(R.id.progressBar)
        recyclerSources = v.findViewById(R.id.recyclerSources)
        swipe = v.findViewById(R.id.swipe)
        tvDir = v.findViewById(R.id.tvDir)
        tvStatChannels = v.findViewById(R.id.tvStatChannels)
        tvStatOk = v.findViewById(R.id.tvStatOk)
        tvStatSources = v.findViewById(R.id.tvStatSources)

        setupSources()
        updateDirLabel()
        updateSummary()

        btnCollect.setOnClickListener { startCollect() }
        btnCheck.setOnClickListener { startCheck() }
        swipe.setOnRefreshListener { startCollect() }
        v.findViewById<MaterialButton>(R.id.btnDiagnosis).setOnClickListener { showDiagnosis() }

        v.findViewById<MaterialButton>(R.id.btnAddSource).setOnClickListener { editSource(null) }
        v.findViewById<MaterialButton>(R.id.btnResetSources).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("恢复默认源")
                .setMessage("将清空自定义源并恢复内置清单，确定吗？")
                .setPositiveButton(R.string.ok) { _, _ ->
                    Prefs.resetSources(requireContext())
                    setupSources()
                }.setNegativeButton(R.string.cancel, null).show()
        }
        v.findViewById<MaterialButton>(R.id.btnChooseDir).setOnClickListener { chooseDir() }

        val exportMap = mapOf(
            R.id.btnM3u to ExportFormat.M3U,
            R.id.btnM3u8 to ExportFormat.M3U8,
            R.id.btnTxt to ExportFormat.TXT,
            R.id.btnJson to ExportFormat.JSON,
            R.id.btnTvbox to ExportFormat.TVBOX,
            R.id.btnXspf to ExportFormat.XSPF,
            R.id.btnCsv to ExportFormat.CSV
        )
        exportMap.forEach { (id, fmt) ->
            v.findViewById<MaterialButton>(id).setOnClickListener { export(fmt) }
        }
        return v
    }

    private fun setupSources() {
        val list = Prefs.getSources(requireContext())
        sourceAdapter = SourceAdapter(
            list,
            onChanged = { Prefs.saveSources(requireContext(), list) },
            onDelete = { s ->
                list.remove(s)
                Prefs.saveSources(requireContext(), list)
                sourceAdapter.submit(list)
            },
            onEdit = { s -> editSource(s) }
        )
        recyclerSources.layoutManager = LinearLayoutManager(requireContext())
        recyclerSources.adapter = sourceAdapter
        sourceAdapter.submit(list)
    }

    private fun editSource(existing: SourceConfig?) {
        val ctx = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_source, null)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etUrl = view.findViewById<EditText>(R.id.etUrl)
        val etUa = view.findViewById<EditText>(R.id.etUa)
        existing?.let {
            etName.setText(it.name)
            etUrl.setText(it.url)
            etUa.setText(it.userAgent)
        }
        AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) "添加订阅源" else "编辑订阅源")
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = etName.text.toString().ifBlank { "自定义源" }
                val url = etUrl.text.toString().trim()
                if (!url.contains("://")) {
                    Toast.makeText(ctx, "请输入有效的 http(s) 地址", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val list = Prefs.getSources(ctx)
                val type = when {
                    url.contains("tvbox", true) || url.contains("config", true) -> SourceConfig.TYPE_TVBOX
                    url.contains(".txt", true) -> SourceConfig.TYPE_TXT
                    else -> SourceConfig.TYPE_M3U
                }
                if (existing != null) {
                    val idx = list.indexOfFirst { it.name == existing.name && it.url == existing.url }
                    if (idx >= 0) list[idx] = existing.copy(name = name, url = url, type = type, userAgent = etUa.text.toString(), builtIn = false)
                } else {
                    list.add(0, SourceConfig(name, url, type, true, etUa.text.toString(), false))
                }
                Prefs.saveSources(ctx, list)
                setupSources()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startCollect() {
        val ctx = requireContext().applicationContext
        job?.cancel()
        job = lifecycleScope.launch {
            btnCollect.isEnabled = false
            swipe.isRefreshing = true
            try {
                val sources = Prefs.getSources(ctx)
                val c = SourceCollector(ctx)
                collector = c
                val channels = c.collect(sources) { p -> updateProgress(p) }
                // 关键：只替换"本次启用的采集源"对应的频道，其他来源（手动导入直播源 / TVBox源）原样保留。
                // 用户已主动删除的频道（回收站）依然过滤，避免采集后又加回。
                val removed = Prefs.getRemovedKeys(ctx)
                val collectedKeys = sources.filter { it.enabled }.map { src ->
                    val kind = if (src.type == SourceConfig.TYPE_TVBOX) Channel.ORIGIN_TVBOX else Channel.ORIGIN_COLLECT
                    "$kind|${src.name}|${src.url}"
                }.toSet()
                val existing = Prefs.getChannels(ctx)
                val untouched = existing.filterNot { collectedKeys.contains("${it.originKind}|${it.originKey}") }
                val freshKept = channels.filterNot { removed.contains(it.identity) }
                val merged = (untouched + freshKept).distinctBy { it.identity }
                Prefs.saveChannels(ctx, merged)
                updateSummary()

                if (Prefs.getBool(ctx, Prefs.KEY_AUTO_CHECK, true) && freshKept.isNotEmpty()) {
                    // 在同一协程里直接执行检测，避免 startCheck() 内部 job?.cancel() 取消自身导致协程异常。
                    // 传入本次采集频道作为检测范围，无效源清理不会波及手动导入的直播源 / TVBox源。
                    doCheck(ctx, freshKept.map { it.identity }.toSet())
                } else {
                    safeToast(ctx, "采集完成：更新 ${freshKept.size} 个频道，其他来源 ${untouched.size} 个已保留")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户主动取消，正常退出
            } catch (e: Exception) {
                safeToast(ctx, "采集失败：${e.message?.take(50)}")
            } finally {
                if (isAdded) {
                    btnCollect.isEnabled = true
                    swipe.isRefreshing = false
                }
            }
        }
    }

    private fun startCheck() {
        val ctx = requireContext().applicationContext
        val channels = Prefs.getChannels(ctx)
        if (channels.isEmpty()) {
            safeToast(ctx, "请先采集或导入频道")
            return
        }
        job?.cancel()
        job = lifecycleScope.launch { doCheck(ctx) }
    }

    /**
     * 执行可用性检测（suspend，供采集后自动检测与手动检测共用）。
     * @param scope 仅检测这些 identity 的频道；null 表示检测全部。
     *              采集后的自动检测只传本次采集频道，确保清理无效源时不误删手动导入的直播源 / TVBox源。
     */
    private suspend fun doCheck(ctx: android.content.Context, scope: Set<String>? = null) {
        if (!isAdded) return
        btnCheck.isEnabled = false
        try {
            val all = Prefs.getChannels(ctx)
            if (all.isEmpty()) return
            val targets = if (scope == null) all else all.filter { scope.contains(it.identity) }
            if (targets.isEmpty()) return
            val checker = ChannelChecker(ctx)
            val checked = checker.check(targets) { p -> updateProgress(p) }
            val checkedIds = checked.map { it.identity }.toSet()
            val valid = checked.filter { it.status == Channel.STATUS_OK }
            val invalidCount = checked.size - valid.size
            val purge = Prefs.getBool(ctx, Prefs.KEY_PURGE_INVALID, true)
            // 把本次检测结果合并回全量列表：未参与检测的频道（其他来源）保持原样不被改动
            val byIdentity = checked.associateBy { it.identity }
            val merged = all.map { byIdentity[it.identity] ?: it }.toMutableList()
            when {
                // 检测后有可用源：只清除本次检测范围内确认失效的源，其他来源完全不受影响
                purge && invalidCount > 0 && valid.isNotEmpty() -> {
                    merged.removeAll { checkedIds.contains(it.identity) && it.status == Channel.STATUS_FAIL }
                    Prefs.saveChannels(ctx, merged)
                    safeToast(ctx, "检测完成：保留 ${valid.size} 个有效源，已清除 $invalidCount 个无效源（其他来源未改动）")
                }
                // 全部无效：多半是网络波动或误判，保留原列表避免数据全丢，交给用户判断
                purge && invalidCount > 0 && valid.isEmpty() -> {
                    Prefs.saveChannels(ctx, merged)
                    safeToast(ctx, "本次检测没有可用源，可能是网络异常，已保留原列表")
                }
                else -> {
                    Prefs.saveChannels(ctx, merged)
                    safeToast(ctx, "检测完成：可用 ${valid.size} 个，无效 $invalidCount 个")
                }
            }
            updateSummary()
        } catch (e: kotlinx.coroutines.CancellationException) {
        } catch (e: Exception) {
            safeToast(ctx, "检测失败：${e.message?.take(50)}")
        } finally {
            if (isAdded) btnCheck.isEnabled = true
        }
    }

    /** 线程安全地弹出 Toast */
    private fun safeToast(ctx: android.content.Context, msg: String) {
        val activity = activity ?: return
        activity.runOnUiThread {
            try { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
    }

    /**
     * 采集/检测的进度回调来自 Dispatchers.IO 线程，必须切回主线程更新 UI，
     * 否则会抛 CalledFromWrongThreadException 导致闪退。
     */
    private fun updateProgress(p: CollectProgress) {
        if (!isAdded || context == null) return
        val activity = activity ?: return
        activity.runOnUiThread {
            if (!isAdded || context == null) return@runOnUiThread
            try {
                tvProgress.text = "${p.stage} · ${p.message}"
                if (p.total > 0) progressBar.progress = (p.done * 100 / p.total).coerceIn(0, 100)
            } catch (_: Exception) {}
        }
    }

    private fun updateSummary() {
        if (!isAdded || context == null) return
        try {
            val ctx = requireContext()
            val list = Prefs.getChannels(ctx)
            val ok = list.count { it.isOk }
            val fail = list.count { it.status == com.iptv.tv.model.Channel.STATUS_FAIL }
            tvStatChannels.text = list.size.toString()
            tvStatOk.text = ok.toString()
            tvStatSources.text = Prefs.getSources(ctx).count { it.enabled }.toString()
            tvSummary.text = "共 ${list.size} 个频道 · 可用 $ok · 失效 $fail"
        } catch (_: Exception) {}
    }

    /** 展示每个数据源的采集诊断信息 */
    private fun showDiagnosis() {
        val results = collector?.getLastResults() ?: emptyList()
        if (results.isEmpty()) {
            Toast.makeText(requireContext(), "请先执行一次采集", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        val okCount = results.count { it.success }
        sb.append("成功 $okCount / ${results.size} 个数据源\n\n")
        results.sortedBy { it.success }.forEach { r ->
            val icon = if (r.success) "✓" else "✕"
            val info = if (r.success) "+${r.count} 频道 · ${r.usedMirror}" else r.detail
            sb.append("$icon ${r.name}\n   $info\n")
        }
        AlertDialog.Builder(requireContext())
            .setTitle("采集诊断详情")
            .setMessage(sb.toString())
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun chooseDir() {
        // OPEN_DOCUMENT_TREE 是 API 21 引入，Android 4.4 用默认内部存储目录
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.widget.Toast.makeText(requireContext(), "Android 4.4 使用默认内部存储目录（Download/IPTV）", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(intent, REQ_DIR)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DIR && resultCode == Activity.RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // 部分机型不支持持久化授权，仅本次会话有效，仍可继续使用
            }
            Prefs.setTreeUri(requireContext(), uri.toString())
            updateDirLabel()
        }
    }

    private fun updateDirLabel() {
        val uri = Prefs.getTreeUri(requireContext())
        tvDir.text = if (uri == null) getString(R.string.dir_not_set) else "下载目录：$uri"
    }

    private fun export(format: ExportFormat) {
        val ctx = requireContext().applicationContext
        // 只导出有效源：明确检测失败的源一律排除；未检测的源（UNKNOWN）保留，
        // 用户可在「可用性检测」后再次导出，即可得到纯有效源列表。
        val channels = Prefs.getChannels(ctx).filter { it.status != Channel.STATUS_FAIL }
        if (channels.isEmpty()) {
            safeToast(ctx, "没有有效源可导出，请先做可用性检测")
            return
        }
        if (Prefs.getTreeUri(ctx) == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            safeToast(ctx, "请先在设置或采集页选择下载目录")
            chooseDir()
            return
        }
        // Android 4.4 无 treeUri 时走降级导出到 Download/IPTV
        safeToast(ctx, "正在导出，请稍候…")
        lifecycleScope.launch {
            try {
                val result = Exporter(ctx).export(channels, format)
                result.fold(
                    onSuccess = { uri ->
                        safeToast(ctx, "已导出 ${channels.size} 个有效源：${uri.lastPathSegment ?: "文件"}")
                    },
                    onFailure = { e -> safeToast(ctx, getString(R.string.export_fail, e.message ?: "未知错误")) }
                )
            } catch (e: Exception) {
                safeToast(ctx, "导出异常：${e.message?.take(50)}")
            }
        }
    }

    override fun onDestroyView() {
        job?.cancel()
        super.onDestroyView()
    }

    companion object {
        private const val REQ_DIR = 1001
    }
}
