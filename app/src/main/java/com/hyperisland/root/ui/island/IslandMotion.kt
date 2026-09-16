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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Small, reusable "alive" motion primitives shared by Compact/Expanded content.
 * Kept intentionally understated (iOS/HyperOS style: subtle, not flashy) and
 * always driven off the actual size given to them, never a hardcoded value,
 * so they scale down gracefully instead of overflowing a tiny custom pill.
 */

/**
 * Animated 3-bar music equalizer, like the Now Playing glyph on iOS/HyperOS.
 *
 * When [realAmplitude] is non-null (live 0..1 loudness from
 * [com.hyperisland.root.audio.AudioPulseEngine]), the bars are driven by the
 * actual audio signal instead of a canned loop: each bar reacts to the same
 * amplitude with a slightly different response curve/lag so they still read
 * as three independent bars rather than one block moving in unison, but the
 * *source* of motion is real playback loudness, not a timer.
 *
 * When [realAmplitude] is null (no RECORD_AUDIO / no capturable session),
 * falls back to the original generic 3-phase loop so the glyph still looks
 * alive.
 */
@Composable
fun EqualizerBars(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barWidth: Dp = 2.4.dp,
    maxHeight: Dp = 12.dp,
    color: Color = Color.White,
    realAmplitude: Float? = null
) {
    val phases: List<Float> = if (realAmplitude != null && isPlaying) {
        // Per-bar response: slightly different gain + attack/decay smoothing
        // so bars don't move as one rigid unit even though they share a
        // single amplitude source. Smoothing is animateFloatAsState-based so
        // Compose interpolates between successive real amplitude samples
        // instead of snapping, which is what keeps this looking musical
        // rather than jittery at ~20-40 update/sec.
        val bar1 by animateFloatAsState(
            targetValue = (realAmplitude * 1.15f).coerceIn(0f, 1f),
            animationSpec = tween(90, easing = LinearEasing),
            label = "eqReal1"
        )
        val bar2 by animateFloatAsState(
            targetValue = (realAmplitude * 0.85f + 0.05f).coerceIn(0f, 1f),
            animationSpec = tween(140, easing = LinearEasing),
            label = "eqReal2"
        )
        val bar3 by animateFloatAsState(
            targetValue = (realAmplitude * 1.05f).coerceIn(0f, 1f),
            animationSpec = tween(110, easing = LinearEasing),
            label = "eqReal3"
        )
        listOf(bar1, bar2, bar3)
    } else {
        val transition = rememberInfiniteTransition(label = "eq")
        // Three independent phases so the bars don't move in lockstep.
        val phase1 by transition.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(480, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "eq1"
        )
        val phase2 by transition.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(620, delayMillis = 90, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "eq2"
        )
        val phase3 by transition.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(390, delayMillis = 160, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "eq3"
        )
        if (isPlaying) listOf(phase1, phase2, phase3) else listOf(0.18f, 0.18f, 0.18f)
    }

    Row(
        modifier = modifier.height(maxHeight),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        phases.forEach { p ->
            val h = (maxHeight * (0.25f + p.coerceIn(0f, 1f) * 0.75f))
            Box(
                Modifier
                    .width(barWidth)
                    .height(h)
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (isPlaying) 0.95f else 0.5f))
            )
        }
    }
}

/** Slow "breathing" alpha pulse used for idle/minimal state so it reads as alive, not dead. */
@Composable
fun rememberBreathingAlpha(minAlpha: Float = 0.18f, maxAlpha: Float = 0.45f): Float {
    val transition = rememberInfiniteTransition(label = "breathe")
    val alpha by transition.animateFloat(
        minAlpha, maxAlpha,
        infiniteRepeatable(tween(1400, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "breatheAlpha"
    )
    return alpha
}

/** Gentle looping scale pulse, e.g. for a charging bolt or an active status glyph. */
@Composable
fun rememberPulseScale(min: Float = 0.94f, max: Float = 1.08f, periodMs: Int = 900): Float {
    val transition = rememberInfiniteTransition(label = "pulseScale")
    val scale by transition.animateFloat(
        min, max,
        infiniteRepeatable(tween(periodMs, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "pulseScaleValue"
    )
    return scale
}

/** Outward-expanding "radar ping" ring, used for Bluetooth/Hotspot "searching/connected" cues. */
@Composable
fun RadarPing(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF60A5FA),
    size: Dp = 22.dp
) {
    val transition = rememberInfiniteTransition(label = "radar")
    val progress by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "radarProgress"
    )
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val maxRadius = min(this.size.width, this.size.height) / 2f
            val radius = maxRadius * progress
            val alpha = (1f - progress).coerceIn(0f, 1f)
            drawCircle(
                color = color.copy(alpha = alpha * 0.55f),
                radius = radius,
                style = Stroke(width = 1.6f)
            )
        }
        Box(
            Modifier
                .size(size * 0.32f)
                .clip(CircleShape)
                .background(color)
        )
    }
}

