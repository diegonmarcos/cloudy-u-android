package com.diegonmarcos.superapp.adbdebug

import com.diegonmarcos.superapp.adbdebug.ChannelMode.AUTO
import com.diegonmarcos.superapp.adbdebug.ChannelMode.EMBEDDED_ONLY
import com.diegonmarcos.superapp.adbdebug.ChannelMode.LOCAL_SERVER
import com.diegonmarcos.superapp.adbdebug.ChannelMode.SHIZUKU
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1709 the automatic chain: single-flight, bounded retries, the embedded session always closed, the real outcome recorded. */
class ServerAutoStartTest {

    private val base = ChannelFacts(LOCAL_SERVER, 1L, serverPort = 38099, devOptions = true, wirelessDebug = true, adbPaired = true)

    /** The device: each step flips the fact the next reads; [launchFails] makes LAUNCH_SERVER fail (or fail n times). */
    private class Dev(var f: ChannelFacts, var launchFailures: Int = 0, val block: CountDownLatch? = null) : ConnectExecutor {
        val ran = ArrayList<StepId>()
        override fun facts() = f
        override fun run(step: StepId): StepResult {
            synchronized(ran) { ran += step }
            block?.await(5, TimeUnit.SECONDS)
            when (step) {
                StepId.ADB_CONNECT -> f = f.copy(adbConnected = true)
                StepId.LAUNCH_SERVER -> {
                    if (launchFailures > 0) { launchFailures--; return StepResult(false, "server did not come up (rc 1)") }
                    f = f.copy(serverRunning = true)
                }
                StepId.CLOSE_EMBEDDED -> f = f.copy(adbConnected = false)
                else -> {}
            }
            return StepResult(true, "ok")
        }
        fun embeddedOpened() = ran.contains(StepId.ADB_CONNECT)
    }

    private fun core(sleeps: MutableList<Long> = ArrayList()) = AutoStartCore(sleep = { sleeps += it }, backoffMs = listOf(10L, 20L))

    @Test fun serverDownConnectsLaunchesProbesAndClosesEmbedded() {
        val dev = Dev(base); val rec = ArrayList<String>()
        val out = core().run(LOCAL_SERVER, dev, { rec += it })!!
        assertEquals(ConnectStatus.SUCCESS, out.status)
        assertEquals(listOf(StepId.ADB_CONNECT, StepId.LAUNCH_SERVER, StepId.VERIFY, StepId.CLOSE_EMBEDDED), dev.ran)
        assertFalse(dev.f.adbConnected); assertTrue(dev.f.serverRunning)
        assertTrue(rec.single().startsWith("attempt 1/3: ok"))
    }

    @Test fun serverUpOpensNoEmbeddedSession() {
        val dev = Dev(base.copy(serverRunning = true)); val rec = ArrayList<String>()
        val out = core().run(LOCAL_SERVER, dev, { rec += it })!!
        assertEquals(ConnectStatus.SUCCESS, out.status)
        assertFalse(dev.embeddedOpened()); assertEquals(listOf(StepId.VERIFY), dev.ran)
    }

    @Test fun launchFailsEveryTimeEmbeddedIsClosedAndTheStateSaysWhy() {
        val dev = Dev(base, launchFailures = 99); val rec = ArrayList<String>(); val sleeps = ArrayList<Long>()
        val out = core(sleeps).run(LOCAL_SERVER, dev, { rec += it })!!
        assertEquals(ConnectStatus.FAILED, out.status); assertEquals(StepId.LAUNCH_SERVER, out.failedStep)
        assertFalse("embedded must not stay open", dev.f.adbConnected)
        assertTrue(dev.ran.last() == StepId.CLOSE_EMBEDDED)
        assertEquals(listOf(10L, 20L), sleeps)                                   // bounded: 3 attempts, backoff between them
        assertEquals(3, rec.count { it.startsWith("attempt ") })
        assertTrue(rec.any { it.contains("server did not come up (rc 1)") })       // the real reason is in the state
        assertTrue(rec.last().contains("closed"))
    }

    @Test fun aTransientLaunchFailureIsRetriedAndSucceeds() {
        val dev = Dev(base, launchFailures = 1); val rec = ArrayList<String>()
        val out = core().run(LOCAL_SERVER, dev, { rec += it })!!
        assertEquals(ConnectStatus.SUCCESS, out.status)
        assertTrue(rec[0].startsWith("attempt 1/3")); assertTrue(rec[1].startsWith("attempt 2/3: ok"))
        assertFalse(dev.f.adbConnected)
    }

