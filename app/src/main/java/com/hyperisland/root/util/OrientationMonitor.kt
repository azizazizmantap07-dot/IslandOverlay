package com.hyperisland.root.util

import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the device is currently portrait or landscape, so the
 * Island overlay can hide itself in landscape (most landscape use is
 * full-screen video/games where a floating pill near the cutout is more
 * annoying than useful) and reappear in portrait.
 *
 * [IslandOverlayService] has no Activity to hook onto, so this is fed from
 * the Service's own [android.app.Service.onConfigurationChanged] callback —
 * the standard way a Service observes orientation changes without an
 * Activity, since the system delivers that callback to every component
 * (Service included) whenever the Configuration changes and the component
 * hasn't declared it handles the relevant configChanges itself.
 */
object OrientationMonitor {
    private val _isLandscape = MutableStateFlow(false)
    val isLandscape: StateFlow<Boolean> = _isLandscape.asStateFlow()

    /** Call once at startup with the current config so the first read is correct
     *  even before any onConfigurationChanged callback has fired. */
    fun init(context: Context) {
        update(context.resources.configuration)
    }

    fun update(configuration: Configuration) {
        _isLandscape.value = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
}
