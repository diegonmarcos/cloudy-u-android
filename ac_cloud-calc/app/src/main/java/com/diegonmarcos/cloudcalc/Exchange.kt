package com.diegonmarcos.cloudcalc

import android.content.SharedPreferences
import com.diegonmarcos.cloudcalc.engine.CalcApi
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

/**
 * #1945 Finance > Exchange, the pure part (a JVM test holds it): the declared spec, the cross-rate maths from ONE base table,
 * the number and time formats, the selection edits and the two stores (the rate table with its timestamp, the user's picks).
 * The app opens no URL itself (C6): the engine downloads the declared mid-market feed, [Rates] reads its table through the engine.
 */
object Exchange {
    /** The app's existing declared store (fleet-config "cloud_calc"); every Exchange key is prefixed so it never meets History's. */
    const val PREFS = "cloud_calc"

    /** build.json::ui.modes[exchange].spec. */
    data class Spec(
        val sourceName: String, val sourceUrl: String, val base: String,
        val maxTargets: Int, val amountDecimals: Int, val rateSignificant: Int,
    )

    fun spec(mode: Declarations.Mode): Spec = JSONObject(mode.spec).let {
        Spec(
            it.optString("source_name"), it.optString("source_url"), it.optString("base", "EUR"),
            it.optInt("max_targets", 12), it.optInt("amount_decimals", 2), it.optInt("rate_significant", 4),
        )
    }

    /**
     * What the user picked: the source currency, the typed amount (canonical, "." decimal) and the target currencies in card order.
     * #1963 every box is editable: [active] is the index of the box the amount was typed in (0 = the source box, i + 1 = targets[i]);
     * every other box is an output computed from it.
     */
    data class State(val source: String, val amount: String, val targets: List<String>, val active: Int = 0) {
        val boxes: List<String> get() = listOf(source) + targets
    }

    fun defaults(mode: Declarations.Mode): State = State(
        mode.defaults["source"].orEmpty(),
        mode.defaults["amount"].orEmpty(),
        runCatching { JSONArray(mode.defaults["targets"]).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList()),
    )

    // ── selection edits ───────────────────────────────────────────────────────────────────────

    /** A new source; when it was a target the old source takes its card (so a code is never twice on screen). */
    fun setSource(s: State, code: String): State = when {
        code == s.source -> s
        code in s.targets -> s.copy(source = code, targets = s.targets.map { if (it == code) s.source else it })
        else -> s.copy(source = code)
    }

    /** Card [index] now shows [code]; a code already on another card or the source is swapped, never duplicated. */
    fun setTarget(s: State, index: Int, code: String): State {
        if (index !in s.targets.indices || code == s.targets[index]) return s
        val old = s.targets[index]
        return when {
            code == s.source -> s.copy(source = old, targets = s.targets.toMutableList().also { it[index] = code })
            code in s.targets -> s.copy(targets = s.targets.map { if (it == code) old else if (it == old) code else it })
            else -> s.copy(targets = s.targets.toMutableList().also { it[index] = code })
        }
    }

    fun addTarget(s: State, code: String, max: Int): State =
        if (code == s.source || code in s.targets || s.targets.size >= max) s else s.copy(targets = s.targets + code)

    fun removeTarget(s: State, code: String): State {
        val i = s.targets.indexOf(code)
        if (i < 0) return s
        val box = i + 1
        return s.copy(targets = s.targets - code, active = if (s.active == box) 0 else if (s.active > box) s.active - 1 else s.active)
    }

    /** The amount typed in box [index] (canonical): that box becomes the input, the others are outputs of it. */
    fun typed(s: State, index: Int, canonicalAmount: String): State = if (index in s.boxes.indices) s.copy(active = index, amount = canonicalAmount) else s

    /** Box [index] becomes the input keeping the value it showed as an output (exact, not the rounded display), so no value drifts. */
    fun activate(s: State, perBase: Map<String, Double>, index: Int): State {
        if (index !in s.boxes.indices || index == s.active) return s
        val v = values(s, perBase, parseDecimal(s.amount, Locale.ROOT))[index]
        return s.copy(active = index, amount = v?.let { canonical(it) } ?: "")
    }

    /** Every box's value: the input box shows [typed], each other box is [typed] converted from the input currency (null = unknown). */
    fun values(s: State, perBase: Map<String, Double>, typed: BigDecimal?): List<BigDecimal?> {
        val from = s.boxes.getOrNull(s.active) ?: return s.boxes.map { null }
        return s.boxes.mapIndexed { i, code -> if (i == s.active) typed else typed?.let { convertExact(it, perBase, from, code) } }
    }

    // ── maths ─────────────────────────────────────────────────────────────────────────────────

    /** How much [to] one [from] buys, from one base table ([perBase]: units of each currency per 1 base unit): perBase[to] / perBase[from]; null when a code is missing. */
    fun rate(perBase: Map<String, Double>, from: String, to: String): Double? {
        if (from == to) return 1.0
        val a = perBase[from]; val b = perBase[to]
        return if (a == null || b == null || a == 0.0) null else b / a
    }

