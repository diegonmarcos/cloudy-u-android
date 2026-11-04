package com.diegonmarcos.cloudsearch

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.diegonmarcos.cloudsearch.core.Http
import com.diegonmarcos.cloudsearch.data.FleetBearer
import com.diegonmarcos.cloudsearch.data.Services
import com.diegonmarcos.cloudsearch.ui.CgcTags
import com.diegonmarcos.cloudsearch.ui.SearchShell
import com.diegonmarcos.cloudsearch.ui.SearchState
import com.diegonmarcos.cloudsearch.ui.Tags
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1184 the two code indexes (#1197: Cloud CGC and Me CGC, the CGC section's two rows) through the real shell, on a fake network that answers the way the fleet's
 * cgc MCP service does (JSON-RPC over an event stream): the answers are shaped like the services', not
 * captured from them. The private index is asked with and without the Cloud Account sign-in, and answers as an unbuilt index does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResearchCgcShellTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private class Fake : Http {
        val gets = mutableListOf<Pair<String, Map<String, String>>>()
        val calls = mutableListOf<Triple<String, Map<String, String>, JSONObject>>()
        var privateBuilt = false
        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Http.Response {
            synchronized(gets) { gets += url to headers }
            return when {
                "cloud-cgc-pvt-mcp" in url && url.endsWith("/health") -> Http.Response(200, """{"status":"degraded","checks":{"octocode":{"binary":true},"kgstore":{"reachable":false}}}""")
                else -> Http.Response(404, "")
            }
        }
        override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): Http.Response {
            val j = JSONObject(body)
            synchronized(gets) { calls += Triple(url, headers, j) }
            fun sse(id: Int, result: String) = Http.Response(200, "event: message\ndata: {\"result\":$result,\"jsonrpc\":\"2.0\",\"id\":$id}\n\n", mapOf("mcp-session-id" to "s"))
            return when (j.getString("method")) {
                "initialize" -> sse(1, "{}")
                "notifications/initialized" -> Http.Response(202, "")
                else -> {
                    val p = j.getJSONObject("params")
                    val a = p.getJSONObject("arguments")
                    fun text(t: String, err: Boolean = false) = sse(2, JSONObject().put("isError", err).put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", t))).toString())
                    when {
                        "cloud-cgc-pvt-mcp" in url -> if (privateBuilt) text("""{"code_blocks":[]}""") else text("No octocode index for project cloud-data", err = true)
                        p.getString("name") == "cgc.octocode.search" -> text("""{"code_blocks":[{"path":"ac_cloud-search/core/Engine.kt","start_line":120,"content":"class SearchEngine","symbols":["SearchEngine"],"distance":0.2}]}""")
                        a.getString("operation") == "search" -> text("""{"nodes":[{"id":"core/Engine.kt","kind":"file","path":"core/Engine.kt","description":"query engine"}]}""")
                        else -> text("""{"relationships":[{"source":"ui/Shell.kt","target":"core/Engine.kt","relationship_type":"imports"},{"source":"core/Engine.kt","target":"core/Cache.kt","relationship_type":"imports"}]}""")
                    }
                }
            }
        }
    }

    private val http = Fake()
    private lateinit var state: SearchState
    private val savedBearer = FleetBearer.reader

    @Before fun setUp() {
        val services = Services(RuntimeEnvironment.getApplication(), http)
        Services.install(services)
        state = SearchState(services)
        FleetBearer.reader = { "" }
    }

    @After fun tearDown() { FleetBearer.reader = savedBearer; Services.install(null) }

    private fun waitFor(tag: String) = compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun text(tag: String): String = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    private fun waitText(tag: String, part: String) = try {
        compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() && text(tag).contains(part) }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        val now = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().let { if (it.isEmpty()) "(no node)" else text(tag) }
        throw AssertionError("'$tag' never contained '$part'; it said '$now'; posted=${synchronized(http.gets) { http.calls.map { it.third.optString("method") } }}", e)
    }

    private fun search(section: String, page: String, q: String) {
        compose.goTo(state, section)
        compose.onNodeWithTag(Tags.subpage(page)).performClick()
        waitFor(CgcTags.QUERY)
        compose.onNodeWithTag(CgcTags.QUERY).performTextInput(q)
        compose.onNodeWithTag(CgcTags.SEARCH).performClick()
    }

    @Test fun cloudCgcSemanticShowsRepoPathLineAndOpens() {
        compose.setContent { SearchShell(state) }
        search("cgc", "cloud_semantic", "query engine")
        waitFor(CgcTags.hit(0))
        waitText(CgcTags.STATUS, "1 results via mesh")
        val call = http.calls.last { it.third.getString("method") == "tools/call" }
        assertEquals("http://cloud-cgc-pub-mcp.mesh.diegonmarcos.com:3105/mcp", call.first)
        assertEquals("cgc.octocode.search", call.third.getJSONObject("params").getString("name"))
        val args = call.third.getJSONObject("params").getJSONObject("arguments")
        assertEquals("query engine", args.getString("query")); assertEquals("cloud-u-android", args.getString("repo"))
        assertEquals("Mcp-Session-Id", call.second.keys.single { it.startsWith("Mcp") })
        compose.onNodeWithTag(CgcTags.web("h0")).assertExists()
    }

    @Test fun cloudCgcGraphRagListsNodesThenCallersAndCallees() {
        compose.setContent { SearchShell(state) }
        search("cgc", "cloud_graph", "engine")
        waitFor(CgcTags.node(0))
        compose.onNodeWithTag(CgcTags.node(0)).performClick()
        waitFor(CgcTags.edge(0))
        compose.onNodeWithTag(CgcTags.edge(100)).assertExists()
        val ops = http.calls.filter { it.third.getString("method") == "tools/call" }.map { it.third.getJSONObject("params").getJSONObject("arguments").getString("operation") }
        assertEquals(listOf("search", "get-relationships"), ops)
    }

    @Test fun meCgcWaitsForTheAccountSignInAndSendsNothingWithout() {
        compose.setContent { SearchShell(state) }
        search("cgc", "me_semantic", "secrets layout")
        waitText(CgcTags.STATUS, "Cloud Account has no fleet sign-in")
        assertTrue("nothing reached the private service", http.calls.none { "pvt" in it.first })
    }

    @Test fun meCgcSaysTheIndexIsNotBuiltInTheServicesWords() {
        FleetBearer.reader = { "tok" }
        compose.setContent { SearchShell(state) }
        search("cgc", "me_semantic", "secrets layout")
        waitText(CgcTags.STATUS, "Private index not built yet: No octocode index for project cloud-data")
        assertEquals("cloud-me-pvt-data", http.calls.last { it.third.getString("method") == "tools/call" }.third.getJSONObject("params").getJSONObject("arguments").getString("repo"))
        assertTrue("the bearer never goes to a mesh name", http.calls.all { !it.second.containsKey("Authorization") })
        assertFalse(compose.onAllNodesWithTag(CgcTags.hit(0)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun meCgcWithAnEmptyAnswerAddsTheServicesHealth() {
        FleetBearer.reader = { "tok" }
        http.privateBuilt = true
        compose.setContent { SearchShell(state) }
        search("cgc", "me_semantic", "anything")
        waitText(CgcTags.STATUS, "service degraded (graph store unreachable)")
        assertTrue(text(CgcTags.STATUS).startsWith("No results, and the private index may not be built yet"))
    }
}
