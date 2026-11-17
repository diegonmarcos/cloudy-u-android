package com.diegonmarcos.cloudme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1771 the font stylesheet that held back the Professional page's script is answered at once; nothing else is gated. */
class FontSheetGateTest {
    @Test fun the_google_fonts_stylesheet_is_gated() {
        assertTrue(FontSheetGate.blocks("fonts.googleapis.com", "/css2"))
        assertTrue(FontSheetGate.blocks("Fonts.GoogleAPIs.com", "/css"))
    }

    @Test fun other_requests_are_not_gated() {
        assertFalse(FontSheetGate.blocks("fonts.gstatic.com", "/s/inter/v12/x.woff2"))
        assertFalse(FontSheetGate.blocks("fonts.googleapis.com", "/icon"))
        assertFalse(FontSheetGate.blocks("analytics.example.org", "/css2"))
        assertFalse(FontSheetGate.blocks(null, null))
        assertFalse(FontSheetGate.blocks("fonts.googleapis.com.evil.example", "/css2"))
    }

    @Test fun the_bundled_pages_still_name_that_stylesheet_and_the_page_hands_it_to_the_gate() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "data/web/mysocials").isDirectory }
        val html = File(root, "data/web/mysocials/linkedin.html").readText()
        // if the page stops linking the sheet the gate is dead code: say so
        assertTrue("linkedin.html no longer links the font stylesheet: drop FontSheetGate", html.contains("https://fonts.googleapis.com/css"))
        val page = File(root, "app/src/main/java/com/diegonmarcos/cloudme/WebPage.kt").readText()
        assertTrue("WebPage no longer consults FontSheetGate", page.contains("FontSheetGate.blocks("))
    }
}
