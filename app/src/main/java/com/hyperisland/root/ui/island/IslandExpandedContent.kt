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
 * Expanded content for media player, charging, and notification (with reply).
 */
/** Enlarged compact-style media view with transport controls + wave strings. */
@Composable
internal fun ExpandedMediaPlayer(
    content: IslandContent.Media,
    availableWidth: Dp,
    availableHeight: Dp,
    onAction: (String) -> Unit
) {
    val liveAmplitude by AudioPulseEngine.amplitude.collectAsState()
    val liveBeatPhase by AudioPulseEngine.beatPhase.collectAsState()
    val liveBpm by AudioPulseEngine.bpm.collectAsState()
    val isCapturing by AudioPulseEngine.isCapturing.collectAsState()
    val realAmplitude = if (isCapturing && content.isPlaying) liveAmplitude else null
    val realBeatPhase = if (isCapturing && content.isPlaying && liveBpm > 0f) liveBeatPhase else null
    val realBpm = if (isCapturing && liveBpm > 0f) liveBpm else null
    val showControls = availableHeight >= 64.dp && availableWidth >= 180.dp
    val showWave = availableHeight >= 48.dp
    val fill = expandFill(availableHeight)
    // In-body scrub bar mirrors the edge ring so the middle of a tall custom
    // card is doing something useful instead of sitting empty. Only worth
    // showing once there's enough height to also fit the transport row.
    val showProgressBar = showControls && content.durationMs > 0
    val progress = if (content.durationMs > 0) {
        (content.positionMs.toFloat() / content.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(280, easing = LinearEasing),
        label = "expandMediaBodyProgress"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        if (showWave) {
            MusicWaveStrings(
                isPlaying = content.isPlaying,
                amplitude = minOf(8.dp, availableHeight * 0.28f),
                strokeWidth = 1.8.dp,
                beatPhase = realBeatPhase,
                bpm = realBpm,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp)
            )
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                EqualizerBars(
                    isPlaying = content.isPlaying,
                    maxHeight = (16.dp + 10.dp * fill),
                    barWidth = (2.dp + 1.dp * fill),
                    modifier = Modifier,
                    realAmplitude = realAmplitude
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        content.title,
                        color = Color.White,
                        fontSize = (13.sp.value + 3.sp.value * fill).sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (availableHeight >= 56.dp) {
                        Text(
                            content.artist,
                            color = Color.White.copy(0.75f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // Live lyric from LRCLIB (synced line at current position)
                    val lyric = content.lyricsLine
                    if (!lyric.isNullOrBlank() && availableHeight >= 72.dp) {
                        Text(
                            lyric,
                            color = Color.White.copy(0.9f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            if (showProgressBar) {
                Spacer(modifier = Modifier.height((8.dp + 10.dp * fill)))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (fill > 0.4f) {
                        Text(
                            formatMediaTime(content.positionMs),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.18f))
                    ) {
                        Box(
                            modifier = Modifier
                                .height(3.dp)
                                .fillMaxWidth(animatedProgress)
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF60A5FA))
                        )
                    }
                    if (fill > 0.4f) {
                        Text(
                            formatMediaTime(content.durationMs),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }
            }
            if (showControls) {
                Spacer(modifier = Modifier.height((6.dp + 6.dp * fill)))
                val transportSize = 26.dp + 8.dp * fill
                val playSize = 30.dp + 10.dp * fill
                val prevInteractionSource = remember { MutableInteractionSource() }
                val playPauseInteractionSource = remember { MutableInteractionSource() }
                val nextInteractionSource = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Previous
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier
                            .size(transportSize)
                            .clickable(
                                interactionSource = prevInteractionSource,
                                indication = null
                            ) { onAction("media_prev") }
                            .pressScale(prevInteractionSource, pressedScale = 0.88f)
                    )
                    // Play / Pause
                    Icon(
                        imageVector = if (content.isPlaying) Icons.Rounded.Pause
                        else Icons.Rounded.PlayArrow,
                        contentDescription = if (content.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier
                            .size(playSize)
                            .clickable(
                                interactionSource = playPauseInteractionSource,
                                indication = null
                            ) { onAction("media_play_pause") }
                            .pressScale(playPauseInteractionSource, pressedScale = 0.88f)
                    )
                    // Next
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier
                            .size(transportSize)
                            .clickable(
                                interactionSource = nextInteractionSource,
                                indication = null
                            ) { onAction("media_next") }
                            .pressScale(nextInteractionSource, pressedScale = 0.88f)
                    )
                }
            }
        }
    }
}

@Composable
internal fun ExpandedCharging(
    content: IslandContent.Charging,
    isCramped: Boolean,
    availableHeight: Dp,
    onCollapse: () -> Unit
) {
    val fill = expandFill(availableHeight)
    val glyphSize = minOf(if (isCramped) 28.dp else 44.dp, availableHeight * 0.55f)
        .safeCoerceIn(16.dp, 56.dp)
    val chargingInteractionSource = remember { MutableInteractionSource() }
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = chargingInteractionSource,
                indication = null
            ) { onCollapse() }
            .pressScale(chargingInteractionSource)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isCramped) 10.dp else 14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            AnimatedBatteryGlyph(
                level = content.level,
                isCharging = content.isCharging,
                width = glyphSize,
                height = glyphSize * 0.55f
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${content.level}%",
                    color = Color.White,
                    fontSize = (16.sp.value + 6.sp.value * fill).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    if (content.isCharging) {
                        buildString {
                            append("Charging")
                            if (content.currentMa != 0) append(" • ${content.currentMa} mA")
                            if (content.temperatureC > 0f) append(" • ${"%.1f".format(content.temperatureC)}°C")
                        }
                    } else {
                        "Not charging"
                    },
                    color = Color.White.copy(0.75f),
                    fontSize = (11.sp.value + 1.sp.value * fill).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!isCramped && content.isCharging) {
            Spacer(Modifier.height(6.dp + 6.dp * fill))
            AnimatedProgressTrack(
                progress = content.level / 100f,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 4.dp)
            )
        }
    }
}

