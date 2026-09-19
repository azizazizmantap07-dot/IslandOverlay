package com.hyperisland.root.root

import android.content.Context
import android.content.Intent
import com.hyperisland.root.util.CrashLogger
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RootManager {
    private const val TAG = "RootManager"
    @Volatile private var isRootAvailable: Boolean? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        Shell.getShell { shell ->
            isRootAvailable = shell.isRoot
            CrashLogger.i("Root available: $isRootAvailable")
        }
    }

    /** Application context, for callers (e.g. [HeadsUpSuppressor]) that need one to bind services. */
    fun applicationContext(): Context? = appContext

    fun isRooted(): Boolean {
        return isRootAvailable ?: (Shell.isAppGrantedRoot() == true)
    }

    suspend fun runRootCommand(vararg commands: String): Shell.Result = withContext(Dispatchers.IO) {
        Shell.cmd(*commands).exec()
    }

    suspend fun setFlashlight(enabled: Boolean): Boolean {
        // Prefer CameraManager (works without root when the app holds CAMERA /
        // flashlight permission path used by system QS). Fall back to sysfs /
        // cmd torch via root for devices where the public API is restricted.
        val camOk = withContext(Dispatchers.Main) {
            try {
                val ctx = appContext ?: return@withContext false
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                    ?: return@withContext false
                val id = cm.cameraIdList.firstOrNull { camId ->
                    val chars = cm.getCameraCharacteristics(camId)
                    val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                    val flash = chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK && flash
                } ?: cm.cameraIdList.firstOrNull()
                if (id != null) {
                    cm.setTorchMode(id, enabled)
                    CrashLogger.d("setFlashlight via CameraManager id=$id enabled=$enabled")
                    true
                } else false
            } catch (t: Throwable) {
                CrashLogger.w("setFlashlight CameraManager failed: ${t.message}")
                false
            }
        }
        if (camOk) return true

        if (!isRooted()) {
            CrashLogger.w("setFlashlight: no root and CameraManager failed")
            return false
        }
        val value = if (enabled) "1" else "0"
        val result = runRootCommand(
            "echo $value > /sys/class/leds/torch-light0/brightness 2>/dev/null || " +
            "echo $value > /sys/class/leds/led:torch_0/brightness 2>/dev/null || " +
            "echo $value > /sys/class/leds/flashlight/brightness 2>/dev/null || " +
            "cmd torch $value 2>/dev/null || true"
        )
        CrashLogger.d("setFlashlight root result success=${result.isSuccess} out=${result.out} err=${result.err}")
        return result.isSuccess
    }

    suspend fun setRingerMode(mode: RingerMode): Boolean {
        // Try non-root first via AudioManager if possible, then root
        try {
            val ctx = appContext
            if (ctx != null) {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                when (mode) {
                    RingerMode.NORMAL -> am.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
                    RingerMode.VIBRATE -> am.ringerMode = android.media.AudioManager.RINGER_MODE_VIBRATE
                    RingerMode.SILENT -> am.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
                    RingerMode.DND -> { /* needs notification policy access */ }
                }
                if (mode != RingerMode.DND) {
                    CrashLogger.i("Ringer set via AudioManager: $mode")
                    return true
                }
            }
        } catch (t: Throwable) {
            CrashLogger.w("AudioManager ringer failed: ${t.message}")
        }

        if (!isRooted()) return false
        val cmd = when (mode) {
            RingerMode.NORMAL -> "settings put system mode_ringer 2; cmd notification set_dnd off"
            RingerMode.VIBRATE -> "settings put system mode_ringer 1; cmd notification set_dnd off"
            RingerMode.SILENT -> "settings put system mode_ringer 0; cmd notification set_dnd off"
            RingerMode.DND -> "cmd notification set_dnd priority"
        }
        val result = runRootCommand(cmd)
        CrashLogger.d("setRingerMode root success=${result.isSuccess}")
        return result.isSuccess
    }




    suspend fun setBluetooth(enabled: Boolean): Boolean {
        // Prefer public API when available (pre-Android 13); fall back to root/settings.
        try {
            val ctx = appContext
            if (ctx != null) {
                val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
                val adapter = bm?.adapter
                if (adapter != null) {
                    @Suppress("DEPRECATION")
                    val ok = if (enabled) adapter.enable() else adapter.disable()
                    if (ok) {
                        CrashLogger.i("Bluetooth set via adapter: enabled=$enabled")
                        return true
                    }
                }
            }
        } catch (t: Throwable) {
            CrashLogger.w("BluetoothAdapter toggle failed: ${t.message}")
        }
        if (!isRooted()) return false
        val value = if (enabled) "1" else "0"
        val result = runRootCommand(
            "settings put global bluetooth_on $value; " +
                "svc bluetooth ${if (enabled) "enable" else "disable"} 2>/dev/null || true"
        )
        CrashLogger.d("setBluetooth root success=${result.isSuccess}")
        return result.isSuccess
    }

    suspend fun setAirplaneMode(enabled: Boolean): Boolean {
        if (!isRooted()) {
            CrashLogger.w("setAirplaneMode: no root")
            return false
        }
        val value = if (enabled) "1" else "0"
        val result = runRootCommand(
            "settings put global airplane_mode_on $value; " +
                "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled"
        )
        CrashLogger.d("setAirplaneMode root success=${result.isSuccess}")
        return result.isSuccess
    }

    suspend fun setHotspot(enabled: Boolean): Boolean {
        if (!isRooted()) {
            CrashLogger.w("setHotspot: no root")
            return false
        }
        // Prefer connectivity tethering (works on most modern AOSP/OEM builds),
        // then fall back to wifi softap start/stop. The old single-line chain
        // often reported success=true while the AP never actually came up,
        // which made the Expanded "On" chip appear broken.
        val cmd = if (enabled) {
            "cmd connectivity tethering wifi on 2>/dev/null; " +
                "cmd wifi start-softap HyperIsland wpa2 HyperIsland123 2>/dev/null || " +
                "cmd wifi start-softap HyperIsland 2 2>/dev/null || true"
        } else {
            "cmd connectivity tethering wifi off 2>/dev/null; " +
                "cmd wifi stop-softap 2>/dev/null || true"
        }
        val result = runRootCommand(cmd)
        CrashLogger.d("setHotspot root success=${result.isSuccess}")
        // Brief settle so WIFI_AP_STATE_CHANGED can fire; treat shell success
        // as the primary signal (broadcast may lag a few hundred ms).
        if (result.isSuccess) {
            kotlinx.coroutines.delay(200)
            return true
        }
        return false
    }

    suspend fun setWifi(enabled: Boolean): Boolean {
        try {
            val ctx = appContext
            if (ctx != null) {
                @Suppress("DEPRECATION")
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                if (wm.setWifiEnabled(enabled)) {
                    CrashLogger.i("Wifi set via WifiManager: enabled=$enabled")
                    return true
                }
            }
        } catch (t: Throwable) {
            CrashLogger.w("WifiManager toggle failed: ${t.message}")
        }
        if (!isRooted()) return false
        val result = runRootCommand("svc wifi ${if (enabled) "enable" else "disable"}")
        CrashLogger.d("setWifi root success=${result.isSuccess}")
        return result.isSuccess
    }

    suspend fun setCellularData(enabled: Boolean): Boolean {
        val onOff = if (enabled) "1" else "0"
        val enableDisable = if (enabled) "enable" else "disable"

        // Prefer root when available — TelephonyManager.setDataEnabled often
        // returns without error but does nothing unless the app holds
        // MODIFY_PHONE_STATE (normal apps do not).
        if (isRooted()) {
            val cmd = "svc data $enableDisable; " +
                "settings put global mobile_data $onOff; " +
                "cmd phone data $enableDisable 2>/dev/null || true"
            val result = runRootCommand(cmd)
            CrashLogger.i("setCellularData root enabled=$enabled success=${result.isSuccess}")
            // Brief settle then verify
            try {
                kotlinx.coroutines.delay(300)
                val actual = readMobileDataEnabled()
                if (actual == enabled) {
                    CrashLogger.i("setCellularData verified actual=$actual")
                    return true
                }
                CrashLogger.w("setCellularData root ran but actual still $actual, retrying")
                runRootCommand(cmd)
                kotlinx.coroutines.delay(200)
                return readMobileDataEnabled() == enabled
            } catch (t: Throwable) {
                CrashLogger.w("setCellularData verify failed: ${t.message}")
                return result.isSuccess
            }
        }

        // Non-root: try hidden TelephonyManager APIs (usually blocked)
        try {
            val ctx = appContext
            if (ctx != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                try {
                    val m = tm.javaClass.getMethod("setDataEnabled", Boolean::class.javaPrimitiveType)
                    m.invoke(tm, enabled)
                    kotlinx.coroutines.delay(200)
                    if (readMobileDataEnabled() == enabled) {
                        CrashLogger.i("setCellularData via TelephonyManager.setDataEnabled($enabled)")
                        return true
                    }
                } catch (_: Exception) {}
                try {
                    val m = tm.javaClass.getMethod(
                        "setDataEnabledForReason",
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType
                    )
                    m.invoke(tm, 0, enabled)
                    kotlinx.coroutines.delay(200)
                    if (readMobileDataEnabled() == enabled) {
                        CrashLogger.i("setCellularData via setDataEnabledForReason($enabled)")
                        return true
                    }
                } catch (_: Exception) {}
            }
        } catch (t: Throwable) {
            CrashLogger.w("TelephonyManager setData failed: ${t.message}")
        }

        CrashLogger.w("setCellularData: could not change data state to $enabled")
        return false
    }

    /**
     * Toggle system location services (GPS / network).
     * Prefer root first (settings + cmd location), then non-root panel intent.
     */
    suspend fun setLocation(enabled: Boolean): Boolean {
        val mode = if (enabled) "3" else "0" // HIGH_ACCURACY vs OFF
        val providers = if (enabled) "gps,network" else ""

        // 1) Root first
        if (isRooted()) {
            val cmd = "settings put secure location_mode $mode; " +
                "settings put secure location_providers_allowed $providers; " +
                "cmd location set-location-enabled ${if (enabled) "true" else "false"} 2>/dev/null || true"
            val result = runRootCommand(cmd)
            CrashLogger.i("setLocation root enabled=$enabled success=${result.isSuccess}")
            try {
                kotlinx.coroutines.delay(250)
                if (readLocationEnabled() == enabled) {
                    CrashLogger.i("setLocation verified actual=$enabled")
                    return true
                }
                CrashLogger.w("setLocation root ran but state mismatch, retrying")
                runRootCommand(cmd)
                kotlinx.coroutines.delay(200)
                if (readLocationEnabled() == enabled) return true
            } catch (t: Throwable) {
                CrashLogger.w("setLocation verify failed: ${t.message}")
            }
            if (result.isSuccess) return true
        }

        // 2) Non-root: open system location settings so the user can toggle.
        // Note: Settings.Panel has no ACTION_LOCATION — only WIFI / NFC /
        // INTERNET_CONNECTIVITY / VOLUME. Always use LOCATION_SOURCE_SETTINGS.
        try {
            val ctx = appContext ?: return false
            val intent = Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            CrashLogger.i("setLocation: opened location source settings (non-root)")
            return true
        } catch (t: Throwable) {
            CrashLogger.w("setLocation non-root settings failed: ${t.message}")
        }

        CrashLogger.w("setLocation: could not change location state to $enabled")
        return false
    }

    private fun readLocationEnabled(): Boolean {
        try {
            val ctx = appContext ?: return false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                return lm.isLocationEnabled
            }
        } catch (_: Exception) {}
        return try {
            val ctx = appContext ?: return false
            @Suppress("DEPRECATION")
            android.provider.Settings.Secure.getInt(
                ctx.contentResolver,
                android.provider.Settings.Secure.LOCATION_MODE,
                0
            ) != 0
        } catch (_: Exception) {
            false
        }
    }

    /** Read current mobile-data enabled flag (best-effort). */
    private fun readMobileDataEnabled(): Boolean {
        try {
            val ctx = appContext ?: return false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                return tm.isDataEnabled
            }
        } catch (_: Exception) {}
        return try {
            val ctx = appContext ?: return false
            android.provider.Settings.Global.getInt(ctx.contentResolver, "mobile_data", 1) == 1
        } catch (_: Exception) {
            false
        }
    }
}

enum class RingerMode { NORMAL, VIBRATE, SILENT, DND }
