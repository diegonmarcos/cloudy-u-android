package com.diegonmarcos.superapp.appstore

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * #1487 battery: nothing in the fleet may start or bind the Nix terminal (cld.termux.nix) except Cloud Store's one-shot
 * post-install unpack hand-off and the migration backup (both in this lib, both user-initiated). A background caller
 * kept it up for 90 h a week. Also: the terminal never auto-starts at boot, and its keep-alive stays off.
 */
class NoForeignTerminalStartTest {
    private fun root(): File? {
        var d: File? = File("").absoluteFile
        while (d != null) { if (File(d, "ab_cloud-libs-shared").isDirectory) return d; d = d.parentFile }
        return null
    }

    private val allowed = setOf("UnpackAfterInstall.kt", "SharedUidMigration.kt", "UnpackTestReceiver.kt", "StoreExec.kt")
    private val start = Regex("""(startService|startForegroundService|bindService)\s*\(""")
    private val terminal = Regex("""cld\.termux|com\.termux\.nix|termux\.app\.(TermuxService|RunCommandService)|terminal\.SESSION|TerminalSessions""")

    @Test fun onlyAllowlistedFilesStartTheTerminal() {
        val r = root(); assumeTrue(r != null)
        val bad = r!!.walkTopDown().onEnter { it.name !in setOf("build", ".git", "z_archive", "node_modules", "ac_cloud-nix-on-droid", "ac_cloud-termux", "ac_cloud-myterminal") }
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path && it.name !in allowed }
            .filter { f -> val t = f.readText(); start.containsMatchIn(t) && terminal.containsMatchIn(t) }
            .map { it.path }.toList()
        assertTrue("these start/bind the terminal: $bad", bad.isEmpty())
    }

    @Test fun terminalDoesNotAutostartOrKeepAlive() {
        val r = root(); assumeTrue(r != null)
        val b = File(r, "ac_cloud-nix-on-droid/build.json"); assumeTrue(b.exists())
        val t = b.readText()
        assertTrue("mesh_sshd.start_on_boot must be false", Regex("\"start_on_boot\"\\s*:\\s*false").containsMatchIn(t))
        val svc = File(r, "ac_cloud-nix-on-droid/app/src/main/java/com/termux/app/TermuxService.java").readText()
        assertTrue("TermuxService must stop when idle", svc.contains("scheduleIdleStop"))
        assertTrue("TermuxService must stay non-sticky", svc.contains("return Service.START_NOT_STICKY") && !svc.contains("START_STICKY;"))
    }
}
