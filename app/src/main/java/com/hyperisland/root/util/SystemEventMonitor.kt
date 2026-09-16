package com.hyperisland.root.util

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.hyperisland.root.root.RingerMode
import com.hyperisland.root.ui.island.IslandBus
import com.hyperisland.root.ui.island.IslandContent
import com.hyperisland.root.ui.island.IslandState

/**
 * Listens to REAL system events and pushes them to the Island automatically.
 * Works after "Start Island" — not only Quick Test buttons.
 */
class SystemEventMonitor(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraManager: CameraManager? = null
    private var torchCallback: CameraManager.TorchCallback? = null
    private var lastRingerMode: Int? = null
    private var lastCharging: Boolean? = null
    private var lastTorch: Boolean? = null
    private var lastBt: Boolean? = null
    private var lastAirplane: Boolean? = null
    private var registered = false
    private var collapseRunnable: Runnable? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    val mode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)
                    onRingerChanged(mode)
                }
                Intent.ACTION_BATTERY_CHANGED -> onBatteryChanged(intent)
                Intent.ACTION_POWER_CONNECTED -> {
                    CrashLogger.i("System: POWER_CONNECTED")
                    // Force refresh from sticky battery
                    lastCharging = false
                    val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    if (sticky != null) onBatteryChanged(sticky)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    CrashLogger.i("System: POWER_DISCONNECTED")
                    lastCharging = true
                    IslandBus.show(
                        IslandContent.Charging(level = readBatteryLevel(), isCharging = false),
                        expand = true
                    )
                    // Timer handled by IslandBus
                }
                "android.app.action.INTERRUPTION_FILTER_CHANGED" -> onDndChanged()
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    onBluetoothChanged(state == BluetoothAdapter.STATE_ON)
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    val on = intent.getBooleanExtra("state", false)
                    onAirplaneChanged(on)
                }
                "android.net.wifi.WIFI_AP_STATE_CHANGED" -> {
                    // Hidden API constants: WIFI_AP_STATE_ENABLED=13, DISABLED=11
                    val state = intent.getIntExtra("wifi_state", 0)
                    val apOn = state == 13
                    CrashLogger.i("System: hotspot state=$state apOn=$apOn")
                    if (apOn) {
                        IslandBus.show(IslandContent.Hotspot(enabled = true), expand = true)
                    } else if (state == 11) {
                        IslandBus.show(IslandContent.Hotspot(enabled = false), expand = false)
                    }
                    // Timer handled by IslandBus
                }
            }
        }
    }

    fun start() {
        if (registered) return
        try {
            val filter = IntentFilter().apply {
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction("android.app.action.INTERRUPTION_FILTER_CHANGED")
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            registered = true
            CrashLogger.i("SystemEventMonitor: receivers registered")
        } catch (t: Throwable) {
            CrashLogger.e("SystemEventMonitor register failed", t)
        }

        // Real flashlight (Quick Settings / camera torch)
        try {
            cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            torchCallback = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    mainHandler.post { onTorchChanged(enabled) }
                }
            }
            cameraManager?.registerTorchCallback(torchCallback!!, mainHandler)
            CrashLogger.i("SystemEventMonitor: torch callback registered")
        } catch (t: Throwable) {
            CrashLogger.e("Torch callback failed", t)
        }

        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            lastRingerMode = am.ringerMode
        } catch (_: Exception) {}
    }

    fun stop() {
        collapseRunnable?.let { mainHandler.removeCallbacks(it) }
        if (registered) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            registered = false
        }
        try {
            torchCallback?.let { cameraManager?.unregisterTorchCallback(it) }
        } catch (_: Exception) {}
        torchCallback = null
        CrashLogger.i("SystemEventMonitor stopped")
    }

    private fun onRingerChanged(mode: Int) {
        if (mode == lastRingerMode) return
        lastRingerMode = mode
        val ringer = when (mode) {
            AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
            AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
            else -> RingerMode.NORMAL
        }
        CrashLogger.i("System: ringer -> $ringer")
        IslandBus.show(IslandContent.Ringer(ringer), expand = true)
        // Timer handled by IslandBus (4s + restore music if interrupted)
    }

    private fun onDndChanged() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val filter = nm.currentInterruptionFilter
            CrashLogger.i("System: interruption filter=$filter")
            if (filter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL &&
                filter != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            ) {
                IslandBus.show(IslandContent.Ringer(RingerMode.DND), expand = true)
                // Timer handled by IslandBus
            }
        } catch (t: Throwable) {
            CrashLogger.e("DND read failed", t)
        }
    }

    private fun onTorchChanged(enabled: Boolean) {
        if (enabled == lastTorch) return
        lastTorch = enabled
        CrashLogger.i("System: torch -> $enabled")
        IslandBus.show(IslandContent.Flashlight(enabled), expand = false)
        // Timer handled by IslandBus
    }

    private fun onBluetoothChanged(enabled: Boolean) {
        if (enabled == lastBt) return
        lastBt = enabled
        CrashLogger.i("System: bluetooth -> $enabled")
        IslandBus.show(IslandContent.Bluetooth(enabled = enabled), expand = true)
        // Timer handled by IslandBus
    }

    private fun onAirplaneChanged(on: Boolean) {
        if (on == lastAirplane) return
        lastAirplane = on
        CrashLogger.i("System: airplane -> $on")
        IslandBus.show(
            IslandContent.Custom(
                title = if (on) "Airplane mode On" else "Airplane mode Off",
                subtitle = null
            ),
            expand = true
        )
        // Timer handled by IslandBus
    }

    private fun onBatteryChanged(intent: Intent) {
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else level
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f

        if (lastCharging == null) {
            lastCharging = charging
            return
        }
        if (charging == lastCharging) return
        lastCharging = charging

        CrashLogger.i("System: charging -> $charging level=$pct")
        IslandBus.show(
            IslandContent.Charging(
                level = pct,
                isCharging = charging,
                currentMa = 0,
                temperatureC = temp
            ),
            expand = true
        )
        // Timer handled by IslandBus (restore music if interrupted)
    }

    private fun readBatteryLevel(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) { -1 }
    }

    /**
     * Legacy helper — no longer drives auto-dismiss.
     * All timing + music restore is owned by [IslandBus.scheduleAutoCollapse].
     * Kept as a no-op so any remaining call sites are harmless.
     */
    private fun scheduleCollapse(ms: Long) {
        // Intentionally empty. IslandBus owns the 4s non-media timer and
        // restores secondaryMedia (music) when the interrupting event expires.
    }
}

