package com.diegonmarcos.superapp.headless

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** #1498 the remote backends: Cloudy-Browser-Headless speaks headless-api v1 itself; Cloudflare Browser Run (edge-md-scraper Worker) answers Markdown for an `open`. */
object HeadlessRemote {
    fun forward(route: HeadlessRoute, path: String, body: JSONObject, timeoutMs: Int = 60_000): JSONObject {
        if (!route.usable) return HeadlessApi.fail("denied", route.reason ?: "backend not configured")
        return runCatching {
            when (route.backend) {
                HeadlessBackend.CLOUDY -> post("${route.baseUrl}$path", route.bearer!!, body, timeoutMs)
                HeadlessBackend.CLOUDFLARE -> cloudflare(route, path, body, timeoutMs)
                else -> HeadlessApi.fail("internal", "not a remote backend")
            }
        }.getOrElse { HeadlessApi.fail("internal", it.message ?: it.javaClass.simpleName) }
    }

    private fun post(url: String, bearer: String, body: JSONObject, timeoutMs: Int): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = timeoutMs; c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $bearer"); c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val s = (if (c.responseCode < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return runCatching { JSONObject(s) }.getOrElse { HeadlessApi.fail("internal", "HTTP ${c.responseCode}") }
        } finally { c.disconnect() }
    }

    /** Only `open` (the Worker renders the page to Markdown) and `run` of such steps; everything else is not offered by this backend. */
    private fun cloudflare(route: HeadlessRoute, path: String, body: JSONObject, timeoutMs: Int): JSONObject {
        if (path != "/command" || body.optString("cmd") != "open") return HeadlessApi.fail("bad_request", "Cloudflare-Browser-Run offers open (Markdown) only")
        val u = "${route.baseUrl}/?url=" + URLEncoder.encode(body.getString("url"), "UTF-8")
        val c = URL(u).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = timeoutMs; c.setRequestProperty("Authorization", "Bearer ${route.bearer}")
            if (c.responseCode >= 400) return HeadlessApi.fail("internal", "Cloudflare answered ${c.responseCode}")
            return HeadlessApi.ok(body.optString("session").ifEmpty { "cf" }, JSONObject().put("url", body.getString("url")).put("markdown", c.inputStream.bufferedReader().use { it.readText() }))
        } finally { c.disconnect() }
    }
}
