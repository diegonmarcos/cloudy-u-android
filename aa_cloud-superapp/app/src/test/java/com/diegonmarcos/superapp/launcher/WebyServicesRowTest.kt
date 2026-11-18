package com.diegonmarcos.superapp.launcher

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1476/#1806 - Cloud > Apps: the Services row (order declared once in ui.services_order) reads Search | News Events Media Center Games | Shopping Jobs & Business Real Estate Health | Money | Hotels Transports City Mobility | KG Store and the AGI row SI AI | Code Tools |
 * MyTerminal Terminal. Each "|" is a declared separator tile (build.json), so the order and the divider placement are data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebyServicesRowTest {
    private fun row(title: String): String =
        Sections.byId("cloud")!!.tileGroups.single { it.title == title }.tiles
            .joinToString(" ") { if (it.separator) "|" else it.label }

    @Test fun `Services reads Search | News Events Media Center Games | Shopping Jobs & Business Real Estate Health | Money | Hotels Transports City Mobility | KG Store`() =
        assertEquals("Search | News Events Media Center Games | Shopping Jobs & Business Real Estate Health | Money | Hotels Transports City Mobility | KG Store", row("Services"))

    @Test fun `the coming-soon placeholders (#1806 order) have no target and no destination`() {
        val g = Sections.byId("cloud")!!.tileGroups.single { it.title == "Services" }
        val soon = g.tiles.filter { it.comingSoon }
        assertEquals(listOf("Events", "Games", "Jobs & Business", "Real Estate", "Health", "Money", "Hotels", "Transports", "City Mobility"), soon.map { it.label })
        assertEquals(List(9) { "" }, soon.map { it.target })
        assertEquals(listOf("Search", "News", "Media Center", "Shopping", "KG Store"), g.destinations.map { it.label })
    }

    @Test fun `AGI reads SI AI | Code Tools | MyTerminal Terminal`() =
        assertEquals("SI AI | Code Tools | MyTerm. Terminal", row("AGI"))

    @Test fun `Services sits between Projects and AGI (Projects follows Me, #1769) and no Services group is left`() {
        val titles = Sections.byId("cloud")!!.tileGroups.map { it.title }
        assertEquals(titles.indexOf("Me") + 1, titles.indexOf("Projects"))
        assertEquals(titles.indexOf("Projects") + 1, titles.indexOf("Services"))
        assertEquals(titles.indexOf("Services") + 1, titles.indexOf("AGI"))
        assertEquals(false, "Weby Services & Markets" in titles || "Weby Services" in titles)
    }
}
