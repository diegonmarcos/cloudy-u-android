package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.appstore.StoreAdopt.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1744 the adopt command, its result parsing, the read-back and the row marker; rows carry no yellow line, the detail keeps the text. */
class StoreAdoptTest {
    private val store = "com.diegonmarcos.cloudstore"

    @Test fun commandIsTheOneAllowlistedShape() {
        assertEquals("pm set-installer com.diegonmarcos.cloudmail $store", StoreAdopt.command("com.diegonmarcos.cloudmail"))
        assertNull(StoreAdopt.command("x; rm -rf /"))
        assertNull(StoreAdopt.command(""))
    }

    @Test fun readBackParsesInstallers() {
        val m = StoreAdopt.parseInstallers("package:com.a.b  installer=com.android.shell\npackage:com.c.d  installer=null\npackage:com.e.f  installer=$store\nnoise\n")
        assertEquals("com.android.shell", m["com.a.b"]); assertNull(m["com.c.d"]); assertTrue("com.c.d" in m); assertEquals(store, m["com.e.f"])
        assertEquals(listOf("com.a.b", "com.c.d"), StoreAdopt.needing(m, listOf("com.e.f", "com.c.d", "com.a.b", "com.gone")))
    }

    @Test fun setResultsAreParsed() {
        assertEquals(State.ADB_DOWN, StoreAdopt.parseSetResult(null).first)
        assertNull(StoreAdopt.parseSetResult("").first)
        assertNull(StoreAdopt.parseSetResult("Success").first)
        assertEquals(State.UNSUPPORTED, StoreAdopt.parseSetResult("Unknown command: set-installer").first)
        assertEquals(State.FAILED, StoreAdopt.parseSetResult("java.lang.SecurityException: not allowed").first)
        assertEquals(State.FAILED, StoreAdopt.parseSetResult("denied by the shell broker: no rule").first)
        assertEquals(State.FAILED, StoreAdopt.parseSetResult("Failure [unknown package]").first)
    }

    @Test fun resolveTrustsTheReadBackNotTheCommand() {
        assertEquals(State.ALREADY, StoreAdopt.resolve("p.q", store, null, store).state)
        assertEquals(State.ADOPTED, StoreAdopt.resolve("p.q", "com.android.shell", "", store).state)
        assertEquals(State.FAILED, StoreAdopt.resolve("p.q", "com.android.shell", "", "com.android.shell").state)   // ran, but nothing changed
        assertEquals(State.ADB_DOWN, StoreAdopt.resolve("p.q", null, null, null).state)
        assertEquals(State.UNSUPPORTED, StoreAdopt.resolve("p.q", null, "Unknown command", null).state)
    }

    @Test fun rowMarkerIsOneSmallToken() {
        assertEquals("", StoreAdopt.marker(null))
        assertEquals(" · silent", StoreAdopt.marker("silent"))
        assertEquals(" · prompts", StoreAdopt.marker("will prompt: installed by an unknown source: Android asks once to move it to Cloud Store, then updates are silent"))
        assertEquals(" · prompts", StoreAdopt.marker("will prompt: targetSdk 33 is below 35"))
    }

    // source-level guard (no Compose host in this module): the row draws no Next update line, the detail view keeps it
    @Test fun rowsDrawNoYellowLineAndTheDetailKeepsTheText() {
        val f = listOf("src/main/java/com/diegonmarcos/superapp/appstore/StoreCloudFragment.kt",
            "ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreCloudFragment.kt").map(::File).first { it.isFile }
        val s = f.readText()
        assertTrue("no store-silent row line", !s.contains("store-silent:") && !s.contains("SilentArea"))
        assertTrue("detail keeps the full text", s.contains("store-detail-next-update:") && s.contains("\"Next update: \$line\""))
        assertTrue("marker in the status line", s.contains("StoreAdopt.marker("))
        // every tab's package rows are the one FleetRow, so no tab can draw its own line
        assertEquals(1, Regex("""StoreRow\.Item\(app\.label""").findAll(s).count())
    }

