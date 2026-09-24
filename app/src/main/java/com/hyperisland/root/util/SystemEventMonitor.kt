package com.hyperisland.root.util

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.database.ContentObserver
import android.provider.Settings
import android.telephony.TelephonyManager
import com.hyperisland.root.system.RingerMode
import com.hyperisland.root.ui.island.IslandBus
import com.hyperisland.root.ui.island.IslandContent

/**
 * Listens to REAL system events and pushes them to the Island automatically.
 * Works after "Start Island" — not only Quick Test buttons.
 */
class SystemEventMonitor(private val context: Context) {

    companion object {
        @Volatile
        var lastKnownTorchEnabled: Boolean = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraManager: CameraManager? = null
    private var torchCallback: CameraManager.TorchCallback? = null
    private var lastRingerMode: Int? = null
    private var lastCharging: Boolean? = null
    private var lastTorch: Boolean? = null
    private val torchByCamera = mutableMapOf<String, Boolean>()
    private var lastBt: Boolean? = null
    private var lastAirplane: Boolean? = null
    private var lastWifi: Boolean? = null
    private var lastCellular: Boolean? = null
    private var lastLocation: Boolean? = null
    /** Ignore ContentObserver pushes until this uptime (ms) after a user toggle. */
    @Volatile private var locationSuppressUntil: Long = 0L
    private var registered = false
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private var mobileDataObserver: ContentObserver? = null
    private var locationObserver: ContentObserver? = null

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
                    lastCharging = false
                    val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    if (sticky != null) onBatteryChanged(sticky)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    CrashLogger.i("System: POWER_DISCONNECTED")
                    lastCharging = true
                    IslandBus.show(
                        IslandContent.Charging(level = readBatteryLevel(), isCharging = false)
                    )
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
                    val state = intent.getIntExtra("wifi_state", 0)
                    val apOn = state == 13
                    CrashLogger.i("System: hotspot state=$state apOn=$apOn")
                    if (apOn) {
                        IslandBus.show(IslandContent.Hotspot(enabled = true))
                    } else if (state == 11) {
                        IslandBus.show(IslandContent.Hotspot(enabled = false))
                    }
                }
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                    when (state) {
                        WifiManager.WIFI_STATE_ENABLED -> onWifiChanged(true)
                        WifiManager.WIFI_STATE_DISABLED -> onWifiChanged(false)
                    }
                }
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    // SSID / connection details may have changed while Wi-Fi stays on
                    if (lastWifi == true) {
                        IslandBus.show(buildWifiContent(enabled = true))
                    }
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
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
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
                    mainHandler.post { onTorchChanged(cameraId, enabled) }
                }
            }
            cameraManager?.registerTorchCallback(torchCallback!!, mainHandler)
            CrashLogger.i("SystemEventMonitor: torch callback registered")
        } catch (t: Throwable) {
            CrashLogger.e("Torch callback failed", t)
        }

        // Mobile data / cellular via ConnectivityManager NetworkCallback
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            connectivityCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    mainHandler.post { onCellularChanged(true) }
                }
                override fun onLost(network: Network) {
                    mainHandler.post {
                        // Only report off if no other cellular network remains
                        val stillUp = cm.getNetworkCapabilities(cm.activeNetwork)
                            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                        if (!stillUp) onCellularChanged(false)
                    }
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    mainHandler.post {
                        // Capability changes (validated/internet) on a cellular transport
                        // do not imply mobile-data was toggled — onCellularChanged already
                        // prefers the explicit mobile_data setting, so only refresh when
                        // the transport is cellular.
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            onCellularChanged(isMobileDataEnabled())
                        }
                    }
                }
            }
            cm.registerNetworkCallback(request, connectivityCallback!!)
            // Seed initial mobile-data preference state
            lastCellular = isMobileDataEnabled()
            CrashLogger.i("SystemEventMonitor: cellular NetworkCallback registered")
        } catch (t: Throwable) {
            CrashLogger.e("Cellular NetworkCallback failed", t)
        }

        // Observe standard mobile_data setting (QS toggle while on Wi-Fi)
        try {
            mobileDataObserver = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    val on = isMobileDataEnabled()
                    CrashLogger.i("System: mobile_data setting -> $on")
                    if (on != lastCellular) {
                        lastCellular = on
                        IslandBus.show(buildCellularContent(on))
                    }
                }
            }
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor("mobile_data"),
                false,
                mobileDataObserver!!
            )
            CrashLogger.i("SystemEventMonitor: mobile_data ContentObserver registered")
        } catch (t: Throwable) {
            CrashLogger.e("mobile_data observer failed", t)
        }

        // Location mode (GPS / location services on/off)
        try {
            locationObserver = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    val on = isLocationEnabled()
                    if (on == lastLocation) return
                    // During programmatic toggle the observer can fire with a
                    // stale LocationManager reading and snap the Expanded chip
                    // back to the previous state. Suppress those echoes briefly.
                    if (android.os.SystemClock.uptimeMillis() < locationSuppressUntil) {
                        CrashLogger.d("System: location observer suppressed (stale=$on, keep=$lastLocation)")
                        return
                    }
                    lastLocation = on
                    CrashLogger.i("System: location -> $on")
                    IslandBus.show(IslandContent.Location(enabled = on))
                }
            }
            @Suppress("DEPRECATION")
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCATION_MODE),
                false,
                locationObserver!!
            )
            // Also watch the older providers list for pre-API-28 / OEM variants
            try {
                context.contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor("location_providers_allowed"),
                    false,
                    locationObserver!!
                )
            } catch (_: Exception) {}
            lastLocation = isLocationEnabled()
            CrashLogger.i("SystemEventMonitor: location ContentObserver registered (seed=$lastLocation)")
        } catch (t: Throwable) {
            CrashLogger.e("location observer failed", t)
        }

        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            lastRingerMode = am.ringerMode
        } catch (_: Exception) {}

        // Seed Wi-Fi initial state (do not show island on start)
        try {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lastWifi = wm.isWifiEnabled
        } catch (_: Exception) {}
    }

    fun stop() {
        if (registered) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            registered = false
        }
        try {
            torchCallback?.let { cameraManager?.unregisterTorchCallback(it) }
        } catch (_: Exception) {}
        torchCallback = null
        try {
            connectivityCallback?.let {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {}
        connectivityCallback = null
        try {
            mobileDataObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        } catch (_: Exception) {}
        mobileDataObserver = null
        try {
            locationObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        } catch (_: Exception) {}
        locationObserver = null
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
        IslandBus.show(IslandContent.Ringer(ringer))
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
                IslandBus.show(IslandContent.Ringer(RingerMode.DND))
            }
        } catch (t: Throwable) {
            CrashLogger.e("DND read failed", t)
        }
    }

    private fun onTorchChanged(cameraId: String, enabled: Boolean) {
        torchByCamera[cameraId] = enabled
        val anyOn = torchByCamera.values.any { it }
        if (anyOn == lastTorch) return
        lastTorch = anyOn
        lastKnownTorchEnabled = anyOn
        CrashLogger.i("System: torch -> $anyOn (cam=$cameraId raw=$enabled)")
        IslandBus.show(IslandContent.Flashlight(enabled = anyOn))
    }

    private fun onBluetoothChanged(enabled: Boolean) {
        if (enabled == lastBt) return
        lastBt = enabled
        CrashLogger.i("System: bluetooth -> $enabled")
        IslandBus.show(IslandContent.Bluetooth(enabled = enabled))
    }

    private fun onAirplaneChanged(on: Boolean) {
        if (on == lastAirplane) return
        lastAirplane = on
        CrashLogger.i("System: airplane -> $on")
        IslandBus.show(
            IslandContent.Custom(
                title = if (on) "Airplane mode On" else "Airplane mode Off",
                subtitle = null
            )
        )
    }

    private fun onWifiChanged(enabled: Boolean) {
        if (enabled == lastWifi) return
        lastWifi = enabled
        CrashLogger.i("System: wifi -> $enabled")
        IslandBus.show(buildWifiContent(enabled))
    }

    private fun onCellularChanged(enabled: Boolean) {
        // Prefer the explicit mobile-data setting when available
        val dataOn = try { isMobileDataEnabled() } catch (_: Exception) { enabled }
        if (dataOn == lastCellular) return
        lastCellular = dataOn
        CrashLogger.i("System: cellular data -> $dataOn")
        IslandBus.show(buildCellularContent(dataOn))
    }

    /** Public entry for OverlayService after a successful toggle action. */
    fun notifyCellularToggled(enabled: Boolean) {
        lastCellular = enabled
        IslandBus.show(buildCellularContent(enabled))
    }

    /**
     * Called after the user toggles location from the Expanded Island.
     * Pins [lastLocation] immediately and suppresses ContentObserver echoes
     * that may still report the pre-toggle LocationManager value.
     */
    fun notifyLocationToggled(enabled: Boolean) {
        lastLocation = enabled
        locationSuppressUntil = android.os.SystemClock.uptimeMillis() + 2_500L
        IslandBus.show(IslandContent.Location(enabled = enabled))
        CrashLogger.i("notifyLocationToggled enabled=$enabled")
    }

    private fun buildWifiContent(enabled: Boolean): IslandContent.Wifi {
        if (!enabled) return IslandContent.Wifi(enabled = false)
        var ssid: String? = null
        var signal = 0
        try {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            if (info != null) {
                val raw = info.ssid
                if (raw != null && raw != "<unknown ssid>" && raw != "0x") {
                    ssid = raw.removePrefix("\"").removeSuffix("\"")
                }
                // RSSI → 0..4 bars (API 30+ instance method; older static 2-arg)
                @Suppress("DEPRECATION")
                val rssi = info.rssi
                signal = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    wm.calculateSignalLevel(rssi).coerceIn(0, 4)
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.calculateSignalLevel(rssi, 5).coerceIn(0, 4)
                }
            }
        } catch (t: Throwable) {
            CrashLogger.w("Wifi info read failed: ${t.message}")
        }
        return IslandContent.Wifi(enabled = true, ssid = ssid, signalLevel = signal)
    }

    private fun buildCellularContent(enabled: Boolean): IslandContent.CellularData {
        if (!enabled) return IslandContent.CellularData(enabled = false)
        var networkType: String? = null
        var operator: String? = null
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            operator = tm.networkOperatorName?.takeIf { it.isNotBlank() }
            @Suppress("DEPRECATION")
            networkType = when (tm.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSDPA -> "HSPA"
                TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                TelephonyManager.NETWORK_TYPE_UNKNOWN -> null
                else -> "Data"
            }
        } catch (t: Throwable) {
            CrashLogger.w("Telephony info read failed: ${t.message}")
        }
        return IslandContent.CellularData(
            enabled = true,
            networkType = networkType,
            operatorName = operator
        )
    }

    private fun isMobileDataEnabled(): Boolean {
        // 1) TelephonyManager.isDataEnabled (API 26+) — standard AOSP
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                return tm.isDataEnabled
            }
        } catch (_: Exception) {}
        // 2) Settings.Global.mobile_data
        try {
            return Settings.Global.getInt(context.contentResolver, "mobile_data", 1) == 1
        } catch (_: Exception) {}
        // 3) Active cellular transport
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun isLocationEnabled(): Boolean {
        // 1) LocationManager.isLocationEnabled (API 28+)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                return lm.isLocationEnabled
            }
        } catch (_: Exception) {}
        // 2) Settings.Secure.LOCATION_MODE (0 = off)
        try {
            @Suppress("DEPRECATION")
            return Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0
        } catch (_: Exception) {}
        // 3) Legacy location_providers_allowed
        return try {
            val providers = Settings.Secure.getString(context.contentResolver, "location_providers_allowed")
            !providers.isNullOrBlank() && providers != "0"
        } catch (_: Exception) {
            false
        }
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
            )
        )
    }

    private fun readBatteryLevel(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) { -1 }
    }
}
