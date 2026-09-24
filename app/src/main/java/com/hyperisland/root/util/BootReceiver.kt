package com.hyperisland.root.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hyperisland.root.service.IslandOverlayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            if (!AppPreferences.isStartOnBootEnabled(context)) {
                CrashLogger.i("BootReceiver: start-on-boot disabled, skipping")
                return
            }
            try {
                CrashLogger.i("BootReceiver: start-on-boot enabled, starting IslandOverlayService")
                IslandOverlayService.start(context)
            } catch (t: Throwable) {
                // Best-effort: if the OS still blocks a foreground service
                // start this early in boot on a given OEM, fail quietly
                // rather than crash the receiver — the user can always
                // start it manually from the app.
                CrashLogger.e("BootReceiver: failed to auto-start Island", t)
            }
        }
    }
}
