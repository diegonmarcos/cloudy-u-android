package com.diegonmarcos.cloudwebserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1724 onStartCommand ALWAYS calls startForeground first, on every path; refusals are caught; the sender skips what cannot go foreground. */
class KeepAliveFlowTest {
    private class FakeHost(val allowed: Boolean = true, val foregroundWorks: Boolean = true) : KeepAliveFlow.Host {
        val calls = ArrayList<String>()
        override fun enterForeground(): Boolean { calls += "foreground"; return foregroundWorks }
        override fun serviceAllowed(): Boolean { calls += "allowed?"; return allowed }
        override fun stopServers() { calls += "stopServers" }
        override fun stopSelfNow() { calls += "stopSelf" }
        override fun startServers() { calls += "startServers" }
    }

    private fun run(h: FakeHost, action: String?): Int = KeepAliveFlow.onStartCommand(h, action)

    @Test fun `normal start goes foreground first then binds, sticky`() {
        val h = FakeHost(); assertEquals(KeepAliveFlow.STICKY, run(h, null))
        assertEquals(listOf("foreground", "allowed?", "startServers"), h.calls)
    }

    @Test fun `keep-alive pref off goes foreground then stops, no servers`() {
        val h = FakeHost(allowed = false); assertEquals(KeepAliveFlow.NOT_STICKY, run(h, null))
        assertEquals("foreground", h.calls.first()); assertTrue("stopSelf" in h.calls); assertFalse("startServers" in h.calls)
        assertTrue(h.calls.indexOf("foreground") < h.calls.indexOf("stopSelf"))
    }

    @Test fun `stop action goes foreground first, releases the ports then stops`() {
        val h = FakeHost(); assertEquals(KeepAliveFlow.NOT_STICKY, run(h, KeepAliveFlow.ACTION_STOP))
        assertEquals(listOf("foreground", "stopServers", "stopSelf"), h.calls)
    }

    @Test fun `a refused foreground start never binds and stops itself, no throw`() {
        val h = FakeHost(foregroundWorks = false); assertEquals(KeepAliveFlow.NOT_STICKY, run(h, null))
        assertEquals(listOf("foreground", "stopSelf"), h.calls)
    }

    @Test fun `every path starts with startForeground`() {
        for (action in listOf(null, KeepAliveFlow.ACTION_STOP, "other.ACTION"))
            for (allowed in listOf(true, false)) for (fg in listOf(true, false)) {
                val h = FakeHost(allowed, fg); run(h, action)
                assertEquals("action=$action allowed=$allowed fg=$fg", "foreground", h.calls.first())
            }
    }

    @Test fun `guarded catches every refusal and records its real reason`() {
        val seen = ArrayList<String>()
        assertTrue(KeepAliveFlow.guarded({}, { seen += it }))
        assertTrue(seen.isEmpty())
        assertFalse(KeepAliveFlow.guarded({ throw SecurityException("no FGS permission") }, { seen += it }))
        assertTrue(seen.single().contains("missing permission") && seen.single().contains("no FGS permission"))
        seen.clear()
        class ForegroundServiceStartNotAllowedException(m: String) : IllegalStateException(m)
        assertFalse(KeepAliveFlow.guarded({ throw ForegroundServiceStartNotAllowedException("bg") }, { seen += it }))
        assertTrue(seen.single().contains("refused the foreground start"))
        seen.clear()
        assertFalse(KeepAliveFlow.guarded({ throw IllegalStateException("x") }, { seen += it }))
        assertTrue(seen.single().startsWith("IllegalStateException"))
    }

    @Test fun `sender skips when keep-alive is off`() {
        val d = KeepAliveFlow.decideStart(false, 36, true, KeepAliveFlow.Origin.ACTIVITY)
        assertFalse(d.start); assertNull(d.reason)
    }

    @Test fun `sender skips from the background on Android 12 plus and says why`() {
        val d = KeepAliveFlow.decideStart(true, 36, false, KeepAliveFlow.Origin.CORE)
        assertFalse(d.start); assertNotNull(d.reason)
    }

    @Test fun `sender starts from the foreground, from boot, and below Android 12`() {
        assertTrue(KeepAliveFlow.decideStart(true, 37, true, KeepAliveFlow.Origin.ACTIVITY).start)
        assertTrue(KeepAliveFlow.decideStart(true, 37, false, KeepAliveFlow.Origin.BOOT).start)
        assertTrue(KeepAliveFlow.decideStart(true, 30, false, KeepAliveFlow.Origin.CORE).start)
    }
}
