package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.C3Nav.Target
import com.diegonmarcos.superapp.bottomnav.NavDecl
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Start page, nav order, aliases and the drawer tree, as pure functions of THIS repository's build.json. */
class C3NavTest {

    private fun ui(): JsonObject {
        var dir = File("").absoluteFile
        while (dir.parentFile != null && !File(dir, "build.json").isFile) dir = dir.parentFile
        return Json.parseToJsonElement(File(dir, "build.json").readText()).jsonObject["ui"]!!.jsonObject
    }

    private val nav: NavDecl by lazy {
        val u = ui()
        NavDecl.parse(u["sections"]!!.toString(), u["bottom_nav"]!!.jsonArray.joinToString(",") { it.jsonPrimitive.content },
            u["default_section"]!!.jsonPrimitive.content)
    }
    private val aliases: Declarations.Aliases by lazy { Declarations.parseAliases(ui()["aliases"]!!.toString()) }

    @Test
    fun `the app opens on Home, not Topology`() {
        assertEquals(Target("home"), C3Nav.start(nav, aliases))
        assertEquals("a saved id nothing owns falls back to Home", Target("home"), C3Nav.start(nav, aliases, "no-such-tab"))
    }

    @Test
    fun `the bottom nav order is Infra, Containers, Home, Ops, Observ`() {
        assertEquals(listOf("infra", "containers", "home", "ops", "observ"), nav.bottomNav)
        assertEquals(listOf("Infra", "Containers", "Home", "Ops", "Observ"), nav.bottomSections().map { it.label })
        assertNull("Configs is not on the island", nav.bottomNav.firstOrNull { it == "configs" })
        assertNotNull("but it is a declared section the top bar opens", nav.section("configs"))
    }

    @Test
    fun `the old ids keep working through ui_aliases`() {
        assertEquals("Topology became Containers", Target("containers"), C3Nav.resolve(nav, aliases, "topology"))
        assertEquals("Apps became Ops", Target("ops"), C3Nav.resolve(nav, aliases, "apps"))
        assertEquals(Target("containers", "all_table"), C3Nav.resolve(nav, aliases, "public"))
        assertEquals(Target("containers", "all_table"), C3Nav.resolve(nav, aliases, "private"))
        assertEquals("the old Stack id opens Infra's first page", Target("infra", "providers"), C3Nav.resolve(nav, aliases, "stack"))
        assertEquals("a restored old tab opens its new home", Target("ops"), C3Nav.start(nav, aliases, "apps"))
    }

    @Test
    fun `declared sections and pages resolve to themselves, unknown ids to nothing`() {
        assertEquals(Target("observ"), C3Nav.resolve(nav, aliases, "observ"))
        assertEquals(Target("observ", "health"), C3Nav.resolve(nav, aliases, "health"))
        assertEquals("the old Table id opens the All project's Table", Target("containers", "all_table"), C3Nav.resolve(nav, aliases, "table"))
        assertEquals(Target("containers", "cg_table"), C3Nav.resolve(nav, aliases, "cg_table"))
        assertEquals(Target("ops", "agents"), C3Nav.resolve(nav, aliases, "agents"))
        assertNull(C3Nav.resolve(nav, aliases, "nope"))
        assertNull(C3Nav.resolve(nav, aliases, ""))
        assertNull(C3Nav.resolve(nav, aliases, null))
    }

    @Test
    fun `every alias points at a declared section or page`() {
        for ((old, new) in aliases.sections) assertNotNull("alias $old -> $new", nav.section(new))
        for ((old, dest) in aliases.pages) {
            val s = nav.section(dest.substringBefore('/'))
            assertNotNull("alias $old -> $dest names no section", s)
            assertTrue("alias $old -> $dest names no page", s!!.allPages().any { it.id == dest.substringAfter('/') })
        }
    }

    @Test
    fun `the drawer tree lists every section with every page, in declared order`() {
        val rows = C3Nav.drawerRows(nav)
        assertEquals(nav.sections.map { it.id }, rows.filter { it.page == null }.map { it.section })
        for (s in nav.sections) {
            assertEquals("pages of ${s.id}", s.allPages().map { it.id }, rows.filter { it.section == s.id && it.page != null }.map { it.page })
        }
        assertTrue("Home, the All project's Table and Configs are all reachable from the drawer",
            rows.any { it.section == "home" } && rows.any { it.page == "all_table" } && rows.any { it.section == "configs" })
    }
}
