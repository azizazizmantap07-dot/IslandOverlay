package com.hyperisland.root.ui.island

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hyperisland.root.xposed.StatusBarIconBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dual-slot live-event bus.
 *
 * At most **two** concurrent events:
 * - [primary]  → main area of the pill
 * - [secondary] → circular badge on the side (the interrupted one)
 *
 * Rules (matching the user's sketch):
 * 1. New event always becomes primary.
 * 2. Previous primary is demoted to secondary (circle).
 * 3. Previous secondary is dropped (only the 2 newest survive).
 * 4. Each non-media event has its own 4 s lifetime from the moment it arrived.
 *    Whichever expires first simply disappears; the other stays.
 * 5. Media while isPlaying=true never expires. Paused media has a 5 s grace.
 * 6. Media position ticks only refresh the media slot; they never demote /
 *    promote other events.
 */
object IslandBus {
    private const val NON_MEDIA_MS = 4000L
    private const val MEDIA_IDLE_MS = 5000L

    private val _state = MutableStateFlow<IslandState>(IslandState.Minimal)
    val state: StateFlow<IslandState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var lastHideSent: Boolean? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Slot = content + absolute uptime millis when it should expire (0 = never). */
    private data class Slot(val content: IslandContent, val expiresAt: Long)

    private var primary: Slot? = null
    private var secondary: Slot? = null

    private var primaryTimer: Runnable? = null
    private var secondaryTimer: Runnable? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun update(newState: IslandState) {
        _state.value = newState
        notifyIconVisibility(newState)
    }

    fun show(content: IslandContent, expand: Boolean = false) {
        val now = SystemClock.uptimeMillis()

        // ── Media position / metadata tick ──────────────────────────────
        // Only refresh the media data in whichever slot holds it; never
        // reshuffle primary/secondary.
        if (content is IslandContent.Media) {
            val refreshed = Slot(content, mediaExpiry(content, now))
            when {
                primary?.content is IslandContent.Media -> {
                    primary = refreshed
                    publish(expand)
                    armTimers()
                    return
                }
                secondary?.content is IslandContent.Media -> {
                    secondary = refreshed
                    publish(expand)
                    armTimers()
                    return
                }
                // No media slot yet → fall through and treat as a new event
            }
        }

        // ── New event arrival ───────────────────────────────────────────
        val newSlot = Slot(content, expiryFor(content, now))

        when {
            // Empty island → just primary
            primary == null -> {
                primary = newSlot
                secondary = null
            }
            // Same kind of content replacing itself (e.g. ringer mode change)
            // → refresh primary in place, keep secondary
            sameKind(primary!!.content, content) -> {
                primary = newSlot
            }
            // Island already has primary → demote it to secondary, drop old secondary
            else -> {
                secondary = primary
                primary = newSlot
            }
        }

        publish(expand = false) // interrupt mode always stays compact-sized
        armTimers()
    }

    fun expand() {
        val p = primary ?: return
        update(IslandState.Expanded(p.content, secondary?.content))
        // Timers keep running; no reschedule needed
    }

    fun collapseToMinimal() {
        cancelTimers()
        primary = null
        secondary = null
        update(IslandState.Minimal)
    }

    fun collapseToCompact() {
        val p = primary ?: return
        update(IslandState.Compact(p.content, secondary?.content))
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun publish(expand: Boolean) {
        val p = primary ?: run {
            update(IslandState.Minimal)
            return
        }
        val s = secondary?.content
        update(
            if (expand) IslandState.Expanded(p.content, s)
            else IslandState.Compact(p.content, s)
        )
    }

    private fun armTimers() {
        cancelTimers()
        val now = SystemClock.uptimeMillis()

        primary?.let { slot ->
            if (slot.expiresAt > 0) {
                val delay = (slot.expiresAt - now).coerceAtLeast(0L)
                val r = Runnable { onPrimaryExpired() }
                primaryTimer = r
                mainHandler.postDelayed(r, delay)
            }
        }
        secondary?.let { slot ->
            if (slot.expiresAt > 0) {
                val delay = (slot.expiresAt - now).coerceAtLeast(0L)
                val r = Runnable { onSecondaryExpired() }
                secondaryTimer = r
                mainHandler.postDelayed(r, delay)
            }
        }
    }

    private fun onPrimaryExpired() {
        primaryTimer = null
        // Primary gone → promote secondary if any
        primary = secondary
        secondary = null
        if (primary == null) {
            update(IslandState.Minimal)
            notifyIconVisibility(IslandState.Minimal)
        } else {
            publish(expand = false)
            armTimers() // re-arm for the promoted slot
        }
    }

    private fun onSecondaryExpired() {
        secondaryTimer = null
        secondary = null
        // Primary keeps running; just republish without secondary
        if (primary == null) {
            update(IslandState.Minimal)
        } else {
            publish(expand = false)
        }
    }

    private fun cancelTimers() {
        primaryTimer?.let { mainHandler.removeCallbacks(it) }
        secondaryTimer?.let { mainHandler.removeCallbacks(it) }
        primaryTimer = null
        secondaryTimer = null
    }

    private fun expiryFor(content: IslandContent, now: Long): Long {
        return when {
            content is IslandContent.Media && content.isPlaying -> 0L // never
            content is IslandContent.Media -> now + MEDIA_IDLE_MS
            else -> now + NON_MEDIA_MS
        }
    }

    private fun mediaExpiry(content: IslandContent.Media, now: Long): Long {
        return if (content.isPlaying) 0L else now + MEDIA_IDLE_MS
    }

    private fun sameKind(a: IslandContent, b: IslandContent): Boolean {
        return a::class == b::class
    }

    private fun notifyIconVisibility(state: IslandState) {
        val ctx = appContext ?: return
        val shouldHide = state !is IslandState.Minimal
        if (shouldHide == lastHideSent) return
        lastHideSent = shouldHide
        StatusBarIconBridge.sendVisibility(ctx, shouldHide)
    }
}
