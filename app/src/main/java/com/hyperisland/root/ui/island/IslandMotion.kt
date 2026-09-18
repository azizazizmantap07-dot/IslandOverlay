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
 * Core motion primitives: equalizer, pulses, radar, progress track, live dot, press scale.
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
 * Lightweight press-feedback modifier: a quick, subtle scale-down while the
 * finger is down, springing back on release — the same "physical" motion
 * language as [BellRingIcon]'s pop and the interruption badge's squeeze,
 * instead of Compose's default ripple.
 *
 * Every tappable surface in the Island (tap-to-expand, quick-toggle chips,
 * media transport buttons) currently sets `indication = null` with nothing
 * in its place, so taps have zero visual acknowledgement — this fills that
 * gap without pulling in Material's ripple (which draws a spreading circle +
 * state-layer overlay, more per-tap draw work than this device class needs
 * for something this small, and visually louder than the rest of the UI).
 *
 * Deliberately track press state via [interactionSource] rather than a
 * second `pointerInput`, so this composes cleanly with an existing
 * `clickable(interactionSource = ..., indication = null, onClick = ...)`
 * — just pass the same [interactionSource] to both.
 *
 * [pressedScale] is intentionally close to 1f (default 4% shrink): big
 * enough to read as a tap acknowledgment, small enough not to jostle
 * neighboring content or make an already-tiny compact pill feel wobbly.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f
): Modifier {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(interactionSource) {
        var pressCount = 0
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    pressCount++
                    // Fast, slightly firm settle — reads as an immediate
                    // "got it" rather than a squishy bounce.
                    scale.animateTo(pressedScale, spring(dampingRatio = 0.7f, stiffness = 1200f))
                }
                is PressInteraction.Release,
                is PressInteraction.Cancel -> {
                    pressCount = (pressCount - 1).coerceAtLeast(0)
                    if (pressCount == 0) {
                        scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 700f))
                    }
                }
            }
        }
    }
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
