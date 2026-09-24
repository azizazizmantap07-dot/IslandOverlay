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

    /**
     * Optimistic MultiExpanded holds. After the user taps a toggle we pin the
     * card to the requested value for a short window so a lagging system
     * callback or early [updateMultiExpanded] snapshot cannot flash the old
     * state (on→off→on flicker).
     */
    private data class OptimisticHold(
        val content: IslandContent,
        val untilUptimeMs: Long
    )
    private val optimisticHolds = mutableListOf<OptimisticHold>()
    private const val OPTIMISTIC_HOLD_MS = 2_000L

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
        val current = _state.value

        // ── Media position / metadata tick ──────────────────────────────
        // Only refresh the media data in whichever slot holds it; never
        // reshuffle primary/secondary.
        if (content is IslandContent.Media) {
            val refreshed = Slot(content, mediaExpiry(content, now))
            // MultiExpanded: keep dual-slot bookkeeping AND patch the media
            // card in the hub so play/pause, lyrics, progress, and rings stay live.
            // Never call publish() here — that would collapse the hub to Compact.
            if (current is IslandState.MultiExpanded) {
                when {
                    primary?.content is IslandContent.Media -> primary = refreshed
                    secondary?.content is IslandContent.Media -> secondary = refreshed
                    else -> primary = refreshed
                }
                patchMultiContent(content)
                return
            }
            when {
                primary?.content is IslandContent.Media -> {
                    primary = refreshed
                    // Preserve Expanded while user is viewing the media popup;
                    // position ticks must not collapse it every second.
                    val keepExpanded = current is IslandState.Expanded &&
                        current.content is IslandContent.Media
                    publish(expand = keepExpanded)
                    if (!holdExpanded) armTimers()
                    return
                }
                secondary?.content is IslandContent.Media -> {
                    secondary = refreshed
                    val keepExpanded = current is IslandState.Expanded
                    publish(expand = keepExpanded)
                    if (!holdExpanded) armTimers()
                    return
                }
                // No media slot yet → fall through and treat as a new event
            }
        }

        // ── New event arrival ───────────────────────────────────────────
        // While MultiExpanded is open, patch the matching card in-place so
        // toggles (wifi/torch/…) update the UI without collapsing the hub or
        // reshuffling cards out of the visible stack.
        // Notification / Charging are not part of the hub — leave dual-slot
        // path for those if we ever want them to interrupt.
        if (current is IslandState.MultiExpanded &&
            content !is IslandContent.Notification &&
            content !is IslandContent.Charging
        ) {
            patchMultiContent(content)
            // Keep dual slots in sync for when the user collapses back.
            val newSlot = Slot(content, expiryFor(content, now))
            val isSameKindRefresh = primary != null && sameKind(primary!!.content, content)
            when {
                primary == null -> { primary = newSlot; secondary = null }
                isSameKindRefresh -> primary = newSlot
                else -> { secondary = primary; primary = newSlot }
            }
            return
        }

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
        // Never demote MultiExpanded from a same-kind system refresh.
        val keepExpanded = holdExpanded && isSameKindRefresh &&
            current is IslandState.Expanded
        if (current is IslandState.MultiExpanded) {
            // Dual-slot bookkeeping only; hub UI stays until user collapses.
            return
        }
        publish(expand = keepExpanded)
        if (!holdExpanded) {
            armTimers()
        }
    }

    /**
     * Remove any Media slot (primary or secondary) when the active session ends.
     * Playing media has expiresAt=0 (never), so without an explicit clear the
     * island would stay stuck on the last track forever.
     */
    fun clearMedia() {
        val hadPrimary = primary?.content is IslandContent.Media
        val hadSecondary = secondary?.content is IslandContent.Media
        if (!hadPrimary && !hadSecondary) return

        if (hadPrimary) {
            primary = secondary
            secondary = null
        } else {
            secondary = null
        }

        // MultiExpanded: remove media card from the hub as well.
        val multi = _state.value
        if (multi is IslandState.MultiExpanded) {
            val remaining = multi.items.filter { it !is IslandContent.Media }
            update(IslandState.MultiExpanded(remaining))
            if (!holdExpanded) armTimers()
            return
        }

        if (primary == null) {
            holdExpanded = false
            replyActive = false
            cancelTimers()
            update(IslandState.Minimal)
        } else {
            // If we were Expanded on media, collapse to Compact on the remaining slot.
            val wasMediaExpanded = _state.value is IslandState.Expanded &&
                (_state.value as IslandState.Expanded).content is IslandContent.Media
            if (wasMediaExpanded) {
                holdExpanded = false
                replyActive = false
            }
            publish(expand = holdExpanded && !wasMediaExpanded)
            if (!holdExpanded) armTimers()
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

    /**
     * User tapped the Minimal pill at any time. Opens a MultiExpanded hub that
     * mirrors every dynamic live-event expand (excluding Notification & Charging)
     * as a vertically scrollable stack of up to 3 visible cards.
     *
     * [snapshot] is the ordered list of current system-control cards supplied by
     * the overlay / SystemEventMonitor. Active (enabled) items are sorted to the
     * front; remaining follow the fixed order:
     * music → wifi → cellular → flashlight → ringer → bluetooth → hotspot → location → airplane.
     */
    fun expandFromMinimal(snapshot: List<IslandContent>) {
        if (snapshot.isEmpty()) return
        // Active-first ONLY on open. After that, [updateMultiExpanded] / [patchMultiContent]
        // preserve this order so toggled-off cards do not jump out of the visible stack.
        val ordered = orderMultiItems(snapshot, activeFirst = true)
        update(IslandState.MultiExpanded(ordered))
        holdExpanded = true
        replyActive = false
        cancelTimers()
    }

    /**
     * @param activeFirst when true (open only), enabled/playing cards sort to the front.
     *        When false, only the fixed type order is applied (used as a fallback for
     *        brand-new kinds inserted after open).
     */
    private fun orderMultiItems(
        items: List<IslandContent>,
        activeFirst: Boolean = false
    ): List<IslandContent> {
        val baseOrder = listOf(
            IslandContent.Media::class,
            IslandContent.Wifi::class,
            IslandContent.CellularData::class,
            IslandContent.Flashlight::class,
            IslandContent.Ringer::class,
            IslandContent.Bluetooth::class,
            IslandContent.Hotspot::class,
            IslandContent.Location::class,
            IslandContent.Custom::class // airplane uses Custom
        )
        fun baseIndex(c: IslandContent): Int =
            baseOrder.indexOfFirst { it == c::class }.takeIf { it >= 0 } ?: 99
        fun isActive(c: IslandContent): Boolean = when (c) {
            is IslandContent.Media -> c.isPlaying
            is IslandContent.Wifi -> c.enabled
            is IslandContent.CellularData -> c.enabled
            is IslandContent.Flashlight -> c.enabled
            is IslandContent.Ringer -> c.mode != com.hyperisland.root.system.RingerMode.NORMAL
            is IslandContent.Bluetooth -> c.enabled
            is IslandContent.Hotspot -> c.enabled
            is IslandContent.Location -> c.enabled
            is IslandContent.Custom -> c.title.contains("Airplane", ignoreCase = true) &&
                c.title.contains("On", ignoreCase = true)
            else -> false
        }
        return if (activeFirst) {
            val active = items.filter { isActive(it) }.sortedBy { baseIndex(it) }
            val inactive = items.filter { !isActive(it) }.sortedBy { baseIndex(it) }
            active + inactive
        } else {
            items.sortedBy { baseIndex(it) }
        }
    }

    /**
     * Replace (or insert) one card inside the open MultiExpanded hub by kind,
     * preserving the rest of the list order. Used by media ticks and live
     * system toggles so the hub stays open and the visible stack does not jump.
     *
     * Stale system updates that contradict an active [holdMultiOptimistic] are
     * ignored so the UI does not flicker off→on after a successful toggle.
     */
    fun patchMultiContent(content: IslandContent, force: Boolean = false) {
        val current = _state.value
        if (current !is IslandState.MultiExpanded) return
        pruneOptimisticHolds()
        if (!force) {
            val hold = optimisticHolds.firstOrNull { sameKind(it.content, content) }
            if (hold != null) {
                // Keep the hold for the full window. Clearing it early on the
                // first matching system event allowed a later contradictory
                // callback (common with multi-camera torch) to flip the UI.
                if (!optimisticMatches(hold.content, content)) return
                // Matching update during the hold: keep showing held value,
                // do not clear the hold yet (expires by time only).
                return
            }
        }
        val items = current.items.toMutableList()
        val idx = items.indexOfFirst { sameKind(it, content) }
        if (idx >= 0) {
            items[idx] = content
        } else {
            items.add(content)
        }
        // Preserve stack order after open — never re-sort here.
        update(IslandState.MultiExpanded(items))
    }

    /**
     * User-driven toggle from MultiExpanded: apply [content] immediately and
     * hold it against conflicting system/snapshot updates for a short window.
     */
    fun holdMultiOptimistic(content: IslandContent, holdMs: Long = OPTIMISTIC_HOLD_MS) {
        val until = SystemClock.uptimeMillis() + holdMs
        optimisticHolds.removeAll { sameKind(it.content, content) }
        optimisticHolds.add(OptimisticHold(content, until))
        patchMultiContent(content, force = true)
    }

    private fun pruneOptimisticHolds() {
        val now = SystemClock.uptimeMillis()
        optimisticHolds.removeAll { it.untilUptimeMs <= now }
    }

    /** True when both contents represent the same toggle direction / mode. */
    private fun optimisticMatches(held: IslandContent, incoming: IslandContent): Boolean {
        if (!sameKind(held, incoming)) return false
        return when {
            held is IslandContent.Flashlight && incoming is IslandContent.Flashlight ->
                held.enabled == incoming.enabled
            held is IslandContent.Wifi && incoming is IslandContent.Wifi ->
                held.enabled == incoming.enabled
            held is IslandContent.Bluetooth && incoming is IslandContent.Bluetooth ->
                held.enabled == incoming.enabled
            held is IslandContent.Hotspot && incoming is IslandContent.Hotspot ->
                held.enabled == incoming.enabled
            held is IslandContent.Location && incoming is IslandContent.Location ->
                held.enabled == incoming.enabled
            held is IslandContent.CellularData && incoming is IslandContent.CellularData ->
                held.enabled == incoming.enabled
            held is IslandContent.Ringer && incoming is IslandContent.Ringer ->
                held.mode == incoming.mode
            held is IslandContent.Custom && incoming is IslandContent.Custom ->
                held.title.equals(incoming.title, ignoreCase = true)
            held is IslandContent.Media && incoming is IslandContent.Media ->
                held.isPlaying == incoming.isPlaying
            else -> true
        }
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

    /** Refresh MultiExpanded contents after a toggle without collapsing. */
    fun updateMultiExpanded(snapshot: List<IslandContent>) {
        val current = _state.value
        if (current !is IslandState.MultiExpanded) return
        pruneOptimisticHolds()
        // Preserve the order established at open (active-first once). Only replace
        // content by kind; append any brand-new kinds at the end.
        val result = mutableListOf<IslandContent>()
        val consumed = BooleanArray(snapshot.size)
        for (old in current.items) {
            val idx = snapshot.indexOfFirst { sameKind(it, old) }
            if (idx >= 0) {
                consumed[idx] = true
                val item = snapshot[idx]
                val hold = optimisticHolds.firstOrNull { sameKind(it.content, item) }
                result.add(hold?.content ?: item)
            }
        }
        snapshot.forEachIndexed { i, item ->
            if (!consumed[i]) {
                val hold = optimisticHolds.firstOrNull { sameKind(it.content, item) }
                result.add(hold?.content ?: item)
            }
        }
        update(IslandState.MultiExpanded(result))
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
            // Compact / Expanded / MultiExpanded: hide every status icon except clock & battery.
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
