package com.hyperisland.root.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the device is currently in immersive / fullscreen mode
 * (status bar hidden by the foreground app). Fed by a broadcast from the
 * SystemUI LSPosed hook ([com.hyperisland.root.xposed.FullscreenBridge]).
 *
 * Combined with [OrientationMonitor]: the Island hides in landscape OR
 * fullscreen-portrait, and reappears when neither applies.
 */
object FullscreenMonitor {
    private val _isFullscreen = MutableStateFlow(false)
    val isFullscreen: StateFlow<Boolean> = _isFullscreen.asStateFlow()

    fun setFullscreen(fullscreen: Boolean) {
        if (_isFullscreen.value != fullscreen) {
            _isFullscreen.value = fullscreen
            CrashLogger.d("FullscreenMonitor: fullscreen=$fullscreen")
        }
    }
}
