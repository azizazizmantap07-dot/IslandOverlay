package com.hyperisland.root.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the device is currently in immersive / fullscreen mode.
 *
 * Fed by [com.hyperisland.root.accessibility.IslandAccessibilityService].
 * When the Accessibility service is not enabled, the value stays false (Island remains visible).
 */
object FullscreenMonitor {
    private val _isFullscreen = MutableStateFlow(false)
    val isFullscreen: StateFlow<Boolean> = _isFullscreen.asStateFlow()

    fun setFullscreen(fullscreen: Boolean) {
        if (_isFullscreen.value != fullscreen) {
            _isFullscreen.value = fullscreen
            CrashLogger.d("FullscreenMonitor: isFullscreen=$fullscreen")
        }
    }
}
