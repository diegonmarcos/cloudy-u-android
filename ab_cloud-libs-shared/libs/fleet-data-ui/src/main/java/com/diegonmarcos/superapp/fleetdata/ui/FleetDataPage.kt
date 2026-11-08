package com.diegonmarcos.superapp.fleetdata.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.cloudlib.jsonviewer.JsonViewer
import com.diegonmarcos.superapp.fleetdata.provider.DatasetDiff
import com.diegonmarcos.superapp.fleetdata.provider.FleetDataEndpoint
import com.diegonmarcos.superapp.fleetdata.provider.FleetDataSchedule
import com.diegonmarcos.superapp.fleetdata.provider.Route
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.GroupTitle
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitFileTree
import com.diegonmarcos.superapp.uikit.KitFileTreeInk
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitSearchBar
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatePill
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.KitTextButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.uikit.kitFileTree
import java.text.DateFormat
import java.util.Date

/** Opens the Data page of [authority] (this app's provider). Hosts the page in the kit theme (the Account host brings its own). */
class FleetDataActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val authority = intent.getStringExtra(EXTRA_AUTHORITY) ?: run { finish(); return }
        setContent {
            val cs = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            val palette = KitPalette(cs.surfaceVariant, cs.secondaryContainer, cs.onSurface, cs.onSurfaceVariant, cs.primary, cs.outlineVariant, cs.onPrimary)
            CloudKitTheme(palette) { Box(Modifier.fillMaxSize().background(cs.surface)) { FleetDataPage(authority, Modifier.fillMaxSize()) } }
        }
    }

    companion object {
        const val EXTRA_AUTHORITY = "authority"
        fun open(ctx: Context, authority: String) =
            ctx.startActivity(Intent(ctx, FleetDataActivity::class.java).putExtra(EXTRA_AUTHORITY, authority).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun stamp(t: Long): String = if (t <= 0) "never" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t))

private fun copy(ctx: Context, label: String, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, "Copied $label", Toast.LENGTH_SHORT).show()
}

/** Everything the Datasets tab reads in one pass; a throw while reading becomes the page's error state. */
private class Loaded(val groups: List<EndpointGroup>, val rows: List<DatasetRow>, val previous: Set<String>)

