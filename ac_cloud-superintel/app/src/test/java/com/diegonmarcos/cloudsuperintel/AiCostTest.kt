package com.diegonmarcos.cloudsuperintel

import com.diegonmarcos.superapp.notificationcenter.AiUsageClient
import com.diegonmarcos.superapp.notificationcenter.AiUsageModel
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1499 Cost reads the very source the Cloud SuperApp Notify AI badge reads: the shared client and model, the same route and host. */
class AiCostTest {
    private val cfg = Cost.parse(Data.ai("cost.json"))
    private val now = 1_800_000_000_000L
    private val body = """{"generated_at":1800000000,
        "claude":{"updated_at":1799999940,"five_hour":{"used_percentage":29.0,"resets_at":1800010000},"seven_day":{"used_percentage":40.0,"resets_at":1800300000}},
        "openrouter":{"configured":true,"balance_usd":42.5,"last_24h":{"usd":1.5,"tokens":1000000},"last_7d":{"usd":7.0,"tokens":5000000},"month":{"usd":20.0,"tokens":9000000}},
        "claude_tokens":{"updated_at":1799999000,
          "days":[{"period":"2027-01-15","input":1000,"output":2000,"cache_write":3000,"cache_read":4000000,"total":4006000,"cost_usd":1.25,
                   "models":[{"family":"opus","label":"Opus","input":900,"output":1500,"cache_write":3000,"cache_read":3000000,"total":3005400,"cost_usd":1.0},
                             {"family":"haiku","label":"Haiku","input":100,"output":500,"cache_write":0,"cache_read":1000000,"total":1000600,"cost_usd":0.25}]},
                  {"period":"2027-01-14","input":5,"output":5,"cache_write":0,"cache_read":0,"total":10,"cost_usd":0.0,"models":[]}],
          "months":[{"period":"2027-01","input":1005,"output":2005,"cache_write":3000,"cache_read":4000000,"total":4006010,"cost_usd":1.25,
                   "models":[{"family":"fable","label":"Fable","input":1005,"output":2005,"cache_write":3000,"cache_read":4000000,"total":4006010,"cost_usd":1.25}]}]}}"""

    @Test fun costFetchesThroughTheSharedClientOnTheBadgesRoute() {
        val urls = mutableListOf<String>()
        val t = AiUsageClient.Transport { url, _ -> urls += url; AiUsageClient.Reply(200, body) }
        val out = AiUsageClient.fetchAny(cfg.bases, "", t, t)
        assertTrue(out is AiUsageClient.Outcome.Ok)
        assertEquals(cfg.bases.first() + AiUsageClient.PATH, urls.first())
        assertEquals("/ai/usage", AiUsageClient.PATH)
    }

    @Test fun theBadgesDeclaredEndpointIsOneOfCostsBases() {
        val badge = JSONObject(File("../../aa_cloud-superapp/build.json").readText()).getJSONObject("ui").getJSONObject("c3_ops").getString("base_url")
        assertTrue("$badge not in ${cfg.bases}", badge in cfg.bases)
    }

    @Test fun aSecondBaseIsTriedWhenTheFirstFails() {
        val t = AiUsageClient.Transport { url, _ -> if (url.contains("cloudy-c3-api")) AiUsageClient.Reply(404, "") else AiUsageClient.Reply(200, body) }
        assertTrue(AiUsageClient.fetchAny(cfg.bases, "", t, t) is AiUsageClient.Outcome.Ok)
    }

    @Test fun theTableIsClaudeAndOpenRouterSideBySideFromTheSameSnapshot() {
        val snap = AiUsageModel.parse(body)!!
        val t = Cost.table(cfg, snap, now)
        assertEquals(listOf("Daily", "Weekly", "Monthly", "Total", "Estimate"), t.rows.map { it.label })
        assertEquals(listOf("Claude", "OpenRouter"), cfg.columns)
        // #1543 Daily / Monthly Claude are the token table's newest day / month, never the 5-hour session % (29 here).
        assertEquals("\$1.25 · 4.0M tok", t.rows[0].claude)
        assertEquals("40%", t.rows[1].claude)
        assertEquals("\$1.25 · 4.0M tok", t.rows[2].claude)
        assertEquals("\$1.50 · 1.0M tok", t.rows[0].openrouter)
        assertEquals("\$7.00 · 5.0M tok", t.rows[1].openrouter)
        assertEquals("\$20.00 · 9.0M tok", t.rows[2].openrouter)
        assertEquals("\$42.50", t.rows[3].openrouter)
        assertEquals("\$30.00", t.rows[4].openrouter)   // 7.00 / 7 * 30
        assertFalse(t.sampleOpenRouter)
        // The weekly estimate is the badge's own wording (AiUsageModel.weekLines), not a second computation.
        assertTrue(t.rows[4].claude.contains("over in"))
        assertEquals(60_000L, t.claudeAgeMs)
    }

