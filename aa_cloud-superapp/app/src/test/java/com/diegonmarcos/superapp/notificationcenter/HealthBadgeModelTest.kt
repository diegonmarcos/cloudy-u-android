package com.diegonmarcos.superapp.notificationcenter

import com.diegonmarcos.superapp.boards.HealthAgg
import com.diegonmarcos.superapp.boards.HealthFixtures
import com.diegonmarcos.superapp.boards.Read
import com.diegonmarcos.superapp.boards.Why
import com.diegonmarcos.superapp.health.HealthBoardReader
import com.diegonmarcos.superapp.notificationcenter.HealthBadgeModel.State
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** The MyHealth card's faces: data, no permission, error, timeout, and the renamed id keeping its old names. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HealthBadgeModelTest {
    private val today = LocalDate.of(2026, 10, 22)
    private fun board(raw: HealthBoardReader.Raw = HealthFixtures.seeded(today)) = HealthAgg.board(raw, today)

    @Test fun `data shows km with the step count, resting HR and last night's sleep`() {
        val f = HealthBadgeModel.face(Read.Ok(board()))
        assertEquals(State.DATA, f.state)
        assertTrue(f.title, Regex("""~?\d+\.\dkm \(11,000 steps\)""").matches(f.title))
        assertTrue(f.text, f.text.contains("Resting HR ") && f.text.contains("bpm"))
        assertTrue(f.text, f.text.contains("Sleep 7h 07m"))   // 84 + 102 + 241 = 427 min asleep
        assertFalse(f.grant)
    }

    @Test fun `the card is built from the same aggregation as the board`() {
        val b = board()
        val f = HealthBadgeModel.face(Read.Ok(b))
        assertEquals(HealthAgg.kmStepsText(b.steps.today.km, b.steps.today.steps, b.steps.today.estimated), f.title)
    }

    @Test fun `a missing grant says why and offers Grant access, never zeros`() {
        val raw = HealthFixtures.seeded(today).copy(status = HealthBoardReader.Status.NEEDS_PERMISSION, granted = emptySet(),
            steps = emptyMap(), distanceM = emptyMap(), restingHr = emptyMap(), sleep = emptyMap())
        val f = HealthBadgeModel.face(Read.Ok(board(raw)))
        assertEquals(State.NO_PERMISSION, f.state)
        assertTrue(f.text, f.text.contains("steps") && f.text.contains("sleep") && f.text.contains("Health Connect"))
        assertTrue(f.grant)
        assertFalse(f.title.contains("0"))
    }

    @Test fun `one missing half shows the rest with the reason and the button`() {
        val raw = HealthFixtures.seeded(today).copy(granted = setOf(HealthBoardReader.STEPS, HealthBoardReader.DISTANCE),
            restingHr = emptyMap(), sleep = emptyMap())
        val f = HealthBadgeModel.face(Read.Ok(board(raw)))
        assertEquals(State.DATA, f.state)
        assertTrue(f.text, f.text.contains("Resting HR --") && f.text.contains("Sleep --"))
        assertTrue(f.lines.any { it.startsWith("Not allowed:") && it.contains("sleep") })
        assertTrue(f.grant)
    }

    @Test fun `a refused read maps to no permission and offers the button`() {
        val f = HealthBadgeModel.face(Read.Unavailable(Why.NO_PERMISSION, "Health Connect refused"))
        assertEquals(State.NO_PERMISSION, f.state); assertTrue(f.grant); assertEquals("Health Connect refused", f.text)
    }

    @Test fun `a failed read is a named error`() {
        val f = HealthBadgeModel.face(Read.Unavailable(Why.NOT_INSTALLED, "Health Connect is not available on this phone"))
        assertEquals(State.ERROR, f.state); assertFalse(f.grant)
        assertEquals("Health Connect is not available on this phone", f.text)
    }

    @Test fun `a read that never answers becomes a timeout error, not reading`() {
        val f = HealthBadgeModel.face(null)
        assertEquals(State.TIMEOUT, f.state)
        assertTrue(f.text, f.text.contains("did not answer") && f.text.contains("${HealthBadgeModel.TIMEOUT_S} s"))
        assertFalse(f.text.contains("reading", ignoreCase = true))
    }

    @Test fun `the last face survives a restart as json and its age reads naturally`() {
        val f = HealthBadgeModel.face(Read.Ok(board()))
        assertEquals(f, HealthBadgeModel.faceFromJson(f.toJson()))
        assertNull(HealthBadgeModel.faceFromJson("not json"))
        assertEquals("updated just now", HealthBadgeModel.updated(20_000L))
        assertEquals("updated 4m ago", HealthBadgeModel.updated(4 * 60_000L))
        assertEquals("updated 3h ago", HealthBadgeModel.updated(3 * 3_600_000L))
    }

    @Test fun `the renamed badge keeps its old ids as aliases`() {
        val root = JSONObject(File("../build.json").readText())
        val all = BadgeDeclaration.parse(root.getJSONObject("ui").getJSONObject("notification_center").toString())
        val mine = BadgeDeclaration.resolve(all, "myhealth")
        assertNotNull(mine)
        assertEquals("Health", mine!!.label)
        assertEquals("page:boards/my-pm", root.getJSONObject("ui").getJSONObject("notification_center").getJSONArray("producers")
            .let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id") == "myhealth" }.getString("click_target") })
        assertEquals(mine, BadgeDeclaration.resolve(all, "health_activity"))
        assertEquals(mine, BadgeDeclaration.resolve(all, "health"))
        assertNull(BadgeDeclaration.resolve(all, "nope"))
        assertEquals(HealthBadgeService.BADGE_ID, mine.id)
    }
}
