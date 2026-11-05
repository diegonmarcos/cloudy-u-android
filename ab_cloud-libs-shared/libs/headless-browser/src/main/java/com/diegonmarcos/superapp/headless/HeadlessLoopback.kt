package com.diegonmarcos.superapp.headless

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** #1498 the loopback-only HTTP door (for Termux): GET /health (open), GET /sessions, POST /command, POST /run (bearer = the per-run token). */
class HeadlessLoopback(
    private val gate: HeadlessTokenGate,
    private val execute: (String, JSONObject) -> JSONObject,
    private val sessions: () -> JSONArray,
    private val count: () -> Int,
) {
    private var server: ServerSocket? = null
    val port: Int get() = server?.localPort ?: 0

    fun start(preferred: Int) {
        val s = ServerSocket(preferred, 8, InetAddress.getByName("127.0.0.1")) // loopback only, never the mesh or the LAN
        server = s
        Thread({ while (!s.isClosed) { val c = runCatching { s.accept() }.getOrNull() ?: break; Thread({ serve(c) }, "headless-http").apply { isDaemon = true }.start() } }, "headless-accept").apply { isDaemon = true }.start()
    }

    fun stop() { runCatching { server?.close() }; server = null }

    private fun serve(c: Socket) = c.use {
        c.soTimeout = 130_000
        val (method, path, headers, body) = readRequest(c.getInputStream()) ?: return@use
        val out = c.getOutputStream()
        fun send(code: Int, o: JSONObject) {
            val b = o.toString().toByteArray()
            out.write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(b); out.flush()
        }
        if (method == "GET" && path == "/health") return@use send(200, JSONObject().put("ok", true).put("sessions", count()))
        gate.check(headers["authorization"])?.let { return@use send(401, HeadlessApi.fail("unauthorized", it)) }
        when {
            method == "GET" && path == "/sessions" -> send(200, JSONObject().put("ok", true).put("result", sessions()))
            method == "POST" && (path == "/command" || path == "/run" || path == "/cdp") -> {
                val j = runCatching { JSONObject(body) }.getOrNull() ?: return@use send(400, HeadlessApi.fail("bad_request", "invalid JSON"))
                val r = execute(path, j)
                send(if (r.optBoolean("ok")) 200 else HeadlessApi.httpStatus(r.optJSONObject("error")?.optString("code").orEmpty()), r)
            }
            else -> send(404, HeadlessApi.fail("not_found", "no such route"))
        }
    }

    private data class Req(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private fun readRequest(i: InputStream): Req? {
        fun line(): String? { val sb = StringBuilder(); while (true) { val b = i.read(); if (b < 0) return if (sb.isEmpty()) null else sb.toString(); if (b == '\n'.code) return sb.toString().trimEnd('\r'); sb.append(b.toChar()); if (sb.length > 8192) return null } }
        val first = line()?.split(" ") ?: return null
        if (first.size < 2) return null
        val h = HashMap<String, String>()
        while (true) { val l = line() ?: return null; if (l.isEmpty()) break; val k = l.indexOf(':'); if (k > 0) h[l.substring(0, k).trim().lowercase()] = l.substring(k + 1).trim() }
        val n = h["content-length"]?.toIntOrNull() ?: 0
        if (n < 0 || n > 2_000_000) return null
        val buf = ByteArray(n); var off = 0
        while (off < n) { val r = i.read(buf, off, n - off); if (r < 0) break; off += r }
        return Req(first[0], first[1].substringBefore('?'), h, String(buf, 0, off))
    }
}
