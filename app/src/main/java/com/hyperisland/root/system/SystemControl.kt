package com.hyperisland.root.system

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import com.hyperisland.root.shizuku.ShizukuHelper
import com.hyperisland.root.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class RingerMode { SILENT, VIBRATE, NORMAL, DND }

/**
 * System toggles for the standalone non-root build.
 * Uses public APIs first, then Shizuku when available.
 */
object SystemControl {
    private const val TAG = "SystemControl"
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        ShizukuHelper.init(context)
    }

    fun isShizukuReady(): Boolean = ShizukuHelper.isReady()

    suspend fun setFlashlight(enabled: Boolean): Boolean {
        val camOk = withContext(Dispatchers.Main) {
            try {
                val ctx = appContext ?: return@withContext false
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                    ?: return@withContext false
                val id = cm.cameraIdList.firstOrNull { camId ->
                    val chars = cm.getCameraCharacteristics(camId)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING)
                    val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    facing == CameraCharacteristics.LENS_FACING_BACK && flash
                } ?: cm.cameraIdList.firstOrNull()
                if (id != null) {
                    cm.setTorchMode(id, enabled)
                    true
                } else false
            } catch (t: Throwable) {
                CrashLogger.w("$TAG: setFlashlight failed: ${t.message}")
                false
            }
        }
        if (camOk) return true
        if (!ShizukuHelper.isReady()) return false
        val value = if (enabled) "1" else "0"
        return ShizukuHelper.runCommand(
            "echo $value > /sys/class/leds/torch-light0/brightness 2>/dev/null || " +
                "echo $value > /sys/class/leds/led:torch_0/brightness 2>/dev/null || " +
                "cmd torch $value 2>/dev/null || true"
        )
    }

    suspend fun setRingerMode(mode: RingerMode): Boolean {
        try {
            val ctx = appContext ?: return false
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            when (mode) {
                RingerMode.SILENT -> am.ringerMode = AudioManager.RINGER_MODE_SILENT
                RingerMode.VIBRATE -> am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                RingerMode.NORMAL -> am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                RingerMode.DND -> { /* policy API */ }
            }
            return true
        } catch (t: Throwable) {
            CrashLogger.w("$TAG: setRingerMode failed: ${t.message}")
        }
        if (!ShizukuHelper.isReady()) return false
        val cmd = when (mode) {
            RingerMode.NORMAL -> "settings put system mode_ringer 2; cmd notification set_dnd off"
            RingerMode.VIBRATE -> "settings put system mode_ringer 1; cmd notification set_dnd off"
            RingerMode.SILENT -> "settings put system mode_ringer 0; cmd notification set_dnd off"
            RingerMode.DND -> "cmd notification set_dnd priority"
        }
        return ShizukuHelper.runCommand(cmd)
    }

    suspend fun setBluetooth(enabled: Boolean): Boolean {
        if (!ShizukuHelper.isReady()) return false
        val value = if (enabled) "1" else "0"
        val svc = if (enabled) "enable" else "disable"
        return ShizukuHelper.runCommand(
            "settings put global bluetooth_on $value; svc bluetooth $svc 2>/dev/null || true"
        )
    }

    suspend fun setAirplaneMode(enabled: Boolean): Boolean {
        if (!ShizukuHelper.isReady()) return false
        val value = if (enabled) "1" else "0"
        return ShizukuHelper.runCommand(
            "settings put global airplane_mode_on $value; " +
                "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled"
        )
    }

    suspend fun setWifi(enabled: Boolean): Boolean {
        try {
            val ctx = appContext ?: return false
            @Suppress("DEPRECATION")
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            if (wm.setWifiEnabled(enabled)) return true
        } catch (t: Throwable) {
            CrashLogger.w("$TAG: WifiManager failed: ${t.message}")
        }
        if (!ShizukuHelper.isReady()) return false
        return ShizukuHelper.runCommand("svc wifi ${if (enabled) "enable" else "disable"}")
    }

    suspend fun setCellularData(enabled: Boolean): Boolean {
        if (!ShizukuHelper.isReady()) return false
        val onOff = if (enabled) "1" else "0"
        val enableDisable = if (enabled) "enable" else "disable"
        return ShizukuHelper.runCommand(
            "svc data $enableDisable; settings put global mobile_data $onOff; " +
                "cmd phone data $enableDisable 2>/dev/null || true"
        )
    }

    suspend fun setHotspot(enabled: Boolean): Boolean {
        if (!ShizukuHelper.isReady()) return false
        val cmd = if (enabled) {
            "cmd connectivity tethering wifi on 2>/dev/null; " +
                "cmd wifi start-softap HyperIsland wpa2 HyperIsland123 2>/dev/null || " +
                "cmd wifi start-softap HyperIsland 2 2>/dev/null || true"
        } else {
            "cmd connectivity tethering wifi off 2>/dev/null; cmd wifi stop-softap 2>/dev/null || true"
        }
        return ShizukuHelper.runCommand(cmd)
    }

    suspend fun setLocation(enabled: Boolean): Boolean {
        if (!ShizukuHelper.isReady()) return false
        // Prefer location_mode only — location_providers_allowed is deprecated and
        // writing an empty value can race with ContentObservers / LocationManager
        // and briefly report the previous state (UI chip snaps back to On).
        val mode = if (enabled) "3" else "0"
        val ok = ShizukuHelper.runCommand("settings put secure location_mode $mode")
        if (ok && enabled) {
            // Best-effort enable classic providers on older ROMs (ignore failure).
            ShizukuHelper.runCommand(
                "settings put secure location_providers_allowed gps,network 2>/dev/null || true"
            )
        }
        return ok
    }
}
