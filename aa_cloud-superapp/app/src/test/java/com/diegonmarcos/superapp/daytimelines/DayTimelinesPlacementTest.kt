package com.diegonmarcos.superapp.daytimelines

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.cal.TaskEvent
import com.diegonmarcos.superapp.cal.TaskTodo
import com.diegonmarcos.superapp.cal.TasksPopup
import com.diegonmarcos.superapp.cal.TasksSource
import com.diegonmarcos.superapp.launcher.BadgeCard
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
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * #1734 the card is drawn, from ONE composable, in both places: the Weather badge's expanded view on the Notify page and the date
 * dropdown (Tasks popup) right after the Agenda. Plus the card itself: lanes, the shared now marker, the tap that flips to the JSON,
 * and the static proof that nothing was removed from either host (rule 16).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DayTimelinesPlacementTest : KitPageHarness() {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val date = LocalDate.of(2026, 6, 21)
    private val noon = date.atTime(12, 0).atZone(berlin).toInstant().toEpochMilli()
    private val startHour = date.atStartOfDay(berlin).toEpochSecond() / 3600L

    private fun n(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun snapshot(status: DataStatus = DataStatus(DataStatus.Kind.LIVE, 0L), note: String? = null): DayRepo.Snapshot {
        val hrs = (0 until 24).map { Hour(startHour + it, 8.0 + it * 0.6, if (it < 8) "clearsky_night" else if (it < 14) "rain" else "partlycloudy_day") }
        return DayRepo.Snapshot(DayTimelines.build(date, berlin, "Berlin", 52.52, 13.405, hrs, noon), status, note)
    }

    @Test fun `the card draws the four bars, axis, legend and the shared now marker`() {
        showKit { DayTimelinesBody(snapshot(), noon) }
        for (t in listOf(DayTimelinesTags.HEADER, DayTimelinesTags.LANES, DayTimelinesTags.DAY, DayTimelinesTags.TEMP, DayTimelinesTags.MOON,
            DayTimelinesTags.SKY, DayTimelinesTags.NOW, DayTimelinesTags.AXIS, DayTimelinesTags.LEGEND, DayTimelinesTags.STATUS))
            n(t).assertExists()
        n(DayTimelinesTags.icon(0)).assertExists()
        n(DayTimelinesTags.icon(8)).assertExists()
        n(DayTimelinesTags.sky("sun")).assertExists()
        n(DayTimelinesTags.sky("jupiter")).assertExists()
    }

    @Test fun `tap flips to the JSON data and back`() {
        showKit { DayTimelinesBody(snapshot(), noon) }
        n(DayTimelinesTags.JSON).assertDoesNotExist()
        compose.onNodeWithTag(DayTimelinesTags.CARD).performClick(); compose.waitForIdle()
        n(DayTimelinesTags.JSON).assertExists()
        n(DayTimelinesTags.LANES).assertDoesNotExist()
        compose.onNodeWithTag(DayTimelinesTags.CARD).performClick(); compose.waitForIdle()
        n(DayTimelinesTags.LANES).assertExists()
    }

    @Test fun `offline card says so and still draws the sky`() {
        showKit { DayTimelinesBody(snapshot(DataStatus(DataStatus.Kind.CACHED, 3 * 3_600_000L, "offline"), "Default city: no location fix on this phone yet"), noon) }
        n(DayTimelinesTags.STATUS).assertExists()
        val txt = n(DayTimelinesTags.STATUS).fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
        assertTrue(txt, txt.contains("cached 3 h ago") && txt.contains("Default city"))
        n(DayTimelinesTags.MOON).assertExists()
        // No data at all: a reason, no crash, bars still there.
        val none = DayRepo.Snapshot(DayTimelines.build(date, berlin, "Berlin", 52.52, 13.405, emptyList(), noon), DataStatus(DataStatus.Kind.NONE, 0L, "offline"), null)
        showKit { DayTimelinesBody(none, noon) }
        n(DayTimelinesTags.TEMP).assertExists()
        n(DayTimelinesTags.icon(0)).assertDoesNotExist()
        showKit { DayTimelinesBody(null, noon) }
        n(DayTimelinesTags.STATUS).assertExists()
    }

    // ---- placement 2: the date dropdown, right after the Agenda
    private val fake = object : TasksSource {
        override fun events(fromMs: Long, toMs: Long) = listOf(TaskEvent("Dentist", noon, noon + 3_600_000L, false))
        override fun todos() = listOf(TaskTodo("Buy milk", "NEEDS-ACTION", null))
    }

    @Test fun `the calendar dropdown carries the card right after the Agenda`() {
        TasksPopup.resetStopwatchForTest(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        showKit { TasksPopup.TasksBubble(fake, date) }
        compose.onNodeWithTag("cal:months").assertExists()
        compose.onNodeWithTag("cal:table:agenda").assertExists()
        compose.onNodeWithTag("cal:table:timelines").assertExists()
        compose.onNodeWithTag(DayTimelinesTags.CARD, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("cal:table:todo").assertExists()
        fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y
        assertTrue("agenda, then timelines, then todo", top("cal:table:agenda") < top("cal:table:timelines") && top("cal:table:timelines") < top("cal:table:todo"))
    }

    // ---- placement 1: the Weather badge's expanded view on Notify
    private fun cardData(id: String): BadgeCardData.Data {
        val b = orderedBadges(androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()).first { it.id == id }
        return BadgeCardData.Data(b, b.label, "headline", "sub", null, emptyList(), true, "", System.currentTimeMillis(), null, emptyList())
    }

    @Test fun `the Weather badge expanded view carries the card and no other badge does`() {
        showKit { Column { BadgeCard(cardData(WeatherBadgeService.BADGE_ID)) } }
        compose.onNodeWithTag(DayTimelinesTags.CARD, useUnmergedTree = true).assertExists()
        // everything the badge showed before is still there (rule 16)
        compose.onNodeWithTag("badge-card-expanded:${WeatherBadgeService.BADGE_ID}").assertExists()
        val other = orderedBadges(androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()).first { it.id != WeatherBadgeService.BADGE_ID }
        showKit { Column { BadgeCard(cardData(other.id)) } }
        assertEquals(0, compose.onAllNodesWithTag(DayTimelinesTags.CARD, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    // ---- static: one composable, both hosts, nothing removed
    private val main = File(System.getProperty("user.dir"), "src/main/java/com/diegonmarcos/superapp")

    @Test fun `both hosts call the one composable and keep everything they had`() {
        val badge = File(main, "launcher/BadgeCards.kt").readText()
        val popup = File(main, "cal/TasksPopup.kt").readText()
        assertTrue(badge.contains("DayTimelinesCard(") && badge.contains("WeatherBadgeService.BADGE_ID"))
        assertTrue(popup.contains("DayTimelinesCard("))
        assertTrue("after the Agenda table, before the Todo table", popup.indexOf("AgendaTable(decl, events") < popup.indexOf("DayTimelinesCard(") &&
            popup.indexOf("DayTimelinesCard(") < popup.indexOf("TodoTable(decl, todos"))
        for (kept in listOf("Months(decl, today", "AgendaTable(decl, events", "TodoTable(decl, todos", "Stopwatch()")) assertTrue(kept, popup.contains(kept))
        for (kept in listOf("ActionRow(d, accent, hostView)", "MediaHubLine(d.badge.id)", "CollapseChevron(")) assertTrue(kept, badge.contains(kept))
        val composables = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains("fun DayTimelinesCard(") }.toList()
        assertEquals("exactly one DayTimelinesCard definition", 1, composables.size)
    }
}
