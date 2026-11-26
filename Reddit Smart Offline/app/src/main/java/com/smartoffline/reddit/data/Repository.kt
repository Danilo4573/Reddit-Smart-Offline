package com.smartoffline.reddit.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample

/**
 * Single access point to the local database. Every write bumps [changes] so that screens observing
 * data through [observe] refresh automatically (a lightweight equivalent of Room's invalidation tracker).
 */
class Repository(private val helper: AppDatabase) {
    private val db: SQLiteDatabase get() = helper.writableDatabase
    val changes = MutableStateFlow(0L)

    private fun changed() { changes.value = changes.value + 1 }

    @OptIn(FlowPreview::class)
    fun <T> observe(block: () -> T): Flow<T> =
        changes.sample(250).map { block() }.distinctUntilChanged().flowOn(Dispatchers.IO)

    fun <T> tx(block: () -> T): T {
        val d = db
        d.beginTransaction()
        try {
            val r = block()
            d.setTransactionSuccessful()
            return r
        } finally {
            d.endTransaction()
            changed()
        }
    }

    // ---------------------------------------------------------------- preferences (UserPreferences)

    fun allPrefs(): Map<String, String> = query("SELECT key, value FROM user_preferences") {
        it.getString(0) to (it.getString(1) ?: "")
    }.toMap()

