package com.hyperisland.root.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * General app-level behavior preferences — separate from [IslandPreferences]
 * (which is purely visual/layout for the pill itself).
 *
 * Currently just the "start on boot" switch: whether [BootReceiver] should
 * auto-start [IslandOverlayService] after the device finishes booting.
 * Defaults to **off** so a fresh install never starts a background overlay
 * service without the user explicitly opting in.
 */
object AppPreferences {
    private const val PREFS_NAME = "island_app_prefs"
    private const val KEY_START_ON_BOOT = "start_on_boot"

    private var prefs: SharedPreferences? = null

    private val _startOnBoot = MutableStateFlow(false)
    val startOnBoot: StateFlow<Boolean> = _startOnBoot.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _startOnBoot.value = prefs?.getBoolean(KEY_START_ON_BOOT, false) ?: false
    }

    fun setStartOnBoot(enabled: Boolean) {
        _startOnBoot.value = enabled
        prefs?.edit()?.putBoolean(KEY_START_ON_BOOT, enabled)?.apply()
    }

    /**
     * Synchronous read for use from [BootReceiver], where there's no
     * guarantee [init] already ran in this process (a fresh boot may deliver
     * BOOT_COMPLETED before anything else touches the app). Reads straight
     * from SharedPreferences if the in-memory singleton isn't ready yet.
     */
    fun isStartOnBootEnabled(context: Context): Boolean {
        prefs?.let { return it.getBoolean(KEY_START_ON_BOOT, false) }
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_START_ON_BOOT, false)
    }
}