/** Animated battery glyph: fill level morphs smoothly and a bolt fades in while charging. */
@Composable
fun AnimatedBatteryGlyph(
    level: Int,
    isCharging: Boolean,
    modifier: Modifier = Modifier,
    width: Dp = 22.dp,
    height: Dp = 11.dp
) {
    val targetFraction = (level / 100f).coerceIn(0f, 1f)
    val transition = rememberInfiniteTransition(label = "battery")
    val chargeShimmer by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "batteryShimmer"
    )
    val fillColor = when {
        isCharging -> Color(0xFF4ADE80)
        level <= 15 -> Color(0xFFF87171)
        level <= 30 -> Color(0xFFFBBF24)
        else -> Color.White
    }
    Canvas(modifier.width(width).height(height)) {
        // All maths below happens in real pixels (DrawScope, not dp), so guard every
        // subtraction against a canvas smaller than the fixed insets — otherwise a
        // very small custom pill size could produce a negative width/size and crash
        // with an IllegalArgumentException instead of just rendering a tiny battery.
        val capWidth = (this.size.height * 0.18f).coerceAtMost(this.size.width * 0.3f)
        val bodyWidth = (this.size.width - capWidth).coerceAtLeast(0f)
        val corner = (this.size.height * 0.28f).coerceAtMost(bodyWidth / 2f).coerceAtLeast(0f)

        // Outline
        drawRoundRect(
            color = Color.White.copy(alpha = 0.55f),
            size = Size(bodyWidth, this.size.height),
            cornerRadius = CornerRadius(corner, corner),
            style = Stroke(width = 1.4f)
        )
        // Cap
        drawRoundRect(
            color = Color.White.copy(alpha = 0.55f),
            topLeft = Offset(bodyWidth + 1.5f, this.size.height * 0.28f),
            size = Size(capWidth, (this.size.height * 0.44f).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(1.5f, 1.5f)
        )
        // Fill
        val inset = (2f).coerceAtMost(minOf(bodyWidth, this.size.height) / 4f)
        val fillMaxWidth = (bodyWidth - inset * 2).coerceAtLeast(0f)
        val fillWidth = max(0f, fillMaxWidth * targetFraction)
        val shimmerAlpha = if (isCharging) 0.85f + chargeShimmer * 0.15f else 1f
        drawRoundRect(
            color = fillColor.copy(alpha = shimmerAlpha),
            topLeft = Offset(inset, inset),
            size = Size(fillWidth, (this.size.height - inset * 2).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(corner * 0.6f, corner * 0.6f)
        )
    }
}

/** Thin animated progress track (media playback position, charging-to-full estimate, etc). */
@Composable
fun AnimatedProgressTrack(
    progress: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = Color.White.copy(alpha = 0.22f),
    fillBrush: Brush = Brush.horizontalGradient(
        listOf(Color.White.copy(alpha = 0.85f), Color.White)
    )
) {
    val clamped = progress.coerceIn(0f, 1f)
    Canvas(modifier.height(3.dp)) {
        val corner = this.size.height / 2f
        drawRoundRect(
            color = trackColor,
            cornerRadius = CornerRadius(corner, corner)
        )
        if (clamped > 0f) {
            drawRoundRect(
                brush = fillBrush,
                size = Size(this.size.width * clamped, this.size.height),
                cornerRadius = CornerRadius(corner, corner)
            )
        }
    }
}

/**
 * Animated "ringing bell" notification glyph.
 *
 * Plays a quick, springy wiggle (rotate + tiny scale pop) the moment a
 * notification arrives, then settles into a slow idle sway so the Island
 * keeps reading as alive rather than a static icon. Purely a notification
 * affordance — unrelated to and does not affect the Media/music visuals
 * (EqualizerBars / MusicWaveStrings / MusicProgressRing).
 *
 * [ringTrigger] should change value (e.g. an incrementing counter) every time
 * a *new* notification arrives, so repeated notifications from the same
 * event re-trigger the wiggle instead of only playing once on first compose.
 */
@Composable
fun BellRingIcon(
    ringTrigger: Any,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    color: Color = Color.White
) {
    val angle = remember { Animatable(0f) }
    val pop = remember { Animatable(1f) }

    LaunchedEffect(ringTrigger) {
        // Quick attention pop
        pop.snapTo(0.7f)
        pop.animateTo(
            1f,
            animationSpec = spring(dampingRatio = 0.35f, stiffness = 500f)
        )
    }

    LaunchedEffect(ringTrigger) {
        // Bell-clapper swing: a few decaying oscillations then rest at 0,
        // like a real bell settling after being struck.
        angle.snapTo(0f)
        val swings = listOf(16f, -12f, 8f, -5f, 3f, -1.5f, 0f)
        for (target in swings) {
            angle.animateTo(
                target,
                animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing)
            )
        }
    }

    // Gentle perpetual idle sway so the bell never reads as a dead/static
    // glyph between notifications — subtle enough not to compete with the
    // ring animation above when it fires.
    val idleSway by rememberInfiniteTransition(label = "bellIdle").animateFloat(
        initialValue = -2.2f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(
            tween(1600, easing = EaseInOutSine),
            RepeatMode.Reverse
        ),
        label = "bellIdleSway"
    )

    val currentAngle = angle.value + idleSway * (1f - (kotlin.math.abs(angle.value) / 16f).coerceIn(0f, 1f))

    Icon(
        Icons.Rounded.Notifications,
        contentDescription = null,
        tint = color,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                rotationZ = currentAngle
                scaleX = pop.value
                scaleY = pop.value
                transformOrigin = TransformOrigin(0.5f, 0.15f) // pivot near the bell's hanger, not its center
            }
    )
}

