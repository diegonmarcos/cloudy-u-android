package com.diegonmarcos.superapp.adbdebug

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Each app that links the ADB shell presents ITS OWN identity (pairing label, key subject, ownership) and keeps its
 * live state to itself. The identity is derived from the host's build.json, so these tests read the real files of the
 * Store and the SuperApp and derive both, rather than restating either name.
 */
class AdbIdentityTest {

    private fun repo(): File {
        val r = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "aa_cloud-superapp/build.json").exists() }
        assumeTrue("not run from a full checkout", r != null)
        return r!!
    }

    private fun derived(app: String): AdbIdentity {
        val b = JSONObject(File(repo(), "$app/build.json").readText())
        // Nix-on-Droid and Termux declare no android.application_id (their build.json is a rootfs content-address input);
        // the lib then presents the running package, so a missing id derives from the name, never throws.
        return AdbIdentity.derive(b.getString("name"), b.optJSONObject("android")?.optString("application_id"), "unused.fallback")
    }

    @Test fun theStorePresentsAsCloudStoreAndNotAsTheSuperApp() {
        val store = derived("ac_cloud-store")
        assertEquals("cloud-store", store.deviceName)
        assertEquals("com.diegonmarcos.cloudstore", store.applicationId)
        assertTrue(store.certSubject.startsWith("CN=cloud-store,"))
        assertFalse("no superapp label may reach the Store's connection", store.deviceName.contains("superapp", ignoreCase = true))
        assertFalse(store.certSubject.contains("superapp", ignoreCase = true))
    }

    @Test fun theSuperAppKeepsItsOwnIdentity() {
        val sa = derived("aa_cloud-superapp")
        assertEquals("cloud-superapp", sa.deviceName)
        assertEquals("com.diegonmarcos.superapp", sa.applicationId)
        assertTrue(sa.certSubject.startsWith("CN=cloud-superapp,"))
    }

    @Test fun twoHostAppsNeverShareALabelOrASubject() {
        val store = derived("ac_cloud-store")
        val sa = derived("aa_cloud-superapp")
        assertNotEquals(store.deviceName, sa.deviceName)
        assertNotEquals(store.certSubject, sa.certSubject)
        assertNotEquals(store.applicationId, sa.applicationId)
    }

    @Test fun everyAppThatLinksTheLibDerivesADistinctLabel() {
        val apps = repo().listFiles { f -> f.isDirectory && Regex("^a[ac]_").containsMatchIn(f.name) && File(f, "build.json").exists() }.orEmpty()
            .filter { d -> Regex("""project\(\s*["']:libs:shizuku-adb-debug-tools["']\s*\)""").containsMatchIn(
                listOf("app/build.gradle", "build.gradle", "settings.gradle").map { File(d, it) }.filter { it.isFile }.joinToString("\n") { it.readText() }) }
        // #1512: only the SuperApp links this lib now (every other app is a shell-broker client); the discovery must still find it.
        assertTrue("the discovery found the SuperApp", apps.any { it.name == "aa_cloud-superapp" })
        val labels = apps.map { derived(it.name).deviceName }
        assertEquals("one label per app: $labels", labels.size, labels.toSet().size)
    }

    @Test fun aBlankDeclarationStillPresentsTheRunningAppNeverAnother() {
        val i = AdbIdentity.derive(null, "", "com.example.thing")
        assertEquals("com.example.thing", i.applicationId)
        assertEquals("thing", i.deviceName)
        assertEquals("cloud-store", AdbIdentity.derive(" cloud-store ", "com.diegonmarcos.cloudstore", "x.y").deviceName)
    }

    @Test fun anIdentityOwnsOnlyItsOwnPackage() {
        val store = AdbIdentity("cloud-store", "com.diegonmarcos.cloudstore")
        assertTrue(store.owns("com.diegonmarcos.cloudstore"))
        assertFalse(store.owns("com.diegonmarcos.superapp"))
    }

    @Test fun theKeyAliasAndPrefsStayStableSoExistingPairingsSurvive() {
        val i = AdbIdentity("cloud-superapp", "com.diegonmarcos.superapp")
        assertEquals("identity", i.keyAlias)
        assertEquals("adb_shell", i.prefsName)
    }

    @Test fun stateIsScopedToThePackageItWasMeasuredFor() {
        val scope = AdbScope<String>()
        var made = 0
        val storeState = scope.get("com.diegonmarcos.cloudstore") { made++; "store-channel-up" }
        assertEquals("store-channel-up", storeState)
        assertSame(storeState, scope.get("com.diegonmarcos.cloudstore") { made++; "again" })
        assertEquals("a repeat read by the owner reuses the value", 1, made)
        assertNull("the SuperApp never reads the Store's state", scope.peek("com.diegonmarcos.superapp"))
        assertEquals("store-channel-up", scope.peek("com.diegonmarcos.cloudstore"))
    }

    @Test fun anotherPackageGetsItsOwnStateNeverTheCachedOne() {
        val scope = AdbScope<String>()
        scope.get("com.diegonmarcos.superapp") { "superapp-channel-down" }
        val store = scope.get("com.diegonmarcos.cloudstore") { "store-channel-up" }
        assertEquals("store-channel-up", store)
        assertNull("the SuperApp's slot is gone, not served to the Store", scope.peek("com.diegonmarcos.superapp"))
    }

    @Test fun currentTouchesOnlyTheHeldValueAndClearEmptiesIt() {
        val scope = AdbScope<StringBuilder>()
        scope.current { error("nothing held yet") }
        val sb = scope.get("a.b") { StringBuilder() }
        scope.current { it.append("x") }
        assertEquals("x", sb.toString())
        scope.clear()
        assertNull(scope.peek("a.b"))
    }

    @Test fun noSharedAdbCodeHardCodesAnAppLabel() {
        val src = File(repo(), "ab_cloud-libs-shared/libs/shizuku-adb-debug-tools/src/main/java")
        val literal = Regex("\"[^\"\\n]*(CloudSuperApp|superapp-ad[b\\[])[^\"\\n]*\"")
        val hits = src.walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { n, l -> if (!l.trimStart().startsWith("*") && !l.trimStart().startsWith("//") && literal.containsMatchIn(l)) "${f.name}:${n + 1}" else null } }.toList()
        assertTrue("hard-coded superapp identity in the shared ADB code: $hits", hits.isEmpty())
    }
}
