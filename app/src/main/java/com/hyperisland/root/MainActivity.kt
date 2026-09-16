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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.hyperisland.root.audio.AudioPulseEngine
import com.hyperisland.root.root.HeadsUpSuppressor
import com.hyperisland.root.root.RootManager
import com.hyperisland.root.root.SuppressedAppsStore
import com.hyperisland.root.service.IslandOverlayService
import com.hyperisland.root.ui.island.IslandPreferences
import com.hyperisland.root.ui.theme.HyperIslandTheme
import com.hyperisland.root.util.AppPreferences
import com.hyperisland.root.util.CrashLogger
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IslandPreferences.init(this)
        SuppressedAppsStore.init(this)
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
    var isRoot by remember { mutableStateOf(RootManager.isRooted()) }
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
    val recordAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRecordAudio = granted
        if (granted) {
            val started = AudioPulseEngine.start()
            Toast.makeText(
                context,
                if (started) "Real audio-reactive equalizer aktif" else "Izin OK, tapi belum ada sesi audio aktif",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(context, "Tanpa izin ini, equalizer pakai animasi fallback", Toast.LENGTH_SHORT).show()
        }
    }

    val layout by IslandPreferences.config.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hyper Island Root") },
                actions = {
                    IconButton(onClick = {
                        CrashLogger.clearLogs(context)
                        Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Clear logs")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    StatusRow(isRoot, "Root access granted", "Root not available (UI still works)")
                    StatusRow(hasOverlay, "Overlay permission OK", "Overlay permission needed")
                    StatusRow(hasNotifAccess, "Notification Access OK (for Music)", "Notification Access needed for Music")
                    StatusRow(hasRecordAudio, "Mic access OK (real audio equalizer)", "Grant mic access for real audio-reactive equalizer")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!hasOverlay) {
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Grant Overlay Permission") }
            }

            if (!hasNotifAccess) {
                Button(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        Toast.makeText(context, "Enable Hyper Island Root in the list", Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Grant Notification Access (Music)") }
            }

            if (!hasRecordAudio) {
                Button(
                    onClick = { recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Grant Mic Access (Real Audio Equalizer)") }
                Text(
                    "Dipakai hanya untuk membaca level & beat audio yang sedang diputar (Visualizer) — bukan untuk merekam suara.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        IslandOverlayService.start(context)
                        CrashLogger.i("Start Island requested")
                        Toast.makeText(context, "Island started – play music to test", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Start Island") }
                OutlinedButton(
                    onClick = {
                        IslandOverlayService.stop(context)
                        CrashLogger.i("Stop Island requested")
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Stop") }
            }

            val startOnBoot by AppPreferences.startOnBoot.collectAsState()
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Aktif otomatis saat boot", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Island akan otomatis start sendiri setiap perangkat selesai reboot — tidak perlu buka aplikasi ini lagi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = startOnBoot,
                        onCheckedChange = { enabled ->
                            AppPreferences.setStartOnBoot(enabled)
                            CrashLogger.i("Start-on-boot set to $enabled")
                            Toast.makeText(
                                context,
                                if (enabled) "Akan auto-start setelah reboot" else "Auto-start saat boot dimatikan",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Mode Overlay (aktif)", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Island interaktif hanya dari tombol Start Island.\n" +
                        "Hook SystemUI (island pendek) sudah dinonaktifkan agar tidak dobel.\n" +
                        "Gunakan slider di bawah untuk mengatur posisi/ukuran/animasi.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ─── Pill Layout Settings ───────────────────────────────────────
            HorizontalDivider()
            Text("Pengaturan Posisi & Ukuran Pill", style = MaterialTheme.typography.titleMedium)
            Text(
                "Geser slider untuk menyesuaikan letak dan ukuran island. Perubahan langsung diterapkan saat Island aktif.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Posisi", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))

                    LayoutSlider(
                        label = "Kiri / Kanan (X)",
                        value = layout.offsetX.toFloat(),
                        valueRange = -200f..200f,
                        valueLabel = "${layout.offsetX} px",
                        onValueChange = { IslandPreferences.setOffsetX(it.roundToInt()) }
                    )
                    LayoutSlider(
                        label = "Atas / Bawah (Y)",
                        value = if (layout.offsetY < 0) 0f else layout.offsetY.toFloat(),
                        valueRange = 0f..120f,
                        valueLabel = if (layout.offsetY < 0) "Auto" else "${layout.offsetY} px",
                        onValueChange = { IslandPreferences.setOffsetY(it.roundToInt()) }
                    )
                    Text(
                        "Y = 0 memakai posisi otomatis berdasarkan status bar. Naikkan nilai untuk turun ke bawah.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Ukuran Minimal", style = MaterialTheme.typography.titleSmall)
                    LayoutSlider(
                        label = "Lebar (panjang)",
                        value = layout.minimalWidthDp,
                        valueRange = 60f..220f,
                        valueLabel = "${layout.minimalWidthDp.roundToInt()} dp",
                        onValueChange = { IslandPreferences.setMinimalWidth(it) }
                    )
                    LayoutSlider(
                        label = "Tinggi (lebar)",
                        value = layout.minimalHeightDp,
                        valueRange = 24f..60f,
                        valueLabel = "${layout.minimalHeightDp.roundToInt()} dp",
                        onValueChange = { IslandPreferences.setMinimalHeight(it) }
                    )
                }
            }

            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Ukuran Compact", style = MaterialTheme.typography.titleSmall)
                    LayoutSlider(
                        label = "Lebar (panjang)",
                        value = layout.compactWidthDp,
                        valueRange = 10f..320f,
                        valueLabel = "${layout.compactWidthDp.roundToInt()} dp",
                        onValueChange = { IslandPreferences.setCompactWidth(it) }
                    )
                    LayoutSlider(
                        label = "Tinggi (lebar)",
                        value = layout.compactHeightDp,
                        valueRange = 10f..72f,
                        valueLabel = "${layout.compactHeightDp.roundToInt()} dp",
                        onValueChange = { IslandPreferences.setCompactHeight(it) }
                    )
                }
            }

            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Ukuran Expand", style = MaterialTheme.typography.titleSmall)
                    LayoutSlider(
                        label = "Lebar (panjang)",
                        value = layout.expandedWidthDp,
                        valueRange = 10f..400f,
                        valueLabel = "${layout.expandedWidthDp.roundToInt()} dp",
                        onValueChange = { IslandPreferences.setExpandedWidth(it) }
                    )
                    LayoutSlider(
                        label = "Tinggi (lebar)",
                        value = layout.expandedHeightDp,
                        valueRange = 10f..160f,
                        valueLabel = "${layout.expandedHeightDp.roundToInt()} dp",
                        onValueChange = { IslandPreferences.setExpandedHeight(it) }
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    IslandPreferences.resetToDefaults()
                    Toast.makeText(context, "Layout direset ke default", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reset Layout ke Default")
            }

            Button(
                onClick = {
                    IslandOverlayService.restart(context)
                    Toast.makeText(context, "Island di-restart agar layout terapkan", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Terapkan & Restart Island")
            }

            Text(
                "Catatan: Slider hanya mengatur mode Overlay (Start Island). Mode LSPosed memakai layout SystemUI terpisah.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()
            HeadsUpSuppressSection(isRoot = isRoot)

            HorizontalDivider()
            Text("Debug Log", style = MaterialTheme.typography.titleSmall)
            Text(
                "Simpan log ke file hanya saat diperlukan (misalnya untuk melaporkan masalah). " +
                    "Biarkan mati untuk pemakaian sehari-hari.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Aktifkan Debug Log", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (layout.debugLogEnabled) "Aktif — log sedang ditulis ke file"
                            else "Nonaktif — tidak ada log yang ditulis ke file",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = layout.debugLogEnabled,
                        onCheckedChange = { enabled ->
                            IslandPreferences.setDebugLogEnabled(enabled)
                            CrashLogger.setFileLoggingEnabled(context, enabled)
                        }
                    )
                }
            }
            Text(
                "Log:\n$logPath",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val f = File(logPath)
                        Toast.makeText(
                            context,
                            if (f.exists()) "Log size: ${f.length()} bytes" else "Log belum ada",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Check Log File") }

                Button(
                    onClick = {
                        val f = File(logPath)
                        if (!f.exists()) {
                            Toast.makeText(context, "Log belum ada", Toast.LENGTH_SHORT).show()
                            return@Button
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
                    modifier = Modifier.weight(1f)
                ) { Text("Share Debug Log") }
            }
        }
    }
}

@Composable
private fun HeadsUpSuppressSection(isRoot: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val masterEnabled by SuppressedAppsStore.masterEnabled.collectAsState()
    val suppressedPackages by SuppressedAppsStore.suppressedPackages.collectAsState()
    var isRestoring by remember { mutableStateOf(false) }

    Text("Blokir Popup Notifikasi", style = MaterialTheme.typography.titleSmall)
    Text(
        "Alihkan notifikasi mengambang aplikasi pilihan ke Dynamic Island saja — " +
            "notifikasi tetap masuk ke shade seperti biasa, cuma popup-nya yang dimatikan. " +
            "Suara notifikasi asli app juga ikut mati; sebagai gantinya Island akan " +
            "membunyikan 1 nada notifikasi standar (menghormati mode silent/DND HP). " +
            "Butuh root. Panggilan telepon tidak pernah diblokir.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(8.dp))

    Card {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Aktifkan Blokir Popup", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (suppressedPackages.isEmpty()) "Belum ada aplikasi dipilih"
                        else "${suppressedPackages.size} aplikasi dipilih",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isRestoring) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Switch(
                        checked = masterEnabled,
                        enabled = isRoot,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                SuppressedAppsStore.setMasterEnabled(true)
                                // Re-apply suppression to every already-picked
                                // package immediately — otherwise packages
                                // picked in a previous session stay un-
                                // suppressed until the user re-opens the app
                                // picker and re-toggles each one individually.
                                val packages = suppressedPackages
                                if (packages.isNotEmpty()) {
                                    isRestoring = true
                                    scope.launch {
                                        packages.forEach { pkg ->
                                            HeadsUpSuppressor.suppressPackage(pkg)
                                        }
                                        isRestoring = false
                                    }
                                }
                            } else {
                                // Turning the master switch off restores every channel
                                // we've ever downgraded, immediately.
                                isRestoring = true
                                scope.launch {
                                    HeadsUpSuppressor.restoreAll()
                                    SuppressedAppsStore.setMasterEnabled(false)
                                    HeadsUpSuppressor.unbind()
                                    isRestoring = false
                                    Toast.makeText(
                                        context,
                                        "Popup notifikasi dikembalikan seperti semula",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )
                }
            }

            if (!isRoot) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Butuh akses root untuk mengubah pengaturan notifikasi sistem.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { context.startActivity(Intent(context, AppPickerActivity::class.java)) },
                enabled = isRoot,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pilih Aplikasi")
            }
        }
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
                color = MaterialTheme.colorScheme.primary
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
            null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(8.dp))
        Text(if (ok) okText else failText)
    }
}
