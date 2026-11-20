package com.diegonmarcos.superapp.configs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1833 the Security tab's pure rules: the accessibility list it writes, which installed services are the fleet's and which tile
 * shows them (Keyboard, SuperApp and Vault always, when installed), old library builds, stale entries, and each tile's one-line
 * reading (what was read and the value).
 */
class SecurityControlsTest {
    private val kb = "com.diegonmarcos.cloudkeyboard/com.diegonmarcos.cloudkeyboard.grabber.GrabberSelectionService"
    private val sa = "com.diegonmarcos.superapp/com.diegonmarcos.superapp.system.LockScreenAccessibilityService"
    private val vault = "com.diegonmarcos.cloudvault/com.x8bit.bitwarden.Accessibility.AccessibilityService"
    private val oldLib = "com.diegonmarcos.cloudlib.gitsync/com.diegonmarcos.cloudlib.gitsync.GitSyncAccessibilityService"

    private fun svc(c: String, app: String, label: String = app, old: String = "") =
        SecurityControls.Svc(c, c.substringBefore('/'), label, app, old)

    @Test fun shortAndLongFormsAreOneComponent() {
        assertEquals(kb, SecurityControls.normalize("com.diegonmarcos.cloudkeyboard/.grabber.GrabberSelectionService"))
        assertEquals(kb, SecurityControls.normalize(kb))
    }

    @Test fun turningOnAddsOnceAndKeepsTheOthers() {
        val before = "com.other.app/.Svc:com.diegonmarcos.cloudkeyboard/.grabber.GrabberSelectionService"
        val on = SecurityControls.edit(before, kb, true)
        assertEquals(listOf("com.other.app/com.other.app.Svc", kb), on)
        assertEquals(listOf("com.other.app/com.other.app.Svc", kb, vault), SecurityControls.edit(on.joinToString(":"), vault, true))
    }

    @Test fun turningOffRemovesOnlyThatService() {
        val list = listOf("com.other.app/com.other.app.Svc", kb, vault).joinToString(":")
        assertEquals(listOf("com.other.app/com.other.app.Svc", vault), SecurityControls.edit(list, kb, false))
        assertEquals(emptyList<String>(), SecurityControls.edit(kb, kb, false))
        assertEquals(emptyList<String>(), SecurityControls.parse(null))
        assertEquals(emptyList<String>(), SecurityControls.parse("null"))
    }

    @Test fun fleetPackagesAreOursAndTheVendoredOnes() {
        assertTrue(SecurityControls.fleetPackage("com.diegonmarcos.superapp"))
        assertTrue(SecurityControls.fleetPackage("com.diegonmarcos.cloudlib.gitsync"))
        assertTrue("the vendored GitSync clone counts as the fleet's", SecurityControls.fleetPackage("com.viscouspot.gitsync"))
        assertFalse(SecurityControls.fleetPackage("com.other.app"))
        assertTrue(SecurityControls.libPackage("com.diegonmarcos.cloudlib.gitsync"))
        assertFalse(SecurityControls.libPackage("com.diegonmarcos.cloudkeyboard"))
    }

    /** Rule 16 parity: the three apps the old tab listed still appear when installed, through a declared tile or a dynamic one. */
    @Test fun keyboardSuperAppAndVaultAppearWhenInstalled() {
        val all = listOf(svc(sa, "Cloud SuperApp"), svc(kb, "Cloud Keyboard"), svc(vault, "Cloud Vault"),
            svc("com.other.app/com.other.app.Svc", "Other"))
        // the apps group declares Keyboard and SuperApp tiles (a11y_package); every other fleet service gets its own tile
        val dyn = SecurityControls.fleetOnly(all, setOf("com.diegonmarcos.cloudkeyboard", "com.diegonmarcos.superapp"))
        assertEquals(listOf(vault), dyn.map { it.component })
        val shown = dyn.map { it.pkg }.toSet() + setOf("com.diegonmarcos.cloudkeyboard", "com.diegonmarcos.superapp")
        listOf(kb, sa, vault).forEach { assertTrue(it, it.substringBefore('/') in shown) }
        // nothing adopted: every fleet service is its own tile, in label order; a foreign app never
        assertEquals(listOf("Cloud Keyboard", "Cloud SuperApp", "Cloud Vault"), SecurityControls.fleetOnly(all, emptySet()).map { it.appLabel })
    }

