package com.diegonmarcos.superapp.fleetdata.ui

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudlib.jsonviewer.JsonViewer
import com.diegonmarcos.superapp.fleetdata.provider.DatasetDiff
import com.diegonmarcos.superapp.fleetdata.provider.FleetDataEndpoint
import com.diegonmarcos.superapp.fleetdata.provider.FleetDataSchedule
import com.diegonmarcos.superapp.fleetdata.provider.Route
import java.text.DateFormat
import java.util.Date

/** Opens the Data page of [authority] (this app's provider). */
class FleetDataActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val authority = intent.getStringExtra(EXTRA_AUTHORITY) ?: run { finish(); return }
        setContent { MaterialTheme { FleetDataPage(authority, Modifier.fillMaxSize()) } }
    }

    companion object {
        const val EXTRA_AUTHORITY = "authority"
        fun open(ctx: Context, authority: String) =
            ctx.startActivity(Intent(ctx, FleetDataActivity::class.java).putExtra(EXTRA_AUTHORITY, authority).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun stamp(t: Long): String = if (t <= 0) "never" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t))
private fun kb(n: Long): String = if (n < 1024) "$n B" else "${n / 1024} KB"

/** The Data page: Datasets | Sync | Consumers | Diff, all read from this app's own provider store (no network here). */
@Composable
fun FleetDataPage(authority: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val ep = remember(authority) { FleetDataEndpoint.of(ctx, authority) }
    var tick by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    val entries = remember(tick) { ep.store.list() }
    Column(modifier) {
        TabRow(selectedTabIndex = tab) {
            listOf("Datasets", "Sync", "Consumers", "Diff").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i; open = null }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> {
                val o = open
                if (o != null) {
                    val text = Route.parse(o.split('/'))?.let { ep.store.read(it) }?.decodeToString().orEmpty()
                    TextButton(onClick = { open = null }) { Text("< $o") }
                    JsonViewer(text, Modifier.fillMaxSize())
                } else {
                    // Grouped by IPC endpoint (app/<pkg>, shared, index), read live from this provider's manifest + cache; nothing baked.
                    val manifest = remember(tick) { ep.server.manifest() }
                    val failed = remember(tick) { ep.status()?.failed?.keys ?: emptySet() }
                    val groups = remember(tick, entries, manifest) { FleetDataView.groups(entries, manifest, failed, authority) }
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { FleetDataSchedule.syncNow(ctx, authority); tick++ }) { Text("Sync all") }
                                TextButton(onClick = { tick++ }) { Text("Refresh") }
                            }
                            Text("index: ${FleetDataView.uri(authority, "index")}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        if (groups.isEmpty()) item {
                            Text("No dataset has been synced yet. Tap Sync all.", Modifier.padding(16.dp))
                        }
                        groups.forEach { grp ->
                            item {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Text(grp.endpoint, style = MaterialTheme.typography.titleSmall)
                                    Text(grp.endpointUri, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            items(grp.rows) { r ->
                                Column(Modifier.fillMaxWidth().clickable { open = r.path }.padding(horizontal = 24.dp, vertical = 6.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(r.dataset, style = MaterialTheme.typography.bodyMedium)
                                        Text(r.state.name.lowercase(), style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(r.uri, style = MaterialTheme.typography.bodySmall)
                                    Text("tier ${r.tier ?: "-"} | schemaVersion ${r.schemaVersion ?: "-"} | ${r.size?.let { kb(it) } ?: "-"} | ETag ${r.etag ?: "-"} | synced ${stamp(r.syncedAt)}",
                                        style = MaterialTheme.typography.bodySmall)
                                    Text("source ${r.source ?: "-"}", style = MaterialTheme.typography.bodySmall)
                                    Text("consumers ${r.consumers.joinToString().ifBlank { "-" }}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            1 -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val s = ep.status()
                Text("Authority: $authority", style = MaterialTheme.typography.bodySmall)
                Text("Source: ${s?.source?.ifBlank { null } ?: "not synced yet"}")
                Text("Last sync: ${stamp(s?.at ?: 0)}")
                Text("Updated: ${s?.updated?.size ?: 0} | not modified: ${s?.notModified?.size ?: 0} | failed: ${s?.failed?.size ?: 0}")
                s?.failed?.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall) }
                Text("Signature check: not enforced yet (verifier present, trust key pending).", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { FleetDataSchedule.syncNow(ctx, authority) }) { Text("Sync now") }
                    TextButton(onClick = { tick++ }) { Text("Refresh") }
                }
            }
            2 -> {
                val m = remember(tick) { ep.server.manifest() }
                val log = remember(tick) { ep.log.rows() }
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    item { Text("May read (manifest)", style = MaterialTheme.typography.titleSmall) }
                    items(m.shared.entries.toList()) { (k, v) -> Text("shared/$k: ${v.consumers.joinToString()}", style = MaterialTheme.typography.bodySmall) }
                    items(m.apps.entries.toList()) { (pkg, ds) -> Text("app/$pkg: ${ds.joinToString()}", style = MaterialTheme.typography.bodySmall) }
                    item { Text("Did read (access log)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp)) }
                    items(log) { r -> Text("${stamp(r.at)} ${r.caller} ${r.path} ${if (r.granted) "ok" else "DENIED"}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            else -> {
                var diffOf by remember { mutableStateOf<String?>(null) }
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    items(entries) { e ->
                        val r = Route.parse(e.path.split('/'))
                        val prev = r?.let { ep.store.previous(it) }
                        Text(e.path + if (prev == null) " (no previous version)" else "", Modifier.clickable(enabled = prev != null) { diffOf = e.path }.padding(vertical = 6.dp))
                        if (diffOf == e.path && r != null && prev != null) {
                            val d = DatasetDiff.diff(prev.decodeToString(), ep.store.read(r)?.decodeToString().orEmpty())
                            if (d.isEmpty()) Text("identical", style = MaterialTheme.typography.bodySmall)
                            d.take(200).forEach { c -> Text("${c.kind.name.first()} ${c.path}  ${c.before?.take(40) ?: ""} -> ${c.after?.take(40) ?: ""}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}
