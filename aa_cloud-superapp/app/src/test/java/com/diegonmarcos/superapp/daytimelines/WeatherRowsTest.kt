package com.diegonmarcos.superapp.daytimelines

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.launcher.BadgeCard
import com.diegonmarcos.superapp.launcher.BadgeCardTags
import com.diegonmarcos.superapp.launcher.orderedBadges
import com.diegonmarcos.superapp.notificationcenter.BadgeCardData
import com.diegonmarcos.superapp.notificationcenter.WeatherBadgeService
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * #1907 the Persistent Weather card: NOTHING between the title and the graphic, then exactly seven rows (two columns) below it,
 * in the ticket's order and with its labels; a missing value is an em dash. Mutations of this contract are proven red by
 * test/test-weather-card-rows.sh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h3000dp-night-450dpi")
class WeatherRowsTest : KitPageHarness() {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val date = LocalDate.of(2026, 6, 21)
    private val noon = date.atTime(12, 0).atZone(berlin).toInstant().toEpochMilli()
    private val startHour = date.atStartOfDay(berlin).toEpochSecond() / 3600L

    private fun snap(hours: List<Hour>, status: DataStatus = DataStatus(DataStatus.Kind.LIVE, 0L)) =
        DayRepo.Snapshot(DayTimelines.build(date, berlin, "Berlin", 52.52, 13.405, hours, noon), status, null, hours, noon)

    private val full = (0 until 24).map {
        Hour(startHour + it, 10.0 + it, "fair_day", humidityPct = 40.0 + it, windMs = 2.0 + it / 23.0 * 3.0, uv = if (it == 13) 7.46 else 0.0,
            precipPct = if (it == 15) 60.0 else 10.0, precipMm = if (it == 15) 1.5 else 0.2, pressureHpa = 1000.0 + it)
    }

    @Test fun `labels and order are the declared six rows`() {
        val want = listOf(
            "Sun: Dawn / Sunrise / Sunset / Dusk" to "Moon: Rise / Set",
            "Temp: min / max" to "Feels like: min / max",
            "Humidity: min / max" to "Wind: min / max",
            "Precipitation: probability / amount" to "UV index",
            "Pressure: min / max" to "Dew point: min~max (comfort)",
            "Moon phase" to "Sun zodiac / Moon zodiac",
            "Last update: time" to "Location",
        )
        for (s in listOf(null, snap(full), snap(emptyList())))
            assertEquals(want, WeatherRows.rows(s).map { it.left.label to it.right.label })
        assertEquals(7, WeatherRows.LAYOUT.size)
    }

    @Test fun `values come from the forecast and the astronomy, a missing one is an em dash`() {
        val r = WeatherRows.rows(snap(full)).associateBy { it.id }
        val sun = r.getValue("sun_moon").left.value.split(" / ")
        assertEquals(4, sun.size); assertTrue(sun.toString(), sun.none { it == WeatherRows.EM })
        assertTrue(sun.zipWithNext().all { (a, b) -> a < b })
        assertEquals(2, r.getValue("sun_moon").right.value.split(" / ").size)
        assertEquals("10° / 33°", r.getValue("temp").left.value)
        val feels = r.getValue("temp").right.value.split(" / ")
        assertTrue(feels.toString(), feels.none { it == WeatherRows.EM } && feels[0].removeSuffix("°").toInt() < feels[1].removeSuffix("°").toInt())
        assertEquals("40% / 63%", r.getValue("humid_wind").left.value)
        assertEquals("7 km/h / 18 km/h", r.getValue("humid_wind").right.value)
        assertEquals("60% / 6.1 mm", r.getValue("precip_uv").left.value)
        assertEquals("7.5", r.getValue("precip_uv").right.value)
        assertEquals("1000 hPa / 1023 hPa", r.getValue("pressure_dew").left.value)
        // Magnus-Tetens per hour from 10..33 C and 40..63 % rh, then today's min / max
        val dews = full.map { WeatherRows.dewPoint(it.tempC, it.humidityPct)!! }
        assertEquals("${Math.round(dews.min())}°~${Math.round(dews.max())}° (${WeatherRows.dewCategory(dews.max())})", r.getValue("pressure_dew").right.value)
        val given = WeatherRows.rows(snap(full.mapIndexed { i, h -> h.copy(dewC = if (i == 0) -3.4 else 5.0) })).associateBy { it.id }
        assertEquals("-3°~5° (Dry and crisp)", given.getValue("pressure_dew").right.value) // dew_point_temperature wins over Magnus
        assertEquals("—", WeatherRows.rows(snap(full.map { it.copy(humidityPct = null) })).first { it.id == "pressure_dew" }.right.value)
        assertEquals("— / —", WeatherRows.rows(snap(full.map { it.copy(pressureHpa = null) })).first { it.id == "pressure_dew" }.left.value)
        for ((v, c) in listOf(9.9 to "Dry and crisp", 10.0 to "Comfortable", 15.9 to "Comfortable", 16.0 to "Noticeably humid",
            20.9 to "Noticeably humid", 21.0 to "Muggy and oppressive", -4.3 to "Dry and crisp", 15.0 to "Comfortable"))
            assertEquals(v.toString(), c, WeatherRows.dewCategory(v))
        val owner = WeatherRows.rows(snap(full.mapIndexed { i, h -> h.copy(dewC = if (i == 0) 12.0 else 15.0) })).first { it.id == "pressure_dew" }
        assertEquals("12°~15° (Comfortable)", owner.right.value)
        assertEquals(-4.3, WeatherRows.dewPoint(0.0, 73.0)!!, 0.1) // Berlin, the owner's worked example
        assertEquals(20.0, WeatherRows.dewPoint(20.0, 100.0)!!, 1e-9)
        assertEquals(null, WeatherRows.dewPoint(22.0, null))
        assertTrue(r.getValue("phase_zodiac").left.value.endsWith("%"))
        assertEquals(2, r.getValue("phase_zodiac").right.value.split(" / ").size)
        assertEquals("12:00", r.getValue("update_place").left.value)
        assertEquals("Berlin", r.getValue("update_place").right.value)
        // Steadman: 20 C, 50 % rh, 1 m/s is a little under the air temperature in the shade
        val f = WeatherRows.feelsLike(20.0, 50.0, 1.0)!!
        assertTrue(f.toString(), f in 17.0..20.0)
        assertEquals(null, WeatherRows.feelsLike(20.0, null, 1.0))
    }

