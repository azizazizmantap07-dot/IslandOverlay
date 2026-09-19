package com.hyperisland.root.root

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.hyperisland.root.util.CrashLogger
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Suppresses heads-up (floating) pop-up notifications for a user-chosen set of
 * apps by downgrading their notification channels to IMPORTANCE_LOW at the
 * system level, while leaving the notification itself intact in the shade —
 * so IslandNotificationListener still receives onNotificationPosted() and can
 * show it on the Island.
 *
 * IMPORTANCE_LOW (2) specifically: no heads-up pop-up, no sound, but still
 * visible in the shade/notification list. This is the official Android
 * importance level for exactly this behavior — it's what "Silent" channels
 * use system-wide, not a hack.
 *
 * Never touches IMPORTANCE_HIGH channels used for calls (channel id typically
 * contains "call"/"phone") — those are skipped even if the app is in the
 * suppressed list, so incoming calls still pop up. Alarm apps aren't touched
 * either since alarms use their own full-screen intent path, not heads-up.
 *
 * All original importance values are remembered in SuppressedAppsStore
 * *before* any change, so restoreAll()/restorePackage() can put every
 * touched channel back exactly as the user (or the app) had it.
 *
 * IMPLEMENTATION NOTE (why this isn't a simple `RootManager.runRootCommand`
 * one-liner): there is no `cmd notification set_importance` shell
 * subcommand — it doesn't exist anywhere in AOSP's NotificationShellCmd
 * (the real subcommand list is allow_listener, disallow_listener,
 * allow_assistant, set_dnd, allow_dnd, disallow_dnd, post, set_bubbles,
 * set_bubbles_channel, list, get, snooze, unsnooze, etc). Calling it always
 * silently failed, which is why the "Blokir Popup Notifikasi" toggle never
 * actually suppressed anything even while it looked enabled in the UI. The
 * only real way to change another app's channel importance is the same
 * route `set_bubbles_channel` itself uses internally in AOSP:
 * `INotificationManager.getNotificationChannel()` + `setImportance()` +
 * `updateNotificationChannelForPackage()` — hidden/system APIs that require
 * the caller to actually run as root/system, not merely have a root *shell*
 * available. So this binds to [HeadsUpRootService] (a libsu RootService
 * running in a genuine root process) and calls those APIs over Binder IPC
 * through [IHeadsUpControl] instead of shelling out.
 */
object HeadsUpSuppressor {
    private const val TAG = "HeadsUpSuppressor"

    // NotificationManager.IMPORTANCE_LOW - shade only, no heads-up, no sound.
    private const val IMPORTANCE_LOW = 2
    private const val IMPORTANCE_DEFAULT = 3
    private const val CONNECT_TIMEOUT_MS = 8_000L

    // Channels whose id/name suggests calls – never downgraded, regardless of
    // the app-level suppression choice, so phone calls keep popping up.
    private val NEVER_SUPPRESS_CHANNEL_HINTS = listOf("call", "phone", "ringtone", "voip")

