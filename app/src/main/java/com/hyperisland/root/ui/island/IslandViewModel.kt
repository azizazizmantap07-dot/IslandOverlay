package com.hyperisland.root.ui.island

import androidx.lifecycle.ViewModel
import com.hyperisland.root.util.CrashLogger
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin pass-through to IslandBus for MainActivity (quick tests / Settings
 * screen). All state and auto-collapse timing lives in IslandBus itself,
 * since that's what every real producer (SystemEventMonitor,
 * MediaSessionTracker, IslandNotificationListener) calls directly – see
 * IslandBus's kdoc for the exact timing rules (4s for notifications/other
 * one-shot content, media stays up while playing, 5s grace once paused).
 */
class IslandViewModel : ViewModel() {

    val state: StateFlow<IslandState> = IslandBus.state

    fun show(content: IslandContent, expand: Boolean = false) {
        IslandBus.show(content, expand)
        CrashLogger.d("Island show: ${content::class.simpleName} expand=$expand")
    }

    fun expand() = IslandBus.expand()
    fun collapseToCompact() = IslandBus.collapseToCompact()
    fun collapseToMinimal() = IslandBus.collapseToMinimal()
}
