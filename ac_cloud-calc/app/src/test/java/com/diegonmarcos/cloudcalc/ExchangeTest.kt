package com.diegonmarcos.cloudcalc

import android.content.Context
import com.diegonmarcos.cloudcalc.engine.CalcApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.time.ZoneId
import java.util.Locale

/**
 * #1945 Finance moved out of Others to its own row right after Decimals (nothing lost: parity test with a mutation),
 * and Finance > Exchange's maths, formats, rate cache / offline reading and persistence.
 */
@RunWith(RobolectricTestRunner::class)
class ExchangeTest {
    private val ui = JSONObject(File("../build.json").readText()).getJSONObject("ui")
    private val sectionsJson = ui.getJSONArray("sections").toString()
    private val nav = Declarations.parseNav(sectionsJson, "calculator", "calculator")
    private val en = Locale.US

    // ── nav parity and order ──────────────────────────────────────────────────────────────────

    /** The Calc rows before #1945: every page of every row, in order. */
    private val before = listOf(
        "Decimals" to listOf("calc", "latex", "convert", "history"),
        "Others" to listOf("datetime", "sound", "finance", "graph", "ask"),
    )
    private val financeFormsBefore = listOf("loan", "interest", "pv", "npv", "savings", "cagr", "inflation", "discount", "tip")

    /** Problems of [rows] against [before]: a page lost, added or reordered within its row, Finance not right after Decimals, a Finance feature lost. */
    private fun problems(rows: List<Pair<String, List<String>>>, formIds: List<String>): List<String> {
        val out = mutableListOf<String>()
        val now = rows.flatMap { it.second }
        before.flatMap { it.second }.filter { it !in now }.forEach { out += "page $it lost" }
        now.filter { p -> before.none { p in it.second } && p != "exchange" }.forEach { out += "page $it invented" }
        if (now.size != now.distinct().size) out += "a page is listed twice"
        if (rows.map { it.first }.take(2) != listOf("Decimals", "Finance")) out += "Finance is not right after Decimals"
        rows.firstOrNull { it.first == "Others" }?.second?.let { if ("finance" in it) out += "Finance is still in Others" }
        if (rows.firstOrNull { it.first == "Others" }?.second != before[1].second.filter { it != "finance" }) out += "Others lost or reordered an entry"
        financeFormsBefore.filter { it !in formIds }.forEach { out += "finance form $it lost" }
        return out
    }

    private fun declaredRows(n: com.diegonmarcos.superapp.bottomnav.NavDecl = nav) =
        n.section("calculator")!!.rowGroups().map { g -> g.label to g.pages.map { it.id } }
    private val declaredForms get() = Declarations.mode("finance")!!.forms.map { it.id }

    @Test fun `Finance sits right after Decimals, Others keeps its other entries, every page and Finance form is still there`() {
        assertEquals(emptyList<String>(), problems(declaredRows(), declaredForms))
        assertEquals(listOf("Decimals", "Finance", "Others"), declaredRows().map { it.first })
        assertEquals(listOf("calc", "latex", "convert", "history", "finance", "exchange", "datetime", "sound", "graph", "ask"), nav.section("calculator")!!.pages.map { it.id })
        // The page id is unchanged, so a saved or deep-linked "finance" still resolves.
        assertEquals("finance", Declarations.resolve("finance"))
        assertEquals("datetime", Declarations.resolve("tools"))
    }

    @Test fun `mutating the declaration turns the parity check red`() {
        assertTrue(problems(declaredRows(), declaredForms).isEmpty())
        val rows = declaredRows()
        val noFinance = rows.map { (l, p) -> l to p.filter { it != "finance" } }
        assertTrue("page lost", problems(noFinance, declaredForms).any { it.contains("lost") })
        val inOthers = listOf(rows[0], "Others" to listOf("datetime", "sound", "finance", "graph", "ask"))
        assertTrue("Finance left in Others", problems(inOthers, declaredForms).isNotEmpty())
        val last = listOf(rows[0], rows[2], rows[1])
        assertTrue("Finance moved after Others", problems(last, declaredForms).any { it.contains("right after Decimals") })
        assertTrue("a Finance form dropped", problems(rows, declaredForms - "tip").any { it.contains("tip") })
        assertTrue("a page dropped from Others", problems(listOf(rows[0], rows[1], "Others" to listOf("datetime", "sound", "graph")), declaredForms).isNotEmpty())
    }

