package com.hyperisland.root.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.hyperisland.root.notification.IslandNotificationListener
import com.hyperisland.root.ui.island.IslandBus
import com.hyperisland.root.ui.island.IslandContent
import com.hyperisland.root.util.CrashLogger

/**
 * Tracks active MediaSessions system-wide and pushes Now Playing
 * info (+ optional lyrics line) to the Island.
 *
 * Requires Notification Listener permission (same as our NLS service).
 */
class MediaSessionTracker(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var positionUpdater: Runnable? = null

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        mainHandler.post { bindBestController(controllers) }
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            pushToIsland()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            pushToIsland()
            schedulePositionUpdates(state)
        }

        override fun onSessionDestroyed() {
            activeController = null
            stopPositionUpdates()
            // Don't force minimal – other content may be showing
        }
    }

    fun start() {
        try {
            sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(context, IslandNotificationListener::class.java)
            sessionManager?.addOnActiveSessionsChangedListener(sessionListener, component)
            val current = sessionManager?.getActiveSessions(component)
            bindBestController(current)
            CrashLogger.i("MediaSessionTracker started, sessions=${current?.size ?: 0}")
        } catch (t: Throwable) {
            CrashLogger.e("MediaSessionTracker start failed – grant Notification Access", t)
        }
    }

    fun stop() {
        stopPositionUpdates()
        try {
            activeController?.unregisterCallback(controllerCallback)
            sessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {}
        activeController = null
        CrashLogger.i("MediaSessionTracker stopped")
    }

    private fun bindBestController(controllers: List<MediaController>?) {
        val best = controllers
            ?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers?.firstOrNull()

        if (best?.sessionToken == activeController?.sessionToken) {
            pushToIsland()
            return
        }

        try {
            activeController?.unregisterCallback(controllerCallback)
        } catch (_: Exception) {}

        activeController = best
        if (best != null) {
            best.registerCallback(controllerCallback, mainHandler)
            CrashLogger.i("Bound media: ${best.packageName}")
            pushToIsland()
            schedulePositionUpdates(best.playbackState)
        }
    }

    private fun pushToIsland() {
        val ctrl = activeController ?: return
        val meta = ctrl.metadata
        val state = ctrl.playbackState

        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "Unknown"
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ctrl.packageName
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = state?.position ?: 0L
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING

        // Lyrics: try common metadata keys, else generate simple timed line
        val lyricsFromMeta = meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
            ?: meta?.getString("android.media.metadata.LYRICS")
            ?: meta?.getString("lyrics")

        val lyricsLine = lyricsFromMeta?.takeIf { it.isNotBlank() && it.length < 120 }
            ?: if (isPlaying && duration > 0) {
                // Simple progress-based placeholder so the slot is visibly "live"
                val pct = ((position.toDouble() / duration) * 100).toInt().coerceIn(0, 100)
                "♪ $pct%  •  ${formatMs(position)} / ${formatMs(duration)}"
            } else null

        val content = IslandContent.Media(
            title = title,
            artist = artist ?: "",
            albumArtUri = null, // bitmap handling can be added later
            isPlaying = isPlaying,
            positionMs = position,
            durationMs = duration,
            lyricsLine = lyricsLine
        )

        // Only override island if nothing more important is expanded, or always for media
        IslandBus.show(content, expand = false)
        CrashLogger.d("Media push: $title – $artist playing=$isPlaying")
    }

    private fun schedulePositionUpdates(state: PlaybackState?) {
        stopPositionUpdates()
        if (state?.state != PlaybackState.STATE_PLAYING) return
        positionUpdater = object : Runnable {
            override fun run() {
                pushToIsland()
                mainHandler.postDelayed(this, 1000L) // update lyrics/progress every 1s
            }
        }
        mainHandler.postDelayed(positionUpdater!!, 1000L)
    }

    private fun stopPositionUpdates() {
        positionUpdater?.let { mainHandler.removeCallbacks(it) }
        positionUpdater = null
    }

    // ---- Controls (used by Expanded media popup) ----
    fun togglePlayPause() {
        val ctrl = activeController ?: return
        val playing = ctrl.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing) ctrl.transportControls.pause() else ctrl.transportControls.play()
    }

    fun skipToNext() {
        activeController?.transportControls?.skipToNext()
    }

    fun skipToPrevious() {
        activeController?.transportControls?.skipToPrevious()
    }

    private fun formatMs(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    companion object {
        @Volatile private var instance: MediaSessionTracker? = null

        fun get(context: Context): MediaSessionTracker {
            return instance ?: synchronized(this) {
                instance ?: MediaSessionTracker(context.applicationContext).also { instance = it }
            }
        }
    }
}
