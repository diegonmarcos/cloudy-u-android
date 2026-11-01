package com.diegonmarcos.cgomniverse

import org.junit.Assert.assertEquals
import org.junit.Test

/** The launch extras: a known section opens it, a known world opens Galaxy over it, anything else is the default page. */
class LaunchTest {
    private val sections = setOf("avatar", "galaxy", "store", "about")
    private val worlds = setOf("a-way-to-go", "lusion-v3")
    private fun r(s: String?, w: String?) = Launch.resolve(s, w, sections, worlds, "avatar")

    @Test fun noExtrasIsTheDefault() = assertEquals(Launch.Start("avatar", null), r(null, null))
    @Test fun aKnownSectionOpensIt() = assertEquals(Launch.Start("store", null), r("store", null))
    @Test fun anUnknownSectionIsTheDefault() = assertEquals(Launch.Start("avatar", null), r("nope", null))
    @Test fun aKnownWorldOpensGalaxy() = assertEquals(Launch.Start("galaxy", "a-way-to-go"), r("store", "a-way-to-go"))
    @Test fun anUnknownWorldIsIgnored() = assertEquals(Launch.Start("store", null), r("store", "nope"))
    @Test fun theDeepLinkNamesAWorld() {
        assertEquals("floor796", Launch.worldFromLink("cgomniverse", "world", "/floor796"))
        assertEquals(null, Launch.worldFromLink("https", "world", "/floor796"))
        assertEquals(null, Launch.worldFromLink("cgomniverse", "other", "/floor796"))
        assertEquals(null, Launch.worldFromLink("cgomniverse", "world", "/"))
    }
}
