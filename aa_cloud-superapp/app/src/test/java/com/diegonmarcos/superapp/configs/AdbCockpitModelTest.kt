package com.diegonmarcos.superapp.configs

import com.diegonmarcos.superapp.adbdebug.ChannelFacts
import com.diegonmarcos.superapp.adbdebug.ChannelLog
import com.diegonmarcos.superapp.adbdebug.ChannelMode
import com.diegonmarcos.superapp.adbdebug.LaunchVia
import com.diegonmarcos.superapp.configs.AdbCockpitModel.Do
import com.diegonmarcos.superapp.configs.AdbCockpitModel.Light
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ADB Shell Status cockpit's pure rules.
 *   C1 four tabs, Status first: Status, Shell, Perms, All
 *   C2 the chain is Perms, Setup, Shizuku, Local Server, Embedded, Connection, in dependency order
 *   C3 Perms is a light only (one action: jump to the Perms tab)
 *   C4 Setup has two sub-lights (Developer Options, Wireless Debugging) and one deep-link action each
 *   C5 the active mode row is highlighted, the other two dim to "switch mode to use"
 *   C6 Local Server owns all its actions, enabled only when they apply
 *   C7 Connection waits in GREY (never red) while Setup or the active mode is not ready
 *   C8 the master pill: green connected / amber ready / red blocked / grey idle
 *   C9 Auto-fix order and its consent gates
 *   C10 log severity and filter
 */
class AdbCockpitModelTest {
    private fun facts(mode: ChannelMode = ChannelMode.LOCAL_SERVER, dev: Boolean? = true, wd: Boolean? = true, paired: Boolean = false, connected: Boolean = false,
                      server: Boolean = false, shz: Triple<Boolean, Boolean, Boolean> = Triple(false, false, false), wifi: Boolean? = true) =
        ChannelFacts(mode = mode, probedAt = 1L, devOptions = dev, wirelessDebug = wd, onWifi = wifi, adbPaired = paired || connected, adbConnected = connected,
            serverPort = 38099, serverRunning = server, serverUid = if (server) "uid 2000" else null,
            shizukuInstalled = shz.first, shizukuRunning = shz.second, shizukuGranted = shz.third)

    private fun inputs(f: ChannelFacts, perms: AdbCockpitModel.PermsDigest? = AdbCockpitModel.PermsDigest(2, emptyList()), fail: String? = null) =
        AdbCockpitModel.Inputs(f, perms, LaunchVia.ADB, fail)

    private fun step(i: AdbCockpitModel.Inputs, id: String) = AdbCockpitModel.steps(i).first { it.id == id }

    @Test fun `C1 four tabs, Status first`() {
        assertEquals(listOf("Status", "Shell", "Perms", "All"), AdbCockpitModel.Tab.values().map { it.label })
        assertEquals(listOf("adb-status", "adb-term", "adb-perms", "adb-all"), AdbCockpitModel.Tab.values().map { it.pageId })
        assertEquals(AdbCockpitModel.Tab.ALL, AdbCockpitModel.Tab.of(null))
        assertEquals(AdbCockpitModel.Tab.SHELL, AdbCockpitModel.Tab.of("adb-term"))
    }

    @Test fun `C2 the chain order`() {
        assertEquals(listOf("perms", "setup", "shizuku", "local", "embedded", "connection"), AdbCockpitModel.steps(inputs(facts())).map { it.id })
        assertEquals(AdbCockpitModel.STEP_ORDER, AdbCockpitModel.steps(inputs(facts())).map { it.id })
        assertEquals(listOf("Perms", "Setup", "Shizuku", "Local Server", "Embedded", "Connection"), AdbCockpitModel.steps(inputs(facts())).map { it.name })
    }

    @Test fun `C3 perms is a light only`() {
        val f = facts()
        val ok = AdbCockpitModel.perms(AdbCockpitModel.PermsDigest(2, emptyList()), f)
        assertEquals(Light.GREEN, ok.light); assertEquals("all granted", ok.message)
        assertEquals(listOf(Do.OPEN_PERMS), ok.acts.map { it.what })
        val miss = AdbCockpitModel.perms(AdbCockpitModel.PermsDigest(2, listOf("Write secure settings", "Notifications")), f)
        assertEquals(Light.AMBER, miss.light); assertTrue(miss.message.startsWith("2 missing"))
        assertEquals(Light.GREY, AdbCockpitModel.perms(null, f).light)
        // pairing types its code into a notification: unpaired + notifications off = nothing can start
        assertEquals(Light.RED, AdbCockpitModel.perms(AdbCockpitModel.PermsDigest(2, listOf("Notifications"), notificationsMissing = true), f).light)
        assertEquals(Light.AMBER, AdbCockpitModel.perms(AdbCockpitModel.PermsDigest(2, listOf("Notifications"), notificationsMissing = true), facts(paired = true)).light)
    }

