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
    /** Expanded notification auto-dismiss if the user takes no action. */
    private const val NOTIF_EXPANDED_MS = 4_000L

    private val _state = MutableStateFlow<IslandState>(IslandState.Minimal)
    val state: StateFlow<IslandState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var lastHideSent: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Slot = content + absolute uptime millis when it should expire (0 = never). */
    private data class Slot(val content: IslandContent, val expiresAt: Long)

    private var primary: Slot? = null
    private var secondary: Slot? = null

    private var primaryTimer: Runnable? = null
    private var secondaryTimer: Runnable? = null

    /**
     * Expanded UI is active. Slot compact timers are paused.
     * - Notifications: 4s auto-dismiss unless [replyActive].
     * - Other events (user-tapped Compact): no timer; dismiss only via swipe/action.
     */
    private var holdExpanded = false
    /** Inline reply field open — suppress the 4s notification timer entirely. */
    private var replyActive = false

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun update(newState: IslandState) {
        _state.value = newState
        notifyIconVisibility(newState)
    }

    /**
     * Publish a live event. Always opens as **Compact** (never auto-expands).
     * User expands via [expand] after tapping Compact, gated by
     * [IslandPreferences.shouldExpand].
     */
    fun show(content: IslandContent) {
        val now = SystemClock.uptimeMillis()

        // ── Media position / metadata tick ──────────────────────────────
        // Only refresh the media data in whichever slot holds it; never
        // reshuffle primary/secondary.
        if (content is IslandContent.Media) {
            val refreshed = Slot(content, mediaExpiry(content, now))
            when {
                primary?.content is IslandContent.Media -> {
                    primary = refreshed
                    // Preserve Expanded while user is viewing the media popup;
                    // position ticks must not collapse it every second.
                    val keepExpanded = _state.value is IslandState.Expanded &&
                        (_state.value as IslandState.Expanded).content is IslandContent.Media
                    publish(expand = keepExpanded)
                    if (!holdExpanded) armTimers()
                    return
                }
                secondary?.content is IslandContent.Media -> {
                    secondary = refreshed
                    val keepExpanded = _state.value is IslandState.Expanded
                    publish(expand = keepExpanded)
                    if (!holdExpanded) armTimers()
                    return
                }
                // No media slot yet → fall through and treat as a new event
            }
        }

        // ── New event arrival ───────────────────────────────────────────
        val newSlot = Slot(content, expiryFor(content, now))
        // True when this is only a data refresh of the same content type
        // (e.g. flashlight on→off, ringer mode change). Must NOT collapse
        // an Expanded view the user is currently interacting with.
        val isSameKindRefresh = primary != null && sameKind(primary!!.content, content)

        when {
            // Empty island → just primary
            primary == null -> {
                primary = newSlot
                secondary = null
            }
            // Same kind of content replacing itself (e.g. ringer mode change)
            // → refresh primary in place, keep secondary
            isSameKindRefresh -> {
                primary = newSlot
            }
            // Island already has primary → demote it to secondary, drop old secondary
            else -> {
                secondary = primary
                primary = newSlot
            }
        }

        // ALL live events open as Compact. Expand only on user tap ([expand]).
        // If the user already opened Expanded and this is a same-kind data
        // refresh, keep Expanded so toggles stay usable.
        val keepExpanded = holdExpanded && isSameKindRefresh &&
            _state.value is IslandState.Expanded
        publish(expand = keepExpanded)
        if (!holdExpanded) {
            armTimers()
        }
    }

    /**
     * User tapped the Compact pill. Expand only if the content type's toggle is ON.
     * - Notification: 4s idle timer (cleared when reply opens).
     * - Other live events: no timer; stays until swipe or explicit action.
     */
    fun expand() {
        val p = primary ?: return
        if (!IslandPreferences.shouldExpand(p.content)) return
        update(IslandState.Expanded(p.content, secondary?.content))
        holdExpanded = true
        replyActive = false
        cancelTimers()
        if (p.content is IslandContent.Notification) {
            armExpandedNotificationTimer()
        }
        // Non-notification Expanded: no auto-dismiss timer.
    }

    /** Start / restart the 4s idle timer for an expanded notification. */
    private fun armExpandedNotificationTimer() {
        holdExpanded = true
        cancelTimers()
        if (replyActive) return // reply field open → no timer
        val r = Runnable {
            holdExpanded = false
            replyActive = false
            collapseToMinimal()
        }
        primaryTimer = r
        mainHandler.postDelayed(r, NOTIF_EXPANDED_MS)
    }

    /**
     * Called when the user opens the inline reply field.
     * Cancels the 4s timer; the notification stays until Send or swipe dismiss.
     */
    fun holdForReply() {
        if (_state.value !is IslandState.Expanded) return
        replyActive = true
        holdExpanded = true
        cancelTimers()
    }

    fun collapseToMinimal() {
        holdExpanded = false
        replyActive = false
        cancelTimers()
        primary = null
        secondary = null
        update(IslandState.Minimal)
    }

    fun collapseToCompact() {
        holdExpanded = false
        replyActive = false
        cancelTimers()
        val p = primary ?: return
        // Re-arm normal short timers for Compact lifetime.
        val now = SystemClock.uptimeMillis()
        primary = Slot(p.content, expiryFor(p.content, now))
        secondary = secondary?.let { Slot(it.content, expiryFor(it.content, now)) }
        update(IslandState.Compact(p.content, secondary?.content))
        armTimers()
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
        if (holdExpanded) return
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
        if (holdExpanded) return
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
        if (holdExpanded) return
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

    /**
     * Re-compute and broadcast the correct status-icon visibility for the current
     * island state. Called on every state change and whenever the Minimal width /
     * offset is adjusted so selective collision hide stays accurate.
     */
    fun refreshIconVisibility() {
        notifyIconVisibility(_state.value, force = true)
    }

    private fun notifyIconVisibility(state: IslandState, force: Boolean = false) {
        val ctx = appContext ?: return

        if (state !is IslandState.Minimal) {
            // Compact / Expanded: hide every status icon except clock & battery.
            val key = "hide_all"
            if (!force && lastHideSent == key) return
            lastHideSent = key
            StatusBarIconBridge.sendHideAll(ctx)
            return
        }

        // Minimal: only hide icons that horizontally collide with the pill.
        val cfg = IslandPreferences.config.value
        val dm = ctx.resources.displayMetrics
        val density = dm.density
        val islandWidthPx = (cfg.minimalWidthDp * density).toInt().coerceAtLeast(1)
        // WindowManager Gravity.TOP|CENTER_HORIZONTAL: x is an offset from the
        // horizontal centre of the screen.
        val centerX = dm.widthPixels / 2 + cfg.offsetX
        val left = centerX - islandWidthPx / 2
        val right = centerX + islandWidthPx / 2

        val key = "sel:$left:$right"
        if (!force && lastHideSent == key) return
        lastHideSent = key
        StatusBarIconBridge.sendSelective(ctx, left, right)
    }
}
