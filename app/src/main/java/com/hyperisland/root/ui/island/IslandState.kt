package com.hyperisland.root.ui.island

/**
 * Three visual states of the Island, matching Apple / HyperOS behavior.
 *
 * [secondary] holds a concurrent live event that was interrupted by [content].
 * The primary occupies the main area of the pill; the secondary shrinks into a
 * circular badge on the side — still one continuous Dynamic Island of normal
 * compact size. At most two events coexist; a third arrival drops the oldest.
 */
sealed class IslandState {
    object Minimal : IslandState()
    data class Compact(
        val content: IslandContent,
        val secondary: IslandContent? = null
    ) : IslandState()
    data class Expanded(
        val content: IslandContent,
        val secondary: IslandContent? = null
    ) : IslandState()
}

sealed class IslandContent {
    /**
     * One notification action button shown in the Expanded popup.
     * [index] matches the system [android.app.Notification.Action] array index
     * so [com.hyperisland.root.notification.NotificationActionStore] can fire it.
     */
    data class NotifActionUi(
        val title: String,
        val index: Int,
        val isReply: Boolean = false
    )

    data class Notification(
        val packageName: String,
        val title: String,
        val text: String,
        val iconUri: String? = null,
        /** StatusBarNotification key — used to look up cached system actions. */
        val key: String = "",
        val actions: List<NotifActionUi> = emptyList(),
        val arrivedAtMs: Long = System.currentTimeMillis()
    ) : IslandContent()

    data class Media(
        val title: String,
        val artist: String,
        val albumArtUri: String? = null,
        val isPlaying: Boolean,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val lyricsLine: String? = null
    ) : IslandContent()

    data class Charging(
        val level: Int,
        val isCharging: Boolean,
        val currentMa: Int = 0,
        val temperatureC: Float = 0f
    ) : IslandContent()

    data class Ringer(
        val mode: com.hyperisland.root.root.RingerMode
    ) : IslandContent()

    data class Flashlight(
        val enabled: Boolean
    ) : IslandContent()

    data class Bluetooth(
        val enabled: Boolean,
        val deviceName: String? = null
    ) : IslandContent()

    data class Hotspot(
        val enabled: Boolean,
        val clientCount: Int = 0
    ) : IslandContent()

    data class Wifi(
        val enabled: Boolean,
        val ssid: String? = null,
        val signalLevel: Int = 0
    ) : IslandContent()

    data class CellularData(
        val enabled: Boolean,
        val networkType: String? = null,
        val operatorName: String? = null
    ) : IslandContent()

    data class Location(
        val enabled: Boolean
    ) : IslandContent()

    data class Custom(
        val title: String,
        val subtitle: String? = null,
        val iconRes: Int? = null
    ) : IslandContent()
}
