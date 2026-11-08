package com.diegonmarcos.superapp.fleetconfigs

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The opt-in Configs tab registry (#1443): registration order, idempotency, and that it never shadows a base page. */
class FleetConfigsExtensionsTest {

    @Before fun clean() = FleetConfigsExtensions.clearForTest()
    @After fun reset() = FleetConfigsExtensions.clearForTest()

    @Test fun base_pages_are_general_perms_about_in_order() {
        // The per-app guard (configs-pages.json::tabs.first) rests on this staying exactly three.
        assertEquals(listOf("general", "perms", "about"), FleetConfigsPage.entries.map { it.id })
    }

    @Test fun registers_in_order_and_is_found_by_id() {
        FleetConfigsExtensions.register(FleetConfigsTab("logcat", "Logcat", "code") { })
        FleetConfigsExtensions.register(FleetConfigsTab("xray", "X-Ray", "insights") { })
        assertEquals(listOf("logcat", "xray"), FleetConfigsExtensions.all().map { it.id })
        assertEquals("X-Ray", FleetConfigsExtensions.byId("xray")?.label)
        assertNull(FleetConfigsExtensions.byId("nope"))
    }

    @Test fun re_registering_the_same_id_replaces_not_duplicates() {
        FleetConfigsExtensions.register(FleetConfigsTab("logcat", "Logcat", "code") { })
        FleetConfigsExtensions.register(FleetConfigsTab("logcat", "Logs", "terminal") { })
        assertEquals(1, FleetConfigsExtensions.all().size)
        assertEquals("Logs", FleetConfigsExtensions.byId("logcat")?.label)
    }

    @Test fun a_base_page_id_is_never_registered_as_an_extra() {
        FleetConfigsExtensions.register(FleetConfigsTab("about", "Fake About", "info") { })
        assertTrue(FleetConfigsExtensions.all().isEmpty())
    }

    @Test fun isFleetConfigsPage_covers_base_and_registered_extras() {
        assertTrue(isFleetConfigsPage("general"))
        FleetConfigsExtensions.register(FleetConfigsTab("logcat", "Logcat", "code") { })
        assertTrue(isFleetConfigsPage("logcat"))
        assertTrue(!isFleetConfigsPage("logcat-not-registered-xyz"))
    }
}
