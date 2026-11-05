package com.diegonmarcos.superapp.launcher

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The home status bar renders from build.json::home_status_bar.
 *
 *   D1 the baked declaration parses and its clusters come out in the declared order
 *   D2 swapping two declared orders changes the render order (the bar follows the declaration, not a list in the drawing)
 *   D3 a hidden element leaves the render; a declared-invisible one can be switched on
 *   D4 the owner's order override leads its cluster; a move stops at the ends
 *   D5 a position outside left|centre|right is refused
 *   D7 (#1522) the ADB icon opens the ADB Shell page, not the network dropdown, and is declared once
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StatusBarDeclTest {

    private fun json(vararg els: String) =
        """{"row_height_dp":26,"elements":[${els.joinToString(",")}]}"""

    private fun el(id: String, pos: String, order: Int, visible: Boolean = true, kind: String = "network") =
        """{"id":"$id","label":"$id","kind":"$kind","position":"$pos","order":$order,"visible":$visible,"pet":null,"opens":"network_popup"}"""

    private fun ids(m: Map<String, List<StatusElement>>, pos: String) = m.getValue(pos).map { it.id }

    @Test
    fun `D1 the baked declaration renders its clusters in declared order`() {
        val d = StatusBarDecl.declared
        val m = StatusBarDecl.layout(d)
        for (pos in StatusBarDecl.POSITIONS) {
            val declared = d.elements.filter { it.position == pos && it.visible }.sortedBy { it.order }.map { it.id }
            assertEquals(declared, ids(m, pos))
        }
        assertEquals(listOf("clock"), ids(m, StatusBarDecl.CENTRE))
        assertEquals(26, d.rowHeightDp)
    }

    @Test
    fun `D2 swapping two declared orders changes the render order`() {
        val a = StatusBarDecl.parse(json(el("wifi", "left", 1), el("bluetooth", "left", 2), el("clock", "centre", 1, kind = "clock")))
        val b = StatusBarDecl.parse(json(el("wifi", "left", 2), el("bluetooth", "left", 1), el("clock", "centre", 1, kind = "clock")))
        assertEquals(listOf("wifi", "bluetooth"), ids(StatusBarDecl.layout(a), "left"))
        assertEquals(listOf("bluetooth", "wifi"), ids(StatusBarDecl.layout(b), "left"))
        // moving an element to another cluster moves it on the bar
        val c = StatusBarDecl.parse(json(el("wifi", "right", 1), el("bluetooth", "left", 2)))
        assertEquals(listOf("wifi"), ids(StatusBarDecl.layout(c), "right"))
        assertEquals(listOf("bluetooth"), ids(StatusBarDecl.layout(c), "left"))
    }

    @Test
    fun `D3 hidden leaves the render and declared-invisible can be switched on`() {
        val d = StatusBarDecl.parse(json(el("wifi", "left", 1), el("kde", "left", 2, visible = false), el("gps", "left", 3)))
        assertEquals(listOf("wifi", "gps"), ids(StatusBarDecl.layout(d), "left"))
        assertEquals(listOf("gps"), ids(StatusBarDecl.layout(d, hidden = mapOf("wifi" to true)), "left"))
        assertEquals(listOf("wifi", "kde", "gps"), ids(StatusBarDecl.layout(d, hidden = mapOf("kde" to false)), "left"))
    }

    @Test
    fun `D4 an owner order leads its cluster and a move stops at the ends`() {
        val d = StatusBarDecl.parse(json(el("wifi", "left", 1), el("bluetooth", "left", 2), el("gps", "left", 3)))
        assertEquals(listOf("gps", "wifi", "bluetooth"), ids(StatusBarDecl.layout(d, orders = mapOf("left" to listOf("gps"))), "left"))
        assertEquals(listOf("b", "a", "c"), StatusBarDecl.move(listOf("a", "b", "c"), 1, -1))
        assertEquals(listOf("a", "b", "c"), StatusBarDecl.move(listOf("a", "b", "c"), 0, -1))
        assertEquals(listOf("a", "b", "c"), StatusBarDecl.move(listOf("a", "b", "c"), 2, 1))
        assertFalse(StatusBarDecl.ordered(d, "left").isEmpty())
    }

    @Test
    fun `D5 an unknown position is refused`() {
        val refused = runCatching { StatusBarDecl.parse(json(el("wifi", "middle", 1))) }.isFailure
        assertTrue(refused)
    }

    @Test
    fun `D6 the speed meter is declared for the network dropdown, not drawn on the bar`() {
        val ns = StatusBarDecl.declared.networkSpeed!!
        assertEquals(1000L, ns.sampleMs)
        assertEquals(300_000L, ns.publicIpTtlMs)
        assertTrue(ns.test.downloadUrl.contains("{bytes}"))
        assertTrue(ns.test.meteredDivisor >= 1)
        val j = """{"row_height_dp":26,"elements":[],"network_speed":{"sample_ms":500,"public_ip":{"url":"https://x/t","ttl_ms":9},
            "speed_test":{"download_url":"https://x/d?b={bytes}","upload_url":"https://x/u","ping_url":"https://x/p","download_bytes":2,"upload_bytes":3,"max_seconds":4,"ping_count":5,"metered_divisor":6}}}"""
        val p = StatusBarDecl.parse(j)
        assertEquals(NetworkSpeedDecl(500L, "https://x/t", 9L, SpeedTestDecl("https://x/d?b={bytes}", "https://x/u", "https://x/p", 2L, 3L, 4, 5, 6)), p.networkSpeed)
        assertEquals(null, StatusBarDecl.parse(json(el("wifi", "left", 1))).networkSpeed)
        assertEquals(null, StatusBarDecl.parse(json(el("wifi", "left", 1))).networkTable)
    }

    @Test
    fun `D7 the network table is declared in groups and a duplicate row id is refused`() {
        val t = StatusBarDecl.declared.networkTable!!
        assertEquals(
            listOf("connection", "mobile", "ip", "vpn", "metered", "usage", "speed", "peak", "test", "test_hist"), t.groups.map { it.id })
        val ids = t.groups.flatMap { g -> g.rows.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
        val dup = """{"row_height_dp":26,"elements":[],"network_table":{"groups":[
            {"id":"a","label":"A","rows":[{"id":"x","label":"X"}]},{"id":"b","label":"B","rows":[{"id":"x","label":"X"}]}]}}"""
        assertTrue(runCatching { StatusBarDecl.parse(dup) }.isFailure)
    }

    @Test
    fun `D7 the ADB icon opens the ADB Shell page and is declared once`() {
        val adb = StatusBarDecl.declared.elements.filter { it.id == "adb" }
        assertEquals(1, adb.size)
        assertEquals("adb_shell_page", adb.single().opens)
        assertEquals(1, StatusBarDecl.declared.elements.count { it.label.contains("ADB") })
        assertTrue(StatusBarDecl.declared.elements.filter { it.kind == "network" && it.id != "adb" }.none { it.opens == "adb_shell_page" })
    }
}
