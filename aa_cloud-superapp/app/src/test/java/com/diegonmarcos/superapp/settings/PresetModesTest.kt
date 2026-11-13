package com.diegonmarcos.superapp.settings

import com.diegonmarcos.superapp.bgpolicy.SsState
import com.diegonmarcos.superapp.bgpolicy.SuperSaving
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1720 Presets modes: exactly one active, the previous mode restored before the next is applied, the upgrade repair of the
 * double state, the root cause of "Off: 0 steps done, 1 did not take", the restore journal, the per-step reasons, idempotent
 * apply, batched shell calls and capability detection. The switch and the engines are the production classes over [ModeRig].
 */
class PresetModesTest {

    // ── exactly one active mode ──
    @Test fun `whatever is picked, exactly one mode is active and the restore journal is that mode's`() {
        val r = ModeRig(); val s = r.switch()
        for (id in listOf("power", "focus", "saving_energy", "super_saving", "focus", "power", "super_saving", "saving_energy", "saving_energy")) {
            val o = s.select(id)
            assertEquals("after picking $id", id, r.sel)
            assertEquals(id, o.mode)
            val j = SuperSaving.journalMode(r.kv)
            assertTrue("the journal is $j while $id is the active mode", j == null || j == id)
            assertTrue("nothing is left pending when the shell is up: ${s.pending()}", s.pending().isEmpty())
        }
        s.off()
        assertNull(r.sel); assertFalse(SuperSaving.isActive(r.kv))
    }

    @Test fun `picking another mode runs the previous mode's Off first and restores it exactly`() {
        val r = ModeRig(); val s = r.switch(); val before = r.world()
        s.select("super_saving")
        assertTrue("Super Saving changed the phone", r.world() != before)
        val o = s.select("power")
        assertEquals("on", o.op)
        assertTrue("the previous mode was restored: ${o.restored}", o.restored.isNotEmpty() && o.restored.all { it.state == SsState.APPLIED })
        assertEquals("power", r.sel); assertEquals("power", SuperSaving.journalMode(r.kv))
        assertEquals("the saver Super Saving switched on is off again", "0", r.dev.settings["global/low_power"])
        s.off()
        assertEquals("with no mode on the phone is exactly as before any mode", before, r.world())
    }

    @Test fun `every mode restores exactly what it changed, on a Samsung and on plain Android`() {
        for (rig in listOf(ModeRig(), ModeRig().samsung())) for (id in ModeRig.ids) {
            val before = rig.world(); val s = rig.switch()
            s.select(id)
            assertTrue("$id changed nothing on ${rig.host.maker.ifEmpty { "plain Android" }}", rig.world() != before)
            val o = s.off()
            assertTrue("$id: ${o.message}", o.ok)
            assertEquals("$id on ${rig.host.maker.ifEmpty { "plain Android" }}", before, rig.world())
            assertNull(rig.sel)
        }
    }

    // ── the upgrade repair of the double state ──
    @Test fun `a store with Power picked and a Super Saving journal open is repaired to one active mode, and Off then works`() {
        val r = ModeRig(); r.sel = "power"
        // the journal as a phone holds it today: written before #1720 (no mode field), one entry that never captured a prior value
        r.kv.put(SuperSaving.K_SNAP, """{"steps":{"sync":{"state":"FAILED"}},"at":1}""")
        val s = r.switch()
        val note = s.migrate()
        assertNotNull("the double state was not noticed", note)
        assertEquals("the mode that holds the journal is the one really applied", "super_saving", r.sel)
        assertNull("a second repair has nothing to do", s.migrate())
        val o = s.off()
        assertTrue(o.message, o.ok)
        assertNull(r.sel); assertFalse(SuperSaving.isActive(r.kv))
        assertTrue(o.restored.single().note, o.restored.single().note.contains("nothing was changed"))
    }

    @Test fun `the other repairs - a journal-only mode with no journal, an undeclared pick, Focus from its old store`() {
        val a = ModeRig(); a.sel = "super_saving"
        assertNotNull(a.switch().migrate()); assertNull(a.sel)
        val b = ModeRig(); b.sel = "gone_mode"
        assertNotNull(b.switch().migrate()); assertNull(b.sel)
        val c = ModeRig()
        assertNotNull(c.switch().migrate(legacyFocus = true)); assertEquals("focus", c.sel)
        val d = ModeRig(); d.sel = "power"
        assertNull("a picked mode outranks the old Focus flag: one stays", d.switch().migrate(legacyFocus = true)); assertEquals("power", d.sel)
    }

