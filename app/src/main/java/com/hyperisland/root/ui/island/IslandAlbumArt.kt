package com.hyperisland.root.ui.island

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.hyperisland.root.media.AlbumArtCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-bleed album art backdrop with a dark scrim so title / controls stay
 * readable. Prefers [AlbumArtCache] (MediaSession bitmaps + MediaStyle icons)
 * over content URIs, which other apps rarely grant us permission to open.
 */
@Composable
internal fun AlbumArtBackdrop(
    albumArtUri: String?,
    title: String = "",
    artist: String = "",
    modifier: Modifier = Modifier,
    scrimAlpha: Float = 0.62f,
    fallback: Color = Color(0xF2000000)
) {
    val context = LocalContext.current
    val cacheVersion by AlbumArtCache.version.collectAsState()
    var art by remember(albumArtUri, title, artist) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(albumArtUri, title, artist, cacheVersion) {
        // 1) In-memory cache from MediaSession / notification largeIcon
        val cached = when {
            !albumArtUri.isNullOrBlank() && albumArtUri.startsWith("cache:") ->
                AlbumArtCache.getByKey(albumArtUri.removePrefix("cache:"))
            title.isNotBlank() ->
                AlbumArtCache.get(title, artist) ?: AlbumArtCache.get(title, "")
            else -> null
        }
        if (cached != null) {
            art = cached
            return@LaunchedEffect
        }

        // 2) Try loading a real URI (file:// / content://) when available
        art = null
        val uri = albumArtUri?.takeIf {
            it.isNotBlank() && !it.startsWith("cache:")
        } ?: return@LaunchedEffect
        art = withContext(Dispatchers.IO) {
            loadAlbumArtBitmap(context, uri)
        }
    }

    Box(modifier = modifier) {
        val bmp = art
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Black.copy(alpha = scrimAlpha * 0.45f),
                                0.45f to Color.Black.copy(alpha = scrimAlpha * 0.72f),
                                1.0f to Color.Black.copy(alpha = (scrimAlpha + 0.15f).coerceAtMost(0.88f))
                            )
                        )
                    )
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(fallback)
            )
        }
    }
}

private fun loadAlbumArtBitmap(
    context: android.content.Context,
    uriString: String
): ImageBitmap? {
    return try {
        val uri = Uri.parse(uriString)
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val stream = when (uri.scheme) {
            "content", "android.resource", "file" ->
                context.contentResolver.openInputStream(uri)
            else -> {
                java.io.File(uri.path ?: uriString).takeIf { it.exists() }?.inputStream()
            }
        } ?: return null
        stream.use { input ->
            BitmapFactory.decodeStream(input, null, opts)?.asImageBitmap()
        }
    } catch (_: Exception) {
        null
    }
}
