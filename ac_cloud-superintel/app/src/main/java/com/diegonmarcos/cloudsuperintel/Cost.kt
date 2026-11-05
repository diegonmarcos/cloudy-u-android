package com.diegonmarcos.cloudsuperintel

import com.diegonmarcos.superapp.notificationcenter.AiUsageClient
import com.diegonmarcos.superapp.notificationcenter.AiUsageModel
import org.json.JSONObject

data class CostRowDecl(val label: String, val claude: String, val openrouter: String)
data class CostConfig(
    val sampleLabel: String, val bases: List<String>, val authority: String, val method: String, val bearerKey: String,
    val columns: List<String>, val rows: List<CostRowDecl>, val sampleOpenRouter: AiUsageModel.OpenRouter,
)
data class CostRow(val label: String, val claude: String, val openrouter: String)
data class CostTable(val rows: List<CostRow>, val sampleOpenRouter: Boolean, val claudeAgeMs: Long?)

/**
 * The Cost page's model. Claude and OpenRouter both come from the usage table the Cloud SuperApp Notify AI badge reads
 * (libs:ai-usage: [AiUsageClient] fetches it, [AiUsageModel] parses it and words the weekly estimate), so no number is
 * computed here that the table already carries. Only the declared sample (assets/ai/cost.json) is local, and it is labelled.
 */
object Cost {
    fun parse(text: String): CostConfig {
        val o = JSONObject(text)
        val u = o.getJSONObject("usage")
        val p = u.getJSONObject("private")
        fun list(k: String) = o.getJSONArray(k).let { a -> (0 until a.length()).map { a.get(it) } }
        val s = o.getJSONObject("sample_openrouter")
        fun spend(k: String) = s.getJSONObject(k).let { AiUsageModel.Spend(it.getDouble("usd"), it.getLong("tokens")) }
        return CostConfig(
            o.getString("sample_label"), u.getJSONArray("bases").let { a -> (0 until a.length()).map { a.getString(it) } },
            p.getString("authority"), p.getString("method"), u.getString("bearer_key"),
            list("columns").map { it.toString() },
            list("rows").map { (it as JSONObject).let { r -> CostRowDecl(r.getString("label"), r.getString("claude"), r.getString("openrouter")) } },
            AiUsageModel.OpenRouter(true, s.getDouble("balance_usd"), spend("last_24h"), spend("last_7d"), spend("month"), null, false),
        )
    }

    /** One read of the table through the shared client, the declared bases in order; null body = no answer (the reason is the second). */
    fun fetch(cfg: CostConfig, bearer: String): Pair<AiUsageModel.Snapshot?, String> =
        when (val out = AiUsageClient.fetchAny(cfg.bases, bearer, AiUsageClient.fleetTransport, AiUsageClient.plainTransport)) {
            is AiUsageClient.Outcome.Ok -> AiUsageModel.parse(out.body) to out.via
            is AiUsageClient.Outcome.Failed -> null to out.reason
        }

    private fun pct(w: AiUsageModel.Win?) = w?.let { "${Math.round(it.usedPct)}%" } ?: "-"
    private fun spend(s: AiUsageModel.Spend?) = s?.let { "${AiUsageModel.usd(it.usd)} · ${AiUsageModel.mTokens(it.tokens)}" } ?: "-"

    private fun claude(metric: String, c: AiUsageModel.Claude?, nowMs: Long): String {
        val w = c?.sevenDay
        val week = w?.let { AiUsageModel.weekLines(it, nowMs) }
        return when (metric) {
            "five_hour" -> pct(c?.fiveHour)
            "seven_day" -> pct(w)
            "week_left" -> week?.get(1)?.removePrefix("Left: ") ?: "-"
            "week_est" -> week?.get(2)?.removePrefix("Est: ")?.replace(" (used avg)", "") ?: "-"
            else -> "-"
        }
    }

    private fun openRouter(metric: String, o: AiUsageModel.OpenRouter): String = when (metric) {
        "last_24h" -> spend(o.day)
        "last_7d" -> spend(o.week)
        "month" -> spend(o.month)
        "balance" -> o.balanceUsd?.let { AiUsageModel.usd(it) } ?: "-"
        "est_30d" -> o.week?.let { AiUsageModel.usd(it.usd / 7.0 * 30.0) } ?: "-"
        else -> "-"
    }

    /** The side-by-side table; OpenRouter falls back to the labelled sample when the table has no configured block. */
    fun table(cfg: CostConfig, snap: AiUsageModel.Snapshot?, nowMs: Long): CostTable {
        val real = snap?.openrouter?.takeIf { it.configured && it.balanceUsd != null }
        val or = real ?: cfg.sampleOpenRouter
        val tag = if (real == null) " (${cfg.sampleLabel})" else ""
        val c = snap?.claude
        val rows = cfg.rows.map { CostRow(it.label, claude(it.claude, c, nowMs), openRouter(it.openrouter, or).let { v -> if (v == "-") v else v + tag }) }
        val age = c?.takeIf { it.updatedAtSec > 0 }?.let { (nowMs - it.updatedAtSec * 1000).coerceAtLeast(0) }
        return CostTable(rows, real == null, age)
    }
}
