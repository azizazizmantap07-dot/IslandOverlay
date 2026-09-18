package com.hyperisland.root.xposed

import android.content.Context
import android.content.Intent

/**
 * Broadcast contract shared between the app process (IslandBus / OverlayService)
 * and the SystemUI process (SystemUIIslandHook).
 *
 * Modes:
 *  - [MODE_SHOW_ALL]   — Minimal with a narrow pill that doesn't collide with any icons
 *  - [MODE_HIDE_ALL]   — Compact / Expanded: hide every status icon except Clock & Battery
 *  - [MODE_SELECTIVE]  — Minimal with a widened pill: hide only icons whose screen bounds
 *                        horizontally intersect the island's [EXTRA_ISLAND_LEFT]..[EXTRA_ISLAND_RIGHT]
 *                        range. Icons outside that range stay visible.
 */
object StatusBarIconBridge {
    const val ACTION_SET_ICON_VISIBILITY = "com.hyperisland.root.ACTION_SET_ICON_VISIBILITY"

    const val EXTRA_MODE = "mode"
    const val MODE_SHOW_ALL = "show_all"
    const val MODE_HIDE_ALL = "hide_all"
    const val MODE_SELECTIVE = "selective"

    /** Screen-coordinate left edge of the island (px), used only in selective mode. */
    const val EXTRA_ISLAND_LEFT = "island_left"
    /** Screen-coordinate right edge of the island (px), used only in selective mode. */
    const val EXTRA_ISLAND_RIGHT = "island_right"

    // Legacy extra kept so older hook builds don't crash if they still read it.
    const val EXTRA_HIDE = "hide"

    /**
     * Sent by the app process. The receiver lives in com.android.systemui and is
     * registered dynamically by SystemUIIslandHook — do NOT setPackage() to our own
     * package or the broadcast will never arrive in SystemUI.
     */
    fun sendShowAll(context: Context) {
        context.applicationContext.sendBroadcast(
            Intent(ACTION_SET_ICON_VISIBILITY).putExtra(EXTRA_MODE, MODE_SHOW_ALL)
        )
    }

    fun sendHideAll(context: Context) {
        context.applicationContext.sendBroadcast(
            Intent(ACTION_SET_ICON_VISIBILITY)
                .putExtra(EXTRA_MODE, MODE_HIDE_ALL)
                .putExtra(EXTRA_HIDE, true)
        )
    }

    fun sendSelective(context: Context, islandLeftPx: Int, islandRightPx: Int) {
        context.applicationContext.sendBroadcast(
            Intent(ACTION_SET_ICON_VISIBILITY)
                .putExtra(EXTRA_MODE, MODE_SELECTIVE)
                .putExtra(EXTRA_ISLAND_LEFT, islandLeftPx)
                .putExtra(EXTRA_ISLAND_RIGHT, islandRightPx)
        )
    }

}
