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
 * Expanded controls for system toggles: ringer, generic on/off, Wi-Fi, cellular.
 */
@Composable
internal fun ExpandedRingerControls(
    content: IslandContent.Ringer,
    isCramped: Boolean,
    availableHeight: Dp,
    onAction: (String) -> Unit
) {
    val modes = listOf(
        RingerMode.NORMAL to ("Ring" to Icons.Rounded.NotificationsActive),
        RingerMode.VIBRATE to ("Vibrate" to Icons.Rounded.Vibration),
        RingerMode.SILENT to ("Silent" to Icons.Rounded.NotificationsOff),
        RingerMode.DND to ("DND" to Icons.Rounded.DoNotDisturb)
    )
    val fill = expandFill(availableHeight)
    val icon = when (content.mode) {
        RingerMode.NORMAL -> Icons.Rounded.NotificationsActive
        RingerMode.VIBRATE -> Icons.Rounded.Vibration
        RingerMode.SILENT -> Icons.Rounded.NotificationsOff
        RingerMode.DND -> Icons.Rounded.DoNotDisturb
    }
    val label = when (content.mode) {
        RingerMode.NORMAL -> "Ring"
        RingerMode.VIBRATE -> "Vibrate"
        RingerMode.SILENT -> "Silent"
        RingerMode.DND -> "Do Not Disturb"
    }
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        // Header row: icon badge scales with card so a tall custom card gets
        // a real focal point instead of a small glyph floating with empty
        // space around it.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            ExpandedIconBadge(icon = icon, tint = Color(0xFF60A5FA), fill = fill)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    color = Color.White,
                    fontSize = (13.sp.value + 3.sp.value * fill).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!isCramped) {
                    Text(
                        "Ketuk mode untuk mengganti",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (!isCramped) {
            Spacer(modifier = Modifier.height((6.dp + 10.dp * fill)))
            val chipVPad = 6.dp + 4.dp * fill
            val chipGap = 6.dp + 2.dp * fill
            Row(
                horizontalArrangement = Arrangement.spacedBy(chipGap),
                modifier = Modifier.fillMaxWidth()
            ) {
                modes.forEach { (mode, pair) ->
                    val (chipLabel, chipIcon) = pair
                    val selected = content.mode == mode
                    val chipInteractionSource = remember(mode) { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) Color(0xFF3B82F6)
                                else Color.White.copy(alpha = 0.14f)
                            )
                            .clickable(
                                interactionSource = chipInteractionSource,
                                indication = null
                            ) {
                                onAction("ringer:${mode.name}")
                            }
                            .pressScale(chipInteractionSource)
                            .padding(horizontal = 8.dp, vertical = chipVPad),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (fill > 0.35f) {
                                Icon(
                                    chipIcon,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                            Text(
                                chipLabel,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ExpandedToggleControls(
    title: String,
    enabled: Boolean,
    onActionLabel: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isCramped: Boolean,
    availableHeight: Dp,
    onAction: (String) -> Unit
) {
    val fill = expandFill(availableHeight)
    val tint = if (enabled) Color(0xFF60A5FA) else Color.White.copy(alpha = 0.6f)
    // Tapping the header (icon + title) toggles the opposite state — useful
    // when the card is too short for On/Off chips, and as a large secondary
    // hit target even when chips are visible.
    val toggleOpposite = {
        onAction("$onActionLabel:${if (enabled) "off" else "on"}")
    }
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        val headerInteractionSource = remember { MutableInteractionSource() }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = headerInteractionSource,
                    indication = null,
                    onClick = toggleOpposite
                )
                .pressScale(headerInteractionSource)
        ) {
            ExpandedIconBadge(icon = icon, tint = tint, fill = fill)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = (13.sp.value + 3.sp.value * fill).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!isCramped) {
                    Text(
                        if (enabled) "Aktif" else "Nonaktif",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        // Always show On/Off chips so the toggle is never unreachable.
        // When cramped, use tighter vertical padding so chips still fit.
        Spacer(modifier = Modifier.height(if (isCramped) 4.dp else (6.dp + 10.dp * fill)))
        val chipVPad = if (isCramped) 4.dp else (6.dp + 4.dp * fill)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf(true to "On", false to "Off").forEach { (on, label) ->
                val selected = enabled == on
                val chipInteractionSource = remember(on) { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (selected) Color(0xFF3B82F6)
                            else Color.White.copy(alpha = 0.14f)
                        )
                        .clickable(
                            interactionSource = chipInteractionSource,
                            indication = null
                        ) {
                            onAction("$onActionLabel:${if (on) "on" else "off"}")
                        }
                        .pressScale(chipInteractionSource)
                        .padding(horizontal = 14.dp, vertical = chipVPad),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = Color.White,
                        fontSize = if (isCramped) 11.sp else 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}


@Composable
internal fun ExpandedWifiControls(
    content: IslandContent.Wifi,
    isCramped: Boolean,
    availableHeight: Dp,
    onAction: (String) -> Unit
) {
    val fill = expandFill(availableHeight)
    val tint = if (content.enabled) Color(0xFF60A5FA) else Color.White.copy(alpha = 0.6f)
    val toggleOpposite = {
        onAction("wifi:${if (content.enabled) "off" else "on"}")
    }
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        val headerInteractionSource = remember { MutableInteractionSource() }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = headerInteractionSource,
                    indication = null,
                    onClick = toggleOpposite
                )
                .pressScale(headerInteractionSource)
        ) {
            ExpandedIconBadge(
                icon = if (content.enabled) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                tint = tint,
                fill = fill
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    content.ssid?.takeIf { it.isNotBlank() } ?: if (content.enabled) "Wi-Fi On" else "Wi-Fi Off",
                    color = Color.White,
                    fontSize = (13.sp.value + 3.sp.value * fill).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!isCramped) {
                    Text(
                        if (content.enabled) {
                            val bars = "●".repeat(content.signalLevel.coerceIn(0, 4)) +
                                "○".repeat((4 - content.signalLevel).coerceIn(0, 4))
                            if (content.ssid != null) "Terhubung • $bars" else "Aktif"
                        } else "Nonaktif",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(if (isCramped) 4.dp else (6.dp + 10.dp * fill)))
        val chipVPad = if (isCramped) 4.dp else (6.dp + 4.dp * fill)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf(true to "On", false to "Off").forEach { (on, label) ->
                val selected = content.enabled == on
                val chipInteractionSource = remember(on) { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (selected) Color(0xFF3B82F6)
                            else Color.White.copy(alpha = 0.14f)
                        )
                        .clickable(
                            interactionSource = chipInteractionSource,
                            indication = null
                        ) {
                            onAction("wifi:${if (on) "on" else "off"}")
                        }
                        .pressScale(chipInteractionSource)
                        .padding(horizontal = 14.dp, vertical = chipVPad),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = Color.White,
                        fontSize = if (isCramped) 11.sp else 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
internal fun ExpandedCellularControls(
    content: IslandContent.CellularData,
    isCramped: Boolean,
    availableHeight: Dp,
    onAction: (String) -> Unit
) {
    val fill = expandFill(availableHeight)
    val tint = if (content.enabled) Color(0xFF34D399) else Color.White.copy(alpha = 0.6f)
    val toggleOpposite = {
        onAction("cellular:${if (content.enabled) "off" else "on"}")
    }
    val cellularHeaderInteractionSource = remember { MutableInteractionSource() }
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = cellularHeaderInteractionSource,
                    indication = null,
                    onClick = toggleOpposite
                )
                .pressScale(cellularHeaderInteractionSource)
        ) {
            ExpandedIconBadge(
                icon = if (content.enabled) Icons.Rounded.SignalCellularAlt else Icons.Rounded.SignalCellularOff,
                tint = tint,
                fill = fill
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    content.operatorName?.takeIf { it.isNotBlank() }
                        ?: if (content.enabled) "Data Seluler On" else "Data Seluler Off",
                    color = Color.White,
                    fontSize = (13.sp.value + 3.sp.value * fill).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!isCramped) {
                    Text(
                        if (content.enabled) {
                            content.networkType ?: "Aktif"
                        } else "Nonaktif",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(if (isCramped) 4.dp else (6.dp + 10.dp * fill)))
        val chipVPad = if (isCramped) 4.dp else (6.dp + 4.dp * fill)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf(true to "On", false to "Off").forEach { (on, label) ->
                val selected = content.enabled == on
                val chipInteractionSource = remember(on) { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (selected) Color(0xFF3B82F6)
                            else Color.White.copy(alpha = 0.14f)
                        )
                        .clickable(
                            interactionSource = chipInteractionSource,
                            indication = null
                        ) {
                            onAction("cellular:${if (on) "on" else "off"}")
                        }
                        .pressScale(chipInteractionSource)
                        .padding(horizontal = 14.dp, vertical = chipVPad),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = Color.White,
                        fontSize = if (isCramped) 11.sp else 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

