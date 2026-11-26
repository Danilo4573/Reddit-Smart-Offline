package com.smartoffline.reddit.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartoffline.reddit.data.MediaKind
import com.smartoffline.reddit.data.MediaState
import com.smartoffline.reddit.ui.AppViewModel
import com.smartoffline.reddit.ui.components.LocalGif
import com.smartoffline.reddit.ui.components.LocalImage
import com.smartoffline.reddit.ui.components.LocalVideo
import androidx.compose.ui.unit.dp

/** Full-screen viewer for a post's downloaded images, GIFs and videos (swipe between gallery items, pinch to zoom). */
@Composable
fun MediaViewerScreen(vm: AppViewModel, postId: String, start: Int) {
    val media by remember(postId) { vm.repo.observe { vm.repo.mediaForPost(postId) } }.collectAsStateWithLifecycle(null)
    val list = media ?: return
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (list.size - 1).coerceAtLeast(0))) { list.size }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 0) { page ->
            val m = list[page].media
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val loc = m.location
                when {
                    m.state != MediaState.COMPLETE || loc == null -> Text(m.error ?: "Not downloaded yet", color = Color.White, modifier = Modifier.padding(24.dp))
                    m.kind == MediaKind.IMAGE -> ZoomableImage(loc)
                    m.kind == MediaKind.GIF && m.mime?.startsWith("video") != true -> LocalGif(loc, Modifier.fillMaxWidth())
                    else -> if (pager.currentPage == page) LocalVideo(loc, Modifier.fillMaxSize(), gifLike = m.kind == MediaKind.GIF, autoPlay = true)
                }
            }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
            if (list.size > 1) Text("${pager.currentPage + 1} / ${list.size}", color = Color.White, style = MaterialTheme.typography.titleMedium)
            list.getOrNull(pager.currentPage)?.caption?.let { Text("  $it", color = Color.White.copy(alpha = 0.8f), maxLines = 1) }
        }
    }
}

@Composable
private fun ZoomableImage(location: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f })
            }
            .pointerInput(Unit) {
                // Only consume gestures while zooming so the pager can still be swiped at normal size.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        LocalImage(
            location,
            Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            maxDim = 2560, contentScale = ContentScale.Fit,
        )
    }
}
