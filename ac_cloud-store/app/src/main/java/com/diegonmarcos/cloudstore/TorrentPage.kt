package com.diegonmarcos.cloudstore

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.appstore.StoreDensity
import com.diegonmarcos.superapp.torrent.EngineState
import com.diegonmarcos.superapp.torrent.TorrentEngine
import com.diegonmarcos.superapp.torrent.TorrentEngines
import com.diegonmarcos.superapp.torrent.TorrentFormat
import com.diegonmarcos.superapp.torrent.TorrentPaths
import com.diegonmarcos.superapp.torrent.TorrentState
import com.diegonmarcos.superapp.torrent.TorrentStatus
import com.diegonmarcos.superapp.torrent.sentence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test tags of the Torrent destination. */
object TorrentTags {
    const val PAGE = "torrent:page"
    const val MAGNET_FIELD = "torrent:magnet"
    const val ADD = "torrent:add"
    const val IMPORT = "torrent:import"
    const val STATUS = "torrent:status"
    const val TOTALS = "torrent:totals"
    const val ACTIVE = "torrent:active"
    const val COMPLETED = "torrent:completed"
    const val OFFLINE = "torrent:offline"
    fun row(id: String) = "torrent:row:$id"
    fun pause(id: String) = "torrent:pause:$id"
    fun resume(id: String) = "torrent:resume:$id"
    fun remove(id: String) = "torrent:remove:$id"
    fun removeFiles(id: String) = "torrent:remove-files:$id"
    fun seed(id: String) = "torrent:seed:$id"
}

/** Whether the phone has a network with internet; false is a banner, never a crash. */
internal fun online(ctx: Context): Boolean = runCatching {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}.getOrDefault(false)

/**
 * The Torrent destination (bottom nav, where Feed was): add a magnet or import a .torrent, then every torrent with its
 * progress, rates, seeds and peers, Pause / Resume / Remove and the per-torrent seed switch, in two lists: Active (downloading,
 * fetching metadata, checking, queued, paused, errors) and Completed (finished: seeding or done). The BitTorrent engine is libs:torrent; this
 * page only polls [TorrentEngine.refresh] about once a second while it is shown (the engine's own ticker applies the seeding policy
 * while it is not). The engine being unavailable, no network and an unusable folder are each said in words.
 * Between the status and the lists: the CATALOGUE (#1681, [catalogueItems]): the declared torrent-catalogue dataset by category, plus
 * the user's own entries, each handed to the engine or to the user's torrent app.
 */
@Composable
fun TorrentPage() {
    val ctx = LocalContext.current
    val torrents = remember { TorrentEngines.get(ctx) }
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<TorrentStatus>>(emptyList()) }
    var engineState by remember { mutableStateOf<EngineState>(torrents.state) }
    var net by remember { mutableStateOf(true) }
    var magnet by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var armed by remember { mutableStateOf<String?>(null) }
    // #1681 the declared catalogue (runtime dataset shared/torrent-catalogue) + the user's own entries from Configs > Torrent.
    var catalogue by remember { mutableStateOf(CatalogueView()) }
    LaunchedEffect(Unit) {
        val (c, served) = withContext(Dispatchers.IO) { runCatching { TorrentCatalogue.load(ctx) }.getOrDefault(TorrentCatalogue.EMPTY to false) }
        catalogue = catalogue.copy(catalogue = c, served = served, loaded = true)
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { torrents.start() }
        while (true) {
            list = withContext(Dispatchers.IO) { torrents.refresh() }
            engineState = torrents.state
            net = online(ctx)
            delay(1_000)
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            note = withContext(Dispatchers.IO) { TorrentIntake.importUris(ctx, uris) }.joinToString("\n")
        }
    }
    val pad = StoreDensity.dpValue(StoreDensity.S12).dp
    val gap = StoreDensity.dpValue(StoreDensity.S4).dp
    val active = list.filter { !it.finished }
    val done = list.filter { it.finished }
    LazyColumn(Modifier.fillMaxSize().testTag(TorrentTags.PAGE).padding(pad), verticalArrangement = Arrangement.spacedBy(gap)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                    OutlinedTextField(magnet, { magnet = it }, Modifier.weight(1f).testTag(TorrentTags.MAGNET_FIELD), singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = StoreDensity.T_BODY.sp), placeholder = { Text("magnet:?xt=urn:btih:…", fontSize = StoreDensity.T_CAPTION.sp) })
                    Button({
                        val m = magnet.trim()
                        if (m.isNotEmpty()) scope.launch {
                            val r = withContext(Dispatchers.IO) { torrents.addMagnet(m) }
                            note = r.sentence(); if (r.idOrNull != null) magnet = ""
                        }
                    }, Modifier.testTag(TorrentTags.ADD)) { Text("Add", fontSize = StoreDensity.T_BODY.sp) }
                    Button({ pick.launch(TorrentIntake.PICK_TYPES) }, Modifier.testTag(TorrentTags.IMPORT)) {
                        Text("Import .torrent", fontSize = StoreDensity.T_BODY.sp)
                    }
                }
                note?.let { Text(it, fontSize = StoreDensity.T_CAPTION.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                StatusLine(ctx, torrents, engineState, net, list)
            }
        }
        catalogueItems(ctx, torrents, catalogue, { catalogue = it }, { note = it }, scope)
        item { Heading("Active (${active.size})", TorrentTags.ACTIVE) }
        if (active.isEmpty()) item { Dim("Nothing downloading. Paste a magnet link, import a .torrent, or search the Torrent sources in Search.") }
        items(active, key = { it.id }) { t -> TorrentRow(torrents, t, armed, { armed = it }, scope) }
        item { Heading("Completed (${done.size})", TorrentTags.COMPLETED) }
        if (done.isEmpty()) item { Dim("Finished torrents appear here, seeding or done.") }
        items(done, key = { it.id }) { t -> TorrentRow(torrents, t, armed, { armed = it }, scope) }
    }
}

