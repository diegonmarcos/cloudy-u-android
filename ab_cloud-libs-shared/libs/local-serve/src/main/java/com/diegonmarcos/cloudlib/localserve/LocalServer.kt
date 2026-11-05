package com.diegonmarcos.cloudlib.localserve

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A static file server for ONE folder, bound to the loopback address only. [start] picks a random free port (or [bindPort]);
 * [close] stops it. Requests: GET and HEAD, the Host header must be loopback, the path must stay inside the folder.
 */
class LocalServer(root: File, private val bindPort: Int = 0) : AutoCloseable {
    private val rootDir: File = root.canonicalFile
    @Volatile private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null

    /** The port once started, else -1. */
    val port: Int get() = socket?.localPort ?: -1

    /** The address the socket is bound to once started; always 127.0.0.1. */
    val boundAddress: InetAddress? get() = socket?.inetAddress

    val isRunning: Boolean get() = socket?.isClosed == false

    /** Binds 127.0.0.1 and starts accepting; returns the port. */
    @Synchronized
    fun start(): Int {
        check(socket == null) { "already started" }
        val s = ServerSocket(bindPort, BACKLOG, LOOPBACK)
        socket = s
        val p = Executors.newCachedThreadPool { r -> Thread(r, "local-serve").also { it.isDaemon = true } }
        pool = p
        p.execute {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: IOException) { break }
                try { p.execute { handle(c) } } catch (e: Exception) { runCatching { c.close() } }
            }
        }
        return s.localPort
    }

    /** The URL of [relative] (a path below the folder) on this server. */
    fun url(relative: String): String = "http://127.0.0.1:$port/" + relative.trimStart('/').split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    @Synchronized
    override fun close() {
        runCatching { socket?.close() }
        pool?.shutdownNow()
        runCatching { pool?.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) }
    }

    private fun handle(c: Socket) {
        c.use { sock ->
            sock.soTimeout = TIMEOUT_MS
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val request = readLine(input) ?: return
            val headers = HashMap<String, String>()
            var n = 0
            while (n++ < MAX_HEADERS) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val parts = request.split(' ')
            if (parts.size < 2) return reply(out, 400, "Bad Request")
            val method = parts[0]
            if (method != "GET" && method != "HEAD") return reply(out, 405, "Method Not Allowed")
            val host = headers["host"]?.substringBeforeLast(':', headers["host"].orEmpty())?.trim('[', ']')
            if (host != null && host != "127.0.0.1" && host != "localhost") return reply(out, 403, "Forbidden")
            val file = resolve(rootDir, parts[1]) ?: return reply(out, 403, "Forbidden")
            val target = if (file.isDirectory) File(file, "index.html") else file
            if (!target.isFile) return reply(out, 404, "Not Found")
            val len = target.length()
            val head = "HTTP/1.1 200 OK\r\nContent-Type: ${mime(target.name)}\r\nContent-Length: $len\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            if (method == "GET") target.inputStream().use { it.copyTo(out) }
            out.flush()
        }
    }

    private fun reply(out: OutputStream, code: Int, text: String) {
        val body = "$code $text"
        out.write("HTTP/1.1 $code $text\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun readLine(i: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (sb.length <= MAX_LINE) {
            val b = i.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return null
    }

    companion object {
        private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private const val BACKLOG = 16
        private const val TIMEOUT_MS = 5000
        private const val MAX_HEADERS = 64
        private const val MAX_LINE = 8192

        /**
         * The file a request target names inside [root], or null when it must be refused: a `..` segment (also encoded), a backslash,
         * a NUL, or a canonical path (symlinks resolved) outside [root]. A folder resolves to itself (the caller adds index.html).
         */
        fun resolve(root: File, target: String): File? {
            val base = root.canonicalFile
            val raw = target.substringBefore('?').substringBefore('#')
            val path = try { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { return null }
            if (path.contains('\u0000') || path.contains('\\') || !path.startsWith("/")) return null
            val segments = path.split('/').filter { it.isNotEmpty() }
            if (segments.any { it == ".." || it == "." }) return null
            val f = File(base, segments.joinToString(File.separator)).canonicalFile
            return if (f == base || f.path.startsWith(base.path + File.separator)) f else null
        }

        fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm", "xhtml" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "txt", "md" -> "text/plain; charset=utf-8"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }
    }
}
