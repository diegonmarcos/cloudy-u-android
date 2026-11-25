package com.diegonmarcos.cloudnav.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1547 the Explored map's capitals and subregion members are served datasets: the served files parse, and the defaults are empty (never a crash). */
class MapsGeoDataTest {
    private fun served(name: String) = File("../../../data/fleet/$name.json").readText()

    @Test fun theServedCapitalsParse() {
        val c = MapsGeoData.parseCapitals(served(MapsGeoData.CAPITALS))
        assertTrue(c.size > 30)
        assertEquals(2, c.getValue("France").size)
        assertEquals(48.856, c.getValue("France")[0], 0.0001)
    }

    @Test fun theServedMembersParseAndPaintASubregion() {
        val m = MapsGeoData.parseMembers(served(MapsGeoData.REGION_MEMBERS))
        assertTrue(m.size > 40)
        assertTrue("Central Germanic" in MapsGeoLayers.visitedSubregionsFor(m, setOf("DE")))
    }

    @Test fun theDefaultsAreEmptyNotACrash() {
        assertTrue(MapsGeoData.DEFAULT_CAPITALS.isEmpty())
        assertTrue(MapsGeoLayers.visitedSubregionsFor(MapsGeoData.DEFAULT_MEMBERS, setOf("DE")).isEmpty())
    }
}
