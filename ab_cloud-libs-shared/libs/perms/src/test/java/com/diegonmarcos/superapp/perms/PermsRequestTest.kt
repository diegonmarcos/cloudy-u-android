package com.diegonmarcos.superapp.perms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1519 the combined location request (API 31+), the permanent-denial route, and which rows the dialog can still answer. */
class PermsRequestTest {
    private val fine = PermsRequest.FINE
    private val coarse = PermsRequest.COARSE

    private fun entry(id: String, perm: String, kind: PermKind = PermKind.RUNTIME, auto: Boolean = true) =
        PermEntry(id, id, "LOCATION", kind, PermSection.RUNTIME, permission = perm, autoRequest = auto)

    @Test fun `fine location is requested together with coarse on API 31 and above`() {
        assertEquals(listOf(fine, coarse), PermsRequest.expand(listOf(fine), 36))
        assertEquals(listOf(fine, coarse), PermsRequest.expand(listOf(fine), 31))
        assertEquals(listOf("p.A", fine, coarse, "p.B"), PermsRequest.expand(listOf("p.A", fine, "p.B"), 34))
    }

    @Test fun `the upgrade from approximate asks both in one call, and never duplicates`() {
        // the user once picked Approximate: only fine is still missing, the request still carries coarse
        assertEquals(setOf(fine, coarse), PermsRequest.expand(listOf(fine), 36).toSet())
        assertEquals(listOf(coarse, fine), PermsRequest.expand(listOf(coarse, fine, fine), 36))
    }

    @Test fun `below API 31 and for other permissions the request is left alone`() {
        assertEquals(listOf(fine), PermsRequest.expand(listOf(fine), 30))
        assertEquals(listOf(coarse), PermsRequest.expand(listOf(coarse), 36))
        assertEquals(listOf("p.A"), PermsRequest.expand(listOf("p.A", ""), 36))
    }

    @Test fun `denied and no rationale after an ask is a permanent denial`() {
        assertTrue(PermsRequest.permanentlyDenied(granted = false, asked = true, rationale = false))
        assertFalse(PermsRequest.permanentlyDenied(granted = false, asked = false, rationale = false))
        assertFalse(PermsRequest.permanentlyDenied(granted = false, asked = true, rationale = true))
        assertFalse(PermsRequest.permanentlyDenied(granted = true, asked = true, rationale = false))
    }

    @Test fun `a permanent denial routes to the settings page, a plain denial to the dialog`() {
        val f = entry("fine_location", fine)
        assertEquals(PermsRequest.Route.DIALOG, PermsRequest.route(f, PermState.DENIED))
        assertEquals(PermsRequest.Route.SETTINGS, PermsRequest.route(f, PermState.PERMANENTLY_DENIED))
        assertEquals(PermsRequest.Route.NONE, PermsRequest.route(f, PermState.GRANTED))
        assertEquals(PermsRequest.Route.SETTINGS, PermsRequest.route(entry("bg", "p.BG", auto = false), PermState.DENIED))
        assertEquals(PermsRequest.Route.SETTINGS, PermsRequest.route(entry("ov", "", PermKind.SPECIAL), PermState.DENIED))
    }

    @Test fun `only rows the dialog can still answer are put in the request`() {
        val f = entry("fine_location", fine); val c = entry("coarse_location", coarse)
        val state = mapOf("fine_location" to PermState.DENIED, "coarse_location" to PermState.PERMANENTLY_DENIED)
        assertEquals(listOf(f), PermsRequest.dialogable(listOf(f, c)) { PermStatus(state.getValue(it.id)) })
    }
}
