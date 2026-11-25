package com.diegonmarcos.superapp.network.mesh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.superapp.R

/**
 * #877 Status: the tunnel at a glance. The rows and their ORDER are `ui.mesh_page.pages[status].rows`
 * (#1797: lights first, then the per-mesh table, then the full breakdown, then the profiler and the Log);
 * [StatusRow] draws one by id, and an id it has no drawing for says so on the page rather than vanishing.
 */
@Composable
fun MeshStatusPage(store: MeshStore, modifier: Modifier = Modifier, embedded: Boolean = false) {
    val s = store.snapshot
    val rows = store.decl.page("status")?.rows.orEmpty()
    Column(
        modifier.pageBody(embedded).testTag(MeshTags.page("status"))
            .padding(MeshDensity.dp(MeshDensity.S8)),
    ) {
        if (s == null) MText(stringResource(R.string.mesh_reading), muted = true)
        else if (store.decl.sections.isEmpty()) for (id in rows) StatusRow(id, store, s)
        else for (sec in store.decl.sections) {
            // #1900 Status (the current state, once), Profiler (measured results), Prober (run controls and raw per-probe detail)
            MeshSectionTitle(sec.id, sec.label)
            for (id in sec.rows) StatusRow(id, store, s)
        }
    }
}

@Composable
private fun StatusRow(id: String, store: MeshStore, s: MeshSnapshot) {
    val host = store.host
    val cfg = s.cfg
    val tag = MeshTags.row(id)
    when (id) {
        "export" -> Row(
            Modifier.testTag(tag).padding(bottom = MeshDensity.dp(MeshDensity.S6)),
            horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S6)),
        ) {
            for (cid in listOf("status_copy_all", "status_export_all", "status_export_md")) store.decl.page("status")?.control(cid)?.let { c ->
                MButton(declLabel("ctl", c.id, c.label), { store.run(c.engine) }, Modifier.testTag(MeshTags.control(c.id)))
            }
        }
        "reachability" -> {
            MHeader(stringResource(R.string.mesh_row_reachability))
            if (store.reach.isEmpty()) MText(stringResource(R.string.mesh_reach_none, store.decl.reachHosts.size), Modifier.testTag(tag), size = MeshDensity.T_META, muted = true)
            store.reach.forEachIndexed { i, r ->
                MText(store.decl.reachLabels[r.host]?.let { "$it · ${r.host}" } ?: r.host, Modifier.testTag(if (i == 0) tag else "mesh:reach:${r.host}"), size = MeshDensity.T_BODY, bold = true, mono = true)
                for (st in r.steps) KvRow(st.id, stringResource(probeVerdict(st.ok)) + " · ${st.ms} ms · " + st.detail, tag = "mesh:reach:${r.host}:${st.id}")
            }
            store.decl.page("status")?.control("reach_run")?.let { c ->
                MButton(declLabel("ctl", c.id, c.label), { store.run(c.engine) },
                    Modifier.testTag(MeshTags.control(c.id)).padding(vertical = MeshDensity.dp(MeshDensity.S4)), enabled = !store.busy)
            }
        }
        "vpn" -> VpnControls(store, s, tag)
        "state" -> {
            val word = when {
                !s.engineInstalled -> stringResource(R.string.mesh_state_unknown)
                !s.up -> stringResource(R.string.mesh_state_down)
                s.light == Light.UP -> stringResource(R.string.mesh_state_up)
                else -> stringResource(R.string.mesh_state_up_nohs)
            }
            Row(
                Modifier.testTag(tag).padding(vertical = MeshDensity.dp(MeshDensity.S4)),
                horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S8)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Light(lightOf(s.light), word)
                MText(word, size = MeshDensity.T_TITLE, bold = true)
                MText(cfg.tunnelName, size = MeshDensity.T_META, mono = true, muted = true)
            }
            if (!s.engineInstalled) Reason(stringResource(R.string.mesh_status_limits))
            if (store.pending) {
                Row(
                    Modifier.padding(vertical = MeshDensity.dp(MeshDensity.S4)),
                    horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S8)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MText(stringResource(R.string.mesh_pending), Modifier.weight(1f), size = MeshDensity.T_META)
                    MButton(stringResource(R.string.mesh_reconnect), { store.run("state.reconnect") }, Modifier.testTag("mesh:pending:reconnect"))
                }
            }
        }
        "interface" -> {
            MHeader(stringResource(R.string.mesh_row_interface))
            KvRow(stringResource(R.string.mesh_k_address), cfg.address.ifBlank { "-" }, tag = tag,
                onCopy = { host.copy("address", cfg.address) })
            KvRow(stringResource(R.string.mesh_k_mtu), cfg.mtu.ifBlank { stringResource(R.string.mesh_default) })
            if (cfg.listenPort.isNotBlank()) KvRow(stringResource(R.string.mesh_k_listen), cfg.listenPort)
        }
        "pubkey" -> KvRow(
            stringResource(R.string.mesh_k_pubkey),
            cfg.publicKey.ifBlank { stringResource(R.string.mesh_no_key) },
            tag = tag,
            onCopy = if (cfg.publicKey.isBlank()) null else { { host.copy("public key", cfg.publicKey) } },
        )
        "endpoint" -> {
            MHeader(stringResource(R.string.mesh_row_endpoint))
            if (cfg.peers.isEmpty()) MText(stringResource(R.string.mesh_no_peers), Modifier.testTag(tag), size = MeshDensity.T_META, muted = true)
            cfg.peers.forEachIndexed { i, p ->
                KvRow(p.name.ifBlank { stringResource(R.string.mesh_peer_n, i + 1) }, p.endpoint.ifBlank { "-" },
                    tag = if (i == 0) tag else "")
            }
        }
        "transport" -> {
            // Which rung of the fallback ladder carries the tunnel, and the last Test fallbacks, rung by rung.
            MHeader(stringResource(R.string.mesh_row_transport))
            val tv = store.transport
            val path = tv?.path.orEmpty()
            KvRow(stringResource(R.string.mesh_k_path), if (path.isBlank()) stringResource(R.string.mesh_path_unknown) else declLabel("path", path, path), tag = tag)
            KvRow(stringResource(R.string.mesh_k_path_mode), declLabel("opt", tv?.mode.orEmpty(), tv?.mode.orEmpty()))
            if (!tv?.detail.isNullOrBlank()) Reason(tv?.detail.orEmpty())
            if (!tv?.relay.isNullOrBlank()) KvRow(stringResource(R.string.mesh_k_relay), tv?.relay.orEmpty())
            for (pr in store.probes) KvRow(declLabel("probe", pr.id, pr.id),
                stringResource(probeVerdict(pr.ok)) + " · " + pr.detail, tag = "mesh:probe:${pr.id}")
            MButton(stringResource(R.string.mesh_ctl_test_fallbacks), { store.run("transport.test") },
                Modifier.testTag("mesh:transport:test").padding(vertical = MeshDensity.dp(MeshDensity.S4)), enabled = !store.busy)
        }
        "handshake" -> {
            MHeader(stringResource(R.string.mesh_row_handshake))
            KvRow(stringResource(R.string.mesh_k_newest), handshakeText(s.newestHandshakeAgeS, s.up, s.engineInstalled), tag = tag)
            // #1900 the per-peer handshake and health live once, in the Peers row (peerFields handshake + health)
        }
        "traffic" -> {
            MHeader(stringResource(R.string.mesh_row_traffic))
            KvRow(stringResource(R.string.mesh_k_down), MeshFormat.bytes(s.rx) + "  " + MeshFormat.rate(s.rxRate), tag = tag)
            KvRow(stringResource(R.string.mesh_k_up), MeshFormat.bytes(s.tx) + "  " + MeshFormat.rate(s.txRate))
            // #1900 the per-peer rates live once, in the Peers row (peerFields rx + tx carry the rates)
        }
        "latency" -> {
            MHeader(stringResource(R.string.mesh_row_latency))
            KvRow(stringResource(R.string.mesh_k_hub), MeshFormat.latency(s.hubLatencyMs), tag = tag)
            // #1900 the per-peer latency lives once, in the Peers row (peerFields latency)
            Reason(stringResource(R.string.mesh_latency_how))
        }
        "dns" -> {
            MHeader(stringResource(R.string.mesh_row_dns))
            KvRow(stringResource(R.string.mesh_k_dns), cfg.dnsInEffect.ifBlank { "-" }, tag = tag)
            KvRow(stringResource(R.string.mesh_k_fleet_dns), cfg.dns.ifBlank { "-" })
        }
        "profile" -> {
            MHeader(stringResource(R.string.mesh_row_profile))
            KvRow(stringResource(R.string.mesh_k_profile), cfg.activeProfile.substringAfterLast('/').ifBlank { stringResource(R.string.mesh_profile_none) }, tag = tag)
            KvRow(stringResource(R.string.mesh_k_provider), store.provider.ifBlank { "-" })
        }
        "cloudflare" -> {
            MHeader(stringResource(R.string.mesh_row_cloudflare))
            val active = store.cf.active
            val one = store.decl.cloudflare.one
            KvRow(stringResource(R.string.mesh_k_cf_transport), stringResource(when (active) {
                one.profileName -> R.string.mesh_cf_t_one
                store.decl.cloudflare.warp.profileName -> R.string.mesh_cf_t_warp
                else -> R.string.mesh_cf_t_mesh
            }), tag = tag)
            KvRow(stringResource(R.string.mesh_k_cf_warp), stringResource(if (store.cf.warp) R.string.mesh_cf_registered else R.string.mesh_cf_not_registered))
            KvRow(stringResource(R.string.mesh_k_cf_one), oneStateText(store))
        }
        // #1797 additions (MeshProbeRows.kt): the lights on top, the per-mesh table, every peer field,
        // Cloud C3's profiler and the complete Log. Nothing above was removed or shortened.
        "lights" -> MeshLightsRow(store, s, tag)
        "meshes" -> MeshTableRow(store, tag)
        "peers" -> MeshPeersRow(store, s, tag)
        "profiler" -> MeshProfilerRow(store, tag)
        "prober" -> MeshProberRow(store, tag)
        "log" -> MeshStatusLogRow(store, tag)
        else -> MText(stringResource(R.string.mesh_row_unknown, id), Modifier.testTag(tag), size = MeshDensity.T_META, muted = true)
    }
}

