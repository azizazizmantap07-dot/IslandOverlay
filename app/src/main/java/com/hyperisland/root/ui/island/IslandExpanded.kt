package com.hyperisland.root.ui.island

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperisland.root.audio.AudioPulseEngine
import com.hyperisland.root.system.RingerMode
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Expanded popup shell: card chrome + body dispatcher.
 * Per-content UI lives in IslandExpandedControls / IslandExpandedContent.
 */
/**
 * Full-width card that sits just under the status bar (like a heads-up
 * notification). Used only while [IslandState.Expanded] is active. The
 * status-bar pill is hidden for the same duration so the eye moves from
 * the cutout area down to this card.
 */
@Composable
internal fun ExpandedPopupCard(
    content: IslandContent,
    secondary: IslandContent?,
    onCollapse: () -> Unit,
    onAction: (String) -> Unit
) {
    val layout by IslandPreferences.config.collectAsState()
    val topPad = 28.dp
    val corner = 22.dp
    // Exact size from sliders so content fills the card — no empty gaps.
    val cardWidth = layout.expandedWidthDp.dp.coerceIn(160.dp, 480.dp)
    val cardHeight = layout.expandedHeightDp.dp.coerceIn(56.dp, 220.dp)

    // Swipe-to-dismiss: drag offset + fade, then collapse in the swipe direction.
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 72.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPad)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .width(cardWidth)
                .height(cardHeight)
                .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
                .graphicsLayer {
                    shadowElevation = 12f
                    shape = RoundedCornerShape(corner)
                    clip = false
                    val dist = maxOf(abs(offsetX.value), abs(offsetY.value))
                    alpha = (1f - (dist / (dismissThresholdPx * 2.5f))).coerceIn(0.25f, 1f)
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            val dx = offsetX.value
                            val dy = offsetY.value
                            val absX = abs(dx)
                            val absY = abs(dy)
                            val shouldDismiss =
                                (absY > dismissThresholdPx && dy < 0f) || // swipe up
                                (absX > dismissThresholdPx)               // left or right
                            if (shouldDismiss) {
                                scope.launch {
                                    // Animate off-screen in the swipe direction.
                                    val targetX = when {
                                        absX >= absY && dx > 0 -> size.width * 1.4f
                                        absX >= absY && dx < 0 -> -size.width * 1.4f
                                        else -> dx
                                    }
                                    val targetY = when {
                                        absY > absX && dy < 0 -> -size.height * 1.6f
                                        else -> dy
                                    }
                                    launch { offsetX.animateTo(targetX, tween(180, easing = FastOutSlowInEasing)) }
                                    launch { offsetY.animateTo(targetY, tween(180, easing = FastOutSlowInEasing)) }
                                    // Brief wait for the exit motion, then collapse state.
                                    kotlinx.coroutines.delay(160)
                                    onCollapse()
                                }
                            } else {
                                scope.launch {
                                    launch { offsetX.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 600f)) }
                                    launch { offsetY.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 600f)) }
                                }
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                offsetX.snapTo(offsetX.value + dragAmount.x)
                                // Prefer upward dismiss; allow mild downward resistance.
                                val nextY = offsetY.value + dragAmount.y
                                offsetY.snapTo(if (nextY < 0f) nextY else nextY * 0.25f)
                            }
                        }
                    )
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(corner))
            ) {
                if (content is IslandContent.Media) {
                    AlbumArtBackdrop(
                        albumArtUri = content.albumArtUri,
                        title = content.title,
                        artist = content.artist,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0xF2000000))
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    ExpandedPopupBody(
                        content = content,
                        availableWidth = cardWidth - 24.dp,
                        availableHeight = cardHeight - 20.dp,
                        onCollapse = onCollapse,
                        onAction = onAction
                    )
                }
            }
            // Progress ring for media; full RGB for everything else.
            // Gated by animRingExpanded (and animRingMusicProgress for media).
            if (layout.animRingExpanded) {
                if (content is IslandContent.Media && content.durationMs > 0 &&
                    layout.animRingMusicProgress
                ) {
                    val progress = (content.positionMs.toFloat() / content.durationMs.toFloat())
                        .coerceIn(0f, 1f)
                    val animatedProgress by animateFloatAsState(
                        targetValue = progress,
                        animationSpec = tween(280, easing = LinearEasing),
                        label = "expandMediaRing"
                    )
                    MusicProgressRing(
                        progress = animatedProgress,
                        cornerRadius = corner,
                        isPlaying = content.isPlaying,
                        strokeWidth = 1.8.dp,
                        modifier = Modifier.matchParentSize()
                    )
                } else if (content !is IslandContent.Media || content.durationMs <= 0) {
                    FullRgbRing(
                        cornerRadius = corner,
                        strokeWidth = 1.8.dp,
                        modifier = Modifier.matchParentSize()
                    )
                }
            }
        }
    }
}

