package com.smartoffline.reddit.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartoffline.reddit.App
import com.smartoffline.reddit.net.SubredditInfo
import com.smartoffline.reddit.ui.AppIcons
import com.smartoffline.reddit.ui.AppViewModel
import com.smartoffline.reddit.ui.Danger
import com.smartoffline.reddit.ui.Screen
import com.smartoffline.reddit.ui.components.EmptyState
import com.smartoffline.reddit.ui.components.InfoBanner
import com.smartoffline.reddit.ui.components.Pill
import com.smartoffline.reddit.ui.components.PostCard
import com.smartoffline.reddit.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Composable
fun SearchScreen(vm: AppViewModel) {
    var online by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var postsTab by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var subs by remember { mutableStateOf<List<SubredditInfo>>(emptyList()) }
    var posts by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val existing by remember { vm.repo.observe { vm.repo.subreddits().map { it.name.lowercase() }.toSet() } }.collectAsStateWithLifecycle(emptySet())
    val ctx = LocalContext.current

    val offline by remember {
        snapshotFlow { query }.debounce(200).flatMapLatest { q -> vm.repo.observe { if (q.isBlank()) emptyList() else vm.repo.listPosts(search = q, limit = 100) } }
    }.collectAsStateWithLifecycle(emptyList())

    fun runOnline() {
        val q = query.trim()
        if (q.isEmpty()) return
        loading = true; error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val r = App.get().reddit
                    if (postsTab) posts = r.searchPosts(q, null, prefs.showNsfw) else subs = r.searchSubreddits(q, prefs.showNsfw)
                }
            } catch (e: Exception) {
                error = e.message ?: "Search failed. Check your connection."
            }
            loading = false
        }
    }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Search", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !online, onClick = { online = false }, shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { Icon(AppIcons.Offline, null, Modifier.size(18.dp)) }) { Text("Offline library") }
                SegmentedButton(selected = online, onClick = { online = true }, shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { Icon(AppIcons.Cloud, null, Modifier.size(18.dp)) }) { Text("Reddit (online)") }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(if (online) "Search Reddit communities or posts" else "Search your downloaded posts") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (online) runOnline() }),
                shape = MaterialTheme.shapes.large,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (online) "Online mode: results come from Reddit and need an internet connection." else "Offline mode: searches titles, text, authors and saved comments on this device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
            )
            if (online) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilterChip(selected = !postsTab, onClick = { postsTab = false; runOnline() }, label = { Text("Communities") })
                    FilterChip(selected = postsTab, onClick = { postsTab = true; runOnline() }, label = { Text("Posts") })
                }
                if (loading) Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                error?.let { InfoBanner(it, color = Danger) }
            }
        }

        if (!online) {
            if (query.isBlank()) item { EmptyState(Icons.Filled.Search, "Search offline", "Find any downloaded post, even without a connection.") }
            else if (offline.isEmpty()) item { EmptyState(Icons.Filled.Search, "No matches", "Nothing downloaded matches “$query”.") }
            items(offline, key = { "o" + it.post.id }) { item ->
                PostCard(item, onClick = { vm.navigate(Screen.PostView(item.post.id)) }, onToggleFavorite = { vm.toggleFavorite(item.post.id, item.protected) })
            }
        } else if (!postsTab) {
            items(subs, key = { "s" + it.name }) { info -> SubResult(info, info.name.lowercase() in existing) { vm.addSubreddit(info) } }
        } else {
            items(posts, key = { "p" + it.optString("id") }) { json ->
                Card(shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("r/${json.optString("subreddit")} · u/${json.optString("author")} · ${Format.ago(json.optLong("created_utc") * 1000)}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Spacer(Modifier.height(4.dp))
                        Text(json.optString("title"), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(Format.count(json.optLong("score")), icon = AppIcons.ArrowUp)
                            Pill(Format.count(json.optLong("num_comments")), icon = AppIcons.Comment)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.reddit.com" + json.optString("permalink")))) }) { Text("Open") }
                            FilledTonalButton(onClick = { vm.savePostOffline(json) }) {
                                Icon(AppIcons.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Save")
                            }
                        }
                    }
                }
            }
        }
    }
}
