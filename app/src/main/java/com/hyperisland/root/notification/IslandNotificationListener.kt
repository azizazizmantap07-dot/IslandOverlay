package com.hyperisland.root.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hyperisland.root.ui.island.IslandBus
import com.hyperisland.root.ui.island.IslandContent
import com.hyperisland.root.util.CrashLogger

/**
 * Pushes incoming notifications straight to IslandBus so the overlay
 * (IslandOverlayService) and/or the SystemUI hook can render them live.
 *
 * Notification handling for the Island.
 * 
 * own heads-up popup and sound never fire), this also plays a uniform
 * notification tone via IslandNotificationSound so the user still gets an
 * audible cue, just from the Island instead of the app itself.
 *
 * Media / transport notifications are deliberately ignored here because
 * [com.hyperisland.root.media.MediaSessionTracker] already owns the
 * "now playing" path. Letting them through would demote the live Media
 * content to the secondary badge every time the player updates its
 * MediaStyle notification (play/pause/skip).
 */
class IslandNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        CrashLogger.i("IslandNotificationListener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // Don't show our own service/summary notifications.
        if (sbn.packageName == packageName) return

        // Ongoing notifications (downloads, media players that set the flag,
        // persistent services) already have dedicated island paths.
        if (sbn.isOngoing) return

        val notification = sbn.notification ?: return

        // Skip group summaries – they duplicate the child notification's content.
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        // ── Skip media / transport notifications ──────────────────────────
        // These are already handled by MediaSessionTracker. Letting them
        // through causes the classic "bentrok" where play/pause updates
        // demote the progress ring to a secondary badge.
        if (isMediaNotification(notification)) {
            CrashLogger.d("IslandNotif skipped (media): ${sbn.packageName}")
            return
        }

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?.takeIf { it.isNotBlank() } ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        CrashLogger.d("IslandNotif posted: $title - $text from ${sbn.packageName}")

        // Cache real Notification.Action[] + contentIntent so Expanded popup can
        // fire actions (including RemoteInput inline reply) and open the app.
        val notifKey = sbn.key
        NotificationActionStore.put(
            key = notifKey,
            actions = notification.actions,
            contentIntent = notification.contentIntent
        )
        val actionUis = NotificationActionStore.getInfos(notifKey).map {
            IslandContent.NotifActionUi(
                title = it.title,
                index = it.index,
                isReply = it.isReply
            )
        }

        IslandBus.show(
            IslandContent.Notification(
                packageName = sbn.packageName,
                title = title,
                text = text,
                key = notifKey,
                actions = actionUis
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        NotificationActionStore.remove(sbn.key)
    }

    /**
     * Returns true when the notification is a media/transport one that should
     * be owned exclusively by MediaSessionTracker.
     *
     * Detection order (cheapest → most specific):
     * 1. CATEGORY_TRANSPORT
     * 2. EXTRA_MEDIA_SESSION present
     * 3. Template / style is MediaStyle (via extras or recoverBuilder)
     */
    private fun isMediaNotification(notification: Notification): Boolean {
        // 1. Explicit category used by almost every media player
        if (notification.category == Notification.CATEGORY_TRANSPORT) return true

        val extras = notification.extras ?: return false

        // 2. MediaSession token attached (MediaStyle sets this)
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return true

        // 3. Template string used by Notification.Builder for MediaStyle
        //    (works even when the style object itself is not recoverable)
        val template = extras.getString(Notification.EXTRA_TEMPLATE)
        if (template != null && template.contains("MediaStyle", ignoreCase = true)) return true

        // 4. Fallback: recover the style (may fail on some ROMs / obfuscated builders)
        return try {
            val recovered = Notification.Builder.recoverBuilder(applicationContext, notification)
            recovered.style is Notification.MediaStyle
        } catch (_: Throwable) {
            false
        }
    }
}