@Composable
private fun Heading(text: String, tag: String) =
    Text(text, Modifier.testTag(tag).padding(top = StoreDensity.dpValue(StoreDensity.S8).dp), fontSize = StoreDensity.T_TITLE.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)

@Composable
private fun Dim(text: String) = Text(text, fontSize = StoreDensity.T_CAPTION.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Engine state, network, folder and the totals: one dense block. */
@Composable
private fun StatusLine(ctx: Context, torrents: TorrentEngine, state: EngineState, net: Boolean, list: List<TorrentStatus>) {
    val bad = Color(0xFFF56565)
    when (state) {
        is EngineState.Unavailable -> Text(state.reason, Modifier.testTag(TorrentTags.STATUS), fontSize = StoreDensity.T_CAPTION.sp, color = bad)
        else -> Unit
    }
    if (!net) Text("No network: downloads wait until the phone is online.", Modifier.testTag(TorrentTags.OFFLINE), fontSize = StoreDensity.T_CAPTION.sp, color = bad)
    val dir = torrents.settings.saveDir
    val free = TorrentPaths.freeBytes(dir)
    val down = list.sumOf { it.downRate }; val up = list.sumOf { it.upRate }
    Text("↓ ${TorrentFormat.rate(down)} · ↑ ${TorrentFormat.rate(up)} · ${list.count { it.active }} active · ${list.count { it.finished }} completed · " +
        "${list.sumOf { it.seeds }} seeds · ${list.sumOf { it.peers }} peers · folder ${dir.path}" + if (free >= 0) " (${TorrentFormat.bytes(free)} free)" else "",
        Modifier.testTag(TorrentTags.TOTALS), fontSize = StoreDensity.T_CAPTION.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TorrentRow(torrents: TorrentEngine, t: TorrentStatus, armed: String?, arm: (String?) -> Unit, scope: kotlinx.coroutines.CoroutineScope) {
    val bad = t.state == TorrentState.ERROR
    val h = StoreDensity.dpValue(StoreDensity.S12).dp
    val v = StoreDensity.dpValue(StoreDensity.S6).dp
    Column(Modifier.fillMaxWidth().testTag(TorrentTags.row(t.id)).background(Color(0xFF1C1C24)).padding(h, v), verticalArrangement = Arrangement.spacedBy(StoreDensity.dpValue(StoreDensity.S2).dp)) {
        Text(t.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_BODY.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!t.finished) TorrentBar(t.progress)
        Text(TorrentFormat.line(t), fontSize = StoreDensity.T_CAPTION.sp, color = if (bad) Color(0xFFF56565) else Color(0x99FFFFFF))
        if (t.error != null) Text(t.error!!, fontSize = StoreDensity.T_CAPTION.sp, color = Color(0xFFF56565))
        Text(t.savePath, fontSize = StoreDensity.T_MICRO.sp, color = Color(0x66FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StoreDensity.dpValue(StoreDensity.S4).dp)) {
            if (t.state == TorrentState.PAUSED || t.state == TorrentState.COMPLETED || t.state == TorrentState.ERROR)
                Button({ scope.launch(Dispatchers.IO) { torrents.resume(t.id) } }, Modifier.testTag(TorrentTags.resume(t.id))) { Text(if (t.finished || t.state == TorrentState.COMPLETED) "Seed" else "Resume", fontSize = StoreDensity.T_CAPTION.sp) }
            else Button({ scope.launch(Dispatchers.IO) { torrents.pause(t.id) } }, Modifier.testTag(TorrentTags.pause(t.id))) { Text("Pause", fontSize = StoreDensity.T_CAPTION.sp) }
            Button({ scope.launch(Dispatchers.IO) { torrents.remove(t.id, deleteFiles = false) } }, Modifier.testTag(TorrentTags.remove(t.id))) { Text("Remove", fontSize = StoreDensity.T_CAPTION.sp) }
            Button({
                if (armed == t.id) { arm(null); scope.launch(Dispatchers.IO) { torrents.remove(t.id, deleteFiles = true) } } else arm(t.id)
            }, Modifier.testTag(TorrentTags.removeFiles(t.id))) { Text(if (armed == t.id) "Confirm: delete files" else "Remove + files", fontSize = StoreDensity.T_CAPTION.sp) }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text("seed", fontSize = StoreDensity.T_CAPTION.sp, color = Color(0x99FFFFFF), modifier = Modifier.padding(end = StoreDensity.dpValue(StoreDensity.S4).dp))
                Switch(t.seedEnabled, { on -> scope.launch(Dispatchers.IO) { torrents.setSeeding(t.id, on) } }, Modifier.testTag(TorrentTags.seed(t.id)))
            }
        }
    }
}

/** A torrent's own thin bar (a track and a fill). Not the Store's download progress: that is drawn once, by the Activity panel. */
@Composable
private fun TorrentBar(fraction: Float) {
    val h = StoreDensity.dpValue(StoreDensity.S4).dp
    Box(Modifier.fillMaxWidth().height(h).background(Color(0xFF2A2A33))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(h).background(Color(0xFF2B6CB0)))
    }
}
