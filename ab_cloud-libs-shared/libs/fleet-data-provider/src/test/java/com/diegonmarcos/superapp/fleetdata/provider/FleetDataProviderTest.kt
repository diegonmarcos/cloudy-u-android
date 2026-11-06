package com.diegonmarcos.superapp.fleetdata.provider

import com.diegonmarcos.superapp.fleetdata.DataSource
import com.diegonmarcos.superapp.fleetdata.FleetDataReader
import com.diegonmarcos.superapp.fleetdata.LastKnownGood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileInputStream

class FleetDataProviderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val SELF = "cloud.store"
    private val TOOLS = "com.diegonmarcos.cloudtoolssmallllms"
    private val KB = "com.diegonmarcos.cloudkeyboard"
    private val manifest = """{"schemaVersion":1,"shared":{"constellation-fleet":{"tier":3,"consumers":["*"]},"keyboard-layouts":{"tier":4,"consumers":["$KB"]}},
        |"apps":{"$TOOLS":["tools-models"]}}""".trimMargin()

    private fun fixture(): Pair<FleetDataStore, FleetDataServer> {
        val store = FleetDataStore(tmp.newFolder())
        val server = FleetDataServer(store, AccessLog(tmp.newFile()), SELF, now = { 1L })
        store.replace(Route.Shared("manifest"), manifest.toByteArray(), null, 1, 1)
        store.replace(Route.Shared("constellation-fleet"), """{"schemaVersion":1}""".toByteArray(), "e1", 1, 1)
        store.replace(Route.Shared("keyboard-layouts"), """{"schemaVersion":1}""".toByteArray(), "e1", 1, 1)
        store.replace(Route.App(TOOLS, "tools-models"), """{"schemaVersion":1,"m":1}""".toByteArray(), "e1", 1, 1)
        return store to server
    }
    private fun seg(p: String) = p.split('/')

    @Test fun ownAppDataIsServedToTheOwnerOnly() {
        val (_, s) = fixture()
        assertTrue(s.open(TOOLS, seg("app/$TOOLS/tools-models")) is FleetDataServer.Outcome.Served)
        assertTrue(s.open(KB, seg("app/$TOOLS/tools-models")) is FleetDataServer.Outcome.Denied)
        assertTrue(s.open(null, seg("app/$TOOLS/tools-models")) is FleetDataServer.Outcome.Denied)
    }

    @Test fun sharedDataIsGrantedThroughTheManifest() {
        val (_, s) = fixture()
        assertTrue(s.open(TOOLS, seg("shared/constellation-fleet")) is FleetDataServer.Outcome.Served)   // "*"
        assertTrue(s.open(KB, seg("shared/keyboard-layouts")) is FleetDataServer.Outcome.Served)          // named
        assertTrue(s.open(TOOLS, seg("shared/keyboard-layouts")) is FleetDataServer.Outcome.Denied)       // not named
        assertTrue(s.open(TOOLS, seg("shared/manifest")) is FleetDataServer.Outcome.Denied)               // not in the manifest
        assertTrue(s.open(TOOLS, seg("shared/not-listed")) is FleetDataServer.Outcome.Denied)
        assertTrue(s.open(SELF, seg("shared/keyboard-layouts")) is FleetDataServer.Outcome.Served)        // the provider's own UI
    }

    @Test fun badRoutesAreMissingAndTraversalIsRefused() {
        val (_, s) = fixture()
        assertEquals(FleetDataServer.Outcome.Missing, s.open(TOOLS, seg("shared/../manifest")))
        assertEquals(FleetDataServer.Outcome.Missing, s.open(TOOLS, seg("nope/x")))
        assertEquals(FleetDataServer.Outcome.Missing, s.open(TOOLS, seg("app/not a pkg/x")))
    }

    @Test fun indexListsOnlyWhatTheCallerMayRead() {
        val (_, s) = fixture()
        val idx = s.index(TOOLS)
        assertTrue(idx.contains("shared/constellation-fleet")); assertTrue(idx.contains("app/$TOOLS/tools-models"))
        assertFalse(idx.contains("keyboard-layouts")); assertFalse(idx.contains("manifest"))
        assertTrue((s.open(KB, seg("index")) as FleetDataServer.Outcome.Bytes).bytes.decodeToString().contains("keyboard-layouts"))
    }

    @Test fun everyRequestIsLogged() {
        val (_, s) = fixture()
        s.open(TOOLS, seg("shared/keyboard-layouts")); s.open(TOOLS, seg("shared/constellation-fleet"))
        val log = AccessLog(tmp.newFile()); log.add("a", "p", true, 5); log.add("b", "q", false, 6)
        assertEquals(listOf("b", "a"), log.rows().map { it.caller })
    }

    @Test fun streamsAFileOverOneMegabyteThroughTheClientReader() {
        val (store, s) = fixture()
        val big = """{"schemaVersion":1,"pad":"${"y".repeat(3 * 1024 * 1024)}","n":9}"""
        store.replace(Route.Shared("constellation-fleet"), big.toByteArray(), "e2", 1, 2)
        val src = DataSource { _, path ->
            (s.open(TOOLS, path.split('/')) as? FleetDataServer.Outcome.Served)?.let { FileInputStream(it.file) }
        }
        val lkg = object : LastKnownGood { override fun load(key: String) = null; override fun store(key: String, bytes: ByteArray) {} }
        val n = FleetDataReader(src, lkg).get("constellation-fleet") { Regex("\"n\":(\\d)").find(it)!!.groupValues[1].toInt() }
        assertEquals(9, n)
    }

    // ── sync ────────────────────────────────────────────────────────────────

    private class Web(val files: MutableMap<String, Pair<String, String>>, val calls: MutableList<String> = mutableListOf()) : Fetcher {
        override fun get(url: String, etag: String?, headers: Map<String, String>): Reply {
            calls += "$url|$etag"
            val (body, tag) = files[url] ?: return Reply(404, null, null)
            return if (etag == tag) Reply(304, null, tag) else Reply(200, body.toByteArray(), tag)
        }
    }
    private val BASE = "https://x/"
    private fun sync(store: FleetDataStore, web: Web, clock: () -> Long, changed: MutableList<String> = mutableListOf()) =
        FleetDataSync(store, PublicRepoSource(BASE), web, now = clock, changed = { changed += it })

    private fun web() = Web(mutableMapOf(
        BASE + "data/fleet/manifest.json" to (manifest to "m1"),
        BASE + "data/fleet/constellation-fleet.json" to ("""{"schemaVersion":1,"v":1}""" to "c1"),
        BASE + "data/fleet/keyboard-layouts.json" to ("""{"schemaVersion":1}""" to "k1"),
        BASE + "data/fleet/app/$TOOLS/tools-models.json" to ("""{"schemaVersion":1}""" to "t1"),
    ))

    @Test fun syncFetchesThenUsesETagAndNotifiesPerUri() {
        val store = FleetDataStore(tmp.newFolder()); val w = web(); var t = 0L; val changed = mutableListOf<String>()
        val s = sync(store, w, { t }, changed)
        val r1 = s.run()
        assertEquals(4, r1.updated.size); assertTrue(r1.failed.isEmpty())
        assertTrue(changed.containsAll(listOf("shared/constellation-fleet", "app/$TOOLS/tools-models", "shared/manifest")))
        assertEquals("c1", store.meta(Route.Shared("constellation-fleet"))?.etag)
        t = 31_000; w.calls.clear(); changed.clear()
        val r2 = s.run()
        assertTrue(r2.updated.isEmpty()); assertEquals(4, r2.notModified.size); assertTrue(changed.isEmpty())
        assertTrue(w.calls.any { it == BASE + "data/fleet/constellation-fleet.json|c1" })
    }

    @Test fun aNewVersionReplacesAndKeepsThePreviousForTheDiff() {
        val store = FleetDataStore(tmp.newFolder()); val w = web(); var t = 0L
        val s = sync(store, w, { t })
        s.run()
        w.files[BASE + "data/fleet/constellation-fleet.json"] = """{"schemaVersion":1,"v":2}""" to "c2"
        t = 60_000
        assertEquals(listOf("constellation-fleet"), s.run().updated)
        val r = Route.Shared("constellation-fleet")
        assertTrue(String(store.read(r)!!).contains("\"v\":2")); assertTrue(String(store.previous(r)!!).contains("\"v\":1"))
        val d = DatasetDiff.diff(String(store.previous(r)!!), String(store.read(r)!!))
        assertEquals(1, d.size); assertEquals(DatasetDiff.Kind.CHANGED, d[0].kind); assertEquals("$.v", d[0].path)
    }

    @Test fun invalidOrNewerDataNeverReplacesTheLastKnownGood() {
        val store = FleetDataStore(tmp.newFolder()); val w = web(); var t = 0L
        val s = sync(store, w, { t })
        s.run()
        val r = Route.Shared("constellation-fleet")
        w.files[BASE + "data/fleet/constellation-fleet.json"] = "{ not json" to "c2"; t = 40_000
        assertEquals("not JSON", s.run().failed["constellation-fleet"])
        w.files[BASE + "data/fleet/constellation-fleet.json"] = """{"schemaVersion":9}""" to "c3"; t = 80_000
        assertTrue(s.run().failed["constellation-fleet"]!!.contains("newer"))
        w.files[BASE + "data/fleet/constellation-fleet.json"] = """{"v":3}""" to "c4"; t = 120_000
        assertEquals("no schemaVersion", s.run().failed["constellation-fleet"])
        assertTrue(String(store.read(r)!!).contains("\"v\":1")); assertEquals("c1", store.meta(r)?.etag)
    }

    @Test fun aRunInsideTheRetryFloorIsRefused() {
        val store = FleetDataStore(tmp.newFolder()); val w = web(); var t = 1_000L
        val s = sync(store, w, { t })
        assertFalse(s.run().throttled)
        t = 20_000; w.calls.clear()
        assertTrue(s.run().throttled); assertTrue(w.calls.isEmpty())
        t = 31_001
        assertFalse(s.run().throttled)
    }

    @Test fun thePublicSourceNeverFetchesPersonalOrFleetTiers() {
        val store = FleetDataStore(tmp.newFolder()); val w = web()
        w.files[BASE + "data/fleet/manifest.json"] = """{"schemaVersion":1,"shared":{"personal/wallet":{"tier":1,"consumers":["*"]},"fleet/probes":{"tier":2,"consumers":["*"]}}}""" to "m2"
        val r = sync(store, w, { 0 }).run()
        assertEquals("tier 1 not served here", r.failed["personal/wallet"]); assertEquals("tier 2 not served here", r.failed["fleet/probes"])
        assertTrue(w.calls.none { it.contains("personal") || it.contains("probes") })
    }

    @Test fun aFailingDatasetDoesNotStopTheOthers() {
        val store = FleetDataStore(tmp.newFolder()); val w = web()
        w.files.remove(BASE + "data/fleet/keyboard-layouts.json")
        val r = sync(store, w, { 0 }).run()
        assertEquals("HTTP 404", r.failed["keyboard-layouts"]); assertEquals(3, r.updated.size)
    }

    @Test fun theApiSourceNeedsABearerAndUsesTheTierRoute() {
        val none = FleetApiSource({ "https://api/c3" }, { null })
        assertNull(none.manifest())
        val src = FleetApiSource({ "https://api/c3/" }, { "tok" })
        assertEquals("https://api/c3/fleet-data/manifest", src.manifest()!!.url)
        assertEquals("Bearer tok", src.manifest()!!.headers["Authorization"])
        assertEquals("https://api/c3/fleet-data/2/fleet/probes", src.dataset("fleet/probes", null, ManifestEntry(2, listOf("*"), null))!!.url)
        assertEquals(setOf(1, 2), src.tiers)
    }

    @Test fun personalDatasetsAreEncryptedAtRestAndServedFromMemory() {
        val xor = object : FleetDataStore.Codec {
            override fun encode(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
            override fun decode(stored: ByteArray) = encode(stored)
        }
        val store = FleetDataStore(tmp.newFolder(), xor)
        val r = Route.Shared("personal/wallet")
        val plain = """{"schemaVersion":1,"name":"visible"}"""
        store.replace(r, plain.toByteArray(), "e", 1, 1)
        assertFalse(store.file(r)!!.readText().contains("visible"))
        assertEquals(plain, String(store.read(r)!!))
        store.replace(Route.Shared("manifest"), """{"shared":{"personal/wallet":{"tier":1,"consumers":["$TOOLS"]}}}""".toByteArray(), null, 1, 1)
        val s = FleetDataServer(store, AccessLog(tmp.newFile()), SELF)
        val o = s.open(TOOLS, seg("shared/personal/wallet"))
        assertEquals(plain, (o as FleetDataServer.Outcome.Bytes).bytes.decodeToString())
    }
}
