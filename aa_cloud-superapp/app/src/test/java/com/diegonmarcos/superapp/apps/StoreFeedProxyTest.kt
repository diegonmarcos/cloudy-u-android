package com.diegonmarcos.superapp.apps

import android.app.Application
import com.diegonmarcos.superapp.appstore.FeedViewer
import com.diegonmarcos.superapp.appstore.SourceResolver
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * #841 The Store's Commits / CI-CD feeds read the fleet git-proxy first, with
 * the fleet bearer, parse its reduced shapes, and on ANY proxy failure fall
 * back to the public GitHub url - whose own error is what the tab says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreFeedProxyTest {

    @After fun reset() { FeedViewer.fleetBearer = { "" } }

    /** One request seen by [server]: path, and the Authorization it carried. */
    class Hit(val path: String, val auth: String?)

    /** Answers each path from [routes] (status to body); 404 otherwise. */
    private fun server(hits: MutableList<Hit>, routes: Map<String, Pair<Int, String>>): ServerSocket {
        val ss = ServerSocket(0)
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                s.use {
                    val r = it.getInputStream().bufferedReader()
                    val path = r.readLine().orEmpty().split(' ').getOrElse(1) { "" }.substringBefore('?')
                    var auth: String? = null
                    while (true) {
                        val l = r.readLine() ?: break
                        if (l.isEmpty()) break
                        if (l.startsWith("Authorization:", ignoreCase = true)) auth = l.substringAfter(':').trim()
                    }
                    hits.add(Hit(path, auth))
                    val (code, body) = routes[path] ?: (404 to "")
                    val bytes = body.toByteArray()
                    it.getOutputStream().write(("HTTP/1.1 $code X\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                }
            }
        }
        return ss
    }

    private fun shipped(): JSONObject = JSONObject(File(listOf(
        "../../ab_cloud-libs-shared/libs/appstore/src/main/assets/appstore-feeds.json",
        "ab_cloud-libs-shared/libs/appstore/src/main/assets/appstore-feeds.json",
        "../ab_cloud-libs-shared/libs/appstore/src/main/assets/appstore-feeds.json",
    ).first { File(it).exists() }).readText())

    /** The shipped declaration with both legs repointed at [port], so the
     *  test reads through the real templates and items names. */
    private fun feedsAt(port: Int): List<FeedViewer.Feed> {
        val d = shipped()
        val a = d.getJSONArray("feeds")
        for (i in 0 until a.length()) {
            val f = a.getJSONObject(i)
            f.put("url", f.getString("url").replace("https://api.github.com", "http://127.0.0.1:$port/gh"))
            // a feed the fleet does not serve yet (prs) declares "proxy": null - GitHub-only, nothing to repoint
            val p = f.optJSONObject("proxy") ?: continue
            p.put("url", p.getString("url").replace("https://api.diegonmarcos.com", "http://127.0.0.1:$port/fleet"))
        }
        return FeedViewer.parse(d)
    }

    private val proxyCommits = """{"repo":"diegonmarcos/cloudy-u-android","cached_at":"t","commits":[
        {"sha":"abcdef1234","message":"feat: one\n\nbody","author":"Diego","date":"2026-10-03T10:00:00Z","html_url":"https://github.com/x/commit/abcdef1234"}]}"""
    private val proxyRuns = """{"repo":"diegonmarcos/cloudy-u-android","cached_at":"t","runs":[
        {"name":"ship","display_title":"feat: one","status":"completed","conclusion":"failure","created_at":"2026-10-03","html_url":"https://github.com/x/runs/1","path":".github/workflows/ship.yml"}]}"""
    private val ghCommits = """[{"sha":"1111111aaa","html_url":"https://github.com/x/c/1","commit":{"message":"direct","author":{"name":"Gh","date":"d"}}}]"""

    private val CP = "/fleet/git/repos/diegonmarcos/cloudy-u-android/commits"
    private val RP = "/fleet/git/repos/diegonmarcos/cloudy-u-android/runs"
    private val GC = "/gh/repos/diegonmarcos/cloudy-u-android/commits"

    @Test fun `the shipped declaration points both feeds at the fleet git-proxy, per_page 50 as data`() {
        val feeds = FeedViewer.parse(shipped()).associateBy { it.id }
        assertEquals("https://api.diegonmarcos.com/git/repos/diegonmarcos/cloudy-u-android/commits?per_page=50", feeds.getValue("commits").proxy)
        assertEquals("https://api.diegonmarcos.com/git/repos/diegonmarcos/cloudy-u-android/runs?per_page=50", feeds.getValue("cicd").proxy)
        assertEquals("commits", feeds.getValue("commits").proxyShape.items)
        assertEquals("runs", feeds.getValue("cicd").proxyShape.items)
        // the public fallback stays GitHub's own shape
        assertTrue(feeds.getValue("commits").url.startsWith("https://api.github.com/"))
        assertNull(feeds.getValue("commits").shape.items)
        assertEquals("workflow_runs", feeds.getValue("cicd").shape.items)
        for (f in feeds.values) assertTrue(f.url.endsWith("per_page=50"))
    }

    @Test fun `the proxy's reduced commit and run shapes parse, with the bearer sent only to the proxy`() {
        val hits = Collections.synchronizedList(mutableListOf<Hit>())
        server(hits, mapOf(CP to (200 to proxyCommits), RP to (200 to proxyRuns))).use { ss ->
            FeedViewer.fleetBearer = { "tok-test" }
            val feeds = feedsAt(ss.localPort).associateBy { it.id }
            val c = FeedViewer.load(feeds.getValue("commits")).single()
            assertEquals("abcdef1234", c.ref)
            assertTrue(c.title.startsWith("feat: one"))
            assertEquals("Diego  ·  2026-10-03T10:00:00Z", c.subtitle)
            assertEquals("https://github.com/x/commit/abcdef1234", c.link)
            val r = FeedViewer.load(feeds.getValue("cicd")).single()
            assertEquals("feat: one", r.title)
            assertEquals("failure", r.state)
            assertTrue(r.subtitle.contains("ship") && r.subtitle.contains("completed"))
            assertTrue(hits.all { it.path.startsWith("/fleet/") })
            assertTrue(hits.all { it.auth == "Bearer tok-test" })
        }
    }

    @Test fun `a refused proxy falls back to GitHub without the bearer, proxy first`() {
        val hits = Collections.synchronizedList(mutableListOf<Hit>())
        val e401 = """{"error":"authorization required","code":"missing_authorization"}"""
        server(hits, mapOf(CP to (401 to e401), GC to (200 to ghCommits))).use { ss ->
            FeedViewer.fleetBearer = { "tok-test" }
            val c = FeedViewer.load(feedsAt(ss.localPort).first { it.id == "commits" }).single()
            assertEquals("direct", c.title)
            assertEquals("Gh  ·  d", c.subtitle)
            assertEquals(listOf(CP, GC), hits.map { it.path })
            assertNull("the fleet bearer must never reach GitHub", hits[1].auth)
        }
    }

    @Test fun `a proxy answering the wrong shape still falls back, never an empty feed`() {
        val hits = Collections.synchronizedList(mutableListOf<Hit>())
        server(hits, mapOf(CP to (200 to """{"workflow_runs":[]}"""), GC to (200 to ghCommits))).use { ss ->
            val c = FeedViewer.load(feedsAt(ss.localPort).first { it.id == "commits" })
            assertEquals(1, c.size)
        }
    }

    @Test fun `when both legs fail the tab says the FALLBACK's error, with the proxy's appended`() {
        val hits = Collections.synchronizedList(mutableListOf<Hit>())
        val e502 = """{"error":"upstream failed","code":"upstream_error"}"""
        val gh403 = """{"message":"API rate limit exceeded for 1.2.3.4"}"""
        server(hits, mapOf(CP to (502 to e502), GC to (403 to gh403))).use { ss ->
            try {
                FeedViewer.load(feedsAt(ss.localPort).first { it.id == "commits" })
                fail("both legs failed; there is no feed to draw")
            } catch (t: Throwable) {
                assertTrue(t is SourceResolver.HttpStatus && t.code == 403)
                val said = FeedViewer.explain(t)
                assertTrue(said, said.startsWith("Refused by the server — HTTP 403 from http://127.0.0.1"))
                assertTrue(said, said.contains("API rate limit exceeded"))
                val fleet = said.substringAfter("(Fleet proxy tried first: ")
                assertTrue(said, fleet.contains("HTTP 502") && fleet.contains("upstream failed · upstream_error"))
                assertTrue(said.indexOf("/gh/") < said.indexOf("/fleet/"))
                assertFalse("no bearer in any message", said.contains("tok-"))
                assertFalse(said.contains("Nothing in this feed"))
            }
        }
    }

    @Test fun `the feeds debug record names the leg that served each feed, never the bearer`() {
        val hits = Collections.synchronizedList(mutableListOf<Hit>())
        val e401 = """{"error":"authorization required","code":"missing_authorization"}"""
        server(hits, mapOf(CP to (401 to e401), GC to (200 to ghCommits), RP to (200 to proxyRuns))).use { ss ->
            FeedViewer.fleetBearer = { "tok-test" }
            val feeds = feedsAt(ss.localPort).filter { it.id == "commits" || it.id == "cicd" } // prs has no proxy leg and no route here
            feeds.forEach { FeedViewer.load(it) }
            assertEquals(FeedViewer.LEG_FALLBACK, FeedViewer.lastServed("commits")!!.leg)
            assertTrue(FeedViewer.lastServed("commits")!!.proxyError!!.contains("HTTP 401"))
            assertEquals(FeedViewer.LEG_PROXY, FeedViewer.lastServed("cicd")!!.leg)
            assertNull(FeedViewer.lastServed("cicd")!!.proxyError)
            val j = FeedViewer.servedJson(feeds)
            val text = j.toString()
            assertFalse("no bearer in the debug record", text.contains("tok-"))
            val byId = (0 until j.getJSONArray("feeds").length()).map { j.getJSONArray("feeds").getJSONObject(it) }.associateBy { it.getString("id") }
            assertEquals("github-fallback", byId.getValue("commits").getJSONObject("last").getString("leg"))
            assertEquals(1, byId.getValue("cicd").getJSONObject("last").getInt("entries"))
            assertTrue(byId.getValue("cicd").getBoolean("bearerSet"))
        }
    }
}