/**
 * #1120 The top of Status: Connect / Disconnect / Reconnect and the Always-on switch. Connect arms the
 * auto-reconnect (the supervisor reconnects through network changes, drops and reboots), Disconnect stops it
 * and stays stopped until Connect. Always-on is Android's own setting, read for real and changed through the
 * privileged channel; with none armed it says why and the switch opens Android's VPN settings.
 */
@Composable
private fun VpnControls(store: MeshStore, s: MeshSnapshot, tag: String) {
    val page = store.decl.page("status")
    val up = s.up
    Column(Modifier.testTag(tag).padding(bottom = MeshDensity.dp(MeshDensity.S6))) {
        Row(horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S6)), verticalAlignment = Alignment.CenterVertically) {
            for (id in listOf("vpn_connect", "vpn_disconnect", "vpn_reconnect")) page?.control(id)?.let { c ->
                val block = store.blockOf(c)
                val applies = if (id == "vpn_connect") !up else if (id == "vpn_disconnect") up || store.intent.wanted else true
                val enabled = !store.busy && block == null && applies
                MButton(declLabel("ctl", c.id, c.label), { store.run(c.engine) }, Modifier.testTag(MeshTags.control(c.id)), enabled = enabled,
                    emphasised = (id == "vpn_connect" && !up) || (id == "vpn_disconnect" && up))
            }
        }
        Reason(stringResource(if (store.intent.wanted) R.string.mesh_vpn_wanted else R.string.mesh_vpn_unwanted), Modifier.testTag("mesh:vpn:intent"))
        if (store.supervisorNote.isNotBlank()) Reason(stringResource(R.string.mesh_vpn_auto, store.supervisorNote), Modifier.testTag("mesh:vpn:note"))
        page?.control("vpn_always_on")?.let { c ->
            val ao = store.aoView
            SwitchLine(declLabel("ctl", c.id, c.label), ao.on == true, { on -> store.run(c.engine, if (on) "on" else "off") },
                Modifier.testTag(MeshTags.control(c.id)), enabled = !store.busy && s.engineInstalled,
                reason = stringResource(when (ao.on) { true -> R.string.mesh_ao_on; false -> R.string.mesh_ao_off; null -> R.string.mesh_ao_unknown }) +
                    (if (ao.reason.isNotBlank()) " " + ao.reason else ""))
            if (!ao.canChange) MButton(stringResource(R.string.mesh_ctl_vpn_settings), { store.run("nav.vpnSettings") }, Modifier.testTag("mesh:vpn:settings"))
        }
    }
}