    @Test fun `C4 setup sub-lights and deep links`() {
        val off = AdbCockpitModel.setup(facts(wd = false))
        assertEquals(Light.AMBER, off.light); assertEquals("Wireless Debugging is off", off.message)
        assertEquals(listOf("Developer Options" to Light.GREEN, "Wireless Debugging" to Light.AMBER), off.subs.map { it.label to it.light })
        assertEquals(listOf(Do.OPEN_DEV, Do.OPEN_WD), off.acts.map { it.what })
        assertTrue(off.acts.first { it.what == Do.OPEN_WD }.primary)
        assertEquals(Light.GREEN, AdbCockpitModel.setup(facts()).light)
        assertEquals("Developer Options are off", AdbCockpitModel.setup(facts(dev = false)).message)
        assertEquals(Light.GREY, AdbCockpitModel.setup(facts(dev = null, wd = null)).light)
        assertEquals(Light.AMBER, AdbCockpitModel.setup(facts(wifi = false)).light)
        assertEquals(Light.GREY, AdbCockpitModel.setup(facts(mode = ChannelMode.SHIZUKU, wd = false)).light)
    }

    @Test fun `C5 active mode highlighted, the others dim`() {
        val i = inputs(facts(ChannelMode.LOCAL_SERVER))
        assertEquals(false, step(i, "local").dim); assertEquals("active mode", step(i, "local").badge)
        for (id in listOf("shizuku", "embedded")) {
            val s = step(i, id)
            assertTrue(id, s.dim); assertEquals(Light.GREY, s.light); assertTrue(s.message.startsWith("switch mode to use"))
        }
        val e = inputs(facts(ChannelMode.EMBEDDED_ONLY))
        assertEquals(setOf("embedded"), AdbCockpitModel.steps(e).filter { !it.dim && it.id in setOf("shizuku", "local", "embedded") }.map { it.id }.toSet())
        assertEquals(setOf("shizuku", "local", "embedded"), AdbCockpitModel.active(ChannelMode.AUTO))
        assertEquals(setOf("local", "shizuku"), AdbCockpitModel.active(ChannelMode.LOCAL_SERVER, LaunchVia.SHIZUKU))
        assertEquals(listOf("Shizuku", "Local Server", "Embedded"), AdbCockpitModel.SELECTABLE.map { AdbCockpitModel.modeName(it) })
    }

    @Test fun `C6 local server owns its actions`() {
        val down = step(inputs(facts()), "local")
        assertEquals(listOf(Do.LOCAL_PAIR, Do.LOCAL_START, Do.LOCAL_STOP, Do.LOCAL_RESTART), down.acts.map { it.what })
        assertEquals("not paired · server down", down.message.removePrefix("switch mode to use · "))
        assertTrue(down.acts.first { it.what == Do.LOCAL_PAIR }.primary)
        assertFalse("no adb or Shizuku session to start it with", down.acts.first { it.what == Do.LOCAL_START }.enabled)
        assertFalse(down.acts.first { it.what == Do.LOCAL_STOP }.enabled)
        val up = step(inputs(facts(paired = true, connected = true, server = true)), "local")
        assertEquals(Light.GREEN, up.light); assertTrue(up.message.startsWith("paired · server up"))
        assertTrue(up.acts.first { it.what == Do.LOCAL_STOP }.enabled)
        assertFalse(up.acts.first { it.what == Do.LOCAL_START }.enabled)
        assertTrue(up.acts.first { it.what == Do.LOCAL_RESTART }.enabled)
        val startable = step(inputs(facts(paired = true, connected = true)), "local")
        assertTrue(startable.acts.first { it.what == Do.LOCAL_START }.enabled)
        // another mode does not use the server: its actions say so
        assertFalse(step(inputs(facts(ChannelMode.SHIZUKU, paired = true, connected = true)), "local").acts.first { it.what == Do.LOCAL_START }.enabled)
    }

    @Test fun `C6b shizuku and embedded rows`() {
        val shz = step(inputs(facts(ChannelMode.SHIZUKU, shz = Triple(true, true, false))), "shizuku")
        assertEquals(Light.AMBER, shz.light); assertEquals("running · authorization needed", shz.message)
        assertTrue(shz.acts.first { it.what == Do.SHIZUKU_AUTH }.enabled)
        assertEquals(Light.RED, step(inputs(facts(ChannelMode.SHIZUKU)), "shizuku").light)
        assertEquals(Light.GREEN, step(inputs(facts(ChannelMode.SHIZUKU, shz = Triple(true, true, true))), "shizuku").light)
        val emb = step(inputs(facts(ChannelMode.EMBEDDED_ONLY, paired = true)), "embedded")
        assertEquals(listOf(Do.EMB_INIT, Do.EMB_RESET), emb.acts.map { it.what }); assertEquals("paired · not connected", emb.message)
        assertEquals(Light.GREEN, step(inputs(facts(ChannelMode.EMBEDDED_ONLY, connected = true)), "embedded").light)
    }