    @Test fun `the notification text is built from the same rows`() {
        val rows = WeatherRows.rows(snap(full))
        val lines = WeatherRows.notificationText(rows).split("\n")
        assertEquals(rows.size, lines.size)
        for ((row, line) in rows.zip(lines)) {
            assertTrue(line, line.contains("${row.left.label}: ${row.left.value}") && line.contains("${row.right.label}: ${row.right.value}"))
        }
        assertTrue(lines[4], lines[4].startsWith("Pressure: min / max: 1000 hPa / 1023 hPa"))
    }

    @Test fun `no data at all gives seven rows of dashes and never throws`() {
        for (s in listOf(null, snap(emptyList(), DataStatus(DataStatus.Kind.NONE, 0L, "offline")))) {
            val r = WeatherRows.rows(s).associateBy { it.id }
            assertEquals(7, r.size)
            for (id in listOf("temp", "humid_wind", "pressure_dew")) for (c in listOf(r.getValue(id).left, r.getValue(id).right))
                assertTrue(c.value, c.value.split(" / ").all { it == WeatherRows.EM || it.startsWith(WeatherRows.EM) })
            assertEquals(WeatherRows.EM, r.getValue("precip_uv").right.value)
            assertEquals(WeatherRows.EM, r.getValue("pressure_dew").right.value)
            assertEquals(WeatherRows.EM, r.getValue("update_place").left.value)
        }
        assertEquals(WeatherRows.EM, WeatherRows.rows(null).first { it.id == "update_place" }.right.value)
    }

    // ---- the card itself
    private fun cardData(): BadgeCardData.Data {
        val b = orderedBadges(androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()).first { it.id == WeatherBadgeService.BADGE_ID }
        return BadgeCardData.Data(b, b.label, "HEADLINE-1907", "SUBLINE-1907", 0.5f,
            listOf(BadgeCardData.Line("09h", "HOURLY-1907")), true, "", System.currentTimeMillis(), null, emptyList(), subText = "INFOLINE-1907")
    }

    private val id = WeatherBadgeService.BADGE_ID
    private fun top(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
    private fun bottom(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom

    @Test fun `nothing is drawn between the title and the graphic and the rows sit below it in order`() {
        showKit { Column { BadgeCard(cardData()) } }
        compose.waitForIdle()
        val titleBottom = bottom(BadgeCardTags.title(id)); val graphicTop = top(DayTimelinesTags.CARD)
        assertTrue("graphic below the title", graphicTop >= titleBottom)
        // No node (text, bar, progress) whose box lies between the title row and the graphic.
        val between = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().let { root ->
            fun walk(n: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
            walk(root).filter { it.boundsInRoot.height > 0f && it.boundsInRoot.top >= titleBottom - 0.5f && it.boundsInRoot.bottom <= graphicTop + 0.5f }
        }
        assertTrue("between title and graphic: " + between.map { it.config.toString() }, between.isEmpty())
        for (t in listOf("HEADLINE-1907", "SUBLINE-1907", "HOURLY-1907", "INFOLINE-1907"))
            assertEquals(t, 0, compose.onAllNodesWithText(t, useUnmergedTree = true).fetchSemanticsNodes().size)
        // The seven rows, below the graphic, in the declared order, with the declared labels.
        val tops = WeatherRows.LAYOUT.map { top(BadgeCardTags.weatherRow(id, it.first)) }
        assertTrue("rows below the graphic", tops.all { it > top(DayTimelinesTags.CARD) })
        assertEquals("rows in order", tops.sorted(), tops)
        assertEquals(7, tops.toSet().size)
        for ((_, l, r) in WeatherRows.LAYOUT) for (t in listOf(l, r))
            assertEquals(t, 1, compose.onAllNodesWithText(t, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun `what left the gap is kept in the detail view`() {
        showKit { Column { BadgeCard(cardData()) } }
        compose.onNodeWithTag(BadgeCardTags.weatherDetail(id), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(BadgeCardTags.weatherDetailToggle(id), useUnmergedTree = true).performClick(); compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(BadgeCardTags.weatherDetail(id), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(BadgeCardTags.weatherDetail(id), useUnmergedTree = true).assertExists()
        for (t in listOf("HEADLINE-1907", "SUBLINE-1907", "HOURLY-1907", "INFOLINE-1907"))
            compose.onAllNodesWithText(t, useUnmergedTree = true).fetchSemanticsNodes().also { assertEquals(t, 1, it.size) }
        assertTrue(top(BadgeCardTags.weatherDetail(id)) > top(BadgeCardTags.weatherRow(id, "update_place")))
    }
}
