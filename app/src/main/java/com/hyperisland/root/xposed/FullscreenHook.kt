package com.hyperisland.root.xposed

import android.content.Context
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Detects immersive / fullscreen mode from inside SystemUI and notifies the
 * app process via [FullscreenBridge].
 *
 * Detection strategy (tried in order, first success wins for each event):
 *  1. [CommandQueue.setWindowState] — AOSP/HyperOS signal that the status-bar
 *     window is SHOWING / HIDING / HIDDEN.
 *  2. [PhoneStatusBarView.setVisibility] — fallback when CommandQueue signature
 *     differs across ROMs.
 *
 * StatusBarManager constants (stable across AOSP):
 *   WINDOW_STATUS_BAR = 1
 *   WINDOW_STATE_SHOWING = 0, HIDING = 1, HIDDEN = 2
 */
object FullscreenHook {

    private const val WINDOW_STATUS_BAR = 1
    private const val WINDOW_STATE_SHOWING = 0
    // HIDING(1) and HIDDEN(2) both mean "don't show the Island on top"

    private var appContext: Context? = null
    private var lastFullscreen: Boolean? = null

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("HyperIsland: FullscreenHook installing")

        // Keep a SystemUI Context so we can sendBroadcast later.
        try {
            val phoneStatusBarViewClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.phone.PhoneStatusBarView",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                phoneStatusBarViewClass,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        appContext = view.context.applicationContext
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("HyperIsland: FullscreenHook PhoneStatusBarView attach failed")
            XposedBridge.log(t)
        }

        hookCommandQueue(lpparam)
        hookStatusBarVisibility(lpparam)
    }

    private fun hookCommandQueue(lpparam: XC_LoadPackage.LoadPackageParam) {
        val className = "com.android.systemui.statusbar.CommandQueue"
        // Newer AOSP: setWindowState(int displayId, int window, int state)
        try {
            val clazz = XposedHelpers.findClass(className, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                clazz,
                "setWindowState",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val window = param.args[1] as Int
                        val state = param.args[2] as Int
                        if (window == WINDOW_STATUS_BAR) {
                            publish(fullscreen = state != WINDOW_STATE_SHOWING)
                        }
                    }
                }
            )
            XposedBridge.log("HyperIsland: hooked CommandQueue.setWindowState(display,window,state)")
            return
        } catch (_: Throwable) { /* try older signature */ }

        // Older AOSP: setWindowState(int window, int state)
        try {
            val clazz = XposedHelpers.findClass(className, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                clazz,
                "setWindowState",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val window = param.args[0] as Int
                        val state = param.args[1] as Int
                        if (window == WINDOW_STATUS_BAR) {
                            publish(fullscreen = state != WINDOW_STATE_SHOWING)
                        }
                    }
                }
            )
            XposedBridge.log("HyperIsland: hooked CommandQueue.setWindowState(window,state)")
        } catch (t: Throwable) {
            XposedBridge.log("HyperIsland: CommandQueue.setWindowState not found – using visibility fallback only")
        }
    }

    private fun hookStatusBarVisibility(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(
                "com.android.systemui.statusbar.phone.PhoneStatusBarView",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                clazz,
                "setVisibility",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val visibility = param.args[0] as Int
                        // GONE / INVISIBLE → treat as fullscreen; VISIBLE → not.
                        // Only used as fallback when CommandQueue didn't fire.
                        if (lastFullscreen == null ||
                            (visibility != View.VISIBLE) != (lastFullscreen == true)
                        ) {
                            // Prefer CommandQueue as source of truth; visibility is backup.
                            // If CommandQueue already set a value, only override on clear mismatch
                            // when status bar is explicitly GONE.
                            if (visibility == View.GONE) {
                                publish(fullscreen = true)
                            } else if (visibility == View.VISIBLE && lastFullscreen == true) {
                                // Don't force-clear fullscreen purely from visibility —
                                // translucent status bar can still be "showing" in immersive.
                                // CommandQueue remains authoritative for SHOWING.
                            }
                        }
                    }
                }
            )
            XposedBridge.log("HyperIsland: hooked PhoneStatusBarView.setVisibility")
        } catch (t: Throwable) {
            XposedBridge.log("HyperIsland: PhoneStatusBarView.setVisibility hook failed")
            XposedBridge.log(t)
        }
    }

    private fun publish(fullscreen: Boolean) {
        if (lastFullscreen == fullscreen) return
        lastFullscreen = fullscreen
        val ctx = appContext
        if (ctx == null) {
            XposedBridge.log("HyperIsland: FullscreenHook no context yet, fullscreen=$fullscreen")
            return
        }
        try {
            FullscreenBridge.send(ctx, fullscreen)
            XposedBridge.log("HyperIsland: fullscreen=$fullscreen broadcast sent")
        } catch (t: Throwable) {
            XposedBridge.log("HyperIsland: FullscreenBridge.send failed")
            XposedBridge.log(t)
        }
    }
}
