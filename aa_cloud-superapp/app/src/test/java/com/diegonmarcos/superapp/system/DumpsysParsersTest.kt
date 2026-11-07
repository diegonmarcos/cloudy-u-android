package com.diegonmarcos.superapp.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The status-bar dropdowns' parsers, on dumps shaped like the real ones, and the FPS counter.
 *
 *   P1 Running Services: services per package, foreground count, pid, from `dumpsys activity services`
 *   P2 Last 3h Memory Usage: heaviest processes by average PSS from `dumpsys procstats --hours 3`
 *   P3 Battery since last charge: usage infos and per-app rows from `dumpsys batterystats --charged`
 *   P4 no ADB = a clear "needs ADB" line, a dump that parses to nothing = "no data", neither throws
 *   F1 the FPS counter reads the frames of the last second and falls to 0 when they stop
 */
class DumpsysParsersTest {

    private val services = """
ACTIVITY MANAGER SERVICES (dumpsys activity services)
  User 0 active services:
  * ServiceRecord{1a2b u0 com.diegonmarcos.superapp/.shell.BrokerService}
    intent={cmp=com.diegonmarcos.superapp/.shell.BrokerService}
    packageName=com.diegonmarcos.superapp
    isForeground=true foregroundId=7
    app=ProcessRecord{3c4d 4242:com.diegonmarcos.superapp/u0a143}
    createTime=-2h5m3s startingBgTimeout=--
  * ServiceRecord{5e6f u0 com.android.systemui/.SystemUIService}
    app=ProcessRecord{7a8b 999:com.android.systemui/u0a40}
    createTime=-5h1m startingBgTimeout=--
  * ServiceRecord{9c0d u0 com.android.systemui/.ImageWallpaper}
    app=ProcessRecord{7a8b 999:com.android.systemui/u0a40}
"""

    @Test
    fun `P1 running services are grouped per package with foreground and pid`() {
        val l = DumpsysParsers.runningServices(services, 10)
        assertEquals("3 services in 2 apps, 2 processes, 1 foreground", l[0].value)
        assertEquals("superapp", l[1].label)
        assertTrue(l[1].value, l[1].value.contains("1 fg") && l[1].value.contains("pid 4242"))
        assertTrue(l[2].value, l[2].value.startsWith("2 svc") && l[2].value.contains("pid 999"))
        assertEquals(2, DumpsysParsers.runningServices(services, 1).size - 1 + 1)
    }

    private val procstats = """
AGGREGATED OVER LAST 3 HOURS:
  * com.android.systemui / u0a40 / v34:
           TOTAL: 100% (80MB-96MB-120MB/70MB-85MB-100MB/150MB-170MB-190MB over 40)
  * com.diegonmarcos.superapp / u0a143 / v1:
           TOTAL: 100% (30MB-41MB-50MB/20MB-30MB-40MB/60MB-70MB-80MB over 40)
  * com.example.big / u0a200 / v3:
           TOTAL: 60% (400MB-512MB-600MB/300MB-400MB-500MB/700MB-800MB-900MB over 12)
"""

    @Test
    fun `P2 last 3h memory lists processes by average PSS`() {
        val p = DumpsysParsers.procstats(procstats)
        assertEquals(listOf("com.android.systemui", "com.diegonmarcos.superapp", "com.example.big"), p.map { it.process })
        assertEquals(96.0, p[0].avgMb, 0.01)
        val l = DumpsysParsers.memory3h("  Total RAM: 7.5GB (status normal)\n$procstats", 2)
        assertEquals(DdLine("Total RAM", "7.5GB (status normal)"), l[0])
        assertEquals("com.example.big", l[1].label)
        assertTrue(l[1].value, l[1].value.startsWith("avg 512.0 MB"))
        assertEquals(3, l.size)
    }

    private val batterystats = """
Statistics since last charge:
  System starts: 0, currently on battery: true
  Time on battery: 3h 2m 1s 100ms (98.5%) realtime, 2h 1m (60.0%) uptime
  Time on battery screen off: 2h 10m (70.0%) realtime
  Estimated power use (mAh):
    Capacity: 5000, Computed drain: 245, actual drain: 200-250
    Screen: 82.0 Excluding smearing: 80
    Uid u0a143: 41.0 ( cpu=36.9 wifi=1.2 ) Excluding smearing: 38
    Cell standby: 12.0 ( radio=12.0 )
    Uid 1000: 10.0 ( cpu=10.0 )

  Other section: 99
"""

    @Test
    fun `P3 since last charge gives usage infos and per-app rows`() {
        val s = DumpsysParsers.batteryStats(batterystats)
        val usage = DumpsysParsers.usageInfos(s)
        assertEquals(DdLine("Time on battery", "3h 2m 1s 100ms (98.5%) realtime, 2h 1m (60.0%) uptime"), usage[0])
        assertTrue(usage.any { it.label == "Capacity (mAh)" && it.value.startsWith("5000") })
        assertEquals("Screen", usage.first { it.value.endsWith("mAh") }.label)
        val apps = DumpsysParsers.perApp(s, 5) { n -> DumpsysParsers.uidOf(n).toString() }
        assertEquals(listOf("10143", "1000"), apps.map { it.label })
        assertTrue(apps[0].value, apps[0].value.startsWith("41.0 mAh · 80.4%"))
        assertEquals(10143, DumpsysParsers.uidOf("u0a143"))
        assertEquals(1_010_005, DumpsysParsers.uidOf("u10a5"))
    }

    @Test
    fun `P4 no adb and an empty dump degrade to a status line`() {
        for (g in listOf("running_services", "memory_3h", "since_charge_usage", "since_charge_apps")) {
            assertEquals(SystemGroups.NEEDS_ADB, SystemGroups.adbLines(g, null, 5).single().value)
            assertEquals("no data in the dump", SystemGroups.adbLines(g, "garbage", 5).single().value)
        }
    }

    @Test
    fun `F1 the fps counter reads the last second and falls to zero`() {
        val c = FpsCounter()
        val frame = 16_666_667L
        for (i in 0 until 90) c.onFrame(i * frame)
        assertEquals(60, c.fps(89 * frame))
        assertEquals(0, c.fps(89 * frame + 2_000_000_000L))
        c.onFrame(5_000_000_000L)
        assertEquals("one lone frame is not an animation", 0, c.fps(5_000_000_001L))
    }

    @Test
    fun `F2 the published readout follows the frames fed to it`() {
        val t0 = System.nanoTime()
        for (i in 0 until 45) FpsMeter.frame(t0 + i * 22_222_222L)   // 45 fps for one second
        val f = FpsMeter.publish(t0 + 44 * 22_222_222L)
        assertTrue("read $f", f in 44..46)
        assertEquals(f, FpsMeter.fps)
        FpsMeter.publish(t0 + 10_000_000_000L)
        assertEquals(0, FpsMeter.fps)
    }
}
