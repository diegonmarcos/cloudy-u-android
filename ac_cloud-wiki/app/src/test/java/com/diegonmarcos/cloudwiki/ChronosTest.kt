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
        assertEquals(listOf("chronos.lib.0", "chronos.lib.1", "chronos.lib.2", "chronos.lib.3", "chronos.lib.4"), tree.page("chronos.lib")?.children)
        assertEquals(listOf("chronos.lib.3.0", "chronos.lib.3.1"), tree.page("chronos.lib.3")?.children)
        assertEquals(listOf("chronos.lib", "chronos.lib.3", "chronos.lib.3.1"), tree.path("chronos.lib.3.1").map { it.id })
        assertTrue(tree.path("math").isEmpty())
    }

    @Test fun theLabelsAreThePagesOwnTitles() {
        assertEquals("Chronos - Cycles Across Scales", tree.root?.label)
        for (p in tree.pages) {
            val html = File("src/main/assets/chronos/" + p.asset).readText()
            val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
                ?.replace("&amp;", "&")?.replace(Regex("\\s+"), " ")?.trim()
            assertEquals("the label of ${p.id} is its page's <title>", title, p.label)
        }
    }

    @Test fun everyDeclaredAssetIsInTheApkAndEveryAssetPageIsDeclared() {
        val root = File("src/main/assets/chronos")
        val onDisk = root.walkTopDown().filter { it.isFile && it.name == "index.html" }.map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .filter { it.startsWith("Chronos/") }.toSortedSet()
        assertEquals(onDisk, tree.pages.map { it.asset }.toSortedSet())
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
        assertNull(tree.pageAt("/chronos/_vendor/chart.js/4.6.1/chart.umd.min.js"))
        assertNull(tree.pageAt("/assets/Chronos/"))
        assertNull(tree.pageAt(null))
    }

    @Test fun aRequestPathNamesItsAssetAndNeverClimbsOut() {
        assertEquals("Chronos/index.html", Chronos.assetFor("Chronos/"))
        assertEquals("index.html", Chronos.assetFor(""))
        assertEquals("_vendor/tailwindcss/3.4.17/tailwind.js", Chronos.assetFor("_vendor/tailwindcss/3.4.17/tailwind.js"))
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
