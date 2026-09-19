package com.hyperisland.root.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the screen is currently ON or OFF via ACTION_SCREEN_ON /
 * ACTION_SCREEN_OFF (event-driven — no polling).
 *
 * The overlay window stays alive while the screen is off (removing/re-adding
 * it on every screen event would be slower and flicker-prone), but nothing
 * useful is visible during that time: the status bar itself isn't being
 * shown, so every infinite Compose animation driving the pill (comet ring,
 * battery shimmer, breathing glow, wave strings, etc.) is pure wasted
 * CPU/GPU/battery — draws no one sees, repeated 60-120 times a second.
 *
 * [OrientationAwareIsland] and the animated composables gate on
 * [isScreenOn] the same way they already gate on landscape/fullscreen, so
 * animations simply hold their last frame while the screen is off and
 * resume exactly where a human would expect the instant it comes back on —
 * no change to how anything looks or feels while actually in use.
 */
object ScreenStateMonitor {
    private val _isScreenOn = MutableStateFlow(true)
    val isScreenOn: StateFlow<Boolean> = _isScreenOn.asStateFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> _isScreenOn.value = false
                Intent.ACTION_SCREEN_ON -> _isScreenOn.value = true
            }
        }
    }

    /** Registers the receiver and seeds the initial state from [PowerManager]. Safe to call more than once. */
    fun start(context: Context) {
        val appCtx = context.applicationContext
        try {
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            _isScreenOn.value = pm?.isInteractive ?: true
        } catch (_: Exception) {
            // Keep previous/default value.
        }

        if (registered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            // Must be a manifest-style registration reachable without RECEIVER_NOT_EXPORTED
            // restrictions since these are system broadcasts (no NOT_EXPORTED needed/available
            // pre-Tiramisu semantics issue here — system actions are always deliverable).
            appCtx.registerReceiver(receiver, filter)
            registered = true
            CrashLogger.i("ScreenStateMonitor: receiver registered")
        } catch (t: Throwable) {
            CrashLogger.e("ScreenStateMonitor register failed", t)
        }
    }

    fun stop(context: Context) {
        if (!registered) return
        try {
            context.applicationContext.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        registered = false
    }
}
