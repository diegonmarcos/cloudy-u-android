package com.diegonmarcos.superapp.shellbroker

import com.diegonmarcos.superapp.shellbroker.Allowlist.Decision
import com.diegonmarcos.superapp.shellclient.ShellResult
import com.diegonmarcos.superapp.shellclient.ShellWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/** #1512 The stdin door: size limits, the install allowlist, spool hygiene, audit. */
class StdinDoorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val json = """{"callers":{
      "app.store":{"allow":[{"id":"r","re":"^echo shell-ok$"}],"stdin":[
        {"id":"i","re":"^pm install( -[a-z])* -S [0-9]+$","maxBytes":1000},
        {"id":"w","re":"^(pm|cmd package) install-write -S [0-9]+ [0-9]+ [\\w.-]+ -$","maxBytes":1000}]},
      "app.plain":{"allow":[{"id":"r","re":"^echo shell-ok$"}]}}}"""
    private val list = Allowlist.parse(json)

    private class Chan(val body: (String, File?) -> String? = { _, _ -> "Success\n\n__SB_EXIT__0\n" }) : BrokerChannel {
        val ran = ArrayList<String>(); var stdinSeen: ByteArray? = null
        override fun name() = "fake"
        override fun ready() = true
        override fun run(cmd: String, stdin: File?): String? { ran += cmd; stdinSeen = stdin?.readBytes(); return body(cmd, stdin) }
    }

    private fun core(ch: Chan, audit: AuditLog = AuditLog()) = BrokerCore({ list }, ch, audit)
    private fun input(n: Int) = ByteArrayInputStream(ByteArray(n) { (it % 251).toByte() })
    private fun spool() = tmp.newFolder()

    @Test fun anInstallStreamsTheInputAndIsAudited() {
        val ch = Chan(); val audit = AuditLog(); val sp = spool()
        val r = core(ch, audit).execStdin("app.store", "pm install -r -d -S 600", input(600), 600, 5_000, sp)
        assertEquals(ShellResult.Ok(0, "Success\n", "", "fake"), r)
        assertEquals(600, ch.stdinSeen!!.size)
        assertTrue(ch.ran.single().startsWith("pm install -r -d -S 600 2>&1; printf"))   // the broker merges stderr itself
        val e = audit.entries().single()
        assertEquals("app.store", e.caller); assertEquals("ok", e.outcome)
        assertTrue(e.cmd, e.cmd.contains("<stdin 600 B>"))
        assertEquals("the spool file is deleted", 0, sp.listFiles()!!.size)
    }

    @Test fun aClientSpelledMergeIsNotDoubled() {
        val ch = Chan()
        core(ch).execStdin("app.store", "pm install -r -S 10 2>&1", input(10), 10, 5_000, spool())
        assertEquals(1, Regex("2>&1").findAll(ch.ran.single()).count())
    }

    @Test fun aSplitWriteIsAllowed() {
        val ch = Chan()
        assertTrue(core(ch).execStdin("app.store", "pm install-write -S 5 1234 split1-x.apk -", input(5), 5, 5_000, spool()) is ShellResult.Ok)
        assertTrue(core(ch).execStdin("app.store", "cmd package install-write -S 5 1234 base.apk -", input(5), 5, 5_000, spool()) is ShellResult.Ok)
    }

    @Test fun anInputOverTheRuleCapIsDeniedBeforeAnyRead() {
        val ch = Chan()
        val r = core(ch).execStdin("app.store", "pm install -S 1001", input(1001), 1001, 5_000, spool())
        assertTrue(r is ShellResult.Denied && r.reason.contains("limit"))
        assertTrue(ch.ran.isEmpty())
    }

    @Test fun theHardCapAndEmptyInputAreDeniedWhateverTheRule() {
        val big = Allowlist.parse(json.replace("\"maxBytes\":1000", "\"maxBytes\":9000000000000"))
        val d = big.decideStdin("app.store", "pm install -S ${ShellWire.STDIN_MAX_BYTES + 1}", ShellWire.STDIN_MAX_BYTES + 1)
        assertTrue(d is Decision.Deny)
        assertTrue(big.decideStdin("app.store", "pm install -S 0", 0) is Decision.Deny)
        assertTrue(list.decideStdin("app.store", "pm install -S 1", -1) is Decision.Deny)
        assertTrue(big.decideStdin("app.store", "pm install -S ${ShellWire.STDIN_MAX_BYTES}", ShellWire.STDIN_MAX_BYTES) is Decision.Allow)
    }

    @Test fun aDeclaredSizeThatDiffersFromTheInputIsDenied() {
        val ch = Chan()
        assertTrue(core(ch).execStdin("app.store", "pm install -S 500", input(100), 100, 5_000, spool()) is ShellResult.Denied)
        assertTrue(ch.ran.isEmpty())
    }

    @Test fun anInputLongerOrShorterThanDeclaredNeverRuns() {
        val ch = Chan(); val sp = spool()
        val longer = core(ch).execStdin("app.store", "pm install -S 100", input(150), 100, 5_000, sp)
        val shorter = core(ch).execStdin("app.store", "pm install -S 100", input(40), 100, 5_000, sp)
        assertTrue(longer is ShellResult.Denied); assertTrue(shorter is ShellResult.Denied)
        assertTrue(ch.ran.isEmpty()); assertEquals(0, sp.listFiles()!!.size)
    }

    @Test fun onlyInstallVerbsMayStream() {
        for (c in listOf("pm uninstall -S 5", "sh -S 5", "cat -S 5", "pm install -S 5; id", "pm install -S 5 | cat", "pm install -S 5 > /x",
                         "pm install -S 5 /sdcard/x.apk", "pm install-write -S 5 1 ../x -", "echo shell-ok", "pm install -S 5\nid"))
            assertTrue(c, list.decideStdin("app.store", c, 5) is Decision.Deny)
    }

    @Test fun aCallerWithoutStdinRulesMayStreamNothing() {
        val d = list.decideStdin("app.plain", "pm install -S 5", 5)
        assertTrue(d is Decision.Deny && d.reason.contains("may not stream"))
        assertTrue(list.decideStdin("app.stranger", "pm install -S 5", 5) is Decision.Deny)
    }

    @Test fun deniedNeverTouchesTheSpoolOrTheChannel() {
        val ch = Chan(); val audit = AuditLog(); val sp = spool()
        val r = core(ch, audit).execStdin("app.plain", "pm install -S 5", input(5), 5, 5_000, sp)
        assertTrue(r is ShellResult.Denied)
        assertTrue(ch.ran.isEmpty()); assertEquals(0, sp.listFiles()!!.size)
        assertEquals("denied", audit.entries().single().outcome)
    }

    @Test fun aWedgedChannelTimesOutAndStillCleansUp() {
        val ch = Chan { _, _ -> Thread.sleep(8_000); null }
        val sp = spool()
        val r = core(ch).execStdin("app.store", "pm install -S 5", input(5), 5, 5_000, sp)
        assertTrue(r is ShellResult.Failed && r.timedOut)
        assertEquals(0, sp.listFiles()!!.size)
    }

    @Test fun theTimeoutCeilingOfAStdinCallIsItsOwn() {
        assertEquals(ShellWire.STDIN_MAX_TIMEOUT_MS, ShellWire.clampStdinTimeout(Long.MAX_VALUE))
        assertEquals(5_000L, ShellWire.clampStdinTimeout(1L))
        assertTrue(ShellWire.STDIN_MAX_TIMEOUT_MS > ShellWire.MAX_TIMEOUT_MS)
    }

    @Test fun oneStdinTransferAtATime() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val ch = Chan { _, _ -> gate.await(5, java.util.concurrent.TimeUnit.SECONDS); "ok\n__SB_EXIT__0\n" }
        val c = core(ch); val sp = spool()
        val t = Thread { c.execStdin("app.store", "pm install -S 5", input(5), 5, 20_000, sp) }.apply { start() }
        Thread.sleep(300)
        val second = c.execStdin("app.store", "pm install -S 5", input(5), 5, 5_000, sp)
        gate.countDown(); t.join()
        assertTrue(second is ShellResult.Failed && second.reason.startsWith("busy"))
    }

    @Test fun aStaleSpoolFileIsSweptButAFreshOneIsKept() {
        val sp = spool()
        val old = File(sp, "stdin-old.bin").apply { writeText("x"); setLastModified(1_000L) }
        val fresh = File(sp, "stdin-fresh.bin").apply { writeText("x") }
        val c = BrokerCore({ list }, Chan(), AuditLog(), clock = { System.currentTimeMillis() })
        c.execStdin("app.store", "pm install -S 5", input(5), 5, 5_000, sp)
        assertFalse(old.exists()); assertTrue(fresh.exists())
    }

    @Test fun theShippedAllowlistLetsOnlyTheInstallersStream() {
        val real = Allowlist.parse(File("src/main/assets/shell-allowlist.json").readText())
        val sz = 40_000_000L
        for (pkg in listOf("com.diegonmarcos.cloudstore", "com.diegonmarcos.cloudaccount", "com.diegonmarcos.clouddrive")) {
            assertTrue(pkg, real.decideStdin(pkg, "pm install -r -d -S $sz", sz) is Decision.Allow)
            assertTrue(pkg, real.decideStdin(pkg, "pm install-write -S $sz 17 split1-x.apk -", sz) is Decision.Allow)
            assertTrue(pkg, real.decideStdin(pkg, "pm install -r -d -S $sz 2>&1", sz) is Decision.Allow)
            assertTrue(pkg, real.decideStdin(pkg, "pm install -r -d -S 1", sz) is Decision.Deny)
            assertTrue(pkg, real.decideStdin(pkg, "sh -c id", sz) is Decision.Deny)
            assertTrue(pkg, real.decideStdin(pkg, "pm install -S ${600L * 1024 * 1024}", 600L * 1024 * 1024) is Decision.Deny)
        }
        for (pkg in listOf("com.diegonmarcos.cloudwebserver", "com.diegonmarcos.cloudbrowser", "com.diegonmarcos.cloudc3", "cld.termux",
                           "cld.termux.nix", "com.diegonmarcos.cloudtoolssmallllms", "some.stranger"))
            assertTrue(pkg, real.decideStdin(pkg, "pm install -r -d -S $sz", sz) is Decision.Deny)
    }

    @Test fun theInstallVerbsOfTheSessionAreAllowedForTheInstallersWithAMergedStderr() {
        val real = Allowlist.parse(File("src/main/assets/shell-allowlist.json").readText())
        for (pkg in listOf("com.diegonmarcos.cloudstore", "com.diegonmarcos.cloudaccount", "com.diegonmarcos.clouddrive"))
            for (c in listOf("pm install-create -r -d -S 123 2>&1", "pm install-commit 17 2>&1", "pm install-abandon 17 2>&1", "echo shell-ok"))
                assertTrue("$pkg $c", real.decide(pkg, c) is Decision.Allow)
        // the merge is the ONLY redirect
        assertTrue(real.decide("com.diegonmarcos.cloudstore", "pm install-commit 17 2>/sdcard/x") is Decision.Deny)
        assertTrue(real.decide("com.diegonmarcos.cloudstore", "pm install-commit 17 2>&1; id") is Decision.Deny)
    }
}
