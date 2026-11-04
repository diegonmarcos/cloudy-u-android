package com.diegonmarcos.cloudc3.cloud

import android.content.Context
import android.util.Base64
import com.diegonmarcos.cloudc3.BuildConfig
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.pages.ContainerProjects
import com.diegonmarcos.cloudc3.pages.ContainersModel
import com.diegonmarcos.cloudc3.pages.ContainersSource
import org.json.JSONArray

/**
 * #648 the stack-declaration models and parsers, MOVED from the SuperApp's Sections.kt
 * with the pages that read them, trimmed to exactly what the carried c3 surface uses:
 * the [StackPanel] shape (with its tiles, repos, anchors, dashboard groups, ntfy scopes),
 * the cloud-dashboard inventory (containers-declared.json via ContainersSource, #1148),
 * the wg-mesh/v1 snapshot (data/mesh.json → BuildConfig.MESH_JSON_B64), and [iconResFor].
 * Field names, `optString` defaults and parse order are the SuperApp's, because this is a
 * copy and the declarations it parses are byte-identical to the ones that shipped.
 *
 * The two stacks themselves bake from build.json::ui.sections[c3].stack_topology /
 * stack_observability — the verbatim carried section — via UI_STACK_TOPOLOGY_B64 /
 * UI_STACK_OBSERV_B64.
 */
object Stacks {

    /** One collapsable card in a [C3StackFragment]. `kind` dispatches to a body builder
     *  there. Unknown kinds fall through to a placeholder card. */
    data class StackPanel(
        val kind: String,
        val title: String,
        val subtitle: String = "",
        val collapsed: Boolean = false,
        /** Used by kind=tile_row — nested mini-tiles. */
        val tiles: List<AggTile> = emptyList(),
        /** Used by kind=feed and the Analytics card's handoff row. */
        val url: String = "",
        val iconName: String = "",
        /** Used by kind=feed (source=github_runs/github_run_stats/github_commits/
         *  gitea_commits) — the repo set, and THE repo set: the renderers iterate this
         *  array and nothing else. */
        val repos: List<RepoRef> = emptyList(),
        /** Used by kind=cloud_dashboard — which cloud_services.json group(s) THIS card
         *  renders (`group` string or `groups` array in the declaration). */
        val dashGroupIds: List<String> = emptyList(),
        /** Used by kind=notification_center — which ui.ntfy scope ids this card shows.
         *  Empty means every channel. */
        val scopes: List<String> = emptyList(),
        val origin: String = "",
        /** Used by kind=notification_center — the stream. The c3 surface declares
         *  `channels`: the ntfy channels alone, one group per channel. */
        val stream: String = "",
        /** Id this panel answers to as an in-page anchor target. */
        val anchor: String = "",
        /** The FURTHER anchor targets this panel provides (the Stack card's sub-tables). */
        val anchors: List<PanelAnchor> = emptyList(),
        /** Used by kind=feed — which fetcher builds this card's rows. */
        val source: String = "",
        /** Rows per repo / total rows / messages per channel group; 0 = renderer default. */
        val limit: Int = 0,
    )

    /** One declared in-panel anchor — see the SuperApp's Sections.PanelAnchor. */
    data class PanelAnchor(val id: String, val group: String = "", val subgroup: String = "")

    /** One toggle declaration — kept for [StackFilters]' stored page settings (the
     *  `__`-prefixed archived/open keys); the c3 pages declare no visible filter row. */
    data class StackFilter(
        val id: String,
        val label: String,
        val default: String,
        val options: List<FilterOption>,
    )

    data class FilterOption(val id: String, val label: String)

    /** One tile in a tile_row. `target` follows the SuperApp's grammar —
     *  anchor:X | page:X/Y | extapp:X | http(s)://… */
    data class AggTile(
        val id: String,
        val label: String,
        val iconName: String,
        val target: String,
    )

    /** One row in a kind=feed panel's repo set. */
    data class RepoRef(val owner: String, val repo: String, val label: String)

    // ── Cloud dashboard model (data/cloud_services.json) ─────────────────
    data class CloudDash(val groups: List<CloudGroup>)
    data class CloudGroup(
        val id: String, val label: String, val icon: String,
        val subgroups: List<CloudSub>, val providers: List<DashProvider>,
    )
    data class CloudSub(val label: String, val icon: String, val containers: List<CloudContainer>)
    /** A dashboard entry. When [external] the url is a full link opened directly with no
     *  status light; otherwise url is a {name}.mesh.diegonmarcos.com host pinged on [port] + opened as https. */
    data class CloudContainer(
        val name: String, val label: String, val url: String,
        val port: Int, val external: Boolean,
        val link: String,
    )
    /** One external provider console in the Providers group. */
    data class DashProvider(val label: String, val url: String)