    fun setPref(key: String, value: String?) {
        if (value == null) db.delete("user_preferences", "key=?", arrayOf(key))
        else db.insertWithOnConflict("user_preferences", null, ContentValues().apply {
            put("key", key); put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    // ---------------------------------------------------------------- subreddits

    fun addSubreddit(name: String, title: String, iconUrl: String?, description: String, subscribers: Long, over18: Boolean, settings: SubredditSettings): Boolean {
        return tx {
            val id = db.insertWithOnConflict("subreddits", null, ContentValues().apply {
                put("name", name); put("title", title); put("icon_url", iconUrl)
                put("description", description); put("subscribers", subscribers)
                put("over18", if (over18) 1 else 0); put("added_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (id != -1L) saveSettingsInternal(settings.copy(subreddit = name))
            id != -1L
        }
    }

    fun updateSubredditInfo(name: String, title: String, iconUrl: String?, description: String, subscribers: Long, over18: Boolean) {
        db.update("subreddits", ContentValues().apply {
            put("title", title); put("icon_url", iconUrl); put("description", description)
            put("subscribers", subscribers); put("over18", if (over18) 1 else 0)
        }, "name=?", arrayOf(name))
        changed()
    }

    fun setSubredditIcon(name: String, location: String?) {
        db.update("subreddits", ContentValues().apply { put("icon_location", location) }, "name=?", arrayOf(name))
        changed()
    }

    fun setSyncStatus(name: String, status: String?, error: String?, started: Boolean = false, success: Boolean = false) {
        val now = System.currentTimeMillis()
        db.update("subreddits", ContentValues().apply {
            put("last_status", status); put("last_error", error)
            if (started) put("last_sync", now)
            if (success) put("last_success", now)
        }, "name=?", arrayOf(name))
        changed()
    }

    /** Removes the subreddit, its settings and its non-protected posts. Protected posts are kept. */
    fun removeSubreddit(name: String) = tx {
        db.execSQL(
            "DELETE FROM posts WHERE subreddit=? AND id NOT IN (SELECT post_id FROM protected_posts)",
            arrayOf(name)
        )
        db.delete("subreddits", "name=?", arrayOf(name))
    }

    fun subreddits(): List<Subreddit> = query("SELECT * FROM subreddits ORDER BY name COLLATE NOCASE") { readSub(it) }

    fun subreddit(name: String): Subreddit? = query("SELECT * FROM subreddits WHERE name=?", name) { readSub(it) }.firstOrNull()

    private fun readSub(c: Cursor) = Subreddit(
        name = c.str("name"), title = c.str("title"), iconUrl = c.strOrNull("icon_url"),
        iconLocation = c.strOrNull("icon_location"), description = c.str("description"),
        subscribers = c.long("subscribers"), over18 = c.int("over18") == 1, addedAt = c.long("added_at"),
        lastSync = c.long("last_sync"), lastSuccess = c.long("last_success"),
        lastStatus = c.strOrNull("last_status"), lastError = c.strOrNull("last_error"),
    )

    fun settings(name: String): SubredditSettings =
        query("SELECT * FROM subreddit_settings WHERE subreddit=?", name) { c ->
            SubredditSettings(
                subreddit = c.str("subreddit"), sort = SortMode.of(c.str("sort")),
                timeRange = TimeRange.of(c.str("time_range")), postCount = c.int("post_count"),
                intervalHours = c.int("interval_hours"), downloadImages = c.int("dl_images") == 1,
                downloadVideos = c.int("dl_videos") == 1, downloadGifs = c.int("dl_gifs") == 1,
                downloadText = c.int("dl_text") == 1, downloadComments = c.int("dl_comments") == 1,
                commentCount = c.int("comment_count"), replyCount = c.int("reply_count"),
                autoSync = c.int("auto_sync") == 1,
            )
        }.firstOrNull() ?: SubredditSettings(name)

    fun saveSettings(s: SubredditSettings) { saveSettingsInternal(s); changed() }

    private fun saveSettingsInternal(s: SubredditSettings) {
        db.insertWithOnConflict("subreddit_settings", null, ContentValues().apply {
            put("subreddit", s.subreddit); put("sort", s.sort.name); put("time_range", s.timeRange.name)
            put("post_count", s.postCount); put("interval_hours", s.intervalHours)
            put("dl_images", s.downloadImages.int); put("dl_videos", s.downloadVideos.int)
            put("dl_gifs", s.downloadGifs.int); put("dl_text", s.downloadText.int)
            put("dl_comments", s.downloadComments.int); put("comment_count", s.commentCount)
            put("reply_count", s.replyCount); put("auto_sync", s.autoSync.int)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun subredditOverviews(): List<SubredditOverview> {
        val counts = query("SELECT subreddit, COUNT(*) FROM posts GROUP BY subreddit COLLATE NOCASE") {
            it.getString(0).lowercase() to it.getInt(1)
        }.toMap()
        val bytes = bytesPerSubreddit().associate { it.first.lowercase() to it.second }
        return subreddits().map {
            SubredditOverview(it, settings(it.name), counts[it.name.lowercase()] ?: 0, bytes[it.name.lowercase()] ?: 0L)
        }
    }

    private fun bytesPerSubreddit(): List<Pair<String, Long>> = query(
        """SELECT sub, SUM(size_bytes) FROM (
             SELECT DISTINCT p.subreddit AS sub, m.id, m.size_bytes FROM media m
             JOIN post_media pm ON pm.media_id = m.id JOIN posts p ON p.id = pm.post_id
             WHERE m.state = 'COMPLETE') GROUP BY sub COLLATE NOCASE ORDER BY 2 DESC"""
    ) { it.getString(0) to it.getLong(1) }

    // ---------------------------------------------------------------- posts

    fun existingPostIds(sub: String): Set<String> = query("SELECT id FROM posts WHERE subreddit=?", sub) { it.getString(0) }.toSet()

    fun postExists(id: String): Boolean = query("SELECT 1 FROM posts WHERE id=?", id) { 1 }.isNotEmpty()

    /** Marks all posts of a subreddit as "not in the current selection" before a fresh listing is applied. */
    fun beginSelection(sub: String) {
        db.execSQL("UPDATE posts SET in_selection=0 WHERE subreddit=?", arrayOf(sub))
    }

    /** Inserts a new post, or refreshes metadata of an existing one without touching its offline data. */
    fun upsertPost(p: Post): Boolean {
        val values = ContentValues().apply {
            put("title", p.title); put("author", p.author); put("selftext", p.selftext); put("url", p.url)
            put("permalink", p.permalink); put("domain", p.domain); put("score", p.score)
            put("num_comments", p.numComments); put("upvote_ratio", p.upvoteRatio); put("over18", p.over18.int)
            put("flair", p.flair); put("in_selection", p.inSelection.int); put("selection_rank", p.selectionRank)
            put("updated_at", System.currentTimeMillis()); put("removed", p.removed.int)
        }
        val updated = db.update("posts", values, "id=?", arrayOf(p.id))
        if (updated > 0) { changed(); return false }
        values.apply {
            put("id", p.id); put("subreddit", p.subreddit); put("created_utc", p.createdUtc)
            put("post_type", p.type.name); put("offline_state", p.offlineState.name); put("media_note", p.mediaNote)
            put("downloaded_at", System.currentTimeMillis())
        }
        db.insert("posts", null, values)
        changed()
        return true
    }

    /** Deletes posts that fell out of the subreddit's selection and are not protected. Returns ids removed. */
    fun deleteOutdated(sub: String): Int {
        val n = db.delete(
            "posts",
            "subreddit=? AND in_selection=0 AND id NOT IN (SELECT post_id FROM protected_posts)",
            arrayOf(sub)
        )
        changed()
        return n
    }

    fun deletePost(id: String) { db.delete("posts", "id=?", arrayOf(id)); changed() }

    fun deleteUnprotectedPosts(sub: String?): Int {
        val n = if (sub == null) db.delete("posts", "id NOT IN (SELECT post_id FROM protected_posts)", null)
        else db.delete("posts", "subreddit=? AND id NOT IN (SELECT post_id FROM protected_posts)", arrayOf(sub))
        changed()
        return n
    }

    fun deletePostsOlderThan(cutoffMs: Long): Int {
        val n = db.delete(
            "posts", "downloaded_at<? AND id NOT IN (SELECT post_id FROM protected_posts)",
            arrayOf(cutoffMs.toString())
        )
        changed()
        return n
    }

    fun setProtected(id: String, protect: Boolean) {
        if (protect) db.insertWithOnConflict("protected_posts", null, ContentValues().apply {
            put("post_id", id); put("protected_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        else db.delete("protected_posts", "post_id=?", arrayOf(id))
        changed()
    }

    fun isProtected(id: String) = query("SELECT 1 FROM protected_posts WHERE post_id=?", id) { 1 }.isNotEmpty()

    fun post(id: String): Post? = query("SELECT * FROM posts WHERE id=?", id) { readPost(it) }.firstOrNull()

    fun setPostOfflineState(id: String, state: OfflineState, note: String?) {
        db.update("posts", ContentValues().apply {
            put("offline_state", state.name); put("media_note", note)
        }, "id=?", arrayOf(id))
        changed()
    }

    fun setCommentsFetched(id: String) {
        db.update("posts", ContentValues().apply { put("comments_fetched_at", System.currentTimeMillis()) }, "id=?", arrayOf(id))
    }

    fun postsNeedingComments(sub: String): List<String> =
        query("SELECT id FROM posts WHERE subreddit=? AND comments_fetched_at=0 AND num_comments>0 ORDER BY selection_rank", sub) { it.getString(0) }

    fun postIdsWithUnfinishedMedia(sub: String?): List<String> = query(
        """SELECT DISTINCT p.id FROM posts p JOIN post_media pm ON pm.post_id=p.id JOIN media m ON m.id=pm.media_id
           WHERE m.state NOT IN ('COMPLETE','UNAVAILABLE','SKIPPED') ${if (sub != null) "AND p.subreddit=?" else ""}
           ORDER BY p.in_selection DESC, p.selection_rank""",
        *listOfNotNull(sub).toTypedArray()
    ) { it.getString(0) }

    /** Library listing with filtering, sorting, local search and paging (via [limit]). */
    fun listPosts(
        filter: LibraryFilter = LibraryFilter.ALL,
        sort: LibrarySort = LibrarySort.NEWEST,
        search: String = "",
        subreddit: String? = null,
        limit: Int = 60,
    ): List<PostListItem> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        when (filter) {
            LibraryFilter.IMAGES -> where += "p.post_type IN ('IMAGE','GALLERY')"
            LibraryFilter.VIDEOS -> where += "p.post_type = 'VIDEO'"
            LibraryFilter.GIFS -> where += "p.post_type = 'GIF'"
            LibraryFilter.TEXT -> where += "p.post_type IN ('TEXT','LINK')"
            LibraryFilter.FAVORITES -> where += "pp.post_id IS NOT NULL"
            LibraryFilter.ALL -> {}
        }
        if (search.isNotBlank()) {
            search.trim().split(Regex("\\s+")).forEach { term ->
                where += "(p.title LIKE ? OR p.selftext LIKE ? OR p.author LIKE ? OR p.subreddit LIKE ? OR p.flair LIKE ? " +
                    "OR p.id IN (SELECT post_id FROM comments WHERE body LIKE ?))"
                repeat(6) { args += "%$term%" }
            }
        }
        if (subreddit != null) { where += "p.subreddit = ?"; args += subreddit }
        val order = when (sort) {
            LibrarySort.NEWEST -> "p.created_utc DESC"
            LibrarySort.OLDEST -> "p.created_utc ASC"
            LibrarySort.POPULAR -> "p.score DESC"
            LibrarySort.SUBREDDIT -> "p.subreddit COLLATE NOCASE, p.selection_rank"
            LibrarySort.RECENT_DOWNLOAD -> "p.downloaded_at DESC"
        }
        val sql = """SELECT p.*, pp.post_id AS prot,
              (SELECT COUNT(*) FROM post_media x WHERE x.post_id=p.id) AS media_count,
              (SELECT x.media_id FROM post_media x WHERE x.post_id=p.id ORDER BY x.position LIMIT 1) AS cover_id
            FROM posts p LEFT JOIN protected_posts pp ON pp.post_id = p.id
            ${if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")}
            ORDER BY $order LIMIT $limit"""
        val rows = query(sql, *args.toTypedArray()) { c ->
            Triple(readPost(c), !c.isNull(c.getColumnIndexOrThrow("prot")), c.int("media_count") to c.longOrNull("cover_id"))
        }
        val coverIds = rows.mapNotNull { it.third.second }
        val covers = mediaByIds(coverIds)
        return rows.map { (post, prot, mc) -> PostListItem(post, prot, mc.second?.let { covers[it] }, mc.first) }
    }

    fun countPosts(): Int = query("SELECT COUNT(*) FROM posts") { it.getInt(0) }.first()

    private fun readPost(c: Cursor) = Post(
        id = c.str("id"), subreddit = c.str("subreddit"), title = c.str("title"), author = c.str("author"),
        selftext = c.str("selftext"), url = c.str("url"), permalink = c.str("permalink"), domain = c.str("domain"),
        createdUtc = c.long("created_utc"), score = c.int("score"), numComments = c.int("num_comments"),
        upvoteRatio = c.getDouble(c.getColumnIndexOrThrow("upvote_ratio")), over18 = c.int("over18") == 1,
        flair = c.strOrNull("flair"), type = PostType.of(c.str("post_type")),
        offlineState = OfflineState.of(c.str("offline_state")), mediaNote = c.strOrNull("media_note"),
        inSelection = c.int("in_selection") == 1, selectionRank = c.int("selection_rank"),
        downloadedAt = c.long("downloaded_at"), updatedAt = c.long("updated_at"),
        commentsFetchedAt = c.long("comments_fetched_at"), removed = c.int("removed") == 1,
    )

    // ---------------------------------------------------------------- comments

    fun replaceComments(postId: String, comments: List<Comment>) = tx {
        db.delete("comments", "post_id=?", arrayOf(postId))
        comments.forEach { c ->
            db.insertWithOnConflict("comments", null, ContentValues().apply {
                put("id", c.id); put("post_id", postId); put("parent_id", c.parentId); put("depth", c.depth)
                put("position", c.position); put("author", c.author); put("body", c.body); put("score", c.score)
                put("created_utc", c.createdUtc)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
        db.update("posts", ContentValues().apply { put("comments_fetched_at", System.currentTimeMillis()) }, "id=?", arrayOf(postId))
    }

    fun comments(postId: String): List<Comment> = query("SELECT * FROM comments WHERE post_id=? ORDER BY position", postId) { c ->
        Comment(c.str("id"), c.str("post_id"), c.strOrNull("parent_id"), c.int("depth"), c.int("position"),
            c.str("author"), c.str("body"), c.int("score"), c.long("created_utc"))
    }

    // ---------------------------------------------------------------- media

    /** Returns the id of the media row for [sourceKey], creating it if needed (deduplicates shared media). */
    fun findOrCreateMedia(sourceKey: String, url: String, audioUrl: String?, kind: MediaKind, mime: String?, width: Int, height: Int, hasAudio: Boolean): Long {
        query("SELECT id FROM media WHERE source_key=?", sourceKey) { it.getLong(0) }.firstOrNull()?.let { id ->
            // Refresh the source URL (Reddit preview URLs are signed and can change); never touch local files.
            db.update("media", ContentValues().apply { put("url", url); put("audio_url", audioUrl) }, "id=? AND state!='COMPLETE'", arrayOf(id.toString()))
            return id
        }
        val id = db.insert("media", null, ContentValues().apply {
            put("source_key", sourceKey); put("url", url); put("audio_url", audioUrl); put("kind", kind.name)
            put("mime", mime); put("width", width); put("height", height); put("state", MediaState.PENDING.name)
            put("has_audio", hasAudio.int); put("updated_at", System.currentTimeMillis())
        })
        changed()
        return id
    }

    fun linkPostMedia(postId: String, mediaId: Long, position: Int, caption: String?) {
        db.insertWithOnConflict("post_media", null, ContentValues().apply {
            put("post_id", postId); put("media_id", mediaId); put("position", position); put("caption", caption)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun mediaForPost(postId: String): List<PostMedia> = query(
        "SELECT m.*, pm.position AS pos, pm.caption AS cap FROM post_media pm JOIN media m ON m.id=pm.media_id WHERE pm.post_id=? ORDER BY pm.position",
        postId
    ) { PostMedia(readMedia(it), it.int("pos"), it.strOrNull("cap")) }

    fun media(id: Long): Media? = query("SELECT * FROM media WHERE id=?", id.toString()) { readMedia(it) }.firstOrNull()

    private fun mediaByIds(ids: List<Long>): Map<Long, Media> {
        if (ids.isEmpty()) return emptyMap()
        return ids.chunked(500).flatMap { chunk ->
            query("SELECT * FROM media WHERE id IN (${chunk.joinToString(",")})") { readMedia(it) }
        }.associateBy { it.id }
    }

    fun setMediaState(id: Long, state: MediaState, error: String? = null, countAttempt: Boolean = false) {
        db.execSQL(
            "UPDATE media SET state=?, error=?, updated_at=?${if (countAttempt) ", attempts=attempts+1" else ""} WHERE id=?",
            arrayOf(state.name, error, System.currentTimeMillis(), id)
        )
        changed()
    }

    fun completeMedia(id: Long, location: String, thumb: String?, size: Long, sha: String?, mime: String?, width: Int, height: Int, hasAudio: Boolean) {
        db.update("media", ContentValues().apply {
            put("location", location); put("thumb_location", thumb); put("size_bytes", size); put("sha256", sha)
            put("mime", mime); put("state", MediaState.COMPLETE.name); put("error", null as String?)
            if (width > 0) put("width", width)
            if (height > 0) put("height", height)
            put("has_audio", hasAudio.int)
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id.toString()))
        db.delete("download_tasks", "media_id=?", arrayOf(id.toString()))
        changed()
    }

    /** Clears the local file reference of a media row (file found missing) so it is downloaded again. */
    fun invalidateMedia(id: Long, reason: String) {
        db.update("media", ContentValues().apply {
            put("location", null as String?); put("thumb_location", null as String?); put("size_bytes", 0)
            put("state", MediaState.PENDING.name); put("error", reason)
        }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun completeMediaBySha(sha: String): Media? =
        query("SELECT * FROM media WHERE sha256=? AND state='COMPLETE' AND location IS NOT NULL LIMIT 1", sha) { readMedia(it) }.firstOrNull()

    fun locationRefCount(location: String): Int = query("SELECT COUNT(*) FROM media WHERE location=? OR thumb_location=?", location, location) { it.getInt(0) }.first()

    fun orphanMedia(): List<Media> = query("SELECT * FROM media WHERE id NOT IN (SELECT media_id FROM post_media)") { readMedia(it) }

    fun deleteMediaRow(id: Long) { db.delete("media", "id=?", arrayOf(id.toString())); changed() }

    fun allCompleteMedia(): List<Media> = query("SELECT * FROM media WHERE state='COMPLETE'") { readMedia(it) }

    fun totalBytes(): Long = query("SELECT COALESCE(SUM(size_bytes),0) FROM media WHERE state='COMPLETE'") { it.getLong(0) }.first()

    fun resetFailedMedia(includeUnavailable: Boolean): Int {
        val states = if (includeUnavailable) "('FAILED','UNAVAILABLE','WAITING_STORAGE')" else "('FAILED','WAITING_STORAGE')"
        val n = db.update("media", ContentValues().apply {
            put("state", MediaState.PENDING.name); put("attempts", 0); put("error", null as String?)
        }, "state IN $states", null)
        changed()
        return n
    }

    fun clearFailedMedia(): List<String> {
        val posts = query(
            "SELECT DISTINCT pm.post_id FROM post_media pm JOIN media m ON m.id=pm.media_id WHERE m.state='FAILED'"
        ) { it.getString(0) }
        db.execSQL("UPDATE media SET state='SKIPPED', error='Download cancelled by user' WHERE state='FAILED'")
        changed()
        return posts
    }

    private fun readMedia(c: Cursor) = Media(
        id = c.long("id"), sourceKey = c.str("source_key"), url = c.str("url"), audioUrl = c.strOrNull("audio_url"),
        kind = MediaKind.valueOf(c.str("kind")), mime = c.strOrNull("mime"), width = c.int("width"),
        height = c.int("height"), location = c.strOrNull("location"), thumbLocation = c.strOrNull("thumb_location"),
        sizeBytes = c.long("size_bytes"), state = MediaState.of(c.str("state")), error = c.strOrNull("error"),
        attempts = c.int("attempts"), hasAudio = c.int("has_audio") == 1,
    )

    /** Derives the post's offline state from its media rows. */
    fun refreshPostState(postId: String) {
        val post = post(postId) ?: return
        val media = mediaForPost(postId).map { it.media }
        val (state, note) = when {
            media.isEmpty() -> (if (post.type == PostType.TEXT || post.type == PostType.LINK) OfflineState.TEXT_ONLY else OfflineState.UNAVAILABLE) to post.mediaNote
            media.all { it.state == MediaState.COMPLETE } -> OfflineState.COMPLETE to post.mediaNote?.takeIf { post.type == PostType.LINK }
            media.all { it.state == MediaState.UNAVAILABLE || it.state == MediaState.SKIPPED } ->
                OfflineState.UNAVAILABLE to (media.firstNotNullOfOrNull { it.error } ?: post.mediaNote)
            media.any { it.state == MediaState.COMPLETE } ->
                OfflineState.PARTIAL to (media.firstOrNull { it.state != MediaState.COMPLETE }?.error ?: "Some files are still downloading")
            media.any { it.state == MediaState.FAILED || it.state == MediaState.WAITING_STORAGE } ->
                OfflineState.PARTIAL to media.firstNotNullOfOrNull { it.error }
            else -> OfflineState.PENDING to null
        }
        setPostOfflineState(postId, state, note)
    }

    // ---------------------------------------------------------------- download tasks

    fun upsertTask(mediaId: Long, state: MediaState, partPath: String?, done: Long, total: Long, error: String?) {
        db.insertWithOnConflict("download_tasks", null, ContentValues().apply {
            put("media_id", mediaId); put("state", state.name); put("part_path", partPath); put("bytes_done", done)
            put("bytes_total", total); put("error", error); put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun taskPartPath(mediaId: Long): String? = query("SELECT part_path FROM download_tasks WHERE media_id=?", mediaId.toString()) { it.getString(0) }.firstOrNull()

    fun allPartPaths(): List<String> = query("SELECT part_path FROM download_tasks WHERE part_path IS NOT NULL") { it.getString(0) }

    /** Media that are not finished yet, with their post, for the download manager. */
    fun downloadQueue(limit: Int = 200): List<DownloadTaskInfo> = query(
        """SELECT m.id, m.kind, m.state, m.error, m.attempts, t.bytes_done, t.bytes_total,
             (SELECT p.id FROM post_media pm JOIN posts p ON p.id=pm.post_id WHERE pm.media_id=m.id LIMIT 1) AS pid,
             (SELECT p.title FROM post_media pm JOIN posts p ON p.id=pm.post_id WHERE pm.media_id=m.id LIMIT 1) AS ptitle,
             (SELECT p.subreddit FROM post_media pm JOIN posts p ON p.id=pm.post_id WHERE pm.media_id=m.id LIMIT 1) AS psub
           FROM media m LEFT JOIN download_tasks t ON t.media_id=m.id
           WHERE m.state NOT IN ('COMPLETE','SKIPPED')
           ORDER BY CASE m.state WHEN 'DOWNLOADING' THEN 0 WHEN 'PENDING' THEN 1 WHEN 'WAITING_STORAGE' THEN 2 WHEN 'FAILED' THEN 3 ELSE 4 END, m.updated_at DESC
           LIMIT $limit"""
    ) { c ->
        DownloadTaskInfo(mediaId = c.getLong(0), kind = MediaKind.valueOf(c.getString(1)), postId = c.getString(7), postTitle = c.getString(8),
            subreddit = c.getString(9), state = MediaState.of(c.getString(2)), bytesDone = if (c.isNull(5)) 0 else c.getLong(5),
            bytesTotal = if (c.isNull(6)) 0 else c.getLong(6), error = c.getString(3), attempts = c.getInt(4))
    }

    // ---------------------------------------------------------------- statistics

    fun storageStats(): StorageStats {
        val counts = query(
            "SELECT kind, COUNT(*) FROM media WHERE state='COMPLETE' AND id IN (SELECT media_id FROM post_media) GROUP BY kind"
        ) { it.getString(0) to it.getInt(1) }.toMap()
        val protectedBytes = query(
            """SELECT COALESCE(SUM(size_bytes),0) FROM media WHERE state='COMPLETE' AND id IN
               (SELECT pm.media_id FROM post_media pm JOIN protected_posts pp ON pp.post_id=pm.post_id)"""
        ) { it.getLong(0) }.first()
        val total = totalBytes()
        val failed = query("SELECT COUNT(*) FROM media WHERE state='FAILED'") { it.getInt(0) }.first()
        val pending = query("SELECT COUNT(*) FROM media WHERE state IN ('PENDING','DOWNLOADING','WAITING_STORAGE')") { it.getInt(0) }.first()
        return StorageStats(
            totalBytes = total, posts = countPosts(), images = counts["IMAGE"] ?: 0, videos = counts["VIDEO"] ?: 0,
            gifs = counts["GIF"] ?: 0, protectedBytes = protectedBytes, autoBytes = (total - protectedBytes).coerceAtLeast(0),
            perSubreddit = bytesPerSubreddit(), failedMedia = failed, pendingMedia = pending,
        )
    }

    // ---------------------------------------------------------------- sync history

    fun startHistory(sub: String?, trigger: String): Long {
        val id = db.insert("sync_history", null, ContentValues().apply {
            put("subreddit", sub); put("trigger_kind", trigger); put("started_at", System.currentTimeMillis()); put("status", "RUNNING")
        })
        changed()
        return id
    }

    fun finishHistory(id: Long, status: String, checked: Int, newPosts: Int, downloaded: Int, failed: Int, removed: Int, bytes: Long, message: String?) {
        db.update("sync_history", ContentValues().apply {
            put("finished_at", System.currentTimeMillis()); put("status", status); put("posts_checked", checked)
            put("new_posts", newPosts); put("downloaded_media", downloaded); put("failed_media", failed)
            put("removed_posts", removed); put("bytes", bytes); put("message", message)
        }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun deleteHistoryIfEmpty(id: Long) { db.delete("sync_history", "id=?", arrayOf(id.toString())); changed() }

    /** Any entry left RUNNING by a killed process is closed as interrupted. */
    fun closeInterruptedHistory() {
        db.execSQL("UPDATE sync_history SET status='INTERRUPTED', finished_at=started_at WHERE status='RUNNING'")
        db.execSQL("UPDATE media SET state='PENDING' WHERE state='DOWNLOADING'")
        db.execSQL("UPDATE subreddits SET last_status='Interrupted' WHERE last_status='Syncing…'")
        changed()
    }

    fun history(limit: Int = 50): List<SyncHistoryEntry> = query("SELECT * FROM sync_history ORDER BY started_at DESC LIMIT $limit") { c ->
        SyncHistoryEntry(c.long("id"), c.strOrNull("subreddit"), c.str("trigger_kind"), c.long("started_at"), c.long("finished_at"),
            c.str("status"), c.int("posts_checked"), c.int("new_posts"), c.int("downloaded_media"), c.int("failed_media"),
            c.int("removed_posts"), c.long("bytes"), c.strOrNull("message"))
    }

    fun lastSuccessfulSync(): Long = query("SELECT COALESCE(MAX(last_success),0) FROM subreddits") { it.getLong(0) }.first()

    // ---------------------------------------------------------------- helpers

    private fun <T> query(sql: String, vararg args: String, map: (Cursor) -> T): List<T> {
        helper.readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<T>(c.count)
            while (c.moveToNext()) out += map(c)
            return out
        }
    }
}

private val Boolean.int get() = if (this) 1 else 0
private fun Cursor.str(col: String): String = getString(getColumnIndexOrThrow(col)) ?: ""
private fun Cursor.strOrNull(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
private fun Cursor.int(col: String): Int = getInt(getColumnIndexOrThrow(col))
private fun Cursor.long(col: String): Long = getLong(getColumnIndexOrThrow(col))
private fun Cursor.longOrNull(col: String): Long? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }
