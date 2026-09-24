package com.hyperisland.root.util

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import com.hyperisland.root.media.MediaSessionTracker
import com.hyperisland.root.system.RingerMode
import com.hyperisland.root.ui.island.IslandContent

/**
 * Builds an ordered snapshot of live-event control cards for MultiExpanded.
 * Excludes Notification and Charging as requested.
 * Order base: music, wifi, cellular, flashlight, ringer, bluetooth, hotspot, location, airplane.
 */
object SystemStateSnapshot {

    fun build(context: Context, mediaTracker: MediaSessionTracker?): List<IslandContent> {
        val items = mutableListOf<IslandContent>()

        // Music / Media (if any active session)
        mediaTracker?.currentMediaContent()?.let { items.add(it) }

        // Wi-Fi
        items.add(buildWifi(context))

        // Cellular data
        items.add(buildCellular(context))

        // Flashlight
        items.add(buildFlashlight(context))

        // Ringer
        items.add(buildRinger(context))

        // Bluetooth
        items.add(buildBluetooth())

        // Hotspot
        items.add(buildHotspot(context))

        // Location
        items.add(buildLocation(context))

        // Airplane
        items.add(buildAirplane(context))

        return items
    }

    private fun buildWifi(context: Context): IslandContent.Wifi {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val enabled = wm.isWifiEnabled
            var ssid: String? = null
            var level = 0
            if (enabled) {
                try {
                    @Suppress("DEPRECATION")
                    val info = wm.connectionInfo
                    ssid = info?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" }
                    level = WifiManager.calculateSignalLevel(info?.rssi ?: -100, 5)
                } catch (_: Exception) {}
            }
            IslandContent.Wifi(enabled = enabled, ssid = ssid, signalLevel = level)
        } catch (_: Exception) {
            IslandContent.Wifi(enabled = false)
        }
    }

    private fun buildCellular(context: Context): IslandContent.CellularData {
        val enabled = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                tm.isDataEnabled
            } else {
                Settings.Global.getInt(context.contentResolver, "mobile_data", 1) == 1
            }
        } catch (_: Exception) {
            false
        }
        return IslandContent.CellularData(enabled = enabled)
    }

    private fun buildFlashlight(context: Context): IslandContent.Flashlight {
        // Prefer the live aggregate tracked by SystemEventMonitor. Falling back
        // to hard-coded false made every MultiExpanded refresh snap the torch
        // card off even while the physical LED was on.
        return IslandContent.Flashlight(enabled = SystemEventMonitor.lastKnownTorchEnabled)
    }

    private fun buildRinger(context: Context): IslandContent.Ringer {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val mode = when (am.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
                AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
                else -> RingerMode.NORMAL
            }
            IslandContent.Ringer(mode = mode)
        } catch (_: Exception) {
            IslandContent.Ringer(mode = RingerMode.NORMAL)
        }
    }

    private fun buildBluetooth(): IslandContent.Bluetooth {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            IslandContent.Bluetooth(enabled = adapter?.isEnabled == true)
        } catch (_: Exception) {
            IslandContent.Bluetooth(enabled = false)
        }
    }

    private fun buildHotspot(context: Context): IslandContent.Hotspot {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            val enabled = method.invoke(wm) as? Boolean ?: false
            IslandContent.Hotspot(enabled = enabled)
        } catch (_: Exception) {
            IslandContent.Hotspot(enabled = false)
        }
    }

    private fun buildLocation(context: Context): IslandContent.Location {
        val enabled = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                lm.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0
            }
        } catch (_: Exception) {
            false
        }
        return IslandContent.Location(enabled = enabled)
    }

    private fun buildAirplane(context: Context): IslandContent.Custom {
        val on = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        } catch (_: Exception) {
            false
        }
        return IslandContent.Custom(
            title = if (on) "Airplane mode On" else "Airplane mode Off",
            subtitle = if (on) "On" else "Off"
        )
    }
}
