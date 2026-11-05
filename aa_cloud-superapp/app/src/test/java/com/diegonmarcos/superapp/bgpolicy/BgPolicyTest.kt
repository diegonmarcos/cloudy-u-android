package com.diegonmarcos.superapp.bgpolicy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1506 Background policy: the state machine, the snapshot/restore round trip, the allowlist, the no-ADB degradation, the gate. */
class BgPolicyTest {

    private val cfg = BgFakeDevice.cfg()
    private fun engine(dev: BgShell?, roles: Set<String> = setOf("com.diegonmarcos.superapp"), kv: BgKv = BgFakeDevice.kv(), audit: MutableList<String> = ArrayList(),
                       comp: (String, String) -> Boolean? = { _, _ -> null }) =
        BgEngine(dev, BgEssential(cfg, roles), BgStore(kv), { audit += it }, comp)

    // ── parsers ──
    @Test fun `appops, bucket and process parsers`() {
        assertEquals("ignore", BgCmd.opMode("RUN_ANY_IN_BACKGROUND: ignore; time=+3d", OP_ANY))
        assertEquals("default", BgCmd.opMode("No operations.", OP_ANY))
        assertEquals(45, BgCmd.bucketOf("45\n")); assertEquals(Bucket.RESTRICTED, BgCmd.bucketOf("RESTRICTED")); assertEquals(Bucket.UNKNOWN, BgCmd.bucketOf("error"))
        val g = BgCmd.parseGlobal("#idle\nuser,com.a,10001\nsystem-excidle,com.b,1\n#ps\nNAME\ncom.c:remote\ncom.d\n")
        assertEquals(setOf("com.a", "com.b"), g.idle)
        assertTrue(g.running("com.c")); assertTrue(g.running("com.d")); assertFalse(g.running("com.e"))
    }

    @Test fun `a package name that is not a package name never reaches a shell line`() {
        for (bad in listOf("com.a; reboot", "a b", "\$(id)", "")) assertTrue(runCatching { BgCmd.restrict(bad) }.isFailure)
        assertTrue(runCatching { BgCmd.component("com.a", "x'; reboot; '", false) }.isFailure)
    }

    // ── the state machine: allowed -> restricted -> allowed, each verified by reading back ──
    @Test fun `restrict then allow moves the device and each verdict is the read-back`() {
        val dev = BgFakeDevice("com.x"); val e = engine(dev)
        val r = e.restrict("com.x")
        assertTrue(r.note, r.ok); assertTrue(r.after!!.restricted)
        assertEquals("ignore", dev.state["com.x"]!!.any); assertEquals("ignore", dev.state["com.x"]!!.inn)
        assertEquals(Bucket.RESTRICTED, dev.state["com.x"]!!.bucket); assertFalse(dev.state["com.x"]!!.idle)
        val a = e.allow("com.x")
        assertTrue(a.note, a.ok); assertFalse(a.after!!.restricted); assertEquals(Bucket.ACTIVE, dev.state["com.x"]!!.bucket)
    }

    @Test fun `an action the device did not take is reported, not claimed`() {
        val dev = BgFakeDevice("com.x"); dev.refuseBucket += "com.x"
        val store = BgFakeDevice.kv()
        val r = engine(dev, kv = store).restrict("com.x")
        assertFalse(r.ok); assertTrue(r.note, r.note.contains("bucket"))
        assertNull("a failed restrict is not persisted as the policy", BgStore(store).choice("com.x"))
    }

    @Test fun `force stop is verified by the process being gone`() {
        val dev = BgFakeDevice("com.x"); val r = engine(dev).forceStop("com.x")
        assertTrue(r.ok); assertFalse(dev.state["com.x"]!!.running)
    }

    @Test fun `a component is disabled and enabled and the verdict is its read-back flag`() {
        val dev = BgFakeDevice("com.x")
        var flag: Boolean? = true
        val e = engine(dev, comp = { _, _ -> flag })
        flag = false
        assertTrue(e.component("com.x", "com.x.BootReceiver", enable = false).ok)
        assertEquals(false, dev.comps["com.x/com.x.BootReceiver"])
        assertFalse("flag still enabled = not done", engine(dev, comp = { _, _ -> true }).component("com.x", "com.x.R", enable = false).ok)
        assertFalse("unreadable = not done", engine(dev).component("com.x", "com.x.R", enable = true).ok)
    }