    @Test fun `Exchange is a page of the Finance row after Finance, and its spec declares the source`() {
        assertEquals(listOf("finance", "exchange"), declaredRows()[1].second)
        assertEquals(listOf("finance"), Declarations.modesOf("finance").map { it.id })
        assertEquals(listOf("exchange"), Declarations.modesOf("exchange").map { it.id })
        val m = Declarations.mode("exchange")!!
        assertEquals("exchange", m.kind)
        val spec = Exchange.spec(m)
        assertEquals("EUR", spec.base)
        assertTrue(spec.sourceUrl.startsWith("https://"))
        assertTrue(spec.sourceName.isNotBlank())
        assertEquals(Exchange.State("INR", "858.02", listOf("USD", "EUR", "GBP", "BRL")), Exchange.defaults(m))
    }

    // ── conversion maths: cross rates via a base ──────────────────────────────────────────────

    private val perEur = mapOf("EUR" to 1.0, "INR" to 100.0, "USD" to 1.08, "GBP" to 0.84)

    @Test fun `cross rates go through the base`() {
        assertEquals(1.08 / 100.0, Exchange.rate(perEur, "INR", "USD")!!, 1e-12)
        assertEquals(100.0 / 1.08, Exchange.rate(perEur, "USD", "INR")!!, 1e-9)
        assertEquals(0.84 / 1.08, Exchange.rate(perEur, "USD", "GBP")!!, 1e-12)
        assertEquals(1.08, Exchange.rate(perEur, "EUR", "USD")!!, 1e-12)
        assertEquals(1.0, Exchange.rate(perEur, "USD", "USD")!!, 0.0)
        assertEquals(858.02 * 1.08 / 100.0, Exchange.convert(858.02, perEur, "INR", "USD")!!, 1e-9)
        // a cross rate is the product of the legs and its inverse is 1/rate
        val ab = Exchange.rate(perEur, "INR", "GBP")!!; val ba = Exchange.rate(perEur, "GBP", "INR")!!
        assertEquals(1.0, ab * ba, 1e-12)
        assertNull("unknown code", Exchange.rate(perEur, "INR", "ZZZ"))
        assertNull("zero source", Exchange.rate(perEur + ("INR" to 0.0), "INR", "USD"))
    }

