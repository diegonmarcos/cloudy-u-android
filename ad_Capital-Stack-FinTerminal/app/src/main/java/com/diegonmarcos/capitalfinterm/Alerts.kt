package com.diegonmarcos.capitalfinterm

import java.util.Locale

/**
 * One alert rule the user typed (Watchlist & Alerts): `AAPL > 230` (price above), `AAPL < 200` (price below) or `events >= 3` (every event of
 * that importance or more in the next [Alerts.EVENT_WINDOW_HOURS] hours). Kept on the device only (EncryptedSecretBox), never sent anywhere.
 */
data class AlertRule(val kind: String, val symbol: String, val level: Double) {
    /** The rule as typed back: one line, no spaces inside a symbol. */
    fun text(): String = when (kind) {
        Alerts.ABOVE -> "$symbol > ${Engine.sig(level)}"
        Alerts.BELOW -> "$symbol < ${Engine.sig(level)}"
        else -> "events >= ${level.toInt()}"
    }
}

/** What the user keeps on the device for the model panels: the watchlist symbols and the alert rules. */
data class UserLists(val watchlist: List<String> = emptyList(), val alerts: List<AlertRule> = emptyList())

/** Watchlist and alert rules: parse, store as one line, evaluate against a panel's points or events. Pure. */
object Alerts {
    const val ABOVE = "above"
    const val BELOW = "below"
    const val EVENTS = "events"
    const val EVENT_WINDOW_HOURS = 24L

    /** A symbol as the user types it: letters, digits and . - ^ _ = (AAPL, BRK.B, ^SPX, BTC-USD), upper case; else null. */
    fun symbol(raw: String): String? = raw.trim().uppercase(Locale.ROOT).takeIf { Regex("^[A-Z0-9.^_=-]{1,15}$").matches(it) }

    /** A watchlist stored as "AAPL,MSFT": the valid symbols, once each, in order. */
    fun watchlist(stored: String?): List<String> = stored.orEmpty().split(',').mapNotNull { symbol(it) }.distinct()

    fun watchlistText(list: List<String>): String = list.distinct().joinToString(",")

    /** The symbol moved `delta` places (-1 up, +1 down) in the watchlist, clamped to its ends; an unknown symbol leaves the list as it is. */
    fun move(list: List<String>, symbol: String, delta: Int): List<String> {
        val i = list.indexOfFirst { it.equals(symbol, ignoreCase = true) }
        if (i < 0) return list
        val j = (i + delta).coerceIn(0, list.lastIndex)
        return list.toMutableList().also { it.add(j, it.removeAt(i)) }
    }

    /** One typed rule, or null when it does not read as `SYM > n`, `SYM < n` or `events >= n` (n from 0 to 3). */
    fun rule(raw: String): AlertRule? {
        val t = raw.trim()
        Regex("^events\\s*>=\\s*([0-3])$", RegexOption.IGNORE_CASE).find(t)?.let { return AlertRule(EVENTS, "", it.groupValues[1].toDouble()) }
        val m = Regex("^(\\S+)\\s*([<>])\\s*(-?[0-9]+(?:\\.[0-9]+)?)$").find(t) ?: return null
        val sym = symbol(m.groupValues[1]) ?: return null
        val level = m.groupValues[3].toDoubleOrNull() ?: return null
        return AlertRule(if (m.groupValues[2] == ">") ABOVE else BELOW, sym, level)
    }

    /** Rules stored as one line, ";" between rules. */
    fun rules(stored: String?): List<AlertRule> = stored.orEmpty().split(';').mapNotNull { rule(it) }

    fun rulesText(list: List<AlertRule>): String = list.joinToString(";") { it.text().replace(" ", "") }

    /** The same symbol across sources: ^SPX, _SPX and SPX are one. */
    fun same(a: String, b: String): Boolean = norm(a) == norm(b)

    private fun norm(s: String): String = s.uppercase(Locale.ROOT).trimStart('^', '_').substringAfterLast(':')

    /** The price rules that hold for [points]: "ALERT AAPL 231.20 > 230". */
    fun priceHits(rules: List<AlertRule>, points: List<Pt>): List<String> = rules.filter { it.kind != EVENTS }.flatMap { r ->
        points.filter { same(it.key, r.symbol) || same(it.label, r.symbol) }.filter { if (r.kind == ABOVE) it.value > r.level else it.value < r.level }
            .map { "ALERT ${r.symbol} ${Engine.sig(it.value)} ${if (r.kind == ABOVE) ">" else "<"} ${Engine.sig(r.level)}" }
    }

    /**
     * The events an `events >= n` rule flags: importance n or more, from [nowUtc] ("YYYY-MM-DDTHH:MMZ") to [EVENT_WINDOW_HOURS] later. An all-day
     * event (a date) is flagged on its day. The lowest n of the rules applies.
     */
    fun eventHits(rules: List<AlertRule>, events: List<Ev>, nowUtc: String): List<String> {
        val min = rules.filter { it.kind == EVENTS }.minOfOrNull { it.level.toInt() } ?: return emptyList()
        val now = java.time.LocalDateTime.parse(nowUtc.removeSuffix("Z"))
        val end = now.plusHours(EVENT_WINDOW_HOURS)
        return events.filter { it.importance >= min }.filter { e ->
            if (e.ts.length == 10) e.ts == nowUtc.take(10)
            else runCatching { java.time.LocalDateTime.parse(e.ts.removeSuffix("Z")) }.getOrNull()?.let { !it.isBefore(now) && !it.isAfter(end) } ?: false
        }.map { "ALERT ${it.ts.replace('T', ' ')} ${it.country} ${it.title}" }
    }
}
