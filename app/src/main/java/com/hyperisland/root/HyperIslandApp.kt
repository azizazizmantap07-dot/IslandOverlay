package com.hyperisland.root

import android.app.Application
import com.hyperisland.root.root.RootManager
import com.hyperisland.root.root.SuppressedAppsStore
import com.hyperisland.root.ui.island.IslandBus
import com.hyperisland.root.ui.island.IslandPreferences
import com.hyperisland.root.util.CrashLogger
import com.topjohnwu.superuser.Shell

class HyperIslandApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.init(this)
        IslandPreferences.init(this)
        SuppressedAppsStore.init(this)
        IslandBus.init(this)

        Shell.enableVerboseLogging = true
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10)
        )
        RootManager.init(this)
        CrashLogger.i("HyperIslandApp started, rooted=${RootManager.isRooted()}")
    }
}
