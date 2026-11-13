package com.diegonmarcos.superapp.adbdebug

import com.diegonmarcos.superapp.adbdebug.ChannelMode.AUTO
import com.diegonmarcos.superapp.adbdebug.ChannelMode.EMBEDDED_ONLY
import com.diegonmarcos.superapp.adbdebug.ChannelMode.LOCAL_SERVER
import com.diegonmarcos.superapp.adbdebug.ChannelMode.SHIZUKU
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Connect sequence: which steps a mode runs, and the machine that walks them. */
class ConnectPlanTest {

    private val none = ChannelFacts(LOCAL_SERVER, 1L, serverPort = 38099)

    /** A device where each step's success flips the fact the next steps read; [failAt]/[waitAt] break one step. */
    private class FakeDevice(var f: ChannelFacts, val failAt: StepId? = null, val waitAt: StepId? = null,
                           val startFlipsShizuku: Boolean = true) : ConnectExecutor {
        val ran = ArrayList<StepId>()
        override fun facts() = f
        override fun run(step: StepId): StepResult {
            ran += step
            if (step == failAt) return StepResult(false, "boom")
            if (step == waitAt) return StepResult(false, "code needed", needsUser = true)
            f = when (step) {
                StepId.DEV_OPTIONS -> f.copy(devOptions = true)
                StepId.WIRELESS_DEBUG -> f.copy(wirelessDebug = true)
                StepId.PAIR -> f.copy(adbPaired = true)
                StepId.ADB_CONNECT -> f.copy(adbConnected = true)
                StepId.SHIZUKU_RUNNING -> f.copy(shizukuInstalled = true, shizukuRunning = true)
                StepId.SHIZUKU_PERMISSION -> f.copy(shizukuGranted = true)
                StepId.VERIFY -> f
                StepId.SHIZUKU_START -> if (startFlipsShizuku) f.copy(shizukuRunning = true) else f
                StepId.LAUNCH_SERVER -> f.copy(serverRunning = true)
                StepId.CLOSE_EMBEDDED -> f.copy(adbConnected = false)
            }
            return StepResult(true, "ok:${step.name}")
        }
    }

    @Test fun stepsPerMode() {
        val adb = listOf(StepId.DEV_OPTIONS, StepId.WIRELESS_DEBUG, StepId.PAIR, StepId.ADB_CONNECT)
        val shz = listOf(StepId.SHIZUKU_RUNNING, StepId.SHIZUKU_PERMISSION)
        assertEquals(adb + StepId.VERIFY, ConnectPlan.steps(EMBEDDED_ONLY, LaunchVia.ADB))
        // #1709 Shizuku: adb part, Shizuku's own start through it, its service + permission, the round trip, adb closed.
        assertEquals(adb + StepId.SHIZUKU_START + shz + StepId.VERIFY + StepId.CLOSE_EMBEDDED, ConnectPlan.steps(SHIZUKU, LaunchVia.ADB))
        // #1709 Local server: adb part, launch the server through it, the server's own round trip, adb closed.
        assertEquals(adb + StepId.LAUNCH_SERVER + StepId.VERIFY + StepId.CLOSE_EMBEDDED, ConnectPlan.steps(LOCAL_SERVER, LaunchVia.ADB))
        assertEquals(adb + StepId.VERIFY, ConnectPlan.steps(AUTO, LaunchVia.ADB))
        // Embedded and Auto are unchanged: they end on the round trip and leave the session open.
        for (m in listOf(EMBEDDED_ONLY, AUTO)) for (v in LaunchVia.values()) assertEquals(StepId.VERIFY, ConnectPlan.steps(m, v).last())
        for (m in listOf(LOCAL_SERVER, SHIZUKU)) assertEquals(StepId.CLOSE_EMBEDDED, ConnectPlan.steps(m).last())
        assertEquals(adb + StepId.VERIFY, ConnectPlan.steps(AUTO, LaunchVia.ADB, ownsServer = false))
        assertEquals(adb, ConnectPlan.ADB_CHAIN); assertEquals(shz, ConnectPlan.SHIZUKU_CHAIN)
    }

