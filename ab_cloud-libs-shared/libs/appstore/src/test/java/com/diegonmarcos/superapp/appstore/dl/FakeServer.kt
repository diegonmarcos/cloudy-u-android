package com.diegonmarcos.superapp.appstore.dl

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** #1517 A tiny real HTTP server (JDK) that honours Range / If-Range / ETag and can misbehave on demand. */
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

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    init {
        server.createContext("/") { ex -> try { handle(ex) } catch (t: Throwable) { runCatching { ex.close() } } }
        server.executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        server.start()
    }

    fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"
    fun put(path: String, body: ByteArray, etag: String = "\"v1\"") = Entry(body, etag).also { files[path] = it }
    fun gets(path: String) = log.count { it.startsWith("$path ") }
    override fun close() = server.stop(0)

    private fun reply(ex: HttpExchange, code: Int) { ex.sendResponseHeaders(code, -1); ex.close() }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        if (path.endsWith(".sha256")) {
            val base = path.removeSuffix(".sha256")
            val text = sidecarOverride(base) ?: files[base]?.body?.let { sha(it) }
            if (text == null) return reply(ex, 404)
            val b = "$text  ${base.substringAfterLast('/')}\n".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            return
        }
        val e = files[path]
        val range = ex.requestHeaders.getFirst("Range")
        val ifRange = ex.requestHeaders.getFirst("If-Range")
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
            ex.responseHeaders.add("Content-Range", "bytes */${data.size}")
            log += "$tag -> 416"
            return reply(ex, 416)
        }
        ex.responseHeaders.add("ETag", e.etag)
        ex.responseHeaders.add("Accept-Ranges", "bytes")
        val slice = data.copyOfRange(start, data.size)
        if (partial) ex.responseHeaders.add("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
        val code = if (partial) 206 else 200
        log += "$tag -> $code"
        ex.sendResponseHeaders(code, slice.size.toLong())
        val out = ex.responseBody
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
            runCatching { out.close() }
        }
    }

    companion object {
        fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
