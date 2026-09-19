package com.hyperisland.root.ui.island

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted layout preferences for the Island pill.
 * Offsets are in pixels (WindowManager); sizes are in dp.
 */
data class IslandLayoutConfig(
    val offsetX: Int = 0,
    val offsetY: Int = -1, // -1 = auto
    val minimalWidthDp: Float = 120f,
    val minimalHeightDp: Float = 36f,
    val compactWidthDp: Float = 200f,
    val compactHeightDp: Float = 40f,
    val expandedWidthDp: Float = 320f,
    val expandedHeightDp: Float = 90f,
    val debugLogEnabled: Boolean = false,
    // Per-event: true = user may expand Compact→Expanded on tap; false = Compact only
    val expandNotification: Boolean = true,
    val expandMedia: Boolean = false,
    val expandCharging: Boolean = true,
    val expandRinger: Boolean = false,
    val expandFlashlight: Boolean = false,
    val expandBluetooth: Boolean = false,
    val expandHotspot: Boolean = false,
    val expandWifi: Boolean = false,
    val expandCellular: Boolean = false,
    val expandLocation: Boolean = false,
    val expandCustom: Boolean = false,
    // Edge-ring animation toggles (true = show animated ring)
    val animRingMinimal: Boolean = true,       // ChasingCometRing on Minimal
    val animRingCompact: Boolean = true,       // FullRgbRing on Compact (non-music)
    val animRingExpanded: Boolean = true,      // FullRgbRing / MusicProgressRing on Expanded
    val animRingMusicProgress: Boolean = true, // MusicProgressRing on pure-music Compact/Minimal edge
)

object IslandPreferences {
    const val ACTION_LAYOUT_CHANGED = "com.hyperisland.root.ACTION_LAYOUT_CHANGED"