    @Test fun theWeeklyNowBlockIsTheBadgesOwnComputation() {
        val snap = AiUsageModel.parse(body)!!
        val w = Cost.week(snap, now)!!
        assertEquals(AiUsageModel.weekLines(snap.claude!!.sevenDay!!, now), w.lines())
        assertEquals(4, w.lines().size)
        assertTrue(w.lines()[0].startsWith("Used: 40% (in "))
        assertNull(Cost.week(null, now))
    }

    @Test fun theDailyAndMonthlyTablesListEachPeriodThenItsModels() {
        val t = AiUsageModel.parse(body)!!.tokens!!
        assertEquals(listOf("Input", "Output", "Cache write", "Cache read", "Total", "Cost"), cfg.tokenColumns)
        val d = Cost.tokenLines(t.days)
        assertEquals(listOf("2027-01-15", "Opus", "Haiku", "2027-01-14"), d.map { it.label })
        assertEquals(listOf(false, true, true, false), d.map { it.child })
        assertEquals(listOf("1.0k", "2.0k", "3.0k", "4.0M", "4.0M", "\$1.25"), d[0].cells)
        assertEquals(listOf("100", "500", "0", "1.0M", "1.0M", "\$0.25"), d[2].cells)
        assertEquals(listOf("2027-01", "Fable"), Cost.tokenLines(t.months).map { it.label })
        assertTrue(Cost.tokenLines(emptyList()).isEmpty())
    }

    @Test fun theBadgesDeepLinkOpensCostAndNothingElseIsAccepted() {
        val l = AiUsageModel.SuperIntelLink
        assertEquals(SiOpen.Target("cost", "cost"), SiOpen.target(l.ACTION, l.COST, l.COST))
        assertEquals(SiOpen.Target("cost", null), SiOpen.target(l.ACTION, "cost", " "))
        assertNull(SiOpen.target("android.intent.action.MAIN", "cost", "cost"))
        assertNull(SiOpen.target(l.ACTION, "", "cost"))
        assertEquals(l.PACKAGE, JSONObject(File("../build.json").readText()).getJSONObject("android").getString("application_id"))
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("<action android:name=\"${l.ACTION}\" />"))
        val bj = JSONObject(File("../build.json").readText())
        val sections = bj.getJSONObject("ui").getJSONArray("sections")
        assertTrue((0 until sections.length()).any { sections.getJSONObject(it).getString("id") == l.COST })
    }

    @Test fun withoutAConfiguredOpenRouterTheSampleShowsLabelled() {
        val snap = AiUsageModel.parse("""{"generated_at":1,"openrouter":{"configured":false}}""")!!
        val t = Cost.table(cfg, snap, now)
        assertTrue(t.sampleOpenRouter)
        t.rows.map { it.openrouter }.filter { it != "-" }.forEach { assertTrue(it, it.endsWith("(sample)")) }
        t.rows.forEach { assertEquals("-", it.claude) }   // no Claude block: no invented figure
    }

    @Test fun noSnapshotMeansNoClaudeFigures() {
        Cost.table(cfg, null, now).rows.forEach { assertEquals("-", it.claude) }
    }

    @Test fun costKotlinHasNoClientOrRouteOfItsOwn() {
        val src = File("src/main/java/com/diegonmarcos/cloudsuperintel/Cost.kt").readText()
        assertTrue(src.contains("AiUsageClient.fetchAny"))
        assertFalse(src.contains("/ai/usage"))
        assertFalse(src.contains("HttpURLConnection"))
    }
}
