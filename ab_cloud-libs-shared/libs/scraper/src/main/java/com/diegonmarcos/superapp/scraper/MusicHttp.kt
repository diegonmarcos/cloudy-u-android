package com.diegonmarcos.superapp.scraper

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MusicHttpException(val code: Int, message: String) : IOException(message)

/** #1459 the one HTTP caller of the music connectors: 429 and 5xx retry with backoff (Retry-After wins), other errors are final. */
class MusicHttp(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val maxTries: Int = 4,
) {
    fun get(url: String, headers: Map<String, String> = emptyMap()): String = call("GET", url, headers, null, null)

    fun postJson(url: String, body: JSONObject, headers: Map<String, String> = emptyMap()): String =
        call("POST", url, headers, "application/json", body.toString())

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): String =
        call("POST", url, headers, "application/x-www-form-urlencoded", form(form))

    private fun call(method: String, url: String, headers: Map<String, String>, type: String?, body: String?): String {
        var attempt = 0
        while (true) {
            val c = open(URL(url))
            try {
                c.requestMethod = method
                c.connectTimeout = 10_000
                c.readTimeout = 30_000
                c.setRequestProperty("Accept", "application/json")
                for ((k, v) in headers) c.setRequestProperty(k, v)
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", type)
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = c.responseCode
                if (code in 200..299) return c.inputStream.bufferedReader().use { it.readText() }
                val err = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                if ((code == 429 || code >= 500) && ++attempt < maxTries) {
                    val wait = c.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1000) ?: (500L shl attempt)
                    sleep(minOf(wait, 30_000L))
                    continue
                }
                throw MusicHttpException(code, err.take(300).ifBlank { "HTTP $code" })
            } finally {
                c.disconnect()
            }
        }
    }

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
        fun form(m: Map<String, String>): String = m.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
    }
}
