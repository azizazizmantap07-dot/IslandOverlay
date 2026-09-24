package com.hyperisland.root.notification

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hyperisland.root.root.SuppressedAppsStore
import com.hyperisland.root.ui.island.IslandBus
import com.hyperisland.root.ui.island.IslandContent
import com.hyperisland.root.media.AlbumArtCache
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
 *
 * Media / transport notifications are not shown as island cards (MediaSessionTracker
 * owns now-playing), but their largeIcon is cached into [AlbumArtCache] for the
 * music backdrop on Compact / Expanded.
 */
class IslandNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: IslandNotificationListener? = null
            private set

        /**
         * Pull MediaStyle largeIcon for the given player into [AlbumArtCache].
         * Called from [com.hyperisland.root.media.MediaSessionTracker] so art
         * is available even when metadata bitmaps are empty (common on HyperOS).
         */
        fun cacheArtForSession(packageName: String?, title: String, artist: String): Boolean {
            val nls = instance ?: return false
            return try {
                nls.cacheArtFromActiveNotifications(packageName, title, artist)
            } catch (t: Throwable) {
                CrashLogger.e("cacheArtForSession failed", t)
                false
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        SuppressedAppsStore.init(applicationContext)
        CrashLogger.i("IslandNotificationListener connected")
        // Seed cache from any already-visible media cards (e.g. user started music
        // before granting / connecting the listener).
        try {
            activeNotifications?.forEach { sbn ->
                val n = sbn.notification ?: return@forEach
                if (isMediaNotification(n)) cacheMediaAlbumArt(n)
            }
        } catch (t: Throwable) {
            CrashLogger.e("NLS seed art failed", t)
        }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // Don't show our own service/summary notifications.
        if (sbn.packageName == packageName) return

        val notification = sbn.notification ?: return

        // Skip group summaries – they duplicate the child notification's content.
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        // ── Media notifications ─────────────────────────────────────────
        // Content is driven by MediaSessionTracker, but album art is often
        // only present as the MediaStyle largeIcon (Spotify / YT Music / etc.).
        // Cache it here so Compact / Expanded backdrops can paint the cover.
        if (isMediaNotification(notification) || (sbn.isOngoing && isMediaNotification(notification))) {
            cacheMediaAlbumArt(notification)
            CrashLogger.d("IslandNotif media art cached: ${sbn.packageName}")
            return
        }

        // Ongoing non-media (downloads, persistent services) — not shown as island cards.
        if (sbn.isOngoing) return

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

        if (SuppressedAppsStore.isSuppressed(sbn.packageName)) {
            IslandNotificationSound.playIfAllowed(applicationContext)
        }
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


    internal fun cacheArtFromActiveNotifications(
        packageName: String?,
        title: String,
        artist: String
    ): Boolean {
        val notifs = try {
            activeNotifications
        } catch (_: Exception) {
            null
        } ?: return false

        fun tryNotif(n: android.app.Notification): Boolean {
            val bmp = extractLargeIconBitmap(n) ?: return false
            AlbumArtCache.put(title, artist, bmp)
            AlbumArtCache.put(title, "", bmp)
            return true
        }

        // Prefer same package as the MediaSession controller
        if (packageName != null) {
            for (sbn in notifs) {
                if (sbn.packageName != packageName) continue
                val n = sbn.notification ?: continue
                if (isMediaNotification(n) || sbn.isOngoing) {
                    if (tryNotif(n)) return true
                }
            }
        }
        // Any MediaStyle notification
        for (sbn in notifs) {
            val n = sbn.notification ?: continue
            if (!isMediaNotification(n)) continue
            if (tryNotif(n)) return true
        }
        return AlbumArtCache.get(title, artist) != null || AlbumArtCache.get(title, "") != null
    }

    private fun extractLargeIconBitmap(notification: Notification): Bitmap? {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                val icon = notification.getLargeIcon()
                if (icon != null) {
                    val d = icon.loadDrawable(this)
                    val bmp = drawableToBitmap(d)
                    if (bmp != null) return bmp
                }
            }
        } catch (_: Exception) {}
        try {
            @Suppress("DEPRECATION")
            notification.largeIcon?.let { return it }
        } catch (_: Exception) {}
        try {
            val extras = notification.extras
            return if (Build.VERSION.SDK_INT >= 33) {
                extras.getParcelable(Notification.EXTRA_LARGE_ICON, Bitmap::class.java)
                    ?: extras.getParcelable(Notification.EXTRA_LARGE_ICON_BIG, Bitmap::class.java)
            } else {
                @Suppress("DEPRECATION")
                (extras.getParcelable(Notification.EXTRA_LARGE_ICON) as? Bitmap)
                    ?: (extras.getParcelable(Notification.EXTRA_LARGE_ICON_BIG) as? Bitmap)
            }
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Pull album art from a MediaStyle notification into [AlbumArtCache].
     * HyperBridge-style islands rely on this path because many players only
     * put the cover on the notification largeIcon, not MediaMetadata bitmaps.
     */
    private fun cacheMediaAlbumArt(notification: Notification) {
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?.takeIf { it.isNotBlank() } ?: return
        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
            ?: ""
        val bmp = extractLargeIconBitmap(notification) ?: return
        AlbumArtCache.put(title, artist, bmp)
        AlbumArtCache.put(title, "", bmp)
        CrashLogger.d("Media art cached for '$title' ($artist) ${bmp.width}x${bmp.height}")
    }

    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val w = (drawable.intrinsicWidth.takeIf { it > 0 } ?: 256)
        val h = (drawable.intrinsicHeight.takeIf { it > 0 } ?: 256)
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp
        } catch (_: Exception) {
            null
        }
    }

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
