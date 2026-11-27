package com.diegonmarcos.cloudc3

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.diegonmarcos.cloudc3.pages.ContainersModel
import com.diegonmarcos.cloudc3.pages.MailStack
import com.diegonmarcos.cloudc3.pages.NetGraph
import com.diegonmarcos.cloudc3.pages.NetGraphLayout
import com.diegonmarcos.cloudc3.pages.NetLabel
import com.diegonmarcos.cloudc3.pages.NetLive
import com.diegonmarcos.cloudc3.pages.NetScene
import com.diegonmarcos.cloudc3.pages.NetSheets
import com.diegonmarcos.cloudc3.pages.OpsMaps
import com.diegonmarcos.cloudc3.pages.TopologyModel
import com.diegonmarcos.cloudc3.pages.netMeasure
import com.diegonmarcos.superapp.uikit.KitPalette
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1955 A rev2 The no-overlap acceptance tests (T1-T5, T7) over the exact display list the phone draws ([NetScene.build]), with REAL
 * measured text boxes: Compose's TextMeasurer under Robolectric native graphics (the real monospace font), at density 2.625 and 1.0.
 * Matrix: six pages x six states (default, all expanded, all collapsed, hub down with a root, highest-degree node selected, search with
 * three hits) x two canvases (412 x 739 dp at 2.625, 320 x 514 dp at 1.0) x views (opening, 2x on the graph centre, 2x on each of the
 * three highest-degree nodes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NetSceneOverlapTest {
    private val dir = FleetFixtures.moduleDir()
    private fun json(f: String): JsonObject = Json.parseToJsonElement(File(dir, "data/$f").readText()).jsonObject
    private val opsMaps = json("ops_maps.json")
    private val catalog = ContainersModel.parse(File(dir, "data/containers_declared.json").readText())!!
    private val infra = TopologyModel.parseInfra(json("infra_slice.json"))
    private val slice = FleetFixtures.slice()
    private val inputs = NetGraph.Inputs(OpsMaps.Sources(opsMaps, json("mesh.json"), infra, json("infra_slice.json"), catalog, slice.domains),
        slice, TopologyModel.Inputs(catalog, slice, infra), json("mesh.json"))
    private val v = NetGraph.parseViewers(opsMaps)
    private val sp = NetScene.Spec.of(v)
    private val pal = KitPalette(Color.Black, Color.DarkGray, Color.White, Color.LightGray, Color.Cyan, Color.Gray, Color.Black,
        Color(0xFF00FF00), Color(0xFFFFFF00), Color(0xFFFF0000))
    private val pages = listOf("net_wg", "net_auth", "net_dns", "net_network", "net_security", "net_mesh")
    private val hub = slice.vms.first { it.id == infra.subnets.first().hub }
    private fun graph(id: String) = NetGraph.page(v, id, inputs)!!.second

    data class Canvas(val name: String, val wDp: Float, val hDp: Float, val density: Float) { val w get() = wDp * density; val h get() = hDp * density }
    private val canvases = listOf(Canvas("412x739", 412f, 739f, 2.625f), Canvas("320x514", 320f, 514f, 1f))

    private val measurers = HashMap<Float, NetScene.Measure>()
    /** The real text measure at [density]: the same function the canvas uses (netMeasure over a TextMeasurer). */
    private fun measure(density: Float): NetScene.Measure = measurers.getOrPut(density) {
        val tm = TextMeasurer(createFontFamilyResolver(RuntimeEnvironment.getApplication()), Density(density, 1f), LayoutDirection.Ltr, 2048)
        netMeasure(tm)
    }

    private fun hubDown(g: NetGraph.Graph): Triple<Map<String, NetLive.Reading>, List<NetLive.Root>, NetLive.Snapshot> {
        val s = NetLive.Snapshot(
            deployed = slice.vms.filter { it.wgIp != null }.associate { vm -> vm.alias to if (vm.alias == hub.alias) TopologyModel.Deployed.Blind("timeout") else TopologyModel.Deployed.Seen(emptyList()) },
            api = true, at = 1L)
        val r = NetLive.applyLive(g, s, v)
        return Triple(r, NetLive.rootCause(g, r, s), s)
    }

    /** One case of the matrix: the layout and the scene state it is drawn with. */
    data class Case(val page: String, val state: String, val g: NetGraph.Graph, val collapsed: Set<String>, val st: NetScene.State, val status: Map<String, MailStack.Health>)

    private fun degree(l: NetGraphLayout.Layout) = l.plan.nodes.map { n -> n.id to l.plan.edges.count { it.from == n.id || it.to == n.id } }
        .sortedWith(compareBy({ -it.second }, { it.first })).map { it.first }

    private fun cases(page: String): List<Case> {
        val g = graph(page)
        val def = NetGraphLayout.defaultCollapsed(g, v)
        val all = g.clusters.map { it.id }.toSet()
        val (r, roots, _) = hubDown(g)
        val status = r.mapValues { it.value.health }
        val root = roots.firstOrNull()
        val l0 = NetGraphLayout.layout(g, v, def, pal, emptyMap(), 1f)
        val top = degree(l0).first()
        val nb = l0.plan.edges.filter { it.from == top || it.to == top }.flatMap { listOf(it.from, it.to) }.toSet() + top
        val three = g.nodes.take(3).map { it.id }.toSet()
        return listOf(
            Case(page, "default", g, def, NetScene.State(), emptyMap()),
            Case(page, "all expanded", g, emptySet(), NetScene.State(), emptyMap()),
            Case(page, "all collapsed", g, all, NetScene.State(), emptyMap()),
            Case(page, "hub down", g, def, NetScene.State(root = root?.id, impacted = root?.impacted.orEmpty()), status),
            Case(page, "selected $top", g, def, NetScene.State(selected = top, focus = nb), emptyMap()),
            Case(page, "search 3 hits", g, def, NetScene.State(matches = three, focus = three), emptyMap()),
        )
    }

    data class Shot(val label: String, val c: Case, val cv: Canvas, val l: NetGraphLayout.Layout, val view: MailStack.View, val scene: NetScene.Scene)

    private fun scene(c: Case, cv: Canvas, l: NetGraphLayout.Layout, view: MailStack.View, tier: NetScene.Tier? = null): NetScene.Scene {
        // the HUD the phone has in this state: the minimap when the graph is not wholly shown, the hit list with a query
        val mini = NetScene.minimap(l.plan.bounds, view, cv.w, cv.h, cv.density, sp, c.st.selected != null)
        val hits = if (c.state.startsWith("search")) MailStack.Rect(0f, 0f, cv.w, (28f + 3 * 36f) * cv.density) else null
        return NetScene.build(l, c.g, v, view, cv.w, cv.h, cv.density, c.st.copy(hud = listOfNotNull(mini, hits), tier = tier), measure(cv.density), c.status)
    }

    private fun shots(page: String): List<Shot> = buildList {
        cases(page).forEach { c ->
            canvases.forEach { cv ->
                val l = NetGraphLayout.layout(c.g, v, c.collapsed, pal, c.status, cv.density)
                val open = NetGraphLayout.openFor(l, v, cv.w, cv.h, cv.density, c.st.root)
                add(Shot("$page / ${c.state} / ${cv.name} / opening", c, cv, l, open, scene(c, cv, l, open)))
                val z = MailStack.View(open.scale * 2f, open.ox, open.oy)
                val b = l.plan.bounds
                val zc = z.centerOn(b, cv.w, cv.h)
                add(Shot("$page / ${c.state} / ${cv.name} / 2x centre", c, cv, l, zc, scene(c, cv, l, zc)))
                degree(l).take(3).forEach { id ->
                    val r = l.plan.nodes.first { it.id == id }.rect
                    val zn = z.centerOn(r, cv.w, cv.h)
                    add(Shot("$page / ${c.state} / ${cv.name} / 2x on $id", c, cv, l, zn, scene(c, cv, l, zn)))
                }
            }
        }
    }

    private fun hit(a: MailStack.Rect, b: MailStack.Rect) = NetScene.hit(a, b)
    private fun inflate(r: MailStack.Rect, d: Float) = NetScene.inflate(r, d)

    @Test fun `T1 geometric no-overlap - every text box on all six pages, six states, two canvases, opening and 2x views`() {
        val bad = mutableListOf<String>()
        var texts = 0
        pages.forEach { page ->
            shots(page).forEach { s ->
                val dp = s.cv.density
                val sc = s.scene
                val ts = sc.texts
                texts += ts.size
                val m = measure(dp)
                // a. no two text boxes intersect (inflated by 1 dp)
                for (i in ts.indices) for (j in i + 1 until ts.size)
                    if (hit(inflate(ts[i].rect, dp), ts[j].rect)) bad += "${s.label}: a. '${ts[i].text}' (${ts[i].cls}) touches '${ts[j].text}' (${ts[j].cls})"
                ts.forEach { t ->
                    // b. inside its own node's content rect, or clear of every node box by 2 dp
                    sc.nodes.forEach { n ->
                        val own = t.owner == n.id && (t.cls == NetScene.Cls.NAME || t.cls == NetScene.Cls.METRIC)
                        if (own) { if (!NetScene.inside(t.rect, n.content)) bad += "${s.label}: b. '${t.text}' leaves its node ${n.id}" }
                        else if (hit(t.rect, inflate(n.box, 2f * dp))) bad += "${s.label}: b. '${t.text}' (${t.cls}) touches node ${n.id}"
                    }
                    // c. no header band, caption strip or HUD rect it does not own
                    sc.frames.forEach { f -> f.header?.let { h -> if (!(t.cls == NetScene.Cls.HEADER && t.owner == f.id) && hit(t.rect, h)) bad += "${s.label}: c. '${t.text}' touches the header of ${f.id}" } }
                    sc.caption?.let { c -> if (t.cls != NetScene.Cls.CAPTION && hit(t.rect, c)) bad += "${s.label}: c. '${t.text}' touches the caption strip" }
                    sc.hud.forEach { h -> if (hit(t.rect, h)) bad += "${s.label}: c. '${t.text}' touches a HUD rect" }
                    // d. inside the canvas
                    if (!NetScene.inside(t.rect, sc.canvas)) bad += "${s.label}: d. '${t.text}' leaves the canvas"
                    // f. the measured text fits its box (nothing relies on clipping); a shortened text carries the ellipsis
                    val (mw, mh) = m.size(t.text, t.sp, t.bold)
                    if (mw > t.rect.r - t.rect.l + 0.5f || mh > t.rect.b - t.rect.t + 0.5f) bad += "${s.label}: f. '${t.text}' measures $mw x $mh in ${t.rect}"
                    if (t.text != t.full && t.cls != NetScene.Cls.CAPTION && !t.text.contains(NetLabel.ELLIPSIS) && t.cls != NetScene.Cls.PILL) {
                        // a stripped shared suffix ("auth.diegonmarcos.com" -> "auth") is a shortening without an ellipsis
                        if (!t.full.startsWith(t.text)) bad += "${s.label}: f. '${t.text}' shortens '${t.full}' without an ellipsis"
                    }
                }
                // e. world: node boxes at least 12 dp apart; no node box crosses a frame border it is not inside
                val ns = s.l.plan.nodes
                val unit = s.l.unit
                for (i in ns.indices) for (j in i + 1 until ns.size) {
                    val a = ns[i].rect; val b = ns[j].rect
                    val gap = maxOf(b.l - a.r, a.l - b.r, b.t - a.b, a.t - b.b)
                    if (gap < 12f * unit - 0.01f) bad += "${s.label}: e. ${ns[i].id} and ${ns[j].id} are ${gap / unit} dp apart"
                }
                s.l.extras.frames.forEach { f -> ns.forEach { n ->
                    if (!NetScene.inside(n.rect, f.rect) && hit(n.rect, f.rect)) bad += "${s.label}: e. ${n.id} crosses the frame ${f.id}"
                } }
            }
        }
        assertTrue("the matrix draws text at all", texts > 0)
        assertEquals(bad.take(40).joinToString("\n"), 0, bad.size)
    }

    @Test fun `T2 label budget - no pill at the opening view, budgets with a selection, at 2x and at FAR`() {
        val cv = canvases[0]
        pages.forEach { page ->
            val cs = cases(page)
            val def = cs.first { it.state == "default" }
            val l = NetGraphLayout.layout(def.g, v, def.collapsed, pal, emptyMap(), cv.density)
            val open = NetGraphLayout.openFor(l, v, cv.w, cv.h, cv.density)
            val sc = scene(def, cv, l, open)
            val by = sc.texts.groupBy { it.cls }
            assertEquals("$page: no edge pill at the opening view", 0, by[NetScene.Cls.PILL].orEmpty().size)
            assertTrue("$page: floating labels <= 12", (by[NetScene.Cls.PILL].orEmpty() + by[NetScene.Cls.ANCHOR].orEmpty()).size <= 12)
            assertTrue("$page: names <= 40", by[NetScene.Cls.NAME].orEmpty().size <= 40)
            assertTrue("$page: texts <= 56 (${sc.texts.size})", sc.texts.size <= 56)
            val selC = cs.first { it.state.startsWith("selected") }
            val sel = scene(selC, cv, l, open)
            assertTrue("$page: pills <= 8 with a selection", sel.texts.count { it.cls == NetScene.Cls.PILL } <= 8)
            val z = MailStack.View(open.scale * 2f, open.ox, open.oy).centerOn(l.plan.bounds, cv.w, cv.h)
            assertTrue("$page: pills <= 12 at 2x", scene(def, cv, l, z).texts.count { it.cls == NetScene.Cls.PILL } <= 12)
            val far = scene(def, cv, l, MailStack.View(0.5f, open.ox, open.oy), NetScene.Tier.FAR)
            assertEquals("$page: no node name at FAR", 0, far.texts.count { it.cls == NetScene.Cls.NAME })
            assertTrue("$page: anchors <= 12 at FAR", far.texts.count { it.cls == NetScene.Cls.ANCHOR } <= 12)
        }
    }

    @Test fun `T3 caps and sizes - nothing under 10 sp, names, metrics, pills and headers within their caps, distinct names stay distinct`() {
        // the shortening rules themselves
        assertEquals("auth", NetLabel.short("auth.diegonmarcos.com", listOf("auth.diegonmarcos.com", "vpn.diegonmarcos.com", "mail.diegonmarcos.com"), 12))
        assertEquals(12, NetLabel.short("a-very-long-service-name", emptyList(), 12).length)
        assertTrue(NetLabel.short("a-very-long-service-name", emptyList(), 12).endsWith(NetLabel.ELLIPSIS))
        val two = NetLabel.shortAll(mapOf("a" to "cloudy-c3-api-server", "b" to "cloudy-c3-mcp-server"), { emptyList() }, { 12 })
        assertTrue("two names that read the same after the cap differ: $two", two["a"] != two["b"])
        assertEquals("10.0.0.3/32 +1", NetLabel.pill("10.0.0.3/32, 10.0.0.0/24", 14))
        assertEquals("", NetLabel.fit("abcdefgh", 1f) { it.length * 10f })
        val bad = mutableListOf<String>()
        pages.forEach { page ->
            shots(page).forEach { s ->
                val names = mutableMapOf<String, String>()
                s.scene.texts.forEach { t ->
                    if (t.sp < 10f) bad += "${s.label}: '${t.text}' at ${t.sp} sp"
                    val cap = when (t.cls) {
                        NetScene.Cls.NAME -> {
                            val n = s.l.plan.nodes.first { it.id == t.owner }
                            if (s.scene.tier == NetScene.Tier.NEAR) sp.capNameNear else if ((n.rect.r - n.rect.l) / s.l.unit >= sp.wideFrom) sp.capNameWide else sp.capName
                        }
                        NetScene.Cls.METRIC -> sp.capMetric
                        NetScene.Cls.PILL -> sp.capPill
                        NetScene.Cls.HEADER -> sp.capHeader
                        NetScene.Cls.CAPTION -> sp.capCaption
                        NetScene.Cls.ANCHOR -> sp.capAnchor
                    }
                    if (t.text.length > cap) bad += "${s.label}: '${t.text}' (${t.cls}) is ${t.text.length} > $cap chars"
                    if (t.cls == NetScene.Cls.NAME) {
                        names[t.text]?.let { other -> if (other != t.full) bad += "${s.label}: '$other' and '${t.full}' both read '${t.text}'" }
                        names[t.text] = t.full
                    }
                }
            }
        }
        assertEquals(bad.take(40).joinToString("\n"), 0, bad.size)
    }

    /** Pages whose rev2 layout has landed: they open with the WHOLE graph on a 412 x 739 phone (T4 switches on per page). */
    private val fitAllPages = v.raw["fit_all_pages"]?.let { e -> (e as? kotlinx.serialization.json.JsonArray)?.map { it.toString().trim('"') } }.orEmpty()

    @Test fun `T4 opening view - readable scale on both canvases, the whole graph on landed pages, the root on screen`() {
        pages.forEach { page ->
            cases(page).filter { it.state == "default" || it.state == "hub down" }.forEach { c ->
                canvases.forEach { cv ->
                    val l = NetGraphLayout.layout(c.g, v, c.collapsed, pal, c.status, cv.density)
                    val open = NetGraphLayout.openFor(l, v, cv.w, cv.h, cv.density, c.st.root)
                    assertTrue("$page ${c.state} ${cv.name}: opens at ${open.scale} < 0.60", open.scale >= 0.6f - 1e-4f)
                    fun shown(r: MailStack.Rect): Boolean {
                        val a = open.toScreen(Offset(r.l, r.t)); val b = open.toScreen(Offset(r.r, r.b))
                        return a.x >= -0.5f && a.y >= -0.5f && b.x <= cv.w + 0.5f && b.y <= cv.h + 0.5f
                    }
                    if (c.state == "default" && cv.name == "412x739" && page in fitAllPages) assertTrue("$page: the whole graph opens on screen", shown(l.plan.bounds))
                    c.st.root?.let { rid ->
                        val r = l.plan.nodes.first { it.id == (l.extras.memberOf[rid] ?: rid) }.rect
                        assertTrue("$page ${cv.name}: the root ${c.st.root} is on screen without a gesture", shown(r))
                    }
                }
            }
        }
    }

    @Test fun `T5 determinism and parity - equal scenes on two runs, parity in every state`() {
        pages.forEach { page ->
            cases(page).forEach { c ->
                val cv = canvases[1]
                val l1 = NetGraphLayout.layout(c.g, v, c.collapsed, pal, c.status, cv.density)
                val l2 = NetGraphLayout.layout(graph(page), v, c.collapsed, pal, c.status, cv.density)
                assertEquals("$page ${c.state}", l1, l2)
                val o = NetGraphLayout.openFor(l1, v, cv.w, cv.h, cv.density, c.st.root)
                assertEquals("$page ${c.state}: the scene", scene(c, cv, l1, o), scene(c, cv, l2, o))
                assertEquals("$page ${c.state}", emptyList<String>(), NetGraphLayout.parity(c.g, l1))
            }
        }
    }

    /** The strings the pre-rev2 canvas drew (taken from its code: node label, status + sub line, reading word, edge and bundle labels, lane label and sub, column label, banner why, impacted count). */
    private fun shipped(g: NetGraph.Graph, l: NetGraphLayout.Layout, r: Map<String, NetLive.Reading>, roots: List<NetLive.Root>): List<Triple<String, String, String>> = buildList {
        l.plan.nodes.forEach { n ->
            add(Triple("node", n.id, n.label))
            add(Triple("sub", n.id, MailStack.healthLabel(n.health) + if (n.sub.isBlank()) "" else "  " + n.sub))
            val word = r[n.id]?.word ?: if (n.kind == "cluster") "" else "declared"
            if (word.isNotBlank()) add(Triple("reading", n.id, word))
        }
        l.plan.edges.forEach { e -> if (e.label.isNotBlank()) add(Triple("edge", e.id, e.label)) }
        l.extras.bundleLabels.forEach { (_, t) -> add(Triple("bundle", "", t)) }
        l.extras.laneRects.keys.forEach { lane ->
            val gl = g.lanes.firstOrNull { it.id == lane }
            add(Triple("lane", lane, (gl?.label ?: lane).ifBlank { g.page }))
            gl?.sub?.takeIf { it.isNotBlank() }?.let { add(Triple("lane sub", lane, it)) }
        }
        g.columns.forEach { add(Triple("column", "", it)) }
        roots.firstOrNull()?.let { rt -> add(Triple("why", rt.id, rt.why)); add(Triple("impacted", rt.id, "${rt.impacted.size}")) }
    }

    @Test fun `T7 deferral parity - every string the old canvas drew is in full in the scene or in a sheet`() {
        val bad = mutableListOf<String>()
        pages.forEach { page ->
            cases(page).filter { it.state in setOf("default", "all expanded", "all collapsed", "hub down") }.forEach { c ->
                val g = c.g
                val (r0, roots0, _) = hubDown(g)
                val readings = if (c.state == "hub down") r0 else NetLive.applyLive(g, NetLive.Snapshot(), v)
                val roots = if (c.state == "hub down") roots0 else emptyList()
                val l = NetGraphLayout.layout(g, v, c.collapsed, pal, readings.mapValues { it.value.health }, 1f)
                val cv = canvases[1]
                val sc = scene(c, cv, l, NetGraphLayout.openFor(l, v, cv.w, cv.h, cv.density, roots.firstOrNull()?.id))
                val texts = sc.texts.map { it.text }.toSet()
                // every sheet a tap can open on this page, as the lines it prints (titles included)
                val sheets = buildList {
                    l.plan.nodes.forEach { p ->
                        val cid = l.extras.aggregates[p.id]
                        if (cid != null && p.id.startsWith("agg:")) {
                            val cl = g.clusters.first { it.id == cid }
                            add(cl.label); NetSheets.cluster(cl, g, readings, p).forEach { add(it.k + " " + it.v) }
                        }
                    }
                    // every node can be opened (tap, search, a cluster sheet's member list, the root chip)
                    g.nodes.forEach { n ->
                        val p = l.plan.nodes.firstOrNull { it.id == n.id }
                        add(n.label); add(statusWordOf(readings[n.id])); NetSheets.node(n, g, readings[n.id] ?: NetLive.DECLARED, roots, roots.firstOrNull()?.impacted.orEmpty(), p).forEach { add(it.k + " " + it.v) }
                    }
                    l.plan.edges.forEach { pe ->
                        val ms = l.extras.edgeMembers[pe.id].orEmpty()
                        val e = (g.edges + g.depends).firstOrNull { it.id == pe.id } ?: (g.edges + g.depends).first { it.id == ms.first() }
                        NetSheets.edge(e, ms, pe, l.extras.bundleOf[pe.id], g, readings).forEach { add(it.k + " " + it.v) }
                    }
                    l.extras.frames.forEach { f -> NetSheets.frame(f, g, l).forEach { add(it.k + " " + it.v) } }
                }
                shipped(g, l, readings, roots).forEach { (kind, id, s) ->
                    if (s !in texts && sheets.none { it.contains(s) }) bad += "$page ${c.state}: $kind $id '$s' is neither drawn nor in a sheet"
                }
                // the root chip's full sentence and the sheet
                roots.firstOrNull()?.let { rt -> if (sheets.none { it.contains(rt.why) }) bad += "$page: the root's why is in no sheet" }
            }
        }
        assertEquals(bad.take(40).joinToString("\n"), 0, bad.size)
    }

    private fun statusWordOf(r: NetLive.Reading?) = com.diegonmarcos.cloudc3.pages.statusWord(r?.health ?: MailStack.Health.UNKNOWN)

    @Test fun `T9 a selected node dims every non-neighbour and is centred above the peek sheet`() {
        val cv = canvases[1]
        pages.forEach { page ->
            val c = cases(page).first { it.state.startsWith("selected") }
            val l = NetGraphLayout.layout(c.g, v, c.collapsed, pal, emptyMap(), cv.density)
            val r = l.plan.nodes.first { it.id == c.st.selected }.rect
            val view = NetGraphLayout.centreAbove(NetGraphLayout.openFor(l, v, cv.w, cv.h, cv.density), r, cv.w, cv.h, 132f * cv.density)
            val sc = scene(c, cv, l, view)
            val focus = c.st.focus!!
            sc.nodes.forEach { n -> assertEquals("$page: ${n.id} dim", n.id !in focus, n.dim) }
            val cy = view.toScreen(Offset(r.cx, r.cy)).y
            assertTrue("$page: the selected node's centre $cy is above the sheet", cy < cv.h - 132f * cv.density)
        }
    }
}