/**
 * Bluetooth glyph with a "pairing lock-in" pulse: a ring expands outward from
 * the icon and fades as it grows — reads as a connection handshake completing,
 * distinct from the ripple-style pulse this replaces. Settles into a slow
 * idle breathing glow so an already-connected device still feels alive.
 */
@Composable
fun BluetoothConnectIcon(
    connectTrigger: Any,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    color: Color = Color(0xFF60A5FA)
) {
    val ringProgress = remember { Animatable(1f) } // 1f = fully collapsed/invisible
    LaunchedEffect(connectTrigger) {
        ringProgress.snapTo(0f)
        ringProgress.animateTo(
            1f,
            animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing)
        )
    }

    val idleGlow by rememberInfiniteTransition(label = "btGlow").animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "btGlowValue"
    )

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (ringProgress.value < 1f) {
            Canvas(Modifier.size(size)) {
                val maxRadius = this.size.minDimension / 2f
                val radius = maxRadius * (0.55f + ringProgress.value * 0.45f)
                val alpha = (1f - ringProgress.value).coerceIn(0f, 1f)
                drawCircle(
                    color = color.copy(alpha = alpha * 0.6f),
                    radius = radius,
                    style = Stroke(width = 1.6f)
                )
            }
        }
        Icon(
            Icons.Rounded.Bluetooth,
            contentDescription = null,
            tint = color.copy(alpha = idleGlow),
            modifier = Modifier.size(size * 0.82f)
        )
    }
}

/**
 * Hotspot glyph: concentric signal arcs light up outward in sequence, like a
 * router broadcasting — visually distinct from Bluetooth's inward pairing
 * pulse even though both represent "a radio is on".
 */
@Composable
fun HotspotBroadcastIcon(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    color: Color = Color(0xFF34D399)
) {
    val transition = rememberInfiniteTransition(label = "hotspotBroadcast")
    val progress by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "hotspotProgress"
    )
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val maxRadius = this.size.minDimension / 2f
            // Three arcs, each lighting up in its own window of the cycle so
            // the "broadcast" visually travels from center outward on loop.
            val ringCount = 3
            for (ring in 0 until ringCount) {
                val windowStart = ring / ringCount.toFloat()
                val local = ((progress - windowStart) % 1f + 1f) % 1f
                val alpha = if (local <= 0.5f) {
                    (1f - (local / 0.5f)).coerceIn(0f, 1f) * 0.7f
                } else 0f
                if (alpha > 0f) {
                    val radius = maxRadius * (0.42f + ring * 0.29f)
                    drawArc(
                        color = color.copy(alpha = alpha),
                        startAngle = -55f,
                        sweepAngle = 110f,
                        useCenter = false,
                        topLeft = Offset(this.size.width / 2f - radius, this.size.height / 2f - radius),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(width = 1.6f, cap = StrokeCap.Round)
                    )
                }
            }
        }
        Icon(
            Icons.Rounded.WifiTethering,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(size * 0.6f)
        )
    }
}

