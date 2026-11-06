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
 * window whose reset time has passed. #1543: the card leads with seven_day (the weekly limit), not five_hour; per-day
 * token totals per model family come in `claude_tokens` (posted from the `my-ai usage` daemon). OpenRouter is fetched server-side; its activity is DAILY (completed UTC days), so its
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
    /** #1543 One token bucket (the transcript usage fields summed): input, output, cache write, cache read, total, cost. */
    data class Tok(val input: Long, val output: Long, val cacheWrite: Long, val cacheRead: Long, val total: Long, val costUsd: Double)
    /** A model family's share of a period ("Opus"), labelled by the API's runtime catalogue. */
    data class FamilyRow(val label: String, val tok: Tok)
    /** One day ("2026-11-05", UTC) or month ("2026-11") with its total and per-family rows. */
    data class Period(val period: String, val tok: Tok, val models: List<FamilyRow>)
    /** `claude_tokens` of GET /ai/usage: newest first. */
    data class Tokens(val updatedAtSec: Long, val days: List<Period>, val months: List<Period>)
    data class Snapshot(val generatedAtSec: Long, val claude: Claude?, val openrouter: OpenRouter?, val tokens: Tokens? = null)

    /**
     * [lines] are "label: value" strings (BadgeCardData splits on the first ": "); the first [weekCount] lines are the
     * weekly block, drawn monospaced as whole lines; [alert] holds the indices drawn red (the week runs out before its
     * reset). [progress] is the WEEKLY (seven_day) used %, 0..100.
     */
    data class Card(
        val title: String, val text: String, val lines: List<String>, val progress: Int?, val stale: Boolean,
        val weekCount: Int = 0, val alert: Set<Int> = emptySet(),
    ) {
        val expanded: String get() = lines.joinToString("\n")
    }

    /**
     * #1543 The weekly now-usage, ONE pure computation shared by the Notify badge and SuperIntel's Cost page.
     * week start = resets_at - 7 d; elapsed = now - start; avg = used / elapsed; left = 100 - used; remaining =
     * resets_at - now; need = left / remaining; "over in" = left / rate, for the used avg and for the flat 100/7 rate.
     * [runsOutAvg] / [runsOutFlat]: that "over in" is shorter than [remainingDays], i.e. the week is spent before it resets.
     */
    data class Week(
        val usedPct: Double, val leftPct: Double, val elapsedDays: Double, val remainingDays: Double,
        val avgPerDay: Double?, val needPerDay: Double?, val flatPerDay: Double,
        val overAvgDays: Double?, val overFlatDays: Double, val runsOutAvg: Boolean, val runsOutFlat: Boolean,
    ) {
        /** The four lines, columns aligned (the "|" under the "|", the ":" under the ":"):
         *     Used: 58% (in 2.9 days) | 20%/day avg
         *     Left: 42% (4.1 days)    | 10%/day
         *     Est: 20%/day (used avg) : over in 2.1 days
         *          14%/day (1/7)      : over in 2.9 days */
        fun lines(): List<String> {
            val u = "Used: ${pct(usedPct)} (in ${d1(elapsedDays)} days)"
            val l = "Left: ${pct(leftPct)} (${d1(remainingDays)} days)"
            val w = maxOf(u.length, l.length)
            val a = "${avgPerDay?.let { pct(it) } ?: "-"}/day (used avg)"
            val b = "${pct(flatPerDay)}/day (1/7)"
            val w2 = maxOf(a.length, b.length)
            fun over(d: Double?) = d?.let { "over in ${d1(it)} days" } ?: "-"
            return listOf(
                "${u.padEnd(w)} | ${avgPerDay?.let { pct(it) } ?: "-"}/day avg",
                "${l.padEnd(w)} | ${needPerDay?.let { pct(it) } ?: "-"}/day",
                "Est: ${a.padEnd(w2)} : ${over(overAvgDays)}",
                "     ${b.padEnd(w2)} : ${over(overFlatDays)}",
            )
        }
        /** Indices into [lines] to draw red. */
        val alert: Set<Int> get() = buildSet { if (runsOutAvg) add(2); if (runsOutFlat) add(3) }
    }

    fun week(w: Win, nowMs: Long): Week {
        val used = w.usedPct.coerceIn(0.0, 100.0)
        val left = 100.0 - used
        val remaining = ((w.resetsAtSec * 1000 - nowMs) / DAY_MS).coerceIn(0.0, 7.0)
        val elapsed = 7.0 - remaining
        val avg = if (elapsed > 0.01) used / elapsed else null
        val need = if (remaining > 0.01) left / remaining else null
        val flat = 100.0 / 7.0
        val overAvg = avg?.takeIf { it > 0.0 }?.let { left / it }
        val overFlat = left / flat
        return Week(used, left, elapsed, remaining, avg, need, flat, overAvg, overFlat,
            overAvg != null && overAvg < remaining, overFlat < remaining)
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
        return Snapshot(root.optLong("generated_at", 0L), c, r, root.optJSONObject("claude_tokens")?.let(::tokens))
    }

    private fun tok(o: JSONObject) = Tok(o.optLong("input"), o.optLong("output"), o.optLong("cache_write"), o.optLong("cache_read"),
        o.optLong("total", o.optLong("input") + o.optLong("output") + o.optLong("cache_write") + o.optLong("cache_read")), o.optDouble("cost_usd", 0.0))

    private fun periods(a: org.json.JSONArray?): List<Period> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val m = o.optJSONArray("models")
        Period(o.optString("period"), tok(o), if (m == null) emptyList() else (0 until m.length()).mapNotNull { j ->
            m.optJSONObject(j)?.let { f -> FamilyRow(f.optString("label", f.optString("family", "?")), tok(f)) }
        })
    }

    private fun tokens(o: JSONObject) = Tokens(o.optLong("updated_at", 0L), periods(o.optJSONArray("days")), periods(o.optJSONArray("months")))

    /** Compact token count: 812, 35.4k, 1.2M, 3.40B. */
    fun count(n: Long): String = when {
        n >= 1_000_000_000L -> String.format(Locale.US, "%.2fB", n / 1e9)
        n >= 1_000_000L -> String.format(Locale.US, "%.1fM", n / 1e6)
        n >= 1_000L -> String.format(Locale.US, "%.1fk", n / 1e3)
        else -> n.toString()
    }

    // ── words ────────────────────────────────────────────────────────────

    fun usd(v: Double): String = String.format(Locale.US, "$%.2f", v)

    /** #1543 Tapping the badge opens Cloud SuperIntel on its Cost page: this action, this package, these extras. */
    object SuperIntelLink {
        const val PACKAGE = "com.diegonmarcos.cloudsuperintel"
        const val ACTION = "com.diegonmarcos.cloudsuperintel.OPEN"
        const val EXTRA_SECTION = "section"
        const val EXTRA_PAGE = "page"
        const val COST = "cost"
    }
    fun mTokens(t: Long): String = String.format(Locale.US, "%.1fM tok", t / 1_000_000.0)
    private fun spend(s: Spend?): String = s?.let { "${usd(it.usd)} · ${mTokens(it.tokens)}" } ?: "-"
    internal fun pct(d: Double): String = "${Math.round(d)}%"

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
    internal fun d1(v: Double): String = String.format(Locale.US, "%.1f", v)

    /** #1451 / #1543 The weekly block's four lines ([Week.lines] of [week]). */
    fun weekLines(w: Win, nowMs: Long): List<String> = week(w, nowMs).lines()

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
        // #1543 The badge is the WEEKLY (seven_day, all models) figure: the title, the progress bar and the first four lines.
        val title = if (c == null) "Claude: no data yet"
            else "Claude week ${seven?.let { pct(it.usedPct) } ?: "-"}" + (seven?.let { " · resets " + until(it.resetsAtSec * 1000 - nowMs) } ?: "") +
                if (claudeStale || readStale) " · stale" else ""
        val or = snap.openrouter
        val text = when {
            or == null -> "OpenRouter: no data"
            !or.configured -> "OpenRouter: key not set"
            or.balanceUsd == null -> "OpenRouter: unavailable" + (or.error?.let { " ($it)" } ?: "")
            else -> "OpenRouter ${usd(or.balanceUsd)} · 24h ${or.day?.let { usd(it.usd) } ?: "-"}"
        }

        val lines = mutableListOf<String>()
        val wk = seven?.let { week(it, nowMs) }
        if (wk != null) lines += wk.lines() else lines += "Week (all models): no window"
        lines += "Week resets: " + (seven?.let { resets(it, nowMs, zone).removePrefix("resets ") } ?: "-")
        lines += "5h session: " + (five?.let { "${pct(it.usedPct)} · ${resets(it, nowMs, zone)}" } ?: "no window")
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
        return Card(title, text, lines, seven?.usedPct?.let { Math.round(it).toInt().coerceIn(0, 100) }, stale,
            if (wk != null) 4 else 0, wk?.alert.orEmpty())
    }
}
