package com.diegonmarcos.cloudc3.cloud
/* #648 MOVED from aa_cloud-superapp with the c3 pages; the package, the R/BuildConfig it resolves against, and the mesh accessor (Sections.mesh -> Stacks.mesh) are the only edits.
   2026-10-16 owner decision: all apps must be Compose — the renderer is a composable. */
import com.diegonmarcos.cloudc3.R

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * Shared WG-mesh renderer — the canonical wg-mesh/v1 snapshot
 * (data/mesh.json via [Stacks.mesh]) grouped by **transport**
 * (wg0 UDP direct vs wg0-tcp wstunnel over WSS).
 *
 * Extracted out of [C3MeshFragment] so the SAME rendering can be folded
 * into the single WireGuard page (Configs → WireGuard) — "all infos on one
 * page". It is a composable that emits into the caller's column, so it nests
 * cleanly inside any scrolling parent without scroll-in-scroll conflicts.
 */
object MeshView {

    private val NODE_HUB = Color(0xFF2E7D32)      // green
    private val NODE_CLIENT = Color(0xFFEF6C00)   // orange
    private val NODE_OTHER = Color(0xFF1565C0)    // blue

    /** One-line summary for the page header (nodes · peers · transports). */
    fun statusText(ctx: Context): String {
        val mesh = Stacks.mesh()
        return ctx.getString(
            R.string.mesh_status_v1,
            mesh.nodes.size, mesh.peers.size, mesh.transports.size,
        )
    }

    /** The per-transport cards (nodes + peerings). */
    @Composable
    fun Render() {
        val mesh = Stacks.mesh()

        // wstunnel users = clients that go via wg0-tcp (laptop/phone).
        // Everyone else uses wg0 direct UDP.
        val tcpNodeNames = mesh.nodes.filter { it.wstunnelClient }.map { it.name }.toSet()
        val tcpNodes     = mesh.nodes.filter { it.wstunnelClient }
        val udpNodes     = mesh.nodes.filter { !it.wstunnelClient }

        val tcpPeers = mesh.peers.filter { it.from in tcpNodeNames || it.to in tcpNodeNames }
        val udpPeers = mesh.peers.filterNot { it.from in tcpNodeNames || it.to in tcpNodeNames }

        for (transport in mesh.transports) {
            val (nodesForT, peersForT) = when (transport.name) {
                "wg0"     -> udpNodes to udpPeers
                "wg0-tcp" -> tcpNodes to tcpPeers
                else      -> mesh.nodes to mesh.peers
            }
            TransportCard(transport, nodesForT, peersForT, mesh)
        }
    }

    @Composable
    private fun TransportCard(
        t: Stacks.MeshTransport,
        nodes: List<Stacks.MeshNode>,
        peers: List<Stacks.MeshPeer>,
        mesh: Stacks.Mesh? = null,
    ) {
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxWidth().padding(bottom = 14.dp).testTag("c3:mesh:${t.name}")) {
            Column(Modifier.fillMaxWidth().background(p.surfaceSelected, RoundedCornerShape(14.dp)).padding(14.dp)) {
                Text("${t.name} · ${t.label}", color = p.accent, style = MaterialTheme.typography.titleMedium)

                val role = when {
                    t.primary  -> "primary"
                    t.fallback -> "fallback"
                    else       -> "—"
                }
                Text(
                    buildString {
                        append(t.protocol.uppercase()).append('/').append(t.port)
                        append("  →  ").append(t.endpoint).append('\n')
                        append(role).append(" · ").append(t.activePeers).append(" active peers · ")
                        append(nodes.size).append(" nodes · ").append(peers.size).append(" peerings\n")
                        if (t.useCase.isNotBlank()) append(t.useCase)
                    },
                    color = p.textPrimary, style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp).alpha(0.85f),
                )
                // #1955 A the snapshot's health line (mesh.json health): a blank field prints as not served, never as nothing
                if (mesh != null) Text("health ${mesh.healthStatus.ifBlank { "not served" }} · last snapshot ${mesh.lastSnapshotAt.ifBlank { "not served" }}",
                    color = p.textSecondary, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 2.dp).testTag("c3:mesh:health:${t.name}"))
            }

            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                // ── nodes ────────────────────────────────────────────────
                if (nodes.isNotEmpty()) {
                    Subheader(stringResource(R.string.mesh_section_nodes) + " · ${nodes.size}")
                    for (n in nodes) NodeRow(n)
                }

                // ── peers ────────────────────────────────────────────────
                if (peers.isNotEmpty()) {
                    Subheader(stringResource(R.string.mesh_section_peers) + " · ${peers.size}")
                    for (peer in peers) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Line("${peer.from}  →  ${peer.to}", title = true)
                            Line("AllowedIPs: ${peer.allowedIps.joinToString(", ")}")
                            Line("keepalive ${peer.keepalive}s", dim = true)
                        }
                    }
                }

                // ── routes (#1955 A: mesh.json routes via this transport) ──
                val routes = mesh?.routes.orEmpty().filter { it.viaTransport == t.name }
                if (routes.isNotEmpty()) {
                    Subheader("Routes · ${routes.size}")
                    for (r in routes) {
                        val alias = mesh?.nodes?.firstOrNull { it.name == r.srcNode }?.alias.orEmpty()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("c3:mesh:route:${r.srcNode}")) {
                            Line("${alias.ifBlank { r.srcNode }}  →  ${r.dstSubnet}", title = true)
                            Line("via ${r.viaTransport}", dim = true)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun NodeRow(n: Stacks.MeshNode) {
        val p = LocalKitPalette.current
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(
                when (n.role) { "hub" -> NODE_HUB; "client" -> NODE_CLIENT; else -> NODE_OTHER }, CircleShape))
            Column(Modifier.weight(3f).padding(start = 10.dp)) {
                Text("${n.name} · ${n.role}", color = p.textPrimary, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${n.alias} · ${n.provider}/${n.region}", color = p.textSecondary,
                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            Text(n.wgIp, color = p.textPrimary, style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(2f))
            Column(Modifier.weight(3f)) {
                Text(n.publicIp, color = p.textPrimary, style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        if (n.portsPublic.isNotEmpty()) append(n.portsPublic.joinToString(" "))
                        if (n.wstunnelServer) append(" · wstunnel↑")
                        if (n.wstunnelClient) append(" · wstunnel↓")
                        if (n.os.isNotBlank()) append(" · ").append(n.os)
                    },
                    color = p.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                )
            }
        }
    }

    @Composable
    private fun Subheader(text: String) {
        val p = LocalKitPalette.current
        Text(text, color = p.accent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 5.dp))
    }

    @Composable
    private fun Line(text: String, title: Boolean = false, dim: Boolean = false) {
        val p = LocalKitPalette.current
        Text(text, color = p.textPrimary, fontFamily = FontFamily.Monospace,
            style = if (title) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            fontSize = if (title) 13.sp else 11.sp,
            modifier = if (dim) Modifier.alpha(0.6f) else Modifier)
    }
}
