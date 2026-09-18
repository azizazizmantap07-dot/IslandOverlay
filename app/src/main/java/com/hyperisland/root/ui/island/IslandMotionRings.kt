package com.hyperisland.root.ui.island

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.DoNotDisturb
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Perimeter rings and wave strings for the Dynamic Island pill edge.
 */
/**
 * Reusable holder for the rounded-rect outline path shared by
 * [MusicProgressRing] / [ChasingCometRing] / [FullRgbRing].
 *
 * Both rings previously rebuilt this path (4x `arcTo` + a fresh [PathMeasure])
 * from scratch on *every single draw frame*, even though the geometry only
 * actually depends on the pill's current width/height/corner-radius — which
 * change at most a few times per second (during a size morph), while the
 * rings themselves redraw at the full display refresh rate (up to 120 Hz on
 * this device class) because of their perpetual hue/pulse/shimmer animations.
 * That meant ~120 wasted Path rebuilds + PathMeasure re-scans per second per
 * ring, purely for geometry that hadn't changed.
 *
 * [ensure] rebuilds the path only when the inset box or corner radius has
 * moved measurably (>0.5px) since the last draw, and reuses the same [Path]
 * / [PathMeasure] instances otherwise — same visual output, far less
 * per-frame CPU work, which is what actually keeps the animation buttery on
 * mid-range GPUs instead of dropping frames under the hue/shimmer load.
 */
internal class RingPathCache {
    val path = Path()
    val measure = PathMeasure()
    var length: Float = 0f
        private set
    private var lastW = Float.NaN
    private var lastH = Float.NaN
    private var lastR = Float.NaN
    private var lastInset = Float.NaN

    fun ensure(w: Float, h: Float, r: Float, inset: Float, canvasWidth: Float) {
        if (w == lastW && h == lastH && r == lastR && inset == lastInset) return
        lastW = w; lastH = h; lastR = r; lastInset = inset

        path.reset()
        val left = inset
        val top = inset
        val right = inset + w
        val bottom = inset + h
        val cx = canvasWidth / 2f

        path.moveTo(cx, top)
        if (r < 0.5f) {
            path.lineTo(right, top)
            path.lineTo(right, bottom)
            path.lineTo(left, bottom)
            path.lineTo(left, top)
            path.lineTo(cx, top)
        } else {
            path.lineTo(right - r, top)
            path.arcTo(
                rect = Rect(right - 2 * r, top, right, top + 2 * r),
                startAngleDegrees = -90f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false
            )
            path.lineTo(right, bottom - r)
            path.arcTo(
                rect = Rect(right - 2 * r, bottom - 2 * r, right, bottom),
                startAngleDegrees = 0f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false
            )
            path.lineTo(left + r, bottom)
            path.arcTo(
                rect = Rect(left, bottom - 2 * r, left + 2 * r, bottom),
                startAngleDegrees = 90f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false
            )
            path.lineTo(left, top + r)
            path.arcTo(
                rect = Rect(left, top, left + 2 * r, top + 2 * r),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false
            )
            path.lineTo(cx, top)
        }
        path.close()

        measure.setPath(path, forceClosed = false)
        length = measure.length
    }
}


/**
 * Progress ring drawn along the outer edge of a rounded-rect (pill) Dynamic Island.
 *
 * - Starts at the **top-center** and fills clockwise until it meets the start again.
 * - Stroke uses a smooth RGB blend of 3 randomly chosen vibrant colors that slowly
 *   cycle (hue shift) so the ring never looks static.
 * - [progress] is a pure 0..1 fraction of the song; the path is rebuilt from the
 *   *current* canvas size every frame, so the visual fill stays correct even when
 *   the user resizes the island mid-track (adaptive length).
 * - Pair with [animateFloatAsState] on the caller so 1 Hz position ticks still
 *   look continuous.
 */
