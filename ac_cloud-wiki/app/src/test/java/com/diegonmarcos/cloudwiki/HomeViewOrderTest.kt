package com.diegonmarcos.cloudwiki

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1984 Home's view tabs read 3D | List | Table | Graph and a cold start opens Home on 3D: pinned on the real build.json::ui.home and on the pure helpers. */
class HomeViewOrderTest {
    private val home: JSONObject by lazy { JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONObject("home") }
    private val declaredModes: String by lazy { home.getJSONArray("view_modes").let { a -> (0 until a.length()).joinToString(",") { a.getString(it) } } }

    @Test fun theDeclaredTabOrderIs3DListTableGraph() {
        assertEquals(listOf("planet", "list", "table", "graph"), HomeIndex.order(declaredModes).map { it.id })
        assertEquals(listOf(HomeIndex.Mode.Planet, HomeIndex.Mode.Outline, HomeIndex.Mode.Table, HomeIndex.Mode.Graph), HomeIndex.order(declaredModes))
    }

    @Test fun aColdStartOpensHomeOn3D() {
        assertEquals(HomeIndex.Mode.Planet, HomeIndex.default(home.getString("default_view"), HomeIndex.order(declaredModes)))
        assertEquals(LibraryTree.HOME, HomeIndex.startPage(home.getString("start_page"), listOf(LibraryTree.HOME, "sciences", "chronos")))
    }

    @Test fun everyViewStaysWhateverTheDeclarationSays() {
        assertEquals(HomeIndex.Mode.entries.toSet(), HomeIndex.order("graph").toSet())
        assertEquals(HomeIndex.Mode.entries.size, HomeIndex.order("list,list,bogus").size)
        assertTrue(HomeIndex.order("").containsAll(HomeIndex.Mode.entries))
    }

    @Test fun anUndeclaredDefaultOrStartFallsBackToTheFirst() {
        assertEquals(HomeIndex.Mode.Planet, HomeIndex.default("nope", HomeIndex.order(declaredModes)))
        assertEquals("sciences", HomeIndex.startPage("nope", listOf("sciences", "home")))
    }
}
