package com.diegonmarcos.superapp.apps

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.launcher.AppLongPressMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1889 Quickmark Add/Remove: the toggle, the persistence, the label flip, the star predicate and the menu parity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class QuickmarksTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private fun e(p: String) = SuitePhoneAppsFragment.Entry(p, p)
    private val groups = listOf(
        SuitePhoneAppsFragment.Group("Social", listOf(e("a.curated")), listOf(SuitePhoneAppsFragment.Folder("Chat", listOf(e("a.inFolder"))))),
        SuitePhoneAppsFragment.Group("Tools", listOf(e("t.one")), emptyList()),
    )
    private val curated = Quickmarks.curatedPkgs(groups)
    private val none = Quickmarks.State()

    @Test fun `curated packages are the group tiles and the folder members`() {
        assertEquals(setOf("a.curated", "a.inFolder", "t.one"), curated)
    }

    @Test fun `add puts a plain app in Quickmarks and remove takes it out again`() {
        assertFalse(Quickmarks.isQuickmark(curated, none, "x.app"))
        val added = Quickmarks.toggle(curated, none, "x.app", "Social", "X")
        assertTrue(Quickmarks.isQuickmark(curated, added, "x.app"))
        assertEquals(Quickmarks.Add("Social", "X"), added.added["x.app"])
        val gone = Quickmarks.toggle(curated, added, "x.app", "Social", "X")
        assertFalse(Quickmarks.isQuickmark(curated, gone, "x.app"))
        assertEquals(none, gone)
    }

    @Test fun `a curated quickmark can be removed as an override and added back`() {
        assertTrue(Quickmarks.isQuickmark(curated, none, "a.curated"))
        val off = Quickmarks.toggle(curated, none, "a.curated", "Social", "A")
        assertFalse(Quickmarks.isQuickmark(curated, off, "a.curated"))
        assertEquals(setOf("a.curated"), off.removed)
        val on = Quickmarks.toggle(curated, off, "a.curated", "Social", "A")
        assertTrue(Quickmarks.isQuickmark(curated, on, "a.curated"))
        assertEquals(none, on)
    }

    @Test fun `the label flips with the state`() {
        assertEquals("Quickmark Add", Quickmarks.label(false))
        assertEquals("Quickmark Remove", Quickmarks.label(true))
    }

    @Test fun `the group follows the Phone folder, with Others as the fallback`() {
        assertEquals("Social", Quickmarks.groupFor("Social", groups))
        assertEquals("Social", Quickmarks.groupFor("chat", groups))
        assertEquals("Tools", Quickmarks.groupFor(" TOOLS ", groups))
        assertEquals("Others", Quickmarks.groupFor("Games", groups))
        assertEquals("Others", Quickmarks.groupFor(null, groups))
    }

    @Test fun `apply lays the state over the declarations without editing them`() {
        val st = Quickmarks.State(mapOf("x.app" to Quickmarks.Add("Social", "X"), "y.app" to Quickmarks.Add("Others", "Y")), setOf("t.one", "a.inFolder"))
        val out = Quickmarks.apply(groups, st)
        assertEquals(listOf("a.curated", "x.app"), out[0].entries.map { it.pkg })
        assertEquals(emptyList<String>(), out[0].folders[0].entries.map { it.pkg })
        assertEquals(emptyList<String>(), out[1].entries.map { it.pkg })
        assertEquals("Others", out[2].title)
        assertEquals(listOf("y.app"), out[2].entries.map { it.pkg })
        assertEquals(listOf("a.curated"), groups[0].entries.map { it.pkg })   // the declarations are untouched
        assertEquals(groups, Quickmarks.apply(groups, none))
    }

    @Test fun `the state persists across a reload`() {
        val st = Quickmarks.State(mapOf("x.app" to Quickmarks.Add("Social", "X")), setOf("a.curated"))
        assertEquals(st, Quickmarks.parse(Quickmarks.serialize(st)))
        assertTrue(Quickmarks.save(ctx, st))
        assertEquals(st, Quickmarks.load(ctx))
        assertEquals(none, Quickmarks.parse("not json"))
        Quickmarks.save(ctx, none)
    }

    @Test fun `the star predicate reads the stored state`() {
        Quickmarks.save(ctx, Quickmarks.State(mapOf("x.app" to Quickmarks.Add("Others", "X"))))
        assertTrue(Quickmarks.isQuickmark(ctx, "x.app"))
        assertFalse(Quickmarks.isQuickmark(ctx, "never.marked"))
        Quickmarks.save(ctx, none)
    }

    @Test fun `the long-press menu keeps every row and gains one Quickmark row that flips`() {
        Quickmarks.save(ctx, none)
        fun rows() = AppLongPressMenu.sectionsFor(ctx, ctx.packageName, "App") {}.filter { it.title != "Actions" }
        val before = rows()
        assertEquals(listOf("Configs", "URLs"), before.map { it.title })
        val configs = before[0].rows.map { it.label }
        for (kept in listOf("App info", "Uninstall", "Update")) assertTrue("$kept stays", kept in configs)
        assertEquals(1, configs.count { it.startsWith("Quickmark ") })
        assertTrue("Quickmark Add" in configs)
        Quickmarks.save(ctx, Quickmarks.State(mapOf(ctx.packageName to Quickmarks.Add("Others", "App"))))
        val after = rows()[0].rows.map { it.label }
        assertTrue("Quickmark Remove" in after)
        assertFalse("Quickmark Add" in after)
        assertEquals(configs.size, after.size)
        Quickmarks.save(ctx, none)
    }
}
