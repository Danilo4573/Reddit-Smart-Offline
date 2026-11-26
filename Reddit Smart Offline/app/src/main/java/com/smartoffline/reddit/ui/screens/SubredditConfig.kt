package com.smartoffline.reddit.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smartoffline.reddit.data.SortMode
import com.smartoffline.reddit.data.SubredditSettings
import com.smartoffline.reddit.data.TimeRange
import com.smartoffline.reddit.ui.AppViewModel
import com.smartoffline.reddit.ui.components.SectionHeader
import com.smartoffline.reddit.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SubredditConfigScreen(vm: AppViewModel, name: String) {
    var s by remember { mutableStateOf<SubredditSettings?>(null) }
    LaunchedEffect(name) { s = withContext(Dispatchers.IO) { vm.repo.settings(name) } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("r/$name") },
            navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { s?.let { vm.saveSettings(it); vm.back() } }) { Icon(Icons.Filled.Check, "Save") } },
        )
    }) { pad ->
        val cur = s ?: return@Scaffold
        fun set(f: (SubredditSettings) -> SubredditSettings) { s = f(cur) }
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            SectionHeader("Post sorting")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortMode.entries.forEach { m -> FilterChip(selected = cur.sort == m, onClick = { set { it.copy(sort = m) } }, label = { Text(m.label) }) }
            }
            if (cur.sort == SortMode.TOP) {
                Spacer(Modifier.height(8.dp))
                Text("Top posts from", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeRange.entries.forEach { r -> FilterChip(selected = cur.timeRange == r, onClick = { set { it.copy(timeRange = r) } }, label = { Text(r.label) }) }
                }
            }

            SectionHeader("Number of posts to keep")
            NumberField(cur.postCount, 1, 1000, "posts") { v -> set { it.copy(postCount = v) } }
            Slider(value = cur.postCount.coerceAtMost(200).toFloat(), onValueChange = { v -> set { it.copy(postCount = v.toInt()) } }, valueRange = 1f..200f)
            Text("These are kept automatically and replaced as new posts arrive. Favorites from this subreddit are kept on top of this number.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionHeader("Synchronization")
            ToggleRow("Automatic synchronization", "Off: only when you tap Sync", cur.autoSync) { v -> set { it.copy(autoSync = v) } }
            if (cur.autoSync) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6, 12, 24, 48, 168).forEach { h ->
                        FilterChip(selected = cur.intervalHours == h, onClick = { set { it.copy(intervalHours = h) } }, label = { Text(Format.interval(h).removePrefix("Every ")) })
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Custom interval:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    NumberField(cur.intervalHours, 1, 720, "hours", Modifier.width(170.dp)) { v -> set { it.copy(intervalHours = v) } }
                }
                Text("Android decides the exact time to save battery, so a sync may run somewhat later than scheduled.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionHeader("Content to download")
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    ToggleRow("Images & galleries", null, cur.downloadImages) { v -> set { it.copy(downloadImages = v) } }
                    ToggleRow("Videos (with sound)", null, cur.downloadVideos) { v -> set { it.copy(downloadVideos = v) } }
                    ToggleRow("GIFs", null, cur.downloadGifs) { v -> set { it.copy(downloadGifs = v) } }
                    ToggleRow("Text & link posts", null, cur.downloadText) { v -> set { it.copy(downloadText = v) } }
                }
            }
            Text("Only posts of the selected types count toward the number of posts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))

            SectionHeader("Comments")
            ToggleRow("Download top comments", "The highest-ranked comments, not whole threads", cur.downloadComments) { v -> set { it.copy(downloadComments = v) } }
            if (cur.downloadComments) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Top-level comments", modifier = Modifier.weight(1f))
                    NumberField(cur.commentCount, 1, 200, "", Modifier.width(120.dp)) { v -> set { it.copy(commentCount = v) } }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Replies per comment", modifier = Modifier.weight(1f))
                    NumberField(cur.replyCount, 0, 20, "", Modifier.width(120.dp)) { v -> set { it.copy(replyCount = v) } }
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = { vm.saveSettings(cur); vm.back() }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Save settings") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun NumberField(value: Int, min: Int, max: Int, suffix: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter { it.isDigit() }.take(5)
            text.toIntOrNull()?.let { onChange(it.coerceIn(min, max)) }
        },
        modifier = modifier, singleLine = true,
        suffix = { if (suffix.isNotEmpty()) Text(suffix) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = if (text.toIntOrNull()?.let { it < min || it > max } != false) ({ Text("$min–$max") }) else null,
    )
}