@Composable
fun MusicProgressRing(
    progress: Float,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 1.8.dp,
    isPlaying: Boolean = true,
    /** When false, skips the rotating shimmer sweep so the ring does not look
     *  like a spinning transparent propeller (used by FullRgbRing for non-music
     *  live events such as hotspot / location / wifi toggles). */
    shimmerEnabled: Boolean = true
) {
    val clamped = progress.coerceIn(0f, 1f)
    if (clamped <= 0f && !isPlaying) return

    // Ultra-bright neon palette — pure sat + full value, biased to primaries.
    // Base hues are kept as raw Float degrees (not Color) so the per-frame
    // shift below is a plain float add instead of an RGB->HSV conversion.
    val baseHues = remember {
        val rnd = kotlin.random.Random(System.currentTimeMillis())
        val picks = listOf(0f, 25f, 55f, 120f, 175f, 200f, 270f, 300f, 330f)
        FloatArray(6) {
            if (rnd.nextFloat() < 0.7f) {
                (picks[rnd.nextInt(picks.size)] + rnd.nextFloat() * 18f - 9f + 360f) % 360f
            } else {
                rnd.nextFloat() * 360f
            }
        }
    }

    val colorShift by rememberInfiniteTransition(label = "ringHue").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 5_000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "hueShift"
    )

    // Head glow: subtle alpha breathing on a *fixed thin* tip dot.
    // Size stays constant so it never looks thick; only brightness pulses.
    val headPulse by rememberInfiniteTransition(label = "ringHeadPulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = if (isPlaying) 900 else 1800, easing = EaseInOutSine),
            RepeatMode.Reverse
        ),
        label = "ringHeadPulseValue"
    )

    // Fine shimmer traveling along the filled arc — cheap "energy flowing" cue,
    // implemented as a second phase-shifted sweep gradient blended additively
    // on top of the core stroke rather than a whole extra geometry pass.
    val shimmerPhase by rememberInfiniteTransition(label = "ringShimmer").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = if (isPlaying) 1600 else 4000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "ringShimmerValue"
    )

    // Recomputed every frame (colorShift animates continuously) but now a
    // direct hue add + Color.hsv() per entry — no FloatArray alloc, no
    // toArgb()/colorToHSV() round trip. Same resulting colors as before.
    val shiftedColors = remember(baseHues, colorShift) {
        List(baseHues.size) { i -> Color.hsv((baseHues[i] + colorShift) % 360f, 1.0f, 1.0f) }
    }

    // Path + PathMeasure are reused across frames — see [RingPathCache] — since
    // this ring redraws every frame (hue/shimmer/head-pulse are all perpetual
    // animations) but the actual outline only changes when the pill resizes.
    val pathCache = remember { RingPathCache() }
    // Segment path is also a reused instance (not `Path()` per frame) — only
    // its contents are rewritten each draw via getSegment, same as before.
    val segmentPath = remember { Path() }

    Canvas(modifier = modifier) {
        val strokePx = strokeWidth.toPx()
        // Inset so the stroke sits centered on the visual edge of the pill
        // (half inside / half outside). Prevents the ring from being clipped
        // by the parent's clip(shape) when drawn as an overlay.
        val inset = strokePx / 2f
        val w = size.width - inset * 2
        val h = size.height - inset * 2
        if (w <= 0f || h <= 0f) return@Canvas

        // Match the ring's corner radius to the *actual* pill shape instead of
        // independently re-deriving it from this Canvas's own inset size.
        // [cornerRadius] already comes in pre-clamped to the real pill's
        // width/height (see IslandContentView.safeCornerTarget), so shrinking
        // it here again by min(w, h) / 2f — computed from a slightly smaller,
        // inset-adjusted box — produced a radius a few px shy of the
        // background's actual corner. That mismatch is barely visible on a
        // large rounded card, but on a near-capsule compact pill (e.g.
        // 200x30dp, where the corner radius is ~half the height and the arcs
        // make up most of the perimeter) it was enough for the straight top/
        // bottom edges of the ring to fall out of sync with the pill outline,
        // so only the corner arcs still lined up — reading as "the ring only
        // shows at the corners". Deriving the radius from the outer pill size
        // (only trimmed by the stroke inset, never re-halved) keeps the ring
        // path glued to the pill's true outline at every compact size.
        val outerR = cornerRadius.toPx()
        val r = (outerR - inset).coerceIn(0f, min(w, h) / 2f)

        // Build (or reuse) a closed rounded-rect path that *starts at
        // top-center* and travels clockwise. PathMeasure then lets us stroke
        // only the first `progress` fraction of the perimeter. Only rebuilt
        // when w/h/r/inset actually moved since the last frame.
        pathCache.ensure(w, h, r, inset, size.width)
        val path = pathCache.path
        val measure = pathCache.measure
        val totalLen = pathCache.length
        if (totalLen <= 0f) return@Canvas

        val drawLen = totalLen * clamped
        val segment = segmentPath.apply { reset() }
        measure.getSegment(0f, drawLen, segment, startWithMoveTo = true)

        // Soft track (full perimeter). Butt cap (not Round) at the start/close
        // seam — a Round cap here doubled up with the segment's own cap and
        // read as a stray "bump" right at top-center where progress starts.
        drawPath(
            path = path,
            color = Color.White.copy(alpha = 0.14f),
            style = Stroke(width = strokePx, cap = StrokeCap.Butt)
        )

        val brush = Brush.sweepGradient(
            colors = shiftedColors + shiftedColors.first(),
            center = Offset(size.width / 2f, size.height / 2f)
        )

        // The filled segment uses a Butt cap at BOTH ends so the tip stays clean
        // and thin (no round cap / head dot). Glow layers kept tight so the ring
        // never looks thick.
        val segStroke = Stroke(width = strokePx, cap = StrokeCap.Butt, join = StrokeJoin.Round)
        val glowStroke = Stroke(width = strokePx * 1.55f, cap = StrokeCap.Butt, join = StrokeJoin.Round)

        // Soft outer glow — much tighter than before (was 2.6x) so the ring stays thin
        drawPath(path = segment, brush = brush, style = glowStroke, alpha = 0.38f)
        // Secondary glow — narrow falloff
        drawPath(
            path = segment,
            brush = brush,
            style = Stroke(width = strokePx * 1.25f, cap = StrokeCap.Butt, join = StrokeJoin.Round),
            alpha = 0.40f
        )
        // Core bright stroke
        drawPath(path = segment, brush = brush, style = segStroke)
        // Traveling shimmer highlight (kept narrow). Skipped for FullRgbRing
        // (non-music live events) so the perimeter does not look like a
        // spinning transparent propeller / bayangan berputar.
        if (shimmerEnabled) {
            rotate(degrees = shimmerPhase, pivot = Offset(size.width / 2f, size.height / 2f)) {
                drawPath(
                    path = segment,
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            Color.White.copy(alpha = 0.75f),
                            Color.Transparent,
                            Color.Transparent
                        ),
                        center = Offset(size.width / 2f, size.height / 2f)
                    ),
                    style = Stroke(width = strokePx * 0.7f, cap = StrokeCap.Butt, join = StrokeJoin.Round),
                    alpha = 0.45f
                )
            }
        }
        // Hairline white hot-core
        drawPath(
            path = segment,
            color = Color.White,
            style = Stroke(width = strokePx * 0.28f, cap = StrokeCap.Butt, join = StrokeJoin.Round),
            alpha = 0.50f
        )

        // Head dot: size restored closer to original (1.35x / 0.55x stroke) so
        // the tip is clearly visible. Radius is fixed — only alpha breathes via
        // headPulse. Ring stroke + glow layers stay thin (unchanged).
        if (clamped > 0.001f) {
            val pos = measure.getPosition(drawLen)
            val headColor = shiftedColors.last()
            drawCircle(
                color = headColor,
                radius = strokePx * 1.35f,
                center = pos,
                alpha = 0.40f * headPulse
            )
            drawCircle(
                color = Color.White,
                radius = strokePx * 0.55f,
                center = pos,
                alpha = 0.90f * headPulse
            )
        }
    }
}


