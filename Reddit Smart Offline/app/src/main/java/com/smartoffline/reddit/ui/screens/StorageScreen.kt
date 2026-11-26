package com.smartoffline.reddit.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartoffline.reddit.ui.AppIcons
import com.smartoffline.reddit.ui.AppViewModel
import com.smartoffline.reddit.ui.Danger
import com.smartoffline.reddit.ui.Orange
import com.smartoffline.reddit.ui.OrangeSoft
import com.smartoffline.reddit.ui.Screen
import com.smartoffline.reddit.ui.components.InfoBanner
import com.smartoffline.reddit.ui.components.SectionHeader
import com.smartoffline.reddit.ui.components.SettingRow
import com.smartoffline.reddit.ui.components.StatTile
import com.smartoffline.reddit.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(vm: AppViewModel) {
    val repo = vm.repo
    val stats by remember { repo.observe { repo.storageStats() } }.collectAsStateWithLifecycle(null)
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Storage") }, navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.Filled.ArrowBack, "Back") } })
    }) { pad ->
        val s = stats ?: return@Scaffold
        val limit = prefs.storageLimitMb * 1024 * 1024
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp)) {
            item {
                if (prefs.storageUnavailable) InfoBanner("The storage location is unavailable. Choose another one in Settings.", color = Danger, actionLabel = "Settings") { vm.selectTab(Screen.Settings) }
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Total used", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Format.bytes(s.totalBytes), style = MaterialTheme.typography.headlineMedium)
                        Text(if (limit > 0) "of ${Format.bytes(limit)} limit" else "No storage limit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        // Stacked bar: protected vs automatic (vs limit when set).
                        val denom = maxOf(if (limit > 0) limit else s.totalBytes, 1L).toFloat()
                        Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            val pw = (s.protectedBytes / denom).coerceIn(0f, 1f)
                            val aw = (s.autoBytes / denom).coerceIn(0f, 1f - pw)
                            if (pw > 0f) Box(Modifier.fillMaxHeight().weight(pw).background(Orange))
                            if (aw > 0f) Box(Modifier.fillMaxHeight().weight(aw).background(OrangeSoft.copy(alpha = 0.6f)))
                            val rest = 1f - pw - aw
                            if (rest > 0f) Spacer(Modifier.weight(rest))
                        }
                        Spacer(Modifier.height(8.dp))
                        Legend("Protected posts", Format.bytes(s.protectedBytes), Orange)
                        Legend("Automatically managed", Format.bytes(s.autoBytes), OrangeSoft.copy(alpha = 0.6f))
                        Spacer(Modifier.height(4.dp))
                        Text("Location: ${vm.describeStorage(prefs.storage)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Posts", s.posts.toString(), AppIcons.Text, Modifier.weight(1f))
                    StatTile("Images", s.images.toString(), AppIcons.Image, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Videos", s.videos.toString(), AppIcons.Video, Modifier.weight(1f))
                    StatTile("GIFs", s.gifs.toString(), AppIcons.Gif, Modifier.weight(1f))
                }
                if (s.pendingMedia > 0 || s.failedMedia > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text("${s.pendingMedia} files waiting · ${s.failedMedia} failed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SectionHeader("By subreddit")
                if (s.perSubreddit.isEmpty()) Text("No media stored yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(s.perSubreddit, key = { it.first }) { (sub, bytes) ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("r/$sub", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(Format.bytes(bytes), style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { confirm = "Delete all non-favorite posts of r/$sub from this device?" to { vm.clearUnprotected(sub) } }) {
                            Icon(Icons.Filled.Delete, "Clear r/$sub")
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth((bytes.toFloat() / maxOf(s.totalBytes, 1)).coerceIn(0.01f, 1f)).fillMaxHeight().background(Orange))
                    }
                }
            }
            item {
                SectionHeader("Cleanup")
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column {
                        SettingRow("Remove unused files", "Files no longer used by any saved post", Icons.Filled.Refresh, onClick = { vm.cleanupOrphans() })
                        SettingRow("Retry failed downloads", "${s.failedMedia} failed", AppIcons.Download, onClick = { vm.retryFailed() })
                        SettingRow("Clear failed downloads", "Stop retrying files that keep failing", Icons.Filled.Delete, onClick = { vm.clearFailed() })
                        SettingRow("Delete posts older than 30 days", "Favorites are kept", AppIcons.History,
                            onClick = { confirm = "Delete non-favorite posts downloaded more than 30 days ago?" to { vm.deleteOlderThan(30) } })
                        SettingRow("Delete all non-favorite posts", "Frees space; next sync downloads the current selection again", Icons.Filled.Favorite,
                            onClick = { confirm = "Delete every post that isn't a favorite from this device?" to { vm.clearUnprotected(null) } })
                    }
                }
            }
        }
    }
    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Free up space") }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Delete", color = Danger) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Legend(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelMedium)
    }
}
