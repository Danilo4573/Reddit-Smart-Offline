package com.smartoffline.reddit.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaPlayer
import android.os.Build
import android.util.LruCache
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.MaterialTheme
import com.smartoffline.reddit.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Decodes images from local storage only (never from the network), downsampled and cached. */
object ImageCache {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    suspend fun local(location: String, maxDim: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$location@$maxDim"
        cache.get(key)?.let { return@withContext it }
        val storage = App.get().storage
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        storage.openInput(location)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val bmp = storage.openInput(location)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        bmp?.also { cache.put(key, it) }
    }

    /** Online-only images (search results). */
    suspend fun remote(url: String, maxDim: Int = 256): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(url)?.let { return@withContext it }
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            val bytes = c.inputStream.use { it.readBytes() }
            c.disconnect()
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
            var sample = 1
            while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= maxDim) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.also { cache.put(url, it) }
        } catch (_: Exception) {
            null
        }
    }
}

@Composable
fun LocalImage(location: String?, modifier: Modifier = Modifier, maxDim: Int = 1080, contentScale: ContentScale = ContentScale.Crop) {
    val bitmap by produceState<ImageBitmap?>(location?.let { ImageCache.get("$it@$maxDim")?.asImageBitmap() }, location, maxDim) {
        if (location != null && value == null) value = ImageCache.local(location, maxDim)?.asImageBitmap()
    }
    val b = bitmap
    if (b != null) Image(b, null, modifier, contentScale = contentScale)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}

@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val bitmap by produceState<ImageBitmap?>(null, url) {
        if (url != null) value = ImageCache.remote(url)?.asImageBitmap()
    }
    val b = bitmap
    if (b != null) Image(b, null, modifier, contentScale = contentScale)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}

/** Animated GIF file played from local storage (first frame only on Android 8). */
@Composable
fun LocalGif(location: String, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < 28) { LocalImage(location, modifier, contentScale = ContentScale.Fit); return }
    val storage = App.get().storage
    AndroidView(
        factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true } },
        modifier = modifier,
        update = { view ->
            if (view.tag == location) return@AndroidView
            view.tag = location
            try {
                val src = storage.file(location)?.let { ImageDecoder.createSource(it) }
                    ?: ImageDecoder.createSource(view.context.contentResolver, storage.uri(location))
                val d = ImageDecoder.decodeDrawable(src)
                view.setImageDrawable(d)
                (d as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; start() }
            } catch (_: Exception) {
            }
        },
    )
}

/**
 * Plays a downloaded video from local storage. GIF-like clips loop silently without controls.
 */
@Composable
fun LocalVideo(location: String, modifier: Modifier = Modifier, gifLike: Boolean = false, autoPlay: Boolean = gifLike) {
    val uri = App.get().storage.uri(location)
    val holder = androidx.compose.runtime.remember { arrayOfNulls<VideoView>(1) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val frame = FrameLayout(ctx)
            val v = VideoView(ctx)
            frame.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
            if (!gifLike) {
                val mc = MediaController(ctx)
                mc.setAnchorView(v)
                v.setMediaController(mc)
            }
            v.setOnPreparedListener { mp: MediaPlayer ->
                mp.isLooping = gifLike
                if (gifLike) mp.setVolume(0f, 0f)
                if (autoPlay) v.start() else v.seekTo(1)
            }
            v.setOnErrorListener { _, _, _ -> true }
            v.setVideoURI(uri)
            holder[0] = v
            frame
        },
    )
    DisposableEffect(location) {
        onDispose { holder[0]?.stopPlayback() }
    }
}

@Composable
fun CenterBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Box(modifier, contentAlignment = Alignment.Center) { content() }
