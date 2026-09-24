package com.hyperisland.root.shizuku

import android.content.Context
import android.content.pm.PackageManager
import com.hyperisland.root.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Thin wrapper around Shizuku for elevated shell commands without root.
 *
 * User must:
 * 1. Install & start the Shizuku app
 * 2. Grant this app permission via Shizuku (or the in-app request dialog)
 */
object ShizukuHelper {
    private const val TAG = "ShizukuHelper"

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    /**
     * Shizuku.newProcess is package-private / private depending on API version.
     * Access it via reflection so the project compiles against the published
     * shizuku-api artifact.
     */
    private val newProcessMethod by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        } catch (t: Throwable) {
            CrashLogger.w("$TAG: newProcess method not found: ${t.message}")
            null
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        CrashLogger.i("$TAG: binder received")
        refreshState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        CrashLogger.w("$TAG: binder dead")
        _available.value = false
        _permissionGranted.value = false
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            _permissionGranted.value = granted
            CrashLogger.i("$TAG: permission result granted=$granted")
        }

    fun init(context: Context) {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            refreshState()
        } catch (t: Throwable) {
            CrashLogger.w("$TAG: init failed: ${t.message}")
        }
    }

    fun refreshState() {
        try {
            val alive = Shizuku.pingBinder()
            _available.value = alive
            _permissionGranted.value = alive && (
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            )
        } catch (t: Throwable) {
            _available.value = false
            _permissionGranted.value = false
            CrashLogger.w("$TAG: refreshState: ${t.message}")
        }
    }

    fun isReady(): Boolean = _available.value && _permissionGranted.value

    fun requestPermission(requestCode: Int = 1001) {
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode)
            } else {
                _permissionGranted.value = true
            }
        } catch (t: Throwable) {
            CrashLogger.w("$TAG: requestPermission failed: ${t.message}")
        }
    }

    /**
     * Runs a shell command via Shizuku (shell uid).
     * Returns true if the process exited with code 0.
     */
    suspend fun runCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        if (!isReady()) {
            CrashLogger.w("$TAG: runCommand skipped — Shizuku not ready")
            return@withContext false
        }
        val method = newProcessMethod
        if (method == null) {
            CrashLogger.w("$TAG: runCommand aborted — newProcess unavailable")
            return@withContext false
        }
        try {
            @Suppress("UNCHECKED_CAST")
            val process = method.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process

            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val code = process.waitFor()
            CrashLogger.d(
                "$TAG: cmd='${command.take(120)}' code=$code " +
                    "out=${stdout.take(200)} err=${stderr.take(200)}"
            )
            code == 0
        } catch (t: Throwable) {
            CrashLogger.w("$TAG: runCommand error: ${t.message}")
            false
        }
    }
}
