package com.diegonmarcos.capitalfinterm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1832 rule 16 for the GARCH fit: the volatility screen keeps every field it had (frozen in src/test/resources/markz-stats-screen-1832-baseline.json)
 * and adds the fit table. The checker is proven red by mutation: it must flag a baseline field removed or changed in a copy of the screen.
 */
class MarkzGarchParityTest {
    private val base = JSONObject(DataFiles.resource("markz-stats-screen-1832-baseline.json"))
    private val d = Markz.parse(DataFiles.markzJson)!!

    private fun pairs(k: String) = base.getJSONArray(k).let { a -> (0 until a.length()).map { a.getJSONArray(it).getString(0) to a.getJSONArray(it).getString(1) } }

    /** What differs from the baseline: a word gone or changed, a column gone or reordered, a row cell that changed. Empty = parity. */
    private fun diff(words: Map<String, String>, columns: List<String>, rowOf: (String, Double, Double, Double, Double?) -> List<String>): List<String> {
        val out = ArrayList<String>()
        val w = base.getJSONObject("words")
        w.keys().forEach { k -> if (words[k] != w.getString(k)) out += "word $k" }
        if (columns != pairs("vol_columns").map { it.second }) out += "columns $columns"
        base.getJSONObject("vol_row").let { r ->
            val cells = r.getJSONArray("cells").let { a -> (0 until a.length()).map { a.getString(it) } }
            if (rowOf(r.getString("name"), r.getDouble("hist"), r.getDouble("ewma"), r.getDouble("garch"), r.getDouble("implied")) != cells) out += "row"
        }
        base.getJSONObject("vol_row_no_implied").let { r ->
            val cells = r.getJSONArray("cells").let { a -> (0 until a.length()).map { a.getString(it) } }
            if (rowOf(r.getString("name"), r.getDouble("hist"), r.getDouble("ewma"), r.getDouble("garch"), null) != cells) out += "row without implied"
        }
        return out
    }

    private fun live(name: String, h: Double, e: Double, g: Double, i: Double?) = MarkzScreen.volRow(name, MarkzVols(h, e, g, i))

    @Test fun theVolatilityScreenKeepsEveryFieldItHad() {
        assertEquals(emptyList<String>(), diff(d.words, MarkzScreen.volColumns(d), this::live))
        assertEquals(base.getJSONArray("vol_models").let { a -> (0 until a.length()).map { a.getString(it) } }, d.stats.volModels.map { it.first })
        assertEquals(pairs("vol_columns").map { it.first }, listOf("st_asset", "st_hist", "st_ewma", "st_garch", "st_implied"))
    }

    @Test fun theCheckerGoesRedWhenAFieldIsRemovedOrChanged() {
        assertTrue(diff(d.words - "st_garch", MarkzScreen.volColumns(d), this::live).isNotEmpty())                   // a word removed
        assertTrue(diff(d.words + ("st_ewma" to "x"), MarkzScreen.volColumns(d), this::live).isNotEmpty())           // a word changed
        assertTrue(diff(d.words, MarkzScreen.volColumns(d).filterIndexed { i, _ -> i != 3 }, this::live).isNotEmpty()) // a column dropped
        assertTrue(diff(d.words, MarkzScreen.volColumns(d)) { n, h, e, g, _ -> live(n, h, e, g, null) }.isNotEmpty()) // the implied cell dropped
    }

    @Test fun theFitTableShowsEveryRequestedFigure() {
        val f = GarchFitter.fit((0..700).map { java.time.LocalDate.of(2020, 1, 1).plusDays(it.toLong()) to 100.0 * Math.exp(0.01 * Math.sin(it * 1.7) * (1 + it % 7)) }, 0.05, 0.90)
        val rows = MarkzScreen.garchRows(d, listOf(MarkzVols(1.0, 1.0, 1.0, null, f), MarkzVols(1.0, 1.0, 1.0, null, null)), 252)
        val labels = listOf("st_g_omega", "st_g_alpha", "st_g_beta", "st_g_persist", "st_g_half", "st_g_lrvol", "st_g_ll", "st_g_n", "st_g_range", "st_g_source", "st_g_realised", "st_g_dropped").map { d.word(it) }
        assertEquals(labels, rows.map { it[0] })
        assertTrue(labels.all { it.isNotEmpty() })
        assertEquals("n/a", rows[0][2])                       // an asset without a fit shows n/a, not a made-up figure
        assertEquals(f.n.toString(), rows.single { it[0] == d.word("st_g_n") }[1])
        assertTrue(rows.single { it[0] == d.word("st_g_range") }[1].contains("2020-01-01"))
    }
}
