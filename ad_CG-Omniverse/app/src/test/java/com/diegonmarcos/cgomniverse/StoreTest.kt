package com.diegonmarcos.cgomniverse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Store's catalogue and filter logic. The mapping (2D/3D and 3D/Video count as both; VR Yes is VR Glass and Flat Screen, VR No
 * Flat Screen only), the complementary chips within a group, "All" as every chip, and every group having to pass. Each rule is
 * held by a test that a planted mutant of Store.kt turns red (.github/workflows/cg-omniverse-proof.yml).
 */
class StoreTest {
    private fun w(id: String, type: String, dim: String, vr: Boolean) =
        World(id, id, "https://x.example/$id", type, dim, "fw", emptyList(), vr, "d")

    private val game3d = w("g3", "Game", "3D", false)
    private val game2d = w("g2", "Game", "2D", false)
    private val gameMix = w("gm", "Game", "2D/3D", false)
    private val gameVideoVr = w("gv", "Game", "3D/Video", true)
    private val site3d = w("s3", "Website", "3D", false)
    private val site2d = w("s2", "Website", "2D", false)
    private val other3dVr = w("o3", "Others", "3D", true)
    private val other2d = w("o2", "Others", "2D", false)
    private val all = listOf(game3d, game2d, gameMix, gameVideoVr, site3d, site2d, other3dVr, other2d)

    private fun only(vararg pairs: Pair<FilterGroup, Set<String>>) = Store.allSelected() + pairs.toMap()
    private fun ids(sel: Map<FilterGroup, Set<String>>) = Store.filter(all, sel).map { it.id }

    @Test fun launchUrlAppendsTheDeclaredQuery() {
        val base = w("f", "Website", "2D", false)
        assertEquals("https://x.example/f", Store.launchUrl(base))
        assertEquals("https://x.example/f?old-render", Store.launchUrl(base.copy(launchQuery = "old-render")))
        assertEquals("https://x.example/f?a=1&old-render#h", Store.launchUrl(base.copy(url = "https://x.example/f?a=1#h", launchQuery = "old-render")))
    }

    @Test fun floor796DeclaresTheLighterRenderPath() {
        val json = listOf(File("../data/store.json"), File("data/store.json")).first { it.exists() }.readText()
        val f = Store.parse(json).first { it.id == "floor796" }
        assertEquals("old-render", f.launchQuery)
        assertTrue(Store.launchUrl(f).endsWith("?old-render"))
    }

    @Test fun everythingIsSelectedByDefault() {
        val sel = Store.allSelected()
        FilterGroup.entries.forEach { assertTrue(Store.isAll(sel, it)); assertEquals(it.chips.toSet(), sel[it]) }
        assertEquals(all.map { it.id }, ids(sel))
    }

    @Test fun typeMapsEachKind() {
        assertEquals(setOf(Store.GAMES), Store.tags(game3d, FilterGroup.TYPE))
        assertEquals(setOf(Store.WEBSITES), Store.tags(site3d, FilterGroup.TYPE))
        assertEquals(setOf(Store.OTHERS), Store.tags(other2d, FilterGroup.TYPE))
        assertEquals(emptySet<String>(), Store.tags(w("u", "Unknown", "3D", false), FilterGroup.TYPE))
    }

    @Test fun mixedDimensionsCountAsBoth() {
        assertEquals(setOf(Store.D3), Store.tags(game3d, FilterGroup.DIMENSION))
        assertEquals(setOf(Store.D2), Store.tags(game2d, FilterGroup.DIMENSION))
        assertEquals(setOf(Store.D2, Store.D3), Store.tags(gameMix, FilterGroup.DIMENSION))
        assertEquals(setOf(Store.D2, Store.D3), Store.tags(gameVideoVr, FilterGroup.DIMENSION))
        assertEquals(listOf("g2", "gm", "gv", "s2", "o2"), ids(only(FilterGroup.DIMENSION to setOf(Store.D2))))
        assertEquals(listOf("g3", "gm", "gv", "s3", "o3"), ids(only(FilterGroup.DIMENSION to setOf(Store.D3))))
    }

