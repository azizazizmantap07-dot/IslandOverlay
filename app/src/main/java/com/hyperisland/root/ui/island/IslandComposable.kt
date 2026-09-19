package com.hyperisland.root.ui.island

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
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
    isFullscreen: Boolean = false,
    isScreenOn: Boolean = true,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onAction: (String) -> Unit = {}
) {
    // Hide everything in landscape, immersive fullscreen, or while the screen
    // is off. Screen-off in particular matters for battery: this collapses
    // the AnimatedVisibility children to nothing, which stops Compose from
    // scheduling any more frames for the comet ring / shimmer / wave strings
    // / etc. underneath — draws nobody could see anyway while the display is
    // off. Nothing about how those animations look or feel changes; they
    // simply pick back up the instant the screen turns back on.
    val screenActive = !isLandscape && !isFullscreen && isScreenOn
    val isExpanded = state is IslandState.Expanded
    // Pill (Minimal/Compact) only when not expanded.
    val showPill = screenActive && !isExpanded
    // Notification-style popup below status bar when Expanded.
    val showPopup = screenActive && isExpanded

    // When the island must be hidden, emit NOTHING at all — not even an empty
    // fillMaxWidth() Box. An empty Box still measures to a real size and keeps
    // the ComposeView's root layer alive, which is what left a shadow ghost and
    // a phantom touch strip behind. IslandOverlayService additionally parks the
    // window itself (see applyWindowVisibility); this is the Compose half.
    if (!screenActive) return

    Box(modifier = Modifier.fillMaxWidth()) {
        // ── Status-bar pill ──────────────────────────────────────────────
        // Lightweight fade only — no spring / size morph on the expand switch.
        // Heavy stacked animations here were the main source of lag & flicker.
        AnimatedVisibility(
            visible = showPill,
            enter = fadeIn(tween(100, easing = FastOutSlowInEasing)),
            exit = ExitTransition.None
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                IslandContentView(
                    // When collapsing from Expanded we land on Minimal/Compact;
                    // never pass Expanded into the pill renderer.
                    state = when (state) {
                        is IslandState.Expanded -> IslandState.Minimal
                        else -> state
                    },
                    onExpand = onExpand,
                    onCollapse = onCollapse,
                    onAction = onAction
                )
            }
        }

        // ── Expanded popup (below status bar, notification-style) ────────
        // Snappy fade + slight scale from top. Pure short tweens only —
        // no expandVertically spring (that caused layout thrash / glitch).
        AnimatedVisibility(
            visible = showPopup,
            enter = fadeIn(tween(120, easing = FastOutSlowInEasing)) +
                scaleIn(
                    initialScale = 0.92f,
                    transformOrigin = TransformOrigin(0.5f, 0f),
                    animationSpec = tween(140, easing = FastOutSlowInEasing)
                ),
            exit = ExitTransition.None
        ) {
            if (state is IslandState.Expanded) {
                ExpandedPopupCard(
                    content = state.content,
                    secondary = state.secondary,
                    onCollapse = onCollapse,
                    onAction = onAction
                )
            }
        }
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

    // Size morph — stiffer + higher damping = settles fast, less overshoot/glitch
    // when live events swap (notif interrupt, play/pause, etc).
    val sizeSpring = spring<Dp>(
        dampingRatio = 0.86f,
        stiffness = 1100f
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

    // Elevation follows size quickly; long tween here lagged behind size morph.
    val elevationTarget = when (state) {
        is IslandState.Minimal -> 2f
        is IslandState.Compact -> 6f
        is IslandState.Expanded -> 12f
    }
    val elevation by animateFloatAsState(
        targetValue = elevationTarget,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "islandElevation"
    )
    val pillShape = RoundedCornerShape(cornerRadius)

    // Media can be either primary content or secondary (when interrupted).
    val secondaryContent = when (state) {
        is IslandState.Compact -> state.secondary
        is IslandState.Expanded -> state.secondary
        else -> null
    }
    val primaryContent = when (state) {
        is IslandState.Compact -> state.content
        is IslandState.Expanded -> state.content
        else -> null
    }
    val primaryMedia = primaryContent as? IslandContent.Media
    val secondaryMedia = secondaryContent as? IslandContent.Media
    val mediaForProgress = secondaryMedia ?: primaryMedia
    val mediaProgress = if (mediaForProgress != null && mediaForProgress.durationMs > 0) {
        (mediaForProgress.positionMs.toFloat() / mediaForProgress.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    // Keep progress ring close to real position (1s ticks from MediaSessionTracker).
    // 900ms lag made the ring feel "stuck" and competed with size morphs.
    val animatedMediaProgress by animateFloatAsState(
        targetValue = mediaProgress,
        animationSpec = tween(durationMillis = 280, easing = LinearEasing),
        label = "mediaRingProgress"
    )
    // Ring modes:
    //  - Minimal          → always ChasingCometRing (2 RGB comets)
    //  - Pure music       → MusicProgressRing following song progress
    //  - Any non-music live event (or music interrupted by another event)
    //                     → FullRgbRing (entire perimeter filled, like song finished)
    val isMinimal = state is IslandState.Minimal
    val isPureMusic = primaryMedia != null && secondaryContent == null &&
        (primaryMedia.isPlaying || animatedMediaProgress > 0.01f)
    val isNonMusicLive = !isMinimal && !isPureMusic && primaryContent != null

    // Touch-safe outer box: for Compact, wider + taller than the visual pill
    // so expand taps are forgiving near the status-bar edges. Height is never
    // less than MIN_TOUCH_TARGET_HEIGHT. The visible pill is centered inside;
    // the extra region is transparent and only exists to capture touches that
    // would otherwise be eaten by SystemUI's status bar.
    val hitAreaHeight = maxOf(targetHeight, MIN_TOUCH_TARGET_HEIGHT)
    val hitAreaWidth = if (state is IslandState.Compact) {
        targetWidth + COMPACT_HIT_PAD_H * 2
    } else {
        targetWidth
    }
    // Only Compact is tap-to-expand (Minimal has no tap action, Expanded has its
    // own internal buttons/collapse handling) — so only extend the clickable
    // behavior onto the padded hit-area for Compact. This mirrors exactly what
    // CompactContent's own clickable already does; having both is harmless
    // (same lambda), it just guarantees the padding region below/beside the
    // visual pill is tappable too, not only the pill's own drawn pixels.
    // Shared interaction source so the outer hit-area's clickable and the
    // press-scale feedback (applied to the inner visual pill, not the padded
    // hit-area) react to the same touch stream.
    val compactInteractionSource = remember { MutableInteractionSource() }
    val hitAreaClickable = if (state is IslandState.Compact) {
        Modifier.clickable(
            interactionSource = compactInteractionSource,
            indication = null,
            onClick = onExpand
        )
    } else {
        Modifier
    }

    // Press-scale only applies to Compact (the only tappable pill state);
    // computed as a plain val (not inside the modifier chain) so the
    // conditional composable call sits at ordinary statement level.
    val pillPressModifier = if (state is IslandState.Compact) {
        Modifier.pressScale(compactInteractionSource)
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .width(hitAreaWidth)
            .height(hitAreaHeight)
            .then(hitAreaClickable),
        contentAlignment = Alignment.TopCenter
    ) {
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
            }
            .then(pillPressModifier),
        contentAlignment = Alignment.Center
    ) {
        // Pill background is drawn FIRST so the ring (added right after, below)
        // paints on top of it instead of being covered by it. Previously the
        // ring was emitted before this Box, and since later siblings draw over
        // earlier ones, the opaque background painted right on top of the ring
        // — only the small sliver of glow that poked outside the clipped shape
        // near the corners survived, which read as "the ring only shows at the
        // corners".
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

            // Content crossfade between Minimal / Compact / Expanded.
            // Size is driven separately by animateDpAsState (sizeSpring) so this
            // only handles content identity changes — keep it light to avoid
            // fighting the size morph.
            AnimatedContent(
                targetState = state,
                // Key on shape + content *type* (and secondary type), NOT full data class.
                // Media position ticks every ~1s would otherwise replay fade/scale → flicker.
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
                    // Lighter transitions: fade + mild scale only (no long slide).
                    // Soft multi-axis enter/exit was stacking with sizeSpring and
                    // interruption badge → glitch / stutter on live event swaps.
                    val enter = fadeIn(tween(110, easing = FastOutSlowInEasing)) +
                        scaleIn(
                            initialScale = 0.94f,
                            transformOrigin = TransformOrigin.Center,
                            animationSpec = spring(dampingRatio = 0.88f, stiffness = 1000f)
                        )
                    val exit = fadeOut(tween(80, easing = FastOutSlowInEasing)) +
                        scaleOut(
                            targetScale = 0.96f,
                            transformOrigin = TransformOrigin.Center,
                            animationSpec = tween(80, easing = FastOutSlowInEasing)
                        )
                    (enter togetherWith exit).using(
                        SizeTransform(clip = false) { _, _ ->
                            spring(dampingRatio = 0.88f, stiffness = 1000f)
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
                    // Expanded is rendered by ExpandedPopupCard in OrientationAwareIsland,
                    // never by the status-bar pill. Guard just in case state is passed in.
                    is IslandState.Expanded -> MinimalContent(
                        availableWidth = availableWidth,
                        availableHeight = availableHeight
                    )
                }
            }
        }
        } // end clipped content Box

        // Edge ring drawn LAST so it always paints over the opaque pill background.
        // Priority (each gated by user animation toggles in IslandPreferences):
        //  1. Minimal          → 2 chasing RGB comets   [animRingMinimal]
        //  2. Pure music       → progress ring by song  [animRingMusicProgress]
        //  3. Any other live   → full RGB ring          [animRingCompact]
        when {
            isMinimal && layout.animRingMinimal -> {
                ChasingCometRing(
                    cornerRadius = cornerRadius,
                    strokeWidth = 1.8.dp,
                    modifier = Modifier.fillMaxSize()
                )
            }
            isPureMusic && layout.animRingMusicProgress -> {
                MusicProgressRing(
                    progress = animatedMediaProgress,
                    cornerRadius = cornerRadius,
                    isPlaying = primaryMedia?.isPlaying == true,
                    strokeWidth = 1.8.dp,
                    modifier = Modifier.fillMaxSize()
                )
            }
            isNonMusicLive && layout.animRingCompact -> {
                FullRgbRing(
                    cornerRadius = cornerRadius,
                    strokeWidth = 1.8.dp,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    } // end visual pill Box (real targetWidth x targetHeight)
    } // end touch-safe hit-area Box (targetWidth x max(targetHeight, MIN_TOUCH_TARGET_HEIGHT))
}

