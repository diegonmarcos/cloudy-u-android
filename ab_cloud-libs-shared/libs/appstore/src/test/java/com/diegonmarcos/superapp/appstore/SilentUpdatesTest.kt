package com.diegonmarcos.superapp.appstore

import android.content.pm.PackageInstaller
import com.diegonmarcos.superapp.updater.BootstrapInstall
import com.diegonmarcos.superapp.updater.InstallDiagnostics
import com.diegonmarcos.superapp.updater.install.SilentReadiness
import com.diegonmarcos.superapp.updater.install.SilentReadiness.Facts
import com.diegonmarcos.superapp.updater.install.SilentReadiness.Reason
import com.diegonmarcos.superapp.updater.install.SilentUpdate
import com.diegonmarcos.superapp.updater.install.StoreHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1675 silent fleet updates through public Android APIs only: session params, the declared permissions, the one installer
 * of record, the targetSdk rule, new-permission detection, the 30 s per-package spacing, the silent / prompt reason mapping
 * and the store's own self-update.
 */
class SilentUpdatesTest {

    private val store = SilentReadiness.INSTALLER_OF_RECORD
    private fun facts(
        deviceSdk: Int = 37, self: String = store, silentOn: Boolean = true, fleetSigned: Boolean = true,
        installed: Boolean = true, installer: String? = store, owner: String? = store, target: Int? = 35,
        newPerms: List<String> = emptyList(), wait: Long = 0,
    ) = Facts(deviceSdk, self, silentOn, fleetSigned, installed, installer, owner, target, newPerms, wait)

    // ── session params on API 31+ ──────────────────────────────────────────────────────────────────────────

    @Test fun everySessionOnApi31PlusAsksForNoUserAction() {
        for (sdk in 31..37) {
            val p = SilentUpdate.plan(sdk, silentOn = true, fleetSigned = true)
            assertEquals("API $sdk", PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED, p.requireUserAction)
            assertTrue(p.layer1)
        }
        assertNull("no such API before 31", SilentUpdate.plan(30, true, true).requireUserAction)
    }

    @Test fun updateOwnershipOn34PlusOnlyForTheInstallerOfRecord() {
        assertFalse(SilentUpdate.plan(33, true, true).requestOwnership)
        assertTrue(SilentUpdate.plan(34, true, true, installerOfRecord = true).requestOwnership)
        assertTrue(SilentUpdate.plan(37, true, true, installerOfRecord = true).requestOwnership)
        assertFalse("another app asking would take ownership away from Cloud Store",
            SilentUpdate.plan(37, true, true, installerOfRecord = false).requestOwnership)
    }

    @Test fun packageSourceIsStoreOn33Plus() {
        assertNull(SilentUpdate.plan(32, true, true).packageSource)
        assertEquals(PackageInstaller.PACKAGE_SOURCE_STORE, SilentUpdate.plan(33, true, true).packageSource)
        assertEquals(PackageInstaller.PACKAGE_SOURCE_STORE, SilentUpdate.plan(37, false, false).packageSource)
    }

    // ── permissions declared ───────────────────────────────────────────────────────────────────────────────

    private fun repo(rel: String): File {
        // the unit test runs in libs/appstore; the repo root is three levels up
        val f = File("../../../$rel")
        assertTrue("missing $rel (cwd ${File(".").absolutePath})", f.isFile)
        return f
    }