    @Test fun aStepThatNeedsThePersonIsNotRetried() {
        var calls = 0
        val dev = object : ConnectExecutor {
            override fun facts() = base.copy(adbPaired = false)
            override fun run(step: StepId): StepResult { calls++; return StepResult(false, "code needed", needsUser = true) }
        }
        val out = core().run(LOCAL_SERVER, dev, { })!!
        assertEquals(ConnectStatus.WAITING_FOR_USER, out.status); assertEquals(StepId.PAIR, out.failedStep)
        assertEquals(1, calls)
    }

    @Test fun singleFlightNeverLaunchesInParallel() {
        val gate = CountDownLatch(1)
        val dev = Dev(base, block = gate); val c = core()
        var first: ConnectOutcome? = null
        val t = Thread { first = c.run(LOCAL_SERVER, dev, { }) }
        t.start()
        val deadline = System.currentTimeMillis() + 3000
        while (!c.inFlight() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(c.inFlight())
        val second = c.run(LOCAL_SERVER, Dev(base), { })
        assertNull("a second launch started while one was running", second)
        gate.countDown(); t.join(5000)
        assertNotNull(first); assertFalse(c.inFlight())
        assertEquals(1, dev.ran.count { it == StepId.LAUNCH_SERVER })
    }

    @Test fun shizukuProfileClosesEmbeddedAfterStartingTheService() {
        val dev = Dev(base.copy(mode = SHIZUKU, shizukuInstalled = true, shizukuGranted = true)); val rec = ArrayList<String>()
        val out = core().run(SHIZUKU, dev, { rec += it })!!
        assertEquals(ConnectStatus.SUCCESS, out.status)
        assertTrue(dev.ran.contains(StepId.SHIZUKU_START)); assertFalse(dev.f.adbConnected)
    }

    @Test fun embeddedAndAutoLeaveTheSessionOpen() {
        for (m in listOf(EMBEDDED_ONLY, AUTO)) {
            val dev = Dev(base.copy(mode = m))
            assertEquals(ConnectStatus.SUCCESS, core().run(m, dev, { })!!.status)
            assertTrue("$m", dev.f.adbConnected); assertFalse(StepId.CLOSE_EMBEDDED in dev.ran)
        }
    }

    @Test fun cooldownByOutcome() {
        assertEquals(0L, LocalServerAutoStart.cooldownMs(ConnectStatus.SUCCESS))
        assertTrue(LocalServerAutoStart.cooldownMs(ConnectStatus.FAILED) in 1L..60_000L)
        assertTrue(LocalServerAutoStart.cooldownMs(ConnectStatus.WAITING_FOR_USER) > LocalServerAutoStart.cooldownMs(ConnectStatus.FAILED))
    }

    /** The launcher is automatic: embedded first, Shizuku only after it, whatever the old stored choice says. */
    @Test fun launcherOrderIsEmbeddedThenShizuku() {
        assertEquals(listOf("embedded-adb", "shizuku"), LaunchViaPrefs.orderNames(listOf("shizuku", "embedded-adb")) { it })
        assertEquals(listOf("embedded-adb", "shizuku"), LaunchViaPrefs.orderNames(listOf("embedded-adb", "shizuku")) { it })
    }

    /** Local server is the default profile of every app; a client app keeps the owner route behind the local layers. */
    @Test fun defaultProfileIsLocalServer() {
        assertEquals(LOCAL_SERVER, ChannelModePrefs.default())
        val ladder = listOf("embedded-adb", "local-server", "superapp-bridge", "shizuku")
        assertEquals(listOf("local-server", "embedded-adb"), ChannelSelector.execOrder(LOCAL_SERVER, ladder, ownsServer = true))
        assertEquals(listOf("local-server", "embedded-adb", "superapp-bridge", "shizuku"), ChannelSelector.execOrder(LOCAL_SERVER, ladder, ownsServer = false))
    }

    @Test fun bootstrapStateNoLongerSaysOnlyTheButton() {
        assertFalse(AdbShellBootstrap.NOT_STARTED.contains("only from its Restart button"))
    }
}
