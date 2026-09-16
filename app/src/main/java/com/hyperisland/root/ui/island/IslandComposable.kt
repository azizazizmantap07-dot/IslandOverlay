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
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperisland.root.audio.AudioPulseEngine
import com.hyperisland.root.root.RingerMode

/**
 * Visual states mirror HyperOS Super Island / Apple Dynamic Island:
 *
 * - Minimal  (小岛 / permanent)  : thin capsule covering cutout, idle or after timeout
 * - Compact  (大岛 / summary)    : left+right content around camera, key info only
 * - Expanded (展开态 / focus)    : larger card with actions, auto-collapses ~5s
 *
 * Spring physics tuned for a bouncy but controlled morph similar to HyperOS.
 *
 * Every state below is rendered inside [BoxWithConstraints], so content always
 * measures itself against the *real* pixel footprint the user configured via
 * the layout sliders (down to 10dp). Nothing assumes the default size — icons,
 * text and motion all thin out or hide in a fixed priority order before
 * anything would clip, so a tiny custom pill degrades gracefully instead of
 * overflowing.
 */
/**
 * Top-level entry point used by [com.hyperisland.root.service.IslandOverlayService].
 * Wraps [IslandContentView] with an orientation-aware show/hide: the Island
 * morphs away (scale + fade, matching the same spring/curve language used for
 * the interruption badge pop) the moment the device rotates to landscape, and
 * morphs back in on returning to portrait — instead of just appearing/
 * disappearing abruptly.
 *
 * The overlay window itself never gets removed/re-added for this — only its
 * Compose content shrinks to nothing, which keeps the transition smooth and
 * avoids any WindowManager add/remove flicker on every rotation.
 */
@Composable
fun OrientationAwareIsland(
    state: IslandState,
    isLandscape: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onAction: (String) -> Unit = {}
) {
    AnimatedVisibility(
        visible = !isLandscape,
        enter = fadeIn(tween(220, easing = FastOutSlowInEasing)) +
            scaleIn(
                initialScale = 0.75f,
                animationSpec = spring(dampingRatio = 0.62f, stiffness = 380f)
            ),
        exit = fadeOut(tween(160, easing = FastOutSlowInEasing)) +
            scaleOut(
                targetScale = 0.75f,
                animationSpec = tween(180, easing = FastOutSlowInEasing)
            )
    ) {
        IslandContentView(
            state = state,
            onExpand = onExpand,
            onCollapse = onCollapse,
            onAction = onAction
        )
    }
}

