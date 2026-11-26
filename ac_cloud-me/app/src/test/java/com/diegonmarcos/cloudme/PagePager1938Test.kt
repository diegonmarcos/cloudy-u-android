package com.diegonmarcos.cloudme

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.navbartop2.SecondRowTags
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * #1938 the section pager. (1) the strips are drawn before the first page fetch completes (the fetch is stubbed to block forever); (2) the queue: the page on screen first, then nav
 * order, one in flight, a failure does not stop it, the visible page jumps, retained pages are bounded (least recently viewed released first); (3) every embedded page's WebView
 * gets the dark-mode configuration. test/test-pager-1938.sh mutates each of these rules red on the sources.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PagePager1938Test {
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

    @Test fun `the section and subpage strips are drawn while the first page fetch is still blocked`() {
        val started = CopyOnWriteArrayList<String>()
        val never = CompletableDeferred<Result<Unit>>()
        val blocking: suspend (String) -> Result<Unit> = { id -> started += id; never.await() }
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalPageFetch provides blocking) {
                CloudKitTheme(KitPalette(Color.Black, Color.DarkGray, Color.White, Color.LightGray, Color.Magenta, Color.Gray, Color.Black)) { SectionScreen("proj", null) }
            }
        }
        compose.waitForIdle()
        // the top strip (Home | Unix ...) and, once an area is opened, its own strip come from the declaration, not from any fetch
        compose.onNodeWithTag(SecondRowTags.tab("pj-unix")).assertExists()
        compose.onNodeWithTag(SecondRowTags.tab("pj-home")).assertExists()
        assertTrue("the queue started its first fetch on the page on screen", started.firstOrNull() == "pj-home")
        assertFalse("the fetch is still blocked", never.isCompleted)
    }

    @Test fun `the queue fetches the visible page first, then the rest in nav order, one in flight`() {
        val q = PageFetchQueue(listOf("a", "b", "c", "d"), 10)
        q.show("c")
        assertEquals("c", q.next()); assertNull("one at a time", q.next()); q.complete("c")
        assertEquals("a", q.next()); assertNull(q.next()); q.complete("a")
        assertEquals("b", q.next()); q.complete("b")
        assertEquals("d", q.next()); q.complete("d")
        assertNull(q.next())
    }

    @Test fun `a failed page shows its error, is skipped, and does not stop the queue`() {
        val q = PageFetchQueue(listOf("a", "b", "c"), 10)
        assertEquals("a", q.next()); q.fail("a", "HTTP 502")
        assertEquals(PageFetchQueue.State.FAILED, q.stateOf("a")); assertEquals("HTTP 502", q.errorOf("a"))
        assertEquals("b", q.next()); q.complete("b")
        assertEquals("c", q.next()); q.complete("c")
        assertNull("the failed page is not retried on its own", q.next())
        q.retry("a"); assertEquals("a", q.next())
    }

    @Test fun `the visible page jumps the queue but never runs beside the one in flight`() {
        val q = PageFetchQueue(listOf("a", "b", "c"), 10)
        assertEquals("a", q.next())
        q.show("c")
        assertNull("one in flight: the visible page waits for it", q.next())
        q.complete("a")
        assertEquals("c", q.next())
    }

    @Test fun `retained pages stay within the bound, the least recently viewed is released first, never the visible one`() {
        val q = PageFetchQueue(listOf("a", "b", "c", "d"), 2)
        q.show("a"); q.next(); assertTrue(q.complete("a").isEmpty())
        q.show("b"); q.next(); assertTrue(q.complete("b").isEmpty())
        q.show("c"); q.next(); assertEquals(listOf("a"), q.complete("c"))
        assertEquals(PageFetchQueue.State.RELEASED, q.stateOf("a"))
        assertEquals(listOf("b", "c"), q.retained())
        q.show("b"); q.show("d"); q.next(); assertEquals("c is older than b", listOf("c"), q.complete("d"))
        assertEquals(setOf("b", "d"), q.retained().toSet())
    }

    @Test fun `the prefetcher drives the queue, one fetch at a time, a failure on the way`() = runBlocking {
        val log = CopyOnWriteArrayList<String>(); var inFlight = 0; var maxInFlight = 0
        val released = CopyOnWriteArrayList<String>()
        val pf = PagePrefetcher(PageFetchQueue(listOf("a", "b", "c"), 10), { id ->
            inFlight++; maxInFlight = maxOf(maxInFlight, inFlight); log += id; kotlinx.coroutines.delay(5); inFlight--
            if (id == "b") Result.failure(java.io.IOException("down")) else Result.success(Unit)
        }, { released += it })
        pf.queue.show("b")
        val job = launch { pf.run() }
        withTimeout(5000) { while (pf.queue.stateOf("c") != PageFetchQueue.State.DONE) kotlinx.coroutines.delay(10) }
        job.cancel()
        assertEquals(listOf("b", "a", "c"), log.toList()); assertEquals(1, maxInFlight)
        assertEquals(PageFetchQueue.State.FAILED, pf.queue.stateOf("b"))
    }

    @Test fun `the pager is declared in build json and its bound is a positive number`() {
        val q = LnktreeCacheConfig.baked
        assertTrue(q.queueEnabled); assertTrue(q.queueMaxRetained >= 1)
        assertEquals(12, q.queueMaxRetained)
    }

    @Test fun `dark mode is configured for every embedded page and chosen by capability`() {
        assertEquals(WebDarkMode.Plan.ALGORITHMIC_PREFER_WEB, WebDarkMode.plan(true, true))
        assertEquals(WebDarkMode.Plan.FORCE_DARK_PREFER_WEB, WebDarkMode.plan(false, true))
        assertEquals(WebDarkMode.Plan.NONE, WebDarkMode.plan(false, false))
        val web = BuroLlcTestData.text("app/src/main/java/com/diegonmarcos/cloudme/WebPage.kt")
        assertTrue("WebPage.make must apply the dark configuration to every embedded WebView", web.contains("WebDarkMode.apply(settings)"))
        val dark = BuroLlcTestData.text("app/src/main/java/com/diegonmarcos/cloudme/WebDarkMode.kt")
        assertTrue(dark.contains("setAlgorithmicDarkeningAllowed(settings, true)") && dark.contains("DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING"))
    }
}