    // ── root cause of "Off: 0 steps done, 0 skipped, 1 did not take" ──
    @Test fun `a step that throws before it captured anything is reported, not journaled, and never blocks Off`() {
        val r = ModeRig(); r.host.syncThrows = true; r.shell = null // the phone: no sync permission, ADB down
        val s = r.switch()
        val on = s.select("super_saving")
        val sync = on.results.first { it.id == "sync" }
        assertEquals(SsState.FAILED, sync.state)
        assertTrue("the reason is the real one: ${sync.note}", sync.note.contains("SYNC_SETTINGS"))
        assertEquals("the keeper step applied, so the mode is on", "super_saving", r.sel)
        assertFalse(r.host.keeperOn)
        val off = s.off()
        assertTrue(off.message, off.ok)
        assertTrue("Off did not retry a restore that has nothing to put back", off.restored.none { it.id == "sync" })
        assertNull(r.sel); assertTrue(r.host.keeperOn); assertFalse(SuperSaving.isActive(r.kv))
    }

    // ── reasons ──
    @Test fun `a step that does not take carries its reason to the outcome and to the stored report`() {
        val r = ModeRig(); r.dev.refuseSettings += "global/low_power"
        val o = r.switch().select("saving_energy")
        assertFalse(o.ok)
        val f = o.failures.single()
        assertEquals("battery_saver", f.id)
        assertTrue(f.note, f.note.contains("low_power reads 0, wanted 1"))
        assertTrue(o.message, o.message.contains("low_power reads 0, wanted 1"))
        val rep = ModeSwitch.report(r.kv)!!
        assertEquals("saving_energy", rep.mode); assertEquals("on", rep.op); assertFalse(rep.ok)
        assertTrue(rep.steps.any { it.id == "battery_saver" && it.state == SsState.FAILED && it.note.contains("low_power") })
        assertEquals("every step is in the report", ModeRig.stepsFor("saving_energy")!!.map { it.id }, rep.steps.map { it.id })
    }

    // ── the restore journal when the shell is down ──
    @Test fun `Off without the shell keeps the mode on and says why, a switch parks the rest, a retry restores it`() {
        val r = ModeRig(); val before = r.world(); val s = r.switch()
        s.select("saving_energy")
        r.shell = null
        val o = s.off()
        assertFalse(o.ok); assertEquals("the mode stays on: its journal still holds steps", "saving_energy", r.sel)
        assertTrue(o.message, o.message.contains("did not finish"))
        assertTrue(o.restored.filter { it.needsAdb }.all { it.state == SsState.SKIPPED && it.note.contains("ADB") })
        // picking Focus without the shell: single choice still holds, and what could not be restored is parked, not lost
        s.select("focus")
        assertEquals("focus", r.sel); assertEquals("focus", SuperSaving.journalMode(r.kv))
        assertEquals(listOf("saving_energy"), s.pending())
        assertTrue(s.pendingSteps("saving_energy").toString(), "battery_saver" in s.pendingSteps("saving_energy"))
        assertEquals("the saver is still on: its restore is pending", "1", r.dev.settings["global/low_power"])
        r.shell = r.dev // the shell is back
        val retry = s.retryPending("saving_energy")
        assertTrue(retry.message, retry.ok); assertTrue(s.pending().isEmpty())
        s.off()
        assertEquals(before, r.world())
    }

    @Test fun `a forced Off leaves the mode and parks what is left`() {
        val r = ModeRig(); val s = r.switch()
        s.select("saving_energy"); r.shell = null
        s.off(force = true)
        assertNull(r.sel); assertFalse(SuperSaving.isActive(r.kv)); assertEquals(listOf("saving_energy"), s.pending())
        s.forgetPending("saving_energy"); assertTrue(s.pending().isEmpty())
    }

    // ── idempotent apply, batched shell calls ──
    @Test fun `picking the active mode again writes nothing, costs one batched read, and repairs a drift without losing the first prior value`() {
        val r = ModeRig(); val s = r.switch()
        s.select("saving_energy")
        val w = r.world(); val n = r.dev.log.size
        val again = s.select("saving_energy")
        assertEquals("reapply", again.op); assertEquals(w, r.world()); assertEquals("saving_energy", r.sel)
        val cmds = r.dev.log.drop(n)
        assertTrue("an unchanged mode was written again: $cmds", cmds.none { it.contains("settings put") || it.contains("settings delete") })
        assertEquals("one batched read is the whole cost: $cmds", 1, cmds.size)
        r.dev.settings["global/low_power"] = "0" // something else switched the saver off
        s.select("saving_energy")
        assertEquals("1", r.dev.settings["global/low_power"])
        s.off()
        assertEquals("Off puts back the value from before the mode, not the drifted one", "0", r.dev.settings["global/low_power"])
    }

    @Test fun `shell calls are batched - one exec reads every setting, each changing step writes and verifies in one exec`() {
        for (id in ModeRig.ids) {
            val r = ModeRig().samsung(); val s = r.switch()
            s.select(id)
            val on = r.dev.log.toList()
            val steps = ModeRig.stepsFor(id)!!
            val reads = on.first { it.contains("settings get") }
            assertTrue("$id: the prior values are not read in one batch: $reads", reads.split(";").count { it.trim().startsWith("settings get") } >= 2)
            assertTrue("$id: every exec fits the broker's line cap", on.all { it.length <= 2048 })
            val settingSteps = steps.count { it.kind == "setting" || it.kind == "setting_cap" }
            val settingExecs = on.count { it.contains("settings put") }
            assertTrue("$id: $settingExecs writing execs for $settingSteps setting steps", settingExecs <= settingSteps)
            val n = r.dev.log.size
            s.off()
            val off = r.dev.log.drop(n)
            assertTrue("$id: Off wrote more execs than it has setting steps", off.count { it.contains("settings put") || it.contains("settings delete") } <= settingSteps)
        }
    }