    /** Embedded and Auto never walk into Shizuku or the server; the launcher choice and server ownership change no chain. */
    @Test fun modesAreIndependentChains() {
        val shizukuSteps = setOf(StepId.SHIZUKU_START, StepId.SHIZUKU_RUNNING, StepId.SHIZUKU_PERMISSION)
        for (v in LaunchVia.values()) for (owns in listOf(true, false)) {
            for (m in listOf(EMBEDDED_ONLY, AUTO)) {
                val plan = ConnectPlan.steps(m, v, owns)
                assertTrue("$m/$v walks into Shizuku", plan.none { it in shizukuSteps })
                assertTrue("$m/$v launches a server", StepId.LAUNCH_SERVER !in plan)
            }
            // Local server: no Shizuku STEP (Shizuku is only the launcher fallback, decided inside the run).
            assertTrue(ConnectPlan.steps(LOCAL_SERVER, v, owns).none { it in shizukuSteps })
            for (m in ChannelMode.values()) assertEquals("$m", ConnectPlan.steps(m, LaunchVia.ADB, owns), ConnectPlan.steps(m, v, owns))
        }
    }

    /** The server launch is a step of the Local server chain only; the old circular "start the server" step stays gone. */
    @Test fun noStepStartsAServer() {
        assertEquals(listOf("DEV_OPTIONS", "WIRELESS_DEBUG", "PAIR", "ADB_CONNECT", "SHIZUKU_RUNNING", "SHIZUKU_PERMISSION", "VERIFY",
            "SHIZUKU_START", "LAUNCH_SERVER", "CLOSE_EMBEDDED"), StepId.values().map { it.name })
        assertTrue(StepId.values().none { it.name == "START_SERVER" })
        for (m in ChannelMode.values()) if (m != LOCAL_SERVER) assertFalse("$m", StepId.LAUNCH_SERVER in ConnectPlan.steps(m))
    }

    /** Server down: the whole Local chain runs (embedded connect, launch, server round trip, embedded closed). */
    @Test fun localServerChainLaunchesAndClosesEmbedded() {
        val dev = FakeDevice(none.copy(devOptions = true, wirelessDebug = true, adbPaired = true, adbConnected = false, serverRunning = false))
        val out = ConnectPlan.run(LOCAL_SERVER, LaunchVia.ADB, dev, ChannelLog())
        assertEquals(ConnectStatus.SUCCESS, out.status)
        assertEquals(listOf(StepId.ADB_CONNECT, StepId.LAUNCH_SERVER, StepId.VERIFY, StepId.CLOSE_EMBEDDED), dev.ran)
        assertFalse(dev.f.adbConnected); assertTrue(dev.f.serverRunning)
    }

    /** Server already answers: no embedded session is opened, only the round trip runs. */
    @Test fun localServerUpOpensNoEmbeddedSession() {
        val dev = FakeDevice(none.copy(serverRunning = true))
        val out = ConnectPlan.run(LOCAL_SERVER, LaunchVia.ADB, dev)
        assertEquals(ConnectStatus.SUCCESS, out.status)
        assertEquals(listOf(StepId.VERIFY), dev.ran)
        assertTrue(out.steps.filter { it.id != StepId.VERIFY }.all { it.state == StepState.SKIPPED })
    }

    /** Embedded cannot connect but Shizuku runs and is granted: it launches; the adb steps are skipped with the reason. */
    @Test fun localServerFallsBackToShizukuWhenEmbeddedCannotConnect() {
        val shz = none.copy(shizukuInstalled = true, shizukuRunning = true, shizukuGranted = true, devOptions = true, wirelessDebug = true, adbPaired = true)
        val dev = FakeDevice(shz, failAt = StepId.ADB_CONNECT)
        val out = ConnectPlan.run(LOCAL_SERVER, LaunchVia.ADB, dev)
        assertEquals(ConnectStatus.SUCCESS, out.status)
        assertEquals(listOf(StepId.ADB_CONNECT, StepId.LAUNCH_SERVER, StepId.VERIFY), dev.ran)
        assertTrue(out.steps.first { it.id == StepId.ADB_CONNECT }.detail.contains("Shizuku launches"))
        // without a granted Shizuku the same failure stops the chain at the connect
        val no = ConnectPlan.run(LOCAL_SERVER, LaunchVia.ADB, FakeDevice(none.copy(devOptions = true, wirelessDebug = true, adbPaired = true), failAt = StepId.ADB_CONNECT))
        assertEquals(StepId.ADB_CONNECT, no.failedStep)
    }

