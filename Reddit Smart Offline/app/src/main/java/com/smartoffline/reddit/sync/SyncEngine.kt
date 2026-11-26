package com.smartoffline.reddit.sync

import com.smartoffline.reddit.data.MediaKind
import com.smartoffline.reddit.data.MediaState
import com.smartoffline.reddit.data.OfflineState
import com.smartoffline.reddit.data.PostType
import com.smartoffline.reddit.data.Prefs
import com.smartoffline.reddit.data.Repository
import com.smartoffline.reddit.data.SubredditSettings
import com.smartoffline.reddit.download.DownloadResult
import com.smartoffline.reddit.download.MediaDownloader
import com.smartoffline.reddit.net.Http
import com.smartoffline.reddit.net.ParsedPost
import com.smartoffline.reddit.net.PostParser
import com.smartoffline.reddit.net.RedditClient
import com.smartoffline.reddit.net.RedditException
import com.smartoffline.reddit.storage.MediaStorage
import com.smartoffline.reddit.util.Format
import com.smartoffline.reddit.util.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

data class SyncProgress(
    val running: Boolean = false,
    val paused: Boolean = false,
    val trigger: String = "",
    val currentSubreddit: String? = null,
    val operation: String = "Idle",
    val queue: List<String> = emptyList(),
    val postsChecked: Int = 0,
    val newPosts: Int = 0,
    val downloadedPosts: Int = 0,
    val mediaDone: Int = 0,
    val mediaTotal: Int = 0,
    val failed: Int = 0,
    val removed: Int = 0,
    val fileName: String? = null,
    val fileDone: Long = 0,
    val fileTotal: Long = 0,
    val bytes: Long = 0,
    val startedAt: Long = 0,
    val lastMessage: String? = null,
)

enum class SyncOutcome { SUCCESS, PARTIAL, RETRY, FAILED, CANCELLED }

/**
 * The Smart Downloads engine. For each subreddit it fetches the configured listing, keeps exactly the
 * configured number of automatic posts (plus any protected ones), removes posts that fell out of the
 * selection, downloads comments and media, and records the run in the sync history.
 * Only one synchronization runs at a time.
 */
