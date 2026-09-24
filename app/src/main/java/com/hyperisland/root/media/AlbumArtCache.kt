package com.hyperisland.root.media

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory album art store. MediaSession bitmaps and MediaStyle notification
 * largeIcons are kept here so Compose can paint them without content-URI grants
 * (which other apps rarely share with our process).
 */
object AlbumArtCache {
    private val bitmaps = ConcurrentHashMap<String, ImageBitmap>()
    private val androidBitmaps = ConcurrentHashMap<String, Bitmap>()
    private val _version = MutableStateFlow(0)
    /** Bumps when art is inserted so Compose can re-read the cache. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun key(title: String, artist: String): String =
        "${title.trim()}|${artist.trim()}".lowercase()

    fun put(title: String, artist: String, bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        val k = key(title, artist)
        try {
            val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false) ?: return
            androidBitmaps[k] = copy
            bitmaps[k] = copy.asImageBitmap()
            _version.value = _version.value + 1
        } catch (_: Exception) {
        }
    }


    fun get(title: String, artist: String): ImageBitmap? = bitmaps[key(title, artist)]

    fun getByKey(k: String): ImageBitmap? = bitmaps[k]

    fun clear() {
        bitmaps.clear()
        androidBitmaps.values.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
        androidBitmaps.clear()
    }
}