    private const val PREFS_NAME = "island_layout_prefs"
    private const val KEY_OFFSET_X = "offset_x"
    private const val KEY_OFFSET_Y = "offset_y"
    private const val KEY_MIN_W = "minimal_width"
    private const val KEY_MIN_H = "minimal_height"
    private const val KEY_COMP_W = "compact_width"
    private const val KEY_COMP_H = "compact_height"
    private const val KEY_EXP_W = "expanded_width"
    private const val KEY_EXP_H = "expanded_height"
    private const val KEY_DEBUG_LOG = "debug_log_enabled"
    private const val KEY_EXP_NOTIF = "expand_notification"
    private const val KEY_EXP_MEDIA = "expand_media"
    private const val KEY_EXP_CHARGE = "expand_charging"
    private const val KEY_EXP_RINGER = "expand_ringer"
    private const val KEY_EXP_FLASH = "expand_flashlight"
    private const val KEY_EXP_BT = "expand_bluetooth"
    private const val KEY_EXP_HOTSPOT = "expand_hotspot"
    private const val KEY_EXP_WIFI = "expand_wifi"
    private const val KEY_EXP_CELLULAR = "expand_cellular"
    private const val KEY_EXP_LOCATION = "expand_location"
    private const val KEY_EXP_CUSTOM = "expand_custom"
    private const val KEY_ANIM_RING_MINIMAL = "anim_ring_minimal"
    private const val KEY_ANIM_RING_COMPACT = "anim_ring_compact"
    private const val KEY_ANIM_RING_EXPANDED = "anim_ring_expanded"
    private const val KEY_ANIM_RING_MUSIC = "anim_ring_music_progress"

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    private val _config = MutableStateFlow(IslandLayoutConfig())
    val config: StateFlow<IslandLayoutConfig> = _config.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _config.value = load()
    }

    private fun load(): IslandLayoutConfig {
        val p = prefs ?: return IslandLayoutConfig()
        return IslandLayoutConfig(
            offsetX = p.getInt(KEY_OFFSET_X, 0),
            offsetY = p.getInt(KEY_OFFSET_Y, -1),
            minimalWidthDp = p.getFloat(KEY_MIN_W, 120f),
            minimalHeightDp = p.getFloat(KEY_MIN_H, 36f),
            compactWidthDp = p.getFloat(KEY_COMP_W, 200f),
            compactHeightDp = p.getFloat(KEY_COMP_H, 40f),
            expandedWidthDp = p.getFloat(KEY_EXP_W, 320f),
            expandedHeightDp = p.getFloat(KEY_EXP_H, 90f),
            debugLogEnabled = p.getBoolean(KEY_DEBUG_LOG, false),
            expandNotification = p.getBoolean(KEY_EXP_NOTIF, true),
            expandMedia = p.getBoolean(KEY_EXP_MEDIA, false),
            expandCharging = p.getBoolean(KEY_EXP_CHARGE, true),
            expandRinger = p.getBoolean(KEY_EXP_RINGER, false),
            expandFlashlight = p.getBoolean(KEY_EXP_FLASH, false),
            expandBluetooth = p.getBoolean(KEY_EXP_BT, false),
            expandHotspot = p.getBoolean(KEY_EXP_HOTSPOT, false),
            expandWifi = p.getBoolean(KEY_EXP_WIFI, false),
            expandCellular = p.getBoolean(KEY_EXP_CELLULAR, false),
            expandLocation = p.getBoolean(KEY_EXP_LOCATION, false),
            expandCustom = p.getBoolean(KEY_EXP_CUSTOM, false),
            animRingMinimal = p.getBoolean(KEY_ANIM_RING_MINIMAL, true),
            animRingCompact = p.getBoolean(KEY_ANIM_RING_COMPACT, true),
            animRingExpanded = p.getBoolean(KEY_ANIM_RING_EXPANDED, true),
            animRingMusicProgress = p.getBoolean(KEY_ANIM_RING_MUSIC, true),
        )
    }

    fun update(transform: (IslandLayoutConfig) -> IslandLayoutConfig) {
        // Ensure prefs are ready even if called before Application.onCreate finishes
        appContext?.let { if (prefs == null) init(it) }
        val newConfig = transform(_config.value)
        _config.value = newConfig
        persist(newConfig)
        // Notify overlay service (works even if Flow collection missed a frame)
        appContext?.sendBroadcast(Intent(ACTION_LAYOUT_CHANGED).setPackage(appContext!!.packageName))
        // Re-evaluate which status icons collide with the (possibly resized) Minimal pill.
        IslandBus.refreshIconVisibility()
    }

    fun setOffsetX(value: Int) = update { it.copy(offsetX = value) }
    fun setOffsetY(value: Int) = update { it.copy(offsetY = value) }
    fun setMinimalWidth(value: Float) = update { it.copy(minimalWidthDp = value) }
    fun setMinimalHeight(value: Float) = update { it.copy(minimalHeightDp = value) }
    fun setCompactWidth(value: Float) = update { it.copy(compactWidthDp = value) }
    fun setCompactHeight(value: Float) = update { it.copy(compactHeightDp = value) }
    fun setExpandedWidth(value: Float) = update { it.copy(expandedWidthDp = value) }
    fun setExpandedHeight(value: Float) = update { it.copy(expandedHeightDp = value) }
    fun setDebugLogEnabled(value: Boolean) = update { it.copy(debugLogEnabled = value) }
    fun setExpandNotification(v: Boolean) = update { it.copy(expandNotification = v) }
    fun setExpandMedia(v: Boolean) = update { it.copy(expandMedia = v) }
    fun setExpandCharging(v: Boolean) = update { it.copy(expandCharging = v) }
    fun setExpandRinger(v: Boolean) = update { it.copy(expandRinger = v) }
    fun setExpandFlashlight(v: Boolean) = update { it.copy(expandFlashlight = v) }
    fun setExpandBluetooth(v: Boolean) = update { it.copy(expandBluetooth = v) }
    fun setExpandHotspot(v: Boolean) = update { it.copy(expandHotspot = v) }
    fun setExpandWifi(v: Boolean) = update { it.copy(expandWifi = v) }
    fun setExpandCellular(v: Boolean) = update { it.copy(expandCellular = v) }
    fun setExpandLocation(v: Boolean) = update { it.copy(expandLocation = v) }
    fun setExpandCustom(v: Boolean) = update { it.copy(expandCustom = v) }
    fun setAnimRingMinimal(v: Boolean) = update { it.copy(animRingMinimal = v) }
    fun setAnimRingCompact(v: Boolean) = update { it.copy(animRingCompact = v) }
    fun setAnimRingExpanded(v: Boolean) = update { it.copy(animRingExpanded = v) }
    fun setAnimRingMusicProgress(v: Boolean) = update { it.copy(animRingMusicProgress = v) }

    /** Whether user tap on Compact may expand [content] to Expanded (per toggle). */
    fun shouldExpand(content: IslandContent): Boolean {
        val c = _config.value
        return when (content) {
            is IslandContent.Notification -> c.expandNotification
            is IslandContent.Media -> c.expandMedia
            is IslandContent.Charging -> c.expandCharging
            is IslandContent.Ringer -> c.expandRinger
            is IslandContent.Flashlight -> c.expandFlashlight
            is IslandContent.Bluetooth -> c.expandBluetooth
            is IslandContent.Hotspot -> c.expandHotspot
            is IslandContent.Wifi -> c.expandWifi
            is IslandContent.CellularData -> c.expandCellular
            is IslandContent.Location -> c.expandLocation
            is IslandContent.Custom -> c.expandCustom
        }
    }

    fun resetToDefaults() {
        val defaults = IslandLayoutConfig()
        _config.value = defaults
        persist(defaults)
        appContext?.sendBroadcast(Intent(ACTION_LAYOUT_CHANGED).setPackage(appContext!!.packageName))
        IslandBus.refreshIconVisibility()
    }

    private fun persist(c: IslandLayoutConfig) {
        prefs?.edit()?.apply {
            putInt(KEY_OFFSET_X, c.offsetX)
            putInt(KEY_OFFSET_Y, c.offsetY)
            putFloat(KEY_MIN_W, c.minimalWidthDp)
            putFloat(KEY_MIN_H, c.minimalHeightDp)
            putFloat(KEY_COMP_W, c.compactWidthDp)
            putFloat(KEY_COMP_H, c.compactHeightDp)
            putFloat(KEY_EXP_W, c.expandedWidthDp)
            putFloat(KEY_EXP_H, c.expandedHeightDp)
            putBoolean(KEY_DEBUG_LOG, c.debugLogEnabled)
            putBoolean(KEY_EXP_NOTIF, c.expandNotification)
            putBoolean(KEY_EXP_MEDIA, c.expandMedia)
            putBoolean(KEY_EXP_CHARGE, c.expandCharging)
            putBoolean(KEY_EXP_RINGER, c.expandRinger)
            putBoolean(KEY_EXP_FLASH, c.expandFlashlight)
            putBoolean(KEY_EXP_BT, c.expandBluetooth)
            putBoolean(KEY_EXP_HOTSPOT, c.expandHotspot)
            putBoolean(KEY_EXP_WIFI, c.expandWifi)
            putBoolean(KEY_EXP_CELLULAR, c.expandCellular)
            putBoolean(KEY_EXP_LOCATION, c.expandLocation)
            putBoolean(KEY_EXP_CUSTOM, c.expandCustom)
            putBoolean(KEY_ANIM_RING_MINIMAL, c.animRingMinimal)
            putBoolean(KEY_ANIM_RING_COMPACT, c.animRingCompact)
            putBoolean(KEY_ANIM_RING_EXPANDED, c.animRingExpanded)
            putBoolean(KEY_ANIM_RING_MUSIC, c.animRingMusicProgress)
            apply()
        }
    }
}
