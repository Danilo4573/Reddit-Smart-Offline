package com.smartoffline.reddit.net

import com.smartoffline.reddit.data.Comment
import com.smartoffline.reddit.data.MediaKind
import com.smartoffline.reddit.data.OfflineState
import com.smartoffline.reddit.data.Post
import com.smartoffline.reddit.data.PostType
import org.json.JSONArray
import org.json.JSONObject

/** A downloadable file found in a post. */
data class MediaCandidate(
    val sourceKey: String,
    val url: String,
    val kind: MediaKind,
    val mime: String?,
    val width: Int = 0,
    val height: Int = 0,
    val caption: String? = null,
    /** For Reddit-hosted videos: DASH manifest used to find the best video and the audio stream. */
    val dashUrl: String? = null,
    val hasAudio: Boolean = false,
)

data class ParsedPost(val post: Post, val media: List<MediaCandidate>)

object PostParser {

    fun parse(d: JSONObject, rank: Int): ParsedPost {
        val source = d.optJSONArray("crosspost_parent_list")?.optJSONObject(0) ?: d
        var note: String? = null
        val media = mutableListOf<MediaCandidate>()
        var type: PostType

        val url = source.optString("url_overridden_by_dest").ifBlank { source.optString("url") }
        val redditVideo = source.optJSONObject("secure_media")?.optJSONObject("reddit_video")
            ?: source.optJSONObject("media")?.optJSONObject("reddit_video")
        val previewVideo = source.optJSONObject("preview")?.optJSONObject("reddit_video_preview")
        val metadata = source.optJSONObject("media_metadata")

        when {
            source.optBoolean("is_gallery") && metadata != null -> {
                type = PostType.GALLERY
                val items = source.optJSONObject("gallery_data")?.optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val meta = metadata.optJSONObject(item.optString("media_id")) ?: continue
                    metadataCandidate(meta, item.optString("caption").ifBlank { null })?.let { media += it }
                }
                if (media.isEmpty()) note = "The gallery's images are no longer available on Reddit."
            }
            redditVideo != null -> {
                val isGif = redditVideo.optBoolean("is_gif")
                type = if (isGif) PostType.GIF else PostType.VIDEO
                val fallback = redditVideo.optString("fallback_url")
                media += MediaCandidate(
                    sourceKey = "vreddit:" + videoId(fallback, url),
                    url = fallback, kind = if (isGif) MediaKind.GIF else MediaKind.VIDEO, mime = "video/mp4",
                    width = redditVideo.optInt("width"), height = redditVideo.optInt("height"),
                    dashUrl = redditVideo.optString("dash_url").ifBlank { null },
                    hasAudio = !isGif && redditVideo.optBoolean("has_audio", true),
                )
            }
            isImageUrl(url) -> {
                val gif = url.substringBefore('?').endsWith(".gif", true)
                type = if (gif) PostType.GIF else PostType.IMAGE
                val previewImage = source.optJSONObject("preview")?.optJSONArray("images")?.optJSONObject(0)
                if (gif) {
                    val mp4 = previewImage?.optJSONObject("variants")?.optJSONObject("mp4")?.optJSONObject("source")
                    media += if (mp4 != null) MediaCandidate("url:" + key(url) + "#mp4", mp4.optString("url"), MediaKind.GIF, "video/mp4", mp4.optInt("width"), mp4.optInt("height"))
                    else MediaCandidate("url:" + key(url), url, MediaKind.GIF, "image/gif")
                } else {
                    val src = previewImage?.optJSONObject("source")
                    media += MediaCandidate("url:" + key(url), url, MediaKind.IMAGE, null, src?.optInt("width") ?: 0, src?.optInt("height") ?: 0)
                }
            }
            url.contains("imgur.com") && url.endsWith(".gifv") -> {
                type = PostType.GIF
                val mp4 = url.removeSuffix(".gifv") + ".mp4"
                media += MediaCandidate("url:" + key(mp4), mp4, MediaKind.GIF, "video/mp4")
            }
            previewVideo != null -> {
                // GIF-like videos hosted elsewhere; Reddit provides an MP4 transcode.
                type = PostType.GIF
                val fallback = previewVideo.optString("fallback_url")
                media += MediaCandidate("vpreview:" + key(fallback), fallback, MediaKind.GIF, "video/mp4",
                    previewVideo.optInt("width"), previewVideo.optInt("height"))
            }
            source.optBoolean("is_self") -> {
                type = PostType.TEXT
                // Images embedded in text posts.
                if (metadata != null) {
                    metadata.keys().forEach { k -> metadata.optJSONObject(k)?.let { metadataCandidate(it, null) }?.let { media += it } }
                }
            }
            else -> {
                type = PostType.LINK
                val host = source.optString("domain")
                val src = source.optJSONObject("preview")?.optJSONArray("images")?.optJSONObject(0)?.optJSONObject("source")
                if (src != null) media += MediaCandidate("url:" + key(src.optString("url")), src.optString("url"), MediaKind.IMAGE, null, src.optInt("width"), src.optInt("height"))
                note = if (source.optJSONObject("secure_media")?.optJSONObject("oembed") != null || source.optString("post_hint") == "rich:video")
                    "Video hosted on $host can't be downloaded. Only the preview image is saved."
                else "Link to $host. The linked page isn't saved offline${if (src != null) "; the preview image is." else "."}"
            }
        }

