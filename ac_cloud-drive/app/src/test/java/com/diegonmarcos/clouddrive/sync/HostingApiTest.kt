package com.diegonmarcos.clouddrive.sync

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #1934 Rename, Archive and Delete Remote are verified against a LOCAL MOCK of the hosting API (the exact request is asserted); nothing here talks to a real repository. */
class HostingApiTest {
    private lateinit var server: HttpServer
    private val seen = mutableListOf<List<String>>()
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before fun up() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen += listOf(ex.requestMethod, ex.requestURI.toString(), ex.requestHeaders.getFirst("Authorization").orEmpty(), ex.requestHeaders.getFirst("Accept").orEmpty(), ex.requestHeaders.getFirst("Content-Type").orEmpty(), body)
            val (code, out) = if (ex.requestMethod == "DELETE") 204 to "" else 200 to "{\"ok\":true}"
            if (out.isEmpty()) ex.sendResponseHeaders(code, -1) else { ex.sendResponseHeaders(code, out.length.toLong()); ex.responseBody.write(out.toByteArray()) }
            ex.close()
        }
        server.start()
    }
    @After fun down() = server.stop(0)

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
