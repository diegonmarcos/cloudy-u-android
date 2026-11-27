package com.diegonmarcos.cloudc3.cloud
/* #648 MOVED from aa_cloud-superapp with the c3 pages; the package,
   the R/BuildConfig it resolves against are the only edits. */
import com.diegonmarcos.cloudc3.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudc3.pages.c3KitPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * C3 · WG Mesh — renders the canonical wg-mesh/v1 snapshot
 * (data/mesh.json) grouped by **transport**, matching the user's
 * mental model of "WG0 vs WG-PUBLIC":
 *
 *   wg0       UDP/51820 direct                  → VMs (hub + spokes)
 *   wg0-tcp   wstunnel TCP/443 over WSS         → roaming clients
 *
 * Each transport card shows:
 *   • Header  protocol/port + endpoint + primary/fallback + use case
 *   • Nodes   that use THIS transport (status dot · name · region
 *              · WG-IP · public IP · public ports · OS)
 *   • Peers   declared from→to relationships involving any node above
 *
 * Per the canonical spec, "wg-public" is NOT a separate WG network —
 * it's the wstunnel TCP/443 transport of the same wg0 network. The
 * UI groups by transport so it reads as two networks (which is how
 * the user perceives them).
 *
 * 2026-10-16 owner decision: all apps must be Compose.
 */
class C3MeshFragment : KitComposeFragment() {

    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        MeshBody()
    }

    companion object { fun newInstance() = C3MeshFragment() }
}

/** #1955 A The WG Mesh page body (lifted from [C3MeshFragment] so the Network viewer's Table mode is the same composable). */
@Composable
fun MeshBody() {
        val p = LocalKitPalette.current
        val ctx = LocalContext.current
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text(stringResource(R.string.mesh_title), color = p.textPrimary, style = MaterialTheme.typography.headlineSmall)
            // All rendering lives in the shared [MeshView] so the same
            // table can be folded into the single WireGuard page.
            Text(MeshView.statusText(ctx), color = p.textSecondary, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
            MeshView.Render()
        }
}
