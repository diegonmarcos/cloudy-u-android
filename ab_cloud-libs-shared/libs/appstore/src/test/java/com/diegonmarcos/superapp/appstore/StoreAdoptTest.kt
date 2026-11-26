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
        assertEquals(2, rows.size)
        fun has(r: String, vararg items: String) = items.forEach { assertTrue("$it in row", r.contains("Item.$it")) }
        has(rows[0], "CHECK", "INSTALL", "DOWNLOAD", "UPDATE")
        assertTrue("batch row keeps its order", listOf("CHECK", "INSTALL", "DOWNLOAD", "UPDATE").map { rows[0].indexOf("Item.$it") }.let { it == it.sorted() })
        // row 2, in the owner's order: Auto update, Up Wifi only, Play Protect, Adopt all, ADB
        has(rows[1], "AUTO_UPDATE", "WIFI_ONLY", "PLAY_PROTECT", "ADOPT_ALL"); assertTrue(rows[1].contains("ChannelButton("))
        assertTrue("row 2 order", listOf("Item.AUTO_UPDATE", "Item.WIFI_ONLY", "Item.PLAY_PROTECT", "Item.ADOPT_ALL", "ChannelButton(").map { rows[1].indexOf(it) }.let { it == it.sorted() })
        assertTrue("Adopt all is omitted where the page has none", rows[1].contains("verbs.adoptAll?.let"))
        assertTrue("Adopt all keeps its full name as description", rows[1].contains("R.string.store_bar_adopt_all)"))
        assertTrue("ADB detail is not drawn on the page", !bar.contains("ADB_DETAIL") && !bar.contains("StoreCaption(ctx.getString(R.string.store_bar_adb"))
        assertTrue("ADB detail stays reachable: description + long-press sheet to the ADB Status page",
            bar.contains("description = detail") && bar.contains("val detail = ch?.let { ControlStatus.label(it) }") && bar.contains("onLongClick = detail?.let") &&
            bar.contains("ADB_SHEET") && bar.contains("store_bar_adb_status_page") && bar.contains("StoreShell.arm(ctx.applicationContext) }"))
        assertTrue("the sheet is Compose (no dialog, no View UI)", !Regex("AlertDialog|setPositiveButton|Dialog\\(").containsMatchIn(bar))
        assertTrue("no full-width Adopt row any more", !src("StoreCloudFragment.kt").contains("StoreBar.Button(\"Adopt all"))
    }

    /** #1774 Adopt all adopts the fleet LIB APKs too: they are fleet apps of kind "lib" in the same baked manifest the pass reads. */
    @Test fun adoptAllCoversEveryInstallableLibApk() {
        fun repo(rel: String) = listOf(rel, "../$rel", "../../$rel", "../../../$rel", "../../../../$rel", "../../../../../$rel").map(::File).first { it.exists() }
        val fleet = org.json.JSONObject(repo("aa_cloud-superapp/data/constellation-fleet.json").readText())
        val apps = fleet.getJSONArray("apps").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val pkgOf = apps.associate { it.getString("id") to it.getString("package") }
        val kind = apps.associate { it.getString("id") to it.optString("kind", "app") }
        val groups = fleet.getJSONArray("groups").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.associateBy { it.getString("id") }
        fun members(id: String) = groups.getValue(id).getJSONArray("members").let { a -> (0 until a.length()).map { a.getString(it) } }
        val libs = members("libs")
        assertEquals("the 17 Libs (lib-json-canvas joined, #1932)", 17, libs.size)
        for (id in libs) assertTrue("$id is an installable lib APK in the manifest", kind[id] == "lib" && pkgOf.getValue(id).isNotEmpty())
        // Lite-ML: the members that are real modules (in the manifest) are installable; the rest are reference rows with no package.
        val liteInstallable = members("libs-l-ml").filter { it in pkgOf }
        assertTrue(liteInstallable.isNotEmpty()); for (id in liteInstallable) assertEquals("lib", kind[id])
        assertTrue("Tiny-ML has no installable package", members("libs-t-ml").none { it in pkgOf })
        // The pass reads the WHOLE baked fleet (every kind), not one tab, and adopts exactly what is installed.
        val frag = listOf("src/main/java/com/diegonmarcos/superapp/appstore/StoreCloudFragment.kt",
            "ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreCloudFragment.kt").map(::File).first { it.isFile }.readText()
        assertTrue(frag.contains("StoreAdopt.adopt(ctx, fleet.map { it.pkg })"))
        assertTrue(frag.contains("private val fleet by lazy { Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64) }"))
        val adopt = listOf("src/main/java/com/diegonmarcos/superapp/appstore/StoreAdopt.kt",
            "ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreAdopt.kt").map(::File).first { it.isFile }.readText()
        assertTrue("no kind filter in the pass", !adopt.contains(".kind") && !adopt.contains("\"lib\""))
        // and every lib package is a legal `pm set-installer` target
        for (id in libs + liteInstallable) assertEquals("pm set-installer ${pkgOf.getValue(id)} $store", StoreAdopt.command(pkgOf.getValue(id)))
    }

    // ── #1792 the in-place reinstall fallback ──
    private val refusalOut = "Exception occurred while executing 'set-installer':\njava.lang.SecurityException: Caller does not have same cert as old installer package com.google.android.packageinstaller\n\tat com.android.server.pm.PackageManagerService.setInstallerPackageName"
    private val fleet = setOf("a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90")
    private fun refused(): StoreAdopt.Result = StoreAdopt.resolve("cld.termux.nix.boot", "com.google.android.packageinstaller", refusalOut, "com.google.android.packageinstaller")

    @Test fun theOldInstallerCertRefusalIsReadWhole() {
        val r = refused()
        assertEquals(State.FAILED, r.state)
        assertTrue(r.reason, r.reason.contains("old installer package com.google.android.packageinstaller"))
        assertTrue(StoreAdopt.isRefusal(r, refusalOut))
    }

    @Test fun onlyAnAndroidRefusalTriggersTheFallback() {
        assertTrue(!StoreAdopt.isRefusal(StoreAdopt.resolve("p.q", "x", null, "x"), null))                                   // ADB down
        assertTrue(!StoreAdopt.isRefusal(StoreAdopt.resolve("p.q", "x", "Unknown command: set-installer", "x"), "Unknown command: set-installer"))
        val broker = "denied by the shell broker: no rule"
        assertTrue(!StoreAdopt.isRefusal(StoreAdopt.resolve("p.q", "x", broker, "x"), broker))
        val gone = "Failure [unknown package]"
        assertTrue(!StoreAdopt.isRefusal(StoreAdopt.resolve("p.q", "x", gone, "x"), gone))
        assertTrue(!StoreAdopt.isRefusal(StoreAdopt.resolve("p.q", store, "", store), ""))                                     // already ours
        assertTrue("ran but did not stick", StoreAdopt.isRefusal(StoreAdopt.resolve("p.q", "x", "", "x"), ""))
    }

    @Test fun sameCertificateAndAReleaseReinstalls() {
        val d = StoreAdopt.decide(refused(), true, fleet, fleet, hasRelease = true)
        assertEquals(StoreAdopt.Fallback.REINSTALL, d.fallback)
        assertTrue(d.reason.contains("reinstalled in place through Cloud Store's own PackageInstaller session"))
    }

    @Test fun aDifferentSignatureStaysFailedWithThatReason() {
        val other = setOf("ffff0000ffff0000ffff0000ffff0000ffff0000ffff0000ffff0000ffff0000")
        val d = StoreAdopt.decide(refused(), true, other, fleet, hasRelease = true)
        assertEquals(StoreAdopt.Fallback.NONE, d.fallback)
        assertTrue(d.reason, d.reason.contains("signed with a different certificate") && d.reason.contains("old installer package"))
    }

    @Test fun unknownCertificatesNoReleaseOrNoRefusalDoNothing() {
        assertEquals(StoreAdopt.Fallback.NONE, StoreAdopt.decide(refused(), true, null, fleet, true).fallback)
        assertEquals(StoreAdopt.Fallback.NONE, StoreAdopt.decide(refused(), true, fleet, null, true).fallback)
        assertEquals(StoreAdopt.Fallback.NONE, StoreAdopt.decide(refused(), true, fleet, fleet, hasRelease = false).fallback)
        assertTrue(StoreAdopt.decide(refused(), true, fleet, fleet, false).reason.contains("no release APK"))
        val r = refused()
        assertEquals(r.reason, StoreAdopt.decide(r, false, fleet, fleet, true).reason)
    }

    @Test fun theReinstallIsJudgedByTheReadBack() {
        val why = refused().reason
        assertEquals(State.ADOPTED, StoreAdopt.afterReinstall("p.q", why, null, store).state)
        val pending = StoreAdopt.afterReinstall("p.q", why, null, "com.google.android.packageinstaller")
        assertEquals(State.FAILED, pending.state); assertTrue(pending.reason.contains("reads back as com.google.android.packageinstaller"))
        val noStart = StoreAdopt.afterReinstall("p.q", why, "the release (versionCode 1) is older", null)
        assertEquals(State.FAILED, noStart.state); assertTrue(noStart.reason.contains("did not start"))
    }

    /** The check needs no ADB: with the shell down a package Cloud Store already owns counts as adopted; only the others wait. */
    @Test fun withTheShellDownTheCheckStillReadsTheInstaller() {
        val owned = StoreAdopt.withoutShell("p.q", StoreAdopt.STORE, "ADB shell: down")
        assertEquals(State.ALREADY, owned.state)
        val other = StoreAdopt.withoutShell("p.r", "com.android.shell", "ADB shell: down")
        assertEquals(State.ADB_DOWN, other.state)
        assertEquals("ADB shell: down", other.reason)
        assertEquals(State.ADB_DOWN, StoreAdopt.withoutShell("p.s", null, "x").state)
    }
}
