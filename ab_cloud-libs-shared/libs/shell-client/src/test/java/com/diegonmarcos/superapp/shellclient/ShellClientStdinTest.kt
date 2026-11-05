package com.diegonmarcos.superapp.shellclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/** #1512 The client half of the stdin door: bounded size before anything is sent, own timeout ceiling. */
class ShellClientStdinTest {
    @get:Rule val tmp = TemporaryFolder()

    private class T(val answer: String? = ShellWire.encode(ShellResult.Ok(0, "Success", "", "x"))) : ShellClient.Transport {
        var calls = 0; var extras: Map<String, String> = emptyMap(); var cmd = ""
        override fun call(method: String, cmd: String, extras: Map<String, String>): String? = null
        override fun callStdin(cmd: String, extras: Map<String, String>, stdin: File): String? {
            calls++; this.extras = extras; this.cmd = cmd; return answer
        }
    }

    private fun file(n: Int) = tmp.newFile().apply { writeBytes(ByteArray(n)) }

    @Test fun theSizeAndAClampedTimeoutTravelWithTheCall() {
        val t = T()
        val r = ShellClient(t).execStdin("pm install -S 64", file(64), timeoutMs = 10_000_000L)
        assertTrue(r is ShellResult.Ok)
        assertEquals("64", t.extras[ShellWire.K_STDIN_SIZE])
        assertEquals(ShellWire.STDIN_MAX_TIMEOUT_MS.toString(), t.extras[ShellWire.K_TIMEOUT])
    }

    @Test fun anEmptyInputNeverReachesTheBroker() {
        val t = T()
        assertTrue(ShellClient(t).execStdin("pm install -S 0", file(0)) is ShellResult.Denied)
        assertEquals(0, t.calls)
    }

    @Test fun anInputOverTheHardLimitNeverReachesTheBroker() {
        val t = T()
        val big = tmp.newFile()
        RandomAccessFile(big, "rw").use { it.setLength(ShellWire.STDIN_MAX_BYTES + 1) }   // sparse: no real disk
        val r = ShellClient(t).execStdin("pm install -S 1", big)
        assertTrue(r is ShellResult.Denied && r.reason.contains("limit"))
        assertEquals(0, t.calls)
    }

    @Test fun aMissingFileOrBlankCommandIsRefusedLocally() {
        val t = T()
        assertTrue(ShellClient(t).execStdin("pm install -S 5", File(tmp.root, "nope.apk")) is ShellResult.Failed)
        assertTrue(ShellClient(t).execStdin("  ", file(5)) is ShellResult.Denied)
        assertEquals(0, t.calls)
    }

    @Test fun aTransportWithoutTheDoorIsFailedNotAThrow() {
        assertTrue(ShellClient { _, _, _ -> null }.execStdin("pm install -S 5", file(5)) is ShellResult.Failed)
    }

    @Test fun theBrokersAnswersPassThrough() {
        assertEquals(ShellResult.NeedsArm, ShellClient(T(ShellWire.encode(ShellResult.NeedsArm))).execStdin("pm install -S 5", file(5)))
        val denied = ShellClient(T(ShellWire.encode(ShellResult.Denied("no stdin rule")))).execStdin("pm install -S 5", file(5))
        assertEquals(ShellResult.Denied("no stdin rule"), denied)
    }

    @Test fun theLimitsAreOneDefinition() {
        assertFalse(ShellWire.stdinProblem(1) != null)
        assertTrue(ShellWire.stdinProblem(0) != null)
        assertTrue(ShellWire.stdinProblem(ShellWire.STDIN_MAX_BYTES) == null)
        assertTrue(ShellWire.stdinProblem(ShellWire.STDIN_MAX_BYTES + 1) != null)
    }
}