    @Test fun `C7 connection waits in grey, never red`() {
        val waiting = step(inputs(facts(wd = false)), "connection")
        assertEquals(Light.GREY, waiting.light); assertEquals("waiting on Setup + Local Server", waiting.message)
        assertEquals(listOf(Do.CONNECT, Do.RECONNECT), waiting.acts.map { it.what })
        // a stored failure does not turn a WAITING connection red
        assertEquals(Light.GREY, step(inputs(facts(wd = false), fail = "boom"), "connection").light)
        val ready = step(inputs(facts(ChannelMode.EMBEDDED_ONLY, paired = true)), "connection")
        assertEquals(Light.AMBER, ready.light); assertEquals("ready · not connected", ready.message)
        assertEquals(Light.RED, step(inputs(facts(ChannelMode.EMBEDDED_ONLY, paired = true), fail = "no answer"), "connection").light)
        val up = step(inputs(facts(paired = true, connected = true, server = true)), "connection")
        assertEquals(Light.GREEN, up.light); assertEquals("connected via local-server", up.message)
    }

    @Test fun `C8 master pill`() {
        assertEquals(Light.GREY, AdbCockpitModel.master(null).light)
        assertEquals("Connected", AdbCockpitModel.master(inputs(facts(paired = true, connected = true, server = true))).label)
        assertEquals(Light.GREEN, AdbCockpitModel.master(inputs(facts(paired = true, connected = true, server = true))).light)
        // the server answers but nothing can relaunch it: degraded
        assertEquals("Degraded", AdbCockpitModel.master(inputs(facts(paired = true, server = true))).label)
        val ready = AdbCockpitModel.master(inputs(facts(ChannelMode.EMBEDDED_ONLY, paired = true)))
        assertEquals(Light.AMBER, ready.light); assertEquals("Ready", ready.label)
        assertEquals("Setup needed", AdbCockpitModel.master(inputs(facts(wd = false))).label)
        val blocked = AdbCockpitModel.master(inputs(facts(ChannelMode.SHIZUKU)))
        assertEquals(Light.RED, blocked.light); assertEquals("Blocked", blocked.label)
    }

    @Test fun `C8b top bar toggle`() {
        assertEquals(Do.CONNECT, AdbCockpitModel.toggle(facts()).what)
        assertEquals(Do.DISCONNECT, AdbCockpitModel.toggle(facts(paired = true, connected = true, server = true)).what)
        assertTrue(AdbCockpitModel.toggle(facts(paired = true, connected = true, server = true)).enabled)
        assertNull(AdbCockpitModel.toggle(facts()).why)
    }

    @Test fun `C9 auto-fix order and gates`() {
        assertEquals(listOf("PERMS", "DEV_OPTIONS", "WIRELESS_DEBUG", "PAIR", "ADB_CONNECT", "START_SERVER", "VERIFY"),
            AdbCockpitModel.autoFixOrder(ChannelMode.LOCAL_SERVER, LaunchVia.ADB, true))
        assertEquals(listOf("PERMS", "SHIZUKU_RUNNING", "SHIZUKU_PERMISSION", "VERIFY"), AdbCockpitModel.autoFixOrder(ChannelMode.SHIZUKU, LaunchVia.ADB, true))
        assertEquals(listOf("PERMS", "DEV_OPTIONS", "WIRELESS_DEBUG", "PAIR", "ADB_CONNECT", "VERIFY"), AdbCockpitModel.autoFixOrder(ChannelMode.EMBEDDED_ONLY, LaunchVia.ADB, true))
        // every consent gate is in some order, and the gates are exactly the person's steps
        val all = ChannelMode.values().flatMap { AdbCockpitModel.autoFixOrder(it, LaunchVia.ADB, true) + AdbCockpitModel.autoFixOrder(it, LaunchVia.SHIZUKU, true) }.toSet()
        assertTrue(all.containsAll(AdbCockpitModel.USER_GATES))
        assertFalse("START_SERVER and ADB_CONNECT run by themselves", "START_SERVER" in AdbCockpitModel.USER_GATES || "ADB_CONNECT" in AdbCockpitModel.USER_GATES)
    }

    @Test fun `C10 log severity and filter`() {
        fun e(tag: String, text: String) = ChannelLog.Entry(1L, tag, text)
        assertEquals(AdbCockpitModel.Sev.ERR, AdbCockpitModel.severity(e("connect", "Pair with code: FAILED")))
        assertEquals(AdbCockpitModel.Sev.ERR, AdbCockpitModel.severity(e("x", "embedded adb is not connected")))
        assertEquals(AdbCockpitModel.Sev.OK, AdbCockpitModel.severity(e("connect", "Developer options on: ok")))
        assertEquals(AdbCockpitModel.Sev.INFO, AdbCockpitModel.severity(e("mode", "selected local")))
        val log = listOf(e("connect", "a"), e("pair", "b notification"), e("action", "c"))
        assertEquals(3, AdbCockpitModel.filter(log, "").size)
        assertEquals(listOf("pair"), AdbCockpitModel.filter(log, "PAIR").map { it.tag })
        assertEquals(listOf("pair"), AdbCockpitModel.filter(log, "notification").map { it.tag })
    }
}