    @Volatile private var control: IHeadsUpControl? = null
    @Volatile private var pendingConnect: CompletableDeferred<IHeadsUpControl?>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val bound = IHeadsUpControl.Stub.asInterface(binder)
            control = bound
            pendingConnect?.complete(bound)
            pendingConnect = null
            CrashLogger.i("$TAG: HeadsUpRootService connected")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            control = null
            CrashLogger.w("$TAG: HeadsUpRootService disconnected")
        }
    }

    /** Binds to [HeadsUpRootService] if not already bound/connecting, and awaits the connection. */
    @Synchronized
    private fun startBindIfNeeded(): CompletableDeferred<IHeadsUpControl?>? {
        control?.let { return null } // already connected, nothing to await
        pendingConnect?.let { return it } // a bind is already in flight

        val ctx = RootManager.applicationContext()
        if (ctx == null) {
            CrashLogger.w("$TAG: no application context, cannot bind HeadsUpRootService")
            return null
        }

        val deferred = CompletableDeferred<IHeadsUpControl?>()
        pendingConnect = deferred
        try {
            val intent = Intent(ctx, HeadsUpRootService::class.java)
            RootService.bind(intent, connection)
        } catch (t: Throwable) {
            CrashLogger.e("$TAG: failed to bind HeadsUpRootService", t)
            deferred.complete(null)
            pendingConnect = null
        }
        return deferred
    }

    private suspend fun ensureBound(): IHeadsUpControl? {
        control?.let { return it }
        if (!RootManager.isRooted()) {
            CrashLogger.w("$TAG: no root, cannot bind HeadsUpRootService")
            return null
        }
        // RootService.bind() mirrors Context.bindService(); calling it off
        // the main thread has been unreliable across Android versions, so
        // it's always initiated on Dispatchers.Main regardless of which
        // dispatcher ensureBound() itself was called from.
        val deferred = withContext(Dispatchers.Main) { startBindIfNeeded() } ?: return control
        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) { deferred.await() }
    }

    /** Unbinds the root service; call when the master toggle is turned off to free the root process. */
    suspend fun unbind() = withContext(Dispatchers.Main) {
        try {
            RootService.unbind(connection)
        } catch (t: Throwable) {
            CrashLogger.w("$TAG: unbind failed: ${t.message}")
        }
        control = null
    }

    /**
     * Applies suppression for [packageName]: reads its channels via the root
     * service, remembers each channel's current importance (only the first
     * time we ever see it, so re-running this never overwrites the true
     * original), then sets non-call channels to IMPORTANCE_LOW.
     */
    suspend fun suppressPackage(packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (!RootManager.isRooted()) {
            CrashLogger.w("$TAG: no root, cannot suppress $packageName")
            return@withContext false
        }
        val svc = ensureBound()
        if (svc == null) {
            CrashLogger.w("$TAG: HeadsUpRootService unavailable, cannot suppress $packageName")
            return@withContext false
        }

        val channels = listChannels(svc, packageName)
        if (channels.isEmpty()) {
            CrashLogger.w("$TAG: no channels found for $packageName (app may not have posted yet)")
            return@withContext false
        }

        var anyApplied = false
        for ((channelId, currentImportance) in channels) {
            if (isCallChannel(channelId)) {
                CrashLogger.d("$TAG: skipping call-like channel $packageName/$channelId")
                continue
            }
            if (currentImportance <= IMPORTANCE_LOW) continue // already quiet

            SuppressedAppsStore.rememberOriginalImportance(packageName, channelId, currentImportance)
            val success = try {
                svc.setImportance(packageName, channelId, IMPORTANCE_LOW)
            } catch (t: Throwable) {
                CrashLogger.w("$TAG: setImportance RPC failed for $packageName/$channelId: ${t.message}")
                false
            }
            if (success) {
                anyApplied = true
                CrashLogger.i("$TAG: suppressed $packageName/$channelId ($currentImportance -> $IMPORTANCE_LOW)")
            } else {
                CrashLogger.w("$TAG: failed to suppress $packageName/$channelId")
            }
        }
        anyApplied
    }

    /** Restores every remembered channel for [packageName] to its original importance. */
    suspend fun restorePackage(packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (!RootManager.isRooted()) return@withContext false
        val svc = ensureBound() ?: return@withContext false
        val keys = SuppressedAppsStore.allRememberedChannelKeys().filter { it.first == packageName }
        var anyRestored = false
        for ((pkg, channelId) in keys) {
            val original = SuppressedAppsStore.getOriginalImportance(pkg, channelId) ?: IMPORTANCE_DEFAULT
            val success = try {
                svc.setImportance(pkg, channelId, original)
            } catch (t: Throwable) {
                CrashLogger.w("$TAG: restore RPC failed for $pkg/$channelId: ${t.message}")
                false
            }
            if (success) {
                anyRestored = true
                SuppressedAppsStore.forgetOriginalImportance(pkg, channelId)
                CrashLogger.i("$TAG: restored $pkg/$channelId -> $original")
            } else {
                CrashLogger.w("$TAG: failed to restore $pkg/$channelId")
            }
        }
        anyRestored
    }

    /**
     * Restores every channel we've ever downgraded, across all packages.
     * Called when the master toggle is turned off, and should also be wired
     * to module-disable/uninstall so nothing is left permanently silenced.
     */
    suspend fun restoreAll(): Boolean = withContext(Dispatchers.IO) {
        if (!RootManager.isRooted()) return@withContext false
        val keys = SuppressedAppsStore.allRememberedChannelKeys()
        if (keys.isEmpty()) return@withContext false
        val svc = ensureBound() ?: return@withContext false
        var anyRestored = false
        for ((pkg, channelId) in keys) {
            val original = SuppressedAppsStore.getOriginalImportance(pkg, channelId) ?: IMPORTANCE_DEFAULT
            val success = try {
                svc.setImportance(pkg, channelId, original)
            } catch (t: Throwable) {
                CrashLogger.w("$TAG: restoreAll RPC failed for $pkg/$channelId: ${t.message}")
                false
            }
            if (success) {
                anyRestored = true
                SuppressedAppsStore.forgetOriginalImportance(pkg, channelId)
            } else {
                CrashLogger.w("$TAG: failed to restore $pkg/$channelId during restoreAll")
            }
        }
        CrashLogger.i("$TAG: restoreAll done, restored ${keys.size} channel(s)")
        anyRestored
    }

    private fun isCallChannel(channelId: String): Boolean {
        val lower = channelId.lowercase()
        return NEVER_SUPPRESS_CHANNEL_HINTS.any { lower.contains(it) }
    }

    /**
     * Lists channel ids + current importance for [packageName] via the real
     * `INotificationManager.getNotificationChannelsForPackage()` API (run
     * inside the root service), replacing the old fragile approach of
     * text-scraping `dumpsys notification --noredact` output.
     */
    private fun listChannels(svc: IHeadsUpControl, packageName: String): List<Pair<String, Int>> {
        val raw = try {
            svc.listChannels(packageName)
        } catch (t: Throwable) {
            CrashLogger.w("$TAG: listChannels RPC failed for $packageName: ${t.message}")
            return emptyList()
        }
        return raw.mapNotNull { entry ->
            val idx = entry.lastIndexOf(':')
            if (idx <= 0) return@mapNotNull null
            val channelId = entry.substring(0, idx)
            val importance = entry.substring(idx + 1).toIntOrNull() ?: return@mapNotNull null
            channelId to importance
        }
    }
}
