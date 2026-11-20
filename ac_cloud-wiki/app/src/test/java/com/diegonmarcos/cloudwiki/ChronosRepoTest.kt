package com.diegonmarcos.cloudwiki

import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1830 the runtime clone of lib-chronos: the app derives the page tree of ITS clone the same way the build tool derived the baked one
 * (ids, labels, icons, assets, hierarchy), applies the same CDN rewrites, follows the refresh modes, and the side menu follows the clone.
 */
class ChronosRepoTest {
    private val runtime = ChronosRepo.parseRuntime(File("src/main/assets/chronos/runtime.json").readText())
    private val baked = Chronos.parse(JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONArray("sections").toString())

    @Test fun theRuntimeConfigIsThePins() {
        val pin = JSONObject(File("../tools/chronos-pin.json").readText())
        assertEquals(pin.getString("url"), runtime.url)
        assertEquals(pin.getString("ref"), runtime.ref)
        assertEquals(pin.getString("source_dir"), runtime.sourceDir)
        assertEquals(listOf("manual", "on_open", "every_6h", "daily"), runtime.modes.map { it.id })
        assertEquals("on_open", runtime.defaultMode)
        assertTrue("three.js is answered from the APK", runtime.rewrites.any { it.first.contains("three.js/r128/three.min.js") && it.second.startsWith(Chronos.SERVE_PATH + "vendor/") })
    }

    /** A tree scanned from a directory of the repo's shape is the tree the build tool declared for the same files. */
    @Test fun aScannedCloneGivesTheSameTreeAsTheBuildTool() {
        val scanned = ChronosRepo.scan(File("src/main/assets/chronos/" + runtime.sourceDir), runtime)
        assertEquals(baked.pages.map { it.id }, scanned.pages.map { it.id })
        assertEquals(baked.pages.map { it.label }, scanned.pages.map { it.label })
        assertEquals(baked.pages.map { it.icon }, scanned.pages.map { it.icon })
        assertEquals(baked.pages.map { it.asset }, scanned.pages.map { it.asset })
        assertEquals(baked.pages.map { it.parent }, scanned.pages.map { it.parent })
        assertEquals(baked.pages.map { it.children }, scanned.pages.map { it.children })
    }

    @Test fun pagesOfTheCloneHaveTheirCdnScriptsAnsweredFromTheApk() {
        val page = "<script src=\"https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js\"></script><script src='https://cdn.tailwindcss.com'></script>"
        val out = ChronosRepo.rewrite(page, runtime.rewrites)
        assertFalse(out.contains("https://"))
        assertTrue(out.contains("\"/chronos/vendor/three/r128/three.min.js\""))
        assertTrue(out.contains("'/chronos/vendor/tailwindcss/3.4.17/tailwind.js'"))
    }

    @Test fun theRefreshModes() {
        val m = runtime.modes.associateBy { it.id }
        val h = 3_600_000L
        assertFalse("manual never refreshes by itself", ChronosRepo.due(m.getValue("manual"), cloned = false, at = 0, now = 10 * h, opening = true))
        assertTrue("every open refreshes on opening", ChronosRepo.due(m.getValue("on_open"), true, 9 * h, 10 * h, opening = true))
        assertFalse(ChronosRepo.due(m.getValue("on_open"), true, 9 * h, 10 * h, opening = false))
        assertTrue("no clone yet: the timed modes clone", ChronosRepo.due(m.getValue("every_6h"), false, 0, 1, false))
        assertFalse(ChronosRepo.due(m.getValue("every_6h"), true, 5 * h, 10 * h, false))
        assertTrue(ChronosRepo.due(m.getValue("every_6h"), true, 3 * h, 10 * h, false))
        assertTrue(ChronosRepo.due(m.getValue("daily"), true, 0, 25 * h, false))
        assertFalse(ChronosRepo.due(m.getValue("daily"), true, 2 * h, 25 * h, false))
    }

    @Test fun idsAndOrderFollowTheBuildToolsRules() {
        assertEquals("3.0", ChronosRepo.segId("3.0.Quantum-Decoherence-Cycle"))
        assertEquals("4", ChronosRepo.segId("4.Astronomy-Astrological-sphere"))
        assertEquals("notes-x", ChronosRepo.segId("Notes X"))
        assertTrue(ChronosRepo.natural("2.B", "10.A") < 0)
        assertEquals("A & B", ChronosRepo.titleOf("<title>A &amp;\n  B</title>"))
    }

    /** #1831 Chronos is ONE side-menu entry; its simulators (from the clone) are its tabs, after a separator; Quantum's pages are its second row. */
    @Test fun chronosIsOneMenuEntryAndItsSimulatorsAreItsTabs() {
        val section = NavSection("wiki", "Wiki", "menu", pages = listOf(
            NavPage("math", "Math", "functions"),
            NavPage(Chronos.PARENT, "Chronos", "timeline", pages = listOf(NavPage("old", "Old", "x"))),
        ))
        val tree = ChronosTree(listOf(
            ChronosPage("chronos.lib", "All cycles", "timeline", "Chronos/index.html", null, emptyList()),
            ChronosPage("chronos.lib.3", "Quantum Cycles", "hub", "Chronos/3.Q/index.html", null, listOf("chronos.lib.3.1")),
            ChronosPage("chronos.lib.3.1", "Quantum Gravity Cycle", "x", "Chronos/3.Q/3.1.G/index.html", "chronos.lib.3", emptyList()),
            ChronosPage("chronos.lib.9", "New page", "description", "Chronos/9.New/index.html", null, emptyList()),
        ))
        val out = withChronos(section, tree)
        assertEquals(listOf("math", Chronos.PARENT), out.pages.map { it.id })
        assertTrue("no simulator in the side menu", out.pages.first { it.id == Chronos.PARENT }.pages.isEmpty())
        val tabs = chronosTabs(tree)
        assertEquals(listOf("chronos.lib", "chronos.lib.3", "chronos.lib.9"), tabs.map { it.id })
        assertEquals(listOf(true, false, false), tabs.map { it.separatorBefore })
        assertEquals("New page", tabs.last().label)
        assertEquals("a deep page sits under its section's tab", "chronos.lib.3", chronosTabOf(tree, "chronos.lib.3.1"))
        assertEquals(null, chronosTabOf(tree, "math"))
    }

    @Test fun theCloneIsReadOnlyAndServedSafely() {
        val src = File("src/main/java/com/diegonmarcos/cloudwiki/ChronosRepo.kt").readText()
        assertTrue(src.contains("JGitEngine.clone(r.url, scratch, depth = 1, singleBranch = true, branch = r.ref)"))
        listOf("commit(", "push(", "stage(", "add()").forEach { assertFalse("the clone is never written to: $it", src.contains(".$it")) }
        assertTrue(src.contains("it.setWritable(false, false)"))
        val page = File("src/main/java/com/diegonmarcos/cloudwiki/ui/ChronosPage.kt").readText()
        assertTrue("never above the clone's source dir", page.contains("if (!f.path.startsWith(base.path)) return notFound()"))
        assertTrue("off-origin still 403", page.contains("if (!onOrigin(u)) return blocked()"))
    }
}
