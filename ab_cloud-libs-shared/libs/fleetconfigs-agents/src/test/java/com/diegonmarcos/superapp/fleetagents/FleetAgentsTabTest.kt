package com.diegonmarcos.superapp.fleetagents

import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FleetAgentsTabTest {
    @Before fun clean() = FleetConfigsExtensions.clearForTest()
    @After fun reset() = FleetConfigsExtensions.clearForTest()

    @Test fun registers_the_agents_tab_first_after_the_separator() {
        FleetAgentsTab.register()
        assertEquals("Agents", FleetConfigsExtensions.byId("agents")?.label)
        val strip = FleetConfigsExtensions.stripOrder()
        assertEquals(listOf("general", "agents", "perms", "about"), strip.map { it.id })
        assertEquals(true, strip[1].separatorBefore)
    }
}