    // ── wg-mesh/v1 model (data/mesh.json) — same fields as the SuperApp's ────
    data class MeshNode(
        val name: String,
        val role: String,
        val alias: String,
        val publicIp: String,
        val wgIp: String,
        val region: String,
        val provider: String,
        val os: String,
        val publicKeyFp: String,
        val portsPublic: List<String>,
        val wstunnelServer: Boolean,
        val wstunnelClient: Boolean,
    )
    data class MeshPeer(
        val from: String,
        val to: String,
        val allowedIps: List<String>,
        val keepalive: Int,
    )
    data class MeshTransport(
        val name: String,
        val label: String,
        val protocol: String,
        val port: Int,
        val endpoint: String,
        val primary: Boolean,
        val fallback: Boolean,
        val activePeers: Int,
        val useCase: String,
    )
    data class Mesh(
        val nodes: List<MeshNode>,
        val peers: List<MeshPeer>,
        val transports: List<MeshTransport>,
    )

    @Volatile private var cachedTopology: List<StackPanel>? = null
    @Volatile private var cachedObserv:   List<StackPanel>? = null
    @Volatile private var cachedMesh:     Mesh?             = null

    /** stack_topology of the carried c3 section — the Topology page's panels. */
    fun stackTopology(): List<StackPanel> =
        cachedTopology ?: parseStack(BuildConfig.UI_STACK_TOPOLOGY_B64).also { cachedTopology = it }

    /** stack_observability of the carried c3 section — the Observability page's panels. */
    fun stackObservability(): List<StackPanel> =
        cachedObserv ?: parseStack(BuildConfig.UI_STACK_OBSERV_B64).also { cachedObserv = it }

    fun tileColumns(): Int = BuildConfig.UI_TILE_COLUMNS.coerceAtLeast(1)

    private fun decode(b64: String): String =
        if (b64.isBlank()) "[]"
        else runCatching { String(Base64.decode(b64, Base64.NO_WRAP)) }.getOrDefault("[]")

    private fun parseStack(b64: String): List<StackPanel> {
        val sa = runCatching { JSONArray(decode(b64)) }.getOrDefault(JSONArray())
        val out = mutableListOf<StackPanel>()
        for (j in 0 until sa.length()) {
            val p = sa.optJSONObject(j) ?: continue
            val reposList = mutableListOf<RepoRef>()
            p.optJSONArray("repos")?.let { ra ->
                for (k in 0 until ra.length()) {
                    val r = ra.optJSONObject(k) ?: continue
                    reposList += RepoRef(
                        owner = r.optString("owner", ""),
                        repo  = r.optString("repo", ""),
                        label = r.optString("label", r.optString("repo", "")),
                    )
                }
            }
            val tilesList = mutableListOf<AggTile>()
            p.optJSONArray("tiles")?.let { ta ->
                for (k in 0 until ta.length()) {
                    val t = ta.optJSONObject(k) ?: continue
                    tilesList += AggTile(
                        id       = t.optString("id", t.optString("label", "")),
                        label    = t.optString("label", ""),
                        iconName = t.optString("icon", "ic_settings"),
                        target   = t.optString("target", ""),
                    )
                }
            }
            val dashGroupIds = mutableListOf<String>()
            p.optString("group", "").takeIf { it.isNotBlank() }?.let(dashGroupIds::add)
            p.optJSONArray("groups")?.let { ga ->
                for (m in 0 until ga.length()) ga.optString(m).takeIf { it.isNotBlank() }?.let(dashGroupIds::add)
            }
            val panelAnchors = mutableListOf<PanelAnchor>()
            p.optJSONArray("anchors")?.let { aa ->
                for (m in 0 until aa.length()) {
                    val a = aa.optJSONObject(m) ?: continue
                    val aid = a.optString("id", "")
                    if (aid.isBlank()) continue
                    panelAnchors += PanelAnchor(
                        id       = aid,
                        group    = a.optString("group", ""),
                        subgroup = a.optString("subgroup", ""),
                    )
                }
            }
            val scopeIds = mutableListOf<String>()
            p.optJSONArray("scopes")?.let { sc ->
                for (m in 0 until sc.length()) sc.optString(m).takeIf { it.isNotBlank() }?.let(scopeIds::add)
            }
            out.add(StackPanel(
                kind         = p.optString("kind", "placeholder"),
                title        = p.optString("title", ""),
                subtitle     = p.optString("subtitle", ""),
                collapsed    = p.optBoolean("collapsed", false),
                tiles        = tilesList,
                url          = p.optString("url", ""),
                iconName     = p.optString("icon", ""),
                repos        = reposList,
                dashGroupIds = dashGroupIds,
                scopes       = scopeIds,
                origin       = p.optString("origin", ""),
                stream       = p.optString("stream", ""),
                anchor       = p.optString("anchor", ""),
                anchors      = panelAnchors,
                source       = p.optString("source", ""),
                limit        = p.optInt("limit", 0),
            ))
        }
        return out
    }

