package com.diegonmarcos.cloudme

import android.app.Application
import android.content.pm.PackageManager
import com.diegonmarcos.superapp.perms.PermState
import com.diegonmarcos.superapp.perms.Perms
import com.diegonmarcos.superapp.perms.PermAction
import com.diegonmarcos.superapp.perms.PermItem
import com.diegonmarcos.superapp.perms.PermItemKind
import com.diegonmarcos.superapp.perms.PermsView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Configs > Perms: the baked declaration, the manifest, and the start-up / Grant all walks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MePermsTest {

    private fun rt(id: String, s: PermState) = PermRow(id, id, "", RowKind.RUNTIME, "android.permission.$id", s)
    private fun sp(id: String, s: PermState) = PermRow(id, id, "", RowKind.SPECIAL, "", s)
    private fun hc(p: String, s: PermState) = PermRow(p, p, "", RowKind.HEALTH, "android.permission.health.$p", s)
    private val fresh = AskMemory(0, emptySet())

    @Test fun `every declared permission is in the merged manifest and every entry is a catalogue row`() {
        val ctx = RuntimeEnvironment.getApplication()
        val manifest = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        val d = MePermDecl.baked
        assertTrue("nothing declared", d.entries.isNotEmpty() && d.healthRead.isNotEmpty() && d.healthWrite.isNotEmpty())
        Perms.testCatalogue = java.io.File("../../fleet-private-data/perms-catalogue.json").readText()
        val cat = Perms.catalogue(ctx)
        for ((id, _) in d.entries) {
            val e = cat.byId(id); assertTrue("$id is not a libs:perms row", e != null)
            if (e!!.permission.isNotEmpty()) assertTrue("${e.permission} not in the manifest", e.permission in manifest)
        }
        for (p in d.health + d.install.map { it.perm }) assertTrue("$p not in the manifest", p in manifest)
    }

    @Test fun `rows list every declared permission once, health rows not applicable without Health Connect`() {
        val ctx = RuntimeEnvironment.getApplication()
        val d = MePermDecl.baked
        val rows = MePerms.rows(ctx, null, healthAvailable = false, healthGranted = emptySet())
        assertEquals(d.entries.size + d.health.size + d.install.size, rows.size)
        assertTrue(rows.filter { it.kind == RowKind.HEALTH }.all { it.state == PermState.NOT_APPLICABLE })
    }

    @Test fun `start-up asks denied runtime ones in one batch, never a permanently denied one`() {
        val steps = MePermPlan.startup(listOf(rt("A", PermState.DENIED), rt("B", PermState.PERMANENTLY_DENIED), rt("C", PermState.DENIED), rt("D", PermState.GRANTED)), fresh)
        assertEquals(listOf(PermStep.Runtime(listOf("android.permission.A", "android.permission.C"))), steps)
    }

    @Test fun `start-up asks Health Connect until it was declined twice`() {
        val rows = listOf(hc("READ_STEPS", PermState.DENIED), hc("WRITE_STEPS", PermState.GRANTED))
        assertEquals(listOf(PermStep.Health(setOf("android.permission.health.READ_STEPS"))), MePermPlan.startup(rows, AskMemory(1, emptySet())))
        assertTrue(MePermPlan.startup(rows, AskMemory(MePermPlan.HEALTH_MAX_ASKS, emptySet())).isEmpty())
    }

    @Test fun `start-up opens a special page once, never again`() {
        val rows = listOf(sp("battery_opt", PermState.DENIED), sp("overlay", PermState.GRANTED), sp("samsung", PermState.UNKNOWN))
        assertEquals(listOf(PermStep.Settings("battery_opt")), MePermPlan.startup(rows, fresh))
        assertTrue(MePermPlan.startup(rows, AskMemory(0, setOf("battery_opt"))).isEmpty())
    }

    @Test fun `nothing missing asks nothing`() {
        assertTrue(MePermPlan.startup(listOf(rt("A", PermState.GRANTED), hc("READ_STEPS", PermState.GRANTED), sp("x", PermState.NOT_APPLICABLE)), fresh).isEmpty())
    }

    @Test fun `grant all walks every missing one, whatever was asked before`() {
        val rows = listOf(rt("A", PermState.DENIED), rt("B", PermState.PERMANENTLY_DENIED), hc("READ_SLEEP", PermState.DENIED),
            sp("battery_opt", PermState.DENIED), sp("overlay", PermState.GRANTED), sp("x", PermState.NOT_APPLICABLE))
        assertEquals(listOf(
            PermStep.Runtime(listOf("android.permission.A")),
            PermStep.Health(setOf("android.permission.health.READ_SLEEP")),
            PermStep.Settings("B"),
            PermStep.Settings("battery_opt"),
        ), MePermPlan.grantAll(rows))
    }

    @Test fun `a row button opens the dialog, a settings page, Health Connect, or nothing`() {
        val h1 = hc("READ_STEPS", PermState.DENIED); val h2 = hc("READ_SLEEP", PermState.DENIED)
        val all = listOf(h1, h2)
        assertEquals(PermStep.Runtime(listOf("android.permission.A")), MePermPlan.forRow(rt("A", PermState.DENIED), all))
        assertEquals(PermStep.Settings("A"), MePermPlan.forRow(rt("A", PermState.PERMANENTLY_DENIED), all))
        assertEquals(PermStep.Health(setOf(h1.permission, h2.permission)), MePermPlan.forRow(h1, all))
        assertNull(MePermPlan.forRow(PermRow("i", "i", "", RowKind.INSTALL, "p", PermState.GRANTED), all))
        assertNull(MePermPlan.forRow(sp("x", PermState.NOT_APPLICABLE), all))
    }

    @Test fun `the banner names missing runtime ones and counts Health Connect`() {
        assertEquals(listOf("A", "Health Connect (2)"), MePermPlan.banner(listOf(rt("A", PermState.DENIED), hc("R1", PermState.DENIED), hc("R2", PermState.PERMANENTLY_DENIED), sp("s", PermState.DENIED))))
        assertEquals("Read active calories burned", MePermPlan.healthLabel("android.permission.health.READ_ACTIVE_CALORIES_BURNED"))
    }

    // ── the page: Cloud Me's rows through libs:perms PermsView (#1372; the view rules themselves are PermsViewTest) ──

    @Test fun `rows become shared items with Cloud Me's kind, reason, icon and used flag`() {
        val r = PermRow("a", "A", "why", RowKind.RUNTIME, "p", PermState.DENIED, "detail", "bolt", used = false)
        val i = MePermView.items(listOf(r)).single()
        assertEquals(PermItem("a", "A", "why", "bolt", PermItemKind.RUNTIME, PermState.DENIED, "detail", PermAction.GRANT, used = false), i)
        assertEquals(listOf(PermItemKind.RUNTIME, PermItemKind.SPECIAL, PermItemKind.HEALTH, PermItemKind.INSTALL), RowKind.values().map { MePermView.kind(it) })
    }

    @Test fun `a row's action is the step it would run, none when nothing is missing`() {
        val rows = listOf(rt("A", PermState.DENIED), rt("P", PermState.PERMANENTLY_DENIED), sp("s", PermState.DENIED), sp("g", PermState.GRANTED),
            sp("u", PermState.UNKNOWN), hc("R1", PermState.DENIED), PermRow("i", "i", "", RowKind.INSTALL, "p", PermState.GRANTED))
        assertEquals(listOf(PermAction.GRANT, PermAction.OPEN, PermAction.OPEN, null, PermAction.OPEN, PermAction.GRANT, null),
            MePermView.items(rows).map { it.action })
    }

    @Test fun `the shared page counts Cloud Me's rows as before, Health Connect once`() {
        val rows = listOf(rt("A", PermState.GRANTED), rt("B", PermState.DENIED), sp("s", PermState.GRANTED), sp("n", PermState.NOT_APPLICABLE),
            sp("u", PermState.UNKNOWN), hc("R1", PermState.GRANTED), hc("R2", PermState.DENIED), hc("R3", PermState.DENIED),
            PermRow("i", "i", "", RowKind.INSTALL, "p", PermState.GRANTED))
        assertEquals(PermsView.Progress(2, 4), PermsView.progress(MePermView.items(rows)))
        val unused = rt("U", PermState.DENIED).copy(used = false)
        val g = PermsView.groups(MePermView.items(listOf(rt("A", PermState.DENIED), unused, sp("s", PermState.GRANTED), hc("R1", PermState.DENIED),
            PermRow("i", "i", "", RowKind.INSTALL, "p", PermState.GRANTED))))
        assertEquals(listOf("Runtime", "Special access", "Health Connect", "Granted at install", PermsView.NOT_USED), g.map { it.title })
        assertTrue(g.last().collapsed)
    }

    @Test fun `every declared row has a fleet icon and the unused ones are flagged`() {
        val d = MePermDecl.baked
        for (e in d.entries) assertTrue("${e.id} has no libs:ui-icons icon", com.diegonmarcos.superapp.uiicons.UiIcons.has(e.icon))
        for (i in d.install) assertTrue("${i.perm} has no libs:ui-icons icon", com.diegonmarcos.superapp.uiicons.UiIcons.has(i.icon))
        assertTrue(com.diegonmarcos.superapp.uiicons.UiIcons.has(d.healthIcon))
        assertTrue("nothing flagged unused", d.entries.any { !it.used }); assertTrue(d.entries.any { it.used })
    }
}
