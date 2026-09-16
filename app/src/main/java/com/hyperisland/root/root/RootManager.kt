package com.hyperisland.root.root

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
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
        if (!isRooted()) {
            CrashLogger.w("setFlashlight: no root")
            return false
        }
        val value = if (enabled) "1" else "0"
        val result = runRootCommand(
            "echo $value > /sys/class/leds/torch-light0/brightness 2>/dev/null || " +
            "echo $value > /sys/class/leds/led:torch_0/brightness 2>/dev/null || " +
            "echo $value > /sys/class/leds/flashlight/brightness 2>/dev/null || " +
            "cmd torch $value 2>/dev/null || true"
        )
        CrashLogger.d("setFlashlight result success=${result.isSuccess} out=${result.out} err=${result.err}")
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

    suspend fun getChargingInfo(): ChargingInfo {
        // Prefer public BatteryManager API (no root needed)
        try {
            val ctx = appContext
            if (ctx != null) {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                val statusIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val status = statusIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val temp = (statusIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                return ChargingInfo(
                    isCharging = isCharging,
                    level = level,
                    currentMa = currentUa / 1000,
                    voltageMv = 0,
                    temperatureC = temp
                )
            }
        } catch (t: Throwable) {
            CrashLogger.w("BatteryManager failed: ${t.message}")
        }

        if (!isRooted()) return ChargingInfo()
        val status = runRootCommand("cat /sys/class/power_supply/battery/status").out.firstOrNull() ?: "Unknown"
        val level = runRootCommand("cat /sys/class/power_supply/battery/capacity").out.firstOrNull()?.toIntOrNull() ?: -1
        val current = runRootCommand("cat /sys/class/power_supply/battery/current_now").out.firstOrNull()?.toLongOrNull() ?: 0L
        val voltage = runRootCommand("cat /sys/class/power_supply/battery/voltage_now").out.firstOrNull()?.toLongOrNull() ?: 0L
        val temp = runRootCommand("cat /sys/class/power_supply/battery/temp").out.firstOrNull()?.toIntOrNull()?.div(10f) ?: 0f
        return ChargingInfo(
            isCharging = status.contains("Charging", ignoreCase = true),
            level = level,
            currentMa = (current / 1000).toInt(),
            voltageMv = (voltage / 1000).toInt(),
            temperatureC = temp
        )
    }

    suspend fun isBluetoothEnabled(): Boolean {
        val result = runRootCommand("settings get global bluetooth_on")
        return result.out.firstOrNull()?.trim() == "1"
    }

    suspend fun isHotspotEnabled(): Boolean {
        val result = runRootCommand("cmd wifi status-tethering 2>/dev/null || dumpsys wifi | grep -i tether")
        return result.out.any { it.contains("enabled", ignoreCase = true) || it.contains("started", ignoreCase = true) }
    }
}

enum class RingerMode { NORMAL, VIBRATE, SILENT, DND }

data class ChargingInfo(
    val isCharging: Boolean = false,
    val level: Int = -1,
    val currentMa: Int = 0,
    val voltageMv: Int = 0,
    val temperatureC: Float = 0f
)
