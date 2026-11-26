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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartoffline.reddit.App
import com.smartoffline.reddit.data.SortMode
import com.smartoffline.reddit.data.SubredditOverview
import com.smartoffline.reddit.net.AccessMode
import com.smartoffline.reddit.net.SubredditInfo
import com.smartoffline.reddit.ui.AppIcons
import com.smartoffline.reddit.ui.AppViewModel
import com.smartoffline.reddit.ui.Danger
import com.smartoffline.reddit.ui.Screen
import com.smartoffline.reddit.ui.Success
import com.smartoffline.reddit.ui.components.EmptyState
import com.smartoffline.reddit.ui.components.InfoBanner
import com.smartoffline.reddit.ui.components.Pill
import com.smartoffline.reddit.ui.components.RemoteImage
import com.smartoffline.reddit.ui.components.SubIcon
import com.smartoffline.reddit.util.Format
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip

@Composable
fun SubredditsScreen(vm: AppViewModel) {
    val repo = vm.repo
    val subs by remember { repo.observe { repo.subredditOverviews() } }.collectAsStateWithLifecycle(null)
    val progress by vm.progress.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf<String?>(null) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { vm.navigate(Screen.AddSubreddit) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add") })
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).statusBarsPadding(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Subreddits", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.syncAll() }, enabled = !progress.running && !subs.isNullOrEmpty()) {
                        Icon(AppIcons.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Sync all")
                    }
                }
                Text("Each community keeps its own number of posts and schedule.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val list = subs
            if (list != null && list.isEmpty()) item {
                EmptyState(Icons.Filled.Search, "No subreddits yet", "Search for a community or type its name to start downloading posts automatically.") {
                    Button(onClick = { vm.navigate(Screen.AddSubreddit) }) { Text("Add a subreddit") }
                }
            }
            items(list.orEmpty(), key = { it.sub.name }) { o ->
                SubredditCard(o, syncing = progress.running && progress.currentSubreddit.equals(o.sub.name, true),
                    onOpen = { vm.navigate(Screen.SubredditPosts(o.sub.name)) },
                    onEdit = { vm.navigate(Screen.SubredditConfig(o.sub.name)) },
                    onSync = { vm.syncSub(o.sub.name) },
                    onRemove = { confirmRemove = o.sub.name })
            }
        }
    }
    confirmRemove?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove r/$name?") },
            text = { Text("Its downloaded posts and media will be deleted from this device. Posts you protected as favorites are kept.") },
            confirmButton = { TextButton(onClick = { vm.removeSubreddit(name); confirmRemove = null }) { Text("Remove", color = Danger) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SubredditCard(o: SubredditOverview, syncing: Boolean, onOpen: () -> Unit, onEdit: () -> Unit, onSync: () -> Unit, onRemove: () -> Unit) {
    val s = o.settings
    Card(onClick = onOpen, shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SubIcon(o.sub.name, o.sub.iconLocation, 44)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("r/${o.sub.name}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${o.postCount} saved · ${Format.bytes(o.bytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (syncing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else IconButton(onClick = onSync) { Icon(AppIcons.Sync, "Sync now") }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit settings") }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, "Remove") }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(s.sort.label + if (s.sort == SortMode.TOP) " · ${s.timeRange.label}" else "")
                Pill("${s.postCount} posts")
                Pill(if (s.autoSync) Format.interval(s.intervalHours) else "Manual sync")
            }
            Spacer(Modifier.height(8.dp))
            val status = when {
                syncing -> "Synchronizing now…"
                o.sub.lastSync == 0L -> "Not synchronized yet"
                else -> "${o.sub.lastStatus ?: "Synced"} · ${Format.ago(o.sub.lastSync)}"
            }
            val color = when (o.sub.lastStatus) { "Failed" -> Danger; "Up to date" -> Success; else -> MaterialTheme.colorScheme.onSurfaceVariant }
            Text(status, style = MaterialTheme.typography.labelMedium, color = color)
            o.sub.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Danger, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSubredditScreen(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<SubredditInfo>>(emptyList()) }
    var subscribed by remember { mutableStateOf<List<SubredditInfo>?>(null) }
    val existing by remember { vm.repo.observe { vm.repo.subreddits().map { it.name.lowercase() }.toSet() } }.collectAsStateWithLifecycle(emptySet())
    val isAccount = vm.accessMode() == AccessMode.ACCOUNT

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        loading = true; error = null
        scope.launch {
            val r = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { App.get().reddit.searchSubreddits(q, prefs.showNsfw) } }
            loading = false
            r.onSuccess { results = it; if (it.isEmpty()) error = "No communities found for “$q”." }
                .onFailure { error = it.message ?: "Search failed" }
        }
    }

    fun addExact() {
        val q = query.trim()
        if (q.isEmpty()) return
        loading = true; error = null
        scope.launch {
            vm.lookupSubreddit(q).onSuccess { vm.addSubreddit(it); vm.back() }.onFailure { error = it.message }
            loading = false
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Add subreddit") }, navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.Filled.ArrowBack, "Back") } })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp)) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Community name or search") }, placeholder = { Text("e.g. wallpapers") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { search() }, enabled = !loading && query.isNotBlank()) { Text("Search") }
                    FilledTonalButton(onClick = { addExact() }, enabled = !loading && query.isNotBlank()) { Text("Add r/${query.trim().removePrefix("r/").take(18)}") }
                }
                if (vm.accessMode() == AccessMode.PUBLIC_GUEST) {
                    Spacer(Modifier.height(8.dp))
                    InfoBanner("Guest mode without a Reddit app client ID uses Reddit's limited public access, which is slow and may be blocked. Add a client ID in Settings for reliable guest access.")
                }
                if (isAccount) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        loading = true
                        scope.launch { vm.subscriptions().onSuccess { subscribed = it }.onFailure { error = it.message }; loading = false }
                    }) { Icon(Icons.Filled.Person, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Show my subscribed communities") }
                }
                if (loading) Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                error?.let { Text(it, color = Danger, modifier = Modifier.padding(vertical = 8.dp)) }
            }
            subscribed?.let { subs ->
                item {
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Your subscriptions (${subs.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.importSubscriptions(); vm.back() }) { Text("Add all") }
                    }
                }
                items(subs, key = { "s_" + it.name }) { info -> SubResult(info, info.name.lowercase() in existing) { vm.addSubreddit(info) } }
            }
            if (results.isNotEmpty()) item { Text("Results", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
            items(results, key = { it.name }) { info -> SubResult(info, info.name.lowercase() in existing) { vm.addSubreddit(info) } }
        }
    }
}

@Composable
fun SubResult(info: SubredditInfo, added: Boolean, onAdd: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (info.iconUrl != null) RemoteImage(info.iconUrl, Modifier.size(40.dp).clip(CircleShape)) else SubIcon(info.name, null, 40)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("r/${info.name}", style = MaterialTheme.typography.titleSmall)
                    if (info.over18) { Spacer(Modifier.width(6.dp)); Pill("NSFW", Danger, Danger.copy(alpha = 0.12f)) }
                }
                Text("${Format.count(info.subscribers)} members" + if (info.description.isNotBlank()) " · ${info.description}" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (added) Text("Added", style = MaterialTheme.typography.labelLarge, color = Success)
            else FilledTonalButton(onClick = onAdd) { Text("Add") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
