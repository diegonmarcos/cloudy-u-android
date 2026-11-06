package com.diegonmarcos.superapp.perms

import org.junit.Assert.assertEquals
import org.junit.Test

/** #1546 the request decision logic: the fine-location upgrade, the permanent-denial route and the Location switch. */
class PermsRequestFlowTest {
    private val fine = PermsRequest.FINE
    private val coarse = PermsRequest.COARSE

    private fun state(perm: String = fine, asked: Boolean = true, rationale: Boolean = false, auto: Boolean = true, sdk: Int = 36,
                      coarseGranted: Boolean = false, coarseRationale: Boolean = false) =
        PermsRequest.runtimeState(perm, granted = false, asked = asked, rationale = rationale, autoRequest = auto, sdk = sdk,
            coarseGranted = coarseGranted, coarseRationale = coarseRationale).state

    @Test fun `fine location with approximate granted is an upgrade the dialog answers, never a permanent denial`() {
        // the phone's case: asked before (an old fine-only request), no rationale, approximate allowed
        assertEquals(PermState.DENIED, state(coarseGranted = true))
        assertEquals(PermState.DENIED, state(coarseRationale = true))
        assertEquals(PermsRequest.Route.DIALOG, PermsRequest.route(PermEntry("fine_location", "Precise", "LOCATION", PermKind.RUNTIME,
            PermSection.RUNTIME, permission = fine), state(coarseGranted = true)))
    }

    @Test fun `the plain runtime rules are kept`() {
        assertEquals(PermState.GRANTED, PermsRequest.runtimeState(fine, true, true, false, true).state)
        assertEquals(PermState.DENIED, state(asked = false))
        assertEquals(PermState.DENIED, state(rationale = true))
        assertEquals(PermState.DENIED, state(perm = "android.permission.ACCESS_BACKGROUND_LOCATION", auto = false))
        assertEquals(PermState.PERMANENTLY_DENIED, state())
        assertEquals(PermState.PERMANENTLY_DENIED, state(perm = "android.permission.CAMERA", coarseGranted = true))
        // below API 31 there is no approximate / precise split
        assertEquals(PermState.PERMANENTLY_DENIED, state(sdk = 30, coarseGranted = true))
    }

    @Test fun `a denial that came back with no dialog opens the app settings page`() {
        val next = PermsRequest.after(mapOf(fine to false, coarse to true), { false }, elapsedMs = 80, locationOn = true)
        assertEquals(PermsRequest.Next.AppSettings(fine), next)
    }

    @Test fun `a denial the user tapped stays on the page`() {
        assertEquals(PermsRequest.Next.Done, PermsRequest.after(mapOf(fine to false), { false }, elapsedMs = 2500, locationOn = true))
        assertEquals(PermsRequest.Next.Done, PermsRequest.after(mapOf(fine to false), { true }, elapsedMs = 50, locationOn = true))
    }

    @Test fun `a location grant with the Location switch off opens the Location settings`() {
        assertEquals(PermsRequest.Next.LocationSettings, PermsRequest.after(mapOf(fine to true, coarse to true), { false }, 1500, locationOn = false))
        assertEquals(PermsRequest.Next.Done, PermsRequest.after(mapOf(fine to true, coarse to true), { false }, 1500, locationOn = true))
        assertEquals(PermsRequest.Next.Done, PermsRequest.after(mapOf("android.permission.CAMERA" to true), { false }, 1500, locationOn = false))
    }

    @Test fun `an empty or unknown-timing result never opens a page by itself`() {
        assertEquals(PermsRequest.Next.Done, PermsRequest.after(emptyMap(), { false }, 10, true))
        assertEquals(PermsRequest.Next.Done, PermsRequest.after(mapOf(fine to false), { false }, -1, true))
    }
}
