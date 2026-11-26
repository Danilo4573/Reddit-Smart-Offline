package com.smartoffline.reddit.data

/** How Reddit should order a subreddit listing. */
enum class SortMode(val api: String, val label: String) {
    HOT("hot", "Hot"), NEW("new", "New"), TOP("top", "Top"), RISING("rising", "Rising");

    companion object {
        fun of(v: String?) = entries.firstOrNull { it.name == v } ?: HOT
    }
}

enum class TimeRange(val api: String, val label: String) {
    DAY("day", "Today"), WEEK("week", "This week"), MONTH("month", "This month"),
    YEAR("year", "This year"), ALL("all", "All time");

    companion object {
        fun of(v: String?) = entries.firstOrNull { it.name == v } ?: DAY
    }
}

enum class PostType(val label: String) {
    TEXT("Text"), IMAGE("Image"), GALLERY("Gallery"), VIDEO("Video"), GIF("GIF"), LINK("Link");

    companion object {
        fun of(v: String?) = entries.firstOrNull { it.name == v } ?: TEXT
    }
}

enum class MediaKind { IMAGE, VIDEO, GIF }

/** State of a single media file. Only COMPLETE files are ever shown as downloaded. */
enum class MediaState { PENDING, DOWNLOADING, COMPLETE, FAILED, UNAVAILABLE, WAITING_STORAGE, SKIPPED;

    companion object {
        fun of(v: String?) = entries.firstOrNull { it.name == v } ?: PENDING
    }
}

/** Overall offline state of a post, derived from its media. */
enum class OfflineState(val label: String) {
    COMPLETE("Fully downloaded"), PARTIAL("Partially downloaded"), PENDING("Waiting to download"),
    UNAVAILABLE("Media unavailable"), TEXT_ONLY("Saved");

    companion object {
        fun of(v: String?) = entries.firstOrNull { it.name == v } ?: PENDING
    }
}

data class SubredditSettings(
    val subreddit: String,
    val sort: SortMode = SortMode.HOT,
    val timeRange: TimeRange = TimeRange.DAY,
    val postCount: Int = 25,
    val intervalHours: Int = 12,
    val downloadImages: Boolean = true,
    val downloadVideos: Boolean = true,
    val downloadGifs: Boolean = true,
    val downloadText: Boolean = true,
    val downloadComments: Boolean = true,
    val commentCount: Int = 20,
    val replyCount: Int = 3,
    val autoSync: Boolean = true,
)

data class Subreddit(
    val name: String,
    val title: String,
    val iconUrl: String?,
    val iconLocation: String?,
    val description: String,
    val subscribers: Long,
    val over18: Boolean,
    val addedAt: Long,
    val lastSync: Long,
    val lastSuccess: Long,
    val lastStatus: String?,
    val lastError: String?,
)

data class SubredditOverview(
    val sub: Subreddit,
    val settings: SubredditSettings,
    val postCount: Int,
    val bytes: Long,
)

data class Post(
    val id: String,
    val subreddit: String,
    val title: String,
    val author: String,
    val selftext: String,
    val url: String,
    val permalink: String,
    val domain: String,
    val createdUtc: Long,
    val score: Int,
    val numComments: Int,
    val upvoteRatio: Double,
    val over18: Boolean,
    val flair: String?,
    val type: PostType,
    val offlineState: OfflineState,
    val mediaNote: String?,
    val inSelection: Boolean,
    val selectionRank: Int,
    val downloadedAt: Long,
    val updatedAt: Long,
    val commentsFetchedAt: Long,
    val removed: Boolean,
)

data class Media(
    val id: Long,
    val sourceKey: String,
    val url: String,
    val audioUrl: String?,
    val kind: MediaKind,
    val mime: String?,
    val width: Int,
    val height: Int,
    val location: String?,
    val thumbLocation: String?,
    val sizeBytes: Long,
    val state: MediaState,
    val error: String?,
    val attempts: Int,
    val hasAudio: Boolean,
)

data class PostMedia(val media: Media, val position: Int, val caption: String?)

data class PostListItem(
    val post: Post,
    val protected: Boolean,
    val cover: Media?,
    val mediaCount: Int,
)

data class Comment(
    val id: String,
    val postId: String,
    val parentId: String?,
    val depth: Int,
    val position: Int,
    val author: String,
    val body: String,
    val score: Int,
    val createdUtc: Long,
)

data class SyncHistoryEntry(
    val id: Long,
    val subreddit: String?,
    val trigger: String,
    val startedAt: Long,
    val finishedAt: Long,
    val status: String,
    val postsChecked: Int,
    val newPosts: Int,
    val downloadedMedia: Int,
    val failedMedia: Int,
    val removedPosts: Int,
    val bytes: Long,
    val message: String?,
)

data class DownloadTaskInfo(
    val mediaId: Long,
    val postId: String?,
    val postTitle: String?,
    val subreddit: String?,
    val kind: MediaKind,
    val state: MediaState,
    val bytesDone: Long,
    val bytesTotal: Long,
    val error: String?,
    val attempts: Int,
)

data class StorageStats(
    val totalBytes: Long,
    val posts: Int,
    val images: Int,
    val videos: Int,
    val gifs: Int,
    val protectedBytes: Long,
    val autoBytes: Long,
    val perSubreddit: List<Pair<String, Long>>,
    val failedMedia: Int,
    val pendingMedia: Int,
)

enum class LibraryFilter(val label: String) { ALL("All"), IMAGES("Images"), VIDEOS("Videos"), GIFS("GIFs"), TEXT("Text"), FAVORITES("Favorites") }

enum class LibrarySort(val label: String) { NEWEST("Newest"), OLDEST("Oldest"), POPULAR("Popularity"), SUBREDDIT("Subreddit"), RECENT_DOWNLOAD("Recently downloaded") }