    /** #1148 the container dashboards: the SAME document as the Table page (ContainersSource), one card per group, one
     *  sub-table per category, in the document's order. Not cached: the live copy may replace the baked one. */
    fun cloudServices(project: String? = null): CloudDash {
        val c = ContainersSource.current()
        val d = Declarations.containerProjects
        return dashOf(if (d.project(project) != null) ContainerProjects.scope(d, c, project!!) else c)
    }

    fun dashOf(c: ContainersModel.Catalog): CloudDash = CloudDash(c.groups.map { g ->
        CloudGroup(g.id, g.label, "", g.categories.map { cat ->
            CloudSub(cat.label, "", cat.names.mapNotNull { c.entry(it) }.map { e ->
                CloudContainer(e.name, e.name, e.dns, e.port ?: -1, false, "")
            })
        }, emptyList())
    })

    /** The wg-mesh/v1 snapshot — same parse as the SuperApp's Sections.mesh(). */
    fun mesh(): Mesh {
        cachedMesh?.let { return it }
        val json = String(Base64.decode(BuildConfig.MESH_JSON_B64, Base64.NO_WRAP))
        val root = org.json.JSONObject(json)

        val nodes = mutableListOf<MeshNode>()
        val nodesArr = root.optJSONArray("nodes") ?: org.json.JSONArray()
        for (i in 0 until nodesArr.length()) {
            val n = nodesArr.getJSONObject(i)
            val ports = mutableListOf<String>()
            n.optJSONArray("ports_public")?.let { pa ->
                for (j in 0 until pa.length()) ports.add(pa.getString(j))
            }
            nodes.add(
                MeshNode(
                    name           = n.getString("name"),
                    role           = n.optString("role", "spoke"),
                    alias          = n.optString("alias", ""),
                    publicIp       = n.optString("public_ip", ""),
                    wgIp           = n.optString("wg_ip", ""),
                    region         = n.optString("region", ""),
                    provider       = n.optString("provider", ""),
                    os             = n.optString("os", ""),
                    publicKeyFp    = n.optString("public_key_fp", ""),
                    portsPublic    = ports,
                    wstunnelServer = n.optBoolean("wstunnel_server", false),
                    wstunnelClient = n.optBoolean("wstunnel_client", false),
                )
            )
        }

        val peers = mutableListOf<MeshPeer>()
        val peersArr = root.optJSONArray("peers") ?: org.json.JSONArray()
        for (i in 0 until peersArr.length()) {
            val p = peersArr.getJSONObject(i)
            val allowed = mutableListOf<String>()
            p.optJSONArray("allowed_ips")?.let { aa ->
                for (j in 0 until aa.length()) allowed.add(aa.getString(j))
            }
            peers.add(
                MeshPeer(
                    from       = p.optString("from", ""),
                    to         = p.optString("to", ""),
                    allowedIps = allowed,
                    keepalive  = p.optInt("persistent_keepalive", 0),
                )
            )
        }

        val transports = mutableListOf<MeshTransport>()
        val tArr = root.optJSONArray("transports") ?: org.json.JSONArray()
        for (i in 0 until tArr.length()) {
            val t = tArr.getJSONObject(i)
            transports.add(
                MeshTransport(
                    name        = t.getString("name"),
                    label       = t.optString("label", t.getString("name")),
                    protocol    = t.optString("protocol", "udp"),
                    port        = t.optInt("port", 51820),
                    endpoint    = t.optString("endpoint", ""),
                    primary     = t.optBoolean("primary", false),
                    fallback    = t.optBoolean("fallback", false),
                    activePeers = t.optInt("active_peers", 0),
                    useCase     = t.optString("use_case", ""),
                )
            )
        }

        val out = Mesh(nodes, peers, transports)
        cachedMesh = out
        return out
    }

    /** Drawable-by-name resolution with the SuperApp's fallback chain (direct →
     *  ic_<slug> → trimmed trailing _<n> → ic_link_tile), cached per name. */
    fun iconResFor(ctx: Context, name: String): Int {
        iconResCache[name]?.let { return it }
        val res = ctx.resources
        val pkg = ctx.packageName
        val fallback by lazy { res.getIdentifier("ic_link_tile", "drawable", pkg) }

        val resolved: Int = when {
            name.isBlank() -> fallback
            else -> {
                var hit = res.getIdentifier(name, "drawable", pkg)
                if (hit == 0) {
                    val slug = name.removeSuffix(".svg").replace('-', '_').lowercase()
                    hit = res.getIdentifier("ic_$slug", "drawable", pkg)
                    var trimmed = slug
                    while (hit == 0 && trailingIndexRe.containsMatchIn(trimmed)) {
                        trimmed = trailingIndexRe.replace(trimmed, "")
                        hit = res.getIdentifier("ic_$trimmed", "drawable", pkg)
                    }
                }
                if (hit != 0) hit else fallback
            }
        }
        iconResCache[name] = resolved
        return resolved
    }
    private val iconResCache = mutableMapOf<String, Int>()
    private val trailingIndexRe = Regex("_\\d+$")
}
