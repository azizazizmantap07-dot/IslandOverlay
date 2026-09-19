package com.hyperisland.root.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import com.hyperisland.root.util.CrashLogger

/**
 * Plays a single uniform notification tone on behalf of the Island whenever
 * a notification arrives and the app channel is
 * silent or low-importance,
 * this is what gives the user an audible cue again, without depending on
 * per-channel sound URIs (which aren't reliably honored once importance
 * drops, across ROMs).
 *
 * Same tone for every app (user's choice) — the system's default
 * notification sound, so it doesn't need to read another app's sound
 * resource or request extra permissions.
 *
 * Respects the device's current ringer mode and Do Not Disturb setting: if
 * the user has silenced their phone or turned DND on, the Island silently
 * skipping the tone is the expected/correct behavior, matching what would
 * happen if the app's own popup+sound had played normally.
 */
object IslandNotificationSound {
    private const val TAG = "IslandNotificationSound"

    private var mediaPlayer: MediaPlayer? = null

    fun playIfAllowed(context: Context) {
        try {
            if (!isSoundAllowedRightNow(context)) {
                CrashLogger.d("$TAG: skipped (ringer/DND)")
                return
            }
            playTone(context)
        } catch (t: Throwable) {
            CrashLogger.e("$TAG: playIfAllowed failed", t)
        }
    }

    private fun isSoundAllowedRightNow(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false // silent or vibrate

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            val filter = nm?.currentInterruptionFilter
            if (filter != null &&
                filter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL &&
                filter != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            ) {
                return false // DND / priority-only / alarms-only / none
            }
        }
        return true
    }

    private fun playTone(context: Context) {
        // Release any tone still playing from a previous rapid-fire notification
        // instead of overlapping sounds.
        release()

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getValidRingtoneUri(context)
            ?: run {
                CrashLogger.w("$TAG: no notification sound URI available on this device")
                return
            }

        val player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(context, soundUri)
            setOnCompletionListener { mp ->
                mp.release()
                if (mediaPlayer === mp) mediaPlayer = null
            }
            setOnErrorListener { mp, what, extra ->
                CrashLogger.w("$TAG: MediaPlayer error what=$what extra=$extra")
                mp.release()
                if (mediaPlayer === mp) mediaPlayer = null
                true
            }
            prepare()
        }
        mediaPlayer = player
        player.start()
    }

    private fun release() {
        mediaPlayer?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        mediaPlayer = null
    }
}