    // ── the allowlist is never restricted ──
    @Test fun `the essential allowlist is never restricted, by any path`() {
        val roles = setOf("com.diegonmarcos.superapp", "cld.dialer.custom", "org.sms.app", "net.my.launcher", "org.my.keyboard", "com.android.systemui")
        val essential = roles.toList() + listOf("com.android.phone", "com.android.server.telecom", "com.samsung.android.messaging")
        val dev = BgFakeDevice(*(essential + "com.other.app" + "com.diegonmarcos.cloudchat").toTypedArray())
        val before = dev.state.mapValues { Triple(it.value.any, it.value.inn, it.value.bucket) }
        val e = engine(dev, roles)
        val pkgs = dev.state.keys.map { BgPkg(it, it, system = false) }
        val outs = e.restrictAll(pkgs, cfg)
        for (p in essential) {
            val o = outs.first { it.pkg == p }
            assertTrue("$p skipped", o.skipped); assertFalse("$p not ok", o.ok)
            assertEquals("$p untouched", before[p], Triple(dev.state[p]!!.any, dev.state[p]!!.inn, dev.state[p]!!.bucket))
            assertTrue("$p force-stop refused", e.forceStop(p).skipped)
            assertTrue("$p components stay", e.component(p, "x.Y", enable = false).skipped)
        }
        assertTrue(outs.first { it.pkg == "com.other.app" }.ok)
        assertTrue("fleet is restricted too", outs.first { it.pkg == "com.diegonmarcos.cloudchat" }.ok)
    }

    @Test fun `bulk leaves system apps alone unless the declaration includes them`() {
        val dev = BgFakeDevice("com.sys.thing", "com.user.app"); val e = engine(dev)
        val outs = e.restrictAll(listOf(BgPkg("com.sys.thing", "s", true), BgPkg("com.user.app", "u", false)), cfg)
        assertEquals(listOf("com.user.app"), outs.map { it.pkg })
    }

    // ── snapshot / restore round trip ──
    @Test fun `restore puts back exactly what was there, even after a second restrict`() {
        val dev = BgFakeDevice("com.a", "com.b", "com.c")
        dev.state["com.a"]!!.apply { any = "allow"; inn = "allow"; bucket = Bucket.FREQUENT; idle = true }
        dev.state["com.b"]!!.apply { bucket = Bucket.ACTIVE }
        val kv = BgFakeDevice.kv(); val e = engine(dev, kv = kv)
        val orig = dev.state.mapValues { listOf(it.value.any, it.value.inn, it.value.bucket, it.value.idle) }
        e.restrictAll(dev.state.keys.map { BgPkg(it, it, false) }, cfg)
        e.restrict("com.a") // a second pass must not overwrite the captured prior with the restricted state
        assertTrue(dev.state.values.all { it.any == "ignore" })
        val outs = e.restore()
        assertTrue(outs.all { it.ok })
        for ((p, o) in orig) {
            val s = dev.state[p]!!
            assertEquals(p, o[0].let { if (it == "default") "allow" else it }, s.any.let { if (it == "default") "allow" else it })
            assertEquals(p + " bucket", o[2], s.bucket); assertEquals(p + " doze whitelist", o[3], s.idle)
        }
        assertTrue("choices cleared", BgStore(kv).choices().isEmpty()); assertTrue(BgStore(kv).priors().isEmpty())
    }

    @Test fun `the policy and the priors survive a new store over the same storage`() {
        val kv = BgFakeDevice.kv(); val dev = BgFakeDevice("com.a")
        engine(dev, kv = kv).restrict("com.a")
        val s = BgStore(kv)
        assertEquals(BgChoice.RESTRICT, s.choice("com.a")); assertEquals(Bucket.WORKING_SET, s.prior("com.a")!!.bucket)
    }

