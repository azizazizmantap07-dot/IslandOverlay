package com.hyperisland.root.xposed

import android.content.Context

/**
 * Non-root build: status-bar icon hide is driven by the Xposed module on the
 * root variant. These no-ops keep [com.hyperisland.root.ui.island.IslandBus]
 * compiling without requiring LSPosed.
 */
object StatusBarIconBridge {
    fun sendHideAll(context: Context) { /* no-op on non-root */ }
    fun sendSelective(context: Context, leftPx: Int, rightPx: Int) { /* no-op on non-root */ }
}
