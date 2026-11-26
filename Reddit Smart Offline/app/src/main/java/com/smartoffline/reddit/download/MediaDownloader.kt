package com.smartoffline.reddit.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Xml
import com.smartoffline.reddit.data.Media
import com.smartoffline.reddit.data.MediaKind
import com.smartoffline.reddit.data.MediaState
import com.smartoffline.reddit.data.Repository
import com.smartoffline.reddit.net.Http
import com.smartoffline.reddit.storage.InsufficientStorageException
import com.smartoffline.reddit.storage.MediaStorage
import com.smartoffline.reddit.storage.StorageUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.security.MessageDigest

sealed class DownloadResult {
    data class Done(val bytes: Long) : DownloadResult()
    /** The file is gone from the source (deleted, removed, expired). Not retried automatically. */
    data class Unavailable(val reason: String) : DownloadResult()
    /** Temporary failure; partial data is kept so the next attempt resumes. */
    data class Failed(val reason: String) : DownloadResult()
    data class NoSpace(val reason: String) : DownloadResult()
    data class StorageMissing(val reason: String) : DownloadResult()
}

private class GoneException(message: String) : IOException(message)

/**
 * Downloads one media file: resumable transfer into a private partial file, video/audio merge for
 * Reddit-hosted videos, integrity checks, duplicate detection and finally a move into [MediaStorage].
 * A media row is only marked COMPLETE after the stored file was verified.
 */
