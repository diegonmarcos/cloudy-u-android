package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.PAGE_TOOLS_LABEL
import com.diegonmarcos.superapp.browser.PAGE_TOOLS_ROW_MIN_DP
import com.diegonmarcos.superapp.browser.TAB_ROW_ICON_GLYPH_DP
import com.diegonmarcos.superapp.browser.TAB_ROW_ICON_TARGET_DP
import com.diegonmarcos.superapp.navbartop1.TopFirstRowSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1840 parity: the tab row's icons are the first row's icons, the entry is Page-Tools everywhere, its links are two steps larger, nothing removed. */
class BrowserTopRowParityTest {
    private fun src(rel: String): String {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, rel).isFile) d = d.parentFile
        return File(d, rel).readText()
    }
    private val lib = "ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/"

    @Test fun `the tab row icons are the size of the first row icons`() {
        assertEquals(TopFirstRowSpec.ICON_TARGET.value.toInt(), TAB_ROW_ICON_TARGET_DP)
        assertEquals(TopFirstRowSpec.ICON.value.toInt(), TAB_ROW_ICON_GLYPH_DP)
        val strip = src(lib + "BrowserTabStrip.kt")
        assertFalse("no 28 dp tab box left", strip.contains("size(28.dp)")); assertFalse("no 32 dp tools box left", strip.contains("size(32.dp)"))
    }

    @Test fun `the colourful emoji is a grey theme tinted vector named Page-Tools`() {
        val strip = src(lib + "BrowserTabStrip.kt")
        assertFalse(strip.contains("🛠")); assertTrue(strip.contains("tint = p.textSecondary")); assertTrue(strip.contains("PageToolsIcon"))
        assertEquals("Page-Tools", PAGE_TOOLS_LABEL)
        listOf(lib + "BrowserTabStrip.kt", lib + "BrowserHostFragment.kt", "ac_cloud-browser/app/src/main/java/com/diegonmarcos/cloudbrowser/MainActivity.kt", "ac_cloud-browser/build.json")
            .forEach { assertFalse("$it still says Browsing Tools", src(it).contains("Browsing Tools")) }
    }

    @Test fun `Page-Tools links are two font steps above the kit row and every section is still there`() {
        val sheets = src(lib + "BrowserSheets.kt")
        assertTrue(sheets.contains("PageToolsRow(") && sheets.contains("MaterialTheme.typography.bodyLarge"))   // kit row: bodySmall (12) -> bodyMedium (14) -> bodyLarge (16)
        assertTrue(PAGE_TOOLS_ROW_MIN_DP >= 44)
        val bj = src("ac_cloud-browser/build.json")
        listOf("add_to_home", "open_external", "copy_url", "bookmark").forEach { assertTrue("$it kept", bj.contains("\"id\": \"$it\"")) }
        listOf("Nav", "Scrapper", "Reading", "Site Data", "Perms", "Dev Tools").forEach { assertTrue("$it kept", bj.contains("\"label\": \"$it\"")) }   // #1856 renamed; pin_site is the one authorised removal
    }

    @Test fun `the add to home sheet and its IPC are declared`() {
        val frag = src(lib + "BrowserHostFragment.kt")
        assertTrue(frag.contains("showAddToHome(") && frag.contains("ShortcutManagerCompat.requestPinShortcut"))
        val man = src("ac_cloud-browser/app/src/main/AndroidManifest.xml")
        assertTrue(man.contains("com.diegonmarcos.superapp\""))
        assertTrue(man.contains("enableOnBackInvokedCallback") && man.contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS") && man.contains("BrowserKeepAliveService"))
    }
}
