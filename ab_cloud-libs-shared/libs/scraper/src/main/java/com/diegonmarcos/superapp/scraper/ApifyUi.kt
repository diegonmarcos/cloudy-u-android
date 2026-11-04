package com.diegonmarcos.superapp.scraper

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.themebg.ThemeColors
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreDot
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreQuick
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

private enum class ApifyView(val label: String) { ACTORS("Actors"), RUNS("Runs"), DATASETS("Datasets") }

private fun fileStem(s: String) = s.replace(Regex("""[^\p{L}\p{N}._-]+"""), "_").trim('_').ifEmpty { "apify" }

/** #1468 Apify: the declared actors, a Store search, and (with the user's token) run an actor, list runs and datasets, export items. */
@Composable
fun ApifyPage(c: ThemeColors) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val http = remember { MusicHttp() }
    val account = remember { ApifyAccount(EncryptedTokenStore(ctx, ApifyAccount.PREFS)) }
    var rev by remember { mutableStateOf(0) }
    var view by remember { mutableStateOf(ApifyView.ACTORS) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<ApifyActor>?>(null) }
    var total by remember { mutableStateOf(0L) }
    var runs by remember { mutableStateOf<List<ApifyRun>>(emptyList()) }
    var sets by remember { mutableStateOf<List<ApifyDataset>>(emptyList()) }
    var open by remember { mutableStateOf<String?>(null) }
    var accountOpen by remember { mutableStateOf(false) }
    var tokenText by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("{}") }
    var message by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf("") }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(pending.toByteArray()) }; "Saved" }.getOrElse { "Save failed: ${it.message}" }
            }
        }
    }
    val connected = rev.let { account.connected() }
    val colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.ink, unfocusedTextColor = c.ink, focusedLabelColor = c.accent, unfocusedLabelColor = c.inkDim)

    fun guarded(what: String, block: suspend () -> Unit) = scope.launch {
        message = what
        runCatching { block() }.onSuccess { if (message == what) message = "" }.onFailure { message = "Apify: ${it.message}" }
    }
    fun client() = ApifyClient(account.token(), http, account.backend.api)
    fun search() = guarded("Searching the Store…") {
        val p = withContext(Dispatchers.IO) { ApifyStore.parse(http.get(ApifyStore.url(query))) }
        found = p.items; total = p.total; view = ApifyView.ACTORS
    }
    fun loadRuns() = guarded("Reading runs…") { runs = withContext(Dispatchers.IO) { client().runs() }; view = ApifyView.RUNS }
    fun loadSets() = guarded("Reading datasets…") { sets = withContext(Dispatchers.IO) { client().datasets() }; view = ApifyView.DATASETS }
    fun runActor(a: ApifyActor) = guarded("Starting ${a.title}…") {
        val r = withContext(Dispatchers.IO) { client().run(a.id, input) }
        message = "Run ${r.id} ${r.status}"; loadRuns()
    }
    fun export(datasetId: String, json: Boolean) = guarded("Reading items…") {
        val items: JSONArray = withContext(Dispatchers.IO) { client().datasetItems(datasetId) }
        pending = if (json) ApifyExport.json(items) else ApifyExport.csv(items)
        message = "${items.length()} items"
        saver.launch(fileStem("apify-$datasetId") + if (json) ".json" else ".csv")
    }
    fun link(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    fun pick(v: ApifyView) {
        open = null
        if (v == ApifyView.ACTORS) { view = v; return }
        if (!connected) { message = "Add your Apify token first"; return }
        if (v == ApifyView.RUNS) loadRuns() else loadSets()
    }

    val actors = if (view == ApifyView.ACTORS) (found ?: ApifyCatalog.actors) else emptyList()
    LazyColumn(Modifier.fillMaxSize().testTag("apify:list")) {
        item(key = "notice") { KitStoreRowLine { KitStoreCaption(ApifyCatalog.NOTICE, color = c.inkDim, modifier = Modifier.testTag("apify:notice")) } }
        item(key = "search") {
            Row(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, label = { Text("Search the Apify Store") }, singleLine = true, colors = colors,
                    modifier = Modifier.weight(1f).testTag("apify:search"))
                TextButton(onClick = { search() }, enabled = query.isNotBlank(), modifier = Modifier.testTag("apify:go")) { Text("Search", color = c.accent) }
            }
        }
        item(key = "backend") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                for (b in ApifyBackend.entries) Chip(b.label, rev.let { account.backend } == b, c, "apify:backend:${b.name}") {
                    account.backend = b; rev++; runs = emptyList(); sets = emptyList(); open = null
                    if (view != ApifyView.ACTORS) view = ApifyView.ACTORS
                }
            }
        }
        item(key = "tabs") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                for (v in ApifyView.entries) Chip(v.label, view == v, c, "apify:view:${v.name}") {
                    pick(v)
                }
                Chip(if (connected) "Token ${account.masked()}" else "Add token", accountOpen, c, "apify:account") { accountOpen = !accountOpen }
            }
        }
        if (accountOpen) item(key = "account") {
            Column(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH).testTag("apify:token")) {
                OutlinedTextField(tokenText, { tokenText = it }, label = { Text("${rev.let { account.backend.label }} token (stored encrypted)") }, singleLine = true, colors = colors,
                    modifier = Modifier.fillMaxWidth().testTag("apify:token:field"))
                Row {
                    Button(onClick = { if (account.save(tokenText)) { tokenText = ""; rev++; accountOpen = false; message = "Token saved" } else message = "That does not look like an Apify token" },
                        enabled = tokenText.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = c.accent), modifier = Modifier.testTag("apify:token:save")) { Text("Save") }
                    if (connected) TextButton(onClick = { account.clear(); rev++; runs = emptyList(); sets = emptyList() }, modifier = Modifier.testTag("apify:token:clear")) { Text("Remove", color = c.accent) }
                }
            }
        }
        if (message.isNotEmpty()) item(key = "status") { KitStoreRowLine { KitStoreCaption(message, color = c.ink, modifier = Modifier.testTag("apify:status")) } }
        if (view == ApifyView.ACTORS && found != null) item(key = "total") { KitStoreRowLine { KitStoreCaption("$total in the Store for \"$query\"", color = c.inkDim) } }
        items(actors, key = { "a:" + it.id }) { a ->
            val isOpen = open == a.id
            Column(Modifier.testTag("apify:actor:${a.id}")) {
                KitStoreRowLine(onClick = { open = if (isOpen) null else a.id }) {
                    Column(Modifier.weight(1f)) {
                        KitStoreName(a.title, color = c.ink)
                        KitStoreCaption(listOf(a.author, "${ApifyStore.compact(a.runs)} runs", "${ApifyStore.compact(a.users)} users", a.pricing).filter { it.isNotBlank() }.joinToString(" · "), color = c.inkDim)
                    }
                    KitStoreQuick("Open", KitStoreRow.BLUE_FILL) { link(a.url) }
                }
                if (isOpen) Column(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH)) {
                    if (a.description.isNotBlank()) KitStoreCaption(a.description, color = c.inkDim)
                    KitStoreCaption(a.id, color = c.inkDim, mono = true)
                    OutlinedTextField(input, { input = it }, label = { Text("Input (JSON)") }, colors = colors, modifier = Modifier.fillMaxWidth().testTag("apify:input"))
                    Button(onClick = { runActor(a) }, enabled = connected, colors = ButtonDefaults.buttonColors(containerColor = c.accent), modifier = Modifier.testTag("apify:run")) {
                        Text(if (connected) "Run" else "Add token to run")
                    }
                }
            }
        }
        if (view == ApifyView.RUNS) {
            if (runs.isEmpty()) item(key = "noruns") { KitStoreRowLine { KitStoreCaption("No runs yet.", color = c.inkDim) } }
            items(runs, key = { "r:" + it.id }) { r ->
                Column(Modifier.testTag("apify:run:${r.id}")) {
                    KitStoreRowLine(onClick = { open = if (open == r.id) null else r.id }) {
                        KitStoreDot(if (r.status == "SUCCEEDED") "✓" else if (r.status == "FAILED" || r.status == "ABORTED") "⚠" else "◯",
                            when (r.status) { "SUCCEEDED" -> KitStoreRow.UP; "FAILED", "ABORTED" -> KitStoreRow.BAD; else -> KitStoreRow.UPDATE })
                        Column(Modifier.weight(1f)) {
                            KitStoreName(r.id, color = c.ink)
                            KitStoreCaption("${r.status} · ${r.startedAt.take(16).replace('T', ' ')} · ${r.actorId}", color = c.inkDim)
                        }
                    }
                    if (open == r.id) ExportRow(c, r.datasetId, "apify:run:${r.id}", { ds, js -> export(ds, js) })
                }
            }
        }
        if (view == ApifyView.DATASETS) {
            if (sets.isEmpty()) item(key = "nosets") { KitStoreRowLine { KitStoreCaption("No datasets yet.", color = c.inkDim) } }
            items(sets, key = { "d:" + it.id }) { d ->
                Column(Modifier.testTag("apify:dataset:${d.id}")) {
                    KitStoreRowLine(onClick = { open = if (open == d.id) null else d.id }) {
                        Column(Modifier.weight(1f)) {
                            KitStoreName(d.name.ifBlank { d.id }, color = c.ink)
                            KitStoreCaption("${d.itemCount} items · ${d.modifiedAt.take(16).replace('T', ' ')}", color = c.inkDim)
                        }
                    }
                    if (open == d.id) ExportRow(c, d.id, "apify:dataset:${d.id}", { ds, js -> export(ds, js) })
                }
            }
        }
    }
}

@Composable
private fun ExportRow(c: ThemeColors, datasetId: String, tag: String, export: (String, Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        KitStoreCaption("Items", color = c.inkDim, modifier = Modifier.padding(end = KitStoreRow.gap))
        TextButton(onClick = { export(datasetId, false) }, enabled = datasetId.isNotBlank(), modifier = Modifier.testTag("$tag:csv")) { Text("Export CSV", color = c.accent) }
        TextButton(onClick = { export(datasetId, true) }, enabled = datasetId.isNotBlank(), modifier = Modifier.testTag("$tag:json")) { Text("Export JSON", color = c.accent) }
    }
}
