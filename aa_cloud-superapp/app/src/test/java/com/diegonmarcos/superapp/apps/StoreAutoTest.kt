package com.diegonmarcos.superapp.apps

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.appstore.FleetInstall
import com.diegonmarcos.superapp.appstore.StoreAuto
import com.diegonmarcos.superapp.appstore.StoreStages
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.cache.ApkCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * #804 — AUTO UPDATE ON + WI-FI: DOWNLOAD EVERYTHING, THEN INSTALL, IN ORDER,
 * AND PICK UP WHERE IT STOPPED.
 *
 * The owner's report: on Wi-Fi with Auto update ON and updates pending, the
 * Store did nothing. The chain under test is [StoreAuto] driven through the
 * REAL path — Fleet.download → ReleaseSource → HttpURLConnection against a
 * local server that counts GETs, bytes and Range headers per asset, and the
 * real StoreStages install / clear — with only the network's answer
 * ([StoreAuto.remote]) and Android's install sheet ([StoreStages.installer])
 * stood in for.
 *
 * "The process died" is a [Killed] thrown from [StoreAuto.checkpoint], which
 * runs right after a package's transition is persisted and before its work: the
 * next [StoreAuto.run] has nothing but what was persisted, exactly like a cold
 * process. Every claim has its control beside it — the same scenario with one
 * fact flipped must flip the verdict.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreAutoTest {

    private class Killed : Error("process killed")

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: TinyHttp
    private val bodies = HashMap<String, ByteArray>()
    private val gets = ConcurrentHashMap<String, AtomicInteger>()
    private val served = ConcurrentHashMap<String, AtomicLong>()
    private val ranges = Collections.synchronizedList(ArrayList<String>())
    private val events = Collections.synchronizedList(ArrayList<String>())
    private val remotes = HashMap<String, Fleet.State>()
    private val remoteCalls = AtomicInteger()
    private val stamp = AtomicLong(100)

    private fun hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** A real zip per build, big enough that a resume shows in the byte count. */
    private fun zip(tag: String): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z ->
            z.setLevel(0)
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(tag.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("assets/payload.bin")); z.write(ByteArray(64 * 1024) { ((it + tag.length) % 251).toByte() }); z.closeEntry()
        }
    }.toByteArray()

    @Volatile private var cancelOn: String? = null

    private fun gets(id: String) = gets["$id.apk"]?.get() ?: 0
    private fun totalGets() = gets.values.sumOf { it.get() }

    @Before
    fun up() {
        UpdateProgress.reset()
        com.diegonmarcos.superapp.appstore.StoreEngine.resetForTest()   // #1517 one engine per process: bind this test's dirs
        // #812 room is real free storage (StatFs); Robolectric's reads 0 — a 40 GB phone.
        ApkCache.freeBytes = { 40_000_000_000L }
        ApkCache.clear(ctx)
        ctx.getSharedPreferences("store_auto", Context.MODE_PRIVATE).edit().clear().commit()
        server = TinyHttp { method, path, headers ->
            val name = path.removePrefix("/r/")
            val body = bodies[name.removeSuffix(".sha256")]
            when {
                body == null -> Triple(404, emptyMap(), ByteArray(0))
                name.endsWith(".sha256") -> Triple(200, emptyMap(), "${hex(body)}  $name\n".toByteArray())
                method == "HEAD" -> Triple(200, emptyMap(), body)
                else -> {
                    events += "get:${name.removeSuffix(".apk")}"
                    // #812 the user taps Cancel while this one is on the wire.
                    if (name == "$cancelOn.apk") UpdateProgress.requestCancel()
                    gets.getOrPut(name) { AtomicInteger() }.incrementAndGet()
                    val range = headers["range"]
                    if (range != null) ranges += "$name $range"
                    val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    val part = body.copyOfRange(from, body.size)
                    served.getOrPut(name) { AtomicLong() }.addAndGet(part.size.toLong())
                    if (from > 0) Triple(206, mapOf("Content-Range" to "bytes $from-${body.size - 1}/${body.size}"), part)
                    else Triple(200, emptyMap(), part)
                }
            }
        }
        StoreAuto.remote = { _, app -> remoteCalls.incrementAndGet(); remotes[app.id] ?: Fleet.State.Installed("1", 1L, "000000000000") }
        StoreAuto.installBudget = { Int.MAX_VALUE }
        StoreAuto.selfUpdate = { events += "self" }
        StoreAuto.checkpoint = { _, _ -> }
        // Android's install sheet, accepting: the device then RUNS those bytes.
        StoreStages.installer = { c, app, v ->
            events += "install:${app.id}"
            installed(c, app.pkg, v.file.readBytes())
            null
        }
    }

    @After
    fun down() {
        server.stop()
        StoreAuto.remote = Fleet::status
        StoreAuto.installBudget = Fleet::unattendedInstallBudget
        StoreAuto.selfUpdate = { com.diegonmarcos.superapp.updater.Updater.start(it) }
        StoreAuto.checkpoint = { _, _ -> }
        StoreStages.installer = FleetInstall::install
        Fleet.autoChain = null
        ApkCache.freeBytes = { c -> runCatching { android.os.StatFs(ApkCache.dir(c).path).availableBytes }.getOrElse { ApkCache.dir(c).usableSpace } }
        UpdateProgress.removeObserver(watch)
        ApkCache.clear(ctx)
        UpdateProgress.reset()
    }

    private fun installed(c: Context, pkg: String, bytes: ByteArray) {
        val src = java.io.File(c.filesDir, "installed-$pkg-${hex(bytes).take(12)}.apk").apply { writeBytes(bytes) }
        shadowOf(c.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg; versionName = "2"; longVersionCode = 2L; lastUpdateTime = stamp.incrementAndGet()
            applicationInfo = ApplicationInfo().apply { packageName = pkg; sourceDir = src.absolutePath }
        })
    }

    private fun entry(id: String, kind: String, pkg: String = "org.example.$id", url: String? = null) = Fleet.App(
        id = id, label = id.replaceFirstChar { it.uppercase() }, pkg = pkg, altId = null,
        registry = "127.0.0.1:1", namespace = "x", image = "x", tag = "latest",
        asset = "$id.apk", assets = emptyMap(),
        releaseUrl = url ?: "http://127.0.0.1:${server.port}/r/$id.apk",
        repoUrl = "", ghcrPage = "", blocked = false, kind = kind,
    )

    /** Published: a new build of [id]; the remote says "update" (or "missing"). */
    private fun publish(id: String, missing: Boolean = false): ByteArray {
        val b = zip("$id-v2")
        bodies["$id.apk"] = b
        remotes[id] = if (missing) Fleet.State.Missing(b.size.toLong())
                      else Fleet.State.UpdateAvailable("2", hex(b).take(12), b.size.toLong(), source = "release")
        return b
    }

    /** The fleet as the manifest lists it: the app FIRST, the host second, libs last —
     *  so any lib-first / host-last order the chain produces is its own doing. */
    private fun fleet(): List<Fleet.App> {
        val app = entry("appa", "app"); val host = entry("host", "app", pkg = ctx.packageName)
        val lib1 = entry("lib1", "lib"); val lib2 = entry("lib2", "lib")
        installed(ctx, app.pkg, zip("appa-v1"))           // the app is on the device at v1
        publish("appa"); publish("lib1"); publish("lib2", missing = true)
        remotes["host"] = Fleet.State.UpdateAvailable("2", "abcdefabcdef", 1L, source = "release")
        return listOf(app, host, lib1, lib2)
    }

    private fun statusOf(s: StoreAuto.State, id: String) = s.queue.first { it.id == id }.status

    private fun killAt(phase: String, id: String, before: () -> Unit = {}) {
        StoreAuto.checkpoint = { p, i -> if (p == phase && i == id) { before(); throw Killed() } }
    }

    private fun runKilled(apps: List<Fleet.App>) {
        try { StoreAuto.run(ctx, apps, StoreAuto.TRIGGER_WIFI); fail("the chain was supposed to die") } catch (e: Killed) {}
        StoreAuto.checkpoint = { _, _ -> }
    }

    // ── the chain, end to end ────────────────────────────────────────────────

    @Test
    fun `804 downloads ALL pending first, installs libs before apps, the host last, then clears`() {
        val apps = fleet() + entry("appb", "app").also { publish("appb", missing = true) }
        val s = StoreAuto.run(ctx, apps, StoreAuto.TRIGGER_WIFI)
        assertEquals(s.summary, StoreAuto.DONE, s.phase)
        val firstInstall = events.indexOfFirst { it.startsWith("install:") }
        val lastGet = events.indexOfLast { it.startsWith("get:") }
        assertTrue("every download precedes every install: $events", firstInstall > lastGet && lastGet >= 0)
        assertEquals("libs (the engines) before apps, whatever the manifest order",
            listOf("install:lib1", "install:lib2", "install:appa"), events.filter { it.startsWith("install:") })
        assertEquals("the host is handed to its self-updater LAST", "self", events.last())
        assertEquals(StoreAuto.HANDED, statusOf(s, "host"))
        for (id in listOf("lib1", "lib2", "appa")) {
            assertEquals(id, StoreAuto.INSTALLED, statusOf(s, id))
            assertEquals("$id fetched exactly once", 1, gets(id))
            assertNull("phase 4 cleared $id's landed cache", StoreStages.cachedFor(ctx, apps.first { it.id == id }))
        }
        // Control: a missing LIB is installed, a missing APP is not (Mode.AUTO's rule).
        assertFalse("a missing app is never auto-installed", s.queue.any { it.id == "appb" })
        assertEquals(0, gets("appb"))
        assertNull(s.lastError)
    }

    @Test
    fun `804 a restart mid-download resumes that package from its part, with no new refresh`() {
        val apps = fleet()
        val lib2 = bodies["lib2.apk"]!!
        val half = lib2.size / 2
        // Died with half of lib2 on disk.
        killAt(StoreAuto.DOWNLOAD, "lib2") {
            // #1517 the engine's part name: <prefix>dl.apk.part
            ApkCache.file(ctx, "fleet-lib2-dl.apk.part").writeBytes(lib2.copyOfRange(0, half))
        }
        runKilled(apps)
        val dead = StoreAuto.json(ctx)
        assertEquals("the persisted phase survives the death", StoreAuto.DOWNLOAD, dead.getString("phase"))
        assertEquals("and names the package it died on", "lib2", dead.getString("current"))
        assertTrue(dead.getBoolean("resumable"))
        assertEquals(StoreAuto.DOWNLOADED, StoreAuto.load(ctx)!!.queue.first { it.id == "lib1" }.status)
        val refreshes = remoteCalls.get()
        assertEquals("nothing was installed before every download finished", 0, events.count { it.startsWith("install:") })

        val s = StoreAuto.run(ctx, apps, StoreAuto.TRIGGER_APP_START)
        assertEquals(s.summary, StoreAuto.DONE, s.phase)
        assertEquals("a resume does not ask the remote again", refreshes, remoteCalls.get())
        assertEquals("lib1 was not fetched twice", 1, gets("lib1"))
        assertEquals("lib2 resumed from its part", listOf("lib2.apk bytes=$half-"), ranges.toList())
        assertEquals("only the missing half crossed the network", (lib2.size - half).toLong(), served["lib2.apk"]!!.get())
        assertEquals(listOf("install:lib1", "install:lib2", "install:appa"), events.filter { it.startsWith("install:") })
        // Control: a chain that never died served lib1 whole, with no Range at all.
        assertEquals(bodies["lib1.apk"]!!.size.toLong(), served["lib1.apk"]!!.get())
    }

    @Test
    fun `804 a restart mid-install trusts the device - a landed install is not redone`() {
        val apps = fleet()
        killAt(StoreAuto.INSTALL, "lib1")
        runKilled(apps)
        assertEquals(StoreAuto.INSTALL, StoreAuto.json(ctx).getString("phase"))
        assertEquals(StoreAuto.INSTALLING, StoreAuto.load(ctx)!!.queue.first { it.id == "lib1" }.status)
        // The install finished while the process was gone (a shell `pm install`).
        installed(ctx, "org.example.lib1", bodies["lib1.apk"]!!)
        val getsBefore = totalGets()

        val s = StoreAuto.run(ctx, apps, StoreAuto.TRIGGER_APP_START)
        assertEquals(StoreAuto.DONE, s.phase)
        assertEquals(StoreAuto.INSTALLED, statusOf(s, "lib1"))
        assertFalse("lib1 already landed — it must not be installed again: $events", "install:lib1" in events)
        assertEquals(listOf("install:lib2", "install:appa"), events.filter { it.startsWith("install:") })
        assertEquals("a resumed install never downloads", getsBefore, totalGets())
    }

    @Test
    fun `804 control - a restart mid-install that did NOT land installs it once, from the cache`() {
        val apps = fleet()
        killAt(StoreAuto.INSTALL, "lib1")
        runKilled(apps)
        val getsBefore = totalGets()
        val s = StoreAuto.run(ctx, apps, StoreAuto.TRIGGER_APP_START)
        assertEquals(StoreAuto.INSTALLED, statusOf(s, "lib1"))
        assertEquals(1, events.count { it == "install:lib1" })
        assertEquals("installed from the cache, not the network", getsBefore, totalGets())
    }

    @Test
    fun `804 one package failing stops at its stage and is shown, the rest still install`() {
        val bad = entry("lib0", "lib", url = "")   // no source can serve it
        remotes["lib0"] = Fleet.State.UpdateAvailable("2", "0123456789ab", 1000L, source = "release")
        val apps = listOf(bad) + fleet()
        val s = StoreAuto.run(ctx, apps, StoreAuto.TRIGGER_WIFI)
        assertEquals(StoreAuto.DONE, s.phase)
        val item = s.queue.first { it.id == "lib0" }
        assertEquals(StoreAuto.FAILED, item.status)
        assertEquals("download", item.failedAt)
        assertEquals("the failure did not block the next lib", StoreAuto.INSTALLED, statusOf(s, "lib1"))
        assertEquals(StoreAuto.INSTALLED, statusOf(s, "appa"))
        val api = StoreAuto.json(ctx)
        assertTrue(api.getString("lastError"), api.getString("lastError").startsWith("lib0: failed at download"))
        val bar = StoreStages.progress()!!
        assertTrue("the bar ends on the failure: ${bar.text}", bar.failed && bar.appId == "lib0")
        // Control: a chain with nothing failing ends clean.
        UpdateProgress.reset()
        publish("lib3")
        val ok = StoreAuto.run(ctx, listOf(entry("lib3", "lib")), StoreAuto.TRIGGER_API)
        assertEquals(StoreAuto.INSTALLED, statusOf(ok, "lib3"))
        assertNull(ok.lastError)
        assertNull(StoreStages.progress())
    }

    @Test
    fun `804 with no privileged channel the install budget caps one pass, the next resumes without re-downloading`() {
        val apps = fleet()
        StoreAuto.installBudget = { 1 }
        val first = StoreAuto.run(ctx, apps, StoreAuto.TRIGGER_WIFI)
        assertEquals("downloads are not capped: all three fetched", 3, totalGets())
        assertEquals(listOf("install:lib1"), events.filter { it.startsWith("install:") })
        assertEquals("stopped at install, to be resumed", StoreAuto.INSTALL, first.phase)
        assertEquals(StoreAuto.DOWNLOADED, statusOf(first, "lib2"))
        assertTrue(first.lastError.orEmpty(), first.lastError.orEmpty().contains("wait for the next pass"))
        val refreshes = remoteCalls.get()

        StoreAuto.installBudget = { Int.MAX_VALUE }
        val next = StoreAuto.run(ctx, apps, StoreAuto.TRIGGER_PERIODIC)
        assertEquals(StoreAuto.DONE, next.phase)
        assertEquals(listOf("install:lib1", "install:lib2", "install:appa"), events.filter { it.startsWith("install:") })
        assertEquals("nothing downloaded twice", 3, totalGets())
        assertEquals("a resume does not refresh", refreshes, remoteCalls.get())
    }

    // ── triggers ─────────────────────────────────────────────────────────────

    @Test
    fun `804 Wi-Fi trigger fires on an unmetered network appearing, and only then`() {
        assertTrue("metered → Wi-Fi", StoreAuto.wifiEdge(false, true))
        assertFalse("still on Wi-Fi", StoreAuto.wifiEdge(true, true))
        assertFalse("first reading of a process (app start is its own trigger)", StoreAuto.wifiEdge(null, true))
        assertFalse("onto mobile data", StoreAuto.wifiEdge(true, false))
        assertFalse(StoreAuto.wifiEdge(false, false))
    }

    @Test
    fun `804 both workers' pass IS the chain - the Constellation one hands the host over, UpdateWorker's keeps it`() {
        val apps = fleet()
        StoreAuto.attach(ctx) { _, _ -> }
        val pass = Fleet.autoPass(ctx, apps, owner = "Fleet/Worker:wifi")
        assertTrue(pass.reason, pass.reason.startsWith("auto chain (wifi)"))
        assertEquals(3, pass.acted)
        assertEquals(1, events.count { it == "self" })
        // A second automatic trigger minutes later IS answered by that chain.
        val calls = remoteCalls.get()
        Fleet.autoPass(ctx, apps, owner = StoreAuto.UPDATER_OWNER)
        assertEquals("no second refresh inside the fresh window", calls, remoteCalls.get())
        // A Store refresh always runs a fresh one.
        StoreAuto.run(ctx, apps, StoreAuto.TRIGGER_STORE_REFRESH)
        assertTrue(remoteCalls.get() > calls)
        // UpdateWorker updates the host itself right after: the chain must not hand it over too.
        ctx.getSharedPreferences("store_auto", Context.MODE_PRIVATE).edit().clear().commit()
        events.clear()
        val own = Fleet.autoPass(ctx, apps, owner = StoreAuto.UPDATER_OWNER)
        assertFalse("UpdateWorker's own pass does not kick the self-updater: $events", "self" in events)
        assertTrue(own.reason, own.reason.startsWith("auto chain (periodic)"))
        assertEquals(StoreAuto.HANDED, StoreAuto.load(ctx)!!.queue.first { it.id == "host" }.status)
    }

    // ── the Store bar and /api/store/auto ────────────────────────────────────

    private val lines = Collections.synchronizedList(ArrayList<String>())
    private val watch: (UpdateProgress.State) -> Unit = { st -> StoreStages.progress(st)?.let { lines += it.text } }

    @Test
    fun `804 the Store bar names the phase and the package while the chain runs`() {
        val apps = fleet()
        UpdateProgress.addObserver(watch); lines.clear()
        StoreAuto.run(ctx, apps, StoreAuto.TRIGGER_WIFI)
        val seen = lines.toList()
        assertTrue("no checking line: $seen", seen.any { it.startsWith("Auto ▸ 1/4 checking") })
        assertTrue("no download line naming Lib1 as 1 of 3: $seen",
            seen.any { it.startsWith("Auto ▸ 2/4 download all") && "Lib1" in it && "1 of 3" in it && "next: Lib2" in it })
        assertTrue("no install line naming Appa: $seen",
            seen.any { it.startsWith("Auto ▸ 3/4 install") && "Appa" in it && "installing" in it })
        // Control: the phase belongs to the running chain only.
        assertNull("a finished clean chain leaves the bar empty", StoreStages.progress())
        assertNull(StoreAuto.label())
    }

    @Test
    fun `804 the API reads the persisted chain - phase, queue, current package, last error`() {
        assertEquals("idle before any chain", StoreAuto.IDLE, StoreAuto.json(ctx).getString("phase"))
        val apps = fleet()
        killAt(StoreAuto.INSTALL, "lib2")
        runKilled(apps)
        val j = StoreAuto.json(ctx)
        assertEquals(StoreAuto.INSTALL, j.getString("phase"))
        assertEquals("lib2", j.getString("current"))
        assertFalse(j.getBoolean("running"))
        val q = j.getJSONArray("queue")
        val ids = (0 until q.length()).map { q.getJSONObject(it).getString("id") }
        assertEquals("the queue in install order", listOf("lib1", "lib2", "appa", "host"), ids)
        assertEquals(StoreAuto.INSTALLED, q.getJSONObject(0).getString("result"))
        assertEquals(StoreAuto.INSTALLING, q.getJSONObject(1).getString("result"))
        assertFalse("no secret or URL in the answer", j.toString().contains("http"))
    }

    // ── #812 real free storage, never evicting what is pending ───────────────

    private fun resetRoom() {
        StoreStages.room = { c -> ApkCache.room(c) }
        ApkCache.freeBytes = { c -> runCatching { android.os.StatFs(ApkCache.dir(c).path).availableBytes }.getOrElse { ApkCache.dir(c).usableSpace } }
        ApkCache.boundFor = { c -> ApkCache.boundOf(ApkCache.freeBytes(c), ApkCache.totalBytes(c)) }
        ApkCache.isLanded = { c, e -> e.record != null && ApkCache.landed(c, e, e.record!!.pkg) }
        StoreAuto.onPending = { _, _ -> }
    }

    private fun cached(name: String, pkg: String, code: Long, bytes: Int, age: Long): java.io.File {
        val f = ApkCache.file(ctx, name).apply { writeBytes(ByteArray(bytes) { 7 }); setLastModified(age) }
        java.io.File(f.parentFile, f.name + ".record").writeText("$pkg\n$code\n${hex(f.readBytes())}\n")
        return f
    }

    @Test
    fun `812 space check reads REAL free storage - 40 GB free and a full cache is not no room`() {
        val gb = 1_000_000_000L
        // The owner's S21+: ~40 GB free, the cache already past the old fixed 1073 MB.
        val room = ApkCache.roomOf(free = 40 * gb, cached = 1_100_000_000L)
        assertTrue("40 GB free must leave room, got $room", room > 5 * gb)
        assertTrue("the bound is derived, not the old 1073 MB",
            ApkCache.boundOf(40 * gb, 1_100_000_000L) > 1_073_741_824L)
        // Control: a genuinely full device has no room, whatever the bound.
        assertEquals(0L, ApkCache.roomOf(free = 1 * gb, cached = 0L))
        assertEquals("the bound never drops under its declared min", 1_073_741_824L, ApkCache.boundOf(0L, 0L))
        // And the live seam is the StatFs reading, not the cache bound.
        ApkCache.freeBytes = { 40 * gb }
        try {
            assertTrue(StoreStages.room(ctx) > 5 * gb)
            assertTrue(StoreAuto.roomJson(ctx).getString("text").contains("40000 MB free"))
        } finally { resetRoom() }
    }

    @Test
    fun `812 a cache over its bound never evicts an APK pending install - only landed or superseded`() {
        val pendA = cached("fleet-calc-release.apk", "org.example.calc", 5, 40_000, 1_000)
        val pendB = cached("fleet-rootfs-release.apk", "org.example.rootfs", 3, 60_000, 2_000)
        val old = cached("fleet-notes-old.apk", "org.example.notes", 1, 30_000, 500)
        val newer = cached("fleet-notes-release.apk", "org.example.notes", 2, 30_000, 3_000)
        val done = cached("fleet-news-release.apk", "org.example.news", 4, 30_000, 100)
        val part = ApkCache.file(ctx, "fleet-camera-release.apk.part").apply { writeBytes(ByteArray(20_000)); setLastModified(50) }
        ApkCache.boundFor = { 10_000L }      // pending total alone is far over the bound
        ApkCache.isLanded = { _, e -> e.record?.pkg == "org.example.news" }
        try {
            val ev = ApkCache.evict(ctx)
            assertTrue("pending calc kept", pendA.exists())
            assertTrue("pending rootfs-sized lib kept", pendB.exists())
            assertTrue("the newest notes build is pending: kept", newer.exists())
            assertTrue("a resumable partial is never evicted", part.exists())
            assertFalse("superseded build evicted", old.exists())
            assertFalse("landed build evicted", done.exists())
            assertEquals(setOf(old.name, done.name), ev.deleted.toSet())
            assertTrue(ev.unverified.isEmpty())
        } finally { resetRoom() }
    }

    @Test
    fun `812 pending total over the room - download, install, clear in rounds, each fetched once`() {
        val apps = fleet()
        val one = bodies["lib1.apk"]!!.size.toLong()
        // Room for ONE package at a time (rootfs-sized, relatively).
        StoreStages.room = { c -> (one * 3 / 2 - ApkCache.totalBytes(c)).coerceAtLeast(0) }
        val told = ArrayList<Int>()
        StoreAuto.onPending = { _, n -> told += n }
        try {
            val s = StoreAuto.run(ctx, apps, StoreAuto.TRIGGER_WIFI)
            assertEquals(s.summary, StoreAuto.DONE, s.phase)
            for (id in listOf("lib1", "lib2", "appa")) {
                assertEquals(id, StoreAuto.INSTALLED, statusOf(s, id))
                assertEquals("$id fetched exactly once — nothing evicted and re-fetched", 1, gets(id))
            }
            assertEquals("one at a time when only one fits",
                listOf("get:lib1", "install:lib1", "get:lib2", "install:lib2", "get:appa", "install:appa"),
                events.filter { it.startsWith("get:") || it.startsWith("install:") })
            assertEquals("the badge is told nothing is left", listOf(0), told)
        } finally { resetRoom() }
    }

    @Test
    fun `812 genuinely no room says so with the real numbers, no thrash, badge keeps the count`() {
        val apps = fleet()
        StoreStages.room = { 0L }
        val told = ArrayList<Int>()
        StoreAuto.onPending = { _, n -> told += n }
        try {
            val s = StoreAuto.run(ctx, apps, StoreAuto.TRIGGER_WIFI)
            assertEquals(StoreAuto.DONE, s.phase)
            assertEquals("nothing fetched", 0, totalGets())
            for (id in listOf("lib1", "lib2", "appa")) {
                val i = s.queue.first { it.id == id }
                assertEquals(StoreAuto.FAILED, i.status)
                assertTrue(i.error.orEmpty(), i.error.orEmpty().contains("usable") && i.error.orEmpty().contains("reserve"))
            }
            val j = StoreAuto.json(ctx)
            assertTrue(j.getString("lastError").contains("usable"))
            assertTrue(j.getJSONObject("room").has("freeBytes"))
            assertEquals(3, j.getInt("pending"))
            assertEquals(listOf(3), told)
        } finally { resetRoom() }
    }

    @Test
    fun `812 Cancel stops the batch with the state persisted, and the next trigger resumes it`() {
        val apps = fleet()
        // Cancel lands DURING lib2's transfer (a download start re-arms the
        // flag, so a cancel before it is not the user's tap mid-batch).
        cancelOn = "lib2"
        val first = StoreAuto.run(ctx, apps, StoreAuto.TRIGGER_WIFI)
        cancelOn = null
        assertTrue(first.lastError.orEmpty(), first.lastError.orEmpty().contains("cancelled"))
        assertEquals("nothing installed after a Cancel", 0, events.count { it.startsWith("install:") })
        assertEquals(StoreAuto.DOWNLOAD, StoreAuto.load(ctx)!!.phase)
        val s = StoreAuto.run(ctx, apps, StoreAuto.TRIGGER_APP_START)
        assertEquals(s.summary, StoreAuto.DONE, s.phase)
        assertEquals(listOf("install:lib1", "install:lib2", "install:appa"), events.filter { it.startsWith("install:") })
        assertEquals("lib1 not fetched again after the resume", 1, gets("lib1"))
    }
}
