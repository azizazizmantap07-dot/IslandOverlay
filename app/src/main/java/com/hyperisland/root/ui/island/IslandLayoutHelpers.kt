package com.hyperisland.root.ui.island

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Adaptive size tiers. Every branch asks "how much room do I actually have"
// instead of assuming the default 200x40 / 320x90, so the same composable
// works whether the user slider is at 10dp or 400dp.

/** Minimum height of the tappable hit box, regardless of visual pill height. */
internal val MIN_TOUCH_TARGET_HEIGHT = 56.dp
/** Extra horizontal padding around the visual pill for a more forgiving Compact hit area. */
internal val COMPACT_HIT_PAD_H = 12.dp

internal val TINY_WIDTH = 46.dp
internal val NARROW_WIDTH = 96.dp
internal val MIN_HEIGHT_FOR_SUBTITLE = 28.dp
internal val MIN_HEIGHT_FOR_PROGRESS = 42.dp

/**
 * Like [Dp.coerceIn], but never throws when [minimumValue] > [maximumValue]
 * (can happen at extreme low custom sizes). Falls back to the smaller bound.
 */
internal fun Dp.safeCoerceIn(minimumValue: Dp, maximumValue: Dp): Dp {
    val lo = minOf(minimumValue, maximumValue)
    val hi = maxOf(minimumValue, maximumValue)
    return this.coerceIn(lo, hi)
}

/**
 * Stable identity for interruption-badge animations: same ongoing event shares
 * a key so Media position ticks do not replay the pop, but a new event does.
 */
internal fun IslandContent.interruptionKey(): String = when (this) {
    is IslandContent.Notification -> "notif:$packageName:$title:$arrivedAtMs"
    is IslandContent.Media -> "media:$title:$artist"
    is IslandContent.Charging -> "charging:$isCharging"
    is IslandContent.Ringer -> "ringer:$mode"
    is IslandContent.Flashlight -> "flash:$enabled"
    is IslandContent.Bluetooth -> "bt:$enabled:$deviceName"
    is IslandContent.Hotspot -> "hotspot:$enabled:$clientCount"
    is IslandContent.Wifi -> "wifi:$enabled:$ssid"
    is IslandContent.CellularData -> "cellular:$enabled:$networkType"
    is IslandContent.Location -> "location:$enabled"
    is IslandContent.Custom -> "custom:$title:$subtitle"
}

/** Scale factor for Expanded card content based on available height. */
internal fun expandFill(availableHeight: Dp): Float {
    val minH = 72.dp.value
    val maxH = 140.dp.value
    return ((availableHeight.value - minH) / (maxH - minH)).coerceIn(0f, 1f)
}

internal fun formatMediaTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
