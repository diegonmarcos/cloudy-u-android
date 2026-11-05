package com.diegonmarcos.superapp.notificationcenter

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * #1151 The AI badge's model: c3-infra-api `GET /ai/usage` -> [Snapshot] -> [Card] (the notification's title, text and
 * "label: value" body lines, which the Notify card reads back unchanged). Pure: no Android, so the numbers, the reset
 * wording and the stale rule are unit-tested without a phone.
 *
 * Claude comes from Claude Code's own statusLine (`rate_limits.five_hour/seven_day`), pushed to the API; the API drops a
 * window whose reset time has passed. OpenRouter is fetched server-side; its activity is DAILY (completed UTC days), so its
 * "24h" is the latest completed day - the API says so in `basis` and the card words it "24h".
 */
object AiUsageModel {

    /** Data older than this is marked stale on the card (Claude's push or the app's own last good read). */
    const val STALE_MS = 15 * 60_000L

    data class Win(val usedPct: Double, val resetsAtSec: Long)
    data class Claude(val updatedAtSec: Long, val fiveHour: Win?, val sevenDay: Win?, val rateLimitedAtSec: Long?)
    data class Spend(val usd: Double, val tokens: Long)
    data class OpenRouter(
        val configured: Boolean, val balanceUsd: Double?, val day: Spend?, val week: Spend?, val month: Spend?,
        val error: String?, val stale: Boolean,
    )
    data class Snapshot(val generatedAtSec: Long, val claude: Claude?, val openrouter: OpenRouter?)

    /** [lines] are "label: value" strings (BadgeCardData splits on the first ": "). [progress] is the 5-hour used %, 0..100. */
    data class Card(val title: String, val text: String, val lines: List<String>, val progress: Int?, val stale: Boolean) {
        val expanded: String get() = lines.joinToString("\n")
    }

    // ── parse ────────────────────────────────────────────────────────────