/** The Data page: Datasets | Sync | Consumers | Diff, all read from this app's own provider store (no network here). Needs a CloudKitTheme above it. */
@Composable
fun FleetDataPage(authority: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val ep = remember(authority) { FleetDataEndpoint.of(ctx, authority) }
    var tick by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    var diffOf by remember { mutableStateOf<String?>(null) }
    val entries = remember(tick) { runCatching { ep.store.list() } }
    Column(modifier) {
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = p.textPrimary) {
            listOf("Datasets", "Sync", "Consumers", "Diff").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i; open = null }, text = { Text(t, fontSize = KitDensity.title) },
                    selectedContentColor = p.accent, unselectedContentColor = p.textSecondary, modifier = Modifier.testTag("fleetdata:tab:$i"))
            }
        }
        when (tab) {
            0 -> DatasetsTab(ctx, ep, authority, tick, entries.getOrNull(), entries.exceptionOrNull(), open, { open = it },
                { tick++ }, { diffOf = it; tab = 3 })
            1 -> SyncTab(ctx, ep, authority) { tick++ }
            2 -> ConsumersTab(ep, tick)
            else -> DiffTab(ep, entries.getOrNull().orEmpty(), diffOf) { diffOf = it }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun DatasetsTab(
    ctx: Context, ep: FleetDataEndpoint, authority: String, tick: Int,
    entries: List<com.diegonmarcos.superapp.fleetdata.provider.FleetDataStore.Entry>?, failure: Throwable?,
    open: String?, onOpen: (String?) -> Unit, onRefresh: () -> Unit, onDiff: (String) -> Unit,
) {
    val p = LocalKitPalette.current
    if (open != null) {
        val text = Route.parse(open.split('/'))?.let { ep.store.read(it) }?.decodeToString().orEmpty()
        KitTextButton(onClick = { onOpen(null) }, modifier = Modifier.padding(horizontal = KitDensity.medium)) { Text("< $open") }
        JsonViewer(text, Modifier.fillMaxSize())
        return
    }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<SyncState?>(null) }
    var bucket by remember { mutableStateOf(FileBucket.ALL) }
    var queued by remember { mutableStateOf(false) }
    // The set of OPEN folder keys; null = "not touched yet", drawn as fully expanded. Collapse/Expand all swap it wholesale.
    var openFolders by remember { mutableStateOf<Set<String>?>(null) }
    // The tapped file whose per-file action sheet is showing (mirrors StoreInspect's ModalBottomSheet over the page).
    var sheetFor by remember { mutableStateOf<DatasetRow?>(null) }
    val loaded = remember(tick, entries) {
        if (entries == null) null else runCatching {
            // Grouped by IPC endpoint (app/<pkg>, shared, index), read live from this provider's manifest + cache; nothing baked.
            val manifest = ep.server.manifest()
            val failed = ep.status()?.failed?.keys ?: emptySet()
            val groups = FleetDataView.groups(entries, manifest, failed, authority)
            Loaded(groups, groups.flatMap { it.rows }, entries.map { it.path }.filter { path -> Route.parse(path.split('/'))?.let { ep.store.previous(it) } != null }.toSet())
        }
    }
    val error = failure ?: loaded?.exceptionOrNull()
    val data = loaded?.getOrNull()
    val now = remember(tick, queued) { System.currentTimeMillis() }
    // Every collapsible folder key (one per endpoint). Expand-all opens exactly this set; the default (null) draws them open too.
    val allFolders = remember(data) { data?.let { KitFileTree.containers(FleetDataView.treeEntries(it.groups)) } ?: emptySet() }
    val openSet = openFolders ?: allFolders
    val allOpen = allFolders.isNotEmpty() && openSet.containsAll(allFolders)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(KitDensity.medium), verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
            KitActionBar(listOf(
                KitAction("Sync all", "fleetdata:sync-all") { FleetDataSchedule.syncNow(ctx, authority); queued = true },
                KitAction("Refresh", "fleetdata:refresh") { queued = false; onRefresh() },
                KitAction(if (allOpen) "Collapse all" else "Expand all", "fleetdata:collapse-all") {
                    openFolders = if (allOpen) emptySet() else allFolders
                },
            ))
            if (data != null) {
                val text = if (queued) "Sync queued · ${FleetDataView.summary(data.rows, now)}" else FleetDataView.summary(data.rows, now)
                KitStatusBanner(text, if (queued) KitState.BUSY else FleetDataView.summaryState(data.rows), tag = "fleetdata-summary")
                KitSearchBar(query, { query = it }, {}, placeholder = "Filter datasets, URIs, sources, consumers")
                // Bucket chips: All · App Files · User Files · RunTime Files · Others.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(KitDensity.small), verticalArrangement = Arrangement.spacedBy(KitDensity.small / 2)) {
                    FileBucket.values().forEach { b -> FilterChip(b.label, bucket == b) { bucket = b } }
                }
                // The existing sync-state chips stay, below the bucket row.
                Row(horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                    FilterChip("all", status == null) { status = null }
                    SyncState.values().forEach { s -> FilterChip(FleetDataView.pillWord(s), status == s) { status = if (status == s) null else s } }
                }
            }
        }
        when {
            error != null -> Notice("Could not read the datasets", error.message ?: error.javaClass.simpleName, "Retry", onRefresh)
            data == null -> Notice("Loading datasets", "Reading this provider's cache.", null) {}
            data.groups.isEmpty() -> Notice("No dataset has been synced yet", "Tap Sync all to fetch them.", "Sync all") { FleetDataSchedule.syncNow(ctx, authority); queued = true }
            else -> {
                val shown = FleetDataView.filter(data.groups, query, status, bucket)
                val treeEntries = FleetDataView.treeEntries(shown)
                val rows = KitFileTree.rows(treeEntries, openSet, rootOrder = shown.map { it.endpoint })
                val ink = KitFileTreeInk(p.textPrimary, p.textSecondary, p.accent)
                LazyColumn(Modifier.fillMaxSize().testTag("fleetdata:list")) {
                    if (shown.isEmpty()) item {
                        Text("No dataset matches the filter.", color = p.textSecondary, fontSize = KitDensity.body, modifier = Modifier.padding(KitDensity.medium))
                    } else kitFileTree(
                        rows, ink, size = { FleetDataView.sizeLabel(it) },
                        date = { if (it <= 0L) "never" else FleetDataView.relativeTime(it, now) },
                        onToggle = { key -> openFolders = (openSet).let { if (key in it) it - key else it + key } },
                        onOpen = { entry -> sheetFor = entry.payload },
                        tagPrefix = "fleetdata",
                    )
                }
            }
        }
    }
    sheetFor?.let { r ->
        FileActionSheet(ctx, r, (data?.previous?.contains(r.path) == true),
            onViewJson = { sheetFor = null; onOpen(r.path) },
            onSync = { FleetDataSchedule.syncNow(ctx, authority); queued = true; sheetFor = null },
            onDiff = { sheetFor = null; onDiff(r.path) },
            onDismiss = { sheetFor = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCopy(onCopy: () -> Unit): Modifier = combinedClickable(onClick = onCopy, onLongClick = onCopy)

/**
 * The per-file action sheet: a [ModalBottomSheet] OVER the pane (mirrors StoreInspect.Sheet, so the Store nav and Back stay
 * put). It shows only REAL actions — the provider is read-only openFile, so no delete/download. View JSON, Sync now, Copy URI,
 * and Diff only when a previous version exists ([hasPrevious]); plus the file's live metadata (tier, schema, size, ETag, consumers).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FileActionSheet(
    ctx: Context, r: DatasetRow, hasPrevious: Boolean,
    onViewJson: () -> Unit, onSync: () -> Unit, onDiff: () -> Unit, onDismiss: () -> Unit,
) {
    val p = LocalKitPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.surface,
        modifier = Modifier.testTag("fleetdata:sheet:${r.path}"),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = KitDensity.medium, vertical = KitDensity.small),
            verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                Text(r.dataset, color = p.textPrimary, fontSize = KitDensity.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                KitStatePill(FleetDataView.pillWord(r.state), FleetDataView.kitState(r.state))
            }
            Text(r.uri, color = p.textSecondary, fontSize = KitDensity.caption, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().combinedClickableCopy { copy(ctx, "URI", r.uri) })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(KitDensity.small), verticalArrangement = Arrangement.spacedBy(KitDensity.small / 2)) {
                Chip(FleetDataView.tierChip(r.tier)); Chip(FleetDataView.schemaChip(r.schemaVersion)); Chip(FleetDataView.sizeLabel(r.size))
                Chip("ETag " + FleetDataView.etagLabel(r.etag, true), mono = true)
                Chip("consumers " + FleetDataView.consumersLabel(r.consumers, true))
            }
            Text("source ${r.source ?: "-"}", color = p.textSecondary, fontSize = KitDensity.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("synced " + (if (r.syncedAt <= 0L) "never" else stamp(r.syncedAt)), color = p.textSecondary, fontSize = KitDensity.caption)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(KitDensity.small / 2), verticalArrangement = Arrangement.spacedBy(KitDensity.small / 2)) {
                KitTextButton(onClick = onViewJson, modifier = Modifier.testTag("fleetdata:json:${r.path}")) { Text("{ } View JSON") }
                KitTextButton(onClick = onSync, modifier = Modifier.testTag("fleetdata:sync:${r.path}")) { Text("↻ Sync now") }
                KitTextButton(onClick = onDiff, enabled = hasPrevious, modifier = Modifier.testTag("fleetdata:diff:${r.path}")) { Text("± Diff") }
                KitTextButton(onClick = { copy(ctx, "URI", r.uri) }, modifier = Modifier.testTag("fleetdata:copy:${r.path}")) { Text("⧉ Copy URI") }
            }
        }
    }
}

/** A small read-only label on the palette's selected surface. */
@Composable
private fun Chip(text: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    val p = LocalKitPalette.current
    Text(text, color = p.textPrimary, fontSize = KitDensity.caption, fontFamily = if (mono) FontFamily.Monospace else null, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(KitDensity.corner)).then(modifier).background(p.surfaceSelected)
            .padding(horizontal = KitDensity.small, vertical = KitDensity.small / 4))
}

/** The status filter: the selected one is lit with the accent. */
@Composable
private fun FilterChip(label: String, on: Boolean, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    Text(label, color = if (on) p.tileInk else p.textPrimary, fontSize = KitDensity.caption, maxLines = 1,
        modifier = Modifier.testTag("fleetdata:filter:$label").clip(RoundedCornerShape(KitDensity.corner))
            .background(if (on) p.accent else p.surfaceSelected).combinedClickableCopy(onClick)
            .padding(horizontal = KitDensity.medium, vertical = KitDensity.small / 2))
}

/** Loading / empty / error: a title, one caption and, when there is one, the action that fixes it. */
@Composable
private fun Notice(title: String, caption: String, action: String?, onAction: () -> Unit) {
    val p = LocalKitPalette.current
    KitCard(Modifier.padding(KitDensity.medium).testTag("fleetdata:notice")) {
        Text(title, color = p.textPrimary, fontSize = KitDensity.title, fontWeight = FontWeight.Bold)
        Text(caption, color = p.textSecondary, fontSize = KitDensity.body)
        if (action != null) KitActionBar(listOf(KitAction(action, "fleetdata:notice:action") { onAction() }))
    }
}

@Composable
private fun SyncTab(ctx: Context, ep: FleetDataEndpoint, authority: String, onRefresh: () -> Unit) {
    val p = LocalKitPalette.current
    val s = ep.status()
    Column(Modifier.padding(KitDensity.medium), verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
        KitCard {
            Text("Authority: $authority", color = p.textSecondary, fontSize = KitDensity.caption, fontFamily = FontFamily.Monospace)
            Text("Source: ${s?.source?.ifBlank { null } ?: "not synced yet"}", fontSize = KitDensity.body)
            Text("Last sync: ${stamp(s?.at ?: 0)}", fontSize = KitDensity.body)
            Text("Updated: ${s?.updated?.size ?: 0} | not modified: ${s?.notModified?.size ?: 0} | failed: ${s?.failed?.size ?: 0}", fontSize = KitDensity.body)
            s?.failed?.forEach { (k, v) -> Text("$k: $v", color = p.bad, fontSize = KitDensity.caption) }
            Text("Signature check: not enforced yet (verifier present, trust key pending).", color = p.textSecondary, fontSize = KitDensity.caption)
        }
        KitActionBar(listOf(KitAction("Sync now", "fleetdata:sync-now") { FleetDataSchedule.syncNow(ctx, authority) }, KitAction("Refresh", "fleetdata:sync-refresh") { onRefresh() }))
    }
}

@Composable
private fun ConsumersTab(ep: FleetDataEndpoint, tick: Int) {
    val p = LocalKitPalette.current
    val m = remember(tick) { ep.server.manifest() }
    val log = remember(tick) { ep.log.rows() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = KitDensity.medium), verticalArrangement = Arrangement.spacedBy(KitDensity.small / 2)) {
        item { GroupTitle("May read (manifest)") }
        items(m.shared.entries.toList()) { (k, v) -> Text("shared/$k: ${v.consumers.joinToString()}", color = p.textSecondary, fontSize = KitDensity.body) }
        items(m.apps.entries.toList()) { (pkg, ds) -> Text("app/$pkg: ${ds.joinToString()}", color = p.textSecondary, fontSize = KitDensity.body) }
        item { GroupTitle("Did read (access log)") }
        items(log) { r ->
            Text("${stamp(r.at)} ${r.caller} ${r.path} ${if (r.granted) "ok" else "DENIED"}", color = if (r.granted) p.textSecondary else p.bad,
                fontSize = KitDensity.caption, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun DiffTab(ep: FleetDataEndpoint, entries: List<com.diegonmarcos.superapp.fleetdata.provider.FleetDataStore.Entry>, diffOf: String?, onPick: (String?) -> Unit) {
    val p = LocalKitPalette.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = KitDensity.medium), verticalArrangement = Arrangement.spacedBy(KitDensity.small / 2)) {
        items(entries) { e ->
            val r = Route.parse(e.path.split('/'))
            val prev = r?.let { ep.store.previous(it) }
            Text(e.path + if (prev == null) " (no previous version)" else "", color = if (prev == null) p.textSecondary else p.accent, fontSize = KitDensity.body,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().combinedClickableCopy { if (prev != null) onPick(e.path) }.padding(vertical = KitDensity.small))
            if (diffOf == e.path && r != null && prev != null) {
                val d = DatasetDiff.diff(prev.decodeToString(), ep.store.read(r)?.decodeToString().orEmpty())
                if (d.isEmpty()) Text("identical", color = p.ok, fontSize = KitDensity.caption)
                d.take(200).forEach { c ->
                    Text("${c.kind.name.first()} ${c.path}  ${c.before?.take(40) ?: ""} -> ${c.after?.take(40) ?: ""}", color = p.textSecondary,
                        fontSize = KitDensity.caption, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
