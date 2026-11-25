package com.diegonmarcos.superapp.network.mesh

import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * #877 `build.json::ui.mesh_page`, parsed: the Cloud Mesh page's tabs, each tab's controls with
 * their defaults, and the numbers the live readout uses. Nothing about the page's shape is written
 * in Kotlin: the strip draws [pages], a tab draws its [Page.controls], and test-mesh-page.sh holds
 * this declaration equal to the code in both directions (a declared page with no composable, a
 * composable with no declaration, a control naming an engine call that does not exist, a control
 * the engine cannot honour that is not shown disabled with its reason).
 *
 * [parse] is pure; [baked] is the declaration THIS build shipped (BuildConfig.UI_MESH_PAGE_B64).
 */
object MeshDecl {

    /** switch | choice | number | text | secret | list | action. */
    data class Control(
        val id: String,
        val kind: String,
        /** The heading this control sits under on its page; blank = none. */
        val group: String,
        val label: String,
        /** The declared default, as text; "" when none ([defaultFrom] names where it lives instead). */
        val default: String,
        val defaultFrom: String,
        val choices: List<String>,
        val choicesFrom: String,
        val min: Long?,
        val max: Long?,
        /** The engine call this control dispatches to (a [MeshActions] id); "" when it has none. */
        val engine: String,
        /** Non-blank: the engine cannot honour this control; the page shows it disabled with this reason. */
        val unsupported: String,
    ) {
        val honoured: Boolean get() = unsupported.isBlank()
    }

    data class Page(
        val id: String,
        val label: String,
        val icon: String,
        /** The ordered readout rows a Status-style page draws; empty for the others. */
        val rows: List<String>,
        val controls: List<Control>,
    ) {
        fun control(id: String): Control? = controls.firstOrNull { it.id == id }
    }

    /** #1900 A section of the Status page: [rows] are ids of the status page's `rows`. */
    data class Section(val id: String, val label: String, val rows: List<String>)

    /**
     * #1928 A subpage of a tab: it draws the [sections] (ids of `pages`) it names, showing only the [controls] it lists (none listed = all of
     * the page's), and for a Status one only the status section [statusSection]. [stamp] names what its "Updated" time reads (snapshot |
     * probes | log); [report] the `==` headings of the status dump its Copy / Export carries.
     */
    data class Subpage(
        val id: String,
        val label: String,
        val sections: List<String>,
        val controls: List<String> = emptyList(),
        val statusSection: String = "",
        val stamp: String = "",
        val report: List<String> = emptyList(),
    ) {
        /** Whether this subpage draws control [id] (a subpage that lists no controls draws every one of its pages'). */
        fun shows(id: String): Boolean = controls.isEmpty() || id in controls
    }

    /** #1074 A tab of the strip: [sections] are the ids of `pages` it draws, in this order; #1928 [subpages] split it into a second strip. */
    data class Tab(val id: String, val label: String, val icon: String, val sections: List<String>, val subpages: List<Subpage> = emptyList()) {
        fun subpage(id: String?): Subpage? = subpages.firstOrNull { it.id == id }
    }

