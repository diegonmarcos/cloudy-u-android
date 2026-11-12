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
 *   G1-G3 gfxinfo (frames, jank, percentiles, slow causes, histogram, draw/triangle lines when present), T1 top, N1 notifications, B1 binder,
 *   M1 meminfo detail, A1 ANR, H1 thermal in full, W1 wakelocks/uid rows, all tolerant: junk in, nothing out
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
        // #1638 the graph: header lines have no bar, the heaviest process is the full bar, the others scale to it
        val all = DumpsysParsers.memory3h("  Total RAM: 7.5GB (status normal)\n$procstats", 3)
        val bars = DumpsysParsers.memory3hBars(all)
        assertEquals(all.size, bars.size)
        assertEquals(null, bars[0])
        assertEquals(1f, bars[1]!!, 0.001f)
        assertEquals(96f / 512f, bars[2]!!, 0.001f)
        assertEquals(41f / 512f, bars[3]!!, 0.001f)
        assertEquals(listOf<Float?>(null), DumpsysParsers.memory3hBars(listOf(DdLine("Status", "needs ADB"))))
        assertTrue(DumpsysParsers.memory3hBars(emptyList()).isEmpty())
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
    fun `P3b uid rows in usage infos gain the app name, categories and unknown uids stay`() {
        val dump = """
  Estimated power use (mAh):
    Capacity: 5000, Computed drain: 245, actual drain: 200-250
    UID u0a355: 282.0 ( cpu=200.0 )
    UID 1000: 271.0 ( cpu=10.0 )
    UID u0a463: 104.0
    Screen: 82.0
    Wifi: 3.0
"""
        val names = mapOf("u0a355" to "Cloud Store", "1000" to "Android system (uid 1000)")
        val u = DumpsysParsers.usageInfos(DumpsysParsers.batteryStats(dump)) { names[it] ?: it }
        assertEquals("UID u0a355 \u00b7 Cloud Store", u.first { it.value == "282.0 mAh" }.label)
        assertEquals("UID 1000 \u00b7 Android system (uid 1000)", u.first { it.value == "271.0 mAh" }.label)
        assertEquals("UID u0a463", u.first { it.value == "104.0 mAh" }.label)
        assertEquals(listOf("Screen", "Wifi"), u.filter { it.label in listOf("Screen", "Wifi") }.map { it.label })
        val throwing = DumpsysParsers.usageInfos(DumpsysParsers.batteryStats(dump)) { error("no pm") }
        assertEquals("UID u0a463", throwing.first { it.value == "104.0 mAh" }.label)
        assertEquals(u.size, throwing.size)
    }

    @Test
    fun `P4 no adb and an empty dump degrade to a status line`() {
        for (g in listOf("running_services", "memory_3h", "since_charge_usage", "since_charge_apps")) {
            assertEquals(SystemGroups.NEEDS_ADB, SystemGroups.adbLines(g, null, 5).single().value)
            assertEquals("no data in the dump", SystemGroups.adbLines(g, "garbage", 5).single().value)
        }
    }

    private val gfx = """
Applications Graphics Acceleration Info:
Uptime: 4000000 Realtime: 4000000

** Graphics info for pid 4242 [com.diegonmarcos.superapp] **

Stats since: 3000000000000ns
Total frames rendered: 1500
Janky frames: 120 (8.00%)
Janky frames (legacy): 130 (8.67%)
50th percentile: 8ms
90th percentile: 16ms
95th percentile: 24ms
99th percentile: 48ms
50th gpu percentile: 3ms
Number Missed Vsync: 10
Number High input latency: 3
Number Slow UI thread: 40
Number Slow bitmap uploads: 2
Number Slow issue draw commands: 30
Number Frame deadline missed: 80
Number Frame deadline missed (legacy): 90
HISTOGRAM: 5ms=100 6ms=200 7ms=0 8ms=400 16ms=60 32ms=5
Total GPU memory usage:
  Draw calls: 1234
  Triangles drawn: 56789
  Total memory usage:
  14680064 bytes, 14.00 MB (3.00 MB is purgeable)
"""

    @Test
    fun `G1 gfxinfo parses frames, jank, percentiles, slow causes and the histogram`() {
        val g = DumpsysParsers.gfxinfo(gfx)!!
        assertEquals(1500L, g.total); assertEquals(120L, g.janky); assertEquals(8.0, g.jankyPct!!, 0.001)
        assertEquals(mapOf(50 to 8.0, 90 to 16.0, 95 to 24.0, 99 to 48.0), g.percentiles)
        assertEquals(10L, g.slow.first { it.first == "Missed Vsync" }.second)
        assertEquals(40L, g.slow.first { it.first == "Slow UI thread" }.second)
        assertTrue("the legacy counter is not a second row", g.slow.none { it.first.contains("legacy") })
        assertEquals(6, g.histogram.size); assertEquals(400L, g.histogram.first { it.first == 8 }.second)
        val live = DumpsysParsers.gfxinfoLines(gfx).associate { it.label to it.value }
        assertEquals("1500 rendered · 120 janky (8.0%)", live["Frames"])
        assertEquals("p50 8.0 ms · p90 16.0 ms · p95 24.0 ms · p99 48.0 ms", live["Frame time"])
        val jank = DumpsysParsers.gfxinfoJank(gfx)
        assertEquals("Jank", jank.first().label)
        assertEquals("Frame deadline missed", jank[1].label)
        assertEquals("Slow UI thread", jank[2].label)
        assertTrue(jank.last().value.startsWith("5ms:100 6ms:200 8ms:400"))
        assertTrue("zero buckets are dropped", !jank.last().value.contains("7ms"))
    }

    @Test
    fun `G2 draw-call, triangle and GPU-memory lines are parsed when the build prints them`() {
        val labels = DumpsysParsers.gfxinfoLines(gfx).map { it.label.lowercase() }
        assertTrue(labels.any { it.contains("draw calls") }); assertTrue(labels.any { it.contains("triangles") })
        assertEquals("1234", DumpsysParsers.gfxinfoLines(gfx).first { it.label.lowercase().contains("draw calls") }.value)
    }

    @Test
    fun `G3 a build without those lines, or no gfxinfo at all, degrades silently`() {
        val plain = "Total frames rendered: 10\nJanky frames: 1 (10.00%)\n"
        val l = DumpsysParsers.gfxinfoLines(plain)
        assertEquals(1, l.size)
        assertEquals("1 / 10", "${DumpsysParsers.gfxinfo(plain)!!.janky} / ${DumpsysParsers.gfxinfo(plain)!!.total}")
        assertEquals(null, DumpsysParsers.gfxinfo("No process found for: com.x"))
        assertEquals(emptyList<DdLine>(), DumpsysParsers.gfxinfoLines("garbage")); assertEquals(emptyList<DdLine>(), DumpsysParsers.gfxinfoJank(""))
        assertEquals("no data in the dump", SystemGroups.adbLines("jank", "garbage", 5).single().value)
    }

    @Test
    fun `T1 top rows are sorted by cpu, with the load and busy lines`() {
        val top = """
Tasks: 480 total,   2 running, 478 sleeping,   0 stopped,   0 zombie
  Mem:  7.5G total,  6.9G used,  600M free,   40M buffers
 800%cpu 120%user   0%nice  80%sys 590%idle   0%iow  10%irq   0%sirq   0%host
  PID USER         PR  NI VIRT  RES  SHR S[%CPU] %MEM     TIME+ ARGS
 1234 u0a143       10 -10 5.0G 200M 100M S  45.5   2.6   0:12.34 com.diegonmarcos.superapp
 2345 system       18  -2  12G 400M 150M S  88.0   5.1   1:02.03 system_server
 9999 shell        20   0 1.0G  10M   5M R   2.0   0.1   0:00.10 top
"""
        val p = DumpsysParsers.topProcs(top)
        assertEquals(listOf("system_server", "com.diegonmarcos.superapp"), p.map { it.name })
        assertEquals(88.0, p.first().cpu, 0.001); assertEquals(2345, p.first().pid)
        val l = DumpsysParsers.topLines(top, 5)
        assertEquals("CPU busy", l.first().label); assertEquals("210% of 800%", l.first().value)
        assertEquals("top system_server", l[1].label); assertEquals("88.0% · pid 2345", l[1].value)
        // the dumpsys cpuinfo fallback
        val ci = "Load: 3.1 / 2.5 / 2.0\n  45% 1234/com.foo: 30% user + 15% kernel\n  12% 55/surfaceflinger: 8% user + 4% kernel\n"
        assertEquals(listOf("com.foo", "surfaceflinger"), DumpsysParsers.topProcs(ci).map { it.name })
        assertEquals("3.1 / 2.5 / 2.0", DumpsysParsers.topLines(ci, 3).first { it.label.startsWith("Load") }.value)
        assertEquals(emptyList<DdLine>(), DumpsysParsers.topLines("nothing", 3))
    }

    private val notif = """
NotificationManagerService
  Notification list:
    NotificationRecord(0x0a1b2c3: pkg=com.chatty.app user=UserHandle{0} id=7 tag=null importance=3 key=0|com.chatty.app|7|null|10160)
    NotificationRecord(0x0d4e5f6: pkg=com.chatty.app user=UserHandle{0} id=8 tag=null importance=3 key=0|com.chatty.app|8|null|10160)
    NotificationRecord(0x0111111: pkg=com.diegonmarcos.superapp user=UserHandle{0} id=1 tag=null importance=2 key=x)
  Usage stats:
    AggregatedStats{
      key='com.chatty.app',
      numPostedByApp=1000,
      numUpdatedByApp=216000,
      numRemovedByApp=900,
      numQuotaViolations=40,
    }
    AggregatedStats{
      key='com.diegonmarcos.superapp',
      numPostedByApp=12,
      numUpdatedByApp=30,
    }
    AggregatedStats{
      key='com.quiet.app',
      numPostedByApp=3,
      numUpdatedByApp=0,
    }
"""

    @Test
    fun `N1 notification posts per app, busiest first - the 217k storm surfaces`() {
        val s = DumpsysParsers.notificationStats(notif)
        assertEquals(listOf("com.chatty.app", "com.diegonmarcos.superapp", "com.quiet.app"), s.map { it.pkg })
        assertEquals(217_000L, s.first().total); assertEquals(2, s.first().active)
        assertEquals(1, s[1].active)
        val l = DumpsysParsers.notifications(notif, 2)
        assertEquals("Total posts", l.first().label)
        assertEquals("chatty.app".let { "com.$it" }, l[1].label)
        assertEquals("217000 posts (1000 new + 216000 updates) · 2 active", l[1].value)
        assertEquals(3, l.size)
        assertEquals(emptyList<DdLine>(), DumpsysParsers.notifications("nope", 5))
        assertEquals("no data in the dump", SystemGroups.adbLines("notif_rate", "nope", 5).single().value)
    }

    @Test
    fun `B1 binder calls per uid, tolerant of other formats`() {
        val b = "Binder call stats:\n  uid=1000 calls=5000 latency=1\n  uid=10143 callCount=300\n  uid=1000 count=20\n  unrelated line\n"
        val s = DumpsysParsers.binderCalls(b)
        assertEquals(listOf("1000" to 5020L, "10143" to 300L), s.map { it.uid to it.calls })
        assertEquals("5320 calls".let { "Total calls" }, DumpsysParsers.binder(b, 5).first().label)
        assertEquals(emptyList<DdLine>(), DumpsysParsers.binder("Service binder_calls_stats does not exist", 5))
    }

    @Test
    fun `M1 meminfo detail reads the RAM totals and the PSS-per-process table`() {
        val m = """
Applications Memory Usage (in Kilobytes):
Uptime: 1 Realtime: 1

Total PSS by process:
    412,345K: system_server (pid 2345 / activities)
    201,000K: com.diegonmarcos.superapp (pid 1234 / activities)
     50,000K: surfaceflinger (pid 55)

Total PSS by OOM adjustment:
    900,000K: Native

Total RAM: 7,654,321K (status normal)
 Free RAM: 1,234,567K (  300,000K cached pss +  800,000K cached kernel +  134,567K free)
 Used RAM: 5,000,000K
 Lost RAM:   100,000K
     ZRAM:   50,000K physical used for  200,000K in swap ( 4,000,000K total swap)
"""
        val procs = DumpsysParsers.meminfoProcs(m)
        assertEquals(listOf("system_server", "com.diegonmarcos.superapp", "surfaceflinger"), procs.map { it.name })
        assertEquals(412_345L, procs.first().kb); assertEquals(55, procs.last().pid)
        val l = DumpsysParsers.meminfoDetail(m, 2)
        assertEquals(listOf("Total RAM", "Free RAM", "Used RAM", "Lost RAM", "ZRAM"), l.take(5).map { it.label })
        assertEquals("402.7 MB PSS · pid 2345", l[5].value)
        assertEquals(7, l.size)
        assertEquals(emptyList<DdLine>(), DumpsysParsers.meminfoDetail("garbage", 5))
    }

    @Test
    fun `A1 ANR events and trace files, none on a healthy device, nothing on a hidden directory`() {
        val a = """
11-08 10:00:00.123  1000  1234  1250 I am_anr  : [0,5555,com.diegonmarcos.superapp,950025541,Input dispatching timed out (waiting to send)]
11-08 11:30:10.000  1000  1234  1250 I am_anr  : [0,6666,com.other.app,1,executing service com.other.app/.Svc]
---
-rw------- 1 system system 123456 2026-11-08 10:00 anr_2026-11-08-10-00-00-123
-rw------- 1 system system 99 2026-11-08 09:00 notes.txt
"""
        val l = DumpsysParsers.anrs(a)
        assertEquals(3, l.size)
        assertEquals("com.diegonmarcos.superapp", l[0].pkg); assertEquals("11-08 10:00:00", l[0].whenText)
        assertTrue(l[2].detail.contains("anr_2026-11-08-10-00-00-123") && l[2].detail.contains("120 kB"))
        val lines = DumpsysParsers.anrLines(a, 5)
        assertEquals("3 recorded", lines.first().value)
        assertEquals("newest listed first", "2026-11-08 10:00", lines[1].label)
        assertEquals("none recorded", DumpsysParsers.anrLines("---\ntotal 0\n", 5).single().value)
        assertEquals(emptyList<DdLine>(), DumpsysParsers.anrLines("ls: /data/anr: Permission denied", 5))
        assertEquals(emptyList<DdLine>(), DumpsysParsers.anrLines("", 5))
    }

    @Test
    fun `H1 thermal in full - status, every sensor with its throttle level, cooling devices`() {
        val t = """
IsStatusOverride: false
Thermal Status: 2
Cached temperatures:
	Temperature{mValue=36.5, mType=3, mName=skin, mStatus=0}
	Temperature{mValue=41.2, mType=0, mName=cpu0, mStatus=2}
	Temperature{mValue=38.0, mType=2, mName=battery, mStatus=0}
HAL Cooling Devices:
	CoolingDevice{mValue=3, mType=1, mName=cpu-cluster1}
	Temperature{mValue=36.5, mType=3, mName=skin, mStatus=0}
"""
        val l = DumpsysParsers.thermalFull(t)
        assertEquals("Throttle status" to "moderate (2)", l[0].label to l[0].value)
        assertEquals("36.5 C · skin", l.first { it.label == "skin" }.value)
        assertEquals("41.2 C · cpu · THROTTLE moderate", l.first { it.label == "cpu0" }.value)
        assertEquals(1, l.count { it.label == "skin" })
        assertEquals("level 3", l.first { it.label == "cool cpu-cluster1" }.value)
        assertEquals(emptyList<DdLine>(), DumpsysParsers.thermalFull("nothing"))
        assertEquals("36.5 C", DumpsysParsers.thermal(t).first { it.label == "skin" }.value)
        assertEquals("no data in the dump", SystemGroups.adbLines("thermal", "nothing", 5).single().value)
    }

    @Test
    fun `W1 wakelocks by count and duration, per-uid activity, alarms and jobs`() {
        val b = """
  u0a150:
    Wake lock Spin: 1m 0s 0ms (5000 times) realtime
    Wake lock Long: 30m 0s 0ms (2 times) realtime
    Cpu: 10m 0s 0ms usr + 2m 0s 0ms krn
    GPS: 3m 0s 0ms
  u0a151:
    Wake lock Mid: 2m 0s 0ms (40 times) realtime
"""
        val l = DumpsysParsers.wakelockLines(b, 4) { "app-$it" }
        assertEquals(listOf("By count", "Spin", "Mid", "By duration", "Long", "Mid"), l.map { it.label })
        assertEquals("5000x · 1.0 min · app-u0a150", l[1].value)
        val u = DumpsysParsers.uidActivityLines(b, 5)
        assertEquals("u0a150", u.single().label)
        assertTrue(u.single().value.startsWith("cpu 12.0 min (bg 12.0)"))
        assertEquals("25 wakeups", DumpsysParsers.alarmLines("  u0a150:com.diegonmarcos.fleet +1s running, 25 wakeups:\n", 3).single().value)
        assertEquals("1 jobs (1 WorkManager)", DumpsysParsers.jobLines("  JOB #u0a150/9: 1a com.diegonmarcos.fleet/androidx.work.impl.background.systemjob.SystemJobService\n", 3).single().value)
        for (g in listOf("wakelocks", "power_by_uid", "alarms", "jobs", "binder", "meminfo_detail", "notif_rate", "jank", "thermal", "anr")) {
            assertEquals(SystemGroups.NEEDS_ADB, SystemGroups.adbLines(g, null, 5).single().value)
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

    // ── the 13-tracker parsers: Storage, Crash, Network, Process & Lifecycle, Security & Perms ──

    @Test
    fun `K1 storage - df mounts and dumpsys diskstats free, media and per-app sizes`() {
        val df = """
Filesystem            Size  Used Avail Use% Mounted on
tmpfs                 2.5G  1.1M  2.5G   1% /dev
/dev/block/dm-9       220G  190G   30G  87% /data
/dev/fuse             220G  190G   30G  87% /storage/emulated
"""
        val l = DumpsysParsers.dfLines(df, 5)
        assertEquals(listOf("/data", "/storage/emulated"), l.map { it.label })
        assertEquals("190G used of 220G (87%) · 30G free", l[0].value)
        assertEquals(87, DumpsysParsers.dfDataPercent(df))
        val ds = """
Latency: 8ms [512B Data Write]
Data-Free: 31457280K / 230686720K total = 13% free
Cache-Free: 1024K / 4096K total = 25% free
System-Free: 2097152K / 4194304K total = 50% free
App Size: 1073741824
App Data Size: 536870912
App Cache Size: 104857600
Photos Size: 2147483648
Package Names: ["com.a.big","com.diegonmarcos.superapp","com.c.small"]
App Sizes: [900000000, 50000000, 1000]
App Data Sizes: [3000000000, 20000000, 500]
Cache Sizes: [100000000, 5000000, 0]
"""
        val d = DumpsysParsers.diskstats(ds, 20)
        assertEquals("Data free", d.first().label); assertTrue(d.first().value.contains("13% free"))
        assertTrue(d.any { it.label == "Photos size" && it.value == "2.00 GB" })
        val sizes = DumpsysParsers.appSizes(ds)
        assertEquals(listOf("com.a.big", "com.diegonmarcos.superapp", "com.c.small"), sizes.map { it.pkg })
        assertEquals(4_000_000_000L, sizes.first().total)
        assertEquals("superapp", DumpsysParsers.appSizeLines(ds, 2)[1].label)
        for (junk in listOf("", "garbage\n\u0000", "Package Names: [")) { assertTrue(DumpsysParsers.diskstats(junk, 5).isEmpty()); assertTrue(DumpsysParsers.appSizes(junk).isEmpty()); assertTrue(DumpsysParsers.dfLines(junk, 5).isEmpty()) }
        assertEquals("no data in the dump", SystemGroups.adbLines("df", "nothing", 5).single().value)
        assertEquals(SystemGroups.NEEDS_ADB, SystemGroups.adbLines("app_sizes", null, 5).single().value)
    }

    @Test
    fun `K2 crash - dropbox crash and anr entries with their process`() {
        val db = """
2026-11-05 12:07:44 data_app_anr (text, 2322 bytes)
Process: com.diegonmarcos.superapp
Subject: Input dispatching timed out
2026-11-06 02:17:23 system_app_crash (text, 900 bytes)
Package: com.sec.android.x v1
2026-11-06 03:00:00 data_app_native_crash (text, 100 bytes)
Process: com.foo.native
2026-11-06 04:00:00 event_data (text, 10 bytes)
"""
        val c = DumpsysParsers.crashes(db)
        assertEquals(listOf("data_app_anr", "system_app_crash", "data_app_native_crash"), c.map { it.tag })
        assertEquals("com.diegonmarcos.superapp", c[0].proc); assertEquals("com.sec.android.x", c[1].proc)
        val l = DumpsysParsers.crashLines(db, 2)
        assertEquals("3 recorded", l.first().value); assertEquals(3, l.size)
        assertEquals("none recorded", DumpsysParsers.crashLines("no crashes here", 3).single().value)
        assertTrue(DumpsysParsers.crashLines("", 3).isEmpty())
        assertEquals("3 recorded", SystemGroups.adbLines("crashes", db, 5).first().value)
    }

    @Test
    fun `K3 network - netstats per uid, connectivity and radio signal`() {
        val ns = """
  ident=[{type=MOBILE}] uid=10150 set=DEFAULT tag=0x0
    NetworkStatsHistory: bucketDuration=3600
      st=1762000000 rb=1000 rp=2 tb=500 tp=1 op=0
      st=1762003600 rb=2000 rp=3 tb=100 tp=1 op=0
  ident=[{type=WIFI}] uid=10150 set=DEFAULT tag=0x1
      st=1762000000 rb=999999 rp=2 tb=999999 tp=1 op=0
  ident=[{type=WIFI}] uid=10151 set=FOREGROUND tag=0x0
      st=1762000000 rb=50 rp=2 tb=40 tp=1 op=0
"""
        val u = DumpsysParsers.netUsage(ns)
        assertEquals(listOf("10150", "10151"), u.map { it.uid })
        assertEquals(3000L, u[0].rx); assertEquals(600L, u[0].tx)
        assertTrue(DumpsysParsers.netUsageLines(ns, 5).first().value.contains("down"))
        assertTrue(DumpsysParsers.netUsage("junk").isEmpty())
        val cn = "Active default network: 100\n  NetworkAgentInfo{network{100}  type: WIFI[]  VALIDATED\n  NetworkAgentInfo{network{101}  type: MOBILE[] NOT_VALIDATED\n"
        val c = DumpsysParsers.connectivity(cn, 6)
        assertEquals("id 100", c.first { it.label == "Default network" }.value)
        assertTrue(c.first { it.label == "Networks up" }.value.contains("WIFI x1"))
        assertTrue(c.first { it.label == "Validation" }.value.contains("not"))
        val tr = "mSignalStrength=SignalStrength: level=3 rsrp=-98\nmDataConnectionState=2\nmNetworkType=LTE\nmCallState=0\n"
        val s = DumpsysParsers.signal(tr, 6)
        assertEquals("level 3/4 · -98 dBm", s.first { it.label == "Signal" }.value)
        assertEquals("connected", s.first { it.label == "Data state" }.value)
        assertEquals("LTE", s.first { it.label == "Network type" }.value)
        assertTrue(DumpsysParsers.signal("junk", 6).isEmpty() && DumpsysParsers.connectivity("junk", 6).isEmpty())
        assertEquals("no data in the dump", SystemGroups.adbLines("net_signal", "junk", 5).single().value)
    }

    @Test
    fun `K4 process lifecycle - oom-adj LRU rows`() {
        val pr = """
  Process LRU list (sorted by oom_adj, 3 total, non-act at 1, non-svc at 1):
    Proc #0: fg     T/A/T  trm: 0 19937:com.diegonmarcos.superapp/u0a355 (top-activity)
    Proc #1: vis    T/A/T  trm: 0 2411:com.android.systemui/u0a12 (service)
    Proc #2: cch+5  CE     LRU 0 5000:com.old.app/u0a99 (cch-empty)
"""
        val p = DumpsysParsers.oomProcs(pr)
        assertEquals(listOf("fg", "vis", "cch+5"), p.map { it.adj })
        assertEquals(19937, p[0].pid); assertEquals("com.old.app", p[2].name)
        val l = DumpsysParsers.oomLines(pr, 5)
        assertTrue(l.first().value.startsWith("3 · "))
        assertEquals(3, l.size) // header + the two non-cached rows
        assertTrue(DumpsysParsers.oomLines("junk", 5).isEmpty())
        assertEquals("no data in the dump", SystemGroups.adbLines("oom_adj", "junk", 5).single().value)
    }

    @Test
    fun `K5 security - requested and granted permissions of this app`() {
        val pk = """
    requested permissions:
      android.permission.INTERNET
      android.permission.CAMERA
      android.permission.RECORD_AUDIO
    install permissions:
      android.permission.INTERNET: granted=true
    runtime permissions:
      android.permission.CAMERA: granted=true
      android.permission.RECORD_AUDIO: granted=false
"""
        assertEquals(listOf("INTERNET", "CAMERA"), DumpsysParsers.grantedPerms(pk))
        val l = DumpsysParsers.permLines(pk, 5)
        assertEquals("3", l.first { it.label == "Requested" }.value)
        assertEquals("2", l.first { it.label == "Granted" }.value)
        assertTrue(DumpsysParsers.permLines("junk", 5).isEmpty())
        assertEquals("2", SystemGroups.adbLines("sec_perms", pk, 5).first { it.label == "Granted" }.value)
    }

    @Test
    fun `K6 Android 16 formats - wakelock duration before partial, service record with a trailing c colon`() {
        val b = "  u0a150:\n    Wake lock Prevent Sleep: 15m 38s 1ms partial (229348 times) max=100 realtime\n"
        assertEquals(229348L, com.diegonmarcos.superapp.xray.XrayAnalyze.wakelocks(b).single().count)
        val sv = "  * ServiceRecord{dac7255 u0 com.google.android.gms/.nearby.Life c:com.google.android.gms}\n    isForeground=true\n"
        assertEquals("com.google.android.gms", DumpsysParsers.services(sv).single().pkg)
        assertEquals(1, com.diegonmarcos.superapp.xray.XrayAnalyze.services(sv).single().foreground)
    }
}
