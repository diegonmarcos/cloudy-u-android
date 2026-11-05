package com.diegonmarcos.superapp.adbdebug

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1487 battery guards: no sticky keep-alive, no fixed-period loop, backoff and screen-off rules. */
class KeepAlivePolicyTest {
    private fun src(name: String): String {
        val rel = "src/main/java/com/diegonmarcos/superapp/adbdebug/$name"
        return listOf(File(rel), File("libs/shizuku-adb-debug-tools/$rel"), File("ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/$rel"))
            .first { it.exists() }.readText()
    }

    @Test fun stopsWhenNothingToKeep() {
        assertFalse(KeepAlivePolicy.needed(false, false))
        assertTrue(KeepAlivePolicy.needed(true, false))
        assertTrue(KeepAlivePolicy.needed(false, true))
    }

    @Test fun backsOffAndCaps() {
        assertEquals(60_000L, KeepAlivePolicy.nextDelayMs(0, true))
        assertEquals(120_000L, KeepAlivePolicy.nextDelayMs(1, true))
        assertEquals(KeepAlivePolicy.MAX_MS, KeepAlivePolicy.nextDelayMs(50, true))
    }

    @Test fun screenOffSleepsLongAndNeverDials() {
        assertEquals(KeepAlivePolicy.SCREEN_OFF_MS, KeepAlivePolicy.nextDelayMs(0, false))
        assertFalse(KeepAlivePolicy.mayReconnect(false))
    }

    @Test fun keepAliveServicesAreNotSticky() {
        for (f in listOf("HostShellService.kt", "WdKeeperService.kt")) {
            val s = src(f)
            assertFalse("$f must not be START_STICKY", s.contains("START_STICKY") && s.lines().any { it.contains("return START_STICKY") })
            assertTrue("$f must read the screen state", s.contains("isInteractive"))
        }
        assertFalse(src("HostShellService.kt").contains("PERIOD_MS"))
    }

    /** #1502 a host that may not run in the background (Account always, Store off Wi-Fi or with auto-update off) has no job for the service. */
    @Test fun noBackgroundNoService() {
        assertTrue(KeepAlivePolicy.needed(debugServerAllowed = true, channelEnabled = true, backgroundAllowed = true))
        assertFalse(KeepAlivePolicy.needed(debugServerAllowed = true, channelEnabled = true, backgroundAllowed = false))
        assertTrue(KeepAlivePolicy.needed(debugServerAllowed = false, channelEnabled = true))   // the default keeps the old behaviour
    }
}
