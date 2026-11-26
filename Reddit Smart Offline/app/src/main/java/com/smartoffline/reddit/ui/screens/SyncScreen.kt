package com.smartoffline.reddit.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.smartoffline.reddit.data.MediaState
import com.smartoffline.reddit.ui.AppIcons
import com.smartoffline.reddit.ui.AppViewModel
import com.smartoffline.reddit.ui.Danger
import com.smartoffline.reddit.ui.Screen
import com.smartoffline.reddit.ui.Success
import com.smartoffline.reddit.ui.Warn
import com.smartoffline.reddit.ui.components.Pill
import com.smartoffline.reddit.ui.components.SectionHeader
import com.smartoffline.reddit.util.Format

/** Synchronization dashboard: live progress, controls, download queue and history. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(vm: AppViewModel) {
    val repo = vm.repo
    val p by vm.progress.collectAsStateWithLifecycle()
    val manual by vm.manualWork.collectAsStateWithLifecycle()
    val history by remember { repo.observe { repo.history(40) } }.collectAsStateWithLifecycle(emptyList())
    val queue by remember { repo.observe { repo.downloadQueue(100) } }.collectAsStateWithLifecycle(emptyList())
    val subs by remember { repo.observe { repo.subreddits() } }.collectAsStateWithLifecycle(emptyList())
    val lastSuccess by remember { repo.observe { repo.lastSuccessfulSync() } }.collectAsStateWithLifecycle(0L)
    val waiting = !p.running && manual.any { it.state == WorkInfo.State.ENQUEUED }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Download activity") }, navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.Filled.ArrowBack, "Back") } })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp)) {
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (label, color) = when {
                                p.running && p.paused -> "Paused" to Warn
                                p.running -> "Synchronizing" to MaterialTheme.colorScheme.primary
                                waiting -> "Waiting for network" to Warn
                                else -> "Idle" to MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(label, style = MaterialTheme.typography.titleLarge, color = color, modifier = Modifier.weight(1f))
                            if (p.running) Text("Started ${Format.ago(p.startedAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Last successful sync: ${Format.ago(lastSuccess)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (p.running) {
                            Spacer(Modifier.height(12.dp))
                            Text(p.currentSubreddit?.let { "r/$it" } ?: "All downloads", style = MaterialTheme.typography.titleMedium)
                            Text(p.operation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            p.fileName?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Spacer(Modifier.height(8.dp))
                            if (p.fileTotal > 0) {
                                LinearProgressIndicator(progress = { (p.fileDone.toFloat() / p.fileTotal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                                Text("${Format.bytes(p.fileDone)} of ${Format.bytes(p.fileTotal)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                            if (p.mediaTotal > 0) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(progress = { p.mediaDone.toFloat() / p.mediaTotal }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)), color = Success)
                                Text("Media ${p.mediaDone} of ${p.mediaTotal}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (p.queue.isNotEmpty()) Text("Next: " + p.queue.joinToString { "r/$it" }, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (p.running || p.startedAt > 0) {
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Metric("Checked", p.postsChecked.toString())
                                Metric("New", p.newPosts.toString())
                                Metric("Saved", p.downloadedPosts.toString())
                                Metric("Failed", p.failed.toString())
                                Metric("Size", Format.bytes(p.bytes))
                            }
                        }
                        p.lastMessage?.let { if (!p.running) Text(it, style = MaterialTheme.typography.bodySmall, color = Warn, modifier = Modifier.padding(top = 8.dp)) }
                        Spacer(Modifier.height(14.dp))
                        if (p.running) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (p.paused) Button(onClick = { vm.resume() }) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Resume") }
                            else FilledTonalButton(onClick = { vm.pause() }) { Icon(AppIcons.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Pause") }
                            OutlinedButton(onClick = { vm.cancelSync() }) { Icon(Icons.Filled.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Cancel") }
                        } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { vm.syncAll() }) { Icon(AppIcons.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Sync all") }
                            OutlinedButton(onClick = { vm.retryFailed() }) { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Retry failed") }
                        }
                    }
                }
            }
            if (subs.isNotEmpty()) item {
                SectionHeader("Synchronize one subreddit")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(subs, key = { it.name }) { s -> AssistChip(onClick = { vm.syncSub(s.name) }, label = { Text("r/${s.name}") }, enabled = !p.running, leadingIcon = { Icon(AppIcons.Sync, null, Modifier.size(16.dp)) }) }
                }
            }
            item { SectionHeader("Download queue (${queue.size})") }
            if (queue.isEmpty()) item { Text("All media downloaded.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
            items(queue, key = { "q${it.mediaId}" }) { t ->
                Surface(onClick = { t.postId?.let { vm.navigate(Screen.PostView(it)) } }, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.postTitle ?: "Media", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            val (label, color) = when (t.state) {
                                MediaState.DOWNLOADING -> "Downloading" to MaterialTheme.colorScheme.primary
                                MediaState.FAILED -> "Failed (${t.attempts})" to Danger
                                MediaState.UNAVAILABLE -> "Unavailable" to Danger
                                MediaState.WAITING_STORAGE -> "No space" to Warn
                                else -> "Queued" to MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Pill(label, color, color.copy(alpha = 0.12f))
                        }
                        Text("${t.subreddit?.let { "r/$it · " } ?: ""}${t.kind.name.lowercase()}" + if (t.bytesDone > 0) " · ${Format.bytes(t.bytesDone)}${if (t.bytesTotal > 0) " / ${Format.bytes(t.bytesTotal)}" else ""}" else "",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (t.state == MediaState.DOWNLOADING && t.bytesTotal > 0) LinearProgressIndicator(progress = { t.bytesDone.toFloat() / t.bytesTotal }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        t.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
                    }
                }
            }
            item {
                if (queue.any { it.state == MediaState.UNAVAILABLE }) OutlinedButton(onClick = { vm.retryUnavailable() }, modifier = Modifier.padding(top = 4.dp)) { Text("Try unavailable files again") }
                SectionHeader("History")
            }
            if (history.isEmpty()) item { Text("No synchronizations yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(history, key = { "h${it.id}" }) { h ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    val color = when (h.status) { "SUCCESS" -> Success; "PARTIAL", "CANCELLED", "INTERRUPTED" -> Warn; "RUNNING" -> MaterialTheme.colorScheme.primary; else -> Danger }
                    Surface(Modifier.size(10.dp).padding(top = 4.dp), shape = RoundedCornerShape(5.dp), color = color) {}
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${h.subreddit?.let { "r/$it" } ?: "All downloads"} · ${h.trigger}", style = MaterialTheme.typography.bodyMedium)
                        Text("${Format.date(h.startedAt)} · ${h.status.lowercase().replaceFirstChar { it.uppercase() }}" +
                            if (h.finishedAt > h.startedAt) " · ${(h.finishedAt - h.startedAt) / 1000}s" else "",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${h.postsChecked} checked · ${h.newPosts} new · ${h.downloadedMedia} files · ${h.failedMedia} failed · ${h.removedPosts} removed · ${Format.bytes(h.bytes)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        h.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = color) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
