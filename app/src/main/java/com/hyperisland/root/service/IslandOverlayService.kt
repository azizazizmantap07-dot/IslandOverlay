package com.hyperisland.root.service

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.hyperisland.root.MainActivity
import com.hyperisland.root.R
import com.hyperisland.root.audio.AudioPulseEngine
import com.hyperisland.root.media.MediaSessionTracker
import com.hyperisland.root.system.RingerMode
import com.hyperisland.root.system.SystemControl
import com.hyperisland.root.util.FullscreenMonitor
import com.hyperisland.root.util.OrientationMonitor
import com.hyperisland.root.util.ScreenStateMonitor
import com.hyperisland.root.util.SystemEventMonitor
import com.hyperisland.root.ui.island.IslandBus
import com.hyperisland.root.ui.island.IslandContent
import com.hyperisland.root.ui.island.IslandState
import com.hyperisland.root.ui.island.IslandPreferences
import com.hyperisland.root.ui.island.OrientationAwareIsland
import com.hyperisland.root.util.CrashLogger
import com.hyperisland.root.notification.NotificationActionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

class IslandOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    /** Whether the overlay window is currently live (true) or parked/inert (false). */
    private var windowVisible = true
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mediaTracker: MediaSessionTracker? = null
    private var eventMonitor: SystemEventMonitor? = null

    private val layoutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == IslandPreferences.ACTION_LAYOUT_CHANGED) {
                CrashLogger.i("Layout changed broadcast received")
                applyLayoutParams()
                // Force Compose to refresh by invalidating the view
                overlayView?.invalidate()
                overlayView?.requestLayout()
            }
        }
    }

    private val fullscreenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "com.hyperisland.root.ACTION_FULLSCREEN_CHANGED") return
            val fs = intent.getBooleanExtra("fullscreen", false)
            FullscreenMonitor.setFullscreen(fs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        CrashLogger.i("IslandOverlayService onCreate")
        IslandPreferences.init(this)
        OrientationMonitor.init(this)
        ScreenStateMonitor.start(this)
        startForeground(NOTIFICATION_ID, createNotification())
        showOverlay()
        mediaTracker = MediaSessionTracker.get(this).also { it.start() }
        eventMonitor = SystemEventMonitor(this).also { it.start() }

        // Flow-based live updates
        serviceScope.launch {
            IslandPreferences.config.collect {
                applyLayoutParams()
            }
        }
        // Expanded notification needs IME focus for inline reply TextField.
        serviceScope.launch {
            IslandBus.state.collect { state ->
                val needFocus = state is IslandState.Expanded &&
                    state.content is IslandContent.Notification
                setOverlayFocusable(needFocus)
            }
        }
        // Window-level hide. Hiding only the Compose content (AnimatedVisibility)
        // is NOT enough: the overlay window itself stays alive as a full-width,
        // TRANSLUCENT, hardware-accelerated surface pinned to the top of the
        // screen. That leaves two visible bugs in landscape / fullscreen:
        //   1. a ghost of the pill's drop shadow (graphicsLayer.shadowElevation)
        //      that the fade-out never fully clears, and
        //   2. a strip across the top that still swallows touches, so the app
        //      underneath cannot be tapped around where the pill used to be.
        // So when the island must be hidden we also make the WINDOW inert and
        // tiny — see [applyWindowVisibility].
        serviceScope.launch {
            combine(
                OrientationMonitor.isLandscape,
                FullscreenMonitor.isFullscreen,
                ScreenStateMonitor.isScreenOn
            ) { landscape, fullscreen, screenOn -> !landscape && !fullscreen && screenOn }
                .distinctUntilChanged()
                .collect { shouldShow -> applyWindowVisibility(shouldShow) }
        }
        // AudioPulseEngine capture is only useful while (a) the screen is on — nothing
        // reads the amplitude/beat stream otherwise, since every animation it drives is
        // invisible off-screen — and (b) some session is actually playing. Keeping the
        // Visualizer attached 24/7 regardless of playback state was pure background
        // battery/CPU drain; this drives start()/stop() off exactly the two conditions
        // that make it observable, so EqualizerBars/MusicWaveStrings look identical
        // whenever the pill is actually visible and something is playing.
        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                IslandBus.state,
                ScreenStateMonitor.isScreenOn
            ) { state, screenOn -> isAnyMediaPlaying(state) && screenOn }
                .distinctUntilChanged()
                .collect { shouldCapture ->
                    if (shouldCapture) startAudioPulseIfPermitted() else AudioPulseEngine.stop()
                }
        }

        // Broadcast backup (in case Flow misses)
        val filter = IntentFilter(IslandPreferences.ACTION_LAYOUT_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                this, layoutReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(layoutReceiver, filter)
        }

        // Fullscreen detection broadcast (fed by AccessibilityService when enabled).
        // Must be EXPORTED so the SystemUI process broadcast can reach us.
        val fsFilter = IntentFilter("com.hyperisland.root.ACTION_FULLSCREEN_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                this, fullscreenReceiver, fsFilter, ContextCompat.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(fullscreenReceiver, fsFilter)
        }
        // The hook only broadcasts on a change. Ask it to re-send the current state
        // now, otherwise starting/restarting the island while an app is already
        // fullscreen leaves the pill drawn over the video until the next change.
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val cfg = IslandPreferences.config.value
        // Width MATCH_PARENT so Expanded popup can span below the status bar;
        // height stays WRAP_CONTENT (pill or popup sizes itself).
        //
        // IMPORTANT (touch-target fix): height is WRAP_CONTENT, but the Compose
        // content itself now always reserves at least `minTouchTargetHeightPx`
        // (status bar height + a safety margin) for its ROOT hit box — see
        // IslandComposable's `touchSafeHeight`. Pixels inside that root box that
        // sit inside the real status bar zone are still routinely "stolen" by
        // SystemUI/the status bar itself regardless of window flags here, no
        // matter what flags are combined on this LayoutParams; the only reliable
        // fix is making sure the tappable Compose box extends below the status
        // bar, which is what the Compose-side change does. This window config
        // does not need to change for that — it's listed here only so the two
        // fixes are easy to find together.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = cfg.offsetX
            // Anchor the window itself slightly ABOVE the visual pill position so the
            // window's own top edge starts inside/above the status bar. Combined with
            // the Compose-side touch-safe hit box (which pads the pill's real touch
            // area down below the status bar), this guarantees the tappable region is
            // never entirely confined to the status bar strip.
            y = resolveYOffset(cfg.offsetY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        layoutParams = params

        val lifecycleOwner = OverlayLifecycleOwner()
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                val state by IslandBus.state.collectAsState()
                val layout by IslandPreferences.config.collectAsState()
                val isLandscape by OrientationMonitor.isLandscape.collectAsState()
                val isFullscreen by FullscreenMonitor.isFullscreen.collectAsState()
                val isScreenOn by ScreenStateMonitor.isScreenOn.collectAsState()
                // Key on layout so size changes force full recomposition of the island
                androidx.compose.runtime.key(layout) {
                    OrientationAwareIsland(
                        state = state,
                        isLandscape = isLandscape,
                        isFullscreen = isFullscreen,
                        isScreenOn = isScreenOn,
                        onExpand = { IslandBus.expand() },
                        onCollapse = { IslandBus.collapseToMinimal() },
                        onAction = { action -> handleAction(action) }
                    )
                }
            }
        }

        overlayView = composeView
        try {
            windowManager?.addView(composeView, params)
            CrashLogger.i("Overlay view added x=${params.x} y=${params.y}")
        } catch (e: Exception) {
            CrashLogger.e("Failed to add overlay view", e)
        }
    }

    /**
     * Makes the overlay window fully inert (or live again).
     *
     * Hidden  → view is GONE, window is flagged NOT_TOUCHABLE and shrunk to 1x1
     *           and parked off-screen. Nothing is drawn (so no shadow ghost) and
     *           no touch can ever be routed to it (so no dead zone).
     * Visible → the original MATCH_PARENT x WRAP_CONTENT geometry and flags are
     *           restored exactly as [showOverlay] created them.
     *
     * The window is deliberately kept attached instead of removeView/addView, for
     * the same reason the Compose layer avoids it: re-adding on every rotation
     * flickers and would recreate the ComposeView.
     */
    private fun applyWindowVisibility(visible: Boolean) {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        if (windowVisible == visible) return
        windowVisible = visible

        if (visible) {
            val cfg = IslandPreferences.config.value
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.x = cfg.offsetX
            params.y = resolveYOffset(cfg.offsetY)
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            params.alpha = 1f
            // Hiding forced NOT_FOCUSABLE; restore focus only if the current state
            // actually needs it (Expanded notification with inline reply).
            val st = IslandBus.state.value
            val needFocus = st is IslandState.Expanded && st.content is IslandContent.Notification
            params.flags = if (needFocus) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            view.visibility = View.VISIBLE
        } else {
            view.visibility = View.GONE
            params.width = 1
            params.height = 1
            // Park far outside the screen as a second line of defence in case a
            // ROM keeps compositing a GONE view for one more frame.
            params.x = HIDDEN_PARK_OFFSET
            params.y = HIDDEN_PARK_OFFSET
            // Also force NOT_FOCUSABLE: if an Expanded notification (which needs IME
            // focus for inline reply) is open when the device rotates or goes
            // fullscreen, a still-focusable hidden window would keep stealing the
            // keyboard/back key from the app the user is actually using.
            params.flags = params.flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            params.alpha = 0f
        }
        try {
            windowManager?.updateViewLayout(view, params)
            CrashLogger.d("Overlay window visible=$visible")
        } catch (e: Exception) {
            CrashLogger.e("Failed to update overlay visibility", e)
        }
    }

    private fun setOverlayFocusable(focusable: Boolean) {
        val params = layoutParams ?: return
        val view = overlayView ?: return
        // Never grant focus to a hidden/inert window (would steal IME + input).
        if (!windowVisible) return
        val wasFocusable = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
        if (wasFocusable == focusable) return
        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try {
            windowManager?.updateViewLayout(view, params)
            CrashLogger.d("Overlay focusable=$focusable")
        } catch (e: Exception) {
            CrashLogger.e("Failed to update overlay focus", e)
        }
    }

    private fun applyLayoutParams() {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        // While hidden the window is parked off-screen; re-applying the user's
        // offsets here would drag it back on-screen (and re-open the dead zone).
        if (!windowVisible) return
        val cfg = IslandPreferences.config.value
        params.x = cfg.offsetX
        params.y = resolveYOffset(cfg.offsetY)
        try {
            windowManager?.updateViewLayout(view, params)
            CrashLogger.d("Overlay layout updated: x=${params.x} y=${params.y}")
        } catch (e: Exception) {
            CrashLogger.e("Failed to update overlay layout", e)
        }
    }

    /**
     * Starts [AudioPulseEngine] capture for the real-audio EqualizerBars /
     * MusicWaveStrings sync, but only if RECORD_AUDIO has actually been
     * granted. Safe to call even if it hasn't — just no-ops and the visuals
     * fall back to their generic animated loop (handled inside the
     * composables themselves, nothing else to wire here).
     */
    private fun startAudioPulseIfPermitted() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            CrashLogger.i("RECORD_AUDIO not granted — EqualizerBars/MusicWaveStrings will use fallback animation")
            return
        }
        val started = AudioPulseEngine.start()
        CrashLogger.i("AudioPulseEngine start() -> $started")
    }

    /** True if either slot currently holds Media content with isPlaying=true. */
    private fun isAnyMediaPlaying(state: IslandState): Boolean {
        fun playing(c: IslandContent?) = (c as? IslandContent.Media)?.isPlaying == true
        return when (state) {
            is IslandState.Compact -> playing(state.content) || playing(state.secondary)
            is IslandState.Expanded -> playing(state.content) || playing(state.secondary)
            is IslandState.Minimal -> false
        }
    }

    private fun handleAction(action: String) {
        CrashLogger.d("Island action: $action")
        when {
            action == "media_play_pause" -> mediaTracker?.togglePlayPause()
            action == "media_next" -> mediaTracker?.skipToNext()
            action == "media_prev" -> mediaTracker?.skipToPrevious()
            action.startsWith("notif_action:") -> {
                // notif_action:<sbnKey>:<index>
                val parts = action.removePrefix("notif_action:").rsplit(":", limit = 1)
                if (parts.size == 2) {
                    val key = parts[0]
                    val index = parts[1].toIntOrNull() ?: return
                    NotificationActionStore.sendAction(this, key, index)
                }
            }
            action.startsWith("notif_reply:") -> {
                // notif_reply:<sbnKey>:<index>:<text>
                // key may contain ':' so parse from the right: last is text, before that index, rest is key
                val body = action.removePrefix("notif_reply:")
                val lastColon = body.lastIndexOf(':')
                if (lastColon <= 0) return
                val text = body.substring(lastColon + 1)
                val beforeText = body.substring(0, lastColon)
                val idxColon = beforeText.lastIndexOf(':')
                if (idxColon <= 0) return
                val index = beforeText.substring(idxColon + 1).toIntOrNull() ?: return
                val key = beforeText.substring(0, idxColon)
                NotificationActionStore.sendReply(this, key, index, text)
            }
            action.startsWith("notif_open:") -> {
                // notif_open:<sbnKey>:<packageName>
                val body = action.removePrefix("notif_open:")
                val idx = body.lastIndexOf(':')
                val key: String
                val pkg: String?
                if (idx > 0) {
                    key = body.substring(0, idx)
                    pkg = body.substring(idx + 1).ifBlank { null }
                } else {
                    key = body
                    pkg = null
                }
                NotificationActionStore.openContent(this, key, pkg)
            }
            action.startsWith("ringer:") -> {
                val modeName = action.removePrefix("ringer:")
                val mode = when (modeName.uppercase()) {
                    "NORMAL" -> RingerMode.NORMAL
                    "VIBRATE" -> RingerMode.VIBRATE
                    "SILENT" -> RingerMode.SILENT
                    "DND" -> RingerMode.DND
                    else -> return
                }
                // Optimistic UI update so Expanded chips reflect the new mode immediately
                IslandBus.show(IslandContent.Ringer(mode = mode))
                serviceScope.launch {
                    SystemControl.setRingerMode(mode)
                }
            }
            action.startsWith("flashlight:") -> {
                val on = action.removePrefix("flashlight:") == "on"
                // Optimistic UI update so Expanded stays open and chips reflect
                // the new state immediately (torch callback may lag or miss).
                IslandBus.show(IslandContent.Flashlight(enabled = on))
                serviceScope.launch {
                    SystemControl.setFlashlight(on)
                }
            }
            action.startsWith("bluetooth:") -> {
                val on = action.removePrefix("bluetooth:") == "on"
                serviceScope.launch {
                    SystemControl.setBluetooth(on)
                }
            }
            action.startsWith("airplane:") -> {
                val on = action.removePrefix("airplane:") == "on"
                serviceScope.launch {
                    SystemControl.setAirplaneMode(on)
                }
            }
            action.startsWith("wifi:") -> {
                val on = action.removePrefix("wifi:") == "on"
                IslandBus.show(IslandContent.Wifi(enabled = on))
                serviceScope.launch {
                    SystemControl.setWifi(on)
                }
            }
            action.startsWith("cellular:") -> {
                val on = action.removePrefix("cellular:") == "on"
                // Optimistic UI
                IslandBus.show(IslandContent.CellularData(enabled = on))
                serviceScope.launch {
                    val ok = SystemControl.setCellularData(on)
                    CrashLogger.i("cellular toggle requested=$on success=$ok")
                    // Re-show from real state (may correct optimistic UI if toggle failed)
                    kotlinx.coroutines.delay(350)
                    eventMonitor?.notifyCellularToggled(on)
                    if (!ok) {
                        // Toggle failed — snap UI back to opposite state
                        eventMonitor?.notifyCellularToggled(!on)
                    }
                }
            }
            action.startsWith("hotspot:") -> {
                val on = action.removePrefix("hotspot:") == "on"
                // Optimistic UI so Expanded On/Off chips update immediately
                // (previously only the system broadcast updated the island,
                // so tapping On looked broken while setHotspot was in flight).
                IslandBus.show(IslandContent.Hotspot(enabled = on))
                serviceScope.launch {
                    val ok = SystemControl.setHotspot(on)
                    CrashLogger.i("hotspot toggle requested=$on success=$ok")
                    if (!ok) {
                        // Snap back if the toggle failed
                        kotlinx.coroutines.delay(300)
                        IslandBus.show(IslandContent.Hotspot(enabled = !on))
                    }
                }
            }
            action.startsWith("location:") -> {
                val on = action.removePrefix("location:") == "on"
                // Optimistic UI + pin monitor so ContentObserver cannot snap
                // the chip back to the previous state while LocationManager lags.
                eventMonitor?.notifyLocationToggled(on)
                serviceScope.launch {
                    val ok = SystemControl.setLocation(on)
                    CrashLogger.i("location toggle requested=$on success=$ok")
                    // Re-confirm after settings settle
                    kotlinx.coroutines.delay(400)
                    if (!ok) {
                        eventMonitor?.notifyLocationToggled(!on)
                    } else {
                        // Keep UI aligned with the requested state; observer
                        // remains suppressed for ~2.5s after notifyLocationToggled.
                        eventMonitor?.notifyLocationToggled(on)
                    }
                }
            }
            action == "hold_expand" -> {
                // Reply field opened — cancel the 4s notification timer.
                IslandBus.holdForReply()
            }
        }
    }

    private fun String.rsplit(sep: String, limit: Int): List<String> {
        if (limit <= 1) return listOf(this)
        val idx = lastIndexOf(sep)
        return if (idx < 0) listOf(this)
        else listOf(substring(0, idx), substring(idx + sep.length))
    }

    private fun resolveYOffset(userOffsetY: Int): Int {
        if (userOffsetY >= 0) {
            CrashLogger.i("Island Y offset=$userOffsetY (user custom)")
            return userOffsetY
        }
        return getDefaultYOffset()
    }

    private fun getDefaultYOffset(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (resId > 0) resources.getDimensionPixelSize(resId) else 48
        val y = (statusBarHeight * 0.25f).toInt().coerceAtLeast(8)
        CrashLogger.i("Island Y offset=$y (statusBar=$statusBarHeight) mode=auto")
        return y
    }

    private fun createNotification(): Notification {
        val channelId = "island_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Hyper Island Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.island_service_notification))
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        OrientationMonitor.update(newConfig)
        CrashLogger.d("Orientation changed: landscape=${OrientationMonitor.isLandscape.value}")
    }

    override fun onDestroy() {
        CrashLogger.i("IslandOverlayService onDestroy")
        try { unregisterReceiver(layoutReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(fullscreenReceiver) } catch (_: Exception) {}
        ScreenStateMonitor.stop(this)
        mediaTracker?.stop()
        eventMonitor?.stop()
        AudioPulseEngine.stop()
        try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        // Safety net: if the Island is gone (crash, force-stop, "Stop Island"), make sure
        // any status icons hidden by SystemUIIslandHook are always restored.
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
        /** Off-screen parking coordinate for the inert (hidden) overlay window. */
        private const val HIDDEN_PARK_OFFSET = -10_000

        fun start(context: Context) {
            val intent = Intent(context, IslandOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IslandOverlayService::class.java))
        }

        fun restart(context: Context) {
            stop(context)
            // Small delay via handler so stop completes
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                start(context)
            }, 300)
        }
    }
}

private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
}
