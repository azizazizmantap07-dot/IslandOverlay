package com.hyperisland.root.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple file-based logger for debug builds.
 * Writes to: Android/data/com.hyperisland.root/files/logs/
 * and also tries Downloads/HyperIsland_logs/ for easy sharing.
 */
object CrashLogger {

    private const val TAG = "HyperIslandLog"
    private const val PREFS_NAME = "island_layout_prefs" // shared with IslandPreferences
    private const val KEY_DEBUG_LOG = "debug_log_enabled"

    private var appContext: Context? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Whether routine debug logging is written to file. Off by default so the
     * app doesn't write to disk during normal use; the user can flip this on
     * from Settings when they actually need to capture a debug log. Crash
     * reports (writeCrash) always go to file regardless of this flag, since
     * those are the one thing you need even when you didn't expect to.
     */
    @Volatile
    private var fileLoggingEnabled: Boolean = false

    fun isFileLoggingEnabled(): Boolean = fileLoggingEnabled

    fun setFileLoggingEnabled(context: Context, enabled: Boolean) {
        fileLoggingEnabled = enabled
        try {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DEBUG_LOG, enabled)
                .apply()
        } catch (_: Exception) {}
        i("Debug log ${if (enabled) "enabled" else "disabled"}")
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        fileLoggingEnabled = try {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DEBUG_LOG, false)
        } catch (_: Exception) {
            false
        }
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(throwable, "Uncaught on thread: ${thread.name}")
            } catch (_: Exception) {}
            // Let the system default handler run after we log
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            // We already replaced it; just rethrow behavior by killing process is fine
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(10)
        }
        i("CrashLogger initialized")
    }

    fun d(msg: String) = log("D", msg)
    fun i(msg: String) = log("I", msg)
    fun w(msg: String) = log("W", msg)
    fun e(msg: String, t: Throwable? = null) {
        log("E", msg)
        if (t != null) writeCrash(t, msg)
    }

    private fun log(level: String, msg: String) {
        when (level) {
            "D" -> Log.d(TAG, msg)
            "I" -> Log.i(TAG, msg)
            "W" -> Log.w(TAG, msg)
            "E" -> Log.e(TAG, msg)
        }
        // Routine logs only hit disk when the user has turned debug logging on.
        // Logcat output above still always happens (useful for `adb logcat`
        // during development) - this flag only gates the persisted file that
        // gets shared via "Share Debug Log".
        if (fileLoggingEnabled) {
            appendToFile("[$level] ${timeFormat.format(Date())}  $msg\n")
        }
    }

    fun writeCrash(t: Throwable, extra: String = "") {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val body = buildString {
            appendLine("========== CRASH ==========")
            appendLine("Time   : ${timeFormat.format(Date())}")
            appendLine("Extra  : $extra")
            appendLine("Device : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("-------- Stacktrace -------")
            appendLine(sw.toString())
            appendLine("===========================")
            appendLine()
        }
        Log.e(TAG, body)
        appendToFile(body)
        // Also write a dedicated crash file for easy share
        writeDedicatedCrashFile(body)
    }

    private fun appendToFile(text: String) {
        val ctx = appContext ?: return
        try {
            val dir = getLogDir(ctx)
            val file = File(dir, "hyperisland_debug.txt")
            file.appendText(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log", e)
        }
    }

    private fun writeDedicatedCrashFile(body: String) {
        val ctx = appContext ?: return
        try {
            val dir = getLogDir(ctx)
            val name = "crash_${dateFormat.format(Date())}.txt"
            File(dir, name).writeText(body)
        } catch (_: Exception) {}
    }

    private fun getLogDir(ctx: Context): File {
        // Prefer app-specific external dir (no storage permission needed on modern Android)
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val dir = File(base, "logs")
        if (!dir.exists()) dir.mkdirs()

        // Also try public Downloads for easier access via file manager
        try {
            val downloads = File("/storage/emulated/0/Download/HyperIsland_logs")
            if (!downloads.exists()) downloads.mkdirs()
            // Mirror the main log there too when possible
        } catch (_: Exception) {}

        return dir
    }

    /** Returns path of the main debug log so UI can show it */
    fun getMainLogPath(ctx: Context): String {
        val dir = getLogDir(ctx)
        return File(dir, "hyperisland_debug.txt").absolutePath
    }

    fun clearLogs(ctx: Context) {
        try {
            getLogDir(ctx).listFiles()?.forEach { it.delete() }
            i("Logs cleared")
        } catch (_: Exception) {}
    }
}
