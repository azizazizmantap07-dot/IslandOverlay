package com.hyperisland.root.xposed

import android.content.Context
import android.content.Intent

/**
 * Broadcast contract shared between the app process (IslandBus / OverlayService)
 * and the SystemUI process (SystemUIIslandHook).
 *
 * The Island itself lives in a separate overlay window (IslandOverlayService), so it
 * cannot directly touch SystemUI's views. Instead, whenever the Island's visual state
 * changes, the app process sends a small local broadcast; SystemUIIslandHook has a
 * receiver registered inside com.android.systemui that reacts by hiding/showing the
 * status icons (wifi, mobile signal, network speed) that would otherwise sit underneath
 * the Island and steal touches meant for it.
 *
 * EXTRA_HIDE = true  -> Island is Compact or Expanded, covering the icon area -> hide icons
 * EXTRA_HIDE = false -> Island is Minimal/idle -> restore icons
 */
object StatusBarIconBridge {
    const val ACTION_SET_ICON_VISIBILITY = "com.hyperisland.root.ACTION_SET_ICON_VISIBILITY"
    const val EXTRA_HIDE = "hide"

    /**
     * Sent by the app process (com.hyperisland.root). The receiver lives in the
     * SystemUI process (com.android.systemui) and is registered dynamically at
     * runtime by SystemUIIslandHook — it is NOT declared in our manifest, so
     * setPackage(ourOwnPackage) would misdirect this and it would never arrive.
     * We deliberately send it as a plain implicit broadcast; the action string is
     * namespaced and unique enough that only our own SystemUI receiver reacts to it.
     */
    fun sendVisibility(context: Context, hide: Boolean) {
        val intent = Intent(ACTION_SET_ICON_VISIBILITY).putExtra(EXTRA_HIDE, hide)
        context.applicationContext.sendBroadcast(intent)
    }
}
