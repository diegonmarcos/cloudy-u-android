package com.diegonmarcos.superapp.scraper

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class ScraperApiException(val code: Int, message: String) : IOException(message)

/**
 * #1429 the client of the scrappers-api container (one API that always exists). Bases are tried in order:
 * the mesh name first, the public bearer path second (fleet addressing). A refused connection moves to the
 * next base; an HTTP error from a base that answered is final.
 */
class ScraperApi(
    private val bases: List<String> = DEFAULT_BASES,
    private val bearer: String? = null,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    data class Download(val fileName: String, val mime: String, val length: Long)

    fun <T> downloadYoutube(body: JSONObject, sink: (InputStream, Download) -> T): T =
        post("/download/youtube", body) { c ->
            val name = fileNameOf(c.getHeaderField("Content-Disposition"), "youtube-download")
            val mime = c.contentType?.substringBefore(';')?.trim().orEmpty().ifBlank { "application/octet-stream" }
            c.inputStream.use { sink(it, Download(name, mime, c.contentLengthLong)) }
        }

    private fun <T> post(path: String, body: JSONObject, ok: (HttpURLConnection) -> T): T {
        var last: IOException? = null
        for (b in bases) {
            val c = open(URL(b.trimEnd('/') + path))
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 8_000
                c.readTimeout = 300_000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Accept", "*/*")
                bearer?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
                try {
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                } catch (e: IOException) {
                    last = e; continue          // this base did not answer: try the next
                }
                val code = c.responseCode
                if (code !in 200..299) {
                    val err = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                    throw ScraperApiException(code, detailOf(err) ?: "HTTP $code")
                }
                return ok(c)
            } finally {
                c.disconnect()
            }
        }
        throw last ?: IOException("no scraper API base configured")
    }

    companion object {
        /** Mesh first, public second (rules/addressing). The Cloud Run URL joins this list when it is declared. */
        val DEFAULT_BASES = listOf(
            "http://scrappers-api.mesh.diegonmarcos.com:3020",
            "https://api.diegonmarcos.com/scrappers",
        )

        /** The file name of a Content-Disposition header (filename*= wins over filename=). */
        fun fileNameOf(header: String?, fallback: String): String {
            if (header.isNullOrBlank()) return fallback
            Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)""").find(header)?.let {
                return runCatching { java.net.URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") }.getOrDefault(fallback).safeName(fallback)
            }
            Regex("""filename\s*=\s*"([^"]*)"|filename\s*=\s*([^;]+)""").find(header)?.let {
                return (it.groupValues[1].ifEmpty { it.groupValues[2] }).trim().safeName(fallback)
            }
            return fallback
        }

        private fun String.safeName(fallback: String) = substringAfterLast('/').substringAfterLast('\\').ifBlank { fallback }

        /** FastAPI's {"detail": "..."} error text. */
        fun detailOf(body: String): String? = runCatching { JSONObject(body).optString("detail").ifBlank { null } }.getOrNull()
    }
}
