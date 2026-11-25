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
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.iptv.tv.R
import com.iptv.tv.data.Prefs

class SettingsFragment : Fragment() {

    private lateinit var etTimeout: EditText
    private lateinit var etConcurrency: EditText
    private lateinit var swAutoCheck: SwitchMaterial
    private lateinit var swPurgeInvalid: SwitchMaterial
    private lateinit var swRetainDup: SwitchMaterial
    private lateinit var spDecoder: Spinner
    private lateinit var spBuffer: Spinner
    private lateinit var tvDir: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_settings, container, false)
        etTimeout = v.findViewById(R.id.etTimeout)
        etConcurrency = v.findViewById(R.id.etConcurrency)
        swAutoCheck = v.findViewById(R.id.swAutoCheck)
        swPurgeInvalid = v.findViewById(R.id.swPurgeInvalid)
        swRetainDup = v.findViewById(R.id.swRetainDup)
        spDecoder = v.findViewById(R.id.spDecoder)
        spBuffer = v.findViewById(R.id.spBuffer)
        tvDir = v.findViewById(R.id.tvDir)

        val ctx = requireContext()
        etTimeout.setText(Prefs.getInt(ctx, Prefs.KEY_TIMEOUT, 8).toString())
        etConcurrency.setText(Prefs.getInt(ctx, Prefs.KEY_CONCURRENCY, 24).toString())
        swAutoCheck.isChecked = Prefs.getBool(ctx, Prefs.KEY_AUTO_CHECK, true)
        swPurgeInvalid.isChecked = Prefs.getBool(ctx, Prefs.KEY_PURGE_INVALID, true)
        swRetainDup.isChecked = Prefs.getBool(ctx, Prefs.KEY_RETAIN_DUP, true)
        spDecoder.setSelection(Prefs.getInt(ctx, Prefs.KEY_DECODER, 0).coerceIn(0, 2))
        spBuffer.setSelection(Prefs.getInt(ctx, Prefs.KEY_BUFFER, 0).coerceIn(0, 2))
        updateDir()

        v.findViewById<Button>(R.id.btnChooseDir).setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
                Toast.makeText(ctx, "Android 4.4 使用默认内部存储目录（Download/IPTV）", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            startActivityForResult(intent, REQ_DIR)
        }
        v.findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            val timeout = etTimeout.text.toString().toIntOrNull()?.coerceIn(3, 30) ?: 8
            val concurrency = etConcurrency.text.toString().toIntOrNull()?.coerceIn(4, 64) ?: 24
            Prefs.putInt(ctx, Prefs.KEY_TIMEOUT, timeout)
            Prefs.putInt(ctx, Prefs.KEY_CONCURRENCY, concurrency)
            Prefs.putBool(ctx, Prefs.KEY_AUTO_CHECK, swAutoCheck.isChecked)
            Prefs.putBool(ctx, Prefs.KEY_PURGE_INVALID, swPurgeInvalid.isChecked)
            Prefs.putBool(ctx, Prefs.KEY_RETAIN_DUP, swRetainDup.isChecked)
            Prefs.putInt(ctx, Prefs.KEY_DECODER, spDecoder.selectedItemPosition)
            Prefs.putInt(ctx, Prefs.KEY_BUFFER, spBuffer.selectedItemPosition)
            Toast.makeText(ctx, "设置已保存", Toast.LENGTH_SHORT).show()
        }
        return v
    }

    override fun onResume() { super.onResume(); updateDir() }

    private fun updateDir() {
        val uri = Prefs.getTreeUri(requireContext())
        tvDir.text = if (uri == null) getString(R.string.dir_not_set) else uri
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DIR && resultCode == Activity.RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // 部分机型不支持持久化授权，忽略
            }
            Prefs.setTreeUri(requireContext(), uri.toString())
            updateDir()
            Toast.makeText(requireContext(), "下载目录已更新", Toast.LENGTH_SHORT).show()
        }
    }

    companion object { private const val REQ_DIR = 3003 }
}
