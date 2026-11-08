package com.diegonmarcos.superapp.notificationcenter

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/** The notify redesign: section order, the Cloudy / Phone split, the title line, the city fallback, the last-media maths. */
class NotifySectionsTest {

    private val nc: String = JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONObject("notification_center").toString()

    @Test fun `the page order is the redesigned taxonomy and Alerts is gone`() {
        val groups = BadgeDeclaration.groups(nc)
        val apps = NotifySections.appSections(nc)
        val order = NotifySections.layout(NotifySections.pageOrder(nc), groups.map { it.id }, apps.map { it.id })
        assertEquals(listOf("live", "media", "network", "apps_push", "apps_keepalive", "actions"), order)
        assertEquals(listOf("Me Infos", "Media", "Device Infos", "Actions"), groups.map { it.label })
        assertEquals(listOf("Apps Push Notifications", "Apps Keep Alive Badges"), apps.map { it.label })
        assertTrue(groups.none { it.alerts || it.id == "alerts" })
        assertTrue(BadgeDeclaration.badges(BadgeDeclaration.parse(nc)).none { it.id == "fleet_alerts" })
    }

    @Test fun `Me Infos rows are Weather, Health, Fin Terminal Markets and nothing else was lost`() {
        val groups = BadgeDeclaration.groups(nc)
        assertEquals(listOf("weather_today", "myhealth", "markets_prices"), groups.first { it.id == "live" }.members)
        val labels = BadgeDeclaration.parse(nc).associate { it.id to it.label }
        assertEquals(listOf("Weather", "Health", "Fin Terminal Markets"), groups.first { it.id == "live" }.members.map { labels[it] })
        val placed = groups.flatMap { it.members }.toSet()
        assertEquals(setOf("kde_status", "media_now_playing", "floating_nav_quick_actions", "network_mesh", "network_data",
            "battery_status", "ai_usage", "myhealth", "weather_today", "markets_prices"), placed)
    }

    @Test fun `push and keep alive each split into Cloudy then Phone`() {
        for (s in NotifySections.appSections(nc)) assertEquals(listOf("Cloudy", "Phone"), s.subs.map { it.label })
        assertEquals(listOf("push", "keepalive"), NotifySections.appSections(nc).map { it.kind })
    }

    @Test fun `AppSplit puts fleet apps under Cloudy and every other app under Phone`() {
        val (c, p) = AppSplit.split(mapOf("com.diegonmarcos.cloudsa" to "Cloud SA", "com.whatsapp" to "WhatsApp",
            "com.android.systemui" to "System UI", "com.diegonmarcos.mail" to "Mail"))
        assertEquals(listOf("com.diegonmarcos.cloudsa", "com.diegonmarcos.mail"), c.map { it.first })
        assertEquals(listOf("com.android.systemui", "com.whatsapp"), p.map { it.first })
    }

    @Test fun `title is City mid Weekday D Mon YYYY mid HH_MM in 24 hour time`() {
        val ms = ZonedDateTime.of(2026, 11, 8, 12, 17, 0, 0, ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
        assertEquals("Berlin · Sun 8 Nov 2026 · 12:17", NotifyTitle.format("Berlin", ms, ZoneId.of("Europe/Berlin")))
        val pm = ZonedDateTime.of(2026, 11, 9, 18, 5, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("Mon 9 Nov 2026 · 18:05", NotifyTitle.format(null, pm, ZoneId.of("UTC")))
        assertEquals("a coordinate is not a city", "Mon 9 Nov 2026 · 18:05", NotifyTitle.format("42.53, 1.52", pm, ZoneId.of("UTC")))
        assertEquals("Mon 9 Nov 2026 · 18:05", NotifyTitle.format("42.53", pm, ZoneId.of("UTC")))
    }

    private class Mem(val m: MutableMap<String, String> = mutableMapOf()) : PlaceName.Cache {
        override fun get(cell: String) = m[cell]
        override fun put(cell: String, city: String) { m[cell] = city }
    }

    @Test fun `a lat lon maps to the locality, then subAdmin, admin, country`() {
        assertEquals("Berlin", PlaceName.pick("Berlin", "Mitte", "BE", "Germany"))
        assertEquals("Mitte", PlaceName.pick(null, "Mitte", "BE", "Germany"))
        assertEquals("BE", PlaceName.pick(" ", null, "BE", "Germany"))
        assertEquals("Germany", PlaceName.pick(null, null, null, "Germany"))
        val cache = Mem()
        var calls = 0
        val src = object : PlaceName.Source { override fun lookup(lat: Double, lon: Double): String? { calls++; return "Berlin" } }
        assertEquals("Berlin", PlaceName.resolve(52.52, 13.405, src, cache))
        assertEquals("cached, no second geocode", "Berlin", PlaceName.resolve(52.521, 13.406, src, cache))
        assertEquals(1, calls)
    }

    @Test fun `no geocoder answer falls back to lat, lon - never a bare number`() {
        val none = object : PlaceName.Source { override fun lookup(lat: Double, lon: Double): String? = null }
        val boom = object : PlaceName.Source { override fun lookup(lat: Double, lon: Double): String? = error("no backend") }
        val bare = object : PlaceName.Source { override fun lookup(lat: Double, lon: Double): String? = "42.53" }
        for (s in listOf(none, boom, bare)) {
            val cache = Mem()
            val r = PlaceName.resolve(42.5312, 1.5211, s, cache)
            assertEquals("42.53, 1.52", r)
            assertTrue(r.contains(","))
            assertTrue("a fallback is not cached as a city", cache.m.isEmpty())
        }
        assertTrue(PlaceName.isCoords("42.53"))
        assertTrue(PlaceName.isCoords("42.53, 1.52"))
        assertFalse(PlaceName.isCoords("Berlin"))
    }
}
