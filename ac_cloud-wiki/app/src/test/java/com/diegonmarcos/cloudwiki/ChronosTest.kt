package com.diegonmarcos.cloudwiki

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1789 the Chronos pages: the nested declaration build.json::ui wiki > chronos (written by tools/regen-chronos.py from lib-chronos at the
 * pin) read as the app reads it, the URL paths of the asset origin mapped to pages both ways, and every declared asset in the APK assets.
 */
class ChronosTest {
    private val sections = JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONArray("sections").toString()
    private val tree = Chronos.parse(sections)

    @Test fun theTreeIsTheRepoHierarchyUnderChronos() {
        assertEquals("chronos.lib", tree.root?.id)
        assertEquals(listOf("chronos.lib", "chronos.lib.0", "chronos.lib.1", "chronos.lib.2", "chronos.lib.3", "chronos.lib.3.0", "chronos.lib.3.1", "chronos.lib.4"),
            tree.pages.map { it.id })
        // #1831 FLAT under the Chronos topic: the root page and the five sections are siblings; only Quantum Cycles nests its two pages
        assertEquals(listOf("chronos.lib", "chronos.lib.0", "chronos.lib.1", "chronos.lib.2", "chronos.lib.3", "chronos.lib.4"),
            tree.pages.filter { it.parent == null }.map { it.id })
        assertEquals(emptyList<String>(), tree.page("chronos.lib")?.children)
        assertEquals(listOf("chronos.lib.3.0", "chronos.lib.3.1"), tree.page("chronos.lib.3")?.children)
        assertEquals(listOf("chronos.lib.3", "chronos.lib.3.1"), tree.path("chronos.lib.3.1").map { it.id })
        assertTrue(tree.path("math").isEmpty())
    }

    /** #1831 the menu names are the repo's own: each page as its hub card names it (as the repo's README lists them). */
    @Test fun theLabelsAreTheReposOwnNames() {
        assertEquals(
            listOf("All cycles", "Humansphere - Circadian", "Earthsphere - Cycles", "Milkwaysphere - Orbits", "Quantum Cycles",
                "Quantum Decoherence Cycle", "Quantum Gravity Cycle", "Astronomy - Astrological Sphere"),
            tree.pages.map { it.label },
        )
    }

    @Test fun everyDeclaredAssetIsInTheApkAndEveryAssetPageIsDeclared() {
        val root = File("src/main/assets/chronos")
        val onDisk = root.walkTopDown().filter { it.isFile && it.name == "index.html" }.map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .filter { it.startsWith("Chronos/") }.toSortedSet()
        assertEquals(onDisk, tree.pages.map { it.asset }.toSortedSet())
    }

    @Test fun noBakedChronosPageIsAPlaceholder() {
        val pages = File("src/main/assets/chronos/Chronos").walkTopDown().filter { it.extension == "html" }.toList()
        assertTrue("the baked Chronos pages exist", pages.size >= 8)
        pages.forEach { assertTrue(it.path + " is a placeholder", !it.readText().contains("Simulator pending upload")) }
    }

    @Test fun aPageLoadsByItsDirectorySoRelativeLinksResolve() {
        assertEquals("/chronos/Chronos/", Chronos.path(tree.root!!))
        assertEquals("/chronos/Chronos/3.Quantum-Cycles/3.0.Quantum-Decoherence-Cycle/", Chronos.path(tree.page("chronos.lib.3.0")!!))
    }

    @Test fun aUrlPathOfTheAssetOriginNamesItsPage() {
        assertEquals("chronos.lib.3", tree.pageAt("/chronos/Chronos/3.Quantum-Cycles/")?.id)
        assertEquals("chronos.lib.3", tree.pageAt("/chronos/Chronos/3.Quantum-Cycles/index.html")?.id)
        assertEquals("chronos.lib.3", tree.pageAt("/chronos/Chronos/3.Quantum-Cycles")?.id)
        assertEquals("chronos.lib", tree.pageAt("/chronos/Chronos/")?.id)
        assertNull(tree.pageAt("/chronos/vendor/chart.js/4.6.1/chart.umd.min.js"))
        assertNull(tree.pageAt("/assets/Chronos/"))
        assertNull(tree.pageAt(null))
    }

    @Test fun aRequestPathNamesItsAssetAndNeverClimbsOut() {
        assertEquals("Chronos/index.html", Chronos.assetFor("Chronos/"))
        assertEquals("index.html", Chronos.assetFor(""))
        assertEquals("vendor/tailwindcss/3.4.17/tailwind.js", Chronos.assetFor("vendor/tailwindcss/3.4.17/tailwind.js"))
        assertNull(Chronos.assetFor("Chronos/../../app-perms.json"))
        assertNull(Chronos.assetFor(".."))
    }

    @Test fun nothingDeclaredOrBrokenJsonGivesNoPages() {
        assertTrue(Chronos.parse("not json").pages.isEmpty())
        assertTrue(Chronos.parse("[]").pages.isEmpty())
        assertTrue(Chronos.parse("""[{"id":"wiki","pages":[{"id":"chronos","label":"Chronos","icon":"timeline"}]}]""").pages.isEmpty())
        assertNull(Chronos.parse("[]").root)
    }

    @Test fun theMarkdownChronosTopicKeepsItsPagesBesideTheSimulators() {
        val chronos = Data.wiki.topic("chronos")
        assertTrue("the dataset's Chronos topic keeps its Markdown subpages", (chronos?.pages?.size ?: 0) >= 4)
        assertTrue("no Markdown page id collides with a Chronos page id", chronos!!.pages.none { tree.page(it.id) != null })
    }
}
