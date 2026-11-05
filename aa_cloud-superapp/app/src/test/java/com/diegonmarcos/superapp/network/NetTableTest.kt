package com.diegonmarcos.superapp.network

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.launcher.StatusBarDecl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * #1523 The network dropdown's table.
 *
 *   U1 units: decimal SI, 1 MB/s = 8 Mbps, both columns from one rate; bytes read in kB / MB / GB
 *   U2 the 24 h aggregation: pairwise counter deltas, a reboot, partial coverage, the 25 h prune
 *   U3 n/a reasons: every unreadable value says why; a grantable permission says "Fix in Perms"
 *   U4 row presence: every declared row is built, in order, whatever the readers gave
 *   U5 the speed-test history: best / worst / average over 24 h
 *   U6 the public-IP lookup: the trace parse and the 5 min cache
 *   U7 no sampling while the dropdown is closed
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NetTableTest {
    private val h = 3_600_000L
    private fun snap(at: Long, tr: Long, tt: Long, mr: Long = 0, mt: Long = 0) = NetSnap(at, tr, tt, mr, mt)
    private fun texts(vs: List<NetValue>) = vs.map { it.text() }

    // ── U1
    @Test fun `U1 one rate gives both units, 1 MB per s is 8 Mbps`() {
        assertEquals(listOf("1.0", "8.0"), texts(NetTable.rate(8.0, "x")))
        assertEquals(listOf("12.5", "100"), texts(NetTable.rate(100.0, "x")))
        assertEquals(listOf("n/a (sampling)", "n/a (sampling)"), texts(NetTable.rate(null, "sampling")))
        assertEquals("1.0 MB/s  8.0 Mbps", NetTable.rateLine(8.0))
        assertEquals(8.0, SpeedMath.mbps(1_000_000, 1000), 1e-9)
        assertEquals("512 kB", NetTable.bytes(512_000))
        assertEquals("1.5 MB", NetTable.bytes(1_500_000))
        assertEquals("2.34 GB", NetTable.bytes(2_340_000_000L))
        assertEquals(8.0, NetTable.avgMbps(86_400_000_000L, 86_400_000L)!!, 1e-9)   // 86.4 GB in 24 h = 8 Mbps
        assertNull(NetTable.avgMbps(5, 0)); assertNull(NetTable.avgMbps(null, 1000))
    }

    // ── U2
    @Test fun `U2 24 h usage sums the counter deltas, per transport`() {
        val now = 100 * h
        val s = listOf(snap(now - 20 * h, 1000, 100, 200, 10), snap(now - 10 * h, 5000, 300, 1200, 50), snap(now, 9000, 700, 1200, 50))
        val u = NetUsage.span(s, now - 24 * h, now)!!
        assertEquals(8000L - 1000L, u.mobRx + u.wifiRx)            // total down = 9000 - 1000
        assertEquals(1000L, u.mobRx); assertEquals(7000L, u.wifiRx)
        assertEquals(40L, u.mobTx); assertEquals(560L, u.wifiTx)
        assertEquals(20 * h, u.coverageMs)                           // history starts 20 h ago, not 24
        // today's cut at -12 h falls between the -20 h and -10 h snapshots: the counters are interpolated there
        val today = NetUsage.span(s, now - 12 * h, now)!!
        assertEquals(12 * h, today.coverageMs)
        assertEquals(4800L, today.totRx); assertEquals(200L, today.mobRx)
    }

    @Test fun `U2 a rebooted counter counts the new value, fewer than two snapshots is no figure`() {
        val now = 50 * h
        val s = listOf(snap(now - 5 * h, 9000, 900), snap(now - h, 300, 30), snap(now, 400, 40))
        val u = NetUsage.span(s, now - 24 * h, now)!!
        assertEquals(300L + 100L, u.totRx); assertEquals(30L + 10L, u.totTx)
        assertNull(NetUsage.span(listOf(snap(now, 1, 1)), now - 24 * h, now))
        assertNull(NetUsage.span(emptyList(), 0, now))
    }

    @Test fun `U2 recording prunes past 25 h, merges an open within 30 s and round-trips`() {
        val now = 100 * h
        val a = NetUsage.record(emptyList(), snap(now - 26 * h, 1, 1))
        val b = NetUsage.record(a, snap(now, 5, 5))
        assertEquals(listOf(now), b.map { it.atMs })                                 // the 26 h one left
        val c = NetUsage.record(b, snap(now + 10_000, 9, 9))
        assertEquals(1, c.size); assertEquals(9L, c[0].totRx)                        // merged
        val d = NetUsage.record(c, snap(now + 60_000, 12, 12))
        assertEquals(2, d.size)
        assertEquals(d, NetUsage.decode(NetUsage.encode(d)))
        assertEquals(emptyList<NetSnap>(), NetUsage.decode("junk\n1,2"))
    }

    @Test fun `U2 the bandwidth rows read the 24 h average and total from the aggregation`() {
        val u = NetUsageSpan(wifiRx = 9_000_000_000L, wifiTx = 0, mobRx = 0, mobTx = 450_000_000L, coverageMs = 3_600_000L)
        val m = NetTable.speedRows(8.0, null, u, 100.0, null)
        assertEquals(listOf("1.0", "8.0", "2.5", "20.0"), texts(m.getValue("down")))   // 9 GB in 1 h = 20 Mbps = 2.5 MB/s
        assertEquals(listOf("12.5", "100"), texts(m.getValue("peak_down")).take(2))
        assertEquals("9.00 GB", m.getValue("peak_down")[2].text())
        assertEquals("450.0 MB", m.getValue("peak_up")[2].text())
        assertEquals("n/a (sampling)", m.getValue("up")[0].text())
    }

    // ── U3
    @Test fun `U3 every n-a says why and a grantable one points at Perms`() {
        assertEquals("n/a (not on Wi-Fi)", NetValue.NA("not on Wi-Fi").text())
        assertEquals("needs location — Fix in Perms ›", NetValue.NA("needs location", fix = true).text())
        assertEquals("n/a", NetValue.NA("anything").cell())
        assertEquals("1.0", NetValue.V("1.0").cell())
        val none = NetTable.speedRows(null, null, null, null, null)
        for ((_, cells) in none) for (c in cells) assertTrue(c is NetValue.NA && c.reason.isNotBlank())
        val noUse = NetTable.usageRows(null, null, "reading")
        assertEquals(listOf("use_wifi", "use_mobile", "use_total"), noUse.keys.toList())
        assertTrue(noUse.values.all { r -> r.size == 4 && r.all { it == NetValue.NA("reading") } })
        // the speed test says what is going on
        assertEquals("n/a (tap Speed test)", NetTable.testRows(emptyList(), 0, false, "", null).getValue("st_down")[0].text())
        assertEquals("n/a (testing: upload)", NetTable.testRows(emptyList(), 0, true, "upload", null).getValue("st_down")[0].text())
        assertEquals("n/a (test failed: offline)", NetTable.testRows(emptyList(), 0, false, "", "offline").getValue("st_up")[0].text())
        assertEquals("n/a (no test in 24 h)", NetTable.testRows(emptyList(), 0, false, "", null).getValue("hd_mbps")[0].text())
        val r = SpeedRun(1000, 80.0, null, 20.0, 2.0, "wifi")
        val m = NetTable.testRows(listOf(r), 1000 + 5 * 60_000, false, "", null)
        assertEquals("10.0 MB/s  80.0 Mbps", m.getValue("st_down")[0].text())
        assertEquals("n/a (upload not measured)", m.getValue("st_up")[0].text())
        assertEquals("20.0 ms", m.getValue("st_latency")[0].text())
        assertEquals("5m ago · wifi · 1 in 24 h", m.getValue("st_last")[0].text())
        assertEquals(listOf("80.0", "80.0", "80.0"), texts(m.getValue("hd_mbps")))
        assertEquals(listOf("10.0", "10.0", "10.0"), texts(m.getValue("hd_mbs")))
        // a grid lists each distinct reason once under it
        val g = NetTable.build(StatusBarDecl.declared.networkTable!!, none).first { it.id == "speed" }
        assertEquals(listOf("n/a (sampling)", "n/a (needs two opens in 24 h)"), g.notes())
    }

    @Test fun `U3 wifi and mobile helpers`() {
        assertEquals("5 GHz", NetTable.band(5180)); assertEquals(36, NetTable.channel(5180))
        assertEquals("2.4 GHz", NetTable.band(2437)); assertEquals(6, NetTable.channel(2437)); assertEquals(14, NetTable.channel(2484))
        assertEquals("6 GHz", NetTable.band(5955)); assertEquals(1, NetTable.channel(5955))
        assertNull(NetTable.band(0)); assertNull(NetTable.channel(900))
        assertEquals("Wi-Fi 6 (802.11ax)", NetTable.wifiStandard(7)); assertEquals("Wi-Fi 4 (802.11n)", NetTable.wifiStandard(4))
        assertEquals("Wi-Fi 7 (802.11be)", NetTable.wifiStandard(8)); assertNull(NetTable.wifiStandard(0))
        assertEquals(15, NetTable.asu(-83, nr = false)); assertEquals(57, NetTable.asu(-83, nr = true))
        assertEquals("yes", NetTable.yesNo(true)); assertEquals("no", NetTable.yesNo(false))
    }

    // ── U4
    @Test fun `U4 every declared row is built, in order, even when no reader supplied it`() {
        val decl = StatusBarDecl.declared.networkTable!!
        val groups = NetTable.build(decl, emptyMap())
        assertEquals(NetTable.ids(decl), groups.flatMap { g -> g.rows.map { it.id } })
        for (g in groups) for (r in g.rows) {
            assertEquals("${r.id} cells", maxOf(1, g.columns.size), r.cells.size)
            assertTrue(r.cells.all { it == NetValue.NA(NetTable.NOT_READ) })
        }
        // the rows the spec names are all declared
        val want = listOf("transport", "ssid", "bssid", "band", "channel", "link", "rssi", "signal", "freq", "standard", "carrier", "nettype", "msignal",
            "roaming", "ipv4", "ipv6", "public_ip", "dns", "gateway", "private_dns", "wg", "mesh_ip", "warp", "metered", "validated", "captive",
            "use_wifi", "use_mobile", "use_total", "down", "up", "peak_down", "peak_up", "st_down", "st_up", "st_latency", "st_jitter", "st_last")
        assertTrue(NetTable.ids(decl).containsAll(want))
        // a grid has the declared columns: now MB/s, now Mbps, 24 h avg MB/s, 24 h avg Mbps
        val speed = decl.groups.first { it.id == "speed" }
        assertEquals(listOf("MB/s", "Mbps", "MB/s", "Mbps"), speed.columns.map { it.unit })
        assertEquals(listOf("Now", "Now", "24 h avg", "24 h avg"), speed.columns.map { it.label })
    }

    // ── U5
    @Test fun `U5 the speed-test history keeps 24 h with best worst and average`() {
        val now = 100 * h
        fun run(at: Long, d: Double?, u: Double?) = SpeedRun(at, d, u, 10.0, 1.0, "wifi")
        val all = listOf(run(now - 25 * h, 999.0, 999.0), run(now - 3 * h, 40.0, 10.0), run(now - 2 * h, 100.0, null), run(now - h, 70.0, 20.0))
        val kept = SpeedHist.prune(all, now)
        assertEquals(3, kept.size)
        val d = SpeedHist.down(kept)!!
        assertEquals(100.0, d.best, 1e-9); assertEquals(40.0, d.worst, 1e-9); assertEquals(70.0, d.avg, 1e-9)
        val u = SpeedHist.up(kept)!!
        assertEquals(20.0, u.best, 1e-9); assertEquals(10.0, u.worst, 1e-9); assertEquals(15.0, u.avg, 1e-9)
        assertNull(SpeedHist.down(emptyList()))
        assertEquals(kept, SpeedHist.decode(SpeedHist.encode(kept)))
        assertEquals(emptyList<SpeedRun>(), SpeedHist.decode("x,1"))
    }

    // ── U6
    @Test fun `U6 the trace gives ip and warp, and is cached for the declared 5 min`() {
        val info = PublicIp.infoOf("fl=1\nip=203.0.113.9\nwarp=off\nloc=PT\n", 1000)!!
        assertEquals("203.0.113.9", info.ip); assertEquals("off", info.warp)
        assertEquals("off", PublicIp.warpText(info))
        assertEquals("on (Cloudflare WARP)", PublicIp.warpText(info.copy(warp = "on")))
        assertNull(PublicIp.warpText(info.copy(warp = null)))
        assertNull(PublicIp.infoOf("warp=on\n", 1000))
        val ttl = StatusBarDecl.declared.networkSpeed!!.publicIpTtlMs
        assertEquals(300_000L, ttl)
        assertTrue(PublicIp.fresh(info, 1000 + ttl - 1, ttl)); assertFalse(PublicIp.fresh(info, 1000 + ttl, ttl)); assertFalse(PublicIp.fresh(null, 1, ttl))
    }

    // ── U7
    @Test fun `U7 the meter takes no sample until the dropdown opens and none after it closes`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val looper = shadowOf(Looper.getMainLooper())
        val m = SpeedMeter(ctx, 1_000L)
        looper.idleFor(Duration.ofSeconds(30))
        assertEquals(0, m.sampleCount); assertFalse(m.running)
        m.start()
        assertTrue(m.running)
        looper.idleFor(Duration.ofSeconds(5))
        val open = m.sampleCount
        assertTrue("sampled while open: $open", open in 4..5)
        m.stop()
        assertFalse(m.running)
        looper.idleFor(Duration.ofSeconds(30))
        assertEquals(open, m.sampleCount)
        // a speed test is never started by constructing it
        val t = SpeedTest(ctx)
        looper.idleFor(Duration.ofSeconds(5))
        assertFalse(t.running)
    }
}