/** Vibrate-mode glyph that actually shakes side to side, like a phone buzzing on a table. */
@Composable
fun VibrationShakeIcon(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    color: Color = Color.White
) {
    val transition = rememberInfiniteTransition(label = "vibrateShake")
    val shake by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "vibrateShakeValue"
    )
    // Short burst of jitter, then a pause — mirrors a real phone's buzz-buzz-rest pattern
    // rather than a nonstop wobble, so it reads as "vibrating" and not "broken".
    val burstWindow = 0.32f
    val local = (shake % 1f).coerceIn(0f, 1f)
    val offsetX = if (local < burstWindow) {
        val t = local / burstWindow
        (sin(t * Math.PI * 8).toFloat()) * (1f - t) * 2.2f
    } else 0f

    Icon(
        Icons.Rounded.Vibration,
        contentDescription = null,
        tint = color,
        modifier = modifier
            .size(size)
            .graphicsLayer { translationX = offsetX }
    )
}

/**
 * DND glyph with a slow, deliberate "focus mode" breathing pulse — a soft red
 * halo that expands and fades, distinct from Vibrate's sharp shake, signaling
 * calm suppression rather than an active alert.
 */
@Composable
fun DndPulseIcon(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    color: Color = Color(0xFFF87171)
) {
    val transition = rememberInfiniteTransition(label = "dndPulse")
    val pulse by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2000, easing = EaseInOutSine), RepeatMode.Restart),
        label = "dndPulseValue"
    )
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val maxRadius = this.size.minDimension / 2f
            val radius = maxRadius * (0.75f + pulse * 0.35f)
            val alpha = (1f - pulse).coerceIn(0f, 1f) * 0.45f
            drawCircle(color = color.copy(alpha = alpha), radius = radius)
        }
        Icon(
            Icons.Rounded.DoNotDisturb,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(size * 0.82f)
        )
    }
}

/**
 * Flashlight glyph. While on: a warm radial glow breathes behind the icon and
 * a few short "rays" flicker at slightly independent phases, like a real bulb.
 * While off: a brief one-shot dim flicker plays so switching off doesn't feel
 * like content just vanished.
 */