        val post = Post(
            id = d.optString("id"),
            subreddit = d.optString("subreddit"),
            title = d.optString("title"),
            author = d.optString("author", "[deleted]"),
            selftext = d.optString("selftext").ifBlank { if (source !== d) source.optString("selftext") else "" },
            url = url,
            permalink = "https://www.reddit.com" + d.optString("permalink"),
            domain = source.optString("domain"),
            createdUtc = d.optLong("created_utc") * 1000,
            score = d.optInt("score"),
            numComments = d.optInt("num_comments"),
            upvoteRatio = d.optDouble("upvote_ratio", 0.0).let { if (it.isNaN()) 0.0 else it },
            over18 = d.optBoolean("over_18"),
            flair = d.optString("link_flair_text").takeIf { it.isNotBlank() && it != "null" },
            type = type,
            offlineState = if (media.isEmpty()) (if (type == PostType.TEXT || type == PostType.LINK) OfflineState.TEXT_ONLY else OfflineState.UNAVAILABLE) else OfflineState.PENDING,
            mediaNote = note,
            inSelection = true,
            selectionRank = rank,
            downloadedAt = 0, updatedAt = 0, commentsFetchedAt = 0,
            removed = d.optString("removed_by_category").let { it.isNotBlank() && it != "null" },
        )
        return ParsedPost(post, media.distinctBy { it.sourceKey })
    }

    private fun metadataCandidate(meta: JSONObject, caption: String?): MediaCandidate? {
        if (meta.optString("status") != "valid") return null
        val s = meta.optJSONObject("s") ?: return null
        val w = s.optInt("x"); val h = s.optInt("y")
        val id = meta.optString("id")
        return when (meta.optString("e")) {
            "AnimatedImage" -> {
                val mp4 = s.optString("mp4").ifBlank { null }
                if (mp4 != null) MediaCandidate("redditmedia:$id#mp4", mp4, MediaKind.GIF, "video/mp4", w, h, caption)
                else s.optString("gif").ifBlank { null }?.let { MediaCandidate("redditmedia:$id", it, MediaKind.GIF, "image/gif", w, h, caption) }
            }
            "Image" -> s.optString("u").ifBlank { null }?.let { MediaCandidate("redditmedia:$id", it, MediaKind.IMAGE, meta.optString("m").ifBlank { null }, w, h, caption) }
            else -> null
        }
    }

    private fun isImageUrl(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp") || path.endsWith(".gif")
    }

    private fun videoId(fallback: String, url: String): String =
        Regex("v\\.redd\\.it/([A-Za-z0-9]+)").find(fallback)?.groupValues?.get(1)
            ?: Regex("v\\.redd\\.it/([A-Za-z0-9]+)").find(url)?.groupValues?.get(1)
            ?: key(fallback)

    /** Stable key: host + path, without signed query parameters. */
    private fun key(url: String) = url.substringBefore('?').substringAfter("://").replace("preview.redd.it", "i.redd.it")

    /** Flattens the top-ranked comments with a limited number of replies, keeping hierarchy. */
    fun parseComments(postId: String, children: JSONArray, topLevel: Int, replies: Int, maxDepth: Int = 2): List<Comment> {
        val out = mutableListOf<Comment>()
        fun walk(arr: JSONArray, parent: String?, depth: Int, limit: Int) {
            var taken = 0
            for (i in 0 until arr.length()) {
                if (taken >= limit) return
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("kind") != "t1") continue
                val c = o.optJSONObject("data") ?: continue
                val body = c.optString("body")
                if (body == "[removed]" || body == "[deleted]") continue
                taken++
                val id = c.optString("id")
                out += Comment(id, postId, parent, depth, out.size, c.optString("author", "[deleted]"), body,
                    c.optInt("score"), c.optLong("created_utc") * 1000)
                if (depth < maxDepth && replies > 0) {
                    val r = c.optJSONObject("replies")?.optJSONObject("data")?.optJSONArray("children")
                    if (r != null) walk(r, id, depth + 1, if (depth == 0) replies else (replies + 1) / 2)
                }
            }
        }
        walk(children, null, 0, topLevel)
        return out
    }
}