    data class Decl(
        val defaultPage: String,
        val pollMs: Long,
        val latencyEveryTicks: Int,
        val latencyTimeoutMs: Int,
        val logCap: Int,
        val handshakeFreshS: Long,
        val handshakeStaleS: Long,
        val nat64Marker: String,
        val pages: List<Page>,
        /** #1074 The strip: Status / Profile / Logs, each over declared sections. */
        val tabs: List<Tab> = emptyList(),
        val defaultTab: String = "",
        /** #1074 Every control id the page had before the reorganisation; the guard that none silently disappears. */
        val previousControls: List<String> = emptyList(),
        val cloudflare: com.diegonmarcos.superapp.network.CloudflareWarp.Decl = com.diegonmarcos.superapp.network.CloudflareWarp.Decl.parse(null),
        /** #1153 The private mesh hosts the Reachability test probes (DNS, TCP :443, HTTPS). */
        val reachHosts: List<String> = emptyList(),
        /** #1797 host -> the name Status shows beside it (Vault, Chat, Mail, Drive ...). */
        val reachLabels: Map<String, String> = emptyMap(),
        /** #1797 The meshes the per-mesh table and Cloud C3's profiler probe, one row each. */
        val meshes: List<com.diegonmarcos.superapp.fleetxray.mesh.C3MeshProbe.Target> = emptyList(),
        /** #1797 The IP packet sizes the profiler's MTU probe sends through the tunnel. */
        val mtuProbeSizes: List<Int> = emptyList(),
        /** #1900 The Prober matrix: which names, URLs and IP literals each group probes, the per-probe timeout and the parallelism. */
        val prober: MeshProber.Decl = MeshProber.Decl(),
        /**
         * #1900 The Status page's three sections (Status / Profiler / Prober): each a label and the row ids it draws. Every id of
         * `rows` is in exactly one; empty = the page draws `rows` flat.
         */
        val sections: List<Section> = emptyList(),
        /** #1928 A former page id -> "tab/subpage" that now holds it, so an old route still opens the right place. */
        val aliases: Map<String, String> = emptyMap(),
    ) {
        fun tab(id: String?): Tab? = tabs.firstOrNull { it.id == id }
        /** #1928 Where target [id] opens: a tab, a subpage, a former page id (via [aliases]) or a section; null when it names nothing. */
        fun resolve(id: String): Pair<Tab, Subpage?>? {
            aliases[id]?.let { a ->
                val t = tab(a.substringBefore('/')) ?: return@let
                return t to t.subpage(a.substringAfter('/', ""))
            }
            tab(id)?.let { return it to null }
            for (t in tabs) t.subpage(id)?.let { return t to it }
            return tabOf(id)?.let { t -> t to t.subpages.firstOrNull { id in it.sections } }
        }
        /** The tab the strip opens on: the declared default, else the first. */
        val startTab: Tab get() = tab(defaultTab) ?: tabs.first()
        /** The tab that draws section [pageId], or null when no tab does. */
        fun tabOf(pageId: String): Tab? = tabs.firstOrNull { pageId in it.sections }
        /** Every control reachable from some tab. */
        val reachableControls: Set<String> get() = tabs.flatMap { t -> t.sections.flatMap { s -> page(s)?.controls.orEmpty().map { it.id } } }.toSet()
        fun page(id: String?): Page? = pages.firstOrNull { it.id == id }
        fun control(id: String): Control? = pages.firstNotNullOfOrNull { it.control(id) }
        /** The page the strip opens on: the declared default, else the first. */
        val startPage: Page get() = page(defaultPage) ?: pages.first()
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    fun parse(json: String): Decl {
        val o = JSONObject(json)
        val hs = o.optJSONObject("handshake") ?: JSONObject()
        val ps = o.getJSONArray("pages")
        return Decl(
            defaultPage = o.optString("default_page"),
            pollMs = o.optLong("poll_ms", 1000L).coerceAtLeast(250L),
            latencyEveryTicks = o.optInt("latency_every_ticks", 5).coerceAtLeast(1),
            latencyTimeoutMs = o.optInt("latency_timeout_ms", 1200).coerceAtLeast(100),
            logCap = o.optInt("log_cap", 200).coerceAtLeast(10),
            reachHosts = o.optJSONArray("reach_hosts").strings(),
            reachLabels = o.optJSONObject("reach_labels").let { l -> if (l == null) emptyMap() else l.keys().asSequence().associateWith { l.getString(it) } },
            meshes = o.optJSONArray("meshes").let { a ->
                if (a == null) emptyList() else (0 until a.length()).map { i ->
                    val m = a.getJSONObject(i)
                    com.diegonmarcos.superapp.fleetxray.mesh.C3MeshProbe.Target(
                        id = m.getString("id"), label = m.optString("label", m.getString("id")),
                        cidr = m.optString("cidr"), cidr6 = m.optString("cidr6"),
                        hub = m.getString("hub"), hub6 = m.optString("hub6"),
                        resolver = m.optString("resolver", m.getString("hub")), name = m.getString("name"),
                        serviceUrl = m.optString("service_url"),
                    )
                }
            },
            prober = MeshProber.parse(o.optJSONObject("prober")),
            sections = o.optJSONArray("status_sections").let { a ->
                if (a == null) emptyList() else (0 until a.length()).map { i ->
                    val x = a.getJSONObject(i)
                    Section(x.getString("id"), x.optString("label", x.getString("id")), x.optJSONArray("rows").strings())
                }
            },
            mtuProbeSizes = o.optJSONArray("mtu_probe_sizes").let { a -> if (a == null) emptyList() else (0 until a.length()).map { a.getInt(it) } },
            handshakeFreshS = hs.optLong("fresh_s", 150L),
            handshakeStaleS = hs.optLong("stale_s", 300L),
            nat64Marker = o.optString("nat64_marker"),
            tabs = o.optJSONArray("tabs").let { a ->
                if (a == null) emptyList() else (0 until a.length()).map { i ->
                    val x = a.getJSONObject(i)
                    Tab(x.getString("id"), x.optString("label", x.getString("id")), x.optString("icon"), x.optJSONArray("sections").strings(),
                        x.optJSONArray("subpages").let { sa ->
                            if (sa == null) emptyList() else (0 until sa.length()).map { k ->
                                val y = sa.getJSONObject(k)
                                Subpage(y.getString("id"), y.optString("label", y.getString("id")), y.optJSONArray("sections").strings(),
                                    y.optJSONArray("controls").strings(), y.optString("status_section"), y.optString("stamp"), y.optJSONArray("report").strings())
                            }
                        })
                }
            },
            defaultTab = o.optString("default_tab"),
            aliases = o.optJSONObject("aliases").let { l -> if (l == null) emptyMap() else l.keys().asSequence().associateWith { l.getString(it) } },
            previousControls = o.optJSONArray("previous_controls").strings(),
            cloudflare = com.diegonmarcos.superapp.network.CloudflareWarp.Decl.parse(o.optJSONObject("cloudflare")),
            pages = (0 until ps.length()).map { i ->
                val p = ps.getJSONObject(i)
                val cs = p.optJSONArray("controls") ?: JSONArray()
                Page(
                    id = p.getString("id"),
                    label = p.optString("label", p.getString("id")),
                    icon = p.optString("icon"),
                    rows = p.optJSONArray("rows").strings(),
                    controls = (0 until cs.length()).map { j ->
                        val c = cs.getJSONObject(j)
                        Control(
                            id = c.getString("id"),
                            kind = c.getString("kind"),
                            group = c.optString("group"),
                            label = c.optString("label", c.getString("id")),
                            default = if (c.has("default")) c.get("default").toString() else "",
                            defaultFrom = c.optString("default_from"),
                            choices = c.optJSONArray("choices").strings(),
                            choicesFrom = c.optString("choices_from"),
                            min = if (c.has("min")) c.getLong("min") else null,
                            max = if (c.has("max")) c.getLong("max") else null,
                            engine = c.optString("engine"),
                            unsupported = c.optString("unsupported"),
                        )
                    },
                )
            },
        )
    }

    /** The seeds `default_from` names: ui.wireguard_default's fields (baked) and ui.dns's default preset. */
    fun seeds(): Map<String, String> = mapOf(
        "interface_mtu" to BuildConfig.UI_WG_INTERFACE_MTU,
        "tunnel_name" to BuildConfig.UI_WG_TUNNEL_NAME,
        "interface_address" to BuildConfig.UI_WG_INTERFACE_ADDRESS,
        "dns.default_preset" to com.diegonmarcos.superapp.network.FleetDns.decl.defaultPreset,
    )

    /** The declaration this build shipped. */
    val baked: Decl by lazy { parse(String(Base64.decode(BuildConfig.UI_MESH_PAGE_B64, Base64.DEFAULT))) }

    /**
     * A control's default, resolved: its own `default`, else the seed `default_from` names in
     * [seeds] (ui.wireguard_default's fields, plus `dns.default_preset`). "" when neither exists.
     */
    fun defaultOf(c: Control, seeds: Map<String, String>): String =
        if (c.defaultFrom.isNotBlank()) seeds[c.defaultFrom].orEmpty() else c.default
}
