package com.diegonmarcos.cloudsuperintel

import com.diegonmarcos.superapp.notificationcenter.AiUsageClient
import com.diegonmarcos.superapp.notificationcenter.AiUsageModel
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1499 Cost reads the very source the Cloud SuperApp Notify AI badge reads: the shared client and model, the same route and host. */
class AiCostTest {
    private val cfg = Cost.parse(Data.ai("cost.json"))
    private val now = 1_800_000_000_000L
    private val body = """{"generated_at":1800000000,
        "claude":{"updated_at":1799999940,"five_hour":{"used_percentage":29.0,"resets_at":1800010000},"seven_day":{"used_percentage":40.0,"resets_at":1800300000}},
        "openrouter":{"configured":true,"balance_usd":42.5,"last_24h":{"usd":1.5,"tokens":1000000},"last_7d":{"usd":7.0,"tokens":5000000},"month":{"usd":20.0,"tokens":9000000}}}"""

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
        assertEquals("29%", t.rows[0].claude)
        assertEquals("40%", t.rows[1].claude)
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