    /** Null when [body] is not a JSON object. A missing part is simply absent from the snapshot. */
    fun parse(body: String): Snapshot? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        fun win(o: JSONObject?): Win? {
            if (o == null || !o.has("used_percentage") || !o.has("resets_at")) return null
            return Win(o.optDouble("used_percentage", 0.0), o.optLong("resets_at", 0L))
        }
        fun spend(o: JSONObject?): Spend? = if (o == null) null else Spend(o.optDouble("usd", 0.0), o.optLong("tokens", 0L))
        val c = root.optJSONObject("claude")?.let { o ->
            Claude(o.optLong("updated_at", 0L), win(o.optJSONObject("five_hour")), win(o.optJSONObject("seven_day")),
                if (o.isNull("rate_limited_at") || !o.has("rate_limited_at")) null else o.optLong("rate_limited_at"))
        }
        val r = root.optJSONObject("openrouter")?.let { o ->
            val configured = o.optBoolean("configured", false)
            OpenRouter(configured, if (o.has("balance_usd")) o.optDouble("balance_usd") else null,
                spend(o.optJSONObject("last_24h")), spend(o.optJSONObject("last_7d")), spend(o.optJSONObject("month")),
                if (o.has("error")) o.optString("error") else null, o.optBoolean("stale", false))
        }
        return Snapshot(root.optLong("generated_at", 0L), c, r)
    }

    // ── words ────────────────────────────────────────────────────────────

    fun usd(v: Double): String = String.format(Locale.US, "$%.2f", v)
    fun mTokens(t: Long): String = String.format(Locale.US, "%.1fM tok", t / 1_000_000.0)
    private fun spend(s: Spend?): String = s?.let { "${usd(it.usd)} · ${mTokens(it.tokens)}" } ?: "-"
    private fun pct(d: Double): String = "${Math.round(d)}%"

    fun ago(ms: Long): String {
        val m = ms / 60_000L
        return when { m < 1 -> "just now"; m < 60 -> "${m}m ago"; m < 1_440 -> "${m / 60}h ago"; else -> "${m / 1_440}d ago" }
    }

    /** "in 3h 12m" / "in 2d 3h" / "now". */
    fun until(ms: Long): String {
        if (ms <= 0) return "now"
        val m = ms / 60_000L
        return when { m < 60 -> "in ${m}m"; m < 1_440 -> "in ${m / 60}h ${m % 60}m"; else -> "in ${m / 1_440}d ${(m % 1_440) / 60}h" }
    }

    /** One decimal with a point ("0.8"). */
    private fun d1(v: Double): String = String.format(Locale.US, "%.1f", v)

    /**
     * #1451 The weekly burn-rate block, computed here from the raw `seven_day` sample (so the app works without any server
     * maths): elapsed = 7d - (resets_at - now); used avg = used / elapsed; needed = left / days left; "over in" = left / rate.
     * Four lines, the second Est line indented so the colons align:
     *     Used: 29% (in 0.8 days) | 35%/day avg
     *     Left: 71% (6.2 days) | 12%/day
     *     Est: 35%/day (used avg) : over in 2.0 days
     *          14%/day (1/7)      : over in 5.0 days
     */
    fun weekLines(w: Win, nowMs: Long): List<String> {
        val left = (100.0 - w.usedPct).coerceAtLeast(0.0)
        val daysLeft = ((w.resetsAtSec * 1000 - nowMs) / DAY_MS).coerceIn(0.0, 7.0)
        val elapsed = 7.0 - daysLeft
        val avg = if (elapsed > 0.01) w.usedPct / elapsed else null
        val need = if (daysLeft > 0.01) left / daysLeft else null
        val flat = 100.0 / 7.0
        fun over(rate: Double?) = if (rate == null || rate <= 0.0) "-" else "over in ${d1(left / rate)} days"
        val a = "${avg?.let { pct(it) } ?: "-"}/day (used avg)"
        val b = "${pct(flat)}/day (1/7)".padEnd(a.length)
        return listOf(
            "Used: ${pct(w.usedPct)} (in ${d1(elapsed)} days) | ${avg?.let { pct(it) } ?: "-"}/day avg",
            "Left: ${pct(left)} (${d1(daysLeft)} days) | ${need?.let { pct(it) } ?: "-"}/day",
            "Est: $a : ${over(avg)}",
            "     $b : ${over(flat)}",
        )
    }

    private const val DAY_MS = 86_400_000.0

    private fun resets(w: Win, nowMs: Long, zone: TimeZone): String {
        val fmt = SimpleDateFormat(if (w.resetsAtSec * 1000 - nowMs > 20 * 3_600_000L) "EEE HH:mm" else "HH:mm", Locale.US).apply { timeZone = zone }
        return "resets ${fmt.format(java.util.Date(w.resetsAtSec * 1000))} (${until(w.resetsAtSec * 1000 - nowMs)})"
    }

    /**
     * The card. [snap] is the last good read (null = none yet), [fetchedAtMs] when the app got it, [note] why the latest
     * attempt failed (blank = fine). Stale = Claude's push older than [STALE_MS], or the app's own read older than it.
     */
    fun card(snap: Snapshot?, fetchedAtMs: Long, nowMs: Long, note: String = "", zone: TimeZone = TimeZone.getDefault()): Card {
        if (snap == null) return Card("AI usage", if (note.isBlank()) "reading..." else note, emptyList(), null, false)
        val c = snap.claude
        val claudeAgeMs = if (c == null || c.updatedAtSec <= 0) Long.MAX_VALUE else (nowMs - c.updatedAtSec * 1000).coerceAtLeast(0)
        val readAgeMs = (nowMs - fetchedAtMs).coerceAtLeast(0)
        val claudeStale = c != null && claudeAgeMs > STALE_MS
        val readStale = readAgeMs > STALE_MS
        val stale = claudeStale || readStale

        val five = c?.fiveHour; val seven = c?.sevenDay
        val title = if (c == null) "Claude: no data yet"
            else "Claude 5h ${five?.let { pct(it.usedPct) } ?: "-"} · wk ${seven?.let { pct(it.usedPct) } ?: "-"}" + if (claudeStale || readStale) " · stale" else ""
        val or = snap.openrouter
        val text = when {
            or == null -> "OpenRouter: no data"
            !or.configured -> "OpenRouter: key not set"
            or.balanceUsd == null -> "OpenRouter: unavailable" + (or.error?.let { " ($it)" } ?: "")
            else -> "OpenRouter ${usd(or.balanceUsd)} · 24h ${or.day?.let { usd(it.usd) } ?: "-"}"
        }

        val lines = mutableListOf<String>()
        lines += "5h session: " + (five?.let { "${pct(it.usedPct)} · ${resets(it, nowMs, zone)}" } ?: "no window")
        lines += "Week (all models): " + (seven?.let { "${pct(it.usedPct)} · ${resets(it, nowMs, zone)}" } ?: "no window")
        if (seven != null) lines += weekLines(seven, nowMs)
        if (c != null) {
            val rl = c.rateLimitedAtSec?.takeIf { nowMs - it * 1000 < 3_600_000L }
            lines += "Claude data: " + (if (claudeStale) "STALE, " else "") + "pushed " + ago(claudeAgeMs) + (if (rl != null) " · rate limited " + ago(nowMs - rl * 1000) else "")
        }
        if (or != null && or.configured) {
            lines += "OR balance: " + (or.balanceUsd?.let { usd(it) } ?: "-")
            lines += "OR 24h: " + spend(or.day)
            lines += "OR 7 days: " + spend(or.week)
            lines += "OR month: " + spend(or.month)
        }
        if (readStale || note.isNotBlank()) lines += "App read: " + (if (note.isNotBlank()) "$note, " else "") + "last good " + ago(readAgeMs)
        return Card(title, text, lines, five?.usedPct?.let { Math.round(it).toInt().coerceIn(0, 100) }, stale)
    }
}
