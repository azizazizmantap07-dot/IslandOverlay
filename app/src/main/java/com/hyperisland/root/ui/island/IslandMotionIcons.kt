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
 * Animated status glyphs: battery, bell, bluetooth, hotspot, vibrate, DND, flashlight, location.
 */
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
        // Quick attention pop — settle fast so it doesn't drag the whole pill
        pop.snapTo(0.82f)
        pop.animateTo(
            1f,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = 900f)
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



/**
 * Location glyph: soft radar-style pulse rings expanding from the pin,
 * conveying "location services are actively reporting".
 */
@Composable
fun LocationPulseIcon(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    color: Color = Color(0xFF60A5FA)
) {
    val transition = rememberInfiniteTransition(label = "locationPulse")
    val progress by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "locationProgress"
    )
    val glow by transition.animateFloat(
        0.75f, 1f,
        infiniteRepeatable(tween(1200, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "locationGlow"
    )

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val maxRadius = this.size.minDimension / 2f
            // Two expanding rings with staggered phase
            for (ring in 0 until 2) {
                val local = ((progress + ring * 0.5f) % 1f)
                val radius = maxRadius * (0.35f + local * 0.6f)
                val alpha = (1f - local).coerceIn(0f, 1f) * 0.55f
                drawCircle(
                    color = color.copy(alpha = alpha),
                    radius = radius,
                    style = Stroke(width = 1.5f)
                )
            }
        }
        Icon(
            Icons.Rounded.LocationOn,
            contentDescription = null,
            tint = color.copy(alpha = glow),
            modifier = Modifier.size(size * 0.72f)
        )
    }
}

