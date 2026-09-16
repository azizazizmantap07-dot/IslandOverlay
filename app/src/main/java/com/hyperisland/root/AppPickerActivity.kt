package com.hyperisland.root

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.hyperisland.root.root.HeadsUpSuppressor
import com.hyperisland.root.root.SuppressedAppsStore
import com.hyperisland.root.ui.theme.HyperIslandTheme
import kotlinx.coroutines.launch

/**
 * Lets the user pick which apps get their pop-up/heads-up notifications
 * suppressed and rerouted to the Island only. Manual whitelist ("hanya
 * aplikasi yang saya pilih") — each toggle calls HeadsUpSuppressor
 * immediately so the change is live right away, and un-toggling restores
 * that app's original channel importance.
 */
class AppPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SuppressedAppsStore.init(this)
        setContent {
            HyperIslandTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppPickerScreen(onBack = { finish() })
                }
            }
        }
    }
}

private data class InstalledAppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val suppressedPackages by SuppressedAppsStore.suppressedPackages.collectAsState()

    var apps by remember { mutableStateOf<List<InstalledAppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    // Tracks packages currently mid-toggle so the switch shows a spinner
    // instead of looking unresponsive while the root command runs.
    var pendingPackages by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        apps = loadUserFacingApps(context)
        loading = false
    }

    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Blokir Popup Aplikasi") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Text(
                "Aplikasi yang dipilih tidak akan menampilkan popup notifikasi mengambang lagi — " +
                    "notifikasinya tetap masuk dan akan ditampilkan lewat Dynamic Island. " +
                    "Notifikasi panggilan telepon tetap muncul seperti biasa demi keamanan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Cari aplikasi") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            Spacer(Modifier.height(8.dp))

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val isSuppressed = app.packageName in suppressedPackages
                        val isPending = app.packageName in pendingPackages

                        AppRow(
                            app = app,
                            checked = isSuppressed,
                            pending = isPending,
                            onCheckedChange = { newValue ->
                                pendingPackages = pendingPackages + app.packageName
                                scope.launch {
                                    if (newValue) {
                                        HeadsUpSuppressor.suppressPackage(app.packageName)
                                        // Picking an app implies the user wants suppression
                                        // active — auto-enable the master switch so they
                                        // don't have to flip it separately on the main screen.
                                        SuppressedAppsStore.setMasterEnabled(true)
                                    } else {
                                        HeadsUpSuppressor.restorePackage(app.packageName)
                                    }
                                    SuppressedAppsStore.setPackageSuppressed(app.packageName, newValue)
                                    pendingPackages = pendingPackages - app.packageName
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledAppEntry,
    checked: Boolean,
    pending: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = remember(app.icon) { app.icon?.toBitmap(width = 96, height = 96) }
            if (bitmap != null) {
                Image(
                    painter = BitmapPainter(bitmap.asImageBitmap()),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (pending) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private fun loadUserFacingApps(context: android.content.Context): List<InstalledAppEntry> {
    val pm = context.packageManager
    val ownPackage = context.packageName
    val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)

    return installed
        .asSequence()
        .filter { it.packageName != ownPackage }
        // Skip apps with no launcher entry (system services etc.) — they
        // rarely post user-facing notifications worth suppressing, and
        // including them just clutters the list.
        .filter { pm.getLaunchIntentForPackage(it.packageName) != null || isUserInstalled(it) }
        .map {
            InstalledAppEntry(
                packageName = it.packageName,
                label = try { pm.getApplicationLabel(it).toString() } catch (_: Exception) { it.packageName },
                icon = try { pm.getApplicationIcon(it) } catch (_: Exception) { null }
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
        .toList()
}

private fun isUserInstalled(info: ApplicationInfo): Boolean =
    (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
