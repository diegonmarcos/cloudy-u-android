package com.diegonmarcos.clouddrive.sync

import java.net.InetAddress
import java.net.ServerSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #1934 Rename, Archive and Delete Remote are verified against a LOCAL MOCK of the hosting API (a plain socket server on 127.0.0.1: the exact request is
 * recorded and asserted); nothing here talks to a real repository.
 */
class HostingApiTest {
    private lateinit var server: ServerSocket
    private val seen = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
    private val base get() = "http://127.0.0.1:${server.localPort}"

    @Before fun up() {
        server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { return@Thread }
                s.use { c ->
                    val inp = c.getInputStream().buffered()
                    fun line(): String { val b = StringBuilder(); while (true) { val ch = inp.read(); if (ch < 0 || ch == '\n'.code) break; if (ch != '\r'.code) b.append(ch.toChar()) }; return b.toString() }
                    val start = line().split(' ')
                    val h = mutableMapOf<String, String>()
                    while (true) { val l = line(); if (l.isEmpty()) break; h[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
                    val n = h["content-length"]?.toInt() ?: 0
                    val buf = ByteArray(n); var o = 0
                    while (o < n) { val r = inp.read(buf, o, n - o); if (r < 0) break; o += r }
                    seen += listOf(start[0], start[1], h["authorization"].orEmpty(), h["accept"].orEmpty(), h["content-type"].orEmpty(), String(buf, Charsets.UTF_8))
                    val del = start[0] == "DELETE"
                    val out = if (del) "" else "{\"ok\":true}"
                    c.getOutputStream().apply { write(("HTTP/1.1 ${if (del) 204 else 200} OK\r\nContent-Length: ${out.length}\r\nConnection: close\r\n\r\n$out").toByteArray()); flush() }
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    @After fun down() { server.close() }

    @Test fun renameIsAPatchWithTheNewName() {
        val r = HostingApi.send(base, "tok", HostingApi.rename("me", "old", "new")).getOrThrow()
        assertEquals(200, r.status)
        assertEquals(listOf("PATCH", "/repos/me/old", "Bearer tok", "application/vnd.github+json", "application/json", "{\"name\":\"new\"}"), seen.single())
    }

    @Test fun archiveIsAPatchWithArchivedTrue() {
        HostingApi.send(base, "tok", HostingApi.archive("me", "r")).getOrThrow()
        assertEquals(listOf("PATCH", "/repos/me/r", "Bearer tok", "application/vnd.github+json", "application/json", "{\"archived\":true}"), seen.single())
    }

    @Test fun deleteRemoteIsADeleteWithNoBody() {
        val r = HostingApi.send(base, "tok", HostingApi.deleteRepo("me", "r")).getOrThrow()
        assertEquals(204, r.status)
        assertEquals(listOf("DELETE", "/repos/me/r", "Bearer tok", "application/vnd.github+json", "", ""), seen.single())
    }

    @Test fun onlyTheReadOnlyRequestsAreSafeToRunLive() {
        listOf(HostingApi.rename("a", "b", "c"), HostingApi.archive("a", "b"), HostingApi.deleteRepo("a", "b")).forEach { assertTrue(HostingApi.changesState(it)) }
        listOf(HostingApi.repoList(), HostingApi.publicRepos("a"), HostingApi.pulls("a", "b"), HostingApi.workflows("a", "b"), HostingApi.runs("a", "b"), HostingApi.repo("a", "b")).forEach { assertFalse(HostingApi.changesState(it)) }
    }

    @Test fun readOnlyRequestsCarryTheirQuery() {
        HostingApi.send(base, "", HostingApi.runs("me", "r")).getOrThrow()
        assertEquals(listOf("GET", "/repos/me/r/actions/runs?per_page=10", "", "application/vnd.github+json", "", ""), seen.single())
    }

    @Test fun chunkedAnswersAreDecoded() {
        val raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n".toByteArray()
        assertEquals("hello world", HostingApi.parse(raw).body)
    }
}
