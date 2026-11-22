package com.diegonmarcos.superapp.fleetagents

import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class FleetAgentsTabTest {
    @Before fun clean() = FleetConfigsExtensions.clearForTest()
    @After fun reset() = FleetConfigsExtensions.clearForTest()

    @Test fun registers_the_agents_tab_second_in_the_shared_tail_after_general() {
        FleetAgentsTab.register()
        assertEquals("Agents", FleetConfigsExtensions.byId("agents")?.label)
        val strip = FleetConfigsExtensions.stripOrder()
        assertEquals(listOf("general", "agents", "perms", "about"), strip.map { it.id })
        assertEquals(false, strip.any { it.separatorBefore })
    }

    @Test fun agents_is_never_first_and_the_separator_follows_the_own_tabs() {
        FleetAgentsTab.register()
        val strip = FleetConfigsExtensions.stripOrder()
        assertNotEquals("agents", strip.first().id)
        assertEquals("general", strip[0].id)
        assertEquals(1, strip.indexOfFirst { it.id == "agents" })
    }
}