/** pass / fail / not testable now, for one Test fallbacks rung. */
internal fun probeVerdict(ok: Boolean?): Int = when (ok) {
    true -> R.string.mesh_probe_pass
    false -> R.string.mesh_probe_fail
    null -> R.string.mesh_probe_skip
}

@Composable
internal fun handshakeText(ageS: Long?, up: Boolean, engine: Boolean): String = when {
    !engine -> stringResource(R.string.mesh_unknown)
    !up -> "-"
    ageS == null -> stringResource(R.string.mesh_hs_none)
    else -> stringResource(R.string.mesh_hs_ago, MeshFormat.age(ageS))
}

@Composable
internal fun healthWord(h: Health): String = stringResource(
    when (h) {
        Health.FRESH -> R.string.mesh_h_fresh
        Health.AGING -> R.string.mesh_h_aging
        Health.STALE -> R.string.mesh_h_stale
        Health.NONE -> R.string.mesh_h_none
        Health.DOWN -> R.string.mesh_h_down
        Health.UNKNOWN -> R.string.mesh_unknown
    },
)

@Composable
internal fun oneStateText(store: MeshStore): String = when (com.diegonmarcos.superapp.network.CloudflareWarp.oneState(store.decl.cloudflare, store.cf.one)) {
    com.diegonmarcos.superapp.network.CloudflareWarp.OneState.WAITING -> stringResource(R.string.mesh_cf_one_waiting, store.decl.cloudflare.one.waitingOn)
    com.diegonmarcos.superapp.network.CloudflareWarp.OneState.READY -> stringResource(R.string.mesh_cf_one_ready, store.decl.cloudflare.one.team)
    com.diegonmarcos.superapp.network.CloudflareWarp.OneState.ENROLLED -> stringResource(R.string.mesh_cf_one_enrolled)
}
