package com.smartoffline.reddit.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpResponse(val code: Int, val headers: Map<String, List<String>>, val body: String) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
}

/** Minimal HTTP client on top of [HttpURLConnection]. */
object Http {
    const val USER_AGENT = "android:com.smartoffline.reddit:v1.0 (Reddit Smart Offline)"

    fun open(url: String, method: String = "GET", headers: Map<String, String> = emptyMap()): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return c
    }

    @Throws(IOException::class)
    fun request(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), form: Map<String, String>? = null): HttpResponse {
        val c = open(url, method, headers)
        try {
            if (form != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val body = form.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return HttpResponse(code, c.headerFields.filterKeys { it != null }, text)
        } finally {
            c.disconnect()
        }
    }

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
