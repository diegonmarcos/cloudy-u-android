package com.diegonmarcos.superapp.appstore.dl

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** #1517 A tiny real HTTP server (plain sockets: the unit-test classpath has no com.sun.net.httpserver) that honours Range / If-Range / ETag and can misbehave on demand. */
internal class FakeServer : AutoCloseable {
    class Entry(@Volatile var body: ByteArray, @Volatile var etag: String = "\"v1\"")

    val files = ConcurrentHashMap<String, Entry>()
    /** One line per request: "<path> range=<Range> ifrange=<If-Range> -> <status>". */
    val log = CopyOnWriteArrayList<String>()
    @Volatile var honorIfRange = true
    @Volatile var ignoreRange = false
    @Volatile var failNext = 0
    /** One-shot: send only this many bytes of the next body, then drop the connection. */
    @Volatile var cutAfter = -1
    @Volatile var onCut: () -> Unit = {}
    @Volatile var sidecarOverride: (String) -> String? = { null }
    /** While set, a body stops after [holdAfter] bytes until the latch is released. */
    @Volatile var hold: CountDownLatch? = null
    @Volatile var holdAfter = 1000
    val inFlight = AtomicInteger()
    val maxInFlight = AtomicInteger()

    private class Ex(val path: String, private val headers: Map<String, String>, val socket: Socket) {
        val out: OutputStream = socket.getOutputStream()
        val extra = LinkedHashMap<String, String>()
        fun header(n: String) = headers[n.lowercase()]
        fun head(code: Int, len: Long) {
            val sb = StringBuilder("HTTP/1.1 $code X\r\nConnection: close\r\nContent-Length: ${maxOf(len, 0)}\r\n")
            extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            out.write(sb.append("\r\n").toString().toByteArray()); out.flush()
        }
        fun close() { runCatching { out.flush() }; runCatching { socket.close() } }
    }

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val port = server.localPort

    init {
        Thread {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Throwable) { break }
                Thread { serve(s) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun serve(s: Socket) {
        try {
            val r = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val line = r.readLine() ?: return s.close()
            val hs = HashMap<String, String>()
            while (true) { val h = r.readLine() ?: break; if (h.isEmpty()) break; hs[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim() }
            handle(Ex(line.split(' ')[1].substringBefore('?'), hs, s))
        } catch (t: Throwable) { runCatching { s.close() } }
    }

    fun url(path: String) = "http://127.0.0.1:$port$path"
    fun put(path: String, body: ByteArray, etag: String = "\"v1\"") = Entry(body, etag).also { files[path] = it }
    fun gets(path: String) = log.count { it.startsWith("$path ") }
    override fun close() = server.close()

    private fun reply(ex: Ex, code: Int) { ex.head(code, 0); ex.close() }

    private fun handle(ex: Ex) {
        val path = ex.path
        if (path.endsWith(".sha256")) {
            val base = path.removeSuffix(".sha256")
            val text = sidecarOverride(base) ?: files[base]?.body?.let { sha(it) }
            if (text == null) return reply(ex, 404)
            val b = "$text  ${base.substringAfterLast('/')}\n".toByteArray()
            ex.head(200, b.size.toLong()); ex.out.write(b); ex.close()
            return
        }
        val e = files[path]
        val range = ex.header("Range")
        val ifRange = ex.header("If-Range")
        val tag = "$path range=$range ifrange=$ifRange"
        if (e == null) { log += "$tag -> 404"; return reply(ex, 404) }
        if (failNext > 0) { failNext--; log += "$tag -> 500"; return reply(ex, 500) }
        val data = e.body
        var start = 0
        var partial = false
        if (range != null && !ignoreRange && (ifRange == null || !honorIfRange || ifRange == e.etag)) {
            start = range.removePrefix("bytes=").substringBefore('-').toInt()
            partial = true
        }
        if (partial && start >= data.size) {
            ex.extra["Content-Range"] = "bytes */${data.size}"
            log += "$tag -> 416"
            return reply(ex, 416)
        }
        ex.extra["ETag"] = e.etag
        ex.extra["Accept-Ranges"] = "bytes"
        val slice = data.copyOfRange(start, data.size)
        if (partial) ex.extra["Content-Range"] = "bytes $start-${data.size - 1}/${data.size}"
        val code = if (partial) 206 else 200
        log += "$tag -> $code"
        ex.head(code, slice.size.toLong())
        val out = ex.out
        val cut = cutAfter
        if (cut >= 0) {
            cutAfter = -1
            out.write(slice, 0, minOf(cut, slice.size)); out.flush()
            onCut()
            ex.close()
            return
        }
        val n = inFlight.incrementAndGet()
        maxInFlight.updateAndGet { maxOf(it, n) }
        try {
            val latch = hold
            if (latch == null) out.write(slice)
            else {
                val first = minOf(holdAfter, slice.size)
                out.write(slice, 0, first); out.flush()
                latch.await(30, TimeUnit.SECONDS)
                out.write(slice, first, slice.size - first)
            }
        } finally {
            inFlight.decrementAndGet()
            ex.close()
        }
    }

    companion object {
        fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
