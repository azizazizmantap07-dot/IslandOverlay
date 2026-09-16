package com.hyperisland.root.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed entry – the interactive island still comes only from OverlayService
 * ("Start Island"); no second island is injected into SystemUI. What IS active here
 * is SystemUIIslandHook, which hides the wifi/mobile-signal/network-speed status icons
 * while the Island overlay is Compact/Expanded, so they stop stealing touches from it.
 *
 * DIAGNOSTIC_MODE: set to true to also run StatusBarInspector, which dumps the full
 * status bar view hierarchy to logcat (tag "HyperIslandInspector") so you can find the
 * real resource-id names for your ROM. Turn it back off once you've updated
 * SystemUIIslandHook's id-name lists — it logs on every status bar attach and is not
 * meant to run permanently.
 */
class IslandXposedEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    private val DIAGNOSTIC_MODE = false

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        XposedBridge.log("HyperIsland: zygote init")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return
        XposedBridge.log("HyperIsland: SystemUI loaded – installing status-icon visibility hook")
        try {
            SystemUIIslandHook.install(lpparam)
        } catch (t: Throwable) {
            XposedBridge.log("HyperIsland: install failed")
            XposedBridge.log(t)
        }

        if (DIAGNOSTIC_MODE) {
            try {
                StatusBarInspector.install(lpparam)
            } catch (t: Throwable) {
                XposedBridge.log("HyperIsland: StatusBarInspector install failed")
                XposedBridge.log(t)
            }
        }
    }
}