/**
 * Minimal-state ring: two RGB comets chasing each other clockwise around the
 * pill edge, always 180° apart so the gaps left/right (or front/back) stay equal.
 *
 * Head size & stroke match [MusicProgressRing] (stroke 1.8.dp default, head
 * glow = stroke×1.35 / core = stroke×0.55, alpha-breathing pulse). Tails are
 * short RGB streaks trailing behind each head (~18% of perimeter) so the
 * overall look stays light and matches the thin music-progress aesthetic.
 * Colors continuously hue-shift like the music ring.
 */
@Composable
fun ChasingCometRing(
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 1.8.dp,
    /** Fraction of the perimeter used for each comet's tail (0.12–0.22 looks balanced). */
    tailFraction: Float = 0.18f
) {
    // Base hues kept as raw Float degrees — see MusicProgressRing for why.
    val baseHues = remember {
        val rnd = kotlin.random.Random(System.currentTimeMillis())
        val picks = listOf(0f, 25f, 55f, 120f, 175f, 200f, 270f, 300f, 330f)
        FloatArray(6) {
            if (rnd.nextFloat() < 0.7f) {
                (picks[rnd.nextInt(picks.size)] + rnd.nextFloat() * 18f - 9f + 360f) % 360f
            } else {
                rnd.nextFloat() * 360f
            }
        }
    }

    val colorShift by rememberInfiniteTransition(label = "cometHue").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 5_000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "cometHueShift"
    )

    // Continuous clockwise rotation of the pair (0 → 1 = one full lap).
    val orbit by rememberInfiniteTransition(label = "cometOrbit").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 4_200, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "cometOrbitValue"
    )

    val headPulse by rememberInfiniteTransition(label = "cometHeadPulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 900, easing = EaseInOutSine),
            RepeatMode.Reverse
        ),
        label = "cometHeadPulseValue"
    )

    val shiftedColors = remember(baseHues, colorShift) {
        List(baseHues.size) { i -> Color.hsv((baseHues[i] + colorShift) % 360f, 1.0f, 1.0f) }
    }

    // Same reuse strategy as MusicProgressRing: this ring runs a perpetual
    // orbit + hue + head-pulse animation (redraws every frame), but the
    // outline geometry only changes when the pill itself resizes. Two comets
    // need two scratch segment paths (plus one for the seam-wrap case), all
    // reused across frames instead of allocated fresh each time.
    val pathCache = remember { RingPathCache() }
    val segmentPaths = remember { arrayOf(Path(), Path()) }
    val wrapPath = remember { Path() }

    Canvas(modifier = modifier) {
        val strokePx = strokeWidth.toPx()
        val inset = strokePx / 2f
        val w = size.width - inset * 2
        val h = size.height - inset * 2
        if (w <= 0f || h <= 0f) return@Canvas

        val outerR = cornerRadius.toPx()
        val r = (outerR - inset).coerceIn(0f, min(w, h) / 2f)

        // Same top-center → clockwise rounded-rect path used by MusicProgressRing,
        // rebuilt only when the geometry actually changes.
        pathCache.ensure(w, h, r, inset, size.width)
        val measure = pathCache.measure
        val totalLen = pathCache.length
        if (totalLen <= 0f) return@Canvas

        val brush = Brush.sweepGradient(
            colors = shiftedColors + shiftedColors.first(),
            center = Offset(size.width / 2f, size.height / 2f)
        )

        val tailLen = totalLen * tailFraction.coerceIn(0.08f, 0.30f)
        val headRadiusGlow = strokePx * 1.35f
        val headRadiusCore = strokePx * 0.55f

        // Two comets, 180° apart.
        for (i in 0..1) {
            val headFrac = (orbit + i * 0.5f) % 1f
            val headDist = headFrac * totalLen
            // Tail trails behind the head (clockwise motion → tail is at smaller distance).
            val tailStart = (headDist - tailLen + totalLen) % totalLen

            val segment = segmentPaths[i].apply { reset() }
            if (tailStart < headDist) {
                measure.getSegment(tailStart, headDist, segment, startWithMoveTo = true)
            } else {
                // Wraps around the seam.
                measure.getSegment(tailStart, totalLen, segment, startWithMoveTo = true)
                val wrap = wrapPath.apply { reset() }
                measure.getSegment(0f, headDist, wrap, startWithMoveTo = true)
                segment.addPath(wrap)
            }

            // Soft glow + core stroke for the tail (same hierarchy as music ring).
            drawPath(
                path = segment,
                brush = brush,
                style = Stroke(width = strokePx * 1.55f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                alpha = 0.32f
            )
            drawPath(
                path = segment,
                brush = brush,
                style = Stroke(width = strokePx * 1.25f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                alpha = 0.38f
            )
            drawPath(
                path = segment,
                brush = brush,
                style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            // Thin white hot-core along the tail
            drawPath(
                path = segment,
                color = Color.White,
                style = Stroke(width = strokePx * 0.28f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                alpha = 0.45f
            )

            // Head pulse (identical sizing / alpha range to MusicProgressRing).
            val headPos = measure.getPosition(headDist)
            val headColor = shiftedColors[(i * 2) % shiftedColors.size]
            drawCircle(
                color = headColor,
                radius = headRadiusGlow,
                center = headPos,
                alpha = 0.40f * headPulse
            )
            drawCircle(
                color = Color.White,
                radius = headRadiusCore,
                center = headPos,
                alpha = 0.90f * headPulse
            )
        }
    }
}


/**
 * Full-perimeter RGB ring (progress = 1) used for non-music live events in
 * Compact / Expanded. Same visual language as a completed music progress ring.
 */
@Composable
fun FullRgbRing(
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 1.8.dp
) {
    // Non-music live events (hotspot, location, wifi, etc.): full perimeter
    // ring with gentle hue shift only — no rotating shimmer, so the pill edge
    // does not read as a transparent spinning propeller.
    MusicProgressRing(
        progress = 1f,
        cornerRadius = cornerRadius,
        modifier = modifier,
        strokeWidth = strokeWidth,
        isPlaying = true,
        shimmerEnabled = false
    )
}


/**
 * Two animated RGB wave strings that stretch across the Dynamic Island
 * (music live activity). Waves travel left→right with independent phase
 * and a continuously shifting neon gradient — matches the "2 garis gelombang"
 * sketch.
 *
 * When [beatPhase] is supplied (0..1, reset to 0 on every detected beat — see
 * [com.hyperisland.root.audio.AudioPulseEngine]), the wave's own travel phase
 * is *locked to the beat* instead of free-running on a fixed timer: each full
 * 0→1 beat cycle advances the wave by one full crest travel, and a detected
 * beat gives the crest a small extra "kick" (brief amplitude boost) so the
 * strings visibly flex in time with the music rather than just looping.
 * [bpm] (when > 0) additionally speeds up the ambient hue shift a little on
 * faster tracks, purely cosmetic.
 *
 * Falls back to the original free-running animation when [beatPhase] is null
 * (no capture permission / no active session / detector hasn't locked on yet).
 */
@Composable
fun MusicWaveStrings(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    amplitude: Dp = 5.dp,
    strokeWidth: Dp = 1.6.dp,
    beatPhase: Float? = null,
    bpm: Float? = null
) {
    val beatSynced = isPlaying && beatPhase != null

    // "Kick" pulse: briefly boosts amplitude right after a beat fires (i.e.
    // when beatPhase is near 0), decaying back to normal by mid-cycle — a
    // lightweight way to make the string visibly react to each hit without
    // reshaping the waveform itself.
    val beatKick = if (beatSynced) {
        val p = beatPhase ?: 0f
        (1f - (p / 0.35f).coerceIn(0f, 1f)) // 1 right at the beat, 0 by 35% into the cycle
    } else 0f
    val animatedKick by animateFloatAsState(
        targetValue = beatKick,
        animationSpec = tween(if (beatKick > 0f) 60 else 220, easing = FastOutSlowInEasing),
        label = "waveBeatKick"
    )

    // Free-running fallback phases (used when we have no beat signal).
    val freePhase by rememberInfiniteTransition(label = "wavePhase").animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            tween(durationMillis = if (isPlaying) 2200 else 8000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "wavePhaseValue"
    )
    val freePhase2 by rememberInfiniteTransition(label = "wavePhase2").animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            tween(durationMillis = if (isPlaying) 2800 else 9000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "wavePhase2Value"
    )

    // Beat-synced phase: one full crest cycle (0..2π) per beat interval,
    // interpolated smoothly between AudioPulseEngine's beatPhase samples so
    // the wave still glides rather than stepping between updates.
    val syncedPhaseTarget = (beatPhase ?: 0f) * (Math.PI * 2).toFloat()
    val syncedPhase by animateFloatAsState(
        targetValue = syncedPhaseTarget,
        animationSpec = tween(120, easing = LinearEasing),
        label = "waveSyncedPhase"
    )

    val phase = if (beatSynced) syncedPhase else freePhase
    val phase2 = if (beatSynced) syncedPhase * 0.9f + 1.2f else freePhase2

    val hueDurationMs = when {
        !isPlaying -> 7000
        bpm != null && bpm > 0f -> (60_000f / bpm * 8f).toInt().coerceIn(2500, 9000)
        else -> 7000
    }
    val hueShift by rememberInfiniteTransition(label = "waveHue").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = hueDurationMs, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "waveHueShift"
    )

    // Base hues kept as raw Float degrees — see MusicProgressRing for why
    // this avoids an RGB->HSV round trip (and its FloatArray alloc) every
    // single frame while still producing the exact same shifted colors.
    val baseHues = remember {
        val rnd = kotlin.random.Random(System.nanoTime())
        FloatArray(4) { rnd.nextFloat() * 360f }
    }
    val waveColors = remember(baseHues, hueShift) {
        List(baseHues.size) { i -> Color.hsv((baseHues[i] + hueShift) % 360f, 1.0f, 1.0f) }
    }

    // The wave's point positions genuinely change every frame (phase keeps
    // advancing), so unlike the rings above there's no static geometry to
    // cache here — but the two Path *objects* themselves don't need to be
    // reallocated 2x/frame just to hold new points. Reused via reset().
    val wavePaths = remember { arrayOf(Path(), Path()) }

    Canvas(modifier = modifier) {
        if (size.width < 4f || size.height < 4f) return@Canvas
        val baseAmp = amplitude.toPx().coerceAtMost(size.height * 0.4f)
        // Kick adds up to +35% amplitude right on the beat, settling back to
        // baseline by the next cycle.
        val amp = baseAmp * (1f + animatedKick * 0.35f)
        val stroke = strokeWidth.toPx()
        val midY = size.height / 2f
        val steps = 48
        val dx = size.width / steps

        fun drawWave(pathIndex: Int, phaseOffset: Float, yBias: Float, alpha: Float) {
            val path = wavePaths[pathIndex].apply { reset() }
            for (i in 0..steps) {
                val x = i * dx
                val t = (x / size.width) * (Math.PI * 3).toFloat() + phaseOffset
                val y = midY + yBias + kotlin.math.sin(t.toDouble()).toFloat() * amp *
                    (if (isPlaying) 1f else 0.35f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val brush = Brush.horizontalGradient(waveColors)
            drawPath(
                path = path,
                brush = brush,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                alpha = alpha
            )
        }

        // Two independent strings with slight vertical offset
        drawWave(0, phase, -amp * 0.35f, 0.85f)
        drawWave(1, phase2 + 1.2f, amp * 0.35f, 0.75f)
    }
}

