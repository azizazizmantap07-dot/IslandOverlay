package com.hyperisland.root.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.hyperisland.root.util.CrashLogger
import com.hyperisland.root.util.FullscreenMonitor

/**
 * Lightweight AccessibilityService used only for fullscreen / immersive detection.
 *
 * Strategy:
 * - On TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOWS_CHANGED, inspect active windows.
 * - If the status-bar window is missing or marked non-visible, treat as fullscreen.
 * - Also check for windows with TYPE_APPLICATION that report immersive-like bounds
 *   covering the full display.
 *
 * This is intentionally conservative: false-negatives (Island stays visible) are
 * preferred over false-positives (Island disappearing while status bar is still shown).
 */
class IslandAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = info
        CrashLogger.i("IslandAccessibilityService connected")
        evaluateFullscreen()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> evaluateFullscreen()
        }
    }

    override fun onInterrupt() {
        CrashLogger.w("IslandAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        FullscreenMonitor.setFullscreen(false)
        CrashLogger.i("IslandAccessibilityService destroyed")
    }

    private fun evaluateFullscreen() {
        try {
            val wins = windows ?: run {
                FullscreenMonitor.setFullscreen(false)
                return
            }

            // Look for the status-bar window. On AOSP it is typically TYPE_SYSTEM
            // or has package com.android.systemui and a small height at the top.
            var statusBarVisible = false
            var hasFullscreenApp = false

            for (w in wins) {
                val type = w.type
                val title = w.title?.toString() ?: ""
                val pkg = try {
                    // root node package is the most reliable signal we have
                    w.root?.packageName?.toString()
                } catch (_: Exception) {
                    null
                }

                // Status bar window heuristics
                if (type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                    pkg == "com.android.systemui" ||
                    title.contains("StatusBar", ignoreCase = true) ||
                    title.contains("status_bar", ignoreCase = true)
                ) {
                    // isActive / isFocused are not reliable for status bar;
                    // layer + bounds are better but we keep it simple.
                    if (w.isActive || w.isFocused || w.isAccessibilityFocused) {
                        statusBarVisible = true
                    }
                    // Even if not "active", a system window that exists usually means bar is shown
                    statusBarVisible = true
                }

                // Application window that claims the whole screen (immersive)
                if (type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val bounds = android.graphics.Rect()
                    w.getBoundsInScreen(bounds)
                    val dm = resources.displayMetrics
                    val coversFull =
                        bounds.left <= 0 &&
                            bounds.top <= 0 &&
                            bounds.right >= dm.widthPixels &&
                            bounds.bottom >= dm.heightPixels
                    if (coversFull && w.isActive) {
                        hasFullscreenApp = true
                    }
                }
            }

            // Fullscreen if we have a full-screen app window AND status bar is not clearly visible
            val fullscreen = hasFullscreenApp && !statusBarVisible
            FullscreenMonitor.setFullscreen(fullscreen)
        } catch (t: Throwable) {
            CrashLogger.w("evaluateFullscreen error: ${t.message}")
        }
    }

    companion object {
        fun isEnabled(context: android.content.Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expected = "${context.packageName}/${IslandAccessibilityService::class.java.canonicalName}"
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
