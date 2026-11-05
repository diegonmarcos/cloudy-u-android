package com.diegonmarcos.superapp.appstore

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * #1524 GUARD: the Store has ONE place that draws a download or install progress, the Activity panel. No page, row or other file may bring
 * back a progress bar, a second panel or a per-row percent. The guard is itself shown red on a mutated source.
 */
class StoreActivityGuardTest {

    private val panel = "StoreActivityPanel.kt"
    private val widget = Regex("""\b(LinearProgressIndicator|CircularProgressIndicator|ProgressIndicator|progress\.Panel|StoreDownloadsPanel|PROGRESS_TAG)\b|"store-(progress|downloads)""")
    private val pages = listOf("StoreCloudFragment.kt", "StorePhoneFragment.kt")

    private fun code(s: String) = s.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.replace(Regex("""(?<![:"])//.*"""), "") }

    /** Violations in [files] (path to text): a progress widget outside the panel, or a page without exactly one panel. */
    fun violations(files: Map<String, String>): List<String> {
        val v = ArrayList<String>()
        for ((path, raw) in files) {
            val name = path.substringAfterLast('/')
            val c = code(raw)
            if (name != panel) widget.findAll(c).forEach { v += "$path draws progress outside the Activity panel: ${it.value}" }
            if (name in pages && Regex("""\bStoreActivityPanel\(""").findAll(c).count() != 1) v += "$path must draw exactly one StoreActivityPanel"
        }
        return v
    }

    private fun root(): File? {
        var d: File? = File("").absoluteFile
        while (d != null) { if (File(d, "ab_cloud-libs-shared").isDirectory) return d; d = d.parentFile }
        return null
    }

    private fun real(): Map<String, String>? {
        val r = root() ?: return null
        val skip = setOf("build", ".git", "z_archive", "node_modules", "test", "androidTest")
        return r.walkTopDown().onEnter { it.name !in skip }
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path }
            .filter { "/libs/appstore/" in it.path || "/ac_cloud-store/app/" in it.path }
            .associate { it.path to it.readText() }
    }

    @Test fun theRealStoreHasNoProgressWidgetOutsideTheActivityPanel() {
        val files = real(); assumeTrue(files != null)
        assertTrue("scanned the pages and the panel", pages.all { p -> files!!.keys.any { it.endsWith("/$p") } } && files!!.keys.any { it.endsWith("/$panel") })
        assertEquals(emptyList<String>(), violations(files!!))
    }

    @Test fun thePanelIsTheOnlyFileWithABar() {
        val files = real(); assumeTrue(files != null)
        val bars = files!!.filter { Regex("""LinearProgressIndicator\(""").containsMatchIn(code(it.value)) }.keys.map { it.substringAfterLast('/') }
        assertEquals(listOf(panel), bars)
    }

    @Test fun aRowCarriesNoProgressNumbers() {
        val files = real(); assumeTrue(files != null)
        val page = code(files!!.entries.first { it.key.endsWith("/StoreCloudFragment.kt") }.value)
        // The row's running verb is a glyph; the text of a running stage is never copied into the row.
        assertTrue(page.contains("if (busy) { dot = "))
        assertTrue(page.contains("if (!busy && (stg.cached != null || stg.failedAt != null))"))
    }

    @Test fun mutationABarOrASecondPanelInAPageIsCaught() {
        val page = "/x/appstore/StoreCloudFragment.kt"
        val ok = "fun C() { StoreActivityPanel(updates = 0, onUpdateAll = {}) }"
        assertEquals(emptyList<String>(), violations(mapOf(page to ok, "/x/appstore/StoreActivityPanel.kt" to "fun P() { LinearProgressIndicator() }")))
        assertTrue(violations(mapOf(page to ok + "\nfun B() { LinearProgressIndicator(progress = { 0.5f }) }")).isNotEmpty())
        assertTrue(violations(mapOf(page to ok + "\nfun B() { StoreDownloadsPanel() }")).isNotEmpty())
        assertTrue(violations(mapOf(page to "fun C() { progress.Panel() }")).size >= 2)
        assertTrue(violations(mapOf(page to ok + "\nfun D() { StoreActivityPanel(0, {}) }")).any { it.contains("exactly one") })
        assertTrue(violations(mapOf("/x/appstore/StorePhoneFragment.kt" to "fun C() {}")).any { it.contains("exactly one") })
    }
}
