package com.diegonmarcos.superapp.bgpolicy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1506 Super Saving Battery Mode: the declared order, the snapshot/restore round trip, the allowlist, the ADB-missing degradation, the audit. */
class SuperSavingTest {

    private val decl = File("src/main/assets/super-saving.json").readText()
    private val steps = SsDecl.parse(decl)
    private val cfg = BgFakeDevice.cfg()

    private class FakeHost(val pkgs: List<BgPkg>) : SsHost {
        var syncOn = true; var idleOn = false; var keeperOn = true
        override fun masterSync() = syncOn
        override fun setMasterSync(on: Boolean) { syncOn = on }
        override fun idleArmed() = idleOn
        override fun armIdle(on: Boolean) { idleOn = on }
        override fun keeperEnabled() = keeperOn
        override fun setKeeper(on: Boolean) { keeperOn = on }
        override fun packages() = pkgs
    }

    private class Rig(val dev: BgFakeDevice, val host: FakeHost, val kv: BgKv, val audit: MutableList<String>, val essential: Set<String>)

    private fun rig(vararg apps: String, essential: Set<String> = setOf("com.diegonmarcos.superapp")): Rig {
        val dev = BgFakeDevice(*apps)
        return Rig(dev, FakeHost(apps.map { BgPkg(it, it, false) }), BgFakeDevice.kv(), ArrayList(), essential)
    }

    private fun mode(r: Rig, shell: BgShell? = r.dev): SuperSaving {
        val bg = BgEngine(shell, BgEssential(cfg, r.essential), BgStore(r.kv), { r.audit += it })
        return SuperSaving(steps, shell, r.host, bg, cfg, r.kv, { r.audit += it })
    }

    /** Everything the mode can touch, as plain data, to compare before and after. */
    private fun world(r: Rig) = listOf(
        r.dev.settings.toSortedMap().toString(), r.dev.nfc, r.dev.night, r.dev.idleForced,
        r.dev.state.mapValues { listOf(it.value.any, it.value.inn, it.value.bucket, it.value.idle) }.toString(),
        r.host.syncOn, r.host.idleOn, r.host.keeperOn,
    )

    private fun seed(r: Rig) {
        r.dev.settings.putAll(mapOf(
            "global/low_power" to "0", "global/wifi_scan_always_enabled" to "1", "global/ble_scan_always_enabled" to "1", "secure/location_mode" to "3",
            "global/bluetooth_on" to "1", "global/mobile_data" to "1", "global/preferred_network_mode" to "33",
            "system/peak_refresh_rate" to "120", "system/min_refresh_rate" to "120", "system/screen_brightness_mode" to "0",
            "system/screen_brightness" to "200", "system/screen_off_timeout" to "60000", "secure/doze_always_on" to "1",
            "global/window_animation_scale" to "1", "global/transition_animation_scale" to "1",
            // adaptive battery, animator scale, auto-brightness adj and aod_mode are deliberately UNSET: Off must delete them again
        ))
        r.dev.nfc = "on"; r.dev.night = "auto"
    }

