package com.diegonmarcos.superapp.launcher

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1476/#1806/#1902 - Cloud > Apps: the Services rows (order declared once in ui.services_order). Services - City reads Money | Shopping Health Real Estate Jobs & Business | City Mobility Transports Hotels,
 * Services - Leisure reads News | Events Media Center Social Games, and the AGI row SI AI | Code Tools | MyTerminal Terminal | Search KG Store.
 * Each "|" is a declared separator tile (build.json), so the order and the divider placement are data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebyServicesRowTest {
    private val city = "Services \u00b7 City"
    private val leisure = "Services \u00b7 Leisure"
    private fun row(title: String): String =
        Sections.byId("cloud")!!.tileGroups.single { it.title == title }.tiles
            .joinToString(" ") { if (it.separator) "|" else it.label }

    @Test fun `Services City reads Money | Shopping Health Real Estate Jobs & Business | City Mobility Transports Hotels`() =
        assertEquals("Money | Shopping Health Real Estate Jobs & Business | City Mobility Transports Hotels", row(city))

    @Test fun `Services Leisure reads News | Events Media Center Social Games`() =
        assertEquals("News | Events Media Center Social Games", row(leisure))

    @Test fun `the coming-soon placeholders (#1806 order) have no target and no destination`() {
        val c = Sections.byId("cloud")!!.tileGroups.single { it.title == city }
        val soonC = c.tiles.filter { it.comingSoon }
        assertEquals(listOf("Money", "Health", "Real Estate", "Jobs & Business", "City Mobility", "Transports", "Hotels"), soonC.map { it.label })
        assertEquals(List(7) { "" }, soonC.map { it.target })
        assertEquals(listOf("Shopping"), c.destinations.map { it.label })
        val l = Sections.byId("cloud")!!.tileGroups.single { it.title == leisure }
        val soonL = l.tiles.filter { it.comingSoon }
        assertEquals(listOf("Events", "Games"), soonL.map { it.label })
        assertEquals(List(2) { "" }, soonL.map { it.target })
        assertEquals(listOf("News", "Media Center", "Social"), l.destinations.map { it.label })
    }

    @Test fun `Social opens the mySocials web page (#1904)`() {
        val social = Sections.byId("cloud")!!.tileGroups.single { it.title == leisure }.tiles.single { it.label == "Social" }
        assertEquals("https://diegonmarcos.github.io/mySocials/", social.target)
    }

    @Test fun `AGI reads SI AI | Code Tools | MyTerminal Terminal | Search KG Store`() =
        assertEquals("SI AI | Code Tools | MyTerm. Terminal | Search KG Store", row("AGI"))

    @Test fun `Services City and Leisure sit between Me and AGI, Projects follows Setup (#1916) and no Services group is left`() {
        val titles = Sections.byId("cloud")!!.tileGroups.map { it.title }
        assertEquals(titles.indexOf("Setup") + 1, titles.indexOf("Projects"))
        assertEquals(titles.indexOf("Me") + 1, titles.indexOf(city))
        assertEquals(titles.indexOf(city) + 1, titles.indexOf(leisure))
        assertEquals(titles.indexOf(leisure) + 1, titles.indexOf("AGI"))
        assertEquals(false, "Services" in titles || "Weby Services & Markets" in titles || "Weby Services" in titles)
    }
}