class SyncEngine(
    private val repo: Repository,
    private val prefs: Prefs,
    private val reddit: RedditClient,
    private val storage: MediaStorage,
    private val notifier: Notifier,
) {
    private val lock = Mutex()
    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress
    private val pausedFlag = MutableStateFlow(false)
    @Volatile private var activeJob: Job? = null

    private val downloader = MediaDownloader(repo, storage) { waitIfPaused() }

    fun pause() { pausedFlag.value = true; _progress.update { it.copy(paused = true) } }
    fun resume() { pausedFlag.value = false; _progress.update { it.copy(paused = false) } }
    fun cancel() { pausedFlag.value = false; activeJob?.cancel(CancellationException("Cancelled by user")) }

    private suspend fun waitIfPaused() {
        if (!pausedFlag.value) return
        _progress.update { it.copy(paused = true) }
        // A paused sync gives up after 30 minutes; everything resumes on the next run.
        val resumed = withTimeoutOrNull(30 * 60_000L) { pausedFlag.first { !it } }
        if (resumed == null) { pausedFlag.value = false; throw CancellationException("Paused for too long") }
        _progress.update { it.copy(paused = false) }
    }

    /**
     * Synchronizes [subs] (all selected subreddits when null). With [mediaOnly], no listing is fetched;
     * only unfinished downloads are retried.
     */
    suspend fun run(subs: List<String>?, trigger: String, mediaOnly: Boolean = false, scheduled: Boolean = false): SyncOutcome =
        withContext(Dispatchers.IO) {
            lock.withLock {
                var outcome = SyncOutcome.SUCCESS
                coroutineScope {
                    val job = launch { outcome = runLocked(subs, trigger, mediaOnly, scheduled) }
                    activeJob = job
                    job.join()
                    if (job.isCancelled && outcome == SyncOutcome.SUCCESS) outcome = SyncOutcome.CANCELLED
                }
                activeJob = null
                pausedFlag.value = false
                _progress.update { it.copy(running = false, paused = false, operation = "Idle", currentSubreddit = null, fileName = null) }
                outcome
            }
        }

    private class Abort(val outcome: SyncOutcome, message: String) : Exception(message)

    private suspend fun runLocked(subs: List<String>?, trigger: String, mediaOnly: Boolean, scheduled: Boolean): SyncOutcome {
        val all = repo.subreddits()
        val targets = (subs?.let { names -> all.filter { s -> names.any { it.equals(s.name, true) } } } ?: all)
            .filter { s -> !scheduled || isDue(s.name, repo.settings(s.name), s.lastSuccess, s.lastStatus) }
        if (targets.isEmpty() && !mediaOnly && subs != null) return SyncOutcome.SUCCESS
        _progress.value = SyncProgress(running = true, trigger = trigger, startedAt = System.currentTimeMillis(),
            queue = targets.map { it.name }, operation = "Starting…")

        storage.checkAvailable()?.let { reason ->
            prefs.update { it.copy(storageUnavailable = true) }
            notifier.alert(Notifier.ID_FOLDER, "Storage location unavailable", reason, "settings")
            val h = repo.startHistory(subs?.singleOrNull(), trigger)
            repo.finishHistory(h, "FAILED", 0, 0, 0, 0, 0, 0, reason)
            _progress.update { it.copy(lastMessage = reason) }
            return SyncOutcome.FAILED
        }
        if (prefs.value.storageUnavailable) prefs.update { it.copy(storageUnavailable = false) }

        var overall = SyncOutcome.SUCCESS
        try {
            if (!mediaOnly) {
                for ((i, sub) in targets.withIndex()) {
                    _progress.update { it.copy(queue = targets.drop(i + 1).map { s -> s.name }) }
                    val r = syncSubreddit(sub.name, trigger)
                    if (r != SyncOutcome.SUCCESS) overall = if (overall == SyncOutcome.SUCCESS) r else overall
                }
            }
            // Finish anything still pending (retries, protected posts of removed subreddits…).
            if (mediaOnly || subs == null) {
                val h = repo.startHistory(null, if (mediaOnly) "$trigger (downloads)" else "$trigger (remaining downloads)")
                val stats = RunStats()
                _progress.update { it.copy(currentSubreddit = null, operation = "Downloading remaining media") }
                downloadMedia(null, stats)
                if (mediaOnly || stats.downloaded > 0 || stats.failed > 0)
                    repo.finishHistory(h, if (stats.failed > 0) "PARTIAL" else "SUCCESS", 0, 0, stats.downloaded, stats.failed, 0, stats.bytes, stats.message)
                else repo.deleteHistoryIfEmpty(h)
            }
        } catch (e: Abort) {
            _progress.update { it.copy(lastMessage = e.message) }
            return e.outcome
        } catch (e: CancellationException) {
            _progress.update { it.copy(lastMessage = "Synchronization cancelled") }
            throw e
        }
        val p = _progress.value
        if (p.newPosts > 0 && trigger == "Scheduled") {
            notifier.alert(Notifier.ID_SUMMARY, "New posts saved for offline", "${p.newPosts} new posts downloaded (${Format.bytes(p.bytes)}).", "home")
        }
        return overall
    }

    fun isDue(name: String, s: SubredditSettings, lastSuccess: Long, lastStatus: String?): Boolean {
        if (!s.autoSync) return false
        if (lastStatus != null && lastStatus != "Up to date") return true // last run failed or was interrupted
        return System.currentTimeMillis() - lastSuccess >= s.intervalHours * 3_600_000L / 2
    }

    private class RunStats {
        var checked = 0; var newPosts = 0; var downloaded = 0; var failed = 0; var removed = 0; var bytes = 0L
        var message: String? = null
    }

    private fun wanted(s: SubredditSettings, t: PostType) = when (t) {
        PostType.IMAGE, PostType.GALLERY -> s.downloadImages
        PostType.VIDEO -> s.downloadVideos
        PostType.GIF -> s.downloadGifs
        PostType.TEXT, PostType.LINK -> s.downloadText
    }

    private suspend fun syncSubreddit(name: String, trigger: String): SyncOutcome {
        val settings = repo.settings(name)
        val history = repo.startHistory(name, trigger)
        val stats = RunStats()
        repo.setSyncStatus(name, "Syncing…", null, started = true)
        _progress.update { it.copy(currentSubreddit = name, operation = "Checking r/$name") }
        try {
            // Community info and icon (refreshed occasionally).
            val sub = repo.subreddit(name)
            if (sub != null && (sub.iconLocation == null || System.currentTimeMillis() - sub.lastSuccess > 7 * 86_400_000L)) {
                runCatching {
                    val info = reddit.about(name)
                    repo.updateSubredditInfo(name, info.title, info.iconUrl, info.description, info.subscribers, info.over18)
                    info.iconUrl?.let { saveIcon(name, it) }
                }.onFailure { if (it is RedditException.NotFound || it is RedditException.Forbidden) throw it }
            }

            // 1-2. Fetch the configured listing until enough matching posts were found.
            val selected = mutableListOf<ParsedPost>()
            var after: String? = null
            var pages = 0
            do {
                currentCoroutineContext().ensureActive()
                waitIfPaused()
                _progress.update { it.copy(operation = "Reading ${settings.sort.label.lowercase()} posts of r/$name") }
                val need = settings.postCount - selected.size
                val (page, next) = reddit.listing(name, settings.sort, settings.timeRange, after, (need + 10).coerceAtMost(100))
                stats.checked += page.size
                _progress.update { it.copy(postsChecked = it.postsChecked + page.size) }
                for (json in page) {
                    if (selected.size >= settings.postCount) break
                    if (json.optBoolean("stickied") && settings.sort == com.smartoffline.reddit.data.SortMode.HOT) continue
                    val parsed = PostParser.parse(json, selected.size)
                    if (parsed.post.id.isBlank() || selected.any { it.post.id == parsed.post.id }) continue
                    if (parsed.post.over18 && !prefs.value.showNsfw) continue
                    if (wanted(settings, parsed.post.type)) selected += parsed
                }
                after = next
                pages++
            } while (selected.size < settings.postCount && after != null && pages < 15)

            // 3-5, 8-9. Apply the new selection: new posts are added, known ones refreshed, the rest removed.
            _progress.update { it.copy(operation = "Updating saved posts") }
            val newIds = mutableListOf<String>()
            repo.tx {
                repo.beginSelection(name)
                for (p in selected) {
                    val isNew = repo.upsertPost(p.post.copy(subreddit = name))
                    if (isNew) newIds += p.post.id
                    p.media.forEachIndexed { i, m ->
                        val id = repo.findOrCreateMedia(m.sourceKey, m.url, m.dashUrl, m.kind, m.mime, m.width, m.height, m.hasAudio)
                        repo.linkPostMedia(p.post.id, id, i, m.caption)
                    }
                }
                stats.removed = repo.deleteOutdated(name)
            }
            stats.newPosts = newIds.size
            _progress.update { it.copy(newPosts = it.newPosts + newIds.size, removed = it.removed + stats.removed) }
            cleanupOrphans()
            verifyFiles(name)
            selected.forEach { repo.refreshPostState(it.post.id) }

            // 6. Comments for posts that don't have them yet.
            if (settings.downloadComments && settings.commentCount > 0) {
                val ids = repo.postsNeedingComments(name)
                for ((i, id) in ids.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    waitIfPaused()
                    _progress.update { it.copy(operation = "Saving comments ${i + 1}/${ids.size}") }
                    try {
                        val children = reddit.comments(id, settings.commentCount, if (settings.replyCount > 0) 3 else 1)
                        repo.replaceComments(id, PostParser.parseComments(id, children, settings.commentCount, settings.replyCount))
                    } catch (e: RedditException.NotFound) {
                        repo.setCommentsFetched(id)
                    } catch (e: RedditException.Forbidden) {
                        repo.setCommentsFetched(id)
                    }
                }
            }

            // 4. Media.
            downloadMedia(name, stats)

            val status = if (stats.failed > 0) "PARTIAL" else "SUCCESS"
            val msg = stats.message ?: if (stats.failed > 0) "${stats.failed} files could not be downloaded; they will be retried." else null
            repo.finishHistory(history, status, stats.checked, stats.newPosts, stats.downloaded, stats.failed, stats.removed, stats.bytes, msg)
            repo.setSyncStatus(name, if (stats.message != null) "Paused: storage" else "Up to date", msg, success = true)
            return if (stats.failed > 0) SyncOutcome.PARTIAL else SyncOutcome.SUCCESS
        } catch (e: CancellationException) {
            repo.finishHistory(history, "CANCELLED", stats.checked, stats.newPosts, stats.downloaded, stats.failed, stats.removed, stats.bytes, "Cancelled")
            repo.setSyncStatus(name, "Cancelled", null)
            throw e
        } catch (e: RedditException) {
            repo.finishHistory(history, "FAILED", stats.checked, stats.newPosts, stats.downloaded, stats.failed, stats.removed, stats.bytes, e.message)
            repo.setSyncStatus(name, "Failed", e.message)
            when (e) {
                is RedditException.AuthExpired -> {
                    notifier.alert(Notifier.ID_AUTH, "Reddit sign-in expired", e.message ?: "", "settings")
                    throw Abort(SyncOutcome.FAILED, e.message ?: "")
                }
                is RedditException.Network, is RedditException.Server -> throw Abort(SyncOutcome.RETRY, e.message ?: "")
                is RedditException.RateLimited -> throw Abort(SyncOutcome.RETRY, e.message ?: "")
                else -> return SyncOutcome.FAILED // This subreddit only (not found, private…).
            }
        } catch (e: Exception) {
            if (e is Abort) throw e
            val msg = "Unexpected error: ${e.message ?: e.javaClass.simpleName}"
            repo.finishHistory(history, "FAILED", stats.checked, stats.newPosts, stats.downloaded, stats.failed, stats.removed, stats.bytes, msg)
            repo.setSyncStatus(name, "Failed", msg)
            return SyncOutcome.FAILED
        }
    }

    private suspend fun downloadMedia(sub: String?, stats: RunStats) {
        val postIds = repo.postIdsWithUnfinishedMedia(sub)
        val jobs = postIds.flatMap { pid -> repo.mediaForPost(pid).map { pid to it.media } }
            .filter { (_, m) -> m.state != MediaState.COMPLETE && m.state != MediaState.UNAVAILABLE && m.state != MediaState.SKIPPED && (m.state != MediaState.FAILED || m.attempts < 5) }
            .distinctBy { it.second.id }
        _progress.update { it.copy(mediaTotal = it.mediaTotal + jobs.size) }
        val limit = prefs.value.storageLimitMb * 1024 * 1024
        var storageFull = false
        for ((postId, media) in jobs) {
            currentCoroutineContext().ensureActive()
            waitIfPaused()
            if (storageFull) {
                repo.setMediaState(media.id, MediaState.WAITING_STORAGE, "Waiting for free space (storage limit reached)")
                repo.refreshPostState(postId)
                continue
            }
            val title = repo.post(postId)?.title ?: ""
            val label = when (media.kind) { MediaKind.IMAGE -> "image"; MediaKind.VIDEO -> "video"; MediaKind.GIF -> "GIF" }
            _progress.update { it.copy(operation = "Downloading $label", fileName = title, fileDone = 0, fileTotal = 0) }
            val fits: (Long) -> Boolean = { size ->
                if (limit <= 0) true
                else if (repo.totalBytes() + size <= limit) true
                else { cleanupOrphans(); repo.totalBytes() + size <= limit }
            }
            val wasComplete = repo.post(postId)?.offlineState == OfflineState.COMPLETE
            when (val r = downloader.download(media, { d, t -> _progress.update { it.copy(fileDone = d, fileTotal = t) } }, fits)) {
                is DownloadResult.Done -> {
                    stats.downloaded++; stats.bytes += r.bytes
                    _progress.update { it.copy(mediaDone = it.mediaDone + 1, bytes = it.bytes + r.bytes) }
                }
                is DownloadResult.Unavailable -> {
                    repo.setMediaState(media.id, MediaState.UNAVAILABLE, r.reason, countAttempt = true)
                    _progress.update { it.copy(mediaDone = it.mediaDone + 1) }
                }
                is DownloadResult.Failed -> {
                    stats.failed++
                    repo.setMediaState(media.id, MediaState.FAILED, r.reason, countAttempt = true)
                    _progress.update { it.copy(failed = it.failed + 1, mediaDone = it.mediaDone + 1) }
                }
                is DownloadResult.NoSpace -> {
                    storageFull = true
                    stats.message = r.reason
                    repo.setMediaState(media.id, MediaState.WAITING_STORAGE, r.reason)
                    notifier.alert(Notifier.ID_STORAGE, "Downloads paused: storage full", r.reason + " Protected posts were kept.", "storage")
                }
                is DownloadResult.StorageMissing -> {
                    repo.setMediaState(media.id, MediaState.PENDING, r.reason)
                    repo.refreshPostState(postId)
                    prefs.update { it.copy(storageUnavailable = true) }
                    notifier.alert(Notifier.ID_FOLDER, "Storage location unavailable", r.reason, "settings")
                    throw Abort(SyncOutcome.FAILED, r.reason)
                }
            }
            repo.refreshPostState(postId)
            if (!wasComplete && repo.post(postId)?.offlineState == OfflineState.COMPLETE) {
                _progress.update { it.copy(downloadedPosts = it.downloadedPosts + 1) }
            }
        }
        _progress.update { it.copy(fileName = null, fileDone = 0, fileTotal = 0) }
    }

    private fun saveIcon(name: String, url: String) {
        val out = File(storage.thumbDir, "icon_${name.lowercase()}.png")
        val c = Http.open(url)
        try {
            if (c.responseCode == 200) {
                val tmp = File(out.path + ".tmp")
                c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                if (tmp.length() > 0 && tmp.renameTo(out)) repo.setSubredditIcon(name, "file:" + out.absolutePath)
            }
        } finally {
            c.disconnect()
        }
    }

    /** Media rows whose stored file disappeared are reset so they are downloaded again. */
    private fun verifyFiles(sub: String) {
        repo.listPosts(subreddit = sub, limit = 5000).forEach { item ->
            var broken = false
            repo.mediaForPost(item.post.id).forEach { pm ->
                val m = pm.media
                if (m.state == MediaState.COMPLETE && (m.location == null || !storage.exists(m.location))) {
                    repo.invalidateMedia(m.id, "The saved file was missing and will be downloaded again.")
                    broken = true
                }
            }
            if (broken) repo.refreshPostState(item.post.id)
        }
    }

    /** Deletes media no longer referenced by any post, and their files when no other media shares them. */
    @Synchronized
    fun cleanupOrphans(): Int {
        val orphans = repo.orphanMedia()
        for (m in orphans) {
            repo.deleteMediaRow(m.id)
            m.location?.let { if (repo.locationRefCount(it) == 0) storage.delete(it) }
            m.thumbLocation?.let { if (repo.locationRefCount(it) == 0) storage.delete(it) }
            File(storage.partialDir, "m${m.id}.v.part").delete()
            File(storage.partialDir, "m${m.id}.a.part").delete()
        }
        return orphans.size
    }

    /** Removes downloaded files on disk that no database row refers to (e.g. after a crash). */
    fun cleanupStrayFiles(): Int {
        val known = repo.allCompleteMedia().flatMap { listOfNotNull(it.location, it.thumbLocation) }.toSet()
        var n = 0
        val dirs = listOf(File(storage.thumbDir.parentFile, "media"), storage.thumbDir, storage.workDir)
        for (dir in dirs) dir.listFiles()?.forEach { f ->
            if (f.name.startsWith("icon_")) return@forEach
            if ("file:" + f.absolutePath !in known) { f.delete(); n++ }
        }
        return n
    }
}
