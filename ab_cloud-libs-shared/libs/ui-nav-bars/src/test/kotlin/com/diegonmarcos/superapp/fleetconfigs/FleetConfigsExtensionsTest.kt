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
        // The per-app guard (configs-pages.json::tabs.shared) rests on this staying exactly three.
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

    // #1852 the strip is {own tabs} | General, Agents, Perms, Logcat, X-Ray App, About. FIELD PARITY (rule 16): no tab of the previous
    // shape ([General + own tabs] | Agents, Perms, Logcat, X-Ray App, About) is lost, whatever an app registers.
    private fun reg(id: String, label: String = id) = FleetConfigsExtensions.register(FleetConfigsTab(id, label, "code") { })

    @Test fun the_shared_tail_is_exactly_the_declared_order() {
        // configs-pages.json::tabs.tail.ids; the guard reads the same list from FleetConfigsExtensions.TAIL_IDS
        assertEquals(listOf("general", "agents", "perms", "logcat", "xray", "about"), FleetConfigsExtensions.TAIL_IDS)
    }

    @Test fun strip_with_no_own_tab_and_no_opt_in_is_general_perms_about_without_a_leading_divider() {
        val s = FleetConfigsExtensions.stripOrder()
        assertEquals(listOf("general", "perms", "about"), s.map { it.id })
        assertEquals(0, s.count { it.separatorBefore })
    }

    @Test fun strip_puts_the_six_shared_tabs_in_exact_order_whatever_the_registration_order() {
        reg("xray", "X-Ray App"); reg("logcat"); reg("agents")
        val s = FleetConfigsExtensions.stripOrder()
        assertEquals(listOf("general", "agents", "perms", "logcat", "xray", "about"), s.map { it.id })
        assertEquals(0, s.count { it.separatorBefore })   // no own tab: nothing to divide from
    }

    @Test fun app_own_tabs_come_first_then_the_divider_then_the_shared_tabs_and_none_is_lost() {
        reg("media"); reg("logcat"); reg("events")
        val s = FleetConfigsExtensions.stripOrder()
        assertEquals(listOf("media", "events", "general", "perms", "logcat", "about"), s.map { it.id })
        val oldShape = listOf("general", "media", "events", "perms", "logcat", "about")   // the pre-#1852 strip
        assertTrue(s.map { it.id }.containsAll(oldShape))
        assertEquals(listOf("general"), s.filter { it.separatorBefore }.map { it.id })
    }

    @Test fun custom_xray_hook_registers_the_tab_when_the_lib_is_not_linked() {
        FleetConfigsExtensions.registerXrayPage { }
        assertEquals("X-Ray App", FleetConfigsExtensions.byId("xray")?.label)
        assertEquals(listOf("general", "perms", "xray", "about"), FleetConfigsExtensions.stripOrder().map { it.id })
    }
    // #1852 a host that draws its own strip (Dialer, Drive, News, ...) runs its declared pages through withTail: same order, same `|`.
    private fun np(id: String) = com.diegonmarcos.superapp.bottomnav.NavPage(id, id.replaceFirstChar { it.uppercase() }, "code")

    @Test fun host_strip_gets_the_tail_in_exact_order_and_keeps_every_own_tab() {
        reg("xray", "X-Ray App"); reg("logcat"); reg("agents")
        val host = listOf("general", "perms", "about", "sim", "status", "usage").map { np(it) }   // Dialer's former declared order
        val s = FleetConfigsExtensions.withTail(host)
        assertEquals(listOf("sim", "status", "usage", "general", "agents", "perms", "logcat", "xray", "about"), s.map { it.id })
        assertEquals(listOf("general"), s.filter { it.separatorBefore }.map { it.id })
        assertTrue(s.map { it.id }.containsAll(host.map { it.id }))
    }

    @Test fun host_strip_already_in_the_new_order_is_unchanged() {
        reg("logcat")
        val host = listOf("media", "events", "news", "apify", "general", "perms", "about").map { np(it) }   // News's declared order
        val s = FleetConfigsExtensions.withTail(host)
        assertEquals(listOf("media", "events", "news", "apify", "general", "perms", "logcat", "about"), s.map { it.id })
        assertEquals("general", s.first { it.separatorBefore }.id)
    }

    @Test fun host_strip_without_general_perms_or_about_gets_them_added() {
        val s = FleetConfigsExtensions.withTail(listOf(np("own")))
        assertEquals(listOf("own", "general", "perms", "about"), s.map { it.id })
        assertEquals(listOf("general"), s.filter { it.separatorBefore }.map { it.id })
    }

    @Test fun host_strip_with_no_own_tab_has_no_divider() {
        val s = FleetConfigsExtensions.withTail(listOf(np("general"), np("perms"), np("about")))
        assertEquals(listOf("general", "perms", "about"), s.map { it.id })
        assertEquals(0, s.count { it.separatorBefore })
    }
}
