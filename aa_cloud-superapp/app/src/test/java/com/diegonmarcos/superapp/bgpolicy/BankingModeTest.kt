package com.diegonmarcos.superapp.bgpolicy

import com.diegonmarcos.superapp.system.BankingMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1847 Banking Apps Mode: the engine round trip over a fake phone (snapshot, only-what-changed restore, reverse order, per-item
 * verdicts), the declaration, the bank decision, and the pairing gate (nothing here can reach a connect or pair path).
 */
class BankingModeTest {
    init { com.diegonmarcos.superapp.apps.FleetPrivateFixtures.install() }   // #1566 fleet-private datasets are materialised by CI, not APK assets
    private val entry: JSONObject = JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONArray("launcher_modes").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id") == "banking" }
    }
    private val steps = SsDecl.parseArray(entry.getJSONArray("steps"))

    /** A phone: item -> on; items in [needsOwner] refuse a programmatic write (deep link), items in [stuck] ignore a write. */
    private class Phone(val state: MutableMap<String, Boolean>, val needsOwner: Set<String> = emptySet(), val stuck: Set<String> = emptySet()) : SsHost {
        val calls = ArrayList<String>()
        var keeperOn = true
        var a11y = mutableListOf("com.diegonmarcos.superapp/.A", "com.diegonmarcos.cloudkeyboard/.G")
        override fun masterSync() = true
        override fun setMasterSync(on: Boolean) {}
        override fun idleArmed() = false
        override fun armIdle(on: Boolean) {}
        override fun keeperEnabled() = keeperOn
        override fun setKeeper(on: Boolean) { calls += "keeper=$on"; keeperOn = on }
        override fun packages() = emptyList<BgPkg>()
        override fun deviceRead(item: String): DeviceRead =
            if (item == "a11y_fleet") DeviceRead(a11y.isNotEmpty(), "bound ${a11y.size}", a11y.joinToString(","))
            else if (item == "adb_shell") DeviceRead(state["usb_debugging"] == true || state["wireless_debugging"] == true, "the server rides on debugging")
            else DeviceRead(state[item], "fake $item")
        override fun deviceSet(item: String, on: Boolean, captured: String): DeviceWrite {
            calls += "$item=$on"
            if (item in needsOwner) return DeviceWrite(false, "no channel", "android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
            if (item == "a11y_fleet") { a11y = if (on) captured.split(',').toMutableList() else mutableListOf(); return DeviceWrite(true, "") }
            if (item == "adb_shell") return if (on) DeviceWrite(true, "keep-alive", verify = false) else DeviceWrite(false, "closes with debugging")
            if (item !in stuck) state[item] = on
            return DeviceWrite(true, "wrote $item")
        }
    }

    private fun engine(p: Phone, kv: BgKv = BgFakeDevice.kv()): Pair<SuperSaving, BgKv> {
        val cfg = BgFakeDevice.cfg()
        val bg = BgEngine(null, BgEssential(cfg, emptySet()), BgStore(kv), {})
        return SuperSaving(steps, null, p, bg, cfg, kv, {}, modeId = "banking", label = "Banking Apps Mode", tag = "mode-banking") to kv
    }

    private fun allOn() = mutableMapOf("wireless_debugging" to true, "usb_debugging" to true, "adb_shell" to true, "developer_options" to true,
        "edge_menus" to true, "floating_nav" to true, "vpn" to false)

    @Test fun `on switches everything off and off restores exactly the snapshot, in reverse order`() {
        val p = Phone(allOn()); val before = p.state.toMap(); val a11yBefore = p.a11y.toList()
        val (e, _) = engine(p)
        val on = e.on()
        assertTrue(on.joinToString { "${it.id}:${it.state}:${it.note}" }, on.none { it.state == SsState.FAILED })
        assertFalse(p.keeperOn); assertTrue(p.a11y.isEmpty())
        for (k in listOf("wireless_debugging", "usb_debugging", "developer_options", "edge_menus", "floating_nav")) assertEquals(k, false, p.state[k])
        val off = e.off()
        assertTrue(off.joinToString { "${it.id}:${it.state}:${it.note}" }, off.all { it.state == SsState.APPLIED })
        assertTrue(p.keeperOn)
        assertEquals(a11yBefore, p.a11y)
        for ((k, v) in before) if (k != "adb_shell") assertEquals("restored $k", v, p.state[k])
        assertFalse("nothing left to restore", e.active)
        // the keep-alive goes off FIRST and comes back LAST; developer options go off after debugging and come back before it
        assertEquals("keeper=false", p.calls.first())
        assertEquals("keeper=true", p.calls.last())
        assertTrue(p.calls.indexOf("wireless_debugging=false") < p.calls.indexOf("developer_options=false"))
        assertTrue(p.calls.indexOf("developer_options=true") < p.calls.indexOf("wireless_debugging=true"))
    }

    @Test fun `only what the mode changed is restored`() {
        val s = allOn().also { it["usb_debugging"] = false }
        val p = Phone(s); val (e, _) = engine(p)
        val on = e.on()
        assertEquals("already off", on.first { it.id == "usb_debugging_off" }.note)
        assertEquals(SsState.SKIPPED, on.first { it.id == "usb_debugging_off" }.state)
        assertFalse("not journaled", e.remaining().contains("usb_debugging_off"))
        e.off()
        assertEquals("USB debugging was off before and stays off", false, p.state["usb_debugging"])
        assertEquals(true, p.state["developer_options"])
    }

    @Test fun `the keep-alive that was already off stays off`() {
        val p = Phone(allOn()).also { it.keeperOn = false }; val (e, _) = engine(p)
        e.on(); e.off()
        assertFalse(p.keeperOn)
    }

    @Test fun `each item reports done, needs your tap with its deep link, or failed`() {
        val p = Phone(allOn(), needsOwner = setOf("developer_options"), stuck = setOf("floating_nav")); val (e, _) = engine(p)
        val on = e.on().associateBy { it.id }
        assertEquals("done", on.getValue("usb_debugging_off").verdict)
        val tap = on.getValue("developer_options_off")
        assertEquals("needs your tap", tap.verdict); assertEquals("android.settings.APPLICATION_DEVELOPMENT_SETTINGS", tap.link)
        assertEquals("failed", on.getValue("floating_nav_off").verdict)
        assertFalse("an item the owner must change is not in the journal", e.remaining().contains("developer_options_off"))
        assertTrue("a failed write that captured a prior value is journaled so Off can still try", e.remaining().contains("floating_nav_off"))
        assertEquals(true, p.state["developer_options"])
    }

    @Test fun `an unreadable item is left alone`() {
        val s = allOn().also { it.remove("edge_menus") }
        val p = Phone(s); val (e, _) = engine(p)
        val r = e.on().first { it.id == "edge_menus_off" }
        assertEquals(SsState.SKIPPED, r.state); assertTrue(r.note, r.note.startsWith("not read"))
    }

    @Test fun `the VPN is kept on unless the mode's own switch is on`() {
        val vpnStep = steps.first { it.id == "vpn_off" }
        assertEquals("vpn", vpnStep.p.getString("item"))
        assertTrue("BankingMode reads vpn through the switch", File("src/main/java/com/diegonmarcos/superapp/system/BankingMode.kt").readText().contains("if (!vpnWanted(ctx)) DeviceRead(false"))
    }

    @Test fun `no step needs the shell, so picking the mode never raises the ADB prompt`() {
        assertTrue(steps.all { !it.adb })
        assertEquals(true, entry.getBoolean("no_shell")); assertEquals(false, entry.getBoolean("cycle"))
        val p = Phone(allOn()); val (e, _) = engine(p)
        assertTrue("no step skipped for a missing shell", e.on().none { it.note.contains("ADB", ignoreCase = true) && it.state == SsState.SKIPPED && it.note != "already off" })
    }

    @Test fun `pairing gate - the mode's sources reach no connect, arm or pair path`() {
        val forbidden = listOf("autoConnect", "autoArm", "openAdbShell", "ChannelAutoArm.set", "AdbReadiness.connector", "runBlocking(", "pairingCode", "PairingFlow", "ServerAutoStart", "AdbShellPage", "connect(")
        for (f in listOf("system/BankingMode.kt", "system/BankingWatcher.kt")) {
            val src = File("src/main/java/com/diegonmarcos/superapp/$f").readText().lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }.joinToString("\n")
            for (w in forbidden) assertFalse("$f reaches $w", src.contains(w))
        }
    }

    @Test fun `the declared banks parse and the decision acts only on a change`() {
        val banks = BankingMode.parseBanks(entry)
        assertTrue(banks.size >= 5); assertTrue("com.revolut.revolut" in banks)
        val b = banks.keys
        assertEquals(BankingMode.Action.OFFER_ON, BankingMode.decide("com.revolut.revolut", "home", b, false, BankingMode.Auto.OFFER, false))
        assertEquals(BankingMode.Action.APPLY_ON, BankingMode.decide("com.revolut.revolut", "home", b, false, BankingMode.Auto.APPLY, false))
        assertEquals("one visit is one offer", BankingMode.Action.NONE, BankingMode.decide("com.revolut.revolut", "com.revolut.revolut", b, false, BankingMode.Auto.OFFER, false))
        assertEquals(BankingMode.Action.NONE, BankingMode.decide("com.revolut.revolut", "home", b, true, BankingMode.Auto.APPLY, true))
        assertEquals(BankingMode.Action.OFFER_OFF, BankingMode.decide("home", "com.revolut.revolut", b, true, BankingMode.Auto.OFFER, true))
        assertEquals(BankingMode.Action.APPLY_OFF, BankingMode.decide("home", "com.revolut.revolut", b, true, BankingMode.Auto.APPLY, true))
        assertEquals("a mode the owner picked is offered, not restored behind their back", BankingMode.Action.OFFER_OFF, BankingMode.decide("home", "com.revolut.revolut", b, true, BankingMode.Auto.APPLY, false))
        assertEquals(BankingMode.Action.NONE, BankingMode.decide("com.revolut.revolut", "home", b, false, BankingMode.Auto.OFF, false))
        assertEquals(BankingMode.Action.NONE, BankingMode.decide(null, "com.revolut.revolut", b, true, BankingMode.Auto.APPLY, true))
    }
}
