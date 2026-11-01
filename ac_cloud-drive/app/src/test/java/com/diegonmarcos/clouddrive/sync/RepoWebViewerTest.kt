package com.diegonmarcos.clouddrive.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1395 Git ▸ Open repo on web: the embedded CloudWeb BROWSER viewer, its menu in order, Back closes it, no external hand-off. */
class RepoWebViewerTest {
    private val sync = File("src/main/java/com/diegonmarcos/clouddrive/sync")

    @Test fun `the menu is Open in another browser, Chrome, Copy link, Share, Reload`() {
        assertEquals(listOf("OTHER_BROWSER", "CHROME", "COPY", "SHARE", "RELOAD"), RepoWebMenu.ORDER.map { it.name })
    }

    @Test fun `the viewer is CloudWeb BROWSER and Back closes it`() {
        val v = File(sync, "RepoWebViewer.kt").readText()
        assertTrue(v.contains("CloudWeb.create(c, WebProfile.BROWSER)") && v.contains("CloudWebClient(WebProfile.BROWSER)"))
        assertTrue(v.contains("BackHandler(onBack = onClose)"))
        assertFalse("no WebView of its own", Regex("""\bWebView\(""").containsMatchIn(v))
    }

    @Test fun `the Git page opens the repository web page in the viewer, not an external app`() {
        val page = File(sync, "GitReposScreen.kt").readText()
        assertFalse(page.contains("actions.openUrl(page.webUrl("))
        assertEquals(2, Regex("""RepoWebViewer\(u\)""").findAll(page).count())
    }
}
