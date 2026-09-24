package com.hyperisland.root.root

import android.content.Context

/**
 * Non-root build: heads-up suppression is a root/Xposed feature.
 * Stub keeps NLS compiling; always reports not-suppressed.
 */
object SuppressedAppsStore {
    fun init(context: Context) { /* no-op */ }
    fun isSuppressed(packageName: String): Boolean = false
}