class MediaDownloader(
    private val repo: Repository,
    private val storage: MediaStorage,
    private val waitIfPaused: suspend () -> Unit,
) {
    /** [onProgress] receives (bytesDone, bytesTotal). [fitsLimit] decides whether a finished file may be stored. */
    suspend fun download(media: Media, onProgress: (Long, Long) -> Unit, fitsLimit: (Long) -> Boolean): DownloadResult = withContext(Dispatchers.IO) {
        if (media.state == MediaState.COMPLETE && media.location != null && storage.exists(media.location)) return@withContext DownloadResult.Done(0)
        repo.setMediaState(media.id, MediaState.DOWNLOADING, null)
        val work = storage.workDir
        val tmpFinal = File(work, "m${media.id}.out")
        try {
            val isVideo = media.mime == "video/mp4" || media.kind == MediaKind.VIDEO
            var videoUrl = media.url
            var audioUrl: String? = null
            if (media.audioUrl != null || (media.kind == MediaKind.VIDEO && media.url.contains("v.redd.it"))) {
                val streams = resolveDash(media)
                videoUrl = streams.first ?: media.url
                audioUrl = if (media.hasAudio) streams.second else null
            }

            val videoPart = File(storage.partialDir, "m${media.id}.v.part")
            fetch(media.id, videoUrl, videoPart, onProgress)
            var hasAudio = false
            if (audioUrl != null) {
                val audioPart = File(storage.partialDir, "m${media.id}.a.part")
                val gotAudio = try {
                    fetch(media.id, audioUrl, audioPart, onProgress); true
                } catch (_: GoneException) {
                    false // Video without a sound track.
                }
                if (gotAudio) {
                    mux(videoPart, audioPart, tmpFinal)
                    audioPart.delete(); videoPart.delete()
                    hasAudio = true
                } else {
                    videoPart.renameTo(tmpFinal)
                }
            } else {
                if (!videoPart.renameTo(tmpFinal)) { videoPart.copyTo(tmpFinal, true); videoPart.delete() }
            }

            // Verify the content really is what we expect before storing it.
            val mime = sniffMime(tmpFinal) ?: throw IOException("The downloaded file is not a valid image or video.")
            var width = media.width
            var height = media.height
            if (mime.startsWith("image/")) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tmpFinal.path, o)
                if (o.outWidth <= 0) throw IOException("The downloaded image is damaged.")
                width = o.outWidth; height = o.outHeight
            } else {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(tmpFinal.path)
                    if (ex.trackCount == 0) throw IOException("The downloaded video is damaged.")
                } catch (e: IOException) {
                    throw IOException("The downloaded video could not be read.")
                } finally {
                    ex.release()
                }
            }
            val size = tmpFinal.length()
            val sha = sha256(tmpFinal)

            // Same bytes already stored for another post? Reuse that file.
            val dup = repo.completeMediaBySha(sha)?.takeIf { it.id != media.id && it.location != null && storage.exists(it.location) }
            if (dup != null) {
                tmpFinal.delete()
                repo.completeMedia(media.id, dup.location!!, dup.thumbLocation, dup.sizeBytes, sha, dup.mime, width, height, hasAudio || dup.hasAudio)
                return@withContext DownloadResult.Done(0)
            }

            if (!fitsLimit(size)) {
                tmpFinal.delete()
                return@withContext DownloadResult.NoSpace("Storage limit reached. Free up space or raise the limit in Settings.")
            }

            val thumb = makeThumb(media.id, tmpFinal, mime)
            val ext = extFor(mime)
            val location = storage.commit(tmpFinal, "rso_${media.id}_${sha.take(10)}.$ext", mime)
            repo.completeMedia(media.id, location, thumb, size, sha, mime, width, height, hasAudio)
            DownloadResult.Done(size)
        } catch (e: GoneException) {
            File(storage.partialDir, "m${media.id}.v.part").delete()
            File(storage.partialDir, "m${media.id}.a.part").delete()
            DownloadResult.Unavailable(e.message ?: "No longer available")
        } catch (e: InsufficientStorageException) {
            DownloadResult.NoSpace(e.message ?: "Not enough storage")
        } catch (e: StorageUnavailableException) {
            DownloadResult.StorageMissing(e.message ?: "Storage unavailable")
        } catch (e: InterruptedIOException) {
            DownloadResult.Failed("The connection timed out. The download will resume later.")
        } catch (e: IOException) {
            DownloadResult.Failed(e.message ?: "Network error. The download will resume later.")
        } finally {
            tmpFinal.delete()
        }
    }

    /** Resumable HTTP download into [part]. Throws [GoneException] for files that no longer exist. */
    private suspend fun fetch(mediaId: Long, url: String, part: File, onProgress: (Long, Long) -> Unit) {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            waitIfPaused()
            val existing = if (part.exists()) part.length() else 0L
            val headers = if (existing > 0) mapOf("Range" to "bytes=$existing-") else emptyMap()
            val c = Http.open(url, headers = headers)
            c.readTimeout = 45_000
            try {
                val code = try { c.responseCode } catch (e: IOException) { throw IOException("Network error while downloading.") }
                if (code == 416 && existing > 0) return // Already complete.
                if (code == 403 || code == 404 || code == 410) throw GoneException(
                    if (code == 403) "Reddit no longer allows access to this file (it may have been removed or the link expired)."
                    else "This file was deleted from Reddit."
                )
                if (code == 429 || code >= 500) {
                    if (++attempt > 2) throw IOException("The media server is busy (HTTP $code). It will be retried later.")
                    kotlinx.coroutines.delay(5000L * attempt)
                    continue
                }
                if (code != 200 && code != 206) throw IOException("Download failed (HTTP $code).")
                val append = code == 206
                val length = c.getHeaderFieldLong("Content-Length", -1)
                val total = if (length > 0) (if (append) existing + length else length) else -1
                var done = if (append) existing else 0L
                repo.upsertTask(mediaId, MediaState.DOWNLOADING, part.path, done, total, null)
                c.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 300) {
                                lastReport = now
                                onProgress(done, total)
                                currentCoroutineContext().ensureActive()
                                waitIfPaused()
                            }
                        }
                    }
                }
                onProgress(done, total)
                if (total > 0 && part.length() != total) {
                    repo.upsertTask(mediaId, MediaState.FAILED, part.path, part.length(), total, "Incomplete")
                    throw IOException("The download was interrupted. It will resume where it stopped.")
                }
                repo.upsertTask(mediaId, MediaState.DOWNLOADING, part.path, done, total, null)
                return
            } finally {
                c.disconnect()
            }
        }
    }

    /** Picks the best video and the audio stream from the DASH manifest, with fallbacks. */
    private fun resolveDash(media: Media): Pair<String?, String?> {
        val dash = media.audioUrl
        val base = media.url.substringBefore('?').substringBeforeLast('/')
        if (dash != null) try {
            val r = Http.request(dash)
            if (r.code == 200) {
                data class Rep(val audio: Boolean, val bandwidth: Long, val url: String)
                val reps = mutableListOf<Rep>()
                val p = Xml.newPullParser()
                p.setInput(r.body.reader())
                var setAudio = false
                var repAudio = false
                var repBw = 0L
                var inBase = false
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    when (p.eventType) {
                        XmlPullParser.START_TAG -> when (p.name) {
                            "AdaptationSet" -> setAudio = (p.getAttributeValue(null, "contentType") ?: p.getAttributeValue(null, "mimeType") ?: "").startsWith("audio")
                            "Representation" -> {
                                repAudio = setAudio || (p.getAttributeValue(null, "mimeType") ?: "").startsWith("audio")
                                repBw = p.getAttributeValue(null, "bandwidth")?.toLongOrNull() ?: 0
                            }
                            "BaseURL" -> inBase = true
                        }
                        XmlPullParser.TEXT -> if (inBase) {
                            val t = p.text.trim()
                            if (t.isNotEmpty()) reps += Rep(repAudio, repBw, if (t.startsWith("http")) t else "$base/$t")
                        }
                        XmlPullParser.END_TAG -> if (p.name == "BaseURL") inBase = false
                    }
                }
                val video = reps.filter { !it.audio }.maxByOrNull { it.bandwidth }?.url
                val audio = reps.filter { it.audio }.maxByOrNull { it.bandwidth }?.url
                if (video != null) return video to audio
            }
        } catch (_: Exception) {
        }
        // Older videos: the audio track has a predictable name.
        if (!media.hasAudio) return null to null
        for (name in listOf("DASH_AUDIO_128.mp4", "DASH_AUDIO_64.mp4", "DASH_audio.mp4")) {
            try {
                val c = Http.open("$base/$name", "HEAD")
                val ok = c.responseCode == 200
                c.disconnect()
                if (ok) return null to "$base/$name"
            } catch (_: IOException) {
            }
        }
        return null to null
    }

    /** Combines Reddit's separate video and audio streams into one MP4 without re-encoding. */
    private fun mux(video: File, audio: File, out: File) {
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vEx = MediaExtractor()
        val aEx = MediaExtractor()
        try {
            vEx.setDataSource(video.path)
            aEx.setDataSource(audio.path)
            val vi = (0 until vEx.trackCount).firstOrNull { vEx.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: throw IOException("The video stream is damaged.")
            val ai = (0 until aEx.trackCount).firstOrNull { aEx.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            vEx.selectTrack(vi)
            val vFormat = vEx.getTrackFormat(vi)
            val vTrack = muxer.addTrack(vFormat)
            var aTrack = -1
            if (ai != null) { aEx.selectTrack(ai); aTrack = muxer.addTrack(aEx.getTrackFormat(ai)) }
            muxer.start()
            val size = maxOf(
                if (vFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) vFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0,
                4 * 1024 * 1024
            )
            val buffer = ByteBuffer.allocate(size)
            fun copy(ex: MediaExtractor, track: Int) {
                val info = MediaCodec.BufferInfo()
                while (true) {
                    buffer.clear()
                    val n = ex.readSampleData(buffer, 0)
                    if (n < 0) break
                    info.offset = 0
                    info.size = n
                    info.presentationTimeUs = ex.sampleTime
                    info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(track, buffer, info)
                    ex.advance()
                }
            }
            copy(vEx, vTrack)
            if (aTrack >= 0) copy(aEx, aTrack)
            muxer.stop()
        } catch (e: IllegalStateException) {
            throw IOException("The video and audio could not be combined.")
        } finally {
            vEx.release(); aEx.release()
            runCatching { muxer.release() }
        }
    }

    private fun makeThumb(id: Long, file: File, mime: String): String? = try {
        val bmp: Bitmap? = if (mime.startsWith("video/")) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.path)
                r.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.frameAtTime
            } finally {
                r.release()
            }
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= 640) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        bmp?.let {
            val scale = minOf(1f, 720f / it.width)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(it, (it.width * scale).toInt().coerceAtLeast(1), (it.height * scale).toInt().coerceAtLeast(1), true) else it
            val out = File(storage.thumbDir, "t$id.jpg")
            out.outputStream().use { s -> small.compress(Bitmap.CompressFormat.JPEG, 82, s) }
            "file:" + out.absolutePath
        }
    } catch (_: Exception) {
        null
    }

    private fun sniffMime(f: File): String? {
        val b = ByteArray(12)
        val n = f.inputStream().use { it.read(b) }
        if (n < 12) return null
        return when {
            b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
            b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "image/png"
            String(b, 0, 4) == "GIF8" -> "image/gif"
            String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WEBP" -> "image/webp"
            String(b, 4, 4) == "ftyp" || String(b, 4, 4) == "moof" || String(b, 4, 4) == "styp" -> "video/mp4"
            else -> null
        }
    }

    private fun extFor(mime: String) = when (mime) {
        "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "mp4"
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
