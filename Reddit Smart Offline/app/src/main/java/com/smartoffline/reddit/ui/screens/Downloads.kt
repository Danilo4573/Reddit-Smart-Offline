package com.smartoffline.reddit.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartoffline.reddit.data.LibraryFilter
import com.smartoffline.reddit.data.LibrarySort
import com.smartoffline.reddit.data.PostListItem
import com.smartoffline.reddit.ui.AppIcons
import com.smartoffline.reddit.ui.AppViewModel
import com.smartoffline.reddit.ui.Screen
import com.smartoffline.reddit.ui.components.EmptyState
import com.smartoffline.reddit.ui.components.PostCard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest

data class LibraryQuery(val filter: LibraryFilter, val sort: LibrarySort, val search: String, val limit: Int)

/** All locally stored posts (or one subreddit's), filtered, sorted and searched in the local database. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Composable
fun DownloadsScreen(vm: AppViewModel, subreddit: String?) {
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(if (subreddit != null) LibrarySort.SUBREDDIT else LibrarySort.NEWEST) }
    var search by rememberSaveable { mutableStateOf("") }
    var limit by remember { mutableIntStateOf(60) }
    var sortMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val posts by remember(subreddit) {
        snapshotFlow { LibraryQuery(filter, sort, search, limit) }
            .debounce(120)
            .flatMapLatest { q -> vm.repo.observe { vm.repo.listPosts(q.filter, q.sort, q.search, subreddit, q.limit) } }
    }.collectAsStateWithLifecycle(null)

    // Paging: load more when the end of the list comes into view.
    val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 >= listState.layoutInfo.totalItemsCount - 6 } }
    LaunchedEffect(nearEnd, posts?.size) { if (nearEnd && (posts?.size ?: 0) >= limit) limit += 60 }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = listState, contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (subreddit != null) IconButton(onClick = { vm.back() }) { Icon(Icons.Filled.ArrowBack, "Back") }
                Text(if (subreddit != null) "r/$subreddit" else "Downloads", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (subreddit != null) {
                    IconButton(onClick = { vm.syncSub(subreddit) }) { Icon(AppIcons.Sync, "Sync now") }
                    IconButton(onClick = { vm.navigate(Screen.SubredditConfig(subreddit)) }) { Icon(Icons.Filled.Edit, "Settings") }
                } else {
                    IconButton(onClick = { vm.navigate(Screen.SyncDashboard) }) { Icon(AppIcons.History, "Download activity") }
                }
            }
            Spacer(Modifier.size(6.dp))
            OutlinedTextField(
                value = search, onValueChange = { search = it; limit = 60 }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search downloaded posts and comments") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Filled.Clear, "Clear") } },
                shape = MaterialTheme.shapes.large,
            )
            Spacer(Modifier.size(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LibraryFilter.entries) { f -> FilterChip(selected = filter == f, onClick = { filter = f; limit = 60 }, label = { Text(f.label) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(posts?.let { if (it.size >= limit) "$limit+ posts" else "${it.size} posts" } ?: "", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { sortMenu = true }) { Text("Sort: ${sort.label}") }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        LibrarySort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { sort = s; sortMenu = false }) }
                    }
                }
            }
        }
        val list = posts
        if (list != null && list.isEmpty()) item {
            EmptyState(
                if (search.isNotBlank()) Icons.Filled.Search else AppIcons.Download,
                if (search.isNotBlank()) "No matches" else "Nothing here yet",
                when {
                    search.isNotBlank() -> "No downloaded post matches “$search”."
                    filter == LibraryFilter.FAVORITES -> "Tap the heart on any post to protect it. Protected posts are never removed automatically."
                    else -> "Posts appear here once they are downloaded. Add subreddits and sync to get started."
                },
            )
        }
        items(list.orEmpty(), key = { it.post.id }) { item: PostListItem ->
            PostCard(item, onClick = { vm.navigate(Screen.PostView(item.post.id)) }, onToggleFavorite = { vm.toggleFavorite(item.post.id, item.protected) })
        }
    }
}
