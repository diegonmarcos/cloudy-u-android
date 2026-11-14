package com.diegonmarcos.superapp.cloud

import android.app.Application
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.diegonmarcos.superapp.cal.TaskEvent
import com.diegonmarcos.superapp.cal.TaskTodo
import com.diegonmarcos.superapp.cal.TasksPopup
import com.diegonmarcos.superapp.cal.TasksSource
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Tasks popup (was "Agenda | Calendar") in Compose.
 *
 *   C1 it is called Tasks and opens on the Calendar view, with the two tables under the months
 *   C2 the calendar shows three months, previous + current + next, today marked
 *   C3 the Agenda and Todo tables are fed from the source, and a day with an event carries a dot
 *   C4 tapping a day filters the Agenda to it; tapping it again clears the filter
 *   C5 the Agenda view shows the tables alone
 *   C6 the stopwatch toggles, and the formatting is m:ss.t
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TasksPopupComposeTest : KitPageHarness() {

    private val today = LocalDate.of(2026, 10, 30)
    private fun ms(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val fake = object : TasksSource {
        override fun events(fromMs: Long, toMs: Long) = listOf(
            TaskEvent("Dentist", ms(today, 10), ms(today, 11), false),
            TaskEvent("Rent", ms(today.plusDays(6), 9), ms(today.plusDays(6), 10), false),
        )
        override fun todos() = listOf(TaskTodo("Buy milk", "NEEDS-ACTION", null), TaskTodo("Done thing", "COMPLETED", null))
    }

    @Before fun fresh() = TasksPopup.resetStopwatchForTest(androidx.test.core.app.ApplicationProvider.getApplicationContext())

    private fun show() = showKit { TasksPopup.TasksBubble(fake, today) }

    private fun waitFor(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `C1 it is Tasks and opens on the Calendar with both tables`() {
        show()
        compose.onNodeWithTag("cal:title").assertTextEquals("Tasks")
        compose.onNodeWithTag("cal:months").assertExists()
        compose.onNodeWithTag("cal:table:agenda").assertExists()
        compose.onNodeWithTag("cal:table:todo").assertExists()
    }

    @Test
    fun `C2 three months with today`() {
        show()
        for (m in listOf("2026-09", "2026-10", "2026-11")) compose.onNodeWithTag("cal:month:$m").assertExists()
        compose.onNodeWithTag("cal:month:2026-08").assertDoesNotExist()
        compose.onNodeWithTag("cal:month:2026-12").assertDoesNotExist()
        compose.onNodeWithTag("cal:today", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `C3 the tables are fed and the event day has a dot`() {
        show()
        waitFor("cal:agenda:row:0")
        // #1738 the Calendar view's Agenda is today only; the full agenda is the Agenda view (C7)
        compose.onNodeWithText("Dentist").assertExists()
        compose.onNodeWithTag("cal:agenda:row:1").assertDoesNotExist()
        waitFor("cal:todo:row:0")
        compose.onNodeWithText("Buy milk").assertExists()
        compose.onNodeWithText("Done thing").assertDoesNotExist()
        compose.onNodeWithTag("cal:dot:$today", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("cal:dot:${today.plusDays(6)}", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `C4 a day tap filters the agenda to that day`() {
        show()
        waitFor("cal:agenda:row:0")
        compose.onNodeWithTag("cal:day:${today.plusDays(6)}").performScrollTo().performClick()
        compose.onNodeWithTag("cal:agenda:row:0").assertExists()
        compose.onNodeWithTag("cal:agenda:row:1").assertDoesNotExist()
        compose.onNodeWithText("Rent").assertExists()
        compose.onNodeWithTag("cal:filter").performScrollTo().performClick()
        compose.onNodeWithText("Dentist").assertExists()
        compose.onNodeWithText("Rent").assertDoesNotExist()
    }

    @Test
    fun `C7 the full agenda stays reachable in the Agenda view`() {
        show()
        waitFor("cal:agenda:row:0")
        compose.onNodeWithTag("cal:tab:Agenda").performClick()
        waitFor("cal:agenda:row:1")
        compose.onNodeWithText("Dentist").assertExists()
        compose.onNodeWithText("Rent").assertExists()
    }

    @Test
    fun `C5 the Agenda view is the tables alone`() {
        show()
        compose.onNodeWithTag("cal:tab:Agenda").performClick()
        compose.onNodeWithTag("cal:months").assertDoesNotExist()
        compose.onNodeWithTag("cal:table:agenda").assertExists()
        compose.onNodeWithTag("cal:table:todo").assertExists()
    }

    @Test
    fun `C6 the stopwatch starts from the toggle and formats m ss t`() {
        assertEquals("0:00.0", TasksPopup.formatStopwatch(0))
        assertEquals("1:05.3", TasksPopup.formatStopwatch(65_300))
        show()
        compose.onNodeWithText("▶").assertExists()
        compose.onNodeWithTag("cal:stopwatch:toggle").performClick()
        compose.onNodeWithText("■").assertExists()
        assertTrue(true)
    }
}
