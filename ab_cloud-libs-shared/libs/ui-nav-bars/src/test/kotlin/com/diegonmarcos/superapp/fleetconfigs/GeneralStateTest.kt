package com.diegonmarcos.superapp.fleetconfigs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1852 the live state words of Configs > General: what each entry says about the phone right now. */
class GeneralStateTest {
    @Test fun perms_is_granted_over_total() {
        assertEquals("9/9 granted", GeneralState.perms(9, 9))
        assertEquals("3/7 granted", GeneralState.perms(3, 7))
        assertEquals("none declared", GeneralState.perms(0, 0))
    }

    @Test fun battery_optimisation_is_on_unless_the_app_is_exempt() {
        assertEquals("optimisation off", GeneralState.battery(true))
        assertEquals("optimisation on", GeneralState.battery(false))
    }

    @Test fun storage_shows_the_size_used_or_an_ellipsis_while_it_is_read() {
        assertEquals("…", GeneralState.storage(-1))
        assertEquals("0 B used", GeneralState.storage(0))
        assertEquals("1.5 MB used", GeneralState.storage(1_572_864))
    }

    @Test fun notifications_say_blocked_or_the_enabled_channel_count() {
        assertEquals("blocked", GeneralState.notifications(false, 3, 3))
        assertEquals("on", GeneralState.notifications(true, 0, 0))
        assertEquals("on · 2/3 channels", GeneralState.notifications(true, 3, 2))
    }

    @Test fun theme_names_the_ui_mode() {
        assertEquals("dark theme", GeneralState.theme(true))
        assertEquals("light theme", GeneralState.theme(false))
    }

    @Test fun update_state_follows_the_published_build() {
        val none = ReleaseState("", "", emptyList(), "")
        val same = ReleaseState("abc123def4567890", "", emptyList(), "")
        val newer = ReleaseState("ffffffffffff", "", emptyList(), "")
        assertEquals("1.2 (7) · not checked", GeneralState.update("1.2 (7)", null, "abc123def456"))
        assertEquals("1.2 (7) · no published build", GeneralState.update("1.2 (7)", none, "abc123def456"))
        assertEquals("1.2 (7) · up to date", GeneralState.update("1.2 (7)", same, "abc123def456"))
        assertEquals("1.2 (7) · newer build", GeneralState.update("1.2 (7)", newer, "abc123def456"))
        assertTrue(GeneralState.update("v", same, "local").endsWith("newer build"))   // a local build is never "up to date"
    }
}
