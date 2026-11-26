package com.smartoffline.reddit.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { DARK, LIGHT, SYSTEM }

/** Where new media files are written. */
sealed class StorageTarget {
    data object Internal : StorageTarget()
    /** An app-specific folder on a removable volume (SD card); no permission needed. */
    data class AppFolder(val path: String) : StorageTarget()
    /** A folder picked by the user through the Storage Access Framework. */
    data class Tree(val uri: String) : StorageTarget()

    fun encode(): String = when (this) {
        Internal -> "internal"
        is AppFolder -> "dir:$path"
        is Tree -> "tree:$uri"
    }

    companion object {
        fun decode(v: String?): StorageTarget = when {
            v == null -> Internal
            v.startsWith("dir:") -> AppFolder(v.removePrefix("dir:"))
            v.startsWith("tree:") -> Tree(v.removePrefix("tree:"))
            else -> Internal
        }
    }
}

data class AppPrefs(
    val theme: ThemeMode = ThemeMode.DARK,
    val autoDownloads: Boolean = true,
    val wifiOnly: Boolean = false,
    val requireCharging: Boolean = false,
    val batteryNotLow: Boolean = true,
    val storageLimitMb: Long = 0, // 0 = unlimited
    val storage: StorageTarget = StorageTarget.Internal,
    val storageUnavailable: Boolean = false,
    val clientId: String = "",
    val username: String? = null,
    val guestMode: Boolean = true,
    val authExpired: Boolean = false,
    val defaultSort: SortMode = SortMode.HOT,
    val defaultCount: Int = 25,
    val defaultInterval: Int = 12,
    val defaultComments: Boolean = true,
    val defaultCommentCount: Int = 20,
    val showNsfw: Boolean = false,
    val onboarded: Boolean = false,
) {
    fun newSubredditSettings(name: String) = SubredditSettings(
        subreddit = name, sort = defaultSort, postCount = defaultCount, intervalHours = defaultInterval,
        downloadComments = defaultComments, commentCount = defaultCommentCount,
    )
}

/** User preferences, persisted in the `user_preferences` table and exposed as a [StateFlow]. */
class Prefs(private val repo: Repository) {
    private val state = MutableStateFlow(load())
    val flow: StateFlow<AppPrefs> = state
    val value: AppPrefs get() = state.value

    private fun load(): AppPrefs {
        val m = repo.allPrefs()
        val d = AppPrefs()
        return AppPrefs(
            theme = m["theme"]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            autoDownloads = m["autoDownloads"]?.toBoolean() ?: d.autoDownloads,
            wifiOnly = m["wifiOnly"]?.toBoolean() ?: d.wifiOnly,
            requireCharging = m["requireCharging"]?.toBoolean() ?: d.requireCharging,
            batteryNotLow = m["batteryNotLow"]?.toBoolean() ?: d.batteryNotLow,
            storageLimitMb = m["storageLimitMb"]?.toLongOrNull() ?: d.storageLimitMb,
            storage = StorageTarget.decode(m["storage"]),
            storageUnavailable = m["storageUnavailable"]?.toBoolean() ?: false,
            clientId = m["clientId"] ?: "",
            username = m["username"],
            guestMode = m["guestMode"]?.toBoolean() ?: true,
            authExpired = m["authExpired"]?.toBoolean() ?: false,
            defaultSort = SortMode.of(m["defaultSort"]),
            defaultCount = m["defaultCount"]?.toIntOrNull() ?: d.defaultCount,
            defaultInterval = m["defaultInterval"]?.toIntOrNull() ?: d.defaultInterval,
            defaultComments = m["defaultComments"]?.toBoolean() ?: d.defaultComments,
            defaultCommentCount = m["defaultCommentCount"]?.toIntOrNull() ?: d.defaultCommentCount,
            showNsfw = m["showNsfw"]?.toBoolean() ?: false,
            onboarded = m["onboarded"]?.toBoolean() ?: false,
        )
    }

    @Synchronized
    fun update(transform: (AppPrefs) -> AppPrefs) {
        val old = state.value
        val new = transform(old)
        if (new == old) return
        fun put(k: String, a: Any?, b: Any?) { if (a != b) repo.setPref(k, b?.toString()) }
        put("theme", old.theme, new.theme)
        put("autoDownloads", old.autoDownloads, new.autoDownloads)
        put("wifiOnly", old.wifiOnly, new.wifiOnly)
        put("requireCharging", old.requireCharging, new.requireCharging)
        put("batteryNotLow", old.batteryNotLow, new.batteryNotLow)
        put("storageLimitMb", old.storageLimitMb, new.storageLimitMb)
        if (old.storage != new.storage) repo.setPref("storage", new.storage.encode())
        put("storageUnavailable", old.storageUnavailable, new.storageUnavailable)
        put("clientId", old.clientId, new.clientId)
        put("username", old.username, new.username)
        put("guestMode", old.guestMode, new.guestMode)
        put("authExpired", old.authExpired, new.authExpired)
        put("defaultSort", old.defaultSort.name, new.defaultSort.name)
        put("defaultCount", old.defaultCount, new.defaultCount)
        put("defaultInterval", old.defaultInterval, new.defaultInterval)
        put("defaultComments", old.defaultComments, new.defaultComments)
        put("defaultCommentCount", old.defaultCommentCount, new.defaultCommentCount)
        put("showNsfw", old.showNsfw, new.showNsfw)
        put("onboarded", old.onboarded, new.onboarded)
        state.value = new
    }

    /** Raw access for values that are not part of [AppPrefs] (encrypted tokens, device id…). */
    fun raw(key: String): String? = repo.allPrefs()[key]
    fun setRaw(key: String, value: String?) = repo.setPref(key, value)
}
