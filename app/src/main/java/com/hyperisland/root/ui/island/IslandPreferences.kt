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
    val debugLogEnabled: Boolean = false
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
            debugLogEnabled = p.getBoolean(KEY_DEBUG_LOG, false)
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

    fun resetToDefaults() {
        val defaults = IslandLayoutConfig()
        _config.value = defaults
        persist(defaults)
        appContext?.sendBroadcast(Intent(ACTION_LAYOUT_CHANGED).setPackage(appContext!!.packageName))
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
            apply()
        }
    }
}