@Composable
fun IslandContentView(
    state: IslandState,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onAction: (String) -> Unit = {}
) {
    val layout by IslandPreferences.config.collectAsState()

    // Size morph — slightly stiffer than default so the pill feels "snappy"
    val sizeSpring = spring<Dp>(
        dampingRatio = 0.72f,
        stiffness = 520f
    )

    val targetWidth by animateDpAsState(
        targetValue = when (state) {
            is IslandState.Minimal -> layout.minimalWidthDp.dp
            is IslandState.Compact -> layout.compactWidthDp.dp
            is IslandState.Expanded -> layout.expandedWidthDp.dp
        },
        animationSpec = sizeSpring,
        label = "islandWidth"
    )
    val targetHeight by animateDpAsState(
        targetValue = when (state) {
            is IslandState.Minimal -> layout.minimalHeightDp.dp
            is IslandState.Compact -> layout.compactHeightDp.dp
            is IslandState.Expanded -> layout.expandedHeightDp.dp
        },
        animationSpec = sizeSpring,
        label = "islandHeight"
    )

    // Corner radius: true capsule when compact/minimal, slightly less rounded when expanded.
    // Also softened automatically once a dimension shrinks below the radius itself, so a
    // 10dp-tall custom pill never shows a clipped/square-ish corner artifact.
    val baseCorner = when (state) {
        is IslandState.Expanded -> 28.dp
        else -> 50.dp
    }
    val safeCornerTarget = minOf(baseCorner, targetWidth / 2, targetHeight / 2)
    val cornerRadius by animateDpAsState(
        targetValue = safeCornerTarget,
        animationSpec = sizeSpring,
        label = "islandCorner"
    )

    // Elevation "breathes" with state depth, another subtle iOS/HyperOS-style cue.
    val elevationTarget = when (state) {
        is IslandState.Minimal -> 2f
        is IslandState.Compact -> 6f
        is IslandState.Expanded -> 12f
    }
    val elevation by animateFloatAsState(
        targetValue = elevationTarget,
        animationSpec = tween(220),
        label = "islandElevation"
    )
    val pillShape = RoundedCornerShape(cornerRadius)

    // Media can be either primary content or secondary (when interrupted).
    // Outer edge ring only appears when media is primary and alone.
    val secondaryContent = when (state) {
        is IslandState.Compact -> state.secondary
        is IslandState.Expanded -> state.secondary
        else -> null
    }
    val primaryMedia = when (state) {
        is IslandState.Compact -> state.content as? IslandContent.Media
        is IslandState.Expanded -> state.content as? IslandContent.Media
        else -> null
    }
    val secondaryMedia = secondaryContent as? IslandContent.Media
    val mediaForProgress = secondaryMedia ?: primaryMedia
    val mediaProgress = if (mediaForProgress != null && mediaForProgress.durationMs > 0) {
        (mediaForProgress.positionMs.toFloat() / mediaForProgress.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val animatedMediaProgress by animateFloatAsState(
        targetValue = mediaProgress,
        animationSpec = tween(durationMillis = 900, easing = LinearEasing),
        label = "mediaRingProgress"
    )
    // Outer full-perimeter ring only when music owns the whole island
    val showOuterRing = primaryMedia != null && secondaryContent == null &&
        (primaryMedia.isPlaying || animatedMediaProgress > 0.01f)

    Box(
        modifier = Modifier
            .width(targetWidth)
            .height(targetHeight)
            // Shadow must be drawn (graphicsLayer) before the clip below, otherwise
            // the clip cuts the shadow off along with everything outside the shape.
            .graphicsLayer {
                shadowElevation = elevation
                shape = pillShape
                clip = false
            },
        contentAlignment = Alignment.Center
    ) {
        // Full edge ring only in pure-media mode (not when interrupted)
        if (showOuterRing) {
            MusicProgressRing(
                progress = animatedMediaProgress,
                cornerRadius = cornerRadius,
                isPlaying = primaryMedia!!.isPlaying,
                strokeWidth = 3.0.dp,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(pillShape)
                .background(Color(0xE6000000)),
            contentAlignment = Alignment.Center
        ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val availableWidth = maxWidth
            val availableHeight = maxHeight

            // Rich, direction-aware morph between Minimal / Compact / Expanded:
            //  - Expanding (going "up" a level, e.g. Minimal→Compact→Expanded) grows
            //    in from slightly below/behind with an upward slide, like the content
            //    is surfacing into view.
            //  - Collapsing (going "down" a level) shrinks back down with a downward
            //    slide, mirroring the expand so the two feel like true inverses instead
            //    of a generic crossfade in both directions.
            //  - A brief overshoot spring on size/scale gives the pop a touch of the
            //    springy, slightly-elastic feel HyperOS/iOS use, without being bouncy
            //    enough to look unstable.
            val stateDepth = { s: IslandState ->
                when (s) {
                    is IslandState.Minimal -> 0
                    is IslandState.Compact -> 1
                    is IslandState.Expanded -> 2
                }
            }
            AnimatedContent(
                targetState = state,
                // Key the crossfade/scale transition on the state's *shape* (Minimal /
                // Compact+contentType / Expanded+contentType) instead of the whole data
                // class. IslandContent.Media is a data class, so every ~1s position/lyric
                // tick from MediaSessionTracker produces a new, unequal instance — if we
                // animate on the full `state` object, AnimatedContent treats each tick as
                // a state change and replays the fade/scale transition, which is the
                // "berkedip-kedip" flicker during playback. Content *inside* an unchanged
                // shape (e.g. the lyric line or elapsed time ticking up) just recomposes
                // normally without a transition.
                contentKey = { s ->
                    when (s) {
                        is IslandState.Minimal -> "minimal"
                        is IslandState.Compact -> {
                            val sec = s.secondary?.let { "+${it::class.simpleName}" } ?: ""
                            "compact:${s.content::class.simpleName}$sec"
                        }
                        is IslandState.Expanded -> {
                            val sec = s.secondary?.let { "+${it::class.simpleName}" } ?: ""
                            "expanded:${s.content::class.simpleName}$sec"
                        }
                    }
                },
                transitionSpec = {
                    val expanding = stateDepth(targetState) >= stateDepth(initialState)

                    val enter = fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                        scaleIn(
                            initialScale = if (expanding) 0.90f else 1.06f,
                            transformOrigin = TransformOrigin(0.5f, if (expanding) 0.65f else 0.35f),
                            animationSpec = spring(dampingRatio = 0.68f, stiffness = 380f)
                        ) +
                        slideIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) { fullSize ->
                            // Offset proportional to the incoming content's own height (not a
                            // fixed dp) so the slide reads consistently whether the pill is
                            // 20dp or 200dp tall.
                            val d = (fullSize.height * 0.18f).toInt().coerceAtLeast(1)
                            IntOffset(0, if (expanding) d else -d)
                        }
                    val exit = fadeOut(tween(150, easing = FastOutSlowInEasing)) +
                        scaleOut(
                            targetScale = if (expanding) 1.04f else 0.92f,
                            transformOrigin = TransformOrigin(0.5f, if (expanding) 0.35f else 0.65f),
                            animationSpec = tween(160)
                        ) +
                        slideOut(animationSpec = tween(150, easing = FastOutSlowInEasing)) { fullSize ->
                            val d = (fullSize.height * 0.09f).toInt().coerceAtLeast(1)
                            IntOffset(0, if (expanding) -d else d)
                        }
                    (enter togetherWith exit).using(
                        SizeTransform(clip = false) { _, _ ->
                            spring(dampingRatio = 0.72f, stiffness = 500f)
                        }
                    )
                },
                label = "islandContent"
            ) { current ->
                when (current) {
                    is IslandState.Minimal -> MinimalContent(
                        availableWidth = availableWidth,
                        availableHeight = availableHeight
                    )
                    is IslandState.Compact -> CompactContent(
                        content = current.content,
                        secondary = current.secondary,
                        availableWidth = availableWidth,
                        availableHeight = availableHeight,
                        onExpand = onExpand,
                        onAction = onAction
                    )
                    is IslandState.Expanded -> ExpandedContent(
                        content = current.content,
                        secondary = current.secondary,
                        availableWidth = availableWidth,
                        availableHeight = availableHeight,
                        onCollapse = onCollapse,
                        onAction = onAction
                    )
                }
            }
        }
        } // end clipped content Box
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Adaptive size tiers. Every branch below asks "how much room do I actually
// have" instead of assuming the default 200x40 / 320x90, so the *same*
// composable works whether the user's slider is at 10dp or 400dp.
// ─────────────────────────────────────────────────────────────────────────

private val TINY_WIDTH = 46.dp
private val NARROW_WIDTH = 96.dp
// Compact height tiers: below MIN_FOR_SUBTITLE there's only room for one text line
// (~12sp ≈ 16dp line box + a hair of breathing room); below MIN_FOR_PROGRESS there's
// a second line (artist) but not a third (the progress bar), matching how a real
// Column of Text+Text+Bar actually stacks instead of a single guessed threshold.
private val MIN_HEIGHT_FOR_SUBTITLE = 28.dp
private val MIN_HEIGHT_FOR_PROGRESS = 42.dp

/**
 * Like [Dp.coerceIn], but never throws when [minimumValue] ends up greater than
 * [maximumValue] — which can happen at the extreme low end of the custom size
 * sliders (down to 10dp) once several proportional floors stack up. Falls back
 * to whichever bound is smaller instead of crashing the whole Island.
 */
private fun Dp.safeCoerceIn(minimumValue: Dp, maximumValue: Dp): Dp {
    val lo = minOf(minimumValue, maximumValue)
    val hi = maxOf(minimumValue, maximumValue)
    return this.coerceIn(lo, hi)
}

/**
 * A stable identity string for a piece of [IslandContent] used to key
 * interruption-badge animations: two values should share a key only when they
 * represent the *same ongoing event*, so a re-composition with fresh
 * position/lyric data (e.g. Media ticking every second) does NOT replay the
 * pop, but a genuinely new event (a new notification, a new device connecting)
 * does — even when both share the same IslandContent subtype.
 */
private fun IslandContent.interruptionKey(): String = when (this) {
    is IslandContent.Notification -> "notif:$packageName:$title:$arrivedAtMs"
    is IslandContent.Media -> "media:$title:$artist"
    is IslandContent.Charging -> "charging:$isCharging"
    is IslandContent.Ringer -> "ringer:$mode"
    is IslandContent.Flashlight -> "flash:$enabled"
    is IslandContent.Bluetooth -> "bt:$enabled:$deviceName"
    is IslandContent.Hotspot -> "hotspot:$enabled:$clientCount"
    is IslandContent.Custom -> "custom:$title:$subtitle"
}

// ─────────────────────────────────────────────────────────────────────────
// Minimal — idle capsule / camera cover
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun MinimalContent(availableWidth: Dp, availableHeight: Dp) {
    val breatheAlpha = rememberBreathingAlpha()
    val dotSize = minOf(8.dp, availableHeight * 0.3f, availableWidth * 0.15f)
        .safeCoerceIn(2.dp, minOf(availableWidth, availableHeight))
    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* idle — permanent camera-cutout pill, no action */ },
        contentAlignment = Alignment.Center
    ) {
        // A soft breathing dot so the idle pill still reads as "alive" rather than
        // a dead black rectangle — same restrained idea as iOS's sleeping indicator.
        Box(
            Modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = breatheAlpha))
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Compact — 大岛 summary
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun CompactContent(
    content: IslandContent,
    secondary: IslandContent? = null,
    availableWidth: Dp,
    availableHeight: Dp,
    onExpand: () -> Unit,
    onAction: (String) -> Unit
) {
    // Horizontal padding shrinks as the pill shrinks so tiny custom sizes don't
    // eat all their space in padding alone.
    val hPad = when {
        availableWidth < TINY_WIDTH -> 4.dp
        availableWidth < NARROW_WIDTH -> 8.dp
        else -> 12.dp
    }
    // Icon scales with available height but is never allowed to exceed it — a hard
    // floor like `coerceAtLeast(10.dp)` would force a 10dp icon into a 10dp-tall pill
    // with zero margin, clipping the moment padding/stroke is added. 4dp is small but
    // still a recognizable glyph at that extreme.
    val iconSize = minOf(18.dp, availableHeight * 0.55f).safeCoerceIn(4.dp, availableHeight)
    val isTight = availableWidth < NARROW_WIDTH
    val isTiny = availableWidth < TINY_WIDTH
    val showSubtitle = availableHeight >= MIN_HEIGHT_FOR_SUBTITLE
    val showProgress = availableHeight >= MIN_HEIGHT_FOR_PROGRESS

    // ── Interrupted mode: primary event + circular badge for secondary ──
    // Total footprint stays the same as a normal compact pill.
    // Secondary (interrupted) event shrinks into a round badge on the right.
    if (secondary != null) {
        InterruptedCompact(
            primary = content,
            secondary = secondary,
            availableWidth = availableWidth,
            availableHeight = availableHeight,
            hPad = hPad,
            iconSize = iconSize,
            isTight = isTight,
            isTiny = isTiny,
            showSubtitle = showSubtitle,
            onExpand = onExpand
        )
        return
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = hPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (isTight) 4.dp else 8.dp)
    ) {
        when (content) {
            is IslandContent.Media -> CompactMedia(
                content = content,
                iconSize = iconSize,
                isTight = isTight,
                isTiny = isTiny,
                showSubtitle = showSubtitle,
                showProgress = showProgress,
                availableHeight = availableHeight
            )
            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onExpand() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (isTight) 4.dp else 8.dp)
                ) {
                    NonMediaCompact(
                        content = content,
                        iconSize = iconSize,
                        isTight = isTight,
                        isTiny = isTiny,
                        showSubtitle = showSubtitle
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Interrupted compact: primary live-event + circular badge for secondary
// Overall size stays identical to a normal compact pill (no extra width).
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun InterruptedCompact(
    primary: IslandContent,
    secondary: IslandContent,
    availableWidth: Dp,
    availableHeight: Dp,
    hPad: Dp,
    iconSize: Dp,
    isTight: Boolean,
    isTiny: Boolean,
    showSubtitle: Boolean,
    onExpand: () -> Unit
) {
    val circleSize = (availableHeight * 0.82f).safeCoerceIn(18.dp, 36.dp)

    // Identity key for the *specific* secondary event, not just its type — so a
    // fresh notification replacing an older one (both IslandContent.Notification)
    // still re-triggers the pop, the same way a type change (Notification → Bluetooth)
    // would. Falls back to the class name for content with no natural identity.
    val secondaryKey = remember(secondary) { secondary.interruptionKey() }

    // Interruption pop-in: badge scales/rotates in from nothing with a springy
    // overshoot the instant a *new* secondary event appears, while the primary
    // row's own AnimatedContent below (keyed on the same identity) handles the
    // width it gives up. A manual Animatable (rather than AnimatedVisibility's
    // default) gives control over the overshoot curve so the badge feels
    // "flicked" into place rather than just fading up.
    val badgeAppear = remember(secondaryKey) { Animatable(0f) }
    LaunchedEffectOnce(secondaryKey) {
        badgeAppear.snapTo(0f)
        badgeAppear.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = 340f)
        )
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = hPad, end = (hPad.value * 0.45f).dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onExpand() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Primary content (the newest / interrupting event) — its own subtle
        // slide-from-left on first appearing after an interruption starts,
        // so it reads as "making room" for the badge rather than teleporting.
        Row(
            modifier = Modifier
                .weight(1f)
                .graphicsLayer {
                    val squeeze = 0.94f + 0.06f * badgeAppear.value
                    scaleX = squeeze
                    translationX = -(1f - badgeAppear.value) * 6.dp.toPx()
                    alpha = 0.55f + 0.45f * badgeAppear.value
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isTight) 4.dp else 6.dp)
        ) {
            when (primary) {
                is IslandContent.Media -> {
                    val liveAmp by AudioPulseEngine.amplitude.collectAsState()
                    val liveActive by AudioPulseEngine.isCapturing.collectAsState()
                    EqualizerBars(
                        isPlaying = primary.isPlaying,
                        maxHeight = minOf(14.dp, availableHeight * 0.45f),
                        modifier = Modifier,
                        realAmplitude = if (liveActive && primary.isPlaying) liveAmp else null
                    )
                    if (!isTiny) {
                        Text(
                            primary.title,
                            color = Color.White,
                            fontSize = if (isTight) 11.sp else 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                else -> NonMediaCompact(
                    content = primary,
                    iconSize = iconSize,
                    isTight = isTight,
                    isTiny = isTiny,
                    showSubtitle = showSubtitle
                )
            }
        }

        // Circular badge for the interrupted (secondary) event — pops in with
        // scale overshoot + a quick partial spin + fade, and (via
        // AnimatedContent keyed on the event's identity, not just its type)
        // re-plays that same pop whenever the secondary event itself changes
        // (e.g. one notification replaced by another while music keeps playing).
        AnimatedContent(
            targetState = secondary,
            contentKey = { it.interruptionKey() },
            transitionSpec = {
                (
                    (fadeIn(tween(180, easing = FastOutSlowInEasing)) +
                        scaleIn(
                            initialScale = 0.3f,
                            animationSpec = spring(dampingRatio = 0.5f, stiffness = 420f)
                        )) togetherWith
                        (fadeOut(tween(120)) +
                            scaleOut(
                                targetScale = 0.4f,
                                animationSpec = tween(140, easing = FastOutSlowInEasing)
                            ))
                ).using(SizeTransform(clip = false))
            },
            label = "interruptionBadge"
        ) { sec ->
            Box(
                modifier = Modifier.graphicsLayer {
                    // A brief overshoot spin on arrival only (settles to 0 quickly since
                    // badgeAppear reaches 1 fast) — gives the pop a little flourish
                    // without leaving the badge visibly rotated at rest.
                    rotationZ = (1f - badgeAppear.value) * -18f
                }
            ) {
                CircularEventBadge(
                    content = sec,
                    size = circleSize
                )
            }
        }
    }
}

/**
 * Runs [block] once whenever [key] changes identity, resetting on each new key
 * — used for one-shot "pop" animations (like the interruption badge) that
 * should replay on every new interruption rather than only on first composition.
 */
@Composable
private fun LaunchedEffectOnce(key: Any?, block: suspend () -> Unit) {
    LaunchedEffect(key) { block() }
}

/** Circular badge for any secondary (interrupted) event. */
@Composable
private fun CircularEventBadge(
    content: IslandContent,
    size: Dp
) {
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        when (content) {
            is IslandContent.Media -> {
                val progress = if (content.durationMs > 0) {
                    (content.positionMs.toFloat() / content.durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f
                val animatedProgress by animateFloatAsState(
                    targetValue = progress,
                    animationSpec = tween(900, easing = LinearEasing),
                    label = "sideMediaProgress"
                )
                MusicProgressRing(
                    progress = animatedProgress,
                    cornerRadius = size / 2,
                    isPlaying = content.isPlaying,
                    strokeWidth = 2.4.dp,
                    modifier = Modifier.fillMaxSize()
                )
                val liveAmp by AudioPulseEngine.amplitude.collectAsState()
                val liveActive by AudioPulseEngine.isCapturing.collectAsState()
                EqualizerBars(
                    isPlaying = content.isPlaying,
                    maxHeight = size * 0.38f,
                    barWidth = 1.8.dp,
                    color = Color.White,
                    modifier = Modifier,
                    realAmplitude = if (liveActive && content.isPlaying) liveAmp else null
                )
            }
            else -> {
                // Soft ring track + event icon in the centre
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 2.2.dp.toPx()
                    drawCircle(
                        color = Color.White.copy(alpha = 0.18f),
                        radius = (this.size.minDimension - stroke) / 2f,
                        style = Stroke(width = stroke)
                    )
                }
                SecondaryEventIcon(content = content, size = size * 0.48f)
            }
        }
    }
}

@Composable
private fun SecondaryEventIcon(content: IslandContent, size: Dp) {
    when (content) {
        is IslandContent.Notification -> {
            BellRingIcon(ringTrigger = content.arrivedAtMs, size = size, color = Color(0xFF60A5FA))
            return
        }
        is IslandContent.Bluetooth -> {
            if (content.enabled) {
                // deviceName can be null (unnamed/unknown device) — fall back to a
                // stable non-null key so the connect-pulse LaunchedEffect still has
                // a valid Any to key off, while still changing (and re-triggering
                // the animation) whenever the actual device name changes.
                BluetoothConnectIcon(connectTrigger = content.deviceName ?: "unknown", size = size)
            } else {
                Icon(Icons.Rounded.Bluetooth, null, tint = Color(0xFF60A5FA), modifier = Modifier.size(size))
            }
            return
        }
        is IslandContent.Hotspot -> {
            if (content.enabled) {
                HotspotBroadcastIcon(size = size)
            } else {
                Icon(Icons.Rounded.WifiTethering, null, tint = Color(0xFF34D399), modifier = Modifier.size(size))
            }
            return
        }
        is IslandContent.Flashlight -> {
            FlashlightGlowIcon(enabled = content.enabled, size = size)
            return
        }
        is IslandContent.Ringer -> {
            when (content.mode) {
                com.hyperisland.root.root.RingerMode.VIBRATE -> VibrationShakeIcon(size = size, color = Color.White)
                com.hyperisland.root.root.RingerMode.DND -> DndPulseIcon(size = size, color = Color(0xFFF87171))
                else -> {
                    val icon = if (content.mode == com.hyperisland.root.root.RingerMode.SILENT)
                        Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(size))
                }
            }
            return
        }
        else -> {}
    }
    val (icon, tint) = when (content) {
        is IslandContent.Charging -> Icons.Rounded.BatteryChargingFull to Color(0xFF4ADE80)
        is IslandContent.Custom -> Icons.Rounded.Info to Color.White
        is IslandContent.Media -> Icons.Rounded.MusicNote to Color.White
        else -> Icons.Rounded.Info to Color.White // unreachable: Notification/Bluetooth/Hotspot/Flashlight/Ringer handled above
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

@Composable
private fun RowScope.CompactMedia(
    content: IslandContent.Media,
    iconSize: Dp,
    isTight: Boolean,
    isTiny: Boolean,
    showSubtitle: Boolean,
    showProgress: Boolean,
    availableHeight: Dp
) {
    // Live media is display-only: no play/pause or skip controls, just an
    // animated "now playing" glyph + title/artist — a live activity, not a
    // control surface.

    // Real audio signal (null when no RECORD_AUDIO / no capturable session —
    // composables below fall back to their generic loop automatically).
    val liveAmplitude by AudioPulseEngine.amplitude.collectAsState()
    val liveBeatPhase by AudioPulseEngine.beatPhase.collectAsState()
    val liveBpm by AudioPulseEngine.bpm.collectAsState()
    val isCapturing by AudioPulseEngine.isCapturing.collectAsState()
    val realAmplitude = if (isCapturing && content.isPlaying) liveAmplitude else null
    val realBeatPhase = if (isCapturing && content.isPlaying && liveBpm > 0f) liveBeatPhase else null
    val realBpm = if (isCapturing && liveBpm > 0f) liveBpm else null

    if (isTiny) {
        EqualizerBars(
            isPlaying = content.isPlaying,
            maxHeight = minOf(12.dp, availableHeight * 0.5f),
            modifier = Modifier,
            realAmplitude = realAmplitude
        )
        return
    }

    // Stack: RGB wave strings as a soft background, then eq + title/artist on top.
    Box(Modifier.weight(1f).fillMaxSize()) {
        // 2 tali gelombang RGB spanning the full content width (sketch)
        MusicWaveStrings(
            isPlaying = content.isPlaying,
            amplitude = minOf(5.dp, availableHeight * 0.22f),
            strokeWidth = 1.5.dp,
            beatPhase = realBeatPhase,
            bpm = realBpm,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp)
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isTight) 4.dp else 6.dp)
        ) {
            EqualizerBars(
                isPlaying = content.isPlaying,
                maxHeight = minOf(14.dp, availableHeight * 0.45f),
                modifier = Modifier,
                realAmplitude = realAmplitude
            )
            Column(Modifier.weight(1f)) {
                Text(
                    content.title,
                    color = Color.White,
                    fontSize = if (isTight) 11.sp else 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    modifier = if (isTight) Modifier else Modifier.basicMarquee()
                )
                if (!isTight && showSubtitle) {
                    Text(
                        content.artist,
                        color = Color.White.copy(0.7f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (showProgress && content.durationMs > 0) {
                    val progress = (content.positionMs.toFloat() / content.durationMs.toFloat()).coerceIn(0f, 1f)
                    AnimatedProgressTrack(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.NonMediaCompact(
    content: IslandContent,
    iconSize: Dp,
    isTight: Boolean,
    isTiny: Boolean,
    showSubtitle: Boolean = true
) {
    when (content) {
        is IslandContent.Notification -> {
            BellRingIcon(
                ringTrigger = content.arrivedAtMs,
                size = iconSize,
                color = Color.White
            )
            if (!isTiny) {
                Column(Modifier.weight(1f)) {
                    Text(
                        content.title,
                        color = Color.White,
                        fontSize = if (isTight) 11.sp else 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!isTight && showSubtitle) {
                        Text(
                            content.text,
                            color = Color.White.copy(0.7f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                // No room for text at all — a live dot still signals "something happened".
                LiveDot(color = Color(0xFF60A5FA))
            }
        }
        is IslandContent.Charging -> {
            AnimatedBatteryGlyph(
                level = content.level,
                isCharging = content.isCharging,
                width = iconSize,
                height = iconSize * 0.55f
            )
            if (!isTiny) {
                Text(
                    "${content.level}%",
                    color = Color.White,
                    fontSize = if (isTight) 12.sp else 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (!isTight && content.isCharging && content.currentMa != 0) {
                Text("${content.currentMa}mA", color = Color.White.copy(0.7f), fontSize = 10.sp)
            }
        }
        is IslandContent.Ringer -> {
            when (content.mode) {
                RingerMode.VIBRATE -> VibrationShakeIcon(size = iconSize, color = Color.White)
                RingerMode.DND -> DndPulseIcon(size = iconSize, color = Color(0xFFF87171))
                else -> {
                    val icon = when (content.mode) {
                        RingerMode.NORMAL -> Icons.Rounded.VolumeUp
                        RingerMode.SILENT -> Icons.Rounded.VolumeOff
                        else -> Icons.Rounded.VolumeUp
                    }
                    AnimatedContent(
                        targetState = icon,
                        transitionSpec = {
                            (scaleIn(spring(dampingRatio = 0.55f, stiffness = 550f)) + fadeIn())
                                .togetherWith(fadeOut(tween(100)))
                        },
                        label = "ringerIcon"
                    ) { animatedIcon ->
                        Icon(animatedIcon, null, tint = Color.White, modifier = Modifier.size(iconSize))
                    }
                }
            }
            if (!isTiny) {
                val label = when (content.mode) {
                    RingerMode.NORMAL -> "Ring"
                    RingerMode.VIBRATE -> "Vibrate"
                    RingerMode.SILENT -> "Silent"
                    RingerMode.DND -> "DND"
                }
                Text(label, color = Color.White, fontSize = if (isTight) 12.sp else 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        is IslandContent.Flashlight -> {
            FlashlightGlowIcon(enabled = content.enabled, size = iconSize)
            if (!isTiny) {
                Text(if (content.enabled) "On" else "Off", color = Color.White, fontSize = if (isTight) 12.sp else 13.sp)
            }
        }
        is IslandContent.Bluetooth -> {
            if (content.enabled) {
                BluetoothConnectIcon(
                    connectTrigger = content.deviceName ?: "unknown",
                    size = iconSize
                )
            } else {
                Icon(Icons.Rounded.Bluetooth, null, tint = Color(0xFF60A5FA), modifier = Modifier.size(iconSize))
            }
            if (!isTiny) {
                Text(
                    content.deviceName ?: if (content.enabled) "On" else "Off",
                    color = Color.White,
                    fontSize = if (isTight) 11.sp else 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        is IslandContent.Hotspot -> {
            if (content.enabled) {
                HotspotBroadcastIcon(size = iconSize)
            } else {
                Icon(Icons.Rounded.WifiTethering, null, tint = Color(0xFF34D399), modifier = Modifier.size(iconSize))
            }
            if (!isTiny) {
                Text(
                    if (content.enabled) "Hotspot" else "Off",
                    color = Color.White,
                    fontSize = if (isTight) 11.sp else 12.sp
                )
            }
        }
        is IslandContent.Custom -> {
            if (!isTiny) {
                Text(
                    content.title,
                    color = Color.White,
                    fontSize = if (isTight) 11.sp else 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                LiveDot(color = Color.White.copy(alpha = 0.8f))
            }
        }
        else -> {}
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Expanded — 展开态 focus card
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun ExpandedContent(
    content: IslandContent,
    secondary: IslandContent? = null,
    availableWidth: Dp,
    availableHeight: Dp,
    onCollapse: () -> Unit,
    onAction: (String) -> Unit
) {
    // Below this, there's no room for a two-line layout — everything folds
    // into a single adaptive row so nothing clips off the bottom/edge when
    // the user has set the Expand slider close to its 10dp floor.
    val isCramped = availableHeight < 48.dp || availableWidth < 140.dp
    val hPad = if (availableWidth < 100.dp) 8.dp else 12.dp
    val vPad = if (availableHeight < 60.dp) 4.dp else 8.dp
    // Children size themselves off the space actually left *inside* the padding,
    // not the raw box size — otherwise an icon sized from availableHeight could
    // still poke past the padded content area on a very short custom pill.
    val innerHeight = (availableHeight - vPad * 2).coerceAtLeast(0.dp)
    val innerWidth = (availableWidth - hPad * 2).coerceAtLeast(0.dp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = hPad, vertical = vPad),
        verticalArrangement = Arrangement.Center
    ) {
        when (content) {
            // IslandContent.Media intentionally has no Expanded rendering: media is
            // compact-only, a live activity that never opens into the expanded card.
            is IslandContent.Charging -> ExpandedCharging(content, isCramped, innerHeight, onCollapse)
            is IslandContent.Notification -> ExpandedNotification(content, isCramped, onCollapse)
            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onCollapse() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (isCramped) 6.dp else 10.dp)
                ) {
                    val iconSize = minOf(if (isCramped) 20.dp else 26.dp, innerHeight * 0.6f)
                        .safeCoerceIn(4.dp, innerHeight)
                    NonMediaCompact(
                        content = content,
                        iconSize = iconSize,
                        isTight = isCramped,
                        isTiny = innerWidth < TINY_WIDTH
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandedCharging(
    content: IslandContent.Charging,
    isCramped: Boolean,
    availableHeight: Dp,
    onCollapse: () -> Unit
) {
    val glyphSize = minOf(if (isCramped) 26.dp else 40.dp, availableHeight * 0.7f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (isCramped) 8.dp else 12.dp),
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) { onCollapse() }
    ) {
        AnimatedBatteryGlyph(
            level = content.level,
            isCharging = content.isCharging,
            width = glyphSize,
            height = glyphSize * 0.5f
        )
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                "${content.level}%",
                color = Color.White,
                fontSize = if (isCramped) 16.sp else 22.sp,
                fontWeight = FontWeight.Bold
            )
            if (!isCramped) {
                Text(
                    if (content.isCharging)
                        "Charging • ${content.currentMa} mA • ${"%.1f".format(content.temperatureC)}°C"
                    else "Not charging",
                    color = Color.White.copy(0.75f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Estimated fill progress bar — a small "still charging" live cue,
                // similar to the ring iOS shows around the battery glyph. Width is
                // capped relative to the card, not a fixed 120dp, so it can never
                // push past a narrower custom Expand size.
                if (content.isCharging) {
                    Spacer(Modifier.height(4.dp))
                    AnimatedProgressTrack(
                        progress = content.level / 100f,
                        modifier = Modifier.widthIn(max = 120.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandedNotification(
    content: IslandContent.Notification,
    isCramped: Boolean,
    onCollapse: () -> Unit
) {
    Column(
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) { onCollapse() }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BellRingIcon(
                ringTrigger = content.arrivedAtMs,
                size = if (isCramped) 14.dp else 16.dp,
                color = Color.White
            )
            Text(
                content.title,
                color = Color.White,
                fontSize = if (isCramped) 12.sp else 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!isCramped) {
            Text(
                content.text,
                color = Color.White.copy(0.8f),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
