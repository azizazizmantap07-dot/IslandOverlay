package com.hyperisland.root.xposed

import android.content.Context
import android.content.Intent

/**
 * Broadcast contract: SystemUI LSPosed hook → app process.
 *
 * Fired whenever the status bar window transitions between shown and hidden
 * (immersive / fullscreen apps). The app's [com.hyperisland.root.util.FullscreenMonitor]
 * listens and drives Island show/hide in portrait.
 */
object FullscreenBridge {
    const val ACTION_FULLSCREEN_CHANGED = "com.hyperisland.root.ACTION_FULLSCREEN_CHANGED"
    const val EXTRA_FULLSCREEN = "fullscreen"

    /**
     * App → SystemUI: "re-send your current fullscreen state now".
     * [FullscreenHook] only broadcasts on a *change*, so a service that starts
     * (or restarts) while an app is already fullscreen would otherwise never
     * learn about it and would draw the pill over the video.
     */
    const val ACTION_REQUEST_FULLSCREEN_STATE = "com.hyperisland.root.ACTION_REQUEST_FULLSCREEN_STATE"

    fun requestState(context: Context) {
        context.applicationContext.sendBroadcast(
            Intent(ACTION_REQUEST_FULLSCREEN_STATE).setPackage("com.android.systemui")
        )
    }

    fun send(context: Context, fullscreen: Boolean) {
        context.applicationContext.sendBroadcast(
            Intent(ACTION_FULLSCREEN_CHANGED).putExtra(EXTRA_FULLSCREEN, fullscreen)
        )
    }
}
