package com.aiutil.videodownloader.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 영상 첫 장면을 썸네일로 보여준다. 추출에 실패하면 기본 아이콘으로 대체한다. */
@Composable
fun VideoThumbnail(uri: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var image by remember(uri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(uri) {
        if (uri.isNullOrBlank()) return@LaunchedEffect
        image = withContext(Dispatchers.IO) {
            ThumbnailCache.load(context, uri)?.asImageBitmap()
        }
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Default.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private object ThumbnailCache {

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(2048)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun load(context: Context, uri: String): Bitmap? {
        cache.get(uri)?.let { return it }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uri))
            // 맨 앞은 검은 화면인 경우가 많아 1초 지점을 쓴다.
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
            frame?.also { cache.put(uri, it) }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