    /** Shizuku profile: a stopped service is started through embedded adb, then adb is closed; a running one skips adb. */
    @Test fun shizukuChainStartsTheServiceThroughEmbeddedThenCloses() {
        val dev = FakeDevice(none.copy(shizukuInstalled = true, devOptions = true, wirelessDebug = true, adbPaired = true))
        val out = ConnectPlan.run(SHIZUKU, LaunchVia.ADB, dev)
        assertEquals(ConnectStatus.SUCCESS, out.status)
        assertEquals(listOf(StepId.ADB_CONNECT, StepId.SHIZUKU_START, StepId.SHIZUKU_PERMISSION, StepId.VERIFY, StepId.CLOSE_EMBEDDED), dev.ran)
        val up = FakeDevice(none.copy(shizukuInstalled = true, shizukuRunning = true, shizukuGranted = true))
        val o2 = ConnectPlan.run(SHIZUKU, LaunchVia.ADB, up)
        assertEquals(ConnectStatus.SUCCESS, o2.status)
        assertEquals(listOf(StepId.VERIFY), up.ran)
    }

    @Test fun methodsShowOnlyWhatAppliesToTheMode() {
        val adbWay = listOf("Pair with code", "Connect via mDNS", "Run over the adb session")
        assertEquals(adbWay, ConnectPlan.methods(EMBEDDED_ONLY, LaunchVia.ADB).map { it.title })
        assertEquals(adbWay, ConnectPlan.methods(AUTO, LaunchVia.ADB, ownsServer = false).map { it.title })
        assertEquals(listOf("Pair with code", "Connect via mDNS", "Launch the local server"), ConnectPlan.methods(LOCAL_SERVER, LaunchVia.SHIZUKU).map { it.title })
        assertEquals(listOf("Shizuku only"), ConnectPlan.methods(SHIZUKU, LaunchVia.ADB, ownsServer = false).map { it.title })
        assertEquals(listOf("Shizuku only"), ConnectPlan.methods(SHIZUKU, LaunchVia.ADB).map { it.title })
        assertTrue(ConnectPlan.methods(AUTO, LaunchVia.ADB).none { it.title.contains("Shizuku") })
    }

    @Test fun runsEveryStepInOrderAndSucceeds() {
        val dev = FakeDevice(none)
        val seen = ArrayList<List<StepRun>>()
        val out = ConnectPlan.run(LOCAL_SERVER, LaunchVia.ADB, dev) { seen += it }
        assertEquals(ConnectStatus.SUCCESS, out.status)
        assertNull(out.failedStep); assertNull(out.explanation)
        assertEquals(ConnectPlan.steps(LOCAL_SERVER, LaunchVia.ADB), dev.ran)
        assertTrue(out.steps.all { it.state == StepState.DONE })
        // progress was reported step by step: a RUNNING snapshot precedes each DONE
        assertTrue(seen.any { s -> s.count { it.state == StepState.RUNNING } == 1 })
        assertEquals(StepState.PENDING, seen.first().first().state)
    }

    @Test fun skipsWhatIsAlreadyTrue() {
        val paired = none.copy(devOptions = true, wirelessDebug = true, adbPaired = true)
        val dev = FakeDevice(paired)
        val out = ConnectPlan.run(EMBEDDED_ONLY, LaunchVia.ADB, dev)
        assertEquals(ConnectStatus.SUCCESS, out.status)
        assertEquals(listOf(StepId.ADB_CONNECT, StepId.VERIFY), dev.ran)
        assertEquals(listOf(StepState.SKIPPED, StepState.SKIPPED, StepState.SKIPPED, StepState.DONE, StepState.DONE), out.steps.map { it.state })
    }