/** Round icon badge with a soft tinted background — fills empty card space
 *  with a real focal point instead of leaving a bare small glyph. */
@Composable
internal fun ExpandedIconBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    fill: Float,
    modifier: Modifier = Modifier
) {
    val badgeSize = (30.dp + (18.dp * fill))
    val iconSize = (16.dp + (10.dp * fill))
    Box(
        modifier = modifier
            .size(badgeSize)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
internal fun ExpandedPopupBody(
    content: IslandContent,
    availableWidth: Dp,
    availableHeight: Dp,
    onCollapse: () -> Unit,
    onAction: (String) -> Unit
) {
    val isCramped = availableHeight < 72.dp || availableWidth < 200.dp
    when (content) {
        is IslandContent.Notification -> ExpandedNotification(
            content = content,
            isCramped = isCramped,
            availableHeight = availableHeight,
            onCollapse = onCollapse,
            onAction = onAction
        )
        is IslandContent.Charging -> ExpandedCharging(
            content = content,
            isCramped = isCramped,
            availableHeight = availableHeight,
            onCollapse = onCollapse
        )
        is IslandContent.Media -> ExpandedMediaPlayer(
            content = content,
            availableWidth = availableWidth,
            availableHeight = availableHeight,
            onAction = onAction
        )
        is IslandContent.Ringer -> ExpandedRingerControls(
            content = content,
            isCramped = isCramped,
            availableHeight = availableHeight,
            onAction = onAction
        )
        is IslandContent.Flashlight -> ExpandedToggleControls(
            title = if (content.enabled) "Flashlight On" else "Flashlight Off",
            enabled = content.enabled,
            onActionLabel = "flashlight",
            icon = Icons.Rounded.FlashlightOn,
            isCramped = isCramped,
            availableHeight = availableHeight,
            onAction = onAction
        )
        is IslandContent.Bluetooth -> ExpandedToggleControls(
            title = if (content.enabled) {
                content.deviceName?.takeIf { it.isNotBlank() } ?: "Bluetooth On"
            } else "Bluetooth Off",
            enabled = content.enabled,
            onActionLabel = "bluetooth",
            icon = Icons.Rounded.Bluetooth,
            isCramped = isCramped,
            availableHeight = availableHeight,
            onAction = onAction
        )
        is IslandContent.Hotspot -> ExpandedToggleControls(
            title = if (content.enabled) "Hotspot On" else "Hotspot Off",
            enabled = content.enabled,
            onActionLabel = "hotspot",
            icon = Icons.Rounded.WifiTethering,
            isCramped = isCramped,
            availableHeight = availableHeight,
            onAction = onAction
        )
        is IslandContent.Wifi -> ExpandedWifiControls(
            content = content,
            isCramped = isCramped,
            availableHeight = availableHeight,
            onAction = onAction
        )
        is IslandContent.CellularData -> ExpandedCellularControls(
            content = content,
            isCramped = isCramped,
            availableHeight = availableHeight,
            onAction = onAction
        )
        is IslandContent.Location -> ExpandedToggleControls(
            title = if (content.enabled) "Lokasi On" else "Lokasi Off",
            enabled = content.enabled,
            onActionLabel = "location",
            icon = Icons.Rounded.LocationOn,
            isCramped = isCramped,
            availableHeight = availableHeight,
            onAction = onAction
        )
        is IslandContent.Custom -> {
            val isAirplane = content.title.contains("Airplane", ignoreCase = true)
            if (isAirplane) {
                val on = content.title.contains("On", ignoreCase = true)
                ExpandedToggleControls(
                    title = content.title,
                    enabled = on,
                    onActionLabel = "airplane",
                    icon = Icons.Rounded.AirplanemodeActive,
                    isCramped = isCramped,
                    availableHeight = availableHeight,
                    onAction = onAction
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    NonMediaCompact(
                        content = content,
                        iconSize = minOf(28.dp, availableHeight * 0.45f),
                        isTight = isCramped,
                        isTiny = availableWidth < 120.dp
                    )
                }
            }
        }
    }
}


/**
 * Multi-card expand hub opened from Minimal.
 *
 * Visual: staircase stack of up to 3 cards, matching the stepped cascade
 * reference. Depth comes from four cues that work together:
 *  1. each back card is clearly NARROWER than the one in front of it,
 *  2. each back card is SHORTER, so only a strip of it peeks above the front,
 *  3. each back card is DIMMER (lifted grey-black → darker with depth) with a
 *     soft vertical gradient, and
 *  4. each card casts a real drop shadow (shadowElevation) onto the card behind it.
 * Only the front card renders its real content; back cards are drawn as
 * clean "sheets" so the stack silhouette stays readable.
 *
 * Vertical drag on the front card cycles it to the back with a smooth spring;
 * infinite scroll keeps exactly 3 layers visible.
 */
@Composable
internal fun MultiExpandedStack(
    items: List<IslandContent>,
    onCollapse: () -> Unit,
    onAction: (String) -> Unit
) {
    if (items.isEmpty()) return
    val layout by IslandPreferences.config.collectAsState()
    val topPad = 28.dp
    val corner = 26.dp
    val cardWidth = layout.expandedWidthDp.dp.coerceIn(160.dp, 480.dp)
    val cardHeight = layout.expandedHeightDp.dp.coerceIn(56.dp, 220.dp)
    val densityFactor = layout.multiStackDensity.coerceIn(0.6f, 1.5f)

    // ── Staircase geometry ────────────────────────────────────────────
    // stepPeek   = how many dp of a back card stays visible above the card in front.
    // widthShrinkPerLayer: front = 100%, 2nd = 75%, 3rd = 50% — an even
    //   25%-per-layer step so the narrowing reads instantly, matching the
    //   reference silhouette.
    // backHeightFactor: back cards are only as tall as needed to show the
    //   peek strip plus enough body to hide behind the next card.
    val stepPeek = (26.dp * densityFactor).coerceIn(14.dp, 42.dp)
    val widthShrinkPerLayer = 0.25f
    val backHeightFactor = 0.66f

    var frontIndex by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }
    val density = LocalDensity.current
    val cycleThresholdPx = with(density) { 40.dp.toPx() }

    val n = items.size
    val visible = minOf(3, n)
    fun itemAt(offset: Int): IslandContent = items[((frontIndex + offset) % n + n) % n]

    // ── Cycle transition driver ───────────────────────────────────────
    // A single 0f→1f progress value drives every layer at once, so the
    // front card's exit and every back card's advance stay perfectly in
    // sync — this is what sells the "rotating stack" illusion instead of
    // each layer settling independently (which read as a snap/flicker).
    // 0f = resting (front card in front, back cards in their slots).
    // 1f = the front card has fully cycled to the back and every other
    //      layer has advanced one step forward.
    val cycleProgress = remember { Animatable(0f) }
    var isCycling by remember { mutableStateOf(false) }
    val cycleSpring = spring<Float>(dampingRatio = 0.88f, stiffness = 420f)

    // Total height of the staircase so the box reserves space for all peeks.
    val stackTotalHeight = cardHeight + stepPeek * (visible - 1)

    // ── Per-layer geometry, parameterized by a continuous depth value ──
    // depth 0f = front slot, depth (visible-1) = furthest back slot.
    // Fractional depth (e.g. 0.35f) is what lets a card be *between* two
    // slots while the cycle animation is running.
    fun scaleXAt(depth: Float) = 1f - depth * widthShrinkPerLayer
    fun scaleYAt(depth: Float) = androidx.compose.ui.util.lerp(1f, backHeightFactor, depth.coerceIn(0f, 1f))
    fun yAt(depth: Float) = stepPeek * ((visible - 1).toFloat() - depth)
    fun dimAt(depth: Float): Float {
        // Same resting dim values as before but with a stronger back-layer
        // dim so depth reads clearly even before the width/height taper
        // is noticed — darker the further back, matching the reference.
        val stops = floatArrayOf(0f, 0.5f, 0.78f)
        val d = depth.coerceIn(0f, (stops.size - 1).toFloat())
        val lo = d.toInt().coerceIn(0, stops.size - 2)
        val frac = d - lo
        return androidx.compose.ui.util.lerp(stops[lo], stops[lo + 1], frac)
    }
    fun shadowAt(depth: Float) = androidx.compose.ui.util.lerp(22f, 6f, (depth / 2f).coerceIn(0f, 1f))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPad)
            .padding(horizontal = 12.dp)
            .height(stackTotalHeight + 8.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        val p = cycleProgress.value

        // While cycling, an extra card is fading in at the very back slot —
        // it's the item that will occupy layer (visible-1) once the cycle
        // commits (i.e. itemAt(visible), using the *current* frontIndex).
        // Without this, the back slot would sit empty until the swap and
        // the stack would visibly "lose a card" mid-gesture.
        val incomingLayer = visible
        val hasIncoming = p > 0f && n > visible

        // Draw back → front so the front card paints last (on top).
        for (layer in incomingLayer downTo 0) {
            if (layer == incomingLayer && !hasIncoming) continue
            val content = itemAt(layer)
            val isFront = layer == 0
            val isIncoming = layer == incomingLayer

            // While cycling (p: 0→1), every card advances one slot toward the
            // front — layer L's resting depth is L, and it animates to (L-1).
            // The front card (layer 0) has nowhere to advance "to" on the
            // staircase, so it gets a dedicated exit curve below instead.
            // The incoming card starts one slot further back than the
            // deepest visible slot and animates in to fill (visible-1).
            val restDepth = layer.toFloat()
            val depth = if (isFront) restDepth else androidx.compose.ui.util.lerp(restDepth, restDepth - 1f, p)

            val animScaleX = scaleXAt(depth)
            val animScaleY = scaleYAt(depth)
            val animY = yAt(depth)
            val animDim = dimAt(depth)
            val animShadow = shadowAt(depth)
            val incomingAlpha = if (isIncoming) p else 1f

            // ── Front-card exit curve ──────────────────────────────────
            // As p goes 0→1 the front card: drops further down (past the
            // bottom edge of the staircase, continuing from its resting
            // "front" Y which is now the bottom-most slot), shrinks to the
            // size the new back slot will have, fades out, and tips
            // backward slightly (rotationX) — reading as "tucking behind"
            // the stack rather than just sliding away. It also keeps
            // tracking the live finger drag (dragY) while p is being
            // driven by that drag.
            val exitScaleX = if (isFront) androidx.compose.ui.util.lerp(1f, scaleXAt((visible - 1).toFloat()), p) else animScaleX
            val exitScaleY = if (isFront) androidx.compose.ui.util.lerp(1f, scaleYAt((visible - 1).toFloat()), p) else animScaleY
            val exitAlpha = if (isFront) androidx.compose.ui.util.lerp(1f, 0f, p) else 1f
            val exitRotationX = if (isFront) androidx.compose.ui.util.lerp(0f, -34f, p) else 0f
            // Front card's resting Y (bottom-most slot) plus extra travel
            // so it visibly continues past the bottom edge as it exits.
            val frontRestYPx = with(density) { yAt(0f).toPx() }
            val exitTravelPx = with(density) { (cardHeight * 0.85f).toPx() }

            val w = cardWidth * exitScaleX
            val h = cardHeight * exitScaleY

            Box(
                modifier = Modifier
                    .width(w)
                    .height(h)
                    .offset {
                        val baseY = if (isFront) {
                            frontRestYPx + androidx.compose.ui.util.lerp(0f, exitTravelPx, p)
                        } else {
                            with(density) { animY.toPx() }
                        }
                        val liveDrag = if (isFront) dragY.value * (1f - p) else 0f
                        IntOffset(0, (baseY + liveDrag).roundToInt())
                    }
                    .graphicsLayer {
                        alpha = exitAlpha * incomingAlpha
                        rotationX = exitRotationX
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        // Stronger shadow on the front card so it visibly floats above
                        // the ones behind; back cards keep a smaller shadow. The
                        // front card's shadow also eases down as it recedes.
                        shadowElevation = if (isFront) {
                            androidx.compose.ui.util.lerp(22f, 6f, p)
                        } else {
                            animShadow
                        }
                        shape = RoundedCornerShape(corner)
                        clip = false
                        cameraDistance = 12f
                    }
                    .then(
                        if (isFront && !isCycling) {
                            Modifier.pointerInput(frontIndex, n) {
                                val dragTravelPx = with(density) { cardHeight.toPx() * 0.85f }
                                detectDragGestures(
                                    onDragEnd = {
                                        val dy = dragY.value
                                        when {
                                            // Drag down past threshold → commit the cycle:
                                            // animate cycleProgress the rest of the way to 1
                                            // (front card finishes tucking behind, every back
                                            // card finishes advancing one slot), all in one
                                            // continuous spring, then swap the data index and
                                            // reset — the reset is invisible because depth 0
                                            // at p=0 looks identical to depth -1→0 at p=1.
                                            dy > cycleThresholdPx -> {
                                                isCycling = true
                                                scope.launch {
                                                    dragY.snapTo(0f)
                                                    cycleProgress.animateTo(1f, cycleSpring)
                                                    frontIndex = (frontIndex + 1) % n
                                                    cycleProgress.snapTo(0f)
                                                    isCycling = false
                                                }
                                            }
                                            // Drag up → collapse hub
                                            dy < -cycleThresholdPx * 1.15f -> {
                                                scope.launch {
                                                    dragY.animateTo(
                                                        -with(density) { cardHeight.toPx() * 1.15f },
                                                        tween(200, easing = FastOutSlowInEasing)
                                                    )
                                                    onCollapse()
                                                }
                                            }
                                            // Released early / not far enough → spring both
                                            // the drag offset and the cycle progress back to
                                            // rest together, so an aborted swipe visibly
                                            // "bounces" the card back into place.
                                            else -> {
                                                scope.launch {
                                                    dragY.animateTo(
                                                        0f,
                                                        spring(dampingRatio = 0.78f, stiffness = 520f)
                                                    )
                                                }
                                                scope.launch {
                                                    cycleProgress.animateTo(
                                                        0f,
                                                        spring(dampingRatio = 0.78f, stiffness = 520f)
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        scope.launch {
                                            val next = (dragY.value + amount.y).coerceIn(
                                                -with(density) { cardHeight.toPx() * 0.95f },
                                                with(density) { cardHeight.toPx() * 0.65f }
                                            )
                                            dragY.snapTo(next)
                                            // Drive the whole stack's cycle progress live from
                                            // the downward drag distance, so the back cards
                                            // visibly advance in lockstep with the finger
                                            // instead of only reacting after release. Upward
                                            // drag (collapse gesture) doesn't touch the stack.
                                            if (next > 0f) {
                                                val liveP = (next / dragTravelPx).coerceIn(0f, 1f)
                                                cycleProgress.snapTo(liveP)
                                            } else if (cycleProgress.value != 0f) {
                                                cycleProgress.snapTo(0f)
                                            }
                                        }
                                    }
                                )
                            }
                        } else Modifier
                    ),
                contentAlignment = Alignment.TopCenter
            ) {
                // Card body. Front = solid black (same as ExpandedPopupCard). Back cards
                // get a lifted, dimmed fill with a soft top→bottom gradient so they read
                // as sheets receding into the distance instead of near-identical clones.
                val isMediaFront = isFront && content is IslandContent.Media
                val bodyFill = if (isFront && !isMediaFront) {
                    Modifier.background(Color(0xF2000000))
                } else if (!isFront) {
                    // Lighter base than the front, then blended toward black by depth.
                    val base = Color(0xFF3A3D42)
                    val top = androidx.compose.ui.graphics.lerp(base, Color.Black, animDim)
                    val bottom = androidx.compose.ui.graphics.lerp(
                        Color(0xFF23262A), Color.Black, animDim
                    )
                    Modifier.background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(top, bottom)
                        )
                    )
                } else {
                    Modifier // media front: album art drawn inside the Box
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(corner))
                        .then(bodyFill),
                    contentAlignment = Alignment.Center
                ) {
                    if (isMediaFront) {
                        AlbumArtBackdrop(
                            albumArtUri = (content as IslandContent.Media).albumArtUri,
                            title = content.title,
                            artist = content.artist,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (isFront) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            ExpandedPopupBody(
                                content = content,
                                availableWidth = w - 28.dp,
                                availableHeight = h - 20.dp,
                                onCollapse = onCollapse,
                                onAction = onAction
                            )
                        }
                    }
                    // Back cards intentionally render no content: only the peeking
                    // strip is visible anyway, and keeping it clean makes the
                    // staircase silhouette read exactly like the reference.
                }

                // RGB ring — gated by the same toggle as Expanded. Back cards get a
                // dimmer ring so they don't compete with the front card.
                if (layout.animRingExpanded) {
                    val ringModifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = 1f - animDim * 0.7f }
                    if (content is IslandContent.Media && content.durationMs > 0 &&
                        layout.animRingMusicProgress
                    ) {
                        val progress = (content.positionMs.toFloat() / content.durationMs.toFloat())
                            .coerceIn(0f, 1f)
                        val animatedProgress by animateFloatAsState(
                            targetValue = progress,
                            animationSpec = tween(280, easing = LinearEasing),
                            label = "multiMediaRing$layer"
                        )
                        MusicProgressRing(
                            progress = animatedProgress,
                            cornerRadius = corner,
                            isPlaying = content.isPlaying,
                            strokeWidth = 1.8.dp,
                            modifier = ringModifier
                        )
                    } else if (content !is IslandContent.Media || content.durationMs <= 0) {
                        FullRgbRing(
                            cornerRadius = corner,
                            strokeWidth = 1.8.dp,
                            modifier = ringModifier
                        )
                    }
                }
            }
        }
    }
}
