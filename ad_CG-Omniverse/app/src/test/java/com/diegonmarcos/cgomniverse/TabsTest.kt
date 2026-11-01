package com.diegonmarcos.cgomniverse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Galaxy's tabs: the first launch opens floor796, open adds or focuses, close picks a neighbour, and the state round-trips. */
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
