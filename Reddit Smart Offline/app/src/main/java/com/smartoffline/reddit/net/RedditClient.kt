package com.smartoffline.reddit.net

import android.net.Uri
import android.util.Base64
import com.smartoffline.reddit.data.Prefs
import com.smartoffline.reddit.data.SecureStore
import com.smartoffline.reddit.data.SortMode
import com.smartoffline.reddit.data.TimeRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** Errors with messages written for the user. */
sealed class RedditException(message: String) : Exception(message) {
    class Network(message: String = "Network error. Check your connection and try again.") : RedditException(message)
    class RateLimited(message: String = "Reddit's rate limit was reached. Synchronization will continue later.") : RedditException(message)
    class AuthExpired(message: String = "Your Reddit sign-in has expired. Sign in again in Settings.") : RedditException(message)
    class AuthFailed(message: String) : RedditException(message)
    class NotFound(message: String) : RedditException(message)
    class Forbidden(message: String) : RedditException(message)
    class Server(message: String) : RedditException(message)
}

data class SubredditInfo(
    val name: String,
    val title: String,
    val iconUrl: String?,
    val description: String,
    val subscribers: Long,
    val over18: Boolean,
)

enum class AccessMode(val label: String) {
    ACCOUNT("Reddit account"), APP_GUEST("Guest (app-only OAuth)"), PUBLIC_GUEST("Guest (public access)")
}

/**
 * Reddit Data API client.
 *
 * - Account mode: OAuth 2 authorization-code flow with the user's own "installed app" client id.
 * - Guest mode with a client id: application-only OAuth ("installed_client" grant), no account needed.
 * - Guest mode without a client id: Reddit's public read-only JSON endpoints, at a slow, polite rate.
 *
 * Requests are serialized and spaced according to Reddit's rate-limit headers.
 */
class RedditClient(private val prefs: Prefs, private val secure: SecureStore) {
    companion object {
        const val REDIRECT_URI = "redditsmartoffline://auth"
        const val SCOPES = "identity read mysubreddits history"
        private const val OAUTH = "https://oauth.reddit.com"
        private const val PUBLIC = "https://www.reddit.com"
    }

    private val gate = Mutex()
    private var nextAllowedAt = 0L

    val mode: AccessMode
        get() = when {
            !prefs.value.guestMode && secure.get("refresh") != null -> AccessMode.ACCOUNT
            prefs.value.clientId.isNotBlank() -> AccessMode.APP_GUEST
            else -> AccessMode.PUBLIC_GUEST
        }

    // ------------------------------------------------------------------ OAuth

    fun authorizeUrl(): String? {
        val clientId = prefs.value.clientId.trim().ifBlank { return null }
        val state = UUID.randomUUID().toString()
        prefs.setRaw("oauth_state", state)
        return Uri.parse("https://www.reddit.com/api/v1/authorize.compact").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("state", state)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("duration", "permanent")
            .appendQueryParameter("scope", SCOPES)
            .build().toString()
    }

