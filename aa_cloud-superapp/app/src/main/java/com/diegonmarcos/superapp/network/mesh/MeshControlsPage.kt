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
 * #877 Controls: every declared control of the `controls` tab, grouped by its declared `group`. Each one
 * acts through its `engine` id ([MeshStore.run] -> the existing engine paths). A control the engine
 * cannot honour is drawn DISABLED with its declared `unsupported` reason beneath it; one the engine can
 * honour but cannot right now (engine absent, no key) is disabled with that reason. None is silent.
 */
@Composable
fun MeshControlsPage(store: MeshStore, modifier: Modifier = Modifier, embedded: Boolean = false, sub: MeshDecl.Subpage? = null) {
    val page = store.decl.page("controls")
    val seeds = remember0 { MeshDecl.seeds() }
    Column(
        modifier.pageBody(embedded).testTag(MeshTags.page("controls"))
            .padding(MeshDensity.dp(MeshDensity.S8)),
    ) {
        var group = ""
        for (c in page?.controls.orEmpty().filter { sub == null || sub.shows(it.id) }) {   // #1928 a subpage draws only the controls it lists
            if (c.group != group) { group = c.group; if (group.isNotBlank()) MHeader(declLabel("group", group, group)) }
            ControlRow(store, c, seeds)
        }
    }
}

@Composable
private fun <T> remember0(f: () -> T): T = androidx.compose.runtime.remember { f() }

@Composable
fun ControlRow(store: MeshStore, c: MeshDecl.Control, seeds: Map<String, String>) {
    val label = declLabel("ctl", c.id, c.label)
    val s = store.snapshot
    val cfg = s?.cfg
    val block = store.blockOf(c)
    val why: String? = when {
        !c.honoured -> declLabel("why", c.id, c.unsupported)
        block == Block.ENGINE_MISSING -> stringResource(R.string.mesh_why_engine)
        block == Block.NO_KEY -> stringResource(R.string.mesh_need_key)
        else -> null
    }
    val usable = why == null
    val tag = Modifier.testTag(MeshTags.control(c.id))
    when (c.kind) {
        "switch" -> {
            val checked = when (c.engine) {
                "state.set" -> store.connected()
                "lockdown.set" -> store.aoView.lockdown == true
                "alwayson.set" -> store.aoView.on == true
                "startup.boot" -> store.intent.onBoot
                "startup.wifi" -> store.intent.onWifi
                "startup.mobile" -> store.intent.onMobile
                else -> false
            }
            // #1120 The kill switch reports Android's real lockdown, and says why when the privileged channel cannot change it.
            val extra = if (c.engine == "lockdown.set") listOfNotNull(when (store.aoView.lockdown) {
                true -> stringResource(R.string.mesh_lockdown_on); false -> stringResource(R.string.mesh_lockdown_off); null -> stringResource(R.string.mesh_lockdown_unknown)
            }, store.aoView.reason.ifBlank { null }).joinToString(" ") else null
            SwitchLine(label, checked, { on -> store.run(c.engine, if (on) "on" else "off") }, tag, enabled = usable && c.engine.isNotBlank() && c.honoured, reason = listOfNotNull(why, extra).joinToString(" ").ifBlank { null })
            if (c.engine == "state.set" && store.alwaysOn != null) Reason(stringResource(if (store.alwaysOn == true) R.string.mesh_always_on else R.string.mesh_not_always_on))
        }
        "action" -> Column(tag) {
            MButton(label, { store.run(c.engine) }, Modifier.padding(vertical = MeshDensity.dp(MeshDensity.S2)), enabled = usable)
            if (why != null) Reason(why)
        }
        "choice" -> {
            val options: List<Pair<String, String>>
            val unavailable: Map<String, String>
            val selected: String
            if (c.engine == "fleetdns.preset") {
                options = store.dnsChoices.map { it.first to it.second }
                unavailable = store.dnsChoices.filter { it.third.isNotBlank() }.associate { it.first to it.third }
                selected = store.dnsPreset.ifBlank { MeshDecl.defaultOf(c, seeds) }
            } else if (c.engine == "transport.mode") {
                options = c.choices.map { it to declLabel("opt", it, it) }
                unavailable = emptyMap()
                selected = store.transport?.mode?.ifBlank { null } ?: c.default
            } else {
                options = c.choices.map { it to declLabel("opt", it, it) }
                unavailable = emptyMap()
                selected = store.view[c.id] ?: c.default
            }
            ChoiceLine(label, options, selected, { store.run(c.engine, it) }, tag, enabled = usable, unavailable = unavailable)
            if (why != null) Reason(why)
        }
        "number", "text" -> {
            val current = when (c.engine) {
                "prefs.mtu" -> cfg?.mtu
                "prefs.keepalive" -> cfg?.peers?.firstOrNull()?.keepalive
                "prefs.tunnelName" -> cfg?.tunnelName
                "prefs.address" -> cfg?.address
                "prefs.listenPort" -> cfg?.listenPort
                else -> null
            }.orEmpty()
            val default = MeshDecl.defaultOf(c, seeds)
            FieldLine(label, tag) {
                Row(horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S6)), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        MField(current, { store.run(c.engine, it) }, enabled = usable, numeric = c.kind == "number", hint = default)
                    }
                    if (default != current) MButton(stringResource(R.string.mesh_reset), { store.run(c.engine, default) }, Modifier.testTag(MeshTags.control(c.id) + ":reset"), enabled = usable)
                }
            }
            if (c.min != null && c.max != null) Reason(stringResource(R.string.mesh_range, c.min, c.max))
            if (why != null) Reason(why)
        }
        "secret" -> FieldLine(label, tag) {
            Column {
                // Two secrets live here: the interface private key and the TLS-443 relay's key. Neither is ever shown.
                val relay = c.engine == "prefs.relayKey"
                val held = if (relay) store.hasRelayKey else store.hasKey
                MField("", { store.run(c.engine, it) }, enabled = usable, secret = true,
                    hint = stringResource(if (held) R.string.mesh_key_stored else R.string.mesh_key_paste))
                Reason(stringResource(if (relay) R.string.mesh_relay_key_text else R.string.mesh_key_text))
                if (why != null) Reason(why)
            }
        }
        else -> MText(stringResource(R.string.mesh_row_unknown, c.id), size = MeshDensity.T_META, muted = true)
    }
}
