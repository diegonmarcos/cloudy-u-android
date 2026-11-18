package com.diegonmarcos.capitalfinterm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1786 rule 16 field parity of the CB Homepage after the live sovereign curves, against the frozen baseline
 * src/test/resources/cb-home-parity-baseline-1786.json (the declarations before them): every CB box keeps its id, title, function, page, unit and
 * every column in order; the major-economy order and the top count stay; every ui word stays with its text, except cb_note, which may only grow;
 * every indicator a box reads keeps its id, name, unit, frequency and every source rule in order (rules are only appended); every source stays.
 */
class Reorg1786CbHomeParityTest {
    private val base = JSONObject(DataFiles.resource("cb-home-parity-baseline-1786.json"))
    private val now = JSONObject(java.io.File(listOf("../data/home-boxes.json", "data/home-boxes.json").first { java.io.File(it).isFile }).readText())
    private val ind = JSONObject(DataFiles.cbIndicatorsJson)
    private fun arr(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    @Test fun everyCbBoxKeepsEveryFieldAndColumn() {
        val was = arr(base.getJSONArray("cb_boxes"))
        val boxes = arr(now.getJSONArray("cb_boxes"))
        assertEquals(4, was.size)
        assertEquals("the boxes keep their order", was.map { it.getString("id") }, boxes.map { it.getString("id") }.take(was.size))
        for (b in was) {
            val n = boxes.firstOrNull { it.getString("id") == b.getString("id") }
            assertNotNull("box ${b.getString("id")} is gone", n)
            for (k in b.keys()) if (k != "columns") assertEquals("box ${b.getString("id")} lost or changed $k", b.get(k).toString(), n!!.opt(k)?.toString())
            val cols = b.getJSONArray("columns")
            assertEquals("box ${b.getString("id")} lost a column", cols.toString(), JSONArray(arr(n!!.getJSONArray("columns")).take(cols.length())).toString())
        }
        // and the parsed declaration still reads them all
        val decl = HomeBoxes.parse(now.toString())!!
        assertEquals(was.sumOf { it.getJSONArray("columns").length() }, decl.cbBoxes.take(was.size).sumOf { it.columns.size })
    }

    @Test fun theOrderTopAndEveryWordStay() {
        assertEquals(base.getInt("top"), now.getInt("top"))
        assertEquals(base.getJSONArray("cb_top").toString(), now.getJSONArray("cb_top").toString())
        val wu = base.getJSONObject("ui"); val nu = now.getJSONObject("ui")
        for (k in wu.keys()) {
            assertTrue("ui word $k is gone", nu.has(k))
            if (k == "cb_note") assertTrue("cb_note lost its text", nu.getString(k).startsWith(wu.getString(k)))
            else assertEquals("ui word $k changed", wu.getString(k), nu.getString(k))
        }
    }

    @Test fun everyIndicatorABoxReadsKeepsEveryRuleInOrder() {
        val full = arr(ind.getJSONObject("full").getJSONArray("groups")).flatMap { arr(it.getJSONArray("items")) }.associateBy { it.getString("id") }
        val was = base.getJSONObject("indicators")
        assertTrue(was.length() >= 10)
        for (id in was.keys()) {
            val b = was.getJSONObject(id)
            val n = full[id]
            assertNotNull("indicator $id is gone", n)
            for (k in listOf("name", "unit", "frequency")) assertEquals("indicator $id changed $k", b.optString(k), n!!.optString(k))
            val rules = b.getJSONArray("sources")
            val nr = n!!.getJSONArray("sources")
            assertTrue("indicator $id lost a rule", nr.length() >= rules.length())
            for (i in 0 until rules.length()) assertEquals("indicator $id rule $i changed", rules.getJSONObject(i).toString(), nr.getJSONObject(i).toString())
        }
        // the yield curve columns gained the live GB / JP / CN rule
        for (id in listOf("yield_2y", "yield_5y", "yield_10y", "yield_30y")) assertEquals("finterm_sov", full.getValue(id).getJSONArray("sources").let { it.getJSONObject(it.length() - 1).getString("src") })
    }

    @Test fun everySourceStays() {
        val srcs = ind.getJSONObject("sources")
        val was = base.getJSONArray("sources")
        for (i in 0 until was.length()) assertTrue("source ${was.getString(i)} is gone", srcs.has(was.getString(i)))
        assertTrue(srcs.has("finterm_sov"))
    }
}
