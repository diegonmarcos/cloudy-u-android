package com.diegonmarcos.superapp.shellrish

import com.diegonmarcos.superapp.shellclient.ShellClient
import com.diegonmarcos.superapp.shellclient.ShellResult
import com.diegonmarcos.superapp.shellclient.ShellWire
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #1512 The terminals' `rish`, broker-backed. `rish` runs inside the terminal's proot (no binder, no app_process, no ART), so it cannot
 * call the broker itself: it speaks one loopback HTTP request to THIS relay, which lives in the terminal's own process and forwards to
 * the broker through [ShellClient] (libs:shell-client) under the TERMINAL's own allowlist entry (the binder uid, never a string `rish` typed). The relay
 * therefore grants nothing the terminal app does not already have: the allowlist decides, default deny.
 *
 *  - loopback only (127.0.0.1, ephemeral port), a fresh random bearer token per process, written 0600 into the session's rish.env;
 *  - `GET /exec?cmd=<urlencoded>[&t=<timeoutMs>]` with `Authorization: Bearer <token>`; the body is stdout+stderr, `X-Exit-Code` the exit;
 *  - one request per connection, request line and headers bounded, a read timeout, no keep-alive, no background work between requests
 *    (a blocking accept on a daemon thread, started with the app process and gone with it).
 */
class RishRelay(private val token: String, private val exec: (cmd: String, timeoutMs: Long) -> ShellResult) {
    private var server: ServerSocket? = null
    private val stopped = AtomicBoolean(false)

    /** Bind 127.0.0.1 on an ephemeral port and serve; returns the port. */
    @Synchronized fun start(): Int {
        server?.let { return it.localPort }
        val s = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread({ acceptLoop(s) }, "rish-relay").apply { isDaemon = true }.start()
        return s.localPort
    }

    @Synchronized fun stop() { stopped.set(true); runCatching { server?.close() }; server = null }

    private fun acceptLoop(s: ServerSocket) {
        while (!stopped.get() && !s.isClosed) {
            val c = try { s.accept() } catch (_: Exception) { return }
            Thread({ serve(c) }, "rish-relay-conn").apply { isDaemon = true }.start()
        }
    }

    private fun serve(c: Socket) {
        c.use { sock ->
            sock.soTimeout = READ_TIMEOUT_MS
            val resp = try {
                val head = readHead(BufferedInputStream(sock.getInputStream()))
                if (head == null) null else answer(head)
            } catch (_: Exception) { Reply(400, "rish: bad request\n", 1) }
            if (resp != null) runCatching {
                val body = resp.body.toByteArray(Charsets.UTF_8)
                val h = "HTTP/1.1 ${resp.status} ${if (resp.status == 200) "OK" else "ERR"}\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                    "X-Exit-Code: ${resp.exit}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                val out = sock.getOutputStream()
                out.write(h.toByteArray(Charsets.US_ASCII)); out.write(body); out.flush()
            }
        }
    }

    internal fun answer(head: List<String>): Reply = when (val req = parse(head, token)) {
        is Parsed.Bad -> Reply(req.status, "rish: ${req.why}\n", exit = 1)
        is Parsed.Cmd -> when (val r = exec(req.cmd, req.timeoutMs)) {
            is ShellResult.Ok -> Reply(200, r.stdout + r.stderr, r.exitCode)
            is ShellResult.Denied -> Reply(403, "rish: denied by the shell broker: ${r.reason}\n", 126)
            ShellResult.NeedsArm -> Reply(503, "rish: the ADB shell of Cloud C3 Webserver is not armed: open Cloud C3 Webserver > ADB Shell and arm it (Wireless debugging), then retry\n", 75)
            is ShellResult.Failed -> Reply(502, "rish: ${r.reason}\n", if (r.timedOut) 124 else 1)
        }
    }

    internal data class Reply(val status: Int, val body: String, val exit: Int = 0)

    internal sealed class Parsed {
        data class Cmd(val cmd: String, val timeoutMs: Long) : Parsed()
        data class Bad(val status: Int, val why: String) : Parsed()
    }

    companion object {
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 32

        fun newToken(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        /** Pure: the request line + headers -> the command to run, or why not. The token is compared in constant time. */
        internal fun parse(head: List<String>, token: String): Parsed {
            val line = head.firstOrNull() ?: return Parsed.Bad(400, "empty request")
            val m = Regex("""^GET /exec\?(\S*) HTTP/1\.[01]$""").matchEntire(line) ?: return Parsed.Bad(404, "only GET /exec?cmd=")
            val auth = head.drop(1).firstOrNull { it.startsWith("Authorization:", ignoreCase = true) }
                ?.substringAfter(':')?.trim().orEmpty()
            val given = if (auth.startsWith("Bearer ")) auth.removePrefix("Bearer ").trim() else ""
            if (token.isEmpty() || !MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) return Parsed.Bad(401, "bad or missing token")
            var cmd = ""; var t = ShellWire.DEFAULT_TIMEOUT_MS
            for (kv in m.groupValues[1].split('&')) {
                val k = kv.substringBefore('='); val v = runCatching { URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8") }.getOrNull() ?: return Parsed.Bad(400, "bad encoding")
                when (k) { "cmd" -> cmd = v; "t" -> t = v.toLongOrNull() ?: t }
            }
            if (cmd.isBlank()) return Parsed.Bad(400, "empty command")
            return Parsed.Cmd(cmd, ShellWire.clampTimeout(t))
        }

        /** The request line and headers up to the blank line, each bounded; null when the peer sent nothing. */
        private fun readHead(input: BufferedInputStream): List<String>? {
            val lines = ArrayList<String>()
            while (lines.size <= MAX_HEADERS) {
                val sb = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b < 0) return lines.takeIf { it.isNotEmpty() }
                    if (b == '\n'.code) break
                    if (b != '\r'.code) sb.append(b.toChar())
                    if (sb.length > MAX_LINE) throw IllegalArgumentException("line too long")
                }
                if (sb.isEmpty()) return lines
                lines += sb.toString()
            }
            throw IllegalArgumentException("too many headers")
        }
    }
}