    /** The point of the machine: a failure at EACH step stops there, names it, and leaves the rest pending. */
    @Test fun failureAtEachStepStopsThereAndExplainsIt() {
        for (mode in ChannelMode.values()) for (via in LaunchVia.values()) {
            val plan = ConnectPlan.steps(mode, via)
            for ((i, step) in plan.withIndex()) {
                if (step == StepId.PAIR) continue                                  // PAIR hands over to the person: its own test
                // A started Shizuku service makes its "running" step already true (skipped by design, proven in
                // shizukuChainStartsTheServiceThroughEmbeddedThenCloses); here the fake start leaves the service down so
                // the running step itself runs and every step of the plan is proven to stop and explain.
                val dev = FakeDevice(none, failAt = step, startFlipsShizuku = false)
                val out = ConnectPlan.run(mode, via, dev)
                val tag = "$mode/$via fail@$step"
                assertEquals(tag, ConnectStatus.FAILED, out.status)
                assertEquals(tag, step, out.failedStep)
                assertEquals(tag, plan.take(i + 1), dev.ran)                          // nothing after the failing step ran
                assertEquals(tag, StepState.FAILED, out.steps[i].state)
                assertEquals(tag, "boom", out.steps[i].detail)
                assertTrue(tag, out.steps.drop(i + 1).all { it.state == StepState.PENDING })
                assertTrue(tag, out.steps.take(i).all { it.state == StepState.DONE })
                assertTrue(tag, out.explanation!!.startsWith("Stopped at \"${step.label}\": "))
                assertTrue(tag, out.explanation!!.contains("(boom)"))
            }
        }
    }

    @Test fun pairingHandsOverToThePerson() {
        val dev = FakeDevice(none, waitAt = StepId.PAIR)
        val out = ConnectPlan.run(EMBEDDED_ONLY, LaunchVia.ADB, dev)
        assertEquals(ConnectStatus.WAITING_FOR_USER, out.status)
        assertEquals(StepId.PAIR, out.failedStep)
        assertEquals(StepState.WAITING, out.steps.first { it.id == StepId.PAIR }.state)
        assertEquals(listOf(StepId.DEV_OPTIONS, StepId.WIRELESS_DEBUG, StepId.PAIR), dev.ran)
        assertTrue(out.explanation!!.contains("type the 6-digit code into the notification"))
        // a pairing that could not even start says what to do instead
        val broke = ConnectPlan.run(EMBEDDED_ONLY, LaunchVia.ADB, FakeDevice(none, failAt = StepId.PAIR))
        assertEquals(ConnectStatus.FAILED, broke.status)
        assertTrue(broke.explanation!!.contains("Allow notifications"))
    }

    @Test fun anExecutorThatThrowsIsAFailedStepNotACrash() {
        val dev = object : ConnectExecutor {
            override fun facts() = none
            override fun run(step: StepId): StepResult = throw IllegalStateException("socket closed")
        }
        val out = ConnectPlan.run(SHIZUKU, LaunchVia.ADB, dev)
        assertEquals(ConnectStatus.FAILED, out.status)
        assertEquals(StepId.DEV_OPTIONS, out.failedStep)
        assertEquals("socket closed", out.steps[0].detail)
    }

    @Test fun everyStepHasAnExplanationNamingTheStep() {
        for (s in StepId.values()) for (w in listOf(true, false)) {
            val e = ConnectPlan.explain(s, "x", w)
            assertTrue("$s", e.startsWith("Stopped at \"${s.label}\": "))
            assertTrue("$s", e.length > 40)
        }
    }

    @Test fun theRunLogsEachStepAndNeverTheCode() {
        val log = ChannelLog()
        ConnectPlan.run(EMBEDDED_ONLY, LaunchVia.ADB, FakeDevice(none, failAt = StepId.ADB_CONNECT), log)
        val text = log.asText()
        assertTrue(text.contains("[connect] Developer options on: ok"))
        assertTrue(text.contains("Connect embedded adb (mDNS): FAILED boom"))
        assertFalse(text.contains("Round trip"))                                     // never reached
    }

    @Test fun launchViaParsing() {
        assertEquals(LaunchVia.ADB, LaunchVia.parse(null))
        assertEquals(LaunchVia.SHIZUKU, LaunchVia.parse("shizuku"))
        assertEquals(LaunchVia.ADB, LaunchVia.parse("nonsense"))
        assertNotNull(LaunchVia.values().firstOrNull { it.id == "adb" })
    }
}
