package com.diegonmarcos.cloudbrowser

import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import com.diegonmarcos.superapp.browser.BrowserPageActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1741 Print... and Convert to PDF go through Android's print framework: the adapter is created and handed to the launcher. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PagePrintTest {
    private class FakeAdapter : PrintDocumentAdapter() {
        override fun onLayout(o: PrintAttributes?, n: PrintAttributes?, c: CancellationSignal?, cb: LayoutResultCallback?, e: Bundle?) {}
        override fun onWrite(p: Array<out android.print.PageRange>?, d: ParcelFileDescriptor?, c: CancellationSignal?, cb: WriteResultCallback?) {}
    }

    @Test fun `Convert to PDF creates the adapter for the job name and launches it with an A4 sheet`() {
        var launched: Triple<String, PrintDocumentAdapter, PrintAttributes?>? = null
        val made = FakeAdapter()
        var askedName: String? = null
        BrowserPageActions.printWith("pic.png", { n -> askedName = n; made }, BrowserPageActions.a4()) { n, a, at -> launched = Triple(n, a, at) }
        assertEquals("pic.png", askedName)
        assertEquals("pic.png", launched!!.first)
        assertSame(made, launched!!.second)
        assertEquals(PrintAttributes.MediaSize.ISO_A4, launched!!.third!!.mediaSize)
    }

    @Test fun `Print keeps the dialog defaults (no attributes)`() {
        var at: PrintAttributes? = PrintAttributes.Builder().build()
        BrowserPageActions.printWith("p", { FakeAdapter() }, null) { _, _, a -> at = a }
        assertNull(at)
    }

    @Test fun `the job name is file safe and never empty`() {
        assertEquals("a_b_c", BrowserPageActions.jobName("a/b\\c", null))
        assertEquals("example.com", BrowserPageActions.jobName(null, "https://example.com/x/y"))
        assertEquals("page", BrowserPageActions.jobName("  ", null))
        assertEquals(60, BrowserPageActions.jobName("x".repeat(200), null).length)
        assertNotNull(BrowserPageActions.jobName(null, null))
    }

    @Test fun `Convert to PDF and Print are both declared menu rows`() {
        val menu = org.json.JSONObject(java.io.File("../build.json").readText()).getJSONObject("ui").getJSONObject("browser").getJSONObject("menu").getJSONArray("items")
        val ids = (0 until menu.length()).associate { menu.getJSONObject(it).getString("id") to menu.getJSONObject(it).getString("label") }
        assertEquals("Convert to PDF", ids["convert_pdf"])
        assertEquals("Print…", ids["print"])
        assertTrue(ids.containsKey("download_page") && ids.containsKey("save_page"))
    }
}
