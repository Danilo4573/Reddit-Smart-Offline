package com.smartoffline.reddit.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartoffline.reddit.data.Comment
import com.smartoffline.reddit.data.MediaKind
import com.smartoffline.reddit.data.MediaState
import com.smartoffline.reddit.data.PostMedia
import com.smartoffline.reddit.ui.AppIcons
import com.smartoffline.reddit.ui.AppViewModel
import com.smartoffline.reddit.ui.Danger
import com.smartoffline.reddit.ui.Orange
import com.smartoffline.reddit.ui.Screen
import com.smartoffline.reddit.ui.Warn
import com.smartoffline.reddit.ui.components.LocalGif
import com.smartoffline.reddit.ui.components.LocalImage
import com.smartoffline.reddit.ui.components.LocalVideo
import com.smartoffline.reddit.ui.components.OfflineBadge
import com.smartoffline.reddit.ui.components.Pill
import com.smartoffline.reddit.util.Format

private data class PostBundle(
    val post: com.smartoffline.reddit.data.Post?,
    val protected: Boolean,
    val media: List<PostMedia>,
    val comments: List<Comment>,
)

/** Detailed post viewer. Reads only local data: works fully offline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostScreen(vm: AppViewModel, id: String) {
    val repo = vm.repo
    val bundle by remember(id) {
        repo.observe { PostBundle(repo.post(id), repo.isProtected(id), repo.mediaForPost(id), repo.comments(id)) }
    }.collectAsStateWithLifecycle(null)
    val ctx = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(bundle?.post?.let { "r/${it.subreddit}" } ?: "") },
            navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.Filled.ArrowBack, "Back") } },
            actions = {
                val b = bundle
                if (b?.post != null) {
                    IconButton(onClick = { vm.toggleFavorite(id, b.protected) }) {
                        Icon(if (b.protected) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favorite", tint = if (b.protected) Orange else MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { showInfo = true }) { Icon(Icons.Filled.Info, "Post information") }
                    IconButton(onClick = {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, b.post.permalink), null))
                    }) { Icon(Icons.Filled.Share, "Share link") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
                }
            },
        )
    }) { pad ->
        val b = bundle ?: return@Scaffold
        val p = b.post
        if (p == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("This post is no longer stored on this device.") }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("u/${p.author} · ${Format.ago(p.createdUtc)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(p.title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        p.flair?.let { Pill(it, MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer) }
                        if (p.over18) Pill("NSFW", Danger, Danger.copy(alpha = 0.12f))
                        if (p.removed) Pill("Removed on Reddit", Warn, Warn.copy(alpha = 0.12f))
                        OfflineBadge(p.offlineState)
                    }
                }
            }
            itemsIndexedMedia(b.media, onOpen = { index -> vm.navigate(Screen.MediaView(id, index)) })
            p.mediaNote?.let { note ->
                item { Notice(note) }
            }
            if (p.selftext.isNotBlank()) item {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Markdown(p.selftext) }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("${Format.count(p.score.toLong())} points", icon = AppIcons.ArrowUp)
                        if (p.upvoteRatio > 0) Pill("${(p.upvoteRatio * 100).toInt()}% upvoted")
                        Pill("${p.numComments} comments", icon = AppIcons.Comment)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.permalink))) }) { Text("Open on Reddit") }
                        if (p.type == com.smartoffline.reddit.data.PostType.LINK && p.url.startsWith("http")) {
                            OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url))) }) {
                                Icon(AppIcons.Link, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(p.domain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    if (b.comments.isEmpty()) "No comments saved" else "Top comments (${b.comments.size} saved of ${p.numComments})",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp),
                )
            }
            items(b.comments, key = { it.id }) { c -> CommentRow(c) }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete from device?") },
        text = { Text("The post, its media and comments will be removed from this device. If it is still among the selected posts of its subreddit, it may be downloaded again at the next sync.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deletePost(id); vm.back() }) { Text("Delete", color = Danger) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    val p = bundle?.post
    if (showInfo && p != null) AlertDialog(
        onDismissRequest = { showInfo = false },
        title = { Text("Original post") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Subreddit: r/${p.subreddit}")
                Text("Author: u/${p.author}")
                Text("Posted: ${Format.date(p.createdUtc)}")
                Text("Score: ${p.score} · ${p.numComments} comments")
                Text("Type: ${p.type.label}")
                Text("Downloaded: ${Format.date(p.downloadedAt)}")
                Text("Metadata updated: ${Format.date(p.updatedAt)}")
                Text("Status: ${p.offlineState.label}")
                Text(if (bundle?.protected == true) "Protected — never removed automatically" else if (p.inSelection) "Kept automatically (rank ${p.selectionRank + 1})" else "Not in the current selection")
                Text("Link: ${p.permalink}", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Close") } },
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedMedia(media: List<PostMedia>, onOpen: (Int) -> Unit) {
    media.forEachIndexed { index, pm ->
        item(key = "m${pm.media.id}") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                MediaBlock(pm, onOpen = { onOpen(index) })
                pm.caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                if (media.size > 1) Text("${index + 1} of ${media.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MediaBlock(pm: PostMedia, onOpen: () -> Unit) {
    val m = pm.media
    val ratio = if (m.width > 0 && m.height > 0) (m.width.toFloat() / m.height).coerceIn(0.5f, 2.5f) else 16f / 9
    val shape = RoundedCornerShape(14.dp)
    if (m.state != MediaState.COMPLETE || m.location == null) {
        Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val (title, color) = when (m.state) {
                    MediaState.UNAVAILABLE -> "Media unavailable" to Danger
                    MediaState.FAILED -> "Download failed — will retry" to Warn
                    MediaState.WAITING_STORAGE -> "Waiting for storage space" to Warn
                    MediaState.DOWNLOADING -> "Downloading…" to MaterialTheme.colorScheme.primary
                    MediaState.SKIPPED -> "Not downloaded" to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> "Waiting to download" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(when (m.kind) { MediaKind.IMAGE -> AppIcons.Image; MediaKind.VIDEO -> AppIcons.Video; MediaKind.GIF -> AppIcons.Gif }, null, tint = color)
                    Spacer(Modifier.width(8.dp))
                    Text(title, style = MaterialTheme.typography.titleSmall, color = color)
                }
                m.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
        return
    }
    val isVideoFile = m.mime?.startsWith("video") == true
    when {
        m.kind == MediaKind.IMAGE -> LocalImage(m.location, Modifier.fillMaxWidth().aspectRatio(ratio).clip(shape).clickable(onClick = onOpen), maxDim = 1440)
        m.kind == MediaKind.GIF && !isVideoFile -> LocalGif(m.location, Modifier.fillMaxWidth().aspectRatio(ratio).clip(shape).clickable(onClick = onOpen))
        else -> {
            Box(Modifier.fillMaxWidth().heightIn(max = 560.dp).aspectRatio(ratio).clip(shape).background(Color.Black), contentAlignment = Alignment.Center) {
                LocalVideo(m.location, Modifier.fillMaxSize(), gifLike = m.kind == MediaKind.GIF)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    (if (m.kind == MediaKind.GIF) "GIF" else if (m.hasAudio) "Video with sound" else "Video (no sound track)") + " · ${Format.bytes(m.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpen) { Text("Full screen") }
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CommentRow(c: Comment) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 16.dp + (14 * c.depth).dp, end = 16.dp)) {
        if (c.depth > 0) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(if (c.depth == 1) Orange.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("u/${c.author}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("  ·  ${Format.count(c.score.toLong())} pts  ·  ${Format.ago(c.createdUtc)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(2.dp))
            Markdown(c.body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
