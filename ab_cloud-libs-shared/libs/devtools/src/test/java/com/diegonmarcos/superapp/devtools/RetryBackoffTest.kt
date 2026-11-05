package com.diegonmarcos.superapp.devtools

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1502 every retry / restart path waits at least 30 s, and the shared loops say so in their source. */
class RetryBackoffTest {
    @Test fun neverBelowTheFloor() {
        assertEquals(30_000L, RetryBackoff.next(0))
        assertEquals(30_000L, RetryBackoff.next(1_000))
        assertEquals(60_000L, RetryBackoff.next(30_000))
        for (f in 0..40) assertTrue("failures=$f", RetryBackoff.afterFailures(f) >= 30_000L)
        for (p in listOf(0L, 1L, 999L, 29_999L, 30_000L, 600_000L, Long.MAX_VALUE / 4)) assertTrue(RetryBackoff.next(p) in 30_000L..RetryBackoff.MAX_MS)
    }

    private fun src(rel: String): String {
        val f = listOf(File("src/main/java/$rel"), File("../shizuku-adb-debug-tools/src/main/java/$rel"), File("libs/devtools/src/main/java/$rel")).first { it.exists() }
        return f.readText().lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/*") }.joinToString("\n")
    }

    @Test fun logPipeRestartHasNoShortWait() {
        val s = src("com/diegonmarcos/superapp/devtools/LogPipe.kt")
        assertFalse("a 1 s restart is a hot loop", s.contains("backoffMs = 1_000L"))
        assertTrue(s.contains("RetryBackoff.MIN_MS") && s.contains("Thread.sleep(backoffMs)"))
        assertTrue("an unread pipe stops", s.contains("IDLE_STOP_MS"))
    }

    @Test fun everySleepInTheSharedLoopsIsAFloorOrABackoff() {
        val sleeps = Regex("""Thread\.sleep\(([^)]*)\)""")
        for (p in listOf("com/diegonmarcos/superapp/devtools/LogPipe.kt", "com/diegonmarcos/superapp/adbdebug/HostShell.kt", "com/diegonmarcos/superapp/adbdebug/HostShellService.kt", "com/diegonmarcos/superapp/adbdebug/WdKeeperService.kt")) {
            val s = runCatching { src(p) }.getOrNull() ?: continue
            for (m in sleeps.findAll(s)) {
                val arg = m.groupValues[1]
                assertTrue("$p sleeps $arg", arg.contains("backoffMs") || arg.contains("RETRY_MIN_MS") || arg.contains("nextDelayMs") || arg.contains("PERIOD_MS") || arg.contains("SCREEN_OFF_MS"))
            }
        }
    }
}