@Composable
fun FlashlightGlowIcon(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    onColor: Color = Color(0xFFFBBF24),
    offColor: Color = Color.White
) {
    val transition = rememberInfiniteTransition(label = "torchGlow")
    val glow by transition.animateFloat(
        0.75f, 1f,
        infiniteRepeatable(tween(900, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "torchGlowValue"
    )
    val rayFlicker by transition.animateFloat(
        0.5f, 1f,
        infiniteRepeatable(tween(260, easing = LinearEasing), RepeatMode.Reverse),
        label = "torchRayFlicker"
    )

    val offFlash = remember { Animatable(0f) }
    LaunchedEffect(enabled) {
        if (!enabled) {
            offFlash.snapTo(1f)
            offFlash.animateTo(0f, animationSpec = tween(350, easing = FastOutSlowInEasing))
        }
    }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (enabled) {
            Canvas(Modifier.size(size)) {
                val maxRadius = this.size.minDimension / 2f
                // Soft glow halo
                drawCircle(
                    color = onColor.copy(alpha = 0.22f * glow),
                    radius = maxRadius * 0.95f
                )
                // Four short rays at fixed angles, brightness flickering independently
                val rayCount = 4
                for (i in 0 until rayCount) {
                    val angle = (i * (360f / rayCount) + 45f) * (Math.PI / 180.0)
                    val innerR = maxRadius * 0.62f
                    val outerR = maxRadius * (0.82f + 0.1f * (i % 2))
                    val cx = this.size.width / 2f
                    val cy = this.size.height / 2f
                    val rayAlpha = (rayFlicker * (if (i % 2 == 0) 1f else 0.7f)).coerceIn(0f, 1f)
                    drawLine(
                        color = onColor.copy(alpha = rayAlpha * 0.8f),
                        start = Offset(cx + (cos(angle) * innerR).toFloat(), cy + (sin(angle) * innerR).toFloat()),
                        end = Offset(cx + (cos(angle) * outerR).toFloat(), cy + (sin(angle) * outerR).toFloat()),
                        strokeWidth = 1.4f,
                        cap = StrokeCap.Round
                    )
                }
            }
        } else if (offFlash.value > 0f) {
            Canvas(Modifier.size(size)) {
                drawCircle(
                    color = onColor.copy(alpha = 0.3f * offFlash.value),
                    radius = this.size.minDimension / 2f * 0.9f
                )
            }
        }
        Icon(
            if (enabled) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
            contentDescription = null,
            tint = if (enabled) onColor.copy(alpha = glow) else offColor,
            modifier = Modifier.size(size * 0.82f)
        )
    }
}


@Composable
fun LiveDot(color: Color, modifier: Modifier = Modifier, size: Dp = 6.dp) {
    val scale = rememberPulseScale(min = 0.85f, max = 1.15f, periodMs = 1000)
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(color)
    )
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
    strokeWidth: Dp = 3.0.dp,
    isPlaying: Boolean = true
) {
    val clamped = progress.coerceIn(0f, 1f)
    if (clamped <= 0f && !isPlaying) return

    // Ultra-bright neon palette — pure sat + full value, biased to primaries.
    val colors = remember {
        val rnd = kotlin.random.Random(System.currentTimeMillis())
        val baseHues = listOf(0f, 25f, 55f, 120f, 175f, 200f, 270f, 300f, 330f)
        List(6) {
            val h = if (rnd.nextFloat() < 0.7f) {
                baseHues[rnd.nextInt(baseHues.size)] + rnd.nextFloat() * 18f - 9f
            } else {
                rnd.nextFloat() * 360f
            }
            Color.hsv((h + 360f) % 360f, 1.0f, 1.0f)
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

    // Head glow: a slow breathing pulse riding on the progress "dot" itself,
    // so the tip of the bar reads as a live, alive cue rather than a static end-cap.
    val headPulse by rememberInfiniteTransition(label = "ringHeadPulse").animateFloat(
        initialValue = 0.75f,
        targetValue = 1f,
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

    val shiftedColors = remember(colors, colorShift) {
        colors.map { c ->
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(c.toArgb(), hsv)
            hsv[0] = (hsv[0] + colorShift) % 360f
            Color.hsv(hsv[0], 1.0f, 1.0f)
        }
    }

    Canvas(modifier = modifier) {
        val strokePx = strokeWidth.toPx()
        // Inset so the stroke sits centered on the visual edge of the pill
        // (half inside / half outside). Prevents the ring from being clipped
        // by the parent's clip(shape) when drawn as an overlay.
        val inset = strokePx / 2f
        val w = size.width - inset * 2
        val h = size.height - inset * 2
        if (w <= 0f || h <= 0f) return@Canvas

        val r = cornerRadius.toPx().coerceAtMost(min(w, h) / 2f)

        // Build a closed rounded-rect path that *starts at top-center* and
        // travels clockwise. PathMeasure then lets us stroke only the first
        // `progress` fraction of the perimeter.
        val path = Path().apply {
            val left = inset
            val top = inset
            val right = inset + w
            val bottom = inset + h
            val cx = size.width / 2f

            // Start: top-center
            moveTo(cx, top)

            if (r < 0.5f) {
                // Degenerate to plain rect (no arcs) for tiny pills
                lineTo(right, top)
                lineTo(right, bottom)
                lineTo(left, bottom)
                lineTo(left, top)
                lineTo(cx, top)
            } else {
                // Top-right straight → top-right arc
                lineTo(right - r, top)
                arcTo(
                    rect = Rect(right - 2 * r, top, right, top + 2 * r),
                    startAngleDegrees = -90f,
                    sweepAngleDegrees = 90f,
                    forceMoveTo = false
                )
                // Right side → bottom-right arc
                lineTo(right, bottom - r)
                arcTo(
                    rect = Rect(right - 2 * r, bottom - 2 * r, right, bottom),
                    startAngleDegrees = 0f,
                    sweepAngleDegrees = 90f,
                    forceMoveTo = false
                )
                // Bottom side → bottom-left arc
                lineTo(left + r, bottom)
                arcTo(
                    rect = Rect(left, bottom - 2 * r, left + 2 * r, bottom),
                    startAngleDegrees = 90f,
                    sweepAngleDegrees = 90f,
                    forceMoveTo = false
                )
                // Left side → top-left arc
                lineTo(left, top + r)
                arcTo(
                    rect = Rect(left, top, left + 2 * r, top + 2 * r),
                    startAngleDegrees = 180f,
                    sweepAngleDegrees = 90f,
                    forceMoveTo = false
                )
                // Close back to top-center
                lineTo(cx, top)
            }
            close()
        }

        val measure = PathMeasure()
        measure.setPath(path, forceClosed = false)
        val totalLen = measure.length
        if (totalLen <= 0f) return@Canvas

        val drawLen = totalLen * clamped
        val segment = Path()
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

        // The filled segment itself uses a Butt cap at BOTH ends: the tail
        // (top-center, where progress always starts) must stay flush with the
        // track underneath it instead of poking out as a rounded nub, and the
        // head gets its own dedicated round dot (below) so its roundness is
        // consistent and independently tunable instead of inherited from the
        // stroke cap.
        val segStroke = Stroke(width = strokePx, cap = StrokeCap.Butt, join = StrokeJoin.Round)
        val glowStroke = Stroke(width = strokePx * 2.6f, cap = StrokeCap.Butt, join = StrokeJoin.Round)

        // Outer glow (wider, brighter alpha than before) so the ring reads at
        // least as bright as the wave strings behind the title text.
        drawPath(path = segment, brush = brush, style = glowStroke, alpha = 0.6f)
        // Secondary tight glow pass — narrows the falloff so the core reads
        // "hot" instead of just smeared.
        drawPath(
            path = segment,
            brush = brush,
            style = Stroke(width = strokePx * 1.6f, cap = StrokeCap.Butt, join = StrokeJoin.Round),
            alpha = 0.55f
        )
        // Core bright stroke
        drawPath(path = segment, brush = brush, style = segStroke)
        // Traveling shimmer highlight blended on top of the core: a narrow
        // bright-then-transparent sweep gradient continuously rotated by
        // [shimmerPhase], so a glint of light appears to travel around the
        // filled arc independent of playback position — a small "energy
        // flowing through the ring" cue.
        rotate(degrees = shimmerPhase, pivot = Offset(size.width / 2f, size.height / 2f)) {
            drawPath(
                path = segment,
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Transparent,
                        Color.White.copy(alpha = 0.9f),
                        Color.Transparent,
                        Color.Transparent
                    ),
                    center = Offset(size.width / 2f, size.height / 2f)
                ),
                style = Stroke(width = strokePx * 0.9f, cap = StrokeCap.Butt, join = StrokeJoin.Round),
                alpha = 0.6f
            )
        }
        // Hairline white hot-core down the middle of the stroke — the classic
        // "neon tube" trick: a thin near-white line inside a saturated color
        // reads dramatically brighter than the flat color alone.
        drawPath(
            path = segment,
            color = Color.White,
            style = Stroke(width = strokePx * 0.32f, cap = StrokeCap.Butt, join = StrokeJoin.Round),
            alpha = 0.55f
        )

        // Head dot: an explicit round glow at the current progress tip so the
        // "leading edge" of the bar has a clean, deliberate rounded end (the
        // only rounded thing in the whole ring) and gently pulses to stay legible.
        if (clamped > 0.001f) {
            val pos = measure.getPosition(drawLen)
            val headColor = shiftedColors.last()
            drawCircle(
                color = headColor,
                radius = strokePx * 1.35f * headPulse,
                center = pos,
                alpha = 0.45f
            )
            drawCircle(
                color = Color.White,
                radius = strokePx * 0.55f * headPulse,
                center = pos,
                alpha = 0.9f
            )
        }
    }
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
        val p = beatPhase!!
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

    val baseColors = remember {
        val rnd = kotlin.random.Random(System.nanoTime())
        List(4) { Color.hsv(rnd.nextFloat() * 360f, 1.0f, 1.0f) }
    }
    val waveColors = remember(baseColors, hueShift) {
        baseColors.map { c ->
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(c.toArgb(), hsv)
            hsv[0] = (hsv[0] + hueShift) % 360f
            Color.hsv(hsv[0], 1.0f, 1.0f)
        }
    }

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

        fun drawWave(phaseOffset: Float, yBias: Float, alpha: Float) {
            val path = Path()
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
        drawWave(phase, -amp * 0.35f, 0.85f)
        drawWave(phase2 + 1.2f, amp * 0.35f, 0.75f)
    }
}
