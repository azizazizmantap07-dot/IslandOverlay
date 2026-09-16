package com.hyperisland.root.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * SystemUI injection for the Island itself stays DISABLED (see history below) — the
 * interactive Island still comes only from IslandOverlayService.
 *
 * What THIS hook does instead: while the Island is Compact or Expanded, it hides the
 * status bar's own wifi / mobile-signal / network-speed icons so they don't visually
 * or touch-wise collide with the Island overlay sitting on top of them. Clock (left)
 * and battery (right) are intentionally left untouched. When the Island returns to
 * Minimal/idle, icons are restored.
 *
 * Previously this hooked PhoneStatusBarView and injected a second short island
 * into the status bar. That competed with the full OverlayService island
 * (sliders, animations, media controls), so that part remains off.
 */
object SystemUIIslandHook {

    // Common resource-id names for the status icon area across AOSP-based ROMs.
    // Not all exist on every ROM/version — each is tried and silently skipped if absent.
    private val ICON_CONTAINER_ID_NAMES = listOf(
        "statusIcons",           // AOSP status_bar.xml container for wifi/mobile/etc icons
        "status_bar_wifi_group", // some ROMs group wifi separately
        "system_icon_area"
    )

    private val NETWORK_SPEED_ID_NAMES = listOf(
        "network_speed_indicator", // HyperOS / some MIUI-based ROMs
        "traffic_text_view",
        "internet_speed"
    )

    // View classes that must stay visible even inside the icon container (defensive allowlist,
    // in case a future ROM ever nests clock/battery inside the same container we hide).
    private val NEVER_HIDE_CLASS_SUFFIXES = listOf("Clock", "BatteryMeterView")

    private var statusIconsGroup: ViewGroup? = null
    private var networkSpeedView: View? = null
    private var receiverRegistered = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log(
            "HyperIsland: SystemUIIslandHook active (icon-hide only, no injected island). " +
                "pkg=${lpparam.packageName}"
        )

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
                        val statusBarView = param.thisObject as? View ?: return
                        onStatusBarAttached(statusBarView)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("HyperIsland: failed to hook PhoneStatusBarView.onAttachedToWindow")
            XposedBridge.log(t)
        }
    }

    private fun onStatusBarAttached(statusBarView: View) {
        try {
            resolveIconViews(statusBarView)
            registerVisibilityReceiver(statusBarView.context)
            XposedBridge.log(
                "HyperIsland: resolved statusIconsGroup=${statusIconsGroup != null} " +
                    "networkSpeedView=${networkSpeedView != null}"
            )
        } catch (t: Throwable) {
            XposedBridge.log("HyperIsland: onStatusBarAttached failed")
            XposedBridge.log(t)
        }
    }

    private fun resolveIconViews(root: View) {
        val res = root.resources
        val pkg = root.context.packageName // com.android.systemui

        statusIconsGroup = ICON_CONTAINER_ID_NAMES
            .asSequence()
            .map { res.getIdentifier(it, "id", pkg) }
            .filter { it != 0 }
            .mapNotNull { root.findViewById<View>(it) as? ViewGroup }
            .firstOrNull()

        networkSpeedView = NETWORK_SPEED_ID_NAMES
            .asSequence()
            .map { res.getIdentifier(it, "id", pkg) }
            .filter { it != 0 }
            .mapNotNull { root.findViewById<View>(it) }
            .firstOrNull()
    }

    private fun registerVisibilityReceiver(context: Context) {
        if (receiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != StatusBarIconBridge.ACTION_SET_ICON_VISIBILITY) return
                val hide = intent.getBooleanExtra(StatusBarIconBridge.EXTRA_HIDE, false)
                applyVisibility(hide)
            }
        }
        val filter = IntentFilter(StatusBarIconBridge.ACTION_SET_ICON_VISIBILITY)
        // SystemUI runs as its own process; this receiver only needs to catch broadcasts
        // sent by our app package, so no custom permission is required here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun applyVisibility(hide: Boolean) {
        val targetVisibility = if (hide) View.GONE else View.VISIBLE
        val group = statusIconsGroup ?: return

        // Some ROMs draw wifi/signal/data icons internally (childCount stays 0) rather
        // than as child Views — in that case hide the container itself. Otherwise hide
        // each child individually so Clock/BatteryMeterView stay protected.
        if (group.childCount == 0) {
            group.visibility = targetVisibility
        } else {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                val isProtected = NEVER_HIDE_CLASS_SUFFIXES.any {
                    child.javaClass.simpleName.endsWith(it)
                }
                if (!isProtected) child.visibility = targetVisibility
            }
        }
        networkSpeedView?.visibility = targetVisibility
    }
}