    // ── re-apply after an update ──
    @Test fun `reapply puts the chosen policy back on the one package and ignores unchosen ones`() {
        val dev = BgFakeDevice("com.a", "com.b"); val kv = BgFakeDevice.kv(); val e = engine(dev, kv = kv)
        e.restrict("com.a")
        dev.state["com.a"]!!.apply { any = "default"; inn = "default"; bucket = Bucket.ACTIVE } // the update reset it
        val o = e.reapply("com.a")!!
        assertTrue(o.ok); assertEquals("ignore", dev.state["com.a"]!!.any)
        assertNull(e.reapply("com.b"))
    }

    // ── ADB missing: everything skips with a note, nothing is written ──
    @Test fun `without a shell every action is skipped with a note and nothing is persisted`() {
        val kv = BgFakeDevice.kv(); val audit = ArrayList<String>(); val e = engine(null, kv = kv, audit = audit)
        assertFalse(e.available); assertNull(e.readAll(listOf(BgPkg("com.a", "a", false)), cfg))
        val outs = listOf(e.allow("com.a"), e.restrict("com.a"), e.forceStop("com.a"), e.component("com.a", "x.Y", true)) + e.restrictAll(listOf(BgPkg("com.a", "a", false)), cfg) + e.restore()
        assertTrue(outs.all { it.skipped && !it.ok && it.note == BgOutcome.NO_ADB })
        assertTrue(BgStore(kv).choices().isEmpty()); assertTrue(BgStore(kv).priors().isEmpty())
        assertTrue("each skip is audited", audit.size == outs.size)
    }

    @Test fun `a shell that throws degrades like no shell`() {
        val boom = object : BgShell { override val channel = "x"; override fun exec(cmd: String): String? = error("channel died") }
        val r = engine(boom).restrict("com.a")
        assertFalse(r.ok)
    }

    // ── read: fleet first ──
    @Test fun `readAll lists the fleet first`() {
        val dev = BgFakeDevice("com.zebra", "com.diegonmarcos.cloudchat", "com.alpha", "cld.termux")
        val rows = engine(dev).readAll(dev.state.keys.map { BgPkg(it, it.substringAfterLast('.'), false) }, cfg)!!
        assertEquals(listOf("com.diegonmarcos.cloudchat", "cld.termux", "com.alpha", "com.zebra"), rows.map { it.first.pkg })
        assertTrue(rows.take(2).all { cfg.isFleet(it.first.pkg) })
    }

    // ── the arm prompt ──
    @Test fun `the gate asks to arm, sends the user to the arm flow or the wireless-debugging switch, and never asks when ready`() = runBlocking {
        var arm = 0; var wd = 0
        assertEquals(BgGate.Result.SENT_TO_ARM, BgGate.run({ false }, { arm++ }, { wd++ }, { BgGate.Choice.REARM }))
        assertEquals(BgGate.Result.SENT_TO_ARM, BgGate.run({ false }, { arm++ }, { wd++ }, { BgGate.Choice.OPEN_WIRELESS_DEBUGGING }))
        assertEquals(1, arm); assertEquals(1, wd)
        assertEquals(BgGate.Result.CANCELLED, BgGate.run({ false }, { arm++ }, { wd++ }, { BgGate.Choice.CANCEL }))
        assertEquals(1, arm); assertEquals(1, wd)
        assertEquals(BgGate.Result.WITHOUT_ADB, BgGate.run({ false }, { arm++ }, { wd++ }, { BgGate.Choice.WITHOUT_ADB }))
        assertEquals(BgGate.Result.READY, BgGate.run({ true }, { arm++ }, { wd++ }, { error("not asked when ready") }))
    }

    @Test fun `the declaration parses and names the essential roles`() {
        assertEquals(listOf("self", "dialer", "sms", "launcher", "keyboard", "systemui"), cfg.essentialRoles)
        assertTrue(cfg.isFleet("cld.termux") && cfg.isFleet("com.diegonmarcos.cloudchat") && !cfg.isFleet("com.whatsapp"))
        assertTrue(cfg.isFixedEssential("com.android.systemui") && !cfg.isFixedEssential("com.whatsapp"))
    }
}
