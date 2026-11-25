package com.diegonmarcos.cloudme

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Data migration of the navigation: every id of the retired Work / House / Profile > Tasks / old Buro layout lands on a declared page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LegacyRoutesTest {
    init { BuroLlcTestData.installRenamed() }
    private val declared: Map<String, Set<String>> = run {
        val secs = JSONObject(BuroLlcTestData.text("build.json")).getJSONObject("ui").getJSONArray("sections")
        (0 until secs.length()).map { secs.getJSONObject(it) }.associate { s ->
            s.getString("id") to (s.optJSONArray("pages")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } } ?: emptyList()).toSet()
        }
    }

    @Test fun `Work became LLC and its Endeavours became Side hustles`() {
        assertEquals("llc" to "employed", LegacyRoutes.resolve("work", "employed"))
        assertEquals("llc" to "side-hustles", LegacyRoutes.resolve("work", "endeavours"))
        assertEquals("llc" to "employed", LegacyRoutes.resolve("work", null))
    }

    @Test fun `House is gone and lands on Buro Docs Assets`() {
        assertEquals("buro" to "assets", LegacyRoutes.resolve("house", "manage"))
        assertEquals("buro" to "assets", LegacyRoutes.resolve("house", null))
        assertEquals("buro" to "assets", LegacyRoutes.resolve("house", "anything-else"))
    }

    @Test fun `Tasks moved from Profile to Tasks Backlogs and the old Buro pages map to the new groups`() {
        assertEquals("tasks" to "backlogs", LegacyRoutes.resolve("me", "tasks"))
        assertEquals("buro" to "ids", LegacyRoutes.resolve("buro", "summary"))
        assertEquals("buro" to "assets", LegacyRoutes.resolve("buro", "docs"))
        assertEquals("buro" to "cashflow", LegacyRoutes.resolve("buro", "acct"))
        assertEquals("buro" to "budgeting", LegacyRoutes.resolve("buro", "budget"))
        assertEquals("llc" to "portfolio", LegacyRoutes.resolve("buro", "portfolio"))
    }

    @Test fun `#1367 RoadMap and Duties land on their Tasks subpages, every page of either`() {
        assertEquals("tasks" to "roadmaps", LegacyRoutes.resolve("roadmap", null))
        assertEquals("tasks" to "roadmaps", LegacyRoutes.resolve("roadmap", "folders"))
        assertEquals("tasks" to "roadmaps", LegacyRoutes.resolve("roadmap", "anything-else"))
        assertEquals("tasks" to "backlogs", LegacyRoutes.resolve("duties", null))
        assertEquals("tasks" to "backlogs", LegacyRoutes.resolve("duties", "tasks"))
        assertEquals("tasks" to "backlogs", LegacyRoutes.resolve("duties", "anything-else"))
        assertEquals("inventory" to "food", LegacyRoutes.resolve("inventory", "food"))
    }

    @Test fun `current ids pass through untouched`() {
        for ((s, pages) in declared) for (p in pages) assertEquals("$s/$p", s to p, LegacyRoutes.resolve(s, p))
        assertEquals("pers" to null, LegacyRoutes.resolve("health", null))   // #1901 Health is Pers
    }

    @Test fun `every destination of the table is a declared page`() {
        val old = listOf("work" to "employed", "work" to "endeavours", "work" to null, "house" to "manage", "house" to null, "me" to "tasks", "me" to "lnktree",
            "buro" to "summary", "buro" to "docs", "buro" to "fin", "buro" to "acct", "buro" to "budget", "buro" to "portfolio")
        for ((s, p) in old) {
            val (ns, np) = LegacyRoutes.resolve(s, p)
            assertTrue("$s/$p -> $ns/$np: section", ns in declared)
            assertTrue("$s/$p -> $ns/$np: page", np == null || np in declared.getValue(ns))
        }
    }
}
