package com.iptv.tv

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex

class IPTVApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Android 4.x 方法数超限时需要 MultiDex
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: Application
            private set
        val context: Context get() = instance.applicationContext
    }
}
