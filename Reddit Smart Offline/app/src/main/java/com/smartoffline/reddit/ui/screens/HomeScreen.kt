package com.smartoffline.reddit.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.smartoffline.reddit.data.LibrarySort
import com.smartoffline.reddit.ui.AppIcons
import com.smartoffline.reddit.ui.AppViewModel
import com.smartoffline.reddit.ui.Danger
import com.smartoffline.reddit.ui.Orange
import com.smartoffline.reddit.ui.Screen
import com.smartoffline.reddit.ui.components.EmptyState
import com.smartoffline.reddit.ui.components.InfoBanner
import com.smartoffline.reddit.ui.components.PostCard
import com.smartoffline.reddit.ui.components.SectionHeader
import com.smartoffline.reddit.ui.components.SubIcon
import com.smartoffline.reddit.util.Format

@Composable
fun HomeScreen(vm: AppViewModel) {
    val repo = vm.repo
    val recent by remember { repo.observe { repo.listPosts(sort = LibrarySort.RECENT_DOWNLOAD, limit = 8) } }.collectAsStateWithLifecycle(emptyList())
    val subs by remember { repo.observe { repo.subredditOverviews() } }.collectAsStateWithLifecycle(emptyList())
    val stats by remember { repo.observe { repo.storageStats() } }.collectAsStateWithLifecycle(null)
    val lastSuccess by remember { repo.observe { repo.lastSuccessfulSync() } }.collectAsStateWithLifecycle(0L)
    val progress by vm.progress.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val manual by vm.manualWork.collectAsStateWithLifecycle()
    val waiting = !progress.running && manual.any { it.state == WorkInfo.State.ENQUEUED }

    LazyColumn(Modifier.statusBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Smart Offline", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (prefs.username != null && !prefs.guestMode) "Signed in as u/${prefs.username}" else "Guest mode",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.navigate(Screen.SyncDashboard) }) {
                    Icon(AppIcons.History, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Activity")
                }
            }
            if (prefs.storageUnavailable) InfoBanner("The storage location is unavailable. Downloads are paused.", color = Danger, actionLabel = "Fix") { vm.selectTab(Screen.Settings) }
            if (prefs.authExpired) InfoBanner("Your Reddit sign-in expired. Using guest mode until you sign in again.", icon = Icons.Filled.Lock, actionLabel = "Sign in") { vm.selectTab(Screen.Settings) }
            Spacer(Modifier.height(12.dp))
        }

        item {
            // Hero card: storage + sync.
            Surface(shape = MaterialTheme.shapes.large, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.background(Brush.linearGradient(listOf(Orange, Color(0xFFB8300A)))).padding(20.dp)
                ) {
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text("Saved on this device", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
                            Text(Format.bytes(stats?.totalBytes ?: 0), color = Color.White, style = MaterialTheme.typography.headlineMedium)
                            Text("${stats?.posts ?: 0} posts · ${subs.size} subreddits", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                        }
                        Icon(AppIcons.Offline, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    if (progress.running) {
                        Text(
                            (if (progress.paused) "Paused · " else "") + (progress.currentSubreddit?.let { "r/$it · " } ?: "") + progress.operation,
                            color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (progress.mediaTotal > 0) LinearProgressIndicator(
                            progress = { progress.mediaDone.toFloat() / progress.mediaTotal }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                            color = Color.White, trackColor = Color.White.copy(alpha = 0.25f),
                        ) else LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)), color = Color.White, trackColor = Color.White.copy(alpha = 0.25f))
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { vm.navigate(Screen.SyncDashboard) }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Orange)) {
                            Text("View download activity")
                        }
                    } else {
                        Text(
                            if (waiting) "Waiting for a suitable network connection…" else "Last successful sync: ${Format.ago(lastSuccess)}",
                            color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { vm.syncAll() }, modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Orange),
                        ) {
                            Icon(AppIcons.Sync, null); Spacer(Modifier.width(8.dp)); Text("Sync all subreddits now")
                        }
                    }
                }
            }
        }

        if (subs.isEmpty()) {
            item {
                EmptyState(AppIcons.Download, "Nothing downloaded yet",
                    "Add subreddits and choose how many posts to keep. They'll be downloaded automatically and stay readable offline.") {
                    Button(onClick = { vm.navigate(Screen.AddSubreddit) }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add a subreddit") }
                }
            }
            return@LazyColumn
        }

        item {
            SectionHeader("Recently synchronized")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(subs.sortedByDescending { it.sub.lastSync }, key = { it.sub.name }) { o ->
                    Surface(
                        onClick = { vm.navigate(Screen.SubredditPosts(o.sub.name)) },
                        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column(Modifier.width(150.dp).padding(12.dp)) {
                            SubIcon(o.sub.name, o.sub.iconLocation, 32)
                            Spacer(Modifier.height(8.dp))
                            Text("r/${o.sub.name}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${o.postCount} posts · ${Format.bytes(o.bytes)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(o.sub.lastStatus?.let { "$it · ${Format.ago(o.sub.lastSync)}" } ?: "Not synced yet",
                                style = MaterialTheme.typography.labelSmall, color = if (o.sub.lastStatus == "Failed") Danger else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        }

        item {
            SectionHeader("Upcoming synchronizations")
            Text("Approximate — Android runs background work when battery and network conditions allow.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    val upcoming = subs.sortedBy { if (it.settings.autoSync) it.sub.lastSuccess + it.settings.intervalHours * 3_600_000L else Long.MAX_VALUE }.take(6)
                    upcoming.forEach { o ->
                        Row(Modifier.fillMaxWidth().clickable { vm.navigate(Screen.SubredditConfig(o.sub.name)) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            SubIcon(o.sub.name, o.sub.iconLocation, 28)
                            Spacer(Modifier.width(10.dp))
                            Text("r/${o.sub.name}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(
                                when {
                                    !prefs.autoDownloads -> "Auto off"
                                    !o.settings.autoSync -> "Manual only"
                                    o.sub.lastSuccess == 0L -> "pending"
                                    else -> Format.inFuture(o.sub.lastSuccess + o.settings.intervalHours * 3_600_000L)
                                },
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionHeader("Recently downloaded") {
                TextButton(onClick = { vm.selectTab(Screen.Downloads) }) { Text("See all") }
            }
        }
        if (recent.isEmpty()) item {
            Text("Downloaded posts appear here after the first synchronization.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        items(recent, key = { it.post.id }) { item ->
            PostCard(item, onClick = { vm.navigate(Screen.PostView(item.post.id)) }, onToggleFavorite = { vm.toggleFavorite(item.post.id, item.protected) },
                modifier = Modifier.padding(bottom = 10.dp))
        }
        item {
            OutlinedButton(onClick = { vm.navigate(Screen.StorageDashboard) }, modifier = Modifier.fillMaxWidth()) {
                Icon(AppIcons.Storage, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Storage dashboard")
            }
        }
    }
}
