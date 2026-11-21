package com.diegonmarcos.capitalfinterm

import java.util.Locale

/**
 * #1832 the text of the volatility sub-page's tables, built here (not in the composable) so a test can freeze what the screen shows: the existing
 * four-estimator table (never changes) and the per-asset GARCH fit table added next to it.
 */
object MarkzScreen {
    fun pct(v: Double?, n: Int = 2): String = if (v == null || !v.isFinite()) "n/a" else Markz.fixed(v, n)

    /** The header of the volatility table, as it was before the fits (Rule 16: nothing leaves it). */
    fun volColumns(d: MarkzDecl): List<String> = listOf(d.word("st_asset"), d.word("st_hist"), d.word("st_ewma"), d.word("st_garch"), d.word("st_implied"))

    fun volRow(name: String, v: MarkzVols): List<String> = listOf(name, pct(v.hist), pct(v.ewma), pct(v.garch), pct(v.implied))

    /** The GARCH fit table: one column per asset, one row per figure. */
    fun garchColumns(d: MarkzDecl, names: List<String>): List<String> = listOf(d.word("st_g_param")) + names

    private fun sci(v: Double): String = if (v.isFinite()) String.format(Locale.US, "%.3e", v) else "n/a"
    private fun f(v: Double, n: Int): String = if (v.isFinite()) Markz.fixed(v, n) else "n/a"

    fun garchRows(d: MarkzDecl, vols: List<MarkzVols>, annual: Int): List<List<String>> {
        val fits = vols.map { it.fit }
        fun row(key: String, cell: (GarchFit) -> String) = listOf(d.word(key)) + fits.map { if (it == null) "n/a" else cell(it) }
        return listOf(
            row("st_g_omega") { sci(it.omega) },
            row("st_g_alpha") { f(it.alpha, 4) },
            row("st_g_beta") { f(it.beta, 4) },
            row("st_g_persist") { f(it.persistence, 4) },
            row("st_g_half") { if (it.persistence <= 0.0) "n/a" else f(it.halfLife, 1) },
            row("st_g_lrvol") { f(it.longRunVol(annual), 2) },
            row("st_g_ll") { f(it.logLik, 1) },
            row("st_g_n") { it.n.toString() },
            row("st_g_range") { (it.from?.toString() ?: "?") + " .. " + (it.to?.toString() ?: "?") },
            row("st_g_source") { d.word(when (it.basis) { "capped" -> "st_g_capped"; "targeted" -> "st_g_targeted"; "defaults" -> "st_g_defaults"; else -> "st_g_fitted" }) },
            row("st_g_realised") { f(it.realisedVol, 2) },
            row("st_g_dropped") { it.dropped.toString() },
        )
    }
}
