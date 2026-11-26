package com.diegonmarcos.clouddrive.sync

import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocketFactory
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * #1934 THE HOSTING-PLATFORM COMMANDS of the control sheet's Manage and Workflow tabs, as exact REST requests (GitHub's API shape): Rename, Archive and
 * Delete Remote change the repository on the platform, so [HostingApiTest] verifies each one against a LOCAL MOCK that asserts the exact request, and no
 * test ever sends one to a real repository. The read-only ones (repo list, PRs, workflows, runs, size) are run against the live API by the CI walk.
 * HTTP/1.1 over a plain socket on purpose: HttpURLConnection has no PATCH.
 */
internal object HostingApi {
    data class Request(val method: String, val path: String, val body: String? = null)
    data class Response(val status: Int, val body: String)

    fun rename(owner: String, name: String, newName: String) = Request("PATCH", "/repos/$owner/$name", buildJsonObject { put("name", newName) }.toString())
    fun archive(owner: String, name: String) = Request("PATCH", "/repos/$owner/$name", buildJsonObject { put("archived", true) }.toString())
    fun deleteRepo(owner: String, name: String) = Request("DELETE", "/repos/$owner/$name")

    // Read-only: safe to run against the live API.
    fun repoList(perPage: Int = 100) = Request("GET", "/user/repos?per_page=$perPage&affiliation=owner&sort=full_name")
    fun publicRepos(owner: String, perPage: Int = 100) = Request("GET", "/users/$owner/repos?per_page=$perPage&sort=full_name")
    fun pulls(owner: String, name: String) = Request("GET", "/repos/$owner/$name/pulls?state=open&per_page=30")
    fun workflows(owner: String, name: String) = Request("GET", "/repos/$owner/$name/actions/workflows")
    fun runs(owner: String, name: String) = Request("GET", "/repos/$owner/$name/actions/runs?per_page=10")
    fun repo(owner: String, name: String) = Request("GET", "/repos/$owner/$name")

    /** The state-changing requests: a test must never send these to the real API. */
    fun changesState(r: Request) = r.method != "GET"

    /** Sends [r] to [baseUrl] with a bearer [token] (blank = anonymous). */
    fun send(baseUrl: String, token: String, r: Request): Result<Response> = runCatching {
        val u = URI(baseUrl.trimEnd('/') + r.path)
        val tls = u.scheme == "https"
        val port = if (u.port > 0) u.port else if (tls) 443 else 80
        val sock: Socket = if (tls) SSLSocketFactory.getDefault().createSocket(u.host, port) else Socket(u.host, port)
        sock.soTimeout = 20_000
        sock.use { s ->
            val body = r.body?.toByteArray(Charsets.UTF_8)
            val head = StringBuilder()
            head.append("${r.method} ${u.rawPath}${if (u.rawQuery != null) "?" + u.rawQuery else ""} HTTP/1.1\r\n")
            head.append("Host: ${u.host}${if (u.port > 0) ":" + u.port else ""}\r\nAccept: application/vnd.github+json\r\nUser-Agent: cloud-drive\r\nAccept-Encoding: identity\r\nConnection: close\r\n")
            if (token.isNotBlank()) head.append("Authorization: Bearer $token\r\n")
            if (body != null) head.append("Content-Type: application/json\r\nContent-Length: ${body.size}\r\n")
            head.append("\r\n")
            s.getOutputStream().apply { write(head.toString().toByteArray(Charsets.UTF_8)); if (body != null) write(body); flush() }
            parse(s.getInputStream().readBytes())
        }
    }

    internal fun parse(raw: ByteArray): Response {
        val text = String(raw, Charsets.ISO_8859_1)
        val split = text.indexOf("\r\n\r\n")
        require(split > 0) { "no HTTP response" }
        val head = text.substring(0, split).lines()
        val status = head.first().split(' ')[1].toInt()
        var body = raw.copyOfRange(split + 4, raw.size)
        if (head.any { it.startsWith("Transfer-Encoding:", true) && it.contains("chunked", true) }) {
            val out = java.io.ByteArrayOutputStream(); var i = 0
            while (i < body.size) {
                var j = i; while (j < body.size && body[j] != '\r'.code.toByte()) j++
                val n = String(body, i, j - i, Charsets.ISO_8859_1).trim().substringBefore(';').toInt(16)
                if (n == 0) break
                out.write(body, j + 2, n); i = j + 2 + n + 2
            }
            body = out.toByteArray()
        }
        return Response(status, String(body, Charsets.UTF_8))
    }
}
