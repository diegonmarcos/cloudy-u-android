package com.diegonmarcos.capitalfinterm

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1663 rule 16 field parity: every block (sub-page) and field (panel, its source, view, columns and items) the EQ page had before the #1612 regroup
 * is still declared (moved or renamed, never dropped), against the frozen baseline src/test/resources/eq-parity-baseline.json.
 */
class EqParityTest {
    private val baseline = JSONObject(listOf("src/test/resources/eq-parity-baseline.json", "app/src/test/resources/eq-parity-baseline.json").map { File(it) }.first { it.isFile }.readText())
    private val ui = JSONObject(DataFiles.buildJson).getJSONObject("ui")
    private val dashboards = JSONObject(DataFiles.dashboardsJson).getJSONArray("dashboards").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun arr(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    @Test fun everyEqBlockIsStillDeclaredOrRedirected() {
        val eq = arr(ui.getJSONArray("sections")).first { it.getString("id") == "eq" }
        val pages = arr(eq.getJSONArray("pages")).map { it.getString("id") }.toSet()
        val redirected = arr(ui.optJSONArray("redirects")).filter { it.optString("tab") == "eq" }.map { it.getString("from") }.toSet()
        for (p in arr(baseline.getJSONArray("pages"))) {
            val id = p.getString("page")
            assertTrue("EQ block $id dropped", id in pages || id.replace('-', '_') in redirected)
        }
    }

    @Test fun everyEqFieldIsStillDeclared() {
        val panels = arr(baseline.getJSONArray("panels"))
        assertEquals(26, panels.size)
        for (b in panels) {
            val (dash, id) = b.getString("now").split("/")
            val d = dashboards.firstOrNull { it.getString("id") == dash }
            assertNotNull("dashboard $dash missing", d)
            val p = arr(d!!.optJSONArray("panels")).firstOrNull { it.optString("id") == id }
            assertNotNull("panel ${b.getString("now")} (was ${b.getString("was")}) missing", p)
            assertEquals(b.getString("now"), b.getString("source"), p!!.optString("source"))
            assertEquals(b.getString("now"), b.getString("view"), p.optString("view"))
            val body = p.toString()
            val cols = b.getJSONArray("columns")
            for (i in 0 until cols.length()) assertTrue("${b.getString("now")} lost column ${cols.getString(i)}", body.contains("\"" + cols.getString(i) + "\""))
            val items = b.getJSONArray("items")
            for (i in 0 until items.length()) assertTrue("${b.getString("now")} lost item ${items.getString(i)}", body.contains(items.getString(i)))
        }
    }
}
