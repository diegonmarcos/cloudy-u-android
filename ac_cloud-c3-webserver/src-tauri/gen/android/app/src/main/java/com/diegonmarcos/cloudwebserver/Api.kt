package com.diegonmarcos.cloudwebserver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * #1682 P1 the Compose UI's one door into the Rust core: the server's own loopback admin API (`http://127.0.0.1:<runtime.port>`),
 * the routes the web shell (server/ui/shell.html) reads, so the native pages and the web page show the same data. GET returns the
 * parsed JSON; POST sends the shell's write header (x-c3-write) and returns the reply or the server's `error`. Every call runs on
 * the IO dispatcher, has short timeouts and never throws: a failure is a [Reply.Err] with the reason to show.
 */
object Api {
    sealed interface Reply {
        data class Ok(val value: Any) : Reply
        data class Err(val reason: String) : Reply
    }

    /** Every admin route the native pages read (test/test-c3-webserver-native.sh holds this list equal to the web shell's). */
    const val POWER = "/__app__/api/power"
    const val ABOUT = "/__app__/api/about"
    const val HEALTH = "/__api__/health"
    const val SERVED = "/__app__/api/served"
    const val SSHD = "/__app__/api/sshd"
    const val SERVING = "/__app__/api/serving"
    const val CONFIG = "/__app__/api/config"
    const val ENGINES = "/__app__/api/engines"
    const val LINKS = "/__app__/api/links"
    const val ROUTES = "/__app__/api/routes"
    const val SUBPAGES = "/__app__/api/subpages"
    const val TOPOLOGY = "/__app__/api/topology"
    const val ROOTS = "/__app__/api/roots"
    const val ROOTS_LS = "/__app__/api/roots/ls"
    const val ADB_SHELL = "/__app__/api/adb-shell"
    const val CONFIGS_OPEN = "/__app__/api/configs-open"
    const val OPEN = "/__app__/api/open"

    fun base(): String = "http://127.0.0.1:${Core.port}"

    fun url(path: String): String = base() + path

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** `/__app__/root/<id>/<path>`, each segment encoded (the web shell's rootUrl). */
    fun rootPath(id: String, path: String): String =
        "/__app__/root/" + enc(id) + "/" + path.split('/').filter { it.isNotEmpty() }.joinToString("/") { enc(it) }

    suspend fun get(path: String): Reply = withContext(Dispatchers.IO) { call("GET", path, null) }

    suspend fun post(path: String, body: JSONObject): Reply = withContext(Dispatchers.IO) { call("POST", path, body) }

    private fun call(method: String, path: String, body: JSONObject?): Reply = try {
        val c = URL(url(path)).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 3000
            c.readTimeout = 8000
            c.useCaches = false
            c.setRequestProperty("cache-control", "no-store")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.setRequestProperty("x-c3-write", "1")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val parsed = runCatching { JSONTokener(text).nextValue() }.getOrNull()
            when {
                code !in 200..299 -> Reply.Err((parsed as? JSONObject)?.optString("error")?.takeIf { it.isNotBlank() } ?: "$code $path")
                parsed is JSONObject || parsed is JSONArray -> Reply.Ok(parsed)
                else -> Reply.Err("$path answered no JSON")
            }
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        Reply.Err(
            if (e is java.net.ConnectException) "the server is not answering on 127.0.0.1:${Core.port} (${e.message ?: "refused"})"
            else e.message ?: e.javaClass.simpleName,
        )
    }
}

/** A link href as the server wrote it, made absolute against the admin origin (the web shell resolved it against the page). */
fun Api.absolute(href: String): String = runCatching { java.net.URI(base() + "/").resolve(href.trim()).toString() }.getOrDefault(href)