    /** Completes sign-in from the redirect. Returns the username. */
    suspend fun completeAuthorization(uri: Uri): String = withContext(Dispatchers.IO) {
        uri.getQueryParameter("error")?.let {
            throw RedditException.AuthFailed(if (it == "access_denied") "Sign-in was cancelled." else "Reddit refused the sign-in ($it).")
        }
        val expected = prefs.raw("oauth_state")
        if (expected == null || uri.getQueryParameter("state") != expected) throw RedditException.AuthFailed("Sign-in response did not match the request. Please try again.")
        val code = uri.getQueryParameter("code") ?: throw RedditException.AuthFailed("Reddit did not return an authorization code.")
        val json = tokenRequest(mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to REDIRECT_URI))
        val refresh = json.optString("refresh_token").ifBlank { throw RedditException.AuthFailed("Reddit did not grant offline access.") }
        secure.put("refresh", refresh)
        storeAccess("access", json)
        prefs.setRaw("oauth_state", null)
        prefs.update { it.copy(guestMode = false, authExpired = false) }
        val me = getJson("/api/v1/me", emptyMap()) as JSONObject
        val name = me.optString("name")
        prefs.update { it.copy(username = name) }
        name
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val refresh = secure.get("refresh")
        if (refresh != null && prefs.value.clientId.isNotBlank()) {
            runCatching {
                Http.request("$PUBLIC/api/v1/revoke_token", "POST", mapOf("Authorization" to basicAuth()),
                    mapOf("token" to refresh, "token_type_hint" to "refresh_token"))
            }
        }
        secure.put("refresh", null); secure.put("access", null)
        prefs.update { it.copy(guestMode = true, username = null, authExpired = false) }
    }

    /** Forgets cached app-only tokens, e.g. after the client id changed. */
    fun resetAppToken() { secure.put("app_access", null); secure.put("access", null) }

    private fun basicAuth() = "Basic " + Base64.encodeToString("${prefs.value.clientId.trim()}:".toByteArray(), Base64.NO_WRAP)

    private fun tokenRequest(form: Map<String, String>): JSONObject {
        val r = try {
            Http.request("$PUBLIC/api/v1/access_token", "POST", mapOf("Authorization" to basicAuth()), form)
        } catch (e: IOException) {
            throw RedditException.Network()
        }
        val json = runCatching { JSONObject(r.body) }.getOrNull()
        if (r.code == 401) throw RedditException.AuthFailed("Reddit rejected the client ID. Check it in Settings → Reddit account.")
        if (r.code != 200 || json == null || json.has("error")) {
            val err = json?.optString("error") ?: "HTTP ${r.code}"
            if (err == "invalid_grant") throw RedditException.AuthExpired()
            throw RedditException.AuthFailed("Reddit authentication failed ($err).")
        }
        return json
    }

    private fun storeAccess(name: String, json: JSONObject) {
        secure.put(name, json.getString("access_token"))
        prefs.setRaw("${name}_expiry", (System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000 - 60_000).toString())
    }

    private fun validToken(name: String): String? {
        val exp = prefs.raw("${name}_expiry")?.toLongOrNull() ?: 0
        return if (exp > System.currentTimeMillis()) secure.get(name) else null
    }

    /** Returns a bearer token for the current mode, or null in public mode. */
    private fun accessToken(forceRefresh: Boolean): String? = when (mode) {
        AccessMode.ACCOUNT -> {
            if (!forceRefresh) validToken("access")?.let { return it }
            val refresh = secure.get("refresh") ?: throw RedditException.AuthExpired()
            try {
                storeAccess("access", tokenRequest(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh)))
            } catch (e: RedditException.AuthExpired) {
                secure.put("refresh", null)
                prefs.update { it.copy(authExpired = true, guestMode = true) }
                throw e
            }
            secure.get("access")
        }
        AccessMode.APP_GUEST -> {
            if (!forceRefresh) validToken("app_access")?.let { return it }
            var device = prefs.raw("device_id")
            if (device == null) { device = UUID.randomUUID().toString().replace("-", "").take(30); prefs.setRaw("device_id", device) }
            storeAccess("app_access", tokenRequest(mapOf("grant_type" to "https://oauth.reddit.com/grants/installed_client", "device_id" to device)))
            secure.get("app_access")
        }
        AccessMode.PUBLIC_GUEST -> null
    }

    // ------------------------------------------------------------------ requests

    private suspend fun getJson(path: String, params: Map<String, String>): Any = withContext(Dispatchers.IO) {
        var authRetried = false
        var rateRetries = 0
        while (true) {
            val response = gate.withLock {
                val wait = nextAllowedAt - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                val token = accessToken(false)
                val base = if (token != null) OAUTH + path else "$PUBLIC$path.json"
                val url = Uri.parse(base).buildUpon().apply {
                    params.forEach { (k, v) -> appendQueryParameter(k, v) }
                    appendQueryParameter("raw_json", "1")
                }.build().toString()
                val headers = if (token != null) mapOf("Authorization" to "bearer $token") else emptyMap()
                val r = try {
                    Http.request(url, headers = headers)
                } catch (e: IOException) {
                    nextAllowedAt = System.currentTimeMillis() + 1000
                    throw RedditException.Network()
                }
                // Space requests: OAuth clients get ~100/min, public access far fewer.
                val minGap = if (token != null) 650L else 6500L
                val remaining = r.header("x-ratelimit-remaining")?.toDoubleOrNull()
                val reset = r.header("x-ratelimit-reset")?.toLongOrNull()
                nextAllowedAt = System.currentTimeMillis() + if (remaining != null && remaining < 2 && reset != null) reset * 1000 else minGap
                r
            }
            when (response.code) {
                in 200..299 -> {
                    val body = response.body.trim()
                    if (body.startsWith("<")) throw RedditException.Forbidden("Reddit returned a web page instead of data. Public access may be blocked — add a Reddit app client ID in Settings.")
                    return@withContext if (body.startsWith("[")) JSONArray(body) else JSONObject(body)
                }
                401 -> {
                    if (mode == AccessMode.PUBLIC_GUEST || authRetried) throw RedditException.AuthExpired()
                    authRetried = true
                    accessToken(true)
                }
                429 -> {
                    val wait = response.header("retry-after")?.toLongOrNull() ?: response.header("x-ratelimit-reset")?.toLongOrNull() ?: 60
                    if (++rateRetries > 2 || wait > 180) throw RedditException.RateLimited()
                    delay(wait * 1000)
                }
                403 -> {
                    val reason = runCatching { JSONObject(response.body).optString("reason") }.getOrNull().orEmpty()
                    throw RedditException.Forbidden(when (reason) {
                        "private" -> "This community is private."
                        "quarantined" -> "This community is quarantined and can't be accessed through the API."
                        "banned" -> "This community has been banned."
                        "gated" -> "This community requires an account with a confirmed opt-in."
                        else -> if (mode == AccessMode.PUBLIC_GUEST) "Reddit blocked public access. Add a Reddit app client ID in Settings to continue in guest mode." else "Access denied by Reddit."
                    })
                }
                404 -> throw RedditException.NotFound("Not found on Reddit. It may have been deleted, banned or never existed.")
                in 500..599 -> throw RedditException.Server("Reddit is having problems (HTTP ${response.code}). Try again later.")
                else -> throw RedditException.Server("Unexpected response from Reddit (HTTP ${response.code}).")
            }
        }
    }

    // ------------------------------------------------------------------ API

    private fun parseSub(d: JSONObject): SubredditInfo {
        val icon = d.optString("community_icon").ifBlank { d.optString("icon_img") }.takeIf { it.startsWith("http") }
        return SubredditInfo(
            name = d.optString("display_name"), title = d.optString("title"), iconUrl = icon,
            description = d.optString("public_description"), subscribers = d.optLong("subscribers"),
            over18 = d.optBoolean("over18"),
        )
    }

    suspend fun about(name: String): SubredditInfo {
        val clean = normalizeName(name) ?: throw RedditException.NotFound("“$name” is not a valid subreddit name.")
        val json = getJson("/r/$clean/about", emptyMap()) as JSONObject
        if (json.optString("kind") != "t5") throw RedditException.NotFound("r/$clean doesn't exist.")
        return parseSub(json.getJSONObject("data"))
    }

    suspend fun searchSubreddits(query: String, includeNsfw: Boolean): List<SubredditInfo> {
        val json = getJson("/subreddits/search", mapOf("q" to query, "limit" to "25", "include_over_18" to if (includeNsfw) "on" else "off")) as JSONObject
        return children(json).filter { it.optString("kind") == "t5" }.map { parseSub(it.getJSONObject("data")) }
    }

    suspend fun subscribed(): List<SubredditInfo> {
        if (mode != AccessMode.ACCOUNT) throw RedditException.AuthFailed("Sign in with a Reddit account to see your subscriptions.")
        val out = mutableListOf<SubredditInfo>()
        var after: String? = null
        repeat(10) {
            val json = getJson("/subreddits/mine/subscriber", buildMap { put("limit", "100"); after?.let { put("after", it) } }) as JSONObject
            out += children(json).map { parseSub(it.getJSONObject("data")) }
            after = json.optJSONObject("data")?.optString("after")?.takeIf { it.isNotBlank() && it != "null" }
            if (after == null) return out
        }
        return out
    }

    /** One page of a subreddit listing. Returns (posts, after). */
    suspend fun listing(sub: String, sort: SortMode, range: TimeRange, after: String?, limit: Int): Pair<List<JSONObject>, String?> {
        val params = buildMap {
            put("limit", limit.coerceIn(1, 100).toString())
            if (sort == SortMode.TOP) put("t", range.api)
            after?.let { put("after", it) }
        }
        val json = getJson("/r/$sub/${sort.api}", params) as JSONObject
        val posts = children(json).filter { it.optString("kind") == "t3" }.map { it.getJSONObject("data") }
        return posts to json.optJSONObject("data")?.optString("after")?.takeIf { it.isNotBlank() && it != "null" }
    }

    suspend fun searchPosts(query: String, sub: String?, includeNsfw: Boolean): List<JSONObject> {
        val path = if (sub != null) "/r/$sub/search" else "/search"
        val params = buildMap {
            put("q", query); put("limit", "25"); put("sort", "relevance"); put("type", "link")
            if (sub != null) put("restrict_sr", "1")
            put("include_over_18", if (includeNsfw) "on" else "off")
        }
        val json = getJson(path, params) as JSONObject
        return children(json).filter { it.optString("kind") == "t3" }.map { it.getJSONObject("data") }
    }

    /** Top-ranked comments for a post. Returns the comment listing's children. */
    suspend fun comments(postId: String, topLevel: Int, depth: Int): JSONArray {
        val json = getJson("/comments/$postId", mapOf("sort" to "top", "limit" to (topLevel * 3).coerceIn(10, 500).toString(), "depth" to depth.toString())) as JSONArray
        return json.optJSONObject(1)?.optJSONObject("data")?.optJSONArray("children") ?: JSONArray()
    }

    suspend fun post(postId: String): JSONObject? {
        val json = getJson("/comments/$postId", mapOf("limit" to "1", "depth" to "1")) as JSONArray
        return json.optJSONObject(0)?.let { children(it) }?.firstOrNull()?.optJSONObject("data")
    }

    private fun children(listing: JSONObject): List<JSONObject> {
        val arr = listing.optJSONObject("data")?.optJSONArray("children") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    fun normalizeName(input: String): String? {
        val n = input.trim().removePrefix("https://").removePrefix("www.").removePrefix("reddit.com").removePrefix("/")
            .removePrefix("r/").removePrefix("R/").trim('/').substringBefore('/')
        return n.takeIf { Regex("^[A-Za-z0-9][A-Za-z0-9_]{1,20}$").matches(it) }
    }
}
