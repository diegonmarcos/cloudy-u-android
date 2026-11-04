package com.diegonmarcos.cloudsearch

import android.app.SearchManager
import android.content.Intent
import android.os.Looper
import com.diegonmarcos.cloudsearch.core.Http
import com.diegonmarcos.cloudsearch.data.Account
import com.diegonmarcos.cloudsearch.data.Services
import com.diegonmarcos.superapp.fleetconfig.CloudSearchQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #937 a query from outside: what each intent carries (CloudSearchQuery, the contract SuperApp sends
 * with), and that MainActivity runs it on the Search page, at launch and while already open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QueryIntentTest {

    private fun search(q: String?) = Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, q)
    private fun webSearch(q: String?) = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
    private fun share(text: String?, type: String = "text/plain") =
        Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_TEXT, text)

    @Test fun eachActionCarriesItsQuery() {
        assertEquals("rent berlin", CloudSearchQuery.from(search("  rent berlin ")))
        assertEquals("kotlin flow", CloudSearchQuery.from(webSearch("kotlin flow")))
        assertEquals("a shared line and the next one", CloudSearchQuery.from(share("a shared line\n\tand the next one\n")))
        // The explicit extra, on any intent (a plain launch too) and behind an empty standard one.
        assertEquals("explicit", CloudSearchQuery.from(Intent(Intent.ACTION_MAIN).putExtra(CloudSearchQuery.EXTRA_QUERY, "explicit")))
        assertEquals("explicit", CloudSearchQuery.from(search(" ").putExtra(CloudSearchQuery.EXTRA_QUERY, "explicit")))
    }

    @Test fun blankOrAbsentIsNoQuery() {
        for (blank in listOf(null, "", "   ", "\n\t ")) {
            assertNull(CloudSearchQuery.from(search(blank)))
            assertNull(CloudSearchQuery.from(webSearch(blank)))
            assertNull(CloudSearchQuery.from(share(blank)))
        }
        assertNull(CloudSearchQuery.from(null))
        assertNull(CloudSearchQuery.from(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)))
        // A shared picture is not a query, whatever its caption.
        assertNull(CloudSearchQuery.from(share("caption", type = "image/png")))
    }

    @Test fun longTextIsCutToTheLimit() {
        val q = CloudSearchQuery.from(share("word ".repeat(1000)))!!
        assertTrue(q.length <= CloudSearchQuery.MAX_LENGTH)
        assertEquals(q.trim(), q)
        assertTrue(q.startsWith("word word"))
        assertEquals("x".repeat(CloudSearchQuery.MAX_LENGTH), CloudSearchQuery.from(search("x".repeat(5000))))
    }

    @Test fun theSentIntentReadsBack() {
        val sent = CloudSearchQuery.intent("com.diegonmarcos.cloudsearch", "  café  near me ")
        assertEquals(Intent.ACTION_SEARCH, sent.action)
        assertEquals("com.diegonmarcos.cloudsearch", sent.`package`)
        assertEquals("café near me", sent.getStringExtra(SearchManager.QUERY))
        assertEquals("café near me", sent.getStringExtra(CloudSearchQuery.EXTRA_QUERY))
        assertEquals("café near me", CloudSearchQuery.from(sent))
    }

    // ── MainActivity: the query lands on the Search page and is sent ────────────────────────────

    /** Nothing answers: the chat stops at the missing token, which is all this needs. */
    private class OfflineHttp : Http {
        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int) = Http.Response(503, "")
        override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int) = Http.Response(503, "")
    }

    private lateinit var services: Services
    private lateinit var savedReader: (android.content.Context, String) -> Account.Token

    @Before fun offline() {
        services = Services(RuntimeEnvironment.getApplication(), OfflineHttp())
        Services.install(services)
        savedReader = Account.reader
        Account.reader = { _, _ -> Account.Token(null, "no token in this test") }
    }

    @After fun online() {
        Account.reader = savedReader
        Services.install(null)
    }

    /** The asked texts, once the send (off the main thread) has stored them. */
    private fun asked(n: Int): List<String> {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            val all = services.sessions.all().map { s -> s.messages.first { it.role == "user" }.content }
            if (all.size >= n) return all.sorted()
            Thread.sleep(20)
        }
        throw AssertionError("fewer than $n chat sessions stored within 10 s")
    }

    @Test fun aLaunchQueryAndALaterOneBothRunOnTheSearchPage() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, search("first query")).setup()
        assertEquals(listOf("first query"), asked(1))
        // singleTask, already open: the next query arrives through onNewIntent and starts its own chat.
        controller.newIntent(share("second query"))
        assertEquals(listOf("first query", "second query"), asked(2))
        controller.pause().stop().destroy()
    }

    @Test fun aPlainLaunchAsksNothing() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN)).setup()
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, services.sessions.all().size)
        controller.pause().stop().destroy()
    }
}
