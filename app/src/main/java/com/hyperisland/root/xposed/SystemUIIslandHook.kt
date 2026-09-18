package com.hyperisland.root.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * SystemUI injection for the Island itself stays DISABLED — the interactive Island
 * still comes only from IslandOverlayService.
 *
 * What THIS hook does:
 *  - Compact / Expanded  → hide all status icons except Clock & Battery (MODE_HIDE_ALL)
 *  - Minimal (widened)   → hide only icons whose horizontal screen bounds collide with
 *                          the island's left..right range (MODE_SELECTIVE)
 *  - Minimal (narrow) / idle → restore every icon (MODE_SHOW_ALL)
 *
 * Clock (left) and battery (right) are always protected.
 */
object SystemUIIslandHook {

    private val ICON_CONTAINER_ID_NAMES = listOf(
        "statusIcons",
        "status_bar_wifi_group",
        "system_icon_area"
    )

    private val NETWORK_SPEED_ID_NAMES = listOf(
        "network_speed_indicator",
        "traffic_text_view",
        "internet_speed"
    )

    private val NEVER_HIDE_CLASS_SUFFIXES = listOf("Clock", "BatteryMeterView")

    /** Extra horizontal padding (px) so a partially-overlapping icon still counts as colliding. */
    private const val COLLISION_PAD_PX = 4

    private var statusIconsGroup: ViewGroup? = null
    private var networkSpeedView: View? = null
    private var receiverRegistered = false

    // Last applied mode so we can re-apply after layout passes if needed.
    private var lastMode: String = StatusBarIconBridge.MODE_SHOW_ALL
    private var lastLeft: Int = 0
    private var lastRight: Int = 0

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log(
            "HyperIsland: SystemUIIslandHook active (selective + full icon-hide). " +
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
            // Re-apply last known mode in case icons were recreated after a config change.
            applyMode(lastMode, lastLeft, lastRight)
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
                val mode = intent.getStringExtra(StatusBarIconBridge.EXTRA_MODE)
                    ?: if (intent.getBooleanExtra(StatusBarIconBridge.EXTRA_HIDE, false)) {
                        StatusBarIconBridge.MODE_HIDE_ALL
                    } else {
                        StatusBarIconBridge.MODE_SHOW_ALL
                    }
                val left = intent.getIntExtra(StatusBarIconBridge.EXTRA_ISLAND_LEFT, 0)
                val right = intent.getIntExtra(StatusBarIconBridge.EXTRA_ISLAND_RIGHT, 0)
                lastMode = mode
                lastLeft = left
                lastRight = right
                applyMode(mode, left, right)
            }
        }
        val filter = IntentFilter(StatusBarIconBridge.ACTION_SET_ICON_VISIBILITY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun applyMode(mode: String, islandLeft: Int, islandRight: Int) {
        when (mode) {
            StatusBarIconBridge.MODE_HIDE_ALL -> applyFullHide(hide = true)
            StatusBarIconBridge.MODE_SHOW_ALL -> applyFullHide(hide = false)
            StatusBarIconBridge.MODE_SELECTIVE -> {
                applySelective(islandLeft, islandRight)
                // Icons may not be laid out yet on the first frame after attach /
                // config change — re-run once after the next layout pass.
                val group = statusIconsGroup
                if (group != null) {
                    group.post {
                        if (lastMode == StatusBarIconBridge.MODE_SELECTIVE) {
                            applySelective(lastLeft, lastRight)
                        }
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (lastMode == StatusBarIconBridge.MODE_SELECTIVE) {
                            applySelective(lastLeft, lastRight)
                        }
                    }, 120)
                }
            }
            else -> applyFullHide(hide = false)
        }
    }

    private fun applyFullHide(hide: Boolean) {
        val targetVisibility = if (hide) View.GONE else View.VISIBLE
        val group = statusIconsGroup ?: return

        if (group.childCount == 0) {
            // ROM draws icons internally — hide/show the whole container.
            group.visibility = targetVisibility
        } else {
            // Ensure container itself is visible so we can control children.
            if (group.visibility != View.VISIBLE) group.visibility = View.VISIBLE
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (isProtected(child)) {
                    child.visibility = View.VISIBLE
                } else {
                    child.visibility = targetVisibility
                }
            }
        }
        networkSpeedView?.visibility = targetVisibility
    }

    private fun applySelective(islandLeft: Int, islandRight: Int) {
        val group = statusIconsGroup
        if (group == null) {
            // Fallback: if we never resolved the group, don't hide anything.
            return
        }

        // Make sure the container is visible so individual children can be shown/hidden.
        if (group.visibility != View.VISIBLE) group.visibility = View.VISIBLE

        val paddedLeft = islandLeft - COLLISION_PAD_PX
        val paddedRight = islandRight + COLLISION_PAD_PX

        if (group.childCount == 0) {
            // Internal-draw ROM: we can't test per-icon bounds. Fall back to
            // comparing the container's own screen rect against the island.
            val loc = IntArray(2)
            group.getLocationOnScreen(loc)
            val gLeft = loc[0]
            val gRight = gLeft + group.width
            val collides = gLeft < paddedRight && gRight > paddedLeft
            group.visibility = if (collides) View.GONE else View.VISIBLE
        } else {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (isProtected(child)) {
                    child.visibility = View.VISIBLE
                    continue
                }
                // Skip zero-size / not-laid-out children.
                if (child.width <= 0 && child.height <= 0) {
                    child.visibility = View.VISIBLE
                    continue
                }
                val loc = IntArray(2)
                child.getLocationOnScreen(loc)
                val cLeft = loc[0]
                val cRight = cLeft + child.width
                val collides = cLeft < paddedRight && cRight > paddedLeft
                child.visibility = if (collides) View.GONE else View.VISIBLE
            }
        }

        networkSpeedView?.let { speed ->
            if (speed.width <= 0 && speed.height <= 0) {
                speed.visibility = View.VISIBLE
                return@let
            }
            val loc = IntArray(2)
            speed.getLocationOnScreen(loc)
            val sLeft = loc[0]
            val sRight = sLeft + speed.width
            val collides = sLeft < paddedRight && sRight > paddedLeft
            speed.visibility = if (collides) View.GONE else View.VISIBLE
        }
    }

    private fun isProtected(view: View): Boolean {
        return NEVER_HIDE_CLASS_SUFFIXES.any { view.javaClass.simpleName.endsWith(it) }
    }
}