    @Test fun theStoreDeclaresBothInstallPermissionsAndTheHandoffReceiver() {
        val storeManifest = repo("ac_cloud-store/app/src/main/AndroidManifest.xml").readText()
        val lib = repo("ab_cloud-libs-shared/libs/updater/src/main/AndroidManifest.xml").readText()
        for (m in listOf(storeManifest, lib)) {
            assertTrue(m.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
            assertTrue(m.contains("android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION"))
        }
        assertTrue("every consumer can see Cloud Store", lib.contains("<package android:name=\"$store\""))
        assertFalse("#1502 Cloud Store declares no receiver: the hand-off is an activity start",
            Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(storeManifest, "").contains("<receiver"))
        val main = repo("ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt").readText()
        assertEquals("onCreate and onNewIntent accept a hand-off", 2, Regex("StoreHandoff\\.accept\\(this, intent\\)").findAll(main).count())
    }

    // ── installer of record ────────────────────────────────────────────────────────────────────────────────

    @Test fun oneInstallerOfRecordEveryOtherAppHandsOff() {
        assertEquals("com.diegonmarcos.cloudstore", store)
        assertEquals(SilentReadiness.Route.LOCAL, SilentReadiness.route(store, storeInstalled = true))
        assertEquals(SilentReadiness.Route.HANDOFF, SilentReadiness.route("com.diegonmarcos.superapp", storeInstalled = true))
        assertEquals(SilentReadiness.Route.HANDOFF, SilentReadiness.route("com.diegonmarcos.cloudmail", storeInstalled = true))
        assertEquals("bootstrap: no Cloud Store yet, install locally",
            SilentReadiness.route("com.diegonmarcos.superapp", storeInstalled = false), SilentReadiness.Route.LOCAL)
        assertEquals(Reason.HANDOFF, SilentReadiness.decide(facts(self = "com.diegonmarcos.superapp")).reason)
        // what the requesting app does: install itself only as Cloud Store or for bootstrap; a tap opens Cloud Store;
        // a background request is left to Cloud Store's own fleet pass (never a second installer)
        assertEquals(StoreHandoff.Action.INSTALL_HERE, StoreHandoff.action(SilentReadiness.Route.LOCAL, foreground = false))
        assertEquals(StoreHandoff.Action.OPEN_STORE, StoreHandoff.action(SilentReadiness.Route.HANDOFF, foreground = true))
        assertEquals(StoreHandoff.Action.LEAVE_TO_STORE, StoreHandoff.action(SilentReadiness.Route.HANDOFF, foreground = false))
    }

    @Test fun everyInstallPathReachesTheOneChoke() {
        val u = "ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater/"
        val installer = repo(u + "install/UpdateInstaller.kt").readText()
        val choke = installer.indexOf("StoreHandoff.handOff(context, targetPackage)")
        assertTrue("UpdateInstaller.install hands off before the gate", choke in 0 until installer.indexOf("InstallGate.serialised("))
        assertTrue(repo(u + "Updater.kt").readText().contains("StoreHandoff.handOff(context, packageName)"))
        for (f in listOf("install/InstallChannel.kt", "ApkInstallWorker.kt", "UpdateWorker.kt"))
            assertTrue("$f reports a hand-off instead of failing", repo(u + f).readText().contains("HandedToStore"))
    }

    @Test fun anAppInstalledElsewherePromptsOnceToTransferOwnership() {
        val adb = SilentReadiness.decide(facts(installer = "com.android.shell", owner = null))
        assertEquals(Reason.NOT_INSTALLER_OF_RECORD, adb.reason)
        assertTrue(adb.line.startsWith("will prompt: installed by com.android.shell"))
        assertEquals(Reason.NOT_INSTALLER_OF_RECORD, SilentReadiness.decide(facts(owner = "com.android.vending")).reason)
        assertEquals("after the transfer it is silent", Reason.SILENT, SilentReadiness.decide(facts()).reason)
    }

    // ── targetSdk rule ─────────────────────────────────────────────────────────────────────────────────────

    @Test fun targetSdkFloorPerAndroidVersion() {
        val want = mapOf(30 to 0, 31 to 29, 32 to 29, 33 to 30, 34 to 31, 35 to 33, 36 to 34, 37 to 35, 38 to 35)
        want.forEach { (sdk, floor) -> assertEquals("API $sdk", floor, SilentReadiness.minTargetSdk(sdk)) }
        assertEquals(SilentReadiness.FLEET_MIN_TARGET_SDK, SilentReadiness.minTargetSdk(SilentReadiness.NEWEST_DEVICE_SDK))
    }

    @Test fun aLowTargetSdkPromptsWithTheReason() {
        val v = SilentReadiness.decide(facts(target = 34))
        assertEquals(Reason.TARGET_SDK, v.reason)
        assertTrue(v.line, v.line.contains("targetSdk 34 is below 35"))
        assertEquals(Reason.SILENT, SilentReadiness.decide(facts(deviceSdk = 36, target = 34)).reason)
        assertEquals(Reason.TARGET_SDK, SilentReadiness.decide(facts(deviceSdk = 34, target = 28)).reason)
    }

    @Test fun theGuardPolicyFloorMatchesTheRuntimeRule() {
        val pol = org.json.JSONObject(repo("1_cicd/src/data/target-sdk-guard.json").readText())
        assertEquals(SilentReadiness.FLEET_MIN_TARGET_SDK, pol.getInt("floor"))
        assertEquals(SilentReadiness.NEWEST_DEVICE_SDK, pol.getInt("newest_device_sdk"))
    }

    // ── new dangerous permission ───────────────────────────────────────────────────────────────────────────

    @Test fun aNewDangerousPermissionIsDetectedAndNamed() {
        val dangerous = setOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO", "android.permission.READ_CONTACTS")
        val installed = setOf("android.permission.INTERNET", "android.permission.READ_CONTACTS")
        val candidate = installed + setOf("android.permission.CAMERA", "android.permission.WAKE_LOCK", "android.permission.RECORD_AUDIO")
        val fresh = SilentReadiness.newDangerousPermissions(installed, candidate) { it in dangerous }
        assertEquals(listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"), fresh)
        assertTrue("an already-held one is not new", SilentReadiness.newDangerousPermissions(installed, installed) { it in dangerous }.isEmpty())
        val v = SilentReadiness.decide(facts(newPerms = fresh))
        assertEquals(Reason.NEW_PERMISSION, v.reason)
        assertEquals("will prompt: this update asks for new permission(s): CAMERA, RECORD_AUDIO", v.line)
    }

    // ── 30 s per-package spacing ───────────────────────────────────────────────────────────────────────────

    @Test fun throttleWaitsOutTheThirtySecondWindow() {
        assertEquals(0L, SilentReadiness.throttleWaitMs(null, 1_000))
        assertEquals(30_000L, SilentReadiness.throttleWaitMs(1_000, 1_000))
        assertEquals(10_000L, SilentReadiness.throttleWaitMs(1_000, 21_000))
        assertEquals(0L, SilentReadiness.throttleWaitMs(1_000, 31_000))
        assertEquals(30_000L, SilentReadiness.throttleWaitMs(50_000, 1_000)) // a clock step back never waits longer than the window
    }

    @Test fun theQueueNeverCommitsTwoSessionsForOnePackageWithinTheWindow() {
        // A batch over a, a, b, a with 2 s per install; each commit waits what throttleWaitMs says, like UpdateInstaller.
        var now = 0L
        val last = HashMap<String, Long>()
        val commits = mutableListOf<Pair<String, Long>>()
        for (pkg in listOf("a", "a", "b", "a")) {
            now += SilentReadiness.throttleWaitMs(last[pkg], now)
            commits += pkg to now
            last[pkg] = now
            now += 2_000
        }
        commits.groupBy({ it.first }, { it.second }).values.forEach { times ->
            times.zipWithNext().forEach { (x, y) -> assertTrue("$x -> $y", y - x >= SilentReadiness.THROTTLE_MS) }
        }
        assertEquals("b is not held by a", 32_000L, commits[2].second)
        assertEquals(Reason.THROTTLE, SilentReadiness.decide(facts(wait = 5_000)).reason)
    }

    // ── silent / prompt reason mapping ─────────────────────────────────────────────────────────────────────

    @Test fun everyReasonMapsToOneLine() {
        assertEquals("silent", SilentReadiness.decide(facts()).line)
        val cases = mapOf(
            Reason.OLD_ANDROID to facts(deviceSdk = 30),
            Reason.TOGGLE_OFF to facts(silentOn = false),
            Reason.NOT_FLEET_SIGNED to facts(fleetSigned = false),
            Reason.HANDOFF to facts(self = "com.diegonmarcos.cloudnav"),
            Reason.FIRST_INSTALL to facts(installed = false, installer = null, owner = null),
            Reason.NOT_INSTALLER_OF_RECORD to facts(installer = "com.android.shell"),
            Reason.TARGET_SDK to facts(target = 33),
            Reason.NEW_PERMISSION to facts(newPerms = listOf("android.permission.CAMERA")),
            Reason.THROTTLE to facts(wait = 1),
        )
        cases.forEach { (want, f) ->
            val v = SilentReadiness.decide(f)
            assertEquals(want, v.reason)
            assertFalse(v.silent)
            assertTrue(v.line, v.line.startsWith("will prompt: ") && v.line.length > "will prompt: ".length + 5)
        }
        assertEquals(Reason.values().size, cases.size + 1)
    }

    @Test fun publicApisGoFirstWhenTheNativePathHolds() {
        assertTrue(SilentReadiness.prefersSession(SilentReadiness.decide(facts())))
        assertTrue("the session waits the throttle out", SilentReadiness.prefersSession(SilentReadiness.decide(facts(wait = 9))))
        assertFalse(SilentReadiness.prefersSession(SilentReadiness.decide(facts(installer = "com.android.shell"))))
        assertFalse(SilentReadiness.prefersSession(SilentReadiness.decide(facts(target = 28))))
        val fleet = repo("ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater/Fleet.kt").readText()
        assertTrue(fleet.contains("if (SilentReadiness.prefersSession(verdict)) listOf(SessionInstall, ShellInstall) else channels(ctx)"))
    }

    @Test fun anUpdateNeverUsesTheViewIntent() {
        assertTrue(BootstrapInstall.usesSession(installed = true))
        assertTrue("a first install is a session too", BootstrapInstall.usesSession(installed = false))
        val boot = repo("ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater/BootstrapInstall.kt").readText()
        assertFalse(boot.contains("ACTION_INSTALL_PACKAGE") || boot.contains("setDataAndType("))
    }

    /** The owner's bug: per-app Update with a cached APK opened Android's "Open with" chooser. It must enqueue a session. */
    @Test fun perAppUpdateWithACachedApkEnqueuesASession() {
        val a = "ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/"
        val row = repo(a + "StoreCloudFragment.kt").readText()
        assertTrue("Update is the queue install", Regex("\"update\" ->[^\\n]*StoreStages\\.INSTALL").containsMatchIn(row))
        assertFalse("no prompted (intent) install remains", row.contains("promptedInstall") || row.contains("BootstrapInstall.launch"))
        val stages = repo(a + "StoreStages.kt").readText()
        val install = stages.substring(stages.indexOf("fun install(ctx: Context, app: Fleet.App"))
        assertTrue("a cached APK is installed from the cache, in the queue",
            install.contains("actionableFor(ctx, app, remote)") && install.contains("installCached(ctx, app, e)"))
        assertTrue("a valid cached APK goes straight to install: the only fetch is the fallback when nothing is cached",
            Regex("val e = cached\\s*\\?: fetch\\(ctx, app\\)").containsMatchIn(install) &&
                Regex("fetch\\(").findAll(install.substring(0, install.indexOf("installCached(ctx, app, e)"))).count() == 1)
        assertTrue("the cached bytes are re-verified before the session",
            stages.substring(stages.indexOf("private fun installCachedGated")).let { it.contains("Fleet.cachedRelease(ctx, app)") })
        assertTrue("the queue runs one install at a time", stages.contains("StoreJobs.runner.install(app.pkg) { installCachedGated(ctx, app, e) }"))
        assertTrue("the queue's installer commits through Fleet.commit", repo(a + "FleetInstall.kt").readText().contains("Fleet.commit(ctx, app, apk)"))
        val u = "ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater/"
        assertTrue("Fleet.commit's session channel is the PackageInstaller session",
            repo(u + "install/InstallChannel.kt").readText().contains("UpdateInstaller(ctx).install(apk, app.pkg)"))
        // #1735 the session is created through InstallSessions.open (reap + cap backoff), whose port is the one createSession call
        assertTrue(repo(u + "install/UpdateInstaller.kt").readText().contains("InstallSessions.open(port"))
        assertTrue(repo(u + "install/InstallSessions.kt").readText().contains("installer.createSession("))
    }

    @Test fun theSessionOutcomeIsLogged() {
        assertEquals("silent", InstallDiagnostics.sessionOutcome(PackageInstaller.STATUS_SUCCESS, prompted = false))
        assertEquals("prompted", InstallDiagnostics.sessionOutcome(PackageInstaller.STATUS_SUCCESS, prompted = true))
        assertEquals("prompting", InstallDiagnostics.sessionOutcome(PackageInstaller.STATUS_PENDING_USER_ACTION, prompted = false))
        assertEquals("failed", InstallDiagnostics.sessionOutcome(PackageInstaller.STATUS_FAILURE_CONFLICT, prompted = true))
        assertEquals("2026-11-12 10:00:00 com.x failed — status 5 INSTALL_FAILED_x",
            InstallDiagnostics.sessionLine("2026-11-12 10:00:00", "com.x", "failed", "status 5 INSTALL_FAILED_x"))
    }

    // ── self-update of the store ───────────────────────────────────────────────────────────────────────────

    @Test fun theStoreUpdatesItselfSilently() {
        assertEquals(SilentReadiness.Route.LOCAL, SilentReadiness.route(store, storeInstalled = true))
        val self = SilentReadiness.decide(facts(self = store, installer = store, owner = store))
        assertEquals(Reason.SILENT, self.reason)
        assertTrue(SilentUpdate.plan(37, true, true, installerOfRecord = SilentReadiness.isInstallerOfRecord(store)).requestOwnership)
        assertEquals("a store first installed by adb asks once, then owns itself",
            Reason.NOT_INSTALLER_OF_RECORD, SilentReadiness.decide(facts(installer = "com.android.shell", owner = null)).reason)
    }
}