    /**
     * A -> EUR -> B in decimal arithmetic (amount * perBase[to] / perBase[from], 34 digits), so A -> B -> A returns the amount
     * to the last digit shown; null when a code is missing.
     */
    fun convertExact(amount: BigDecimal, perBase: Map<String, Double>, from: String, to: String): BigDecimal? {
        if (from == to) return amount
        val a = perBase[from]; val b = perBase[to]
        if (a == null || b == null || a == 0.0 || !a.isFinite() || !b.isFinite()) return null
        return amount.multiply(BigDecimal(b)).divide(BigDecimal(a), MathContext.DECIMAL128)
    }

    fun convert(amount: Double, perBase: Map<String, Double>, from: String, to: String): Double? = rate(perBase, from, to)?.let { it * amount }

    // ── formats ───────────────────────────────────────────────────────────────────────────────

    /** An amount with [decimals] decimals in [locale]'s grouping and decimal separator. */
    fun amountText(x: Double, locale: Locale, decimals: Int = 2): String =
        if (!x.isFinite()) "—" else NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = decimals; maximumFractionDigits = decimals }.format(x)

    /** A unit rate to 4 decimal places (0.010802 -> 0.0108); only when that would print zero, [significant] significant digits (0.00001234 -> 0.00001234). */
    fun rateText(x: Double, locale: Locale, significant: Int = 4): String {
        if (!x.isFinite()) return "—"
        val fixed = BigDecimal(x).setScale(4, java.math.RoundingMode.HALF_UP)
        val bd = if (fixed.signum() == 0 && x != 0.0) BigDecimal(x).round(MathContext(significant)).stripTrailingZeros() else fixed
        val scale = bd.scale().coerceAtLeast(0)
        return NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = scale; maximumFractionDigits = scale }.format(bd)
    }

    /** "1 INR → 0.0108 USD"; an unknown rate reads "—". */
    fun rateLine(from: String, to: String, rate: Double?, locale: Locale, significant: Int = 4): String =
        "1 $from → ${rate?.let { rateText(it, locale, significant) } ?: "—"} $to"

    /** The amount a user typed: digits and [locale]'s decimal separator (grouping marks and anything else ignored), null when it is no number. */
    fun parseAmount(text: String, locale: Locale): Double? {
        val dec = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val cleaned = text.filter { it.isDigit() || it == dec }
        val i = cleaned.indexOf(dec)
        val canon = if (i < 0) cleaned else cleaned.substring(0, i) + "." + cleaned.substring(i + 1).replace(dec.toString(), "")
        return canon.takeIf { it.isNotEmpty() && it != "." }?.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** [parseAmount] without the double: the exact typed decimal. */
    fun parseDecimal(text: String, locale: Locale): BigDecimal? {
        val dec = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val cleaned = text.filter { it.isDigit() || it == dec }
        val i = cleaned.indexOf(dec)
        val canon = if (i < 0) cleaned else cleaned.substring(0, i) + "." + cleaned.substring(i + 1).replace(dec.toString(), "")
        return canon.takeIf { it.isNotEmpty() && it != "." }?.toBigDecimalOrNull()
    }

    /** A value in [locale] with [decimals] decimals, half-up. */
    fun amountText(x: BigDecimal?, locale: Locale, decimals: Int = 2): String =
        if (x == null) "—" else NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = decimals; maximumFractionDigits = decimals; roundingMode = java.math.RoundingMode.HALF_UP
        }.format(x)

    /** The canonical form of an exact value (25 significant digits, no exponent). */
    fun canonical(x: BigDecimal): String = x.round(MathContext(25)).stripTrailingZeros().toPlainString()

    /** The canonical ("." decimal) form a typed amount is stored in. */
    fun canonical(x: Double): String = BigDecimal.valueOf(x).stripTrailingZeros().toPlainString()

    /** A stored canonical amount as the user types it in [locale]. */
    fun editText(canonical: String, locale: Locale): String =
        canonical.replace('.', DecimalFormatSymbols.getInstance(locale).decimalSeparator)

    /** "26 Nov 2026, 21:08". */
    fun stamp(ms: Long, zone: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale).withZone(zone).format(Instant.ofEpochMilli(ms))

    /** A round flag's emoji: the ISO country of a real ISO 4217 code (its first two letters, EUR = EU), a globe for funds, metals and non-ISO codes. */
    fun flag(code: String): String {
        val c = code.uppercase()
        val globe = "🌐"
        if (c.length != 3 || c[0] == 'X' || runCatching { Currency.getInstance(c) }.isFailure) return globe
        val cc = if (c == "EUR") "EU" else c.substring(0, 2)
        return cc.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    /** Pick-list rows [codes] narrowed by [query] (code or title, any case). */
    fun filter(codes: List<String>, titles: Map<String, String>, query: String): List<String> {
        val q = query.trim()
        return if (q.isEmpty()) codes else codes.filter { it.contains(q, true) || (titles[it] ?: "").contains(q, true) }
    }

    // ── stores ────────────────────────────────────────────────────────────────────────────────

    /** A rate table and when it was last known good. */
    data class Snap(val perBase: Map<String, Double>, val updatedMs: Long)

    /** The rate table kept on the device with its timestamp, for offline use. [prefs] null = nothing is kept (previews). */
    class RateCache(private val prefs: SharedPreferences?) {
        fun load(): Snap? = runCatching {
            val o = JSONObject(prefs?.getString("exchange_rates", null) ?: return null)
            val r = o.getJSONObject("rates")
            Snap(r.keys().asSequence().associateWith { r.getDouble(it) }, o.getLong("t"))
        }.getOrNull()

        fun save(s: Snap) {
            val r = JSONObject(); s.perBase.forEach { (k, v) -> r.put(k, v) }
            prefs?.edit()?.putString("exchange_rates", JSONObject().put("t", s.updatedMs).put("rates", r).toString())?.apply()
        }
    }

    /** The user's picks, kept across launches. */
    class Store(private val prefs: SharedPreferences?) {
        fun load(defaults: State): State {
            val p = prefs ?: return defaults
            val targets = runCatching { JSONArray(p.getString("exchange_targets", null)).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull()
            val t = targets ?: defaults.targets
            val active = p.getInt("exchange_active", 0).takeIf { it in 0..t.size } ?: 0
            return State(p.getString("exchange_source", null) ?: defaults.source, p.getString("exchange_amount", null) ?: defaults.amount, t, active)
        }

        fun save(s: State) {
            prefs?.edit()?.putString("exchange_source", s.source)?.putString("exchange_amount", s.amount)?.putString("exchange_targets", JSONArray(s.targets).toString())?.putInt("exchange_active", s.active)?.apply()
        }
    }

    // ── rates ─────────────────────────────────────────────────────────────────────────────────

    /** The table for the screen: [offline] when the last refresh failed (the numbers are the cached ones, [updatedMs] says from when). */
    data class Table(val perBase: Map<String, Double>, val updatedMs: Long, val offline: Boolean)

    /**
     * Reads the base table through the engine for the codes on screen, merged over the device cache, and keeps the merge with its
     * timestamp. The timestamp is the refresh's own time when one just succeeded, else the newer of the cache's and the engine's
     * rate date: a stale number is never shown as fresh.
     */
    class Rates(
        private val api: CalcApi, private val cache: RateCache, private val base: String, private val options: String,
        private val now: () -> Long = System::currentTimeMillis,
    ) {
        /** [fetched] is a fetchRates answer when a refresh was just attempted, null when none was. */
        fun read(codes: Collection<String>, fetched: String? = null): Table {
            val ok = fetched?.let { runCatching { JSONObject(it).optBoolean("ok") }.getOrDefault(false) }
            val cached = cache.load()
            val fresh = engineTable(codes)
            val merged = cached?.perBase.orEmpty() + fresh
            val engineMs = Fx.time(runCatching { api.ratesInfo() }.getOrDefault("{}")) * 1000
            val updated = if (ok == true && fresh.isNotEmpty()) now() else maxOf(cached?.updatedMs ?: 0L, if (fresh.isNotEmpty()) engineMs else 0L)
            if (merged.isNotEmpty() && (cached == null || merged != cached.perBase || updated != cached.updatedMs)) cache.save(Snap(merged, updated))
            return Table(merged, updated, offline = ok == false || (fresh.isEmpty() && codes.any { it != base }))
        }

        /** Refresh when due (stale, not tried this hour), or at once when [force]; then read. */
        fun refresh(codes: Collection<String>, force: Boolean): Table {
            val t = now()
            val fetched = if (force) Fx.refresh(api, t) else Fx.refreshIfStale(api, t)
            return read(codes, fetched)
        }

        private fun engineTable(codes: Collection<String>): Map<String, Double> = buildMap {
            codes.distinct().forEach { c ->
                if (c == base) put(c, 1.0)
                else runCatching { Logic.result(api.eval(Logic.convert("1", base, c), options)) }.getOrNull()
                    ?.takeIf { it.ok }?.let { r -> Fx.numberIn(r.text)?.takeIf { it > 0 } }?.let { put(c, it) }
            }
        }
    }

    /** The pickable currencies: the engine's Currency units that are 3-letter codes, always with the base; [fallback] when the engine lists none. */
    fun currencies(api: CalcApi, base: String, fallback: List<String>): List<Logic.Item> {
        val rows = runCatching { Logic.items(api.items("unit", "Currency", 1000)) }.getOrDefault(emptyList()).filter { it.name.matches(Regex("[A-Z]{3}")) }
        val all = rows.ifEmpty { fallback.map { Logic.Item(it, "", "unit", "Currency") } }
        return if (all.any { it.name == base }) all else listOf(Logic.Item(base, "Euro", "unit", "Currency")) + all
    }
}