    /** #1744 the shell prints a header line and the cause on the next one; the reason is the cause, classified, never the bare header. */
    @Test fun refusalReasonIsTheRealCauseNotTheTruncatedHeader() {
        val sec = "Exception occurred while executing 'set-installer':\njava.lang.SecurityException: Caller uid 2000 is not allowed to change the installer of p.q\n\tat com.android.server.pm.X.y(X.java:1)"
        val (st, why) = StoreAdopt.parseSetResult(sec)
        assertEquals(State.FAILED, st)
        assertTrue(why, why.contains("SecurityException") && why.contains("not allowed to change") && !why.startsWith("Exception occurred"))
        val own = StoreAdopt.parseSetResult("Exception occurred while executing 'set-installer':\njava.lang.IllegalArgumentException: Package p.q is owned by com.other (update owner)").second
        assertTrue(own, own.contains("update ownership") && own.contains("com.other"))
        val sys = StoreAdopt.parseSetResult("Exception occurred while executing 'set-installer':\njava.lang.SecurityException: Cannot set installer of system app p.q").second
        assertTrue(sys, sys.startsWith("system or privileged package"))
        assertEquals("package not installed", StoreAdopt.parseSetResult("Failure [unknown package]").second)
        assertTrue(StoreAdopt.causeOf("Exception occurred while executing 'x':\nCaused by: java.lang.IllegalStateException: boom").startsWith("java.lang.IllegalStateException"))
    }

    @Test fun failureLinesListEveryRefusedPackageWithItsReason() {
        val rs = listOf(StoreAdopt.Result("b.b", State.FAILED, "why b", null), StoreAdopt.Result("a.a", State.UNSUPPORTED, "why a", null),
            StoreAdopt.Result("c.c", State.ADOPTED, "ok", store), StoreAdopt.Result("d.d", State.ALREADY, "ok", store))
        assertEquals(listOf("a.a: why a", "b.b: why b"), StoreAdopt.failureLines(rs))
    }

    // source-level guards (no Compose host here): Cloud Store itself is in the pass; toolbar layout; nothing dropped
    @Test fun storeItselfIsAlwaysAdoptedAndToolbarLayoutHolds() {
        val d = "ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/"
        fun src(n: String) = listOf(File("src/main/java/com/diegonmarcos/superapp/appstore/$n"), File(d + n)).first { it.isFile }.readText()
        assertTrue("Store in the pass", src("StoreAdopt.kt").contains("(pkgs + STORE).distinct()"))
        val bar = src("StoreBar.kt")
        val rows = bar.substringAfter("Column(modifier.testTag(TAG)) {").substringBefore("if (verbs.disabledReason").split("            Row {").drop(1)
        assertEquals(3, rows.size)
        fun has(r: String, vararg items: String) = items.forEach { assertTrue("$it in row", r.contains("Item.$it")) }
        has(rows[0], "CHECK", "INSTALL", "DOWNLOAD", "UPDATE")
        assertTrue("Auto update is not on the batch row", !rows[0].contains("AUTO_UPDATE"))
        has(rows[1], "AUTO_UPDATE", "WIFI_ONLY"); assertTrue(rows[1].contains("ChannelButton("))
        has(rows[2], "PLAY_PROTECT", "ADOPT_ALL")
        assertTrue("ADB detail is not drawn on the page", !bar.contains("ADB_DETAIL") && !bar.contains("StoreCaption(ctx.getString(R.string.store_bar_adb"))
        assertTrue("ADB detail stays reachable: description + long-press sheet to the ADB Status page",
            bar.contains("description = detail") && bar.contains("val detail = ch?.let { ControlStatus.label(it) }") && bar.contains("onLongClick = detail?.let") &&
            bar.contains("ADB_SHEET") && bar.contains("store_bar_adb_status_page") && bar.contains("StoreShell.arm(ctx.applicationContext) }"))
        assertTrue("the sheet is Compose (no dialog, no View UI)", !Regex("AlertDialog|setPositiveButton|Dialog\\(").containsMatchIn(bar))
        assertTrue("no full-width Adopt row any more", !src("StoreCloudFragment.kt").contains("StoreBar.Button(\"Adopt all"))
    }
}