    @Test fun vrYesIsBothDevicesAndVrNoIsFlatOnly() {
        assertEquals(setOf(Store.FLAT, Store.VR_GLASS), Store.tags(other3dVr, FilterGroup.DEVICE))
        assertEquals(setOf(Store.FLAT), Store.tags(game3d, FilterGroup.DEVICE))
        assertEquals(listOf("gv", "o3"), ids(only(FilterGroup.DEVICE to setOf(Store.VR_GLASS))))
        assertEquals(all.map { it.id }, ids(only(FilterGroup.DEVICE to setOf(Store.FLAT))))
    }

    @Test fun chipsInAGroupAreComplementary() {
        assertEquals(listOf("g3", "g2", "gm", "gv"), ids(only(FilterGroup.TYPE to setOf(Store.GAMES))))
        assertEquals(listOf("g3", "g2", "gm", "gv", "s3", "s2"), ids(only(FilterGroup.TYPE to setOf(Store.GAMES, Store.WEBSITES))))
        assertEquals(listOf("o3", "o2"), ids(only(FilterGroup.TYPE to setOf(Store.OTHERS))))
    }

    @Test fun everyGroupMustPass() {
        val sel = only(FilterGroup.TYPE to setOf(Store.GAMES), FilterGroup.DIMENSION to setOf(Store.D3), FilterGroup.DEVICE to setOf(Store.VR_GLASS))
        assertEquals(listOf("gv"), ids(sel))
        assertTrue(Store.matches(gameVideoVr, sel))
        assertFalse(Store.matches(game3d, sel))
        assertFalse(Store.matches(other3dVr, sel))
        assertEquals(emptyList<String>(), ids(only(FilterGroup.TYPE to emptySet())))
    }

    @Test fun toggleFlipsOneChipOnly() {
        var sel = Store.allSelected()
        sel = Store.toggle(sel, FilterGroup.TYPE, Store.OTHERS)
        assertEquals(setOf(Store.GAMES, Store.WEBSITES), sel[FilterGroup.TYPE])
        assertFalse(Store.isAll(sel, FilterGroup.TYPE))
        assertTrue(Store.isAll(sel, FilterGroup.DIMENSION))
        sel = Store.toggle(sel, FilterGroup.TYPE, Store.OTHERS)
        assertTrue(Store.isAll(sel, FilterGroup.TYPE))
        assertEquals(sel, Store.toggle(sel, FilterGroup.TYPE, Store.D3))
    }

    @Test fun allSelectsEveryChipOrClearsTheGroup() {
        var sel = Store.toggle(Store.allSelected(), FilterGroup.DIMENSION, Store.D2)
        sel = Store.toggleAll(sel, FilterGroup.DIMENSION)
        assertEquals(setOf(Store.D3, Store.D2), sel[FilterGroup.DIMENSION])
        sel = Store.toggleAll(sel, FilterGroup.DIMENSION)
        assertEquals(emptySet<String>(), sel[FilterGroup.DIMENSION])
        assertTrue(Store.isAll(sel, FilterGroup.TYPE))
        assertTrue(Store.isAll(sel, FilterGroup.DEVICE))
    }

    @Test fun theGeneratedCatalogueParsesWhole() {
        val f = listOf(File("../data/store.json"), File("data/store.json")).first { it.exists() }
        val worlds = Store.parse(f.readText())
        assertEquals(31, worlds.size)
        assertEquals(worlds.size, Store.filter(worlds, Store.allSelected()).size)
        assertEquals(5, worlds.count { it.vr })
        assertEquals(worlds.map { it.id }.toSet().size, worlds.size)
        assertTrue(worlds.all { it.url.startsWith("http") && it.name.isNotEmpty() && it.description.isNotEmpty() })
        assertTrue(Store.parse("not json").isEmpty())
    }

    @Test fun floor796IsInTheCatalogue() {
        val f = listOf(File("../data/store.json"), File("data/store.json")).first { it.exists() }
        val w = Store.parse(f.readText()).single { it.id == "floor796" }
        assertEquals("floor796", w.name)
        assertEquals("https://floor796.com", w.url)
        assertEquals(setOf(Store.WEBSITES), Store.tags(w, FilterGroup.TYPE))
        assertEquals(setOf(Store.D2), Store.tags(w, FilterGroup.DIMENSION))
        assertEquals(setOf(Store.FLAT), Store.tags(w, FilterGroup.DEVICE))
    }
}
