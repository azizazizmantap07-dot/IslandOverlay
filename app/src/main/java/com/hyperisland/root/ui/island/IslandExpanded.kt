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
                    .background(Color(0xF2000000))
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
            // Progress ring for media; full RGB for everything else.
            if (content is IslandContent.Media && content.durationMs > 0) {
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
            } else {
                FullRgbRing(
                    cornerRadius = corner,
                    strokeWidth = 1.8.dp,
                    modifier = Modifier.matchParentSize()
                )
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

