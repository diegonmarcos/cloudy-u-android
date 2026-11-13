package com.diegonmarcos.cgomniverse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Galaxy's tabs: the first launch opens the declared default tabs (floor796 alone when none is declared), open adds or focuses,
 * close picks a neighbour, the state round-trips, and a device that already has tabs is given the newly declared ones once (#1711).
 */
class TabsTest {
    private fun w(id: String, url: String = "https://$id.example") = World(id, id, url, "Website", "2D", "", emptyList(), false, "d")
    private val worlds = listOf(w("a"), w("b"), w("floor796", "https://floor796.com"), w("c"))
    private fun st(sel: String?, vararg open: String) = Tabs.State(open.toList(), sel)

    @Test fun theFirstLaunchOpensFloor796() {
        assertEquals(st("floor796", "floor796"), Tabs.default(worlds))
        assertEquals(st("floor796", "floor796"), Tabs.decode(null, worlds))
        assertEquals(Tabs.EMPTY, Tabs.default(listOf(w("a"))))
    }

    @Test fun theRealCatalogueDefaultsToFloor796() {
        val f = listOf(File("../data/store.json"), File("data/store.json")).first { it.exists() }
        val d = Tabs.default(Store.parse(f.readText()))
        assertEquals(listOf("floor796"), d.open)
        assertEquals("floor796", d.selected)
    }

    // ── #1711 the declared default tabs: Cyber City Orion, floor796, Messenger ───────────────────────────────────────────────
    private val declared = listOf("o", "floor796", "m")
    private val more = worlds + listOf(w("o"), w("m"))

    @Test fun theFirstLaunchOpensTheDeclaredTabsInTheirOrder() {
        assertEquals(st("o", "o", "floor796", "m"), Tabs.default(more, declared))
        assertEquals(st("o", "o", "floor796", "m"), Tabs.decode(null, more, declared))
        // a declared world the catalogue lacks is left out, never a broken tab; none declared is the old single default
        assertEquals(st("floor796", "floor796"), Tabs.default(worlds, declared))
        assertEquals(Tabs.EMPTY, Tabs.default(listOf(w("a")), declared))
        assertEquals(st("floor796", "floor796"), Tabs.default(more, emptyList()))
        assertEquals(listOf("o", "floor796", "m"), Tabs.ids(" o,floor796 ,,m,o"))
        assertTrue(Tabs.ids("").isEmpty())
    }

    /** build.json::galaxy_tabs against the real catalogue: Orion, floor796, Messenger, in that order, Orion shown first. */
    @Test fun theRealDeclarationOpensOrionFloor796AndMessenger() {
        val store = listOf(File("../data/store.json"), File("data/store.json")).first { it.exists() }
        val build = listOf(File("../build.json"), File("build.json")).first { it.exists() }
        val ids = org.json.JSONObject(build.readText()).getJSONArray("galaxy_tabs").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(listOf("cyber-city-orion", "floor796", "messenger"), ids)
        val all = Store.parse(store.readText())
        val d = Tabs.default(all, ids)
        assertEquals(ids, d.open); assertEquals("cyber-city-orion", d.selected)
        assertEquals("https://orion.adrianred.com/", all.first { it.id == "cyber-city-orion" }.url)
        assertEquals("https://messenger.abeto.co/", all.first { it.id == "messenger" }.url)
        // the declaration is baked by the build and read through Decl; an id the catalogue lacks fails the build
        val gradle = listOf(File("build.gradle"), File("app/build.gradle")).first { it.exists() && it.readText().contains("GALAXY_TABS") }.readText()
        assertTrue(gradle.contains("buildConfigField \"String\", \"GALAXY_TABS\"") && gradle.contains("is not a world of data/store.json"))
    }

    @Test fun aDeviceWithTabsIsGivenTheNewDeclaredOnesOnce() {
        // the phone today: one tab, floor796, given before the declaration existed
        assertEquals(st("floor796", "o", "floor796", "m"), Tabs.seed(st("floor796", "floor796"), declared, setOf("floor796"), more))
        // the user's other tabs follow the declared ones, in their own order; the selection stays
        assertEquals(st("c", "o", "floor796", "m", "c", "a"), Tabs.seed(st("c", "c", "floor796", "a"), declared, setOf("floor796"), more))
        // a declared tab the user closed stays closed; with no tab at all the first new one is shown
        assertEquals(st("a", "o", "m", "a"), Tabs.seed(st("a", "a"), declared, setOf("floor796"), more))
        assertEquals(st("o", "o", "m"), Tabs.seed(Tabs.EMPTY, declared, setOf("floor796"), more))
        // once given, never again: a closed Orion is not brought back
        val given = setOf("o", "floor796", "m")
        assertEquals(st("floor796", "floor796"), Tabs.seed(st("floor796", "floor796"), declared, given, more))
        assertEquals(Tabs.EMPTY, Tabs.seed(Tabs.EMPTY, declared, given, more))
        // a tab that is open already is not doubled, and a declared world the catalogue lacks is not added
        assertEquals(st("o", "o", "floor796", "m"), Tabs.seed(st("o", "o", "floor796"), declared, setOf("floor796"), more))
        assertEquals(st("floor796", "floor796"), Tabs.seed(st("floor796", "floor796"), declared, setOf("floor796"), worlds))
    }

    @Test fun theTabsAtAppStart() {
        // a first launch: the declared tabs, all counted as given
        val first = Tabs.restore(null, null, declared, more)
        assertEquals(st("o", "o", "floor796", "m"), first.state); assertEquals("o,floor796,m", first.seeded)
        // the update on a device from before the declaration: floor796 was the only tab ever given
        val update = Tabs.restore("floor796|floor796", null, declared, more)
        assertEquals(st("floor796", "o", "floor796", "m"), update.state); assertEquals("floor796,o,m", update.seeded)
        // ... and a floor796 tab closed on that device stays closed (it was given before)
        assertEquals(st("a", "o", "m", "a"), Tabs.restore("a|a", null, declared, more).state)
        // the next start: nothing more to give, whatever the user closed since
        val next = Tabs.restore("m|m", update.seeded, declared, more)
        assertEquals(st("m", "m"), next.state); assertEquals("floor796,o,m", next.seeded)
        // every tab closed stays closed, and a later declaration is given once
        assertEquals(Tabs.EMPTY, Tabs.restore("|", "floor796,o,m", declared, more).state)
        assertEquals(st("a", "o", "floor796", "m", "b", "a"), Tabs.restore("a|o,floor796,m,a", "floor796,o,m", declared + "b", more).state)
        // no declaration: exactly the old behaviour
        assertEquals(st("floor796", "floor796"), Tabs.restore(null, null, emptyList(), more).state)
        assertEquals(st("a", "a"), Tabs.restore("a|a", null, emptyList(), more).state)
        val shell = listOf(File("src/main/java/com/diegonmarcos/cgomniverse/ui/AppShell.kt"), File("app/src/main/java/com/diegonmarcos/cgomniverse/ui/AppShell.kt")).first { it.exists() }.readText()
        assertTrue(shell.contains("Tabs.restore(Web.tabs(ctx), Web.tabsSeeded(ctx), Decl.galaxyTabs, Decl.worlds)") && shell.contains("Web.setTabsSeeded(ctx, restored.seeded)"))
    }

    @Test fun openAddsAtTheEndAndSelects() = assertEquals(st("b", "a", "b"), Tabs.open(st("a", "a"), "b"))

    @Test fun openingAnOpenWorldFocusesItWithoutADuplicate() =
        assertEquals(st("a", "a", "b"), Tabs.open(st("b", "a", "b"), "a"))

    @Test fun closingTheSelectedTabSelectsTheRightNeighbourElseTheLeft() {
        assertEquals(st("c", "a", "c"), Tabs.close(st("b", "a", "b", "c"), "b"))
        assertEquals(st("b", "a", "b"), Tabs.close(st("c", "a", "b", "c"), "c"))
        assertEquals(st(null), Tabs.close(st("a", "a"), "a"))
    }

    @Test fun closingAnotherTabKeepsTheSelection() {
        assertEquals(st("c", "b", "c"), Tabs.close(st("c", "a", "b", "c"), "a"))
        assertEquals(st("a", "a"), Tabs.close(st("a", "a"), "zz"))
    }

    @Test fun tabsPersist() {
        val s = st("c", "a", "floor796", "c")
        assertEquals(s, Tabs.decode(Tabs.encode(s), worlds))
        // every tab closed stays closed: not the first-launch default
        assertEquals(Tabs.EMPTY, Tabs.decode(Tabs.encode(Tabs.EMPTY), worlds))
    }

    @Test fun aRestoreDropsUnknownWorldsAndALostSelection() {
        assertEquals(st("a", "a", "b"), Tabs.decode("gone|a,gone,b", worlds))
        assertNull(Tabs.decode("|", worlds).selected)
        assertTrue(Tabs.decode("|", worlds).open.isEmpty())
    }
}