    @Test fun `formats are 2 decimals for amounts and 4 significant for unit rates, locale aware`() {
        assertEquals("9.27", Exchange.amountText(9.2666, en))
        assertEquals("1,234,567.50", Exchange.amountText(1234567.5, en))
        assertEquals("1.234.567,50", Exchange.amountText(1234567.5, Locale.GERMANY))
        assertEquals("0.0108", Exchange.rateText(0.010802, en))
        assertEquals("0.0099", Exchange.rateText(0.0099, en))
        assertEquals("0.0621", Exchange.rateText(0.062134, en))
        assertEquals("83.1234", Exchange.rateText(83.1234, en))
        assertEquals("1.0000", Exchange.rateText(1.0, en))
        assertEquals("0,0108", Exchange.rateText(0.010802, Locale.GERMANY))
        assertEquals("only a rate that would print 0.0000 falls back to 4 significant digits", "0.00001234", Exchange.rateText(0.00001234, en))
        // mutant: 4 significant digits (the old rule) would print 0.06213, so this pin goes red on it
        assertTrue(Exchange.rateText(0.062134, en) != "0.06213")
        assertEquals("1 INR → 0.0108 USD", Exchange.rateLine("INR", "USD", 0.010802, en))
        assertEquals("1 INR → — USD", Exchange.rateLine("INR", "USD", null, en))
        assertEquals("26 Nov 2026, 21:08", Exchange.stamp(java.time.ZonedDateTime.of(2026, 11, 26, 21, 8, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli(), ZoneId.of("UTC"), en))
    }

    @Test fun `typed amounts parse in the locale and round-trip through the canonical form`() {
        assertEquals(858.02, Exchange.parseAmount("858.02", en)!!, 0.0)
        assertEquals(1234.5, Exchange.parseAmount("1,234.5", en)!!, 0.0)
        assertEquals(858.02, Exchange.parseAmount("858,02", Locale.GERMANY)!!, 0.0)
        assertNull(Exchange.parseAmount("", en)); assertNull(Exchange.parseAmount(".", en)); assertNull(Exchange.parseAmount("abc", en))
        assertEquals("858.02", Exchange.canonical(858.02))
        assertEquals("858,02", Exchange.editText("858.02", Locale.GERMANY))
    }

    @Test fun `flags are emoji, a globe for non-ISO codes`() {
        assertEquals("🇮🇳", Exchange.flag("INR"))
        assertEquals("🇪🇺", Exchange.flag("EUR"))
        assertEquals("🇬🇧", Exchange.flag("GBP"))
        assertEquals("🌐", Exchange.flag("BTC"))
        assertEquals("🌐", Exchange.flag("XAU"))
    }

    @Test fun `selection edits never duplicate a currency`() {
        val s = Exchange.State("INR", "1", listOf("USD", "EUR"))
        assertEquals(Exchange.State("INR", "1", listOf("USD", "EUR", "GBP")), Exchange.addTarget(s, "GBP", 12))
        assertEquals(s, Exchange.addTarget(s, "USD", 12)); assertEquals(s, Exchange.addTarget(s, "INR", 12)); assertEquals(s, Exchange.addTarget(s, "GBP", 2))
        assertEquals(listOf("EUR"), Exchange.removeTarget(s, "USD").targets)
        assertEquals(Exchange.State("USD", "1", listOf("INR", "EUR")), Exchange.setSource(s, "USD"))
        assertEquals(Exchange.State("JPY", "1", listOf("USD", "EUR")), Exchange.setSource(s, "JPY"))
        assertEquals(listOf("GBP", "EUR"), Exchange.setTarget(s, 0, "GBP").targets)
        assertEquals(listOf("EUR", "USD"), Exchange.setTarget(s, 0, "EUR").targets)
        assertEquals(Exchange.State("USD", "1", listOf("INR", "EUR")), Exchange.setTarget(s, 0, "INR"))
    }

    // ── rate cache and offline ────────────────────────────────────────────────────────────────

    private class FakeEngine : CalcApi {
        @Volatile var perEur = mapOf("USD" to "1.2 USD", "INR" to "90 INR", "GBP" to "0.85 GBP")
        @Volatile var engineTime = 1783296000L
        @Volatile var fetchOk = true
        var fetches = 0
        override fun info() = "{}"
        override fun eval(expr: String, options: String): String {
            val code = expr.substringAfterLast(" ")
            val r = perEur[code]
            return if (r == null) """{"ok":false,"result":"","messages":[]}""" else JSONObject().put("ok", true).put("result", r).put("messages", org.json.JSONArray()).toString()
        }
        override fun plot(expr: String, xmin: Double, xmax: Double, steps: Int) = "{}"
        override fun complete(prefix: String, max: Int) = "[]"
        override fun items(kind: String, category: String, max: Int) = """[{"name":"USD","title":"US Dollar","kind":"unit","category":"Currency"},{"name":"INR","title":"Indian Rupee","kind":"unit","category":"Currency"},{"name":"btc","title":"x","kind":"unit","category":"Currency"}]"""
        override fun ratesInfo() = """{"time":$engineTime}"""
        override fun fetchRates(): String { fetches++; return if (fetchOk) """{"ok":true,"time":$engineTime}""" else """{"ok":false,"error":"offline"}""" }
    }

    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("exchange_test", Context.MODE_PRIVATE)

    @Test fun `the table is read through the engine from the EUR base and cached with its time`() {
        prefs.edit().clear().commit()
        val api = FakeEngine(); var clock = 5_000L
        val rates = Exchange.Rates(api, Exchange.RateCache(prefs), "EUR", "{}") { clock }
        val t = rates.read(listOf("INR", "USD", "EUR"))
        assertEquals(mapOf("INR" to 90.0, "USD" to 1.2, "EUR" to 1.0), t.perBase)
        assertFalse(t.offline)
        assertEquals(1783296000L * 1000, t.updatedMs)
        assertEquals(1.2 / 90.0, Exchange.rate(t.perBase, "INR", "USD")!!, 1e-12)
        val kept = Exchange.RateCache(prefs).load()!!
        assertEquals(t.perBase, kept.perBase); assertEquals(t.updatedMs, kept.updatedMs)
        clock = 9_000L
        val fresh = rates.refresh(listOf("INR", "USD"), force = true)
        assertEquals(9_000L, fresh.updatedMs)
        assertEquals(1, api.fetches)
    }

    @Test fun `offline the cached table and its time stay, flagged offline`() {
        prefs.edit().clear().commit()
        val api = FakeEngine(); var clock = 7_000L
        val rates = Exchange.Rates(api, Exchange.RateCache(prefs), "EUR", "{}") { clock }
        val good = rates.refresh(listOf("INR", "USD"), force = true)
        assertEquals(7_000L, good.updatedMs)
        // The engine now answers nothing and the fetch fails: a new process reads the cache.
        api.perEur = emptyMap(); api.fetchOk = false; clock = 99_000L
        val off = Exchange.Rates(api, Exchange.RateCache(prefs), "EUR", "{}") { clock }.refresh(listOf("INR", "USD"), force = true)
        assertTrue(off.offline)
        assertEquals(good.perBase, off.perBase)
        assertEquals("the cached time, not now", 7_000L, off.updatedMs)
        // No cache and no engine: empty, offline, time 0 (the screen says "never").
        prefs.edit().clear().commit()
        val none = Exchange.Rates(api, Exchange.RateCache(prefs), "EUR", "{}") { clock }.read(listOf("INR", "USD"))
        assertTrue(none.perBase.isEmpty() && none.offline && none.updatedMs == 0L)
        assertNull(Exchange.RateCache(prefs).load())
    }

    @Test fun `the pick list is the engine's three-letter currencies with the base`() {
        val rows = Exchange.currencies(FakeEngine(), "EUR", emptyList()).map { it.name }
        assertEquals(listOf("EUR", "USD", "INR"), rows)
        assertEquals(listOf("USD"), Exchange.filter(listOf("USD", "INR"), mapOf("USD" to "US Dollar", "INR" to "Indian Rupee"), "dollar"))
        assertEquals(listOf("INR"), Exchange.filter(listOf("USD", "INR"), mapOf("USD" to "US Dollar", "INR" to "Indian Rupee"), "rupee"))
    }

    // ── persistence ───────────────────────────────────────────────────────────────────────────

    @Test fun `the picks and the amount persist`() {
        prefs.edit().clear().commit()
        val d = Exchange.State("INR", "858.02", listOf("USD", "EUR", "GBP", "BRL"))
        assertEquals("fresh install = the declared defaults", d, Exchange.Store(prefs).load(d))
        val changed = Exchange.State("USD", "12.5", listOf("JPY", "CHF"))
        Exchange.Store(prefs).save(changed)
        assertEquals(changed, Exchange.Store(prefs).load(d))
        assertNotNull(prefs.getString("exchange_targets", null))
        assertEquals("a store with no prefs is the defaults", d, Exchange.Store(null).load(d))
    }
}
