package com.diegonmarcos.superapp.apps

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.appstore.AppsMesh
import com.diegonmarcos.superapp.appstore.AppsMesh.GapKind
import com.diegonmarcos.superapp.appstore.StoreMesh
import com.diegonmarcos.superapp.kdeconnect.KdeConnectConfig
import com.diegonmarcos.superapp.kdeconnect.KdeMesh
import com.diegonmarcos.superapp.kdeconnect.KdePeers
import com.diegonmarcos.superapp.updater.Fleet
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #733 Apps Mesh: the missing-membership detector, the member actions and the
 * export, on the fleet manifest this build baked and the page declaration it
 * ships (assets/appstore-controls.json, merged into this app). Plus Peer
 * Control's peer list.
 *
 * Every gap is asserted as a PAIR: the defect planted on one installed app
 * must produce exactly that gap, and the same phone with the defect removed
 * must produce none for that app. No member or package is named here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppsMeshTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val b64 = com.diegonmarcos.superapp.appstore.BuildConfig.CONSTELLATION_FLEET_B64
    private val fleetJson = JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))
    private val fleet = Fleet.parse(b64)
    private val links = StoreMesh.links(fleetJson)
    private val seeded = ServedFixtures.seed(ctx, "appstore-controls")   // #1562 served dataset, read as production does
    private val decl = AppsMesh.load(ctx)
    private val apps = fleet.filter { it.kind == "app" }
    private val everyone = fleet.map { it.id }

    /** A phone where every member is installed, a member, reachable, granted,
     *  sees every peer and every engine answers its contract: a full mesh. */
    private fun healthy(
        installed: Collection<String> = everyone,
        peers: Set<String> = everyone.toSet(),
        reachable: Set<String> = everyone.toSet(),
        granted: Set<String> = everyone.toSet(),
        views: Map<String, Set<String>> = everyone.associateWith { everyone.toSet() },
        contracts: Map<String, Int> = links.groupBy { it.key }.mapValues { (_, l) -> l.maxOf { it.minContract } },
    ) = StoreMesh.Live(installed.associateWith { "1.0" }, reachable.associateWith { 38090 }, peers,
        contracts, emptyMap(), granted, views)

    private fun kinds(live: StoreMesh.Live, id: String) =
        AppsMesh.gaps(decl, fleet, links, live).filter { it.app.id == id }.map { it.kind }

    @Test
    fun `the page declaration ships with the four member actions and every gap's words`() {
        val ids = decl.actions.map { it.id }
        for (a in listOf("api", "start", "stop", "open", "details"))
            assertTrue("action $a is not declared in appstore-controls.json::apps_mesh.controls (scope member): $ids", a in ids)
        for (k in GapKind.values()) {
            val w = decl.gapWords[k.name]
            assertTrue("gap ${k.name} has no label/fix declared", w != null && w.first.isNotBlank() && w.second.isNotBlank())
        }
        // the Store-row action exists only where a Store row does
        assertTrue(AppsMesh.actionsFor(decl, hasStore = true).any { it.id == "store" })
        assertFalse(AppsMesh.actionsFor(decl, hasStore = false).any { it.id == "store" })
        // #793 Wake · Open · Docs lead, as the owner asked, then Stop and Details
        assertEquals(listOf("start", "open", "api", "stop", "details"),
            AppsMesh.actionsFor(decl, hasStore = false).map { it.id })
        assertEquals(listOf("Wake", "Open", "Docs"), decl.actions.take(3).map { it.label })
        assertTrue("a member action has no declared colour", decl.actions.filter { it.type == "action" }.all { it.color != null })
    }

    @Test
    fun `809 every control is declared with a type, each one the page implements, and the order is the declared one`() {
        assertEquals(AppsMesh.FILTERS, decl.filters.map { it.id })
        assertTrue("a control has no known type", decl.controls.all { it.type in AppsMesh.TYPES })
        assertTrue("a control's id is not handled on its page",
            decl.controls.all { it.id in AppsMesh.HANDLED[it.scope].orEmpty() })
        fun ids(scope: String, type: String) = decl.of(scope, type).map { it.id }
        assertEquals(listOf("endpoints", "gaps"), ids("root", "page"))
        assertEquals(listOf("reprobe", "wake"), ids("root", "action"))
        assertEquals(listOf("json", "markdown", "copy"), ids("endpoints", "action"))
        assertEquals(listOf("export", "copy"), ids("gaps", "action"))
        assertEquals("Export is on the page it acts on, not on root", emptyList<String>(),
            ids("root", "action").filter { it in listOf("json", "markdown", "export", "copy") })
        for (s in decl.controls.map { it.scope }.distinct())
            assertEquals("a control is declared twice on $s", decl.of(s).size, decl.of(s).map { it.id }.toSet().size)
    }

    @Test
    fun `809 Details is a page with its own Copy, and the debug API lists exactly the drawn controls`() {
        assertEquals("page", decl.of("member").first { it.id == "details" }.type)
        assertEquals(listOf("copy"), decl.of("details", "action").map { it.id })
        val j = AppsMesh.controlsJson(decl)
        val arr = j.getJSONArray("controls")
        val listed = (0 until arr.length()).map { arr.getJSONObject(it) }
        assertEquals("the API lists a control the page does not draw, or misses one",
            decl.controls.map { "${it.scope}:${it.id}:${it.type}" },
            listed.map { "${it.getString("scope")}:${it.getString("id")}:${it.getString("type")}" })
        assertEquals(decl.controls.size, j.getInt("count"))
        assertEquals(AppsMesh.TYPES, (0 until j.getJSONArray("types").length()).map { j.getJSONArray("types").getString(it) })
        assertTrue(listed.any { it.getString("scope") == "member" && it.getString("id") == "details" && it.getString("type") == "page" })
        // mutation: a control dropped by the filter (a typo) is not listed either
        val typo = AppsMesh.decl(JSONObject().put("apps_mesh", JSONObject().put("controls", org.json.JSONArray()
            .put(JSONObject().put("scope", "details").put("id", "copy").put("type", "action"))
            .put(JSONObject().put("scope", "details").put("id", "cpy").put("type", "action"))
            .put(JSONObject().put("scope", "details").put("id", "copy").put("type", "chip")))))
        assertEquals(1, AppsMesh.controlsJson(typo).getInt("count"))
    }

    @Test
    fun `809 a typo in type or id is dropped, not drawn as a dead control`() {
        val bad = AppsMesh.decl(JSONObject().put("apps_mesh", JSONObject().put("controls", org.json.JSONArray()
            .put(JSONObject().put("scope", "root").put("id", "reprobe").put("type", "button"))
            .put(JSONObject().put("scope", "root").put("id", "reprob").put("type", "action"))
            .put(JSONObject().put("scope", "root").put("id", "json").put("type", "action"))
            .put(JSONObject().put("scope", "root").put("id", "wake").put("type", "filter"))
            .put(JSONObject().put("scope", "root").put("id", "wake").put("type", "action")))))
        assertEquals(listOf("wake"), bad.controls.map { it.id })
    }

    @Test
    fun `793 each filter keeps exactly what it names, on the same phone, and counts it`() {
        val ids = everyone.toSet()
        val app = apps.first().id
        // the defect for each chip, planted on one member, with the healthy control
        val stopped = healthy(reachable = ids - app, views = (ids - app).associateWith { ids })
        fun keeps(f: String, live: StoreMesh.Live) = fleet.filter { AppsMesh.matches(f, it, links, live) }.map { it.id }.toSet()
        assertFalse("a stopped member is still Running", app in keeps("running", stopped))
        assertTrue("a stopped member is not under Not running", app in keeps("stopped", stopped))
        assertFalse("a stopped member is still Reachable", app in keeps("reachable", stopped))
        assertTrue(app in keeps("running", healthy()) && app !in keeps("stopped", healthy()))
        // reachable needs the authenticated API: answering ping alone is running, not reachable
        val noAuth = healthy(views = ids.associateWith { ids } - app)
        assertTrue(app in keeps("running", noAuth) && app !in keeps("reachable", noAuth))
        // a member that is not installed is neither running nor stopped
        val gone = healthy(installed = everyone - app, reachable = ids - app, peers = ids - app)
        assertFalse(app in keeps("stopped", gone) || app in keeps("running", gone))
        // engine links: exactly the members a declared link touches, known before any probe
        val touched = links.flatMap { listOf(it.from, it.engine) }.toSet()
        assertEquals(touched, fleet.filter { AppsMesh.matches("engine", it, links, null) }.map { it.id }.toSet())
        assertEquals("nothing is running before a probe", 0, AppsMesh.counts(fleet, links, null).getValue("running"))
        val n = AppsMesh.counts(fleet, links, stopped)
        assertEquals(fleet.size, n.getValue("all"))
        assertEquals(1, n.getValue("stopped"))
        assertEquals(ids.size - 1, n.getValue("running"))
    }

    @Test
    fun `793 a member only looked at and not woken is not running, not a gap, until a wake fails`() {
        val id = apps.first().id
        val ids = everyone.toSet()
        val asleep = healthy(reachable = ids - id).let { StoreMesh.Live(it.installed, it.reachable, it.peers,
            it.contracts, it.shares, it.granted, it.peerViews, asleep = setOf(id)) }
        assertFalse(kinds(asleep, id).contains(GapKind.NO_DEBUG_API))
        assertTrue(AppsMesh.report(decl, fleet, links, asleep).contains("not running (not woken)"))
        // control: the same member after a wake that did not bring it up IS the gap
        assertEquals(listOf(GapKind.NO_DEBUG_API), kinds(healthy(reachable = ids - id), id))
    }

    @Test
    fun `793 a pending member shows what the cache said, a landed one what the probe found`() {
        val a = apps[0].id; val b = apps[1].id
        val ids = everyone.toSet()
        val cached = healthy()
        val fresh = healthy(reachable = ids - a - b, views = (ids - a - b).associateWith { ids })
        val shown = AppsMesh.overlay(fresh, cached, pending = setOf(a))
        assertTrue("the pending member lost its cached answer", a in shown.reachable && a in shown.peerViews)
        assertFalse("the landed member kept a stale cached answer", b in shown.reachable || b in shown.peerViews)
        assertEquals(fresh.reachable.keys, AppsMesh.overlay(fresh, cached, emptySet()).reachable.keys)
    }

    @Test
    fun `793 the cache round-trips every field, so the next open draws exactly the last answer`() {
        val l = StoreMesh.Live(mapOf("x" to "1.2"), mapOf("x" to 38140), setOf("x"), mapOf("e|a" to 2),
            mapOf("x" to listOf("content://x.fleet")), setOf("x"), mapOf("x" to setOf("x", "y")),
            setOf("x"), mapOf("x" to "{\"endpoints\":[]}"), setOf("y"))
        AppsMesh.writeCache(ctx, AppsMesh.Cached(1234L, l, mapOf("x" to 99L)))
        val c = AppsMesh.readCache(ctx)!!
        assertEquals(1234L, c.at); assertEquals(mapOf("x" to 99L), c.docsAt)
        val r = c.live
        assertEquals(l.installed, r.installed); assertEquals(l.reachable, r.reachable); assertEquals(l.peers, r.peers)
        assertEquals(l.contracts, r.contracts); assertEquals(l.shares, r.shares); assertEquals(l.granted, r.granted)
        assertEquals(l.peerViews, r.peerViews); assertEquals(l.woken, r.woken); assertEquals(l.docs, r.docs)
        assertEquals(l.asleep, r.asleep)
        assertEquals("3 min ago", AppsMesh.age(0L, 180_000L))
    }

    @Test
    fun `793 the endpoints catalogue filters like the page's chips and reports their counts`() {
        val id = apps.first().id
        val ids = everyone.toSet()
        val live = healthy(reachable = ids - id)
        val all = AppsMesh.catalogue(fleet, live, "", emptyList(), links)
        val stopped = AppsMesh.catalogue(fleet, live, "", emptyList(), links, "stopped")
        assertEquals(fleet.size, all.getJSONArray("members").length())
        assertEquals(1, stopped.getJSONArray("members").length())
        assertEquals(id, stopped.getJSONArray("members").getJSONObject(0).getString("id"))
        assertFalse(stopped.getJSONArray("members").getJSONObject(0).getBoolean("running"))
        assertEquals(1, stopped.getJSONObject("counts").getInt("stopped"))
        assertEquals("stopped", stopped.getString("filter"))
        val md = AppsMesh.markdown(fleet, live)
        assertFalse("a member that is not running has a section", md.contains("(${id})"))
        assertTrue(md.contains("http://127.0.0.1:38090"))
    }

    @Test
    fun `a healthy full mesh reports no gap at all`() {
        assertTrue("the baked manifest has no apps", apps.size > 1)
        assertEquals(emptyList<GapKind>(), AppsMesh.gaps(decl, fleet, links, healthy()).map { it.kind })
    }

    @Test
    fun `each membership defect is reported on exactly that app, with its fix, and only while it holds`() {
        for (app in apps) {
            val id = app.id
            val noProvider = kinds(healthy(peers = everyone.toSet() - id, reachable = everyone.toSet() - id), id)
            assertEquals("$id without a provider", listOf(GapKind.NO_PROVIDER), noProvider)
            assertEquals("$id asleep-for-good", listOf(GapKind.NO_DEBUG_API), kinds(healthy(reachable = everyone.toSet() - id), id))
            assertEquals("$id without the grant", listOf(GapKind.NO_PERMISSION), kinds(healthy(granted = everyone.toSet() - id), id))
            val other = everyone.first { it != id }
            val blind = AppsMesh.gaps(decl, fleet, links, healthy(views = everyone.associateWith {
                if (it == id) everyone.toSet() - other else everyone.toSet() })).filter { it.app.id == id }
            assertEquals("$id that cannot see $other", listOf(GapKind.PEER_BLIND), blind.map { it.kind })
            val otherLabel = fleet.first { it.id == other }.label
            assertTrue("PEER_BLIND does not name the member it cannot see: ${blind.single().label}",
                blind.single().label.contains(otherLabel))
            // the control: the same app on the healthy phone has nothing to fix
            assertEquals("$id healthy", emptyList<GapKind>(), kinds(healthy(), id))
            // an app that is not on this phone is never reported
            assertEquals("$id absent", emptyList<GapKind>(), kinds(healthy(installed = everyone - id, peers = everyone.toSet() - id,
                reachable = everyone.toSet() - id, granted = everyone.toSet() - id), id))
        }
    }

    @Test
    fun `a broken engine link is a gap on the app that binds it, with the Store fix`() {
        assertTrue("no engine links baked — nothing below would verify anything", links.isNotEmpty())
        for (l in links) {
            val gaps = AppsMesh.gaps(decl, fleet, links, healthy(installed = everyone - l.engine))
                .filter { it.app.id == l.from && it.kind == GapKind.ENGINE_BROKEN }
            if (fleet.first { it.id == l.from }.kind != "app") continue
            assertTrue("${l.from} -> ${l.engine} missing is not an ENGINE_BROKEN gap", gaps.isNotEmpty())
            assertTrue("the gap does not carry the Store fix: ${gaps.map { it.fix }}",
                gaps.any { it.fix.contains(fleet.first { it.id == l.engine }.label) && it.fix.contains("Store") })
            assertFalse("${l.from} healthy still reports a broken engine",
                kinds(healthy(), l.from).contains(GapKind.ENGINE_BROKEN))
        }
    }

    @Test
    fun `the export carries the gap report and every member`() {
        val id = apps.first().id
        val text = AppsMesh.report(decl, fleet, links, healthy(peers = everyone.toSet() - id, reachable = everyone.toSet() - id))
        assertTrue(text, text.contains("Missing membership (1)") && text.contains("[NO_PROVIDER]"))
        for (app in fleet) assertTrue("export misses ${app.id}", text.contains("(${app.id}, "))
        assertTrue(AppsMesh.report(decl, fleet, links, healthy()).contains("Missing membership (0)"))
    }

    @Test
    fun `a member that answers late after a wake is woken ok, and one that never answers is the gap`() {
        // #762 three healthy apps answered ~10 s after their wake; a single short
        // settle reported them as NO_DEBUG_API. The poll must keep sweeping.
        val id = apps.first().id
        var sweeps = 0
        val late = StoreMesh.awaitWoken(emptyMap(), setOf(id), StoreMesh.WAKE_TIMEOUT_MS, StoreMesh.WAKE_POLL_MS,
            { sweeps++; if (sweeps >= 10) mapOf(id to 38091) else emptyMap() }, {})
        assertEquals("the member that answers on the 10th sweep was given up on", mapOf(id to 38091), late)
        assertEquals("the poll kept sweeping after the member answered", 10, sweeps)
        // control: a member that never answers stops at the bound, not forever
        sweeps = 0
        val never = StoreMesh.awaitWoken(emptyMap(), setOf(id), StoreMesh.WAKE_TIMEOUT_MS, StoreMesh.WAKE_POLL_MS,
            { sweeps++; emptyMap() }, {})
        assertTrue(never.isEmpty())
        assertEquals((StoreMesh.WAKE_TIMEOUT_MS / StoreMesh.WAKE_POLL_MS).toInt(), sweeps)
        // nothing asleep: no extra sweep
        sweeps = 0
        StoreMesh.awaitWoken(mapOf(id to 38090), emptySet(), StoreMesh.WAKE_TIMEOUT_MS, StoreMesh.WAKE_POLL_MS,
            { sweeps++; emptyMap() }, {})
        assertEquals(0, sweeps)

        val woke = healthy().let { StoreMesh.Live(it.installed, it.reachable, it.peers, it.contracts, it.shares,
            it.granted, it.peerViews, woken = setOf(id)) }
        assertFalse(kinds(woke, id).contains(GapKind.NO_DEBUG_API))
        val text = AppsMesh.report(decl, fleet, links, woke)
        assertTrue(text, text.contains("(1 woke ok)") && text.contains("was stopped, woke ok"))
        assertFalse(AppsMesh.report(decl, fleet, links, healthy()).contains("was stopped, woke ok"))
    }

    @Test
    fun `every fleet package has its own debug port and the address says when a member is not on it`() {
        // #792 sixty members on a 50-port first-free range left sixteen with no port at all.
        val assigned = fleet.filter { it.pkg.isNotEmpty() }.associateWith {
            com.diegonmarcos.superapp.devtools.AppDebugServer.portOf(it.pkg) }
        assertEquals("fleet rows with no port in debug-ports.json: ${assigned.filterValues { it == null }.keys.map { it.id }}",
            emptyList<String>(), assigned.filterValues { it == null }.keys.map { it.id })
        assertEquals("two fleet packages share a port", assigned.size, assigned.values.toSet().size)
        val app = apps.first()
        val own = assigned.getValue(app)!!
        assertEquals("http://127.0.0.1:$own", StoreMesh.address(app, own))
        assertTrue(StoreMesh.address(app, own + 1000).contains("assigned :$own"))
    }

    @Test
    fun `the endpoints catalogue lists each reachable member's own docs and never claims the mesh`() {
        val app = apps.first()
        val docs = """{"endpoints":[{"path":"/api/docs","description":"this catalog"}],
            "groups":[{"group":"news","endpoints":[{"path":"/api/news/latest","params":"n=count","description":"newest"}]}]}"""
        assertEquals(listOf("/api/docs", "/api/news/latest"), AppsMesh.endpointList(docs).map { it.getString("path") })
        assertEquals("news", AppsMesh.endpointList(docs)[1].getString("group"))
        assertEquals(0, AppsMesh.endpointCount("not json"))

        val live = healthy().let { StoreMesh.Live(it.installed, mapOf(app.id to 38140), it.peers, it.contracts,
            it.shares, it.granted, it.peerViews, docs = mapOf(app.id to docs)) }
        val cat = AppsMesh.catalogue(fleet, live, AppsMesh.exposure(decl, listOf("10.0.0.9")), listOf("10.0.0.9"))
        assertFalse("the catalogue claims the debug API is on the mesh", cat.getBoolean("mesh_reachable"))
        assertTrue(cat.getString("exposure"), cat.getString("exposure").contains("10.0.0.9"))
        val members = (0 until cat.getJSONArray("members").length()).map { cat.getJSONArray("members").getJSONObject(it) }
        assertEquals("every fleet row is in the catalogue", fleet.size, members.size)
        val me = members.single { it.getString("id") == app.id }
        assertEquals("http://127.0.0.1:38140", me.getString("base"))
        assertEquals(2, me.getJSONArray("endpoints").length())
        // a member that did not answer has no endpoints, not someone else's
        val other = members.first { it.getString("id") != app.id && it.optBoolean("installed") }
        assertEquals(0, other.getJSONArray("endpoints").length())
    }

    @Test
    fun `Peer Control lists every declared device and every mesh VM once, and remembers the choice`() {
        val devices = KdeConnectConfig.get().devices
        val nodes = KdeMesh.nodes()
        val peers = KdePeers.all()
        assertTrue("no peers at all — the selector would be empty", peers.size > 1)
        for (d in devices) assertTrue("device ${d.id} missing from the selector", peers.any { it.id == d.id })
        for (n in nodes) assertTrue("mesh node ${n.name} (${n.wgIp}) missing", peers.any { it.wgIp == n.wgIp })
        assertEquals("a wg IP is listed twice", peers.size, peers.map { it.wgIp }.toSet().size)
        val pick = peers.last()
        KdePeers.select(ctx, pick.id)
        assertEquals(pick.id, KdePeers.selected(ctx, peers)?.id)
        KdePeers.select(ctx, peers.first().id)
        assertEquals(peers.first().id, KdePeers.selected(ctx, peers)?.id)
    }
}
