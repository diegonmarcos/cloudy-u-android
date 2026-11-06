package com.diegonmarcos.superapp.notificationcenter

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.TimeZone

/** #1151 c3-infra-api /ai/usage -> the AI card: the figures, the reset wording, the stale rule, and the posted card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AiUsageModelTest {

    private val utc = TimeZone.getTimeZone("UTC")
    // 2026-10-24T12:00:00Z
    private val now = 1_792_843_200_000L
    private val sec = now / 1000

    /** The shape GET /ai/usage answers (see c3-infra-api test-ai-usage.ts). */
    private fun body(
        pushedAgo: Long = 120, five: String = """{"used_percentage":23.5,"resets_at":${sec + 3 * 3600 + 12 * 60}}""",
        seven: String = """{"used_percentage":41.2,"resets_at":${sec + 2 * 86400 + 3 * 3600}}""",
        rate: String = "null", openrouter: String = """{"configured":true,"fetched_at":$sec,"balance_usd":37.654322,
            "last_24h":{"usd":2.0,"tokens":2000000},"last_7d":{"usd":7.0,"tokens":6000000},"month":{"usd":11.0,"tokens":10000000}}""",
    ) = """{"generated_at":$sec,"claude":{"updated_at":${sec - pushedAgo},"source":"statusline","five_hour":$five,"seven_day":$seven,
        "rate_limited_at":$rate,"history":[]},"openrouter":$openrouter}"""

    private fun card(b: String = body(), fetchedAgoMs: Long = 0, note: String = "") =
        AiUsageModel.card(AiUsageModel.parse(b), now - fetchedAgoMs, now, note, utc)

    @Test fun `the weekly percent and its reset lead the title, openrouter balance and day lead the text`() {
        val c = card()
        assertEquals("Claude week 41% · resets in 2d 3h", c.title)
        assertEquals("OpenRouter $37.65 · 24h $2.00", c.text)
        assertEquals("the bar is the weekly limit, not the 5-hour session", 41, c.progress)
        assertFalse(c.stale)
    }

    @Test fun `reset times are shown as a clock time and as time left`() {
        val lines = card().lines
        assertTrue(lines.toString(), lines.contains("5h session: 24% · resets 15:12 (in 3h 12m)"))
        assertTrue(lines.toString(), lines.contains("Week resets: Mon 15:00 (in 2d 3h)"))
    }

    @Test fun `the card opens with the four weekly lines, monospaced, red where the week runs out first`() {
        val c = card()
        assertEquals(4, c.weekCount)
        assertEquals(AiUsageModel.weekLines(AiUsageModel.parse(body())!!.claude!!.sevenDay!!, now), c.lines.take(4))
        assertTrue(c.lines[0], c.lines[0].startsWith("Used: 41% (in 4.9 days) | 8%/day avg"))
        // 41% in 4.9 days = 8%/day: 59% left lasts 7.0 days, past the reset in 2.1 -> not red; 1/7 lasts 4.1 -> not red.
        assertTrue(c.alert.isEmpty())
        val hot = card(body(seven = """{"used_percentage":90,"resets_at":${sec + 2 * 86400}}"""))
        assertEquals(setOf(2, 3), hot.alert)
    }

    @Test fun `openrouter shows balance and each window in dollars and millions of tokens`() {
        val lines = card().lines
        assertTrue(lines.contains("OR balance: $37.65"))
        assertTrue(lines.contains("OR 24h: $2.00 · 2.0M tok"))
        assertTrue(lines.contains("OR 7 days: $7.00 · 6.0M tok"))
        assertTrue(lines.contains("OR month: $11.00 · 10.0M tok"))
    }

    @Test fun `a claude push older than fifteen minutes is marked stale in the title and the lines`() {
        val c = card(body(pushedAgo = 16 * 60L))
        assertTrue(c.stale)
        assertTrue(c.title, c.title.endsWith("· stale"))
        assertTrue(c.lines.toString(), c.lines.any { it.startsWith("Claude data: STALE, pushed 16m ago") })
        assertFalse("14 minutes is fresh", card(body(pushedAgo = 14 * 60L)).stale)
    }

    @Test fun `the apps own read older than fifteen minutes is stale even when claude pushed just now`() {
        val c = card(fetchedAgoMs = 20 * 60_000L, note = "public leg: HTTP 503")
        assertTrue(c.stale)
        assertTrue(c.lines.toString(), c.lines.any { it == "App read: public leg: HTTP 503, last good 20m ago" })
    }

    @Test fun `a failed read with no earlier data says why instead of showing numbers`() {
        val c = AiUsageModel.card(null, 0L, now, "no fleet bearer on this device", utc)
        assertEquals("AI usage", c.title)
        assertEquals("no fleet bearer on this device", c.text)
        assertTrue(c.lines.isEmpty())
        assertNull(c.progress)
    }

    @Test fun `windows the server dropped and a missing key degrade to dashes and a hint`() {
        val c = card(body(five = "null", seven = "null", openrouter = """{"configured":false,"error":"OPENROUTER_MANAGEMENT_KEY not set"}"""))
        assertEquals("Claude week -", c.title)
        assertEquals("OpenRouter: key not set", c.text)
        assertNull(c.progress)
        assertTrue(c.lines.contains("5h session: no window"))
        assertFalse(c.lines.any { it.startsWith("OR ") })
    }

    @Test fun `no claude object at all is no data yet, and garbage is not a snapshot`() {
        assertEquals("Claude: no data yet", AiUsageModel.card(AiUsageModel.parse("""{"generated_at":1,"claude":null,"openrouter":null}"""), now, now, "", utc).title)
        assertNull(AiUsageModel.parse("<html>"))
        assertNull(AiUsageModel.parse(""))
    }

    @Test fun `a recent rate limit event from a cloud session is shown`() {
        val c = card(body(rate = "${sec - 600}"))
        assertTrue(c.lines.toString(), c.lines.any { it.contains("rate limited 10m ago") })
    }

    @Test fun `the declared AI badge renders its card from the notification it posted`() {
        val ctx = RuntimeEnvironment.getApplication()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(AiBadgeService.CHANNEL_ID, "AI usage", NotificationManager.IMPORTANCE_LOW))
        val c = card()
        nm.notify(AiBadgeService.NOTIF_ID, NotificationCompat.Builder(ctx, AiBadgeService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon).setContentTitle(c.title).setContentText(c.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(AiBadgeService.styled(c))).setProgress(100, c.progress!!, false).build())
        val b = BadgeServices.declared.first { it.id == AiBadgeService.BADGE_ID }
        BadgeCardData.override = null
        val d = BadgeCardData.read(ctx, b)
        assertEquals(c.title, d.headline)
        assertEquals(c.text, d.sub)
        assertEquals(0.41f, d.progress!!, 0.001f)
        assertEquals("the card carries every row, not the default four", c.lines.size, d.lines.size)
        assertTrue(d.lines.any { it.label == "5h session" && it.value.startsWith("24% · resets") })
        assertTrue(d.lines.any { it.label == "OR 24h" && it.value == "$2.00 · 2.0M tok" })
        // #1543 the weekly block comes back whole and monospaced, its alignment intact
        assertEquals(c.lines.take(4), d.lines.take(4).map { it.value })
        assertTrue(d.lines.take(4).all { it.mono && it.label.isEmpty() })
        assertFalse(d.lines.drop(4).any { it.mono })
        assertNotNull(NotifyGroups.groupOf(AiBadgeService.BADGE_ID))
    }
}