    @Test fun anAppWithTwoServicesNamesEach() {
        val a = svc("com.diegonmarcos.x/com.diegonmarcos.x.A", "Cloud X", "Grabber")
        val b = svc("com.diegonmarcos.x/com.diegonmarcos.x.B", "Cloud X", "Lock")
        assertEquals("Cloud X: Grabber", SecurityControls.tileLabel(a, listOf(a, b)))
        assertEquals("Cloud X", SecurityControls.tileLabel(a, listOf(a)))
    }

    /** The owner's phone: an old lib APK still installed as "Cloud Library" with an accessibility service. */
    @Test fun oldLibraryBuildsAreNamedAndCurrentOnesAreNot() {
        val catalog = mapOf("com.diegonmarcos.cloudlib.feed" to "cloud-lib-feed")
        val v = SecurityControls.libVerdict("com.diegonmarcos.cloudlib.gitsync", "Cloud Library", catalog)
        assertTrue(v, v.startsWith("old library build: com.diegonmarcos.cloudlib.gitsync"))
        assertTrue(v, v.contains("not a shipped module"))
        val renamed = SecurityControls.libVerdict("com.diegonmarcos.cloudlib.feed", "Cloud Library", catalog)
        assertTrue(renamed, renamed.contains("installed as 'Cloud Library', the fleet names it 'cloud-lib-feed'"))
        assertEquals("", SecurityControls.libVerdict("com.diegonmarcos.cloudlib.feed", "cloud-lib-feed", catalog))
        assertEquals("an app is never an old library", "", SecurityControls.libVerdict("com.diegonmarcos.superapp", "Cloud Library", catalog))
        assertTrue(SecurityControls.serviceLine(false, true, v).startsWith("old library build"))
        assertTrue(SecurityControls.serviceLine(false, true, v).contains("tap uninstalls"))
    }

    @Test fun staleEntriesAreFleetComponentsNoInstalledServiceAnswersTo() {
        val list = listOf(sa, oldLib, "com.other.app/.Svc").joinToString(":")
        assertEquals(listOf(oldLib), SecurityControls.stale(list, setOf(sa)))
        assertTrue(SecurityControls.stale(sa, setOf(sa)).isEmpty())
    }

    @Test fun eachTileSaysWhatItReadAndTheValue() {
        assertEquals(true, SecurityControls.flag("adb_wifi_enabled", "1").state)
        assertEquals("Settings.Global adb_wifi_enabled=1", SecurityControls.flag("adb_wifi_enabled", "1").detail)
        assertEquals(false, SecurityControls.flag("adb_enabled", null).state)
        assertNull(SecurityControls.flag("adb_enabled", "2").state)
        assertEquals("AccessibilityManager: bound; Settings.Secure enabled_accessibility_services: listed",
            SecurityControls.serviceLine(true, true))
    }

    @Test fun theAdbTileIsTheBrokerAndNamesTheTransport() {
        val p = SecurityControls.shellLine(false, "none", "Cloud C3 Webserver missing", "ADB: Wireless debugging is off", "")
        assertEquals(false, p.state)
        assertTrue(p.detail, p.detail.contains("not armed: Cloud C3 Webserver missing"))
        assertTrue(p.detail, p.detail.contains("adb transport (status-strip dot): ADB: Wireless debugging is off"))
        assertEquals(true, SecurityControls.shellLine(true, "local", "", "ADB: connected", "127.0.0.1:5555").state)
    }

    @Test fun theEdgeMenuIsTheStripsOnScreenAndSaysWhyNot() {
        assertEquals(true, SecurityControls.edgeLine(2, 2, true, true, true).state)
        val off = SecurityControls.edgeLine(0, 0, true, false, false)
        assertEquals(false, off.state)
        assertTrue(off.detail, off.detail.contains("switch ON but no strip on screen (no overlay permission)"))
        assertTrue(SecurityControls.edgeLine(0, 0, false, true, false).detail.contains("switch OFF"))
    }

    @Test fun vaultAutofillIsEitherMechanismAndSaysWhich() {
        val pkg = "com.diegonmarcos.cloudvault"
        assertEquals(true, SecurityControls.vaultLine(pkg, true, true, null, null).state)
        val af = SecurityControls.vaultLine(pkg, true, false, "$pkg/com.x8bit.bitwarden.Autofill", null)
        assertEquals(true, af.state)
        assertTrue(af.detail, af.detail.startsWith("via autofill framework"))
        val none = SecurityControls.vaultLine(pkg, false, false, "com.google.android.gms/.Autofill", null)
        assertEquals(false, none.state)
        assertTrue(none.detail, none.detail.contains("no Vault service installed"))
        assertTrue(SecurityControls.vaultLine(pkg, true, false, null, "SecurityException").detail.contains("unreadable"))
    }
}
