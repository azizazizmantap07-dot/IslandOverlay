package com.hyperisland.root.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hyperisland.root.root.SuppressedAppsStore
import com.hyperisland.root.ui.island.IslandBus
import com.hyperisland.root.ui.island.IslandContent
import com.hyperisland.root.util.CrashLogger

/**
 * Pushes incoming notifications straight to IslandBus so the overlay
 * (IslandOverlayService) and/or the SystemUI hook can render them live.
 *
 * For apps the user has chosen to suppress (see SuppressedAppsStore /
 * HeadsUpSuppressor — their channel importance is downgraded so the app's
 * own heads-up popup and sound never fire), this also plays a uniform
 * notification tone via IslandNotificationSound so the user still gets an
 * audible cue, just from the Island instead of the app itself.
 */
class IslandNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        SuppressedAppsStore.init(applicationContext)
        CrashLogger.i("IslandNotificationListener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // Don't show our own service/summary notifications, or ongoing
        // notifications like media/downloads which have their own island paths.
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return

        val notification = sbn.notification ?: return

        // Skip group summaries – they duplicate the child notification's content.
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notification.extras
        val title = extras.getCharSequence("android.title")?.toString()
            ?.takeIf { it.isNotBlank() } ?: return
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        CrashLogger.d("IslandNotif posted: $title - $text from ${sbn.packageName}")

        IslandBus.show(
            IslandContent.Notification(
                packageName = sbn.packageName,
                title = title,
                text = text,
                actions = notification.actions?.mapNotNull { it.title?.toString() } ?: emptyList()
            ),
            expand = false
        )

        if (SuppressedAppsStore.isSuppressed(sbn.packageName)) {
            IslandNotificationSound.playIfAllowed(applicationContext)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // optional: collapse related island if it's currently showing this notification
    }
}
