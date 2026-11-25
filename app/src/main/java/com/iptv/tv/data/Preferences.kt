package com.iptv.tv.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.iptv.tv.core.SourceOrigin
import com.iptv.tv.model.Channel
import com.iptv.tv.model.SourceConfig

object Prefs {
    private const val NAME = "iptv_prefs"
    private val gson = Gson()

    private fun ctx(c: Context): SharedPreferences = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private inline fun <reified T> SharedPreferences.getJson(key: String, def: T): T = try {
        val raw = getString(key, null)
        if (raw.isNullOrBlank()) def else gson.fromJson(raw, object : TypeToken<T>() {}.type) ?: def
    } catch (_: Exception) { def }

    // ---------------- 源配置 ----------------
    fun getSources(c: Context): MutableList<SourceConfig> {
        val savedVersion = ctx(c).getInt("catalog_version", 0)
        if (savedVersion < SourceCatalog.CATALOG_VERSION) {
            // 内置源清单升级：重建内置源，但保留用户自定义源
            val cached = ctx(c).getJson("sources", mutableListOf<SourceConfig>())
            val custom = cached.filter { !it.builtIn }
            val merged = (SourceCatalog.builtInSources + custom).toMutableList()
            ctx(c).edit()
                .putString("sources", gson.toJson(merged))
                .putInt("catalog_version", SourceCatalog.CATALOG_VERSION)
                .apply()
            return merged
        }
        return ctx(c).getJson("sources", SourceCatalog.builtInSources.toMutableList()).toMutableList()
    }

    fun saveSources(c: Context, list: List<SourceConfig>) {
        ctx(c).edit().putString("sources", gson.toJson(list)).apply()
    }

    fun resetSources(c: Context) {
        ctx(c).edit().remove("sources").putInt("catalog_version", 0).apply()
    }

    // ---------------- 频道缓存 ----------------
    fun getChannels(c: Context): MutableList<Channel> =
        SourceOrigin.decorate(ctx(c).getJson("channels", mutableListOf<Channel>())).toMutableList()

    fun saveChannels(c: Context, list: List<Channel>) {
        ctx(c).edit().putString("channels", gson.toJson(SourceOrigin.decorate(list))).apply()
    }

    // ---------------- 已删除频道回收站 ----------------
    /** 保存用户主动删除的频道（含完整信息，便于回收站原样恢复） */
    fun getRecycleBin(c: Context): MutableList<Channel> =
        SourceOrigin.decorate(ctx(c).getJson("recycle_bin", mutableListOf<Channel>())).toMutableList()

    fun saveRecycleBin(c: Context, list: List<Channel>) {
        ctx(c).edit().putString("recycle_bin", gson.toJson(SourceOrigin.decorate(list))).apply()
    }

    /** 用户删除的频道唯一键（name+url），采集/导入时据此过滤，避免删了又被加回 */
    fun getRemovedKeys(c: Context): Set<String> = getRecycleBin(c).map { it.identity }.toSet()

    fun getFavorites(c: Context): MutableSet<String> =
        ctx(c).getStringSet("favorites", emptySet())!!.toMutableSet()

    fun saveFavorites(c: Context, set: Set<String>) {
        ctx(c).edit().putStringSet("favorites", set).apply()
    }

    // ---------------- 目录 / 设置 ----------------
    fun getTreeUri(c: Context): String? = ctx(c).getString("tree_uri", null)
    fun setTreeUri(c: Context, uri: String?) {
        ctx(c).edit().putString("tree_uri", uri).apply()
    }

    fun getInt(c: Context, key: String, def: Int) = ctx(c).getInt(key, def)
    fun putInt(c: Context, key: String, value: Int) = ctx(c).edit().putInt(key, value).apply()
    fun getBool(c: Context, key: String, def: Boolean) = ctx(c).getBoolean(key, def)
    fun putBool(c: Context, key: String, value: Boolean) = ctx(c).edit().putBoolean(key, value).apply()
    fun getString(c: Context, key: String, def: String) = ctx(c).getString(key, def) ?: def
    fun putString(c: Context, key: String, value: String) = ctx(c).edit().putString(key, value).apply()

    const val KEY_TIMEOUT = "check_timeout"          // 秒
    const val KEY_CONCURRENCY = "check_concurrency"
    const val KEY_AUTO_CHECK = "auto_check"
    const val KEY_PURGE_INVALID = "purge_invalid"     // 检测后自动清除无效源，默认开启
    const val KEY_DECODER = "decoder"                 // 0=自动 1=硬解优先 2=软解优先
    const val KEY_BUFFER = "buffer_mode"              // 0=默认 1=低延迟 2=大缓冲
    const val KEY_RETAIN_DUP = "retain_duplicate"
    const val KEY_LAST_PLAYED = "last_played"
    const val KEY_PLAY_POSITION = "play_position"
    const val KEY_UA = "user_agent"
    const val KEY_RETURN_TO_LIBRARY = "return_to_library"   // 播放器返回后定位到频道列表页
}
