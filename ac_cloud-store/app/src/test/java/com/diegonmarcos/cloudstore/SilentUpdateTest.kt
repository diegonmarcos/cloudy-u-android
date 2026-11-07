package com.diegonmarcos.cloudstore

import android.content.pm.PackageInstaller
import android.os.Build
import com.diegonmarcos.superapp.updater.install.SilentUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Native silent updates: the signer gate, the session settings the pure plan yields, and the card's honest lines. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SilentUpdateTest {
    private val fleet = setOf("aa11")
    private val NOT_REQUIRED = PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
    private val REQUIRED = PackageInstaller.SessionParams.USER_ACTION_REQUIRED
    private val S = Build.VERSION_CODES.S
    private val U = Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    // ── the signer gate ──────────────────────────────────────────────────────

    @Test fun anInstalledFleetAppIsEligible() {
        assertTrue(SilentUpdate.fleetSigned(fleet, fleet, installedPresent = true, candidate = null))
    }

    @Test fun anInstalledForeignAppIsNotEvenWithAFleetSignedCandidate() {
        assertFalse(SilentUpdate.fleetSigned(fleet, setOf("bb22"), installedPresent = true, candidate = fleet))
    }

    @Test fun aFreshInstallIsJudgedOnTheCandidateApk() {
        assertTrue(SilentUpdate.fleetSigned(fleet, null, installedPresent = false, candidate = fleet))
        assertFalse(SilentUpdate.fleetSigned(fleet, null, installedPresent = false, candidate = setOf("bb22")))
    }

    @Test fun anUnreadableSignerIsNeverEligible() {
        assertFalse(SilentUpdate.fleetSigned(fleet, null, installedPresent = true, candidate = fleet))
        assertFalse(SilentUpdate.fleetSigned(null, fleet, installedPresent = true, candidate = null))
        assertFalse(SilentUpdate.fleetSigned(emptySet(), emptySet(), installedPresent = true, candidate = null))
        assertFalse(SilentUpdate.fleetSigned(fleet, null, installedPresent = false, candidate = null))
    }

    // ── the session settings ─────────────────────────────────────────────────

    @Test fun aFleetAppOnAndroid14GetsNotRequiredOwnershipAndStoreSource() {
        val p = SilentUpdate.plan(U, silentOn = true, fleetSigned = true)
        assertTrue(p.layer1)
        assertEquals(NOT_REQUIRED, p.requireUserAction)
        assertTrue(p.requestOwnership)
        assertEquals(PackageInstaller.PACKAGE_SOURCE_STORE, p.packageSource)
        assertNull(p.why)
    }

    @Test fun android12AsksNoOwnershipAndSetsNoSource() {
        val p = SilentUpdate.plan(S, true, true)
        assertEquals(NOT_REQUIRED, p.requireUserAction)
        assertFalse("ownership is an Android 14 call", p.requestOwnership)
        assertNull("package source is an Android 13 call", p.packageSource)
    }

    @Test fun aForeignAppGetsTheConfirmedSessionEvenWithTheToggleOn() {
        val p = SilentUpdate.plan(U, silentOn = true, fleetSigned = false)
        assertFalse(p.layer1)
        assertEquals(REQUIRED, p.requireUserAction)
        assertFalse(p.requestOwnership)
        assertNotNull(p.why)
    }

    @Test fun theToggleOffGetsTheConfirmedSession() {
        val p = SilentUpdate.plan(U, silentOn = false, fleetSigned = true)
        assertFalse(p.layer1); assertEquals(REQUIRED, p.requireUserAction); assertFalse(p.requestOwnership)
    }

    @Test fun android11HasNoNativeSilentPathAndSetsNothingAboutIt() {
        val p = SilentUpdate.plan(Build.VERSION_CODES.R, true, true)
        assertFalse(p.layer1); assertNull(p.requireUserAction); assertFalse(p.requestOwnership)
        assertTrue(p.why!!.contains("Android 11"))
    }

    // ── the card ─────────────────────────────────────────────────────────────

    @Test fun theCardStatesTheLimitsPlainly() {
        val l = SilentUpdate.statusLines(U, true, null, "com.diegonmarcos.cloudstore")
        assertTrue(l[0].contains("API $U") && l[0].contains("available"))
        assertTrue(l[1].contains("none yet"))
        assertTrue(l.last().contains("ONE confirmation") && l.last().contains("Android 11") && l.last().contains("never installed silently"))
        assertTrue(SilentUpdate.statusLines(U, true, "com.diegonmarcos.cloudstore", "com.diegonmarcos.cloudstore")[1].contains("Cloud Store"))
        assertTrue(SilentUpdate.statusLines(Build.VERSION_CODES.R, true, null, "x")[0].contains("not available"))
    }
}
