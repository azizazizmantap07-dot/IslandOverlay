package com.hyperisland.root.xposed

import android.content.Context
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DIAGNOSTIC ONLY — does not hide or modify anything.
 *
 * Dumps the full status bar view hierarchy straight into the same debug log file
 * CrashLogger already uses (hyperisland_debug.txt). No adb needed: open the app,
 * scroll to "Debug Log", tap "Share Debug Log", and send the file (WhatsApp, email,
 * Files, etc). The dump is appended right alongside normal app logs and crashes.
 *
 * IMPORTANT: this hook runs inside the com.android.systemui process, which never
 * calls HyperIslandApp.onCreate(), so CrashLogger.appContext is null there and
 * CrashLogger.d()/i() would silently no-op. Instead this class resolves the app's
 * own external-files directory directly via createPackageContext(), writing to the
 * exact same physical file CrashLogger would have used.
 *
 * File location (matches CrashLogger.getLogDir()):
 *   Android/data/com.hyperisland.root/files/logs/hyperisland_debug.txt
 *
 * Output format inside that file:
 *   [2] LinearLayout id=statusIcons visible=true 240x48 childCount=4
 *   [3]   ImageView id=wifi_signal visible=true 24x24 childCount=0
 *   [3]   ImageView id=mobile_signal visible=true 24x24 childCount=0
 *   [3]   TextView id=network_speed_indicator visible=false 0x0 childCount=0
 *
 * The number in [brackets] is depth in the tree. "id=" is the resource entry name
 * (what you'd pass to getIdentifier(name, "id", pkg)) or NO_ID if the view has none.
 * Look for the group that contains wifi/signal icons, and any text/icon view that
 * looks like a network speed indicator — those names go into SystemUIIslandHook.
 */
object StatusBarInspector {

    private const val LOGCAT_TAG = "HyperIslandInspector"
    private const val APP_PACKAGE = "com.hyperisland.root"
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val phoneStatusBarViewClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.phone.PhoneStatusBarView",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                phoneStatusBarViewClass,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val statusBarView = param.thisObject as? View ?: return
                        val lines = StringBuilder()
                        dumpTree(statusBarView, depth = 0, out = lines)
                        writeToDebugLog(statusBarView.context, lines.toString())
                    }
                }
            )
            XposedBridge.log("$LOGCAT_TAG: installed on PhoneStatusBarView.onAttachedToWindow")
        } catch (t: Throwable) {
            XposedBridge.log("$LOGCAT_TAG: failed to install")
            XposedBridge.log(t)
        }
    }

    private fun dumpTree(view: View, depth: Int, out: StringBuilder) {
        val idName = resolveIdName(view)
        val indent = "  ".repeat(depth)
        out.append(
            "$indent[$depth] ${view.javaClass.simpleName} id=$idName " +
                "visible=${view.visibility == View.VISIBLE} " +
                "${view.width}x${view.height} " +
                "childCount=${(view as? ViewGroup)?.childCount ?: 0}\n"
        )
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                dumpTree(view.getChildAt(i), depth + 1, out)
            }
        }
    }

    private fun resolveIdName(view: View): String {
        return try {
            if (view.id == View.NO_ID || view.id == 0) {
                "NO_ID"
            } else {
                view.resources.getResourceEntryName(view.id)
            }
        } catch (t: Throwable) {
            "unresolvable(${view.id})"
        }
    }

    /**
     * Writes the dump into hyperisland_debug.txt inside the APP's own external-files
     * folder — not SystemUI's. We're currently running with a SystemUI Context, so we
     * have to explicitly resolve the target app's Context to get the right path.
     */
    private fun writeToDebugLog(systemUiContext: Context, dumpText: String) {
        try {
            val appContext = systemUiContext.createPackageContext(
                APP_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY
            )
            val base = appContext.getExternalFilesDir(null) ?: run {
                XposedBridge.log("$LOGCAT_TAG: getExternalFilesDir returned null, skipping file write")
                return
            }
            val dir = File(base, "logs")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "hyperisland_debug.txt")

            val header = buildString {
                appendLine("========== STATUS BAR INSPECTOR DUMP ==========")
                appendLine("Time: ${timeFormat.format(Date())}")
                appendLine(
                    "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                        "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
                )
                appendLine("-------------------------------------------------")
            }
            file.appendText(header)
            file.appendText(dumpText)
            file.appendText("=================================================\n\n")

            XposedBridge.log("$LOGCAT_TAG: dump written to ${file.absolutePath}")
        } catch (t: Throwable) {
            // Falls back to logcat only if we can't reach the app's storage
            // (e.g. package context resolution failed for some reason).
            XposedBridge.log("$LOGCAT_TAG: failed to write dump to debug log file, falling back to logcat")
            XposedBridge.log(t)
            XposedBridge.log("$LOGCAT_TAG:\n$dumpText")
        }
    }
}
