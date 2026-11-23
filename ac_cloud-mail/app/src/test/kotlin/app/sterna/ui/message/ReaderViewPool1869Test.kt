package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1869 step 10: the reader's WebView pool, its reset on release, and the wiring that keeps the #10 guards. */
class ReaderViewPool1869Test {
    private class View(val n: Int) {
        var context: String? = null
        var tag: Any? = "doc"
        var settleToken: Any? = Any()
        var reportingEnabled = true
        var blankPending = false
        var destroyed = false
    }

    private var made = 0
    private fun pool(max: Int = 3) = ReaderViewPool<String, View>(
        max = max,
        create = { ctx -> View(++made).also { it.context = ctx } },
        rebind = { v, ctx -> v.context = ctx },
        reset = { v -> v.tag = null; v.settleToken = null; v.reportingEnabled = false; v.context = "app"; v.blankPending = true },
        destroy = { it.destroyed = true },
        ready = { !it.blankPending },
    )

    @Test fun `a released view is reset and reused for the next page, rebound to its context`() {
        val p = pool()
        val a = p.acquire("activity-1")
        p.release(a)
        assertEquals(null, a.tag)
        assertEquals(null, a.settleToken)
        assertFalse(a.reportingEnabled)
        assertEquals("an idle view holds no activity", "app", a.context)
        a.blankPending = false // its blank page finished
        val b = p.acquire("activity-2")
        assertSame(a, b)
        assertEquals("activity-2", b.context)
        assertEquals(1, made)
    }

    @Test fun `a view still blanking is not handed out`() {
        val p = pool()
        val a = p.acquire("x")
        p.release(a)
        val b = p.acquire("x")
        assertNotSame(a, b)
        assertEquals(2, made)
    }

    @Test fun `at most max idle views are kept, a surplus one is destroyed`() {
        val p = pool(max = 2)
        val views = (1..3).map { p.acquire("x") }
        views.forEach(p::release)
        assertEquals(2, p.idleCount)
        assertTrue(views[2].destroyed)
        assertFalse(views[0].destroyed || views[1].destroyed)
    }

    @Test fun `the same view released twice is kept once`() {
        val p = pool()
        val a = p.acquire("x")
        p.release(a)
        p.release(a)
        assertEquals(1, p.idleCount)
        assertFalse(a.destroyed)
    }

    private fun src(rel: String): String =
        listOf(File("src/main/kotlin/app/sterna/$rel"), File("app/src/main/kotlin/app/sterna/$rel")).first { it.isFile }.readText()

    @Test fun `the reader keeps its FrameLayout wrapper, its GPU layer latch and its profile, and releases into the pool`() {
        val ms = src("ui/message/MessageScreen.kt")
        val factory = ms.substringAfter("val webView = ReaderWebViews.pool.acquire(ctx)").substringBefore("update = { frame ->")
        assertTrue(factory.contains("setLayerType(if (useSoftwareLayer) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_NONE, null)"))
        assertTrue(factory.contains("webViewClient = client"))
        assertTrue("the WebView is not the AndroidView root (#10)", factory.contains("FrameLayout(ctx).apply {"))
        assertTrue(ms.contains("val ctx = android.content.MutableContextWrapper(base)"))
        assertTrue(ms.substringAfter("private object ReaderWebViews {").contains("CloudWeb.configure(BodyWebView(ctx), WebProfile.UNTRUSTED_HTML)"))
        assertTrue(ms.contains("ReaderWebViews.pool.release(webView)"))
        val reset = ms.substringAfter("fun reset(view: BodyWebView) {").substringBefore("\n    }\n")
        for (line in listOf("view.loadUrl(\"about:blank\")", "view.tag = null", "view.settleToken = null", "view.reportingEnabled = false", "view.blankPending = true")) {
            assertTrue("release must do `$line`", reset.contains(line))
        }
        assertTrue("the blank page is never measured as a body", ms.contains("if (wv is BodyWebView && wv.blankPending) {"))
        assertTrue("the #10 sentinel still arms per load", ms.contains("if (!useSoftwareLayer) WebViewLayerGuard.armIfUnproven(context)"))
    }

    @Test fun `the fade is skipped only when the tap's snapshot is ready, the crash latch first`() {
        val app = src("ui/SternaApp.kt")
        val enter = app.substringAfter("enterTransition = {").substringBefore("popExitTransition = {")
        val latch = enter.indexOf("if (messageFadeDisabled || !motionEnabled) EnterTransition.None")
        val ready = enter.indexOf("else if (readerSnapshotReady(readerHandoff, targetState.arguments)) EnterTransition.None")
        assertTrue(latch >= 0 && ready > latch)
        assertTrue(enter.contains("else fadeIn(tween(200))"))
        assertTrue(app.contains(".container.mailRepository.preloadReader(accountId, id)"))
    }
}
