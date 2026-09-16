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
import com.hyperisland.root.util.OrientationMonitor
import com.hyperisland.root.util.SystemEventMonitor
import com.hyperisland.root.ui.island.IslandBus
import com.hyperisland.root.ui.island.IslandPreferences
import com.hyperisland.root.ui.island.OrientationAwareIsland
import com.hyperisland.root.util.CrashLogger
import com.hyperisland.root.xposed.StatusBarIconBridge
import kotlinx.coroutines.*

class IslandOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
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

    override fun onCreate() {
        super.onCreate()
        CrashLogger.i("IslandOverlayService onCreate")
        IslandPreferences.init(this)
        OrientationMonitor.init(this)
        startForeground(NOTIFICATION_ID, createNotification())
        showOverlay()
        mediaTracker = MediaSessionTracker.get(this).also { it.start() }
        eventMonitor = SystemEventMonitor(this).also { it.start() }
        startAudioPulseIfPermitted()

        // Flow-based live updates
        serviceScope.launch {
            IslandPreferences.config.collect {
                applyLayoutParams()
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
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val cfg = IslandPreferences.config.value
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
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
                // Key on layout so size changes force full recomposition of the island
                androidx.compose.runtime.key(layout) {
                    OrientationAwareIsland(
                        state = state,
                        isLandscape = isLandscape,
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

    private fun applyLayoutParams() {
        val view = overlayView ?: return
        val params = layoutParams ?: return
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

    private fun handleAction(action: String) {
        CrashLogger.d("Island action: $action")
        // Media play/pause/skip controls were removed from the live media UI
        // (title + artist only now) — no action strings currently dispatch to
        // MediaSessionTracker anymore. Kept as a hook for future non-media actions.
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
        mediaTracker?.stop()
        eventMonitor?.stop()
        AudioPulseEngine.stop()
        try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        // Safety net: if the Island is gone (crash, force-stop, "Stop Island"), make sure
        // any status icons hidden by SystemUIIslandHook are always restored.
        try { StatusBarIconBridge.sendVisibility(this, hide = false) } catch (_: Exception) {}
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1001

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