    // ── capability detection ──
    @Test fun `a Samsung step is skipped with the reason elsewhere, the plain-Android fallback is skipped on a Samsung, a missing key is never invented`() {
        val plain = ModeRig()
        val o = plain.switch().select("saving_energy").results.associateBy { it.id }
        assertEquals(SsState.SKIPPED, o.getValue("refresh_rate_samsung").state)
        assertTrue(o.getValue("refresh_rate_samsung").note, o.getValue("refresh_rate_samsung").note.contains("Samsung"))
        assertEquals(SsState.APPLIED, o.getValue("refresh_rate").state)
        assertEquals("60", plain.dev.settings["system/peak_refresh_rate"])
        assertFalse(plain.dev.settings.containsKey("secure/refresh_rate_mode"))

        val galaxy = ModeRig().samsung()
        val g = galaxy.switch().select("saving_energy").results.associateBy { it.id }
        assertEquals(SsState.APPLIED, g.getValue("refresh_rate_samsung").state)
        assertEquals("0", galaxy.dev.settings["secure/refresh_rate_mode"])
        assertEquals(SsState.SKIPPED, g.getValue("refresh_rate").state)
        assertEquals("the plain-Android keys are left alone on a Samsung", "120", galaxy.dev.settings["system/peak_refresh_rate"])
        assertEquals("1", galaxy.switch().let { it.off(); it.select("power"); galaxy.dev.settings["global/sem_enhanced_cpu_responsiveness"] })

        val older = ModeRig(); older.host.maker = "samsung" // a One UI build without these keys
        val h = older.switch().select("saving_energy").results.associateBy { it.id }
        assertEquals(SsState.SKIPPED, h.getValue("processing_speed_samsung").state)
        assertTrue(h.getValue("processing_speed_samsung").note, h.getValue("processing_speed_samsung").note.contains("not present on this device"))
        assertFalse(older.dev.settings.containsKey("global/sem_enhanced_cpu_responsiveness"))
    }

    // ── the four bundles ──
    @Test fun `the four modes are declared as a ladder with distinct levers`() {
        assertEquals(listOf("power", "focus", "saving_energy", "super_saving"), ModeRig.ids)
        fun keys(id: String) = ModeRig.stepsFor(id)!!.flatMap { s -> s.p.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("key") + "=" + a.getJSONObject(it).getString("value") } }.orEmpty() }.toSet()
        assertTrue("Power switches the saver off", "low_power=0" in keys("power"))
        assertTrue("Saving Energy and Super Saving switch it on", "low_power=1" in keys("saving_energy") && "low_power=1" in keys("super_saving"))
        assertTrue("Focus pulls no power lever", keys("focus").none { it.startsWith("low_power") || it.contains("refresh_rate") })
        assertTrue("Focus is the one with Do Not Disturb", ModeRig.stepsFor("focus")!!.any { it.id == "dnd" } && ModeRig.ids.filter { it != "focus" }.none { m -> ModeRig.stepsFor(m)!!.any { it.id == "dnd" } })
        val heavy = setOf("bg_policy", "idle_screen_off", "nfc", "bluetooth", "mobile_data", "sync", "fleet_stop", "location")
        assertTrue("only Super Saving restricts apps and switches radios off",
            ModeRig.stepsFor("super_saving")!!.map { it.id }.containsAll(heavy) && ModeRig.ids.filter { it != "super_saving" }.none { m -> ModeRig.stepsFor(m)!!.any { it.id in heavy } })
        for (id in ModeRig.ids) for (s in ModeRig.stepsFor(id)!!) {
            assertTrue("$id/${s.id}: every step has a label", s.label.isNotBlank())
            assertTrue("$id/${s.id}: an unknown step kind cannot be restored", s.kind in setOf("launcher_toggles", "setting", "setting_cap", "shell_toggle", "bg_policy", "sync", "idle_screen_off", "keeper", "fleet_stop"))
        }
    }

    @Test fun `Focus sets Do Not Disturb and quiet switches, and Off puts both back`() {
        val r = ModeRig(); val s = r.switch(); val before = r.world()
        s.select("focus")
        assertEquals("1", r.dev.settings["global/zen_mode"]); assertEquals("0", r.dev.settings["global/heads_up_notifications_enabled"])
        assertEquals(false, r.host.toggles["pets_anim"])
        s.off()
        assertEquals(before, r.world())
    }
}
