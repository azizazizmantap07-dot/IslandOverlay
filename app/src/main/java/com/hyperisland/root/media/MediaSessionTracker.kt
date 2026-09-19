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
import com.hyperisland.root.util.ScreenStateMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Tracks active MediaSessions system-wide and pushes Now Playing
 * info + live lyrics (via [LyricsFetcher] / LRCLIB) to the Island.
 *
 * Requires Notification Listener permission (same as our NLS service)
 * and INTERNET permission for remote lyrics APIs.
 */
class MediaSessionTracker(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var positionUpdater: Runnable? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lyricsJob: Job? = null
    private var screenWakeJob: Job? = null

    /** Cached synced/plain lyrics for the current track. */
    @Volatile private var cachedLyrics: LyricsFetcher.LyricsResult? = null
    @Volatile private var lyricsKey: String? = null

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        mainHandler.post { bindBestController(controllers) }
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            // New track → invalidate lyrics cache and refetch
            cachedLyrics = null
            lyricsKey = null
            pushToIsland()
            requestLyricsIfNeeded()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            pushToIsland()
            schedulePositionUpdates(state)
        }

        override fun onSessionDestroyed() {
            activeController = null
            stopPositionUpdates()
            cachedLyrics = null
            lyricsKey = null
            lyricsJob?.cancel()
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
        // While the screen is off, nothing reads the 1s position ticks — the
        // Island window itself is hidden (see IslandOverlayService), so those
        // pushToIsland() calls were pure wasted wakeups/recomposition work.
        // schedulePositionUpdates() below checks isScreenOn and skips the
        // actual push while off; this collector's only job is to push once
        // immediately when the screen comes back on, so the progress bar/
        // lyrics line is already correct on the very first visible frame
        // instead of looking stale for up to 1s.
        screenWakeJob = scope.launch {
            ScreenStateMonitor.isScreenOn.collect { on ->
                if (on && activeController?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    pushToIsland()
                }
            }
        }
    }

    fun stop() {
        stopPositionUpdates()
        lyricsJob?.cancel()
        screenWakeJob?.cancel()
        screenWakeJob = null
        try {
            activeController?.unregisterCallback(controllerCallback)
            sessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {}
        activeController = null
        cachedLyrics = null
        lyricsKey = null
        CrashLogger.i("MediaSessionTracker stopped")
    }

    private fun bindBestController(controllers: List<MediaController>?) {
        val best = controllers
            ?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers?.firstOrNull()

        if (best?.sessionToken == activeController?.sessionToken) {
            pushToIsland()
            requestLyricsIfNeeded()
            return
        }

        try {
            activeController?.unregisterCallback(controllerCallback)
        } catch (_: Exception) {}

        // New session → clear lyrics
        cachedLyrics = null
        lyricsKey = null
        lyricsJob?.cancel()

        activeController = best
        if (best != null) {
            best.registerCallback(controllerCallback, mainHandler)
            CrashLogger.i("Bound media: ${best.packageName}")
            pushToIsland()
            schedulePositionUpdates(best.playbackState)
            requestLyricsIfNeeded()
        }
    }

    private fun currentTrackKey(title: String, artist: String): String =
        "${title.trim().lowercase()}|${artist.trim().lowercase()}"

    /**
     * Fetch lyrics in background via LRCLIB (and fallbacks) when we have
     * a valid title/artist and no cache for this track yet.
     */
    private fun requestLyricsIfNeeded() {
        val ctrl = activeController ?: return
        val meta = ctrl.metadata ?: return
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        if (title.isBlank()) return

        val key = currentTrackKey(title, artist)
        if (key == lyricsKey && cachedLyrics != null) return
        if (key == lyricsKey && lyricsJob?.isActive == true) return

        lyricsKey = key
        val durationSec = ((meta.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) / 1000L).toInt()

        lyricsJob?.cancel()
        lyricsJob = scope.launch(Dispatchers.IO) {
            try {
                val result = LyricsFetcher.fetch(title, artist, durationSec)
                // Only apply if still the same track
                if (lyricsKey == key) {
                    cachedLyrics = result
                    if (result != null) {
                        CrashLogger.i(
                            "Lyrics loaded from ${result.source} " +
                                "(synced=${result.isSynced}, lines=${result.lines.size}) for \"$title\""
                        )
                    } else {
                        CrashLogger.d("No lyrics found for \"$title\" – \"$artist\"")
                    }
                    mainHandler.post { pushToIsland() }
                }
            } catch (t: Throwable) {
                CrashLogger.d("Lyrics fetch error: ${t.message}")
            }
        }
    }

    private fun resolveLyricsLine(
        positionMs: Long,
        isPlaying: Boolean,
        durationMs: Long,
        metaLyrics: String?
    ): String? {
        // 1) Prefer synced / plain from LRCLIB (or fallbacks)
        val fetched = cachedLyrics
        if (fetched != null) {
            val line = fetched.lineAt(positionMs)
            if (!line.isNullOrBlank()) return line.take(120)
        }

        // 2) Metadata lyrics from the player (rare)
        metaLyrics?.takeIf { it.isNotBlank() && it.length < 120 }?.let { return it }

        // 3) Progress placeholder only while playing so the slot looks "live"
        if (isPlaying && durationMs > 0) {
            val pct = ((positionMs.toDouble() / durationMs) * 100).toInt().coerceIn(0, 100)
            return "♪ $pct%  •  ${formatMs(positionMs)} / ${formatMs(durationMs)}"
        }
        return null
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

        val lyricsFromMeta = meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
            ?: meta?.getString("android.media.metadata.LYRICS")
            ?: meta?.getString("lyrics")

        val lyricsLine = resolveLyricsLine(position, isPlaying, duration, lyricsFromMeta)

        val content = IslandContent.Media(
            title = title,
            artist = artist ?: "",
            albumArtUri = null, // bitmap handling can be added later
            isPlaying = isPlaying,
            positionMs = position,
            durationMs = duration,
            lyricsLine = lyricsLine
        )

        IslandBus.show(content)
        CrashLogger.d("Media push: $title – $artist playing=$isPlaying lyrics=${lyricsLine?.take(40)}")
    }

    private fun schedulePositionUpdates(state: PlaybackState?) {
        stopPositionUpdates()
        if (state?.state != PlaybackState.STATE_PLAYING) return
        positionUpdater = object : Runnable {
            override fun run() {
                // Skip the push while the screen is off: the overlay window is
                // hidden then (see IslandOverlayService/ScreenStateMonitor), so
                // there's nothing on screen for this 1Hz tick to update — only
                // the timer itself keeps running (cheap) so position is instantly
                // correct the moment the screen turns back on (see start()'s
                // screenWakeJob), instead of drifting for up to 1s.
                if (ScreenStateMonitor.isScreenOn.value) {
                    pushToIsland()
                }
                // ~1s is enough for progress; synced lyrics change less often
                mainHandler.postDelayed(this, 1000L)
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

        /** Singleton accessor used by [com.hyperisland.root.service.IslandOverlayService]. */
        fun get(context: Context): MediaSessionTracker {
            return instance ?: synchronized(this) {
                instance ?: MediaSessionTracker(context.applicationContext).also { instance = it }
            }
        }

    }
}