@Composable
internal fun ExpandedNotification(
    content: IslandContent.Notification,
    isCramped: Boolean,
    availableHeight: Dp = 90.dp,
    onCollapse: () -> Unit,
    onAction: (String) -> Unit = {}
) {
    var replyForIndex by remember(content.key) { mutableStateOf<Int?>(null) }
    var replyText by remember(content.key) { mutableStateOf("") }

    // While the reply field is open, keep the Expanded popup alive so it does not
    // disappear mid-typing.
    LaunchedEffect(replyForIndex) {
        if (replyForIndex != null) {
            onAction("hold_expand")
        }
    }
    LaunchedEffect(replyText) {
        if (replyForIndex != null && replyText.isNotEmpty()) {
            onAction("hold_expand")
        }
    }

    val fill = expandFill(availableHeight)
    // Tapping anywhere on the card OUTSIDE the reply field / action chips opens
    // the originating app, matching standard heads-up / popup notification
    // behaviour (tap the notification → app opens; only its explicit buttons
    // do something else). Action chips and the reply field sit on top with
    // their own clickable() below, which consumes the tap first — so this
    // background handler only ever fires for the remaining "outside" area
    // (title/text row, icon, padding, empty space).
    val cardInteractionSource = remember { MutableInteractionSource() }
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = cardInteractionSource,
                indication = null
            ) {
                onAction("notif_open:${content.key}:${content.packageName}")
                onCollapse()
            }
    ) {
        // Bell sits in a scaling badge so a tall custom card gets a real focal
        // point in the body row instead of a tiny glyph plus dead space.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size((26.dp + 18.dp * fill))
                    .clip(CircleShape)
                    .background(Color(0xFF60A5FA).copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                BellRingIcon(
                    ringTrigger = content.arrivedAtMs,
                    size = (13.dp + 6.dp * fill),
                    color = Color(0xFF60A5FA)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    content.title,
                    color = Color.White,
                    fontSize = (12.sp.value + 3.sp.value * fill).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (content.text.isNotBlank()) {
                    Text(
                        content.text,
                        color = Color.White.copy(0.8f),
                        fontSize = 12.sp,
                        maxLines = if (isCramped) 1 else (2 + (fill * 3).toInt()),
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Inline reply field (shown when user taps a reply action)
        if (replyForIndex != null && !isCramped) {
            Spacer(modifier = Modifier.height((6.dp + 8.dp * fill)))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = replyText,
                    onValueChange = {
                        replyText = it
                        onAction("hold_expand")
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = {
                        Text("Balas…", color = Color.White.copy(0.45f), fontSize = 13.sp)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFF60A5FA),
                        focusedBorderColor = Color(0xFF60A5FA),
                        unfocusedBorderColor = Color.White.copy(0.25f),
                        focusedContainerColor = Color.White.copy(0.06f),
                        unfocusedContainerColor = Color.White.copy(0.06f)
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (replyText.isNotBlank()) Color(0xFF3B82F6)
                            else Color.White.copy(0.14f)
                        )
                        .clickable(enabled = replyText.isNotBlank()) {
                            val idx = replyForIndex ?: return@clickable
                            onAction("notif_reply:${content.key}:$idx:${replyText}")
                            replyText = ""
                            replyForIndex = null
                            onCollapse()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        "Kirim",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Action chips — reply opens inline field; others fire system PendingIntent.
        // These do NOT open the app; only the body row above does.
        if (!isCramped && content.actions.isNotEmpty() && replyForIndex == null) {
            Spacer(modifier = Modifier.height((6.dp + 8.dp * fill)))
            val actionChipVPad = 6.dp + 4.dp * fill
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                content.actions.take(3).forEach { action ->
                    val actionChipInteractionSource = remember(action.index) { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.14f))
                            .clickable(
                                interactionSource = actionChipInteractionSource,
                                indication = null
                            ) {
                                if (action.isReply) {
                                    replyForIndex = action.index
                                    replyText = ""
                                    onAction("hold_expand")
                                } else {
                                    onAction("notif_action:${content.key}:${action.index}")
                                    onCollapse()
                                }
                            }
                            .pressScale(actionChipInteractionSource)
                            .padding(horizontal = 10.dp, vertical = actionChipVPad),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            action.title,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

