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
import com.hyperisland.root.root.RingerMode
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Minimal (idle capsule) and Compact (summary) content for the status-bar pill.
 * Called from [IslandContentView]; Expanded is handled separately in IslandExpanded.
 */
// ─────────────────────────────────────────────────────────────────────────
// Minimal — idle capsule / camera cover
// ─────────────────────────────────────────────────────────────────────────

@Composable
internal fun MinimalContent(availableWidth: Dp, availableHeight: Dp) {
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
internal fun CompactContent(
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

    // Entire compact pill is tappable → expand (if allowed for this content type).
    val rowInteractionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = hPad)
            .clickable(
                interactionSource = rowInteractionSource,
                indication = null
            ) { onExpand() }
            .pressScale(rowInteractionSource),
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
            else -> NonMediaCompact(
                content = content,
                iconSize = iconSize,
                isTight = isTight,
                isTiny = isTiny,
                showSubtitle = showSubtitle
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Interrupted compact: primary live-event + circular badge for secondary
// Overall size stays identical to a normal compact pill (no extra width).
// ─────────────────────────────────────────────────────────────────────────

@Composable
internal fun InterruptedCompact(
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
        // Higher stiffness + damping: quick settle, less elastic overshoot that
        // used to look like stutter when primary content was also resizing.
        badgeAppear.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.78f, stiffness = 900f)
        )
    }

    val interruptedRowInteractionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = hPad, end = (hPad.value * 0.45f).dp)
            .clickable(
                interactionSource = interruptedRowInteractionSource,
                indication = null
            ) { onExpand() }
            .pressScale(interruptedRowInteractionSource),
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
                    (fadeIn(tween(100, easing = FastOutSlowInEasing)) +
                        scaleIn(
                            initialScale = 0.55f,
                            animationSpec = spring(dampingRatio = 0.78f, stiffness = 900f)
                        )) togetherWith
                        (fadeOut(tween(70)) +
                            scaleOut(
                                targetScale = 0.7f,
                                animationSpec = tween(70, easing = FastOutSlowInEasing)
                            ))
                ).using(SizeTransform(clip = false))
            },
            label = "interruptionBadge"
        ) { sec ->
            Box(
                modifier = Modifier.graphicsLayer {
                    // Mild rotation only while appearing; settle quickly with the
                    // stiffer badgeAppear spring above.
                    rotationZ = (1f - badgeAppear.value) * -10f
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
internal fun LaunchedEffectOnce(key: Any?, block: suspend () -> Unit) {
    LaunchedEffect(key) { block() }
}

/** Circular badge for any secondary (interrupted) event. */
@Composable
internal fun CircularEventBadge(
    content: IslandContent,
    size: Dp
) {
    val layout by IslandPreferences.config.collectAsState()
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        when (content) {
            is IslandContent.Media -> {
                if (layout.animRingMusicProgress) {
                    val progress = if (content.durationMs > 0) {
                        (content.positionMs.toFloat() / content.durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    val animatedProgress by animateFloatAsState(
                        targetValue = progress,
                        animationSpec = tween(280, easing = LinearEasing),
                        label = "sideMediaProgress"
                    )
                    MusicProgressRing(
                        progress = animatedProgress,
                        cornerRadius = size / 2,
                        isPlaying = content.isPlaying,
                        strokeWidth = 1.5.dp,
                        modifier = Modifier.fillMaxSize()
                    )
                }
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
internal fun SecondaryEventIcon(content: IslandContent, size: Dp) {
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
                        Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(size))
                }
            }
            return
        }
        is IslandContent.Wifi -> {
            Icon(
                if (content.enabled) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                null,
                tint = if (content.enabled) Color(0xFF60A5FA) else Color.White.copy(0.6f),
                modifier = Modifier.size(size)
            )
            return
        }
        is IslandContent.CellularData -> {
            Icon(
                if (content.enabled) Icons.Rounded.SignalCellularAlt else Icons.Rounded.SignalCellularOff,
                null,
                tint = if (content.enabled) Color(0xFF34D399) else Color.White.copy(0.6f),
                modifier = Modifier.size(size)
            )
            return
        }
        is IslandContent.Location -> {
            if (content.enabled) {
                LocationPulseIcon(size = size)
            } else {
                Icon(
                    Icons.Rounded.LocationOff,
                    null,
                    tint = Color.White.copy(0.6f),
                    modifier = Modifier.size(size)
                )
            }
            return
        }
        else -> {}
    }
    val (icon, tint) = when (content) {
        is IslandContent.Charging -> Icons.Rounded.BatteryChargingFull to Color(0xFF4ADE80)
        is IslandContent.Custom -> Icons.Rounded.Info to Color.White
        is IslandContent.Media -> Icons.Rounded.MusicNote to Color.White
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

@Composable
internal fun RowScope.CompactMedia(
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
                    // Prefer live lyric line from LRCLIB when available
                    val subtitle = content.lyricsLine?.takeIf { it.isNotBlank() }
                        ?: content.artist
                    Text(
                        subtitle,
                        color = Color.White.copy(0.7f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee()
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
internal fun RowScope.NonMediaCompact(
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
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        "${content.level}%",
                        color = Color.White,
                        fontSize = if (isTight) 12.sp else 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    if (showSubtitle && !isTight) {
                        Text(
                            if (content.isCharging) {
                                if (content.currentMa != 0) "${content.currentMa}mA" else "Charging"
                            } else "Not charging",
                            color = Color.White.copy(0.7f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        is IslandContent.Ringer -> {
            when (content.mode) {
                RingerMode.VIBRATE -> VibrationShakeIcon(size = iconSize, color = Color.White)
                RingerMode.DND -> DndPulseIcon(size = iconSize, color = Color(0xFFF87171))
                else -> {
                    val icon = when (content.mode) {
                        RingerMode.NORMAL -> Icons.AutoMirrored.Rounded.VolumeUp
                        RingerMode.SILENT -> Icons.AutoMirrored.Rounded.VolumeOff
                        RingerMode.VIBRATE, RingerMode.DND -> Icons.AutoMirrored.Rounded.VolumeUp
                    }
                    AnimatedContent(
                        targetState = icon,
                        transitionSpec = {
                            (scaleIn(
                                initialScale = 0.85f,
                                animationSpec = spring(dampingRatio = 0.85f, stiffness = 1000f)
                            ) + fadeIn(tween(80)))
                                .togetherWith(fadeOut(tween(60)))
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
        is IslandContent.Wifi -> {
            Icon(
                if (content.enabled) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                null,
                tint = if (content.enabled) Color(0xFF60A5FA) else Color.White.copy(0.6f),
                modifier = Modifier.size(iconSize)
            )
            if (!isTiny) {
                Text(
                    content.ssid ?: if (content.enabled) "Wi-Fi" else "Off",
                    color = Color.White,
                    fontSize = if (isTight) 11.sp else 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        is IslandContent.CellularData -> {
            Icon(
                if (content.enabled) Icons.Rounded.SignalCellularAlt else Icons.Rounded.SignalCellularOff,
                null,
                tint = if (content.enabled) Color(0xFF34D399) else Color.White.copy(0.6f),
                modifier = Modifier.size(iconSize)
            )
            if (!isTiny) {
                Text(
                    content.networkType ?: if (content.enabled) "Data" else "Off",
                    color = Color.White,
                    fontSize = if (isTight) 11.sp else 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        is IslandContent.Location -> {
            if (content.enabled) {
                LocationPulseIcon(size = iconSize)
            } else {
                Icon(
                    Icons.Rounded.LocationOff,
                    null,
                    tint = Color.White.copy(0.6f),
                    modifier = Modifier.size(iconSize)
                )
            }
            if (!isTiny) {
                Text(
                    if (content.enabled) "Lokasi" else "Off",
                    color = Color.White,
                    fontSize = if (isTight) 11.sp else 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
