package com.diegonmarcos.capitalfinterm

import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * FinTerm number formatting and refresh policy (FinTerm UI spec 1.2, 2.6, 5.1). Pure; the same rules as the web mirror's `fin.ts`.
 * A change is always signed (+0.12 / -0.12), zero is unsigned (0.00), a missing value is the em dash, never a bare "-".
 */
object FinFormat {
    const val DASH = "—"

    enum class Dir { UP, DOWN, FLAT }

    fun parseNum(s: String?): Double? {
        if (s == null) return null
        val m = Regex("""[-+]?\d[\d,]*(?:\.\d+)?|[-+]?\.\d+""").find(s.replace('−', '-')) ?: return null
        return m.value.replace(",", "").toDoubleOrNull()
    }

    fun dirOf(s: String?): Dir {
        val n = parseNum(s) ?: return Dir.FLAT
        return if (n > 0) Dir.UP else if (n < 0) Dir.DOWN else Dir.FLAT
    }

    fun signed(v: Double?, decimals: Int = 2, suffix: String = ""): String {
        if (v == null || !v.isFinite()) return DASH
        val t = String.format(Locale.US, "%.${decimals}f", abs(v))
        if (t.toDouble() == 0.0) return t + suffix
        return (if (v > 0) "+" else "-") + t + suffix
    }

    enum class Kind { FX, RATE, CRYPTO, NUM }

    fun price(v: Double?, kind: Kind = Kind.NUM): String {
        if (v == null || !v.isFinite()) return DASH
        val d = when {
            kind == Kind.FX && abs(v) < 10 -> 4
            kind == Kind.CRYPTO && abs(v) >= 1000 -> 0
            else -> 2
        }
        return String.format(Locale.US, "%,.${d}f", v)
    }

    /** Lower is better for unemployment, inflation and jobless claims; higher otherwise. */
    fun betterHigher(title: String): Boolean =
        !Regex("""unemploy|jobless|claims|\bcpi\b|inflation|hicp|\bppi\b|pce|price index""", RegexOption.IGNORE_CASE).containsMatchIn(title)

    /** Beat (UP) / miss (DOWN) / in line (FLAT) of an actual against its forecast; null when either is missing. */
    fun beatMiss(actual: String?, forecast: String?, higherIsBetter: Boolean = true): Dir? {
        val a = parseNum(actual) ?: return null
        val f = parseNum(forecast) ?: return null
        if (a == f) return Dir.FLAT
        return if ((a > f) == higherIsBetter) Dir.UP else Dir.DOWN
    }

    data class Policy(val everyMs: Long, val staleMs: Long)

    private const val MIN = 60_000L
    private const val HOUR = 3_600_000L
    private val POLICIES: List<Pair<Regex, Policy>> = listOf(
        Regex("^(coinbase|kraken)$") to Policy(15_000, MIN),
        Regex("^(cboe|stooq|finnhub)$") to Policy(MIN, 5 * MIN),
        Regex("^(frankfurter|nyfed|treasury_xml|ecb_data)$") to Policy(5 * MIN, 30 * MIN),
        Regex("^(gdelt|polymarket)$") to Policy(5 * MIN, 30 * MIN),
        Regex("^(forexfactory|central_banks|nasdaq_calendar)$") to Policy(15 * MIN, 2 * HOUR),
        Regex("^(fred|dbnomics|eia)$") to Policy(30 * MIN, 6 * HOUR),
        Regex("^(bls|eurostat|oecd|imf|worldbank|sec_edgar|sec_xbrl)$") to Policy(6 * HOUR, 48 * HOUR),
    )

    fun policy(sourceId: String): Policy = POLICIES.firstOrNull { it.first.matches(sourceId) }?.second ?: Policy(30 * MIN, 6 * HOUR)

    /** Next refresh delay: +-10 % jitter ([rnd] in 0..1), doubled per consecutive failure, at most 10 minutes. */
    fun nextDelay(everyMs: Long, failures: Int, rnd: Double): Long {
        val base = if (failures > 0) min(everyMs * 2.0.pow(failures).toLong(), 10 * MIN) else everyMs
        return (base * (0.9 + 0.2 * rnd)).roundToLong()
    }

    fun isStale(fetchedAt: Long, now: Long, sourceId: String): Boolean = now - fetchedAt > policy(sourceId).staleMs

    const val MESH_OFF = "mesh off"
    /** Why a load failed, for the error body: a timeout, the mesh being off (a container-only panel), else no answer. */
    fun failureReason(e: Throwable): String = when {
        e is java.net.SocketTimeoutException -> "timeout"
        e.message.orEmpty().startsWith(MESH_OFF) -> MESH_OFF
        else -> "no answer"
    }
    /** The error body: a container-only panel off the mesh names the mesh, never its declared public source. */
    fun failureText(source: String, reason: String): String =
        if (reason == MESH_OFF) "Mesh off: served by the FinTerm container on the private mesh only" else "$source did not answer ($reason)"
}
