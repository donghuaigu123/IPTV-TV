package com.iptv.tv.ui

import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.iptv.tv.BuildConfig
import com.iptv.tv.R
import com.iptv.tv.data.Prefs
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var navCollect: MaterialButton
    private lateinit var navLibrary: MaterialButton
    private lateinit var navSettings: MaterialButton
    private var currentTab = -1
    private var lastBackTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.tvVersion).text = "v${BuildConfig.VERSION_NAME} TV版"

        navCollect = findViewById(R.id.navCollect)
        navLibrary = findViewById(R.id.navLibrary)
        navSettings = findViewById(R.id.navSettings)
        navCollect.setOnClickListener { switchTo(0) }
        navLibrary.setOnClickListener { switchTo(1) }
        navSettings.setOnClickListener { switchTo(2) }

        // 从播放器返回时定位到频道列表页
        val initialTab = if (Prefs.getBool(this, Prefs.KEY_RETURN_TO_LIBRARY, false)) {
            Prefs.putBool(this, Prefs.KEY_RETURN_TO_LIBRARY, false)
            1
        } else 0
        switchTo(initialTab)
        navCollect.post {
            (if (initialTab == 1) navLibrary else navCollect).requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        // 播放器在后台设置返回标志后，回到主界面时切到频道页
        if (Prefs.getBool(this, Prefs.KEY_RETURN_TO_LIBRARY, false)) {
            Prefs.putBool(this, Prefs.KEY_RETURN_TO_LIBRARY, false)
            switchTo(1)
            navLibrary.requestFocus()
        }
    }

    /** 遥控器：数字键 1/2/3 快速切页；返回键双击退出 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_1 -> { switchTo(0); return true }
                KeyEvent.KEYCODE_2 -> { switchTo(1); return true }
                KeyEvent.KEYCODE_3 -> { switchTo(2); return true }
                KeyEvent.KEYCODE_BACK -> {
                    val now = System.currentTimeMillis()
                    if (now - lastBackTime < 2000) finish()
                    else {
                        lastBackTime = now
                        Toast.makeText(this, "再按一次返回键退出", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun switchTo(pos: Int) {
        if (pos == currentTab) return
        currentTab = pos
        navCollect.isSelected = pos == 0
        navLibrary.isSelected = pos == 1
        navSettings.isSelected = pos == 2
        val tag = "tab_$pos"
        var frag = supportFragmentManager.findFragmentByTag(tag)
        if (frag == null) {
            frag = when (pos) {
                0 -> CollectorFragment()
                1 -> LibraryFragment()
                else -> SettingsFragment()
            }
            supportFragmentManager.beginTransaction()
                .add(R.id.container, frag, tag)
                .commitNowAllowingStateLoss()
        }
        supportFragmentManager.fragments.forEach { f ->
            if (f != frag) supportFragmentManager.beginTransaction().hide(f).commitNowAllowingStateLoss()
        }
        supportFragmentManager.beginTransaction().show(frag).commitNowAllowingStateLoss()
    }
}
