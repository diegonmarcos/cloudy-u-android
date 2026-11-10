package com.diegonmarcos.superapp.perms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1600 the Shizuku and Termux rows exist on the Perms page, land in the Shell/ADB category, and get a shell grant command. */
class PermsShizukuTermuxTest {
    private val cat = PermCatalogue.parse(File("src/main/assets/perms-catalogue.json").readText())
    private val pkg = "com.diegonmarcos.superapp"

    private fun entry(id: String) = cat.byId(id) ?: error("no catalogue entry $id")

    @Test fun `shizuku and termux are declared with their real permissions`() {
        assertEquals("moe.shizuku.manager.permission.API_V23", entry("shizuku").permission)
        assertEquals("com.termux.permission.RUN_COMMAND", entry("termux_run_command").permission)
    }

    @Test fun `both land in the Shell category and offer a shell grant`() {
        for (id in listOf("shizuku", "termux_run_command")) {
            val e = entry(id)
            assertEquals(PermCategory.SHELL, PermsPage.category(e))
            val g = PermsShellGrant.commands(pkg, e)
            assertTrue("$id has no shell grant", g != null && g.contains("pm grant $pkg ${e.permission}"))
        }
    }

    @Test fun `they are extra rows, not replacements - every pre-existing id is still present`() {
        val ids = cat.entries.map { it.id }.toSet()
        assertTrue("shizuku" in ids && "termux_run_command" in ids)
        assertTrue("read_logs" in ids && "dump" in ids && "notifications" in ids)   // unchanged rows kept
    }
}
