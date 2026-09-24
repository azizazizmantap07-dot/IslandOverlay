package com.hyperisland.root

import android.app.Application
import com.hyperisland.root.system.SystemControl
import com.hyperisland.root.ui.island.IslandBus
import com.hyperisland.root.ui.island.IslandPreferences
import com.hyperisland.root.util.AppPreferences
import com.hyperisland.root.util.CrashLogger

class HyperIslandApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.init(this)
        IslandPreferences.init(this)
        AppPreferences.init(this)
        IslandBus.init(this)
        SystemControl.init(this)
        CrashLogger.i("HyperIslandApp started")
    }
}