    // ── the declaration ──
    @Test fun `the steps are declared in the asked order and each one says whether it needs adb`() {
        val ids = steps.map { it.id }
        val need = listOf("bg_policy", "battery_saver", "adaptive_battery", "idle_screen_off", "wifi_scan", "ble_scan", "location", "nfc", "bluetooth", "mobile_data",
            "network_type", "sync", "refresh_rate", "brightness_auto", "brightness_cap", "screen_timeout", "dark_mode", "aod_doze", "animations", "keeper", "fleet_stop")
        for (n in need) assertTrue("step $n declared", n in ids)
        assertEquals("order", need, ids.filter { it in need })
        assertEquals("the background policy leads", "bg_policy", ids.first())
        assertEquals(false, steps.first { it.id == "sync" }.adb); assertEquals(true, steps.first { it.id == "nfc" }.adb)
        assertTrue("every step has a label", steps.all { it.label.isNotBlank() })
        val keys = steps.flatMap { s -> s.p.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("key") } }.orEmpty() }
        for (k in listOf("low_power", "wifi_scan_always_enabled", "ble_scan_always_enabled", "peak_refresh_rate", "min_refresh_rate", "screen_off_timeout",
            "window_animation_scale", "transition_animation_scale", "animator_duration_scale")) assertTrue("setting $k", k in keys)
    }

    @Test fun `the mode sits right after Saving Energy in the Presets modes`() {
        val modes = JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONArray("launcher_modes")
        val ids = (0 until modes.length()).map { modes.getJSONObject(it).getString("id") }
        assertEquals(ids.indexOf("saving_energy") + 1, ids.indexOf("super_saving"))
        assertEquals("super_saving", modes.getJSONObject(ids.indexOf("super_saving")).getString("engine"))
    }

    // ── on ──
    @Test fun `on applies every step and writes one audit entry per step`() {
        val r = rig("com.other.app", "com.diegonmarcos.cloudchat", "com.diegonmarcos.superapp"); seed(r)
        val out = mode(r).on()
        val byId = out.associateBy { it.id }
        for (s in steps) assertNotNull("result for ${s.id}", byId[s.id])
        assertEquals("1", r.dev.settings["global/low_power"]); assertEquals("0", r.dev.settings["global/wifi_scan_always_enabled"]); assertEquals("0", r.dev.settings["global/ble_scan_always_enabled"])
        assertEquals("60", r.dev.settings["system/peak_refresh_rate"]); assertEquals("60", r.dev.settings["system/min_refresh_rate"])
        assertEquals("15000", r.dev.settings["system/screen_off_timeout"]); assertEquals("1", r.dev.settings["system/screen_brightness_mode"])
        assertEquals("0", r.dev.settings["global/animator_duration_scale"]); assertEquals("0", r.dev.settings["global/window_animation_scale"])
        assertEquals("off", r.dev.nfc); assertEquals("yes", r.dev.night); assertEquals("0", r.dev.settings["global/bluetooth_on"]); assertEquals("0", r.dev.settings["global/mobile_data"])
        assertEquals("80", r.dev.settings["system/screen_brightness"]); assertFalse(r.host.syncOn); assertTrue(r.host.idleOn)
        assertEquals("ignore", r.dev.state["com.other.app"]!!.any); assertEquals("ignore", r.dev.state["com.diegonmarcos.cloudchat"]!!.any)
        assertEquals("the SuperApp itself is never restricted", "default", r.dev.state["com.diegonmarcos.superapp"]!!.any)
        for (s in steps) assertTrue("audit entry for ${s.id}", r.audit.any { it.startsWith("super-saving ${s.id}:") })
        assertTrue(SuperSaving.isActive(r.kv))
    }

    @Test fun `on twice does nothing the second time`() {
        val r = rig("com.a"); seed(r); val m = mode(r)
        m.on(); val before = world(r)
        val again = m.on()
        assertEquals(SsState.SKIPPED, again.single().state); assertEquals(before, world(r))
    }

    // ── the snapshot / restore round trip ──
    @Test fun `off puts back every step exactly, including keys that were unset and apps that were already tuned`() {
        val r = rig("com.a", "com.b", "com.diegonmarcos.cloudchat"); seed(r)
        r.dev.state["com.a"]!!.apply { any = "allow"; inn = "allow"; bucket = Bucket.FREQUENT; idle = true }
        val before = world(r)
        val m = mode(r)
        m.on()
        assertTrue("something changed", world(r) != before)
        val out = m.off()
        assertTrue(out.joinToString { "${it.id}:${it.state}:${it.note}" }, out.all { it.state == SsState.APPLIED })
        assertEquals("the world is exactly as it was", before, world(r))
        assertFalse(SuperSaving.isActive(r.kv))
        assertTrue("off twice is a no-op", m.off().isEmpty())
    }

    @Test fun `off runs the steps in reverse order`() {
        val r = rig("com.a"); seed(r); val m = mode(r); m.on()
        val ids = m.off().map { it.id }
        assertEquals(steps.map { it.id }.filter { it in ids }.reversed(), ids)
    }

    @Test fun `an app the owner had already restricted stays restricted after off`() {
        val r = rig("com.a", "com.b"); seed(r)
        val bg = BgEngine(r.dev, BgEssential(cfg, r.essential), BgStore(r.kv))
        assertTrue(bg.restrict("com.a").ok)
        val m = mode(r); m.on(); m.off()
        assertEquals("ignore", r.dev.state["com.a"]!!.any); assertEquals("default", r.dev.state["com.b"]!!.any)
    }

    @Test fun `a brightness cap lowers a high value and never raises a low one`() {
        val hi = rig("com.a"); seed(hi); hi.dev.settings["system/screen_brightness"] = "200"
        val m = mode(hi); m.on(); assertEquals("80", hi.dev.settings["system/screen_brightness"]); m.off(); assertEquals("200", hi.dev.settings["system/screen_brightness"])
        val lo = rig("com.a"); seed(lo); lo.dev.settings["system/screen_brightness"] = "40"
        mode(lo).on(); assertEquals("40", lo.dev.settings["system/screen_brightness"])
    }

    // ── conditions and graceful skips ──
    @Test fun `bluetooth stays on while a device is connected, mobile data stays on off Wi-Fi, an unreadable NFC is skipped`() {
        val r = rig("com.a"); seed(r); r.dev.btConnected = true; r.dev.wifiConnected = false; r.dev.nfc = null
        val out = mode(r).on().associateBy { it.id }
        assertEquals(SsState.SKIPPED, out["bluetooth"]!!.state); assertTrue(out["bluetooth"]!!.note.contains("connected"))
        assertEquals("1", r.dev.settings["global/bluetooth_on"])
        assertEquals(SsState.SKIPPED, out["mobile_data"]!!.state); assertEquals("1", r.dev.settings["global/mobile_data"])
        assertEquals(SsState.SKIPPED, out["nfc"]!!.state)
    }

    @Test fun `a setting this device does not have is skipped, not invented`() {
        val r = rig("com.a"); seed(r); r.dev.settings.remove("global/preferred_network_mode")
        val out = mode(r).on().associateBy { it.id }
        assertEquals(SsState.SKIPPED, out["network_type"]!!.state); assertFalse(r.dev.settings.containsKey("global/preferred_network_mode"))
        assertFalse(r.dev.settings.containsKey("system/aod_mode"))
    }

    @Test fun `a write the device ignores is reported as failed and off still puts the rest back`() {
        val r = rig("com.a"); seed(r); r.dev.refuseSettings += "global/low_power"
        val before = world(r)
        val m = mode(r); val out = m.on().associateBy { it.id }
        assertEquals(SsState.FAILED, out["battery_saver"]!!.state)
        m.off(); assertEquals(before, world(r))
    }

    // ── ADB missing ──
    @Test fun `without adb every adb step is skipped with a note and the non-adb steps still apply and restore`() {
        val r = rig("com.a"); seed(r); val before = world(r)
        val m = mode(r, shell = null)
        val out = m.on()
        for (s in steps.filter { it.adb }) {
            val x = out.first { it.id == s.id }
            assertEquals("${s.id} skipped", SsState.SKIPPED, x.state); assertEquals(BgOutcome.NO_ADB, x.note); assertTrue(x.needsAdb)
        }
        assertEquals(SsState.APPLIED, out.first { it.id == "sync" }.state); assertFalse(r.host.syncOn)
        assertEquals("the keeper is stopped only when no adb step needs Off", SsState.APPLIED, out.first { it.id == "keeper" }.state); assertFalse(r.host.keeperOn)
        assertTrue("the shell was never used", r.dev.log.isEmpty())
        m.off()
        assertEquals(before, world(r))
    }

    @Test fun `with adb the keeper is kept because off needs the shell`() {
        val r = rig("com.a"); seed(r)
        val out = mode(r).on().first { it.id == "keeper" }
        assertEquals(SsState.SKIPPED, out.state); assertTrue(out.note.contains("Off needs")); assertTrue(r.host.keeperOn)
    }

    @Test fun `off without the shell restores what it can and keeps the adb steps for the next try`() {
        val r = rig("com.a"); seed(r)
        mode(r).on()
        val out = mode(r, shell = null).off()
        assertTrue(out.filter { it.needsAdb }.all { it.state == SsState.SKIPPED })
        assertTrue(r.host.syncOn); assertTrue("still on: adb steps await", SuperSaving.isActive(r.kv))
        val before = world(r)
        mode(r).off() // the shell is back
        assertFalse(SuperSaving.isActive(r.kv)); assertTrue(world(r) != before)
        assertEquals("1", r.dev.settings["global/wifi_scan_always_enabled"])
    }

    // ── fleet ──
    @Test fun `the fleet engines are stopped and an essential one is never stopped`() {
        val r = rig("com.diegonmarcos.cloudwebserver", "cld.termux", "com.diegonmarcos.cloudc3", "com.other.app"); seed(r)
        mode(r).on()
        assertFalse(r.dev.state["com.diegonmarcos.cloudwebserver"]!!.running); assertFalse(r.dev.state["cld.termux"]!!.running)
        assertTrue("not in the fleet-stop list", r.dev.state["com.other.app"]!!.running)
        val r2 = rig("com.diegonmarcos.cloudwebserver", essential = setOf("com.diegonmarcos.cloudwebserver")); seed(r2)
        mode(r2).on(); assertTrue(r2.dev.state["com.diegonmarcos.cloudwebserver"]!!.running)
    }

    @Test fun `no step command is built from anything but declared names`() {
        val bad = runCatching { BgCmd.settingPut("global", "x; reboot", "1") }
        assertTrue(bad.isFailure); assertTrue(runCatching { BgCmd.settingGet("vendor", "k") }.isFailure)
    }
}
