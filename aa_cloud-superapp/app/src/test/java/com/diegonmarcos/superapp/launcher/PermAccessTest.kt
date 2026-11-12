package com.diegonmarcos.superapp.launcher

import com.diegonmarcos.superapp.launcher.PermAccess.SensorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1673 the pure rules of the Permission Access Status row and its 24 h dashboard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PermAccessTest {

    private val now = 1_800_000_000_000L
    private val utc = TimeZone.getTimeZone("UTC")

    @Test fun `icon state mapping active idle unknown`() {
        assertEquals(SensorState.ACTIVE, PermAccess.state(watching = true, active = true))
        assertEquals(SensorState.IDLE, PermAccess.state(watching = true, active = false))
        assertEquals(SensorState.UNKNOWN, PermAccess.state(watching = false, active = false))
        // A use the launcher did see still lights it without a live source.
        assertEquals(SensorState.ACTIVE, PermAccess.state(watching = false, active = true))
    }

    @Test fun `dashboard groups are every group Android reports`() {
        val labels = PermAccess.GROUPS.map { it.label }
        for (want in listOf("Location", "Phone", "Nearby devices", "Physical activity", "Calendar", "Contacts and accounts", "Call logs",
            "Health / fitness and wellness", "Camera", "Microphone", "Photos and videos", "Notifications", "Files", "SMS"))
            assertTrue("missing group $want", want in labels)
        assertEquals("a group id is duplicated", labels.size, PermAccess.GROUPS.map { it.id }.toSet().size)
        val ops = PermAccess.GROUPS.flatMap { it.ops }
        assertEquals("an op is in two groups", ops.size, ops.toSet().size)
        assertTrue(PermAccess.GROUPS.all { it.ops.isNotEmpty() })
        // Every segment is one the broker's reads-1 rule allows.
        val rule = Regex("""^dumpsys [\w.:/=,@+*?-]+( [\w.:/=,@+*?-]+)*$""")
        assertTrue(PermAccess.DASHBOARD_CMD.split("; ").all { rule.matches(it) })
        assertEquals(ops.size, PermAccess.DASHBOARD_CMD.split("; ").size)
    }

    private val modern = """
Current AppOps Service state:
  Uid u0a101:
    state=top
    Package com.example.rec:
      RECORD_AUDIO (allow):
        null=[
          Access: [top-s] 2027-01-15 07:00:00.000 (-10m0s0ms) duration=+5s
          Access: [fg-s] 2027-01-15 06:00:00.000 (-1h10m0s0ms) duration=+2s
        ]
      FINE_LOCATION (allow):
        Access: [top-s] 2027-01-15 07:00:00.000 (-30m) duration=+1s
        Running start at: +2m ago
  Uid u0a102:
    Package com.example.old:
      RECORD_AUDIO (allow):
        Access: [top-s] 2027-01-13 07:00:00.000 (-2d0h0m0s0ms) duration=+5s
      CAMERA (allow): time=+3h0m0s ago; duration=+1s
""".trimIndent()

    @Test fun `parses accesses and the 24 h window`() {
        val uses = PermAccess.parseDumpsys(modern, now)
        val rec = uses.single { it.pkg == "com.example.rec" && it.group == "microphone" }
        assertEquals(2, rec.count)
        assertEquals(now - 10 * 60_000L, rec.lastMs)
        assertFalse(rec.running)
        val loc = uses.single { it.pkg == "com.example.rec" && it.group == "location" }
        assertTrue(loc.running)
        assertTrue(PermAccess.locationRunning(uses))
        // Two days old: outside the 24 h window, dropped.
        assertTrue(uses.none { it.pkg == "com.example.old" && it.group == "microphone" })
        // The older one-line form.
        assertEquals(now - 3 * 3_600_000L, uses.single { it.pkg == "com.example.old" && it.group == "camera" }.lastMs)
    }

    @Test fun `absolute time is used when no age is printed`() {
        val at = PermAccess.accessMs("Access: [top-s] 2027-01-15 07:00:00.250 duration=+1s", now, utc)
        assertEquals(java.time.Instant.parse("2027-01-15T07:00:00.250Z").toEpochMilli(), at)
    }

    @Test fun `dashboard has all groups, used first, unused greyed, overflow past three`() {
        val uses = (1..5).map { PermAccess.Use("camera", "p$it", it, now - it * 1000L, false) } +
            PermAccess.Use("sms", "q", 1, now - 500L, false)
        val d = PermAccess.dashboard(uses, now)
        assertEquals(PermAccess.GROUPS.size, d.rows.size)
        assertEquals(listOf("sms", "camera"), d.rows.take(2).map { it.group.id })
        val cam = d.rows.first { it.group.id == "camera" }
        assertEquals(3, cam.shown.size)
        assertEquals(2, cam.overflow)
        assertEquals(listOf("p1", "p2", "p3"), cam.shown.map { it.pkg })
        assertEquals(15, cam.total)
        assertTrue(d.rows.drop(2).none { it.used })
        assertEquals("Not used in the past 24 hours", PermAccess.summary(d.rows.last()))
        assertFalse(d.empty)
    }

    @Test fun `empty and garbage input never invent use`() {
        assertTrue(PermAccess.parseDumpsys(null, now).isEmpty())
        assertTrue(PermAccess.parseDumpsys("Error: unknown op FOO", now).isEmpty())
        assertTrue(PermAccess.dashboard(emptyList(), now).empty)
    }

    @Test fun `no access and refused reads are NoAccess, never data`() {
        assertTrue(PermAccessUi.load({ null }, now) is PermAccess.Dashboard.NoAccess)
        assertTrue(PermAccessUi.load({ "Permission Denial: can't dump" }, now) is PermAccess.Dashboard.NoAccess)
        assertTrue(PermAccessUi.load({ throw IllegalStateException("x") }, now) is PermAccess.Dashboard.NoAccess)
        val ok = PermAccessUi.load({ modern }, now)
        assertTrue(ok is PermAccess.Dashboard.Ready && !ok.empty)
    }
}
