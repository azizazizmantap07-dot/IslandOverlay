package com.hyperisland.root.root

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Persists:
 * 1) Which packages the user picked to have their heads-up/pop-up notifications
 *    suppressed (manual whitelist – "hanya aplikasi yang saya pilih").
 * 2) Per-channel original importance for every channel we've ever downgraded,
 *    so [HeadsUpSuppressor] can restore them exactly when a package is removed
 *    from the list, when the master toggle is turned off, or when the module
 *    itself is disabled/uninstalled ("dikembalikan otomatis saat modul
 *    dimatikan/uninstall").
 *
 * Storage format for original importance: a JSON object keyed by
 * "packageName/channelId" -> original importance int, e.g.
 * {"com.whatsapp/messages": 4, "com.whatsapp/calls": 4}
 */
object SuppressedAppsStore {
    private const val PREFS_NAME = "island_headsup_suppress_prefs"
    private const val KEY_ENABLED = "master_enabled"
    private const val KEY_PACKAGES = "suppressed_packages" // comma-separated
    private const val KEY_ORIGINAL_IMPORTANCE = "original_importance_json"

    private var prefs: SharedPreferences? = null

    private val _suppressedPackages = MutableStateFlow<Set<String>>(emptySet())
    val suppressedPackages: StateFlow<Set<String>> = _suppressedPackages.asStateFlow()

    private val _masterEnabled = MutableStateFlow(false)
    val masterEnabled: StateFlow<Boolean> = _masterEnabled.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _suppressedPackages.value = loadPackages()
        _masterEnabled.value = prefs?.getBoolean(KEY_ENABLED, false) ?: false
    }

    private fun loadPackages(): Set<String> {
        val raw = prefs?.getString(KEY_PACKAGES, "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun isSuppressed(packageName: String): Boolean =
        _masterEnabled.value && packageName in _suppressedPackages.value

    fun setMasterEnabled(enabled: Boolean) {
        _masterEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }

    fun setPackageSuppressed(packageName: String, suppressed: Boolean) {
        val current = _suppressedPackages.value.toMutableSet()
        if (suppressed) current.add(packageName) else current.remove(packageName)
        _suppressedPackages.value = current
        prefs?.edit()?.putString(KEY_PACKAGES, current.joinToString(","))?.apply()
    }

    // ---- Original per-channel importance, for restore ----

    private fun key(packageName: String, channelId: String) = "$packageName/$channelId"

    @Synchronized
    fun rememberOriginalImportance(packageName: String, channelId: String, importance: Int) {
        val obj = readOriginalImportanceJson()
        val k = key(packageName, channelId)
        if (!obj.has(k)) { // never overwrite an already-remembered original
            obj.put(k, importance)
            prefs?.edit()?.putString(KEY_ORIGINAL_IMPORTANCE, obj.toString())?.apply()
        }
    }

    @Synchronized
    fun getOriginalImportance(packageName: String, channelId: String): Int? {
        val obj = readOriginalImportanceJson()
        val k = key(packageName, channelId)
        return if (obj.has(k)) obj.getInt(k) else null
    }

    @Synchronized
    fun forgetOriginalImportance(packageName: String, channelId: String) {
        val obj = readOriginalImportanceJson()
        val k = key(packageName, channelId)
        if (obj.has(k)) {
            obj.remove(k)
            prefs?.edit()?.putString(KEY_ORIGINAL_IMPORTANCE, obj.toString())?.apply()
        }
    }

    /** All "packageName/channelId" keys we currently hold an original importance for. */
    @Synchronized
    fun allRememberedChannelKeys(): List<Pair<String, String>> {
        val obj = readOriginalImportanceJson()
        val result = mutableListOf<Pair<String, String>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val idx = k.indexOf('/')
            if (idx > 0) {
                result.add(k.substring(0, idx) to k.substring(idx + 1))
            }
        }
        return result
    }

    private fun readOriginalImportanceJson(): JSONObject {
        val raw = prefs?.getString(KEY_ORIGINAL_IMPORTANCE, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }
}
