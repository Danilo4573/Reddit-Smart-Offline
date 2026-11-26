package com.smartoffline.reddit.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import com.smartoffline.reddit.data.Prefs
import com.smartoffline.reddit.data.StorageTarget
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

class StorageUnavailableException(message: String) : IOException(message)
class InsufficientStorageException(message: String) : IOException(message)

data class StorageOption(val label: String, val detail: String, val target: StorageTarget)

/**
 * Physical storage of downloaded media, separate from the database.
 *
 * A location string identifies one file: `file:/abs/path` (app storage / SD card app folder) or
 * `doc:content://…` (a document in a folder picked through the Storage Access Framework).
 * Files already downloaded keep their location when the user picks a new target; only new
 * downloads go to the new place.
 */
class MediaStorage(private val context: Context, private val prefs: Prefs) {
    private val resolver get() = context.contentResolver

    val thumbDir: File get() = File(context.filesDir, "thumbs").apply { mkdirs() }
    val partialDir: File get() = File(context.noBackupFilesDir, "partial").apply { mkdirs() }
    val workDir: File get() = File(context.cacheDir, "work").apply { mkdirs() }

    fun options(): List<StorageOption> {
        val list = mutableListOf(StorageOption("App storage", "Private to this app on internal storage", StorageTarget.Internal))
        context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
            if (Environment.isExternalStorageRemovable(dir) && Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED) {
                list += StorageOption("SD card", dir.absolutePath, StorageTarget.AppFolder(dir.absolutePath))
            }
        }
        return list
    }

    private fun fileDir(target: StorageTarget): File? = when (target) {
        StorageTarget.Internal -> File(context.filesDir, "media")
        is StorageTarget.AppFolder -> File(target.path, "media")
        is StorageTarget.Tree -> null
    }

    /** Returns null when the current target can be written to, or a user-facing reason otherwise. */
    fun checkAvailable(target: StorageTarget = prefs.value.storage): String? = when (target) {
        StorageTarget.Internal -> null
        is StorageTarget.AppFolder -> {
            val dir = File(target.path)
            if (Environment.getExternalStorageState(dir) != Environment.MEDIA_MOUNTED) "The SD card is not available. Insert it or choose another storage location."
            else null
        }
        is StorageTarget.Tree -> {
            val uri = Uri.parse(target.uri)
            val granted = resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
            if (!granted) "Access to the selected folder was lost. Choose the folder again in Settings → Storage."
            else try {
                val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use {
                    if (it.moveToFirst()) null else "The selected folder no longer exists. Choose another storage location."
                } ?: "The selected folder is not available. Choose another storage location."
            } catch (e: Exception) {
                "The selected folder is not available (${e.javaClass.simpleName}). Choose another storage location."
            }
        }
    }

    fun takeTreePermission(uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    fun releaseTreePermission(uri: String) {
        runCatching {
            resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    fun describe(target: StorageTarget): String = when (target) {
        StorageTarget.Internal -> "App storage (internal)"
        is StorageTarget.AppFolder -> "SD card (${target.path})"
        is StorageTarget.Tree -> {
            val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(target.uri)) }.getOrNull() ?: target.uri
            "Folder: " + id.replace("primary:", "Internal storage/").replace(":", "/")
        }
    }

    /** Free bytes on the volume of the current target, or null if it cannot be determined (SAF). */
    fun freeBytes(target: StorageTarget = prefs.value.storage): Long? {
        val dir = fileDir(target) ?: return null
        dir.mkdirs()
        return runCatching { StatFs(dir.path).availableBytes }.getOrNull()
    }

    /** Moves a finished temporary file into the current storage target. Returns its location. */
    @Throws(IOException::class)
    fun commit(tmp: File, name: String, mime: String): String {
        val target = prefs.value.storage
        checkAvailable(target)?.let { throw StorageUnavailableException(it) }
        val size = tmp.length()
        fileDir(target)?.let { dir ->
            dir.mkdirs()
            val free = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
            if (free < size + 20L * 1024 * 1024) throw InsufficientStorageException("Not enough free space on the device to save more media.")
            val dest = File(dir, name)
            if (!tmp.renameTo(dest)) {
                tmp.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
                tmp.delete()
            }
            if (!dest.exists() || dest.length() != size) {
                dest.delete()
                throw IOException("The file could not be saved completely.")
            }
            return "file:" + dest.absolutePath
        }
        val tree = Uri.parse((target as StorageTarget.Tree).uri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = try {
            DocumentsContract.createDocument(resolver, parent, mime, name)
        } catch (e: Exception) {
            throw StorageUnavailableException("Could not create a file in the selected folder: ${e.message}")
        } ?: throw StorageUnavailableException("Could not create a file in the selected folder.")
        try {
            resolver.openOutputStream(doc, "w")!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
        } catch (e: IOException) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            if (e.message?.contains("ENOSPC") == true) throw InsufficientStorageException("The selected folder's storage is full.")
            throw e
        }
        val location = "doc:$doc"
        if (size(location) != size) {
            delete(location)
            throw IOException("The file could not be saved completely in the selected folder.")
        }
        tmp.delete()
        return location
    }

    fun openInput(location: String): InputStream? = try {
        when {
            location.startsWith("file:") -> FileInputStream(location.removePrefix("file:"))
            location.startsWith("doc:") -> resolver.openInputStream(Uri.parse(location.removePrefix("doc:")))
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    fun uri(location: String): Uri = when {
        location.startsWith("file:") -> Uri.fromFile(File(location.removePrefix("file:")))
        else -> Uri.parse(location.removePrefix("doc:"))
    }

    fun file(location: String): File? = if (location.startsWith("file:")) File(location.removePrefix("file:")) else null

    fun size(location: String): Long = when {
        location.startsWith("file:") -> File(location.removePrefix("file:")).let { if (it.exists()) it.length() else -1 }
        else -> try {
            resolver.query(Uri.parse(location.removePrefix("doc:")), arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
                if (it.moveToFirst()) it.getLong(0) else -1
            } ?: -1
        } catch (_: Exception) {
            -1
        }
    }

    fun exists(location: String) = size(location) > 0

    fun delete(location: String) {
        try {
            when {
                location.startsWith("file:") -> File(location.removePrefix("file:")).delete()
                location.startsWith("doc:") -> DocumentsContract.deleteDocument(resolver, Uri.parse(location.removePrefix("doc:")))
            }
        } catch (_: Exception) {
        }
    }
}
