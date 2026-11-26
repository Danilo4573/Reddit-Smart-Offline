package com.smartoffline.reddit.ui

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.smartoffline.reddit.App
import com.smartoffline.reddit.data.AppPrefs
import com.smartoffline.reddit.data.StorageTarget
import com.smartoffline.reddit.data.SubredditSettings
import com.smartoffline.reddit.net.PostParser
import com.smartoffline.reddit.net.RedditException
import com.smartoffline.reddit.net.SubredditInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface Screen {
    data object Home : Screen
    data object Subreddits : Screen
    data object Downloads : Screen
    data object Search : Screen
    data object Settings : Screen
    data class SubredditConfig(val name: String) : Screen
    data class SubredditPosts(val name: String) : Screen
    data class PostView(val id: String) : Screen
    data class MediaView(val postId: String, val index: Int) : Screen
    data object SyncDashboard : Screen
    data object StorageDashboard : Screen
    data object AddSubreddit : Screen
    data object About : Screen
}

val Tabs = listOf(Screen.Home, Screen.Subreddits, Screen.Downloads, Screen.Search, Screen.Settings)

/** App-wide state and user actions shared by all screens. */
class AppViewModel : ViewModel() {
    private val app = App.get()
    val repo = app.repo
    val prefs: StateFlow<AppPrefs> = app.prefs.flow
    val progress = app.engine.progress
    val manualWork: StateFlow<List<WorkInfo>> = app.scheduler.manualWork().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stack = mutableStateListOf<Screen>(Screen.Home)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    fun navigate(s: Screen) { stack.add(s) }
    fun selectTab(s: Screen) { stack.clear(); stack.add(s) }
    fun back(): Boolean {
        if (stack.size <= 1) {
            if (stack.firstOrNull() != Screen.Home) { stack.clear(); stack.add(Screen.Home); return true }
            return false
        }
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun say(msg: String) { messages.tryEmit(msg) }

    private fun io(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        try { block() } catch (e: RedditException) { say(e.message ?: "Reddit error") } catch (e: Exception) { say(e.message ?: "Something went wrong") }
    }

    // ------------------------------------------------------------- sync

    fun syncAll() {
        if (repo.subreddits().isEmpty()) { say("Add a subreddit first"); return }
        app.scheduler.syncNow(prefs.value, null)
        say(if (prefs.value.wifiOnly) "Synchronization queued (Wi-Fi only)" else "Synchronizing all subreddits")
    }

    fun syncSub(name: String) { app.scheduler.syncNow(prefs.value, listOf(name)); say("Synchronizing r/$name") }

    fun retryFailed() = io {
        val n = repo.resetFailedMedia(includeUnavailable = false)
        app.scheduler.syncNow(prefs.value, emptyList(), mediaOnly = true)
        say(if (n > 0) "Retrying $n downloads" else "Resuming pending downloads")
    }

    fun retryUnavailable() = io {
        val n = repo.resetFailedMedia(includeUnavailable = true)
        app.scheduler.syncNow(prefs.value, emptyList(), mediaOnly = true)
        say("Retrying $n downloads")
    }

    fun pause() = app.engine.pause()
    fun resume() = app.engine.resume()
    fun cancelSync() { app.engine.cancel(); say("Synchronization cancelled. Partial downloads resume next time.") }

    // ------------------------------------------------------------- posts

    fun toggleFavorite(id: String, currentlyProtected: Boolean) = io {
        repo.setProtected(id, !currentlyProtected)
        say(if (currentlyProtected) "Protection removed. The post may be cleaned up automatically." else "Protected: this post will never be removed automatically")
    }

    fun deletePost(id: String) = io {
        repo.deletePost(id)
        app.engine.cleanupOrphans()
        say("Post deleted from this device")
    }

    /** Saves one post found online, protected so it stays until the user removes it. */
    fun savePostOffline(json: JSONObject) = io {
        val parsed = PostParser.parse(json, 0)
        val post = parsed.post.copy(inSelection = false)
        repo.tx {
            repo.upsertPost(post)
            parsed.media.forEachIndexed { i, m ->
                val id = repo.findOrCreateMedia(m.sourceKey, m.url, m.dashUrl, m.kind, m.mime, m.width, m.height, m.hasAudio)
                repo.linkPostMedia(post.id, id, i, m.caption)
            }
        }
        repo.setProtected(post.id, true)
        repo.refreshPostState(post.id)
        if (post.numComments > 0) runCatching {
            val p = prefs.value
            repo.replaceComments(post.id, PostParser.parseComments(post.id, app.reddit.comments(post.id, p.defaultCommentCount, 3), p.defaultCommentCount, 3))
        }
        app.scheduler.syncNow(prefs.value, emptyList(), mediaOnly = true)
        say("Saved to Favorites. Media is downloading.")
    }

    // ------------------------------------------------------------- subreddits

    suspend fun lookupSubreddit(input: String): Result<SubredditInfo> = withContext(Dispatchers.IO) {
        runCatching { app.reddit.about(input) }
    }

    fun addSubreddit(info: SubredditInfo, settings: SubredditSettings? = null, syncNow: Boolean = true) = io {
        val s = settings ?: prefs.value.newSubredditSettings(info.name)
        if (!repo.addSubreddit(info.name, info.title, info.iconUrl, info.description, info.subscribers, info.over18, s.copy(subreddit = info.name))) {
            say("r/${info.name} is already in your list"); return@io
        }
        app.scheduler.schedule(repo.settings(info.name), prefs.value, replace = true)
        if (syncNow) app.scheduler.syncNow(prefs.value, listOf(info.name))
        say("Added r/${info.name}")
    }

    fun importSubscriptions() = io {
        val subs = app.reddit.subscribed()
        var n = 0
        subs.forEach { info ->
            val s = prefs.value.newSubredditSettings(info.name)
            if (repo.addSubreddit(info.name, info.title, info.iconUrl, info.description, info.subscribers, info.over18, s)) {
                app.scheduler.schedule(s, prefs.value, replace = true); n++
            }
        }
        say(if (n == 0) "No new subscriptions to add" else "Added $n subscribed communities")
    }

    suspend fun subscriptions(): Result<List<SubredditInfo>> = withContext(Dispatchers.IO) { runCatching { app.reddit.subscribed() } }

    fun removeSubreddit(name: String) = io {
        app.scheduler.cancel(name)
        repo.removeSubreddit(name)
        app.engine.cleanupOrphans()
        say("Removed r/$name. Favorites from it were kept.")
    }

    fun saveSettings(s: SubredditSettings) = io {
        repo.saveSettings(s)
        app.scheduler.schedule(s, prefs.value, replace = true)
        say("Settings saved for r/${s.subreddit}")
    }

    // ------------------------------------------------------------- preferences

    fun updatePrefs(transform: (AppPrefs) -> AppPrefs) {
        val old = prefs.value
        app.prefs.update(transform)
        val new = prefs.value
        if (old.autoDownloads != new.autoDownloads || old.wifiOnly != new.wifiOnly || old.requireCharging != new.requireCharging || old.batteryNotLow != new.batteryNotLow) {
            viewModelScope.launch(Dispatchers.IO) { app.scheduler.scheduleAll(new, replace = true) }
        }
    }

    fun setClientId(id: String) {
        app.reddit.resetAppToken()
        updatePrefs { it.copy(clientId = id.trim()) }
    }

    fun signInUrl(): String? {
        if (prefs.value.clientId.isBlank()) { say("Enter your Reddit app client ID first"); return null }
        return app.reddit.authorizeUrl()
    }

    fun handleAuthRedirect(uri: Uri) = io {
        val name = app.reddit.completeAuthorization(uri)
        say("Signed in as u/$name")
    }

    fun signOut() = io { app.reddit.signOut(); say("Signed out. Guest mode is active.") }

    fun useGuestMode() = io { app.reddit.signOut() }

    fun accessMode() = app.reddit.mode

    // ------------------------------------------------------------- storage

    fun storageOptions() = app.storage.options()
    fun describeStorage(t: StorageTarget) = app.storage.describe(t)

    fun chooseStorage(t: StorageTarget) {
        val old = prefs.value.storage
        val problem = app.storage.checkAvailable(t)
        if (problem != null) { say(problem); return }
        if (old is StorageTarget.Tree && old != t) app.storage.releaseTreePermission(old.uri)
        updatePrefs { it.copy(storage = t, storageUnavailable = false) }
        say("New downloads will be saved to ${app.storage.describe(t)}")
    }

    fun onTreePicked(uri: Uri) {
        try {
            app.storage.takeTreePermission(uri)
            chooseStorage(StorageTarget.Tree(uri.toString()))
        } catch (e: Exception) {
            say("This folder can't be used: ${e.message}")
        }
    }

    fun cleanupOrphans() = io {
        val n = app.engine.cleanupOrphans()
        val f = if (!progress.value.running) app.engine.cleanupStrayFiles() else 0
        say("Removed ${n + f} unused files")
    }

    fun clearUnprotected(sub: String?) = io {
        val n = repo.deleteUnprotectedPosts(sub)
        app.engine.cleanupOrphans()
        say("Removed $n posts. Favorites were kept.")
    }

    fun deleteOlderThan(days: Int) = io {
        val n = repo.deletePostsOlderThan(System.currentTimeMillis() - days * 86_400_000L)
        app.engine.cleanupOrphans()
        say("Removed $n posts downloaded more than $days days ago")
    }

    fun clearFailed() = io {
        val posts = repo.clearFailedMedia()
        posts.forEach { repo.refreshPostState(it) }
        say("Cleared failed downloads")
    }
}
