package com.hyperisland.root

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.hyperisland.root.audio.AudioPulseEngine
import com.hyperisland.root.service.IslandOverlayService
import com.hyperisland.root.ui.island.IslandPreferences
import com.hyperisland.root.ui.theme.HyperIslandTheme
import com.hyperisland.root.util.AppPreferences
import com.hyperisland.root.util.CrashLogger
import com.hyperisland.root.accessibility.IslandAccessibilityService
import com.hyperisland.root.shizuku.ShizukuHelper
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IslandPreferences.init(this)
        AppPreferences.init(this)
        setContent {
            HyperIslandTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val logPath = remember { CrashLogger.getMainLogPath(context) }

    fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return flat.contains(context.packageName)
    }
    var hasNotifAccess by remember { mutableStateOf(hasNotificationAccess()) }

    fun hasRecordAudio(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    var hasRecordAudio by remember { mutableStateOf(hasRecordAudio()) }

    // Shizuku + Accessibility
    val shizukuAvailable by ShizukuHelper.available.collectAsState()
    val shizukuGranted by ShizukuHelper.permissionGranted.collectAsState()
    var hasAccessibility by remember {
        mutableStateOf(IslandAccessibilityService.isEnabled(context))
    }

    // Refresh Shizuku + Accessibility whenever the Activity resumes
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                ShizukuHelper.refreshState()
                hasAccessibility = IslandAccessibilityService.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val recordAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRecordAudio = granted
        if (granted) {
            val started = AudioPulseEngine.start()
            Toast.makeText(
                context,
                if (started) "Equalizer audio real aktif" else "Izin diberikan, menunggu sesi audio",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(context, "Equalizer pakai animasi fallback", Toast.LENGTH_SHORT).show()
        }
    }

    val layout by IslandPreferences.config.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Hyper Island", fontWeight = FontWeight.SemiBold)
                },
                actions = {
                    IconButton(onClick = {
                        CrashLogger.clearLogs(context)
                        Toast.makeText(context, "Log dihapus", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Hapus log")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ─── Status ────────────────────────────────────────────────
            SectionGroup {
                StatusDivider()
                StatusRow(hasOverlay, "Izin overlay", "Izin overlay diperlukan")
                StatusDivider()
                StatusRow(hasNotifAccess, "Akses notifikasi", "Akses notifikasi diperlukan")
                StatusDivider()
                StatusRow(hasRecordAudio, "Akses mikrofon", "Mikrofon nonaktif — pakai fallback")
                StatusDivider()
                StatusRow(
                    shizukuAvailable && shizukuGranted,
                    "Shizuku aktif",
                    if (!shizukuAvailable) "Shizuku tidak berjalan" else "Izin Shizuku belum diberikan"
                )
                StatusDivider()
                StatusRow(hasAccessibility, "Deteksi fullscreen aktif", "Deteksi fullscreen nonaktif")
            }
            Text(
                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )

            // ─── Perizinan yang belum lengkap ──────────────────────────
            if (!hasOverlay || !hasNotifAccess || !hasRecordAudio) {
                SectionHeader("Izin diperlukan")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!hasOverlay) {
                        SoftButton(
                            text = "Izinkan Overlay",
                            icon = Icons.Rounded.PictureInPicture,
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        )
                    }
                    if (!hasNotifAccess) {
                        SoftButton(
                            text = "Izinkan Akses Notifikasi",
                            icon = Icons.Rounded.Notifications,
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                Toast.makeText(context, "Aktifkan Hyper Island di daftar", Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                    if (!hasRecordAudio) {
                        SoftButton(
                            text = "Izinkan Mikrofon",
                            icon = Icons.Rounded.Mic,
                            onClick = { recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                        )
                        HelperText("Hanya untuk membaca level audio, bukan merekam.")
                    }
                }
            }

            // ─── Shizuku & Accessibility (opsional) ────────────────────
            SectionHeader("Peningkatan sistem")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!shizukuAvailable || !shizukuGranted) {
                    SoftButton(
                        text = if (!shizukuAvailable) "Buka Shizuku" else "Izinkan Shizuku",
                        icon = Icons.Rounded.Security,
                        onClick = {
                            if (!shizukuAvailable) {
                                try {
                                    val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                    if (intent != null) {
                                        context.startActivity(intent)
                                    } else {
                                        // Open Play Store / GitHub release page
                                        val market = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))
                                        context.startActivity(market)
                                    }
                                } catch (t: Throwable) {
                                    Toast.makeText(context, "Install Shizuku terlebih dahulu", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                ShizukuHelper.requestPermission()
                            }
                            ShizukuHelper.refreshState()
                        }
                    )
                    HelperText("Mengaktifkan kontrol Airplane, Data, Lokasi, dan Hotspot dari Island.")
                }
                if (!hasAccessibility) {
                    SoftButton(
                        text = "Aktifkan Aksesibilitas",
                        icon = Icons.Rounded.Visibility,
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    )
                    HelperText("Hanya untuk mendeteksi fullscreen agar Island otomatis disembunyikan.")
                }
            }

            // ─── Kontrol Island ────────────────────────────────────────
            SectionHeader("Island")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        IslandOverlayService.start(context)
                        CrashLogger.i("Start Island requested")
                        Toast.makeText(context, "Island aktif — putar musik untuk mencoba", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("Mulai") }
                OutlinedButton(
                    onClick = {
                        IslandOverlayService.stop(context)
                        CrashLogger.i("Stop Island requested")
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("Berhenti") }
            }

            val startOnBoot by AppPreferences.startOnBoot.collectAsState()
            SectionGroup {
                ToggleRow(
                    title = "Aktif otomatis saat boot",
                    subtitle = "Island menyala sendiri setelah HP restart",
                    checked = startOnBoot,
                    onCheckedChange = { enabled ->
                        AppPreferences.setStartOnBoot(enabled)
                        CrashLogger.i("Start-on-boot set to $enabled")
                    }
                )
            }

            // ─── Posisi & Ukuran ───────────────────────────────────────
            SectionHeader("Posisi & Ukuran")
            SectionGroup {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    LayoutSlider(
                        label = "Kiri / Kanan",
                        value = layout.offsetX.toFloat(),
                        valueRange = -200f..200f,
                        valueLabel = "${layout.offsetX} px",
                        onValueChange = { IslandPreferences.setOffsetX(it.roundToInt()) }
                    )
                    LayoutSlider(
                        label = "Atas / Bawah",
                        value = if (layout.offsetY < 0) 0f else layout.offsetY.toFloat(),
                        valueRange = 0f..120f,
                        valueLabel = if (layout.offsetY < 0) "Auto" else "${layout.offsetY} px",
                        onValueChange = { IslandPreferences.setOffsetY(it.roundToInt()) }
                    )
                }
            }

            SectionGroup {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        "Ukuran Minimal",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LayoutSlider(
                        label = "Lebar",
                        value = layout.minimalWidthDp,
                        valueRange = 60f..220f,
                        valueLabel = "${layout.minimalWidthDp.roundToInt()} dp",
                        onValueChange = { IslandPreferences.setMinimalWidth(it) }
                    )
                    LayoutSlider(
                        label = "Tinggi",
                        value = layout.minimalHeightDp,
                        valueRange = 24f..60f,
                        valueLabel = "${layout.minimalHeightDp.roundToInt()} dp",
                        onValueChange = { IslandPreferences.setMinimalHeight(it) }
                    )
                }
            }

            SectionGroup {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        "Ukuran Compact",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LayoutSlider(
                        label = "Lebar",
                        value = layout.compactWidthDp,
                        valueRange = 10f..320f,
                        valueLabel = "${layout.compactWidthDp.roundToInt()} dp",
                        onValueChange = { IslandPreferences.setCompactWidth(it) }
                    )
                    LayoutSlider(
                        label = "Tinggi",
                        value = layout.compactHeightDp,
                        valueRange = 10f..72f,
                        valueLabel = "${layout.compactHeightDp.roundToInt()} dp",
                        onValueChange = { IslandPreferences.setCompactHeight(it) }
                    )
                }
            }

            SectionGroup {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        "Ukuran Expand",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LayoutSlider(
                        label = "Lebar",
                        value = layout.expandedWidthDp,
                        valueRange = 10f..400f,
                        valueLabel = "${layout.expandedWidthDp.roundToInt()} dp",
                        onValueChange = { IslandPreferences.setExpandedWidth(it) }
                    )
                    LayoutSlider(
                        label = "Tinggi",
                        value = layout.expandedHeightDp,
                        valueRange = 10f..160f,
                        valueLabel = "${layout.expandedHeightDp.roundToInt()} dp",
                        onValueChange = { IslandPreferences.setExpandedHeight(it) }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        IslandPreferences.resetToDefaults()
                        Toast.makeText(context, "Layout direset", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reset")
                }
                Button(
                    onClick = {
                        IslandOverlayService.restart(context)
                        Toast.makeText(context, "Island di-restart", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("Terapkan") }
            }

            // ─── Mode Expand ───────────────────────────────────────────
            SectionHeader("Mode Expand", subtitle = "Izinkan expand saat island Compact di-tap")
            SectionGroup {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    ExpandToggleRow("Notifikasi", layout.expandNotification) {
                        IslandPreferences.setExpandNotification(it)
                    }
                    ExpandToggleRow("Musik / Media", layout.expandMedia) {
                        IslandPreferences.setExpandMedia(it)
                    }
                    ExpandToggleRow("Charging", layout.expandCharging) {
                        IslandPreferences.setExpandCharging(it)
                    }
                    ExpandToggleRow("Ringer / DND", layout.expandRinger) {
                        IslandPreferences.setExpandRinger(it)
                    }
                    ExpandToggleRow("Senter", layout.expandFlashlight) {
                        IslandPreferences.setExpandFlashlight(it)
                    }
                    ExpandToggleRow("Bluetooth", layout.expandBluetooth) {
                        IslandPreferences.setExpandBluetooth(it)
                    }
                    ExpandToggleRow("Hotspot", layout.expandHotspot) {
                        IslandPreferences.setExpandHotspot(it)
                    }
                    ExpandToggleRow("Wi-Fi", layout.expandWifi) {
                        IslandPreferences.setExpandWifi(it)
                    }
                    ExpandToggleRow("Data Seluler", layout.expandCellular) {
                        IslandPreferences.setExpandCellular(it)
                    }
                    ExpandToggleRow("Lokasi", layout.expandLocation) {
                        IslandPreferences.setExpandLocation(it)
                    }
                    ExpandToggleRow("Custom", layout.expandCustom) {
                        IslandPreferences.setExpandCustom(it)
                    }
                }
            }

            // ─── Animasi Ring ──────────────────────────────────────────
            SectionHeader(
                "Animasi Ring",
                subtitle = "Aktifkan / nonaktifkan ring RGB di tiap state"
            )
            SectionGroup {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    ExpandToggleRow("Ring komet Minimal", layout.animRingMinimal) {
                        IslandPreferences.setAnimRingMinimal(it)
                    }
                    ExpandToggleRow("Ring RGB Compact", layout.animRingCompact) {
                        IslandPreferences.setAnimRingCompact(it)
                    }
                    ExpandToggleRow("Ring RGB Expanded", layout.animRingExpanded) {
                        IslandPreferences.setAnimRingExpanded(it)
                    }
                    ExpandToggleRow("Ring progres Musik", layout.animRingMusicProgress) {
                        IslandPreferences.setAnimRingMusicProgress(it)
                    }
                }
            }

            // ─── Blokir Popup ──────────────────────────────────────────

            // ─── Debug Log ─────────────────────────────────────────────
            SectionHeader("Debug Log")
            SectionGroup {
                ToggleRow(
                    title = "Simpan log ke file",
                    subtitle = if (layout.debugLogEnabled) "Aktif" else "Nonaktif",
                    checked = layout.debugLogEnabled,
                    onCheckedChange = { enabled ->
                        IslandPreferences.setDebugLogEnabled(enabled)
                        CrashLogger.setFileLoggingEnabled(context, enabled)
                    }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        val f = File(logPath)
                        Toast.makeText(
                            context,
                            if (f.exists()) "Ukuran log: ${f.length()} bytes" else "Log belum ada",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("Cek Log") }

                OutlinedButton(
                    onClick = {
                        val f = File(logPath)
                        if (!f.exists()) {
                            Toast.makeText(context, "Log belum ada", Toast.LENGTH_SHORT).show()
                            return@OutlinedButton
                        }
                        try {
                            val uri: Uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                f
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, "Hyper Island debug log")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(shareIntent, "Share debug log")
                            )
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                "Gagal share log: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("Bagikan Log") }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}



/** Judul kecil untuk memisahkan setiap kelompok pengaturan. */
@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Kartu soft dengan sudut membulat dan tanpa bayangan tajam, jadi wadah seragam untuk tiap section. */
@Composable
private fun SectionGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

@Composable
private fun StatusDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun SoftButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

@Composable
private fun HelperText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ExpandToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LayoutSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun StatusRow(ok: Boolean, okText: String, failText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(if (ok) okText else failText, style = MaterialTheme.typography.bodyMedium)
    }
}
