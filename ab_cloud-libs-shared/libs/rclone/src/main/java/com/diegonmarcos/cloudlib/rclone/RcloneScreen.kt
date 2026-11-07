package com.diegonmarcos.cloudlib.rclone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import com.diegonmarcos.superapp.fleetdata.FleetData
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one entry point libs:rclone exports: Remotes (rclone.conf, add / edit /
 * test / delete from a data-driven catalogue of backends), Jobs (declared by
 * the host and user-created; run with live bytes / speed / ETA / errors and a
 * log tail; cancel), Browse (lsjson navigation of any remote, download a file
 * and hand it to the host). Hosted by the app that links this module
 * (cloud-drive, push 5 of #567); this library never names that app.
 *
 * @param target "remotes" | "jobs" | "browse" to open on that tab, a job id to
 *   open Jobs with that job selected, or null.
 * @param onOpenFile the host's file hand-off for a downloaded file.
 * @param onClose the host's way out of this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RcloneScreen(
    target: String?,
    onOpenFile: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val runner = remember { RcloneRunner(context) }
    val store = remember { RcloneJobStore(File(context.filesDir, "rclone/jobs.json")) }
    val types by produceState(emptyList<RemoteType>()) { value = withContext(Dispatchers.IO) { runCatching { FleetData.get(context.applicationContext, RcloneRemoteTypes.DATASET) { RcloneRemoteTypes.parse(it) } }.getOrNull().orEmpty() } }
    var tab by remember { mutableIntStateOf(when (target) { "jobs" -> 1; "browse" -> 2; null, "remotes" -> 0; else -> 1 }) }
    var remotes by remember { mutableStateOf(runner.remotes()) }
    var jobs by remember { mutableStateOf(store.load()) }
    var version by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { version = withContext(Dispatchers.IO) { runner.version() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.cloudlib_rclone_title))
                        Text(
                            if (!runner.isAvailable) "engine missing" else "rclone ${version ?: "…"} · ${remotes.size} remote(s)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                listOf("Remotes", "Jobs", "Browse").forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) }
            }
            if (!runner.isAvailable) {
                Text(runner.unavailableReason ?: "The rclone engine is not available.",
                    Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
            }
            when (tab) {
                0 -> RemotesTab(runner, types, remotes, onChanged = { remotes = it }, notify = { scope.launch { snackbar.showSnackbar(it) } })
                1 -> JobsTab(runner, store, jobs, remotes, selectedId = target?.takeIf { it !in listOf("remotes", "jobs", "browse") }, onChanged = { jobs = it }, notify = { scope.launch { snackbar.showSnackbar(it) } })
                else -> BrowseTab(runner, remotes, onOpenFile, notify = { scope.launch { snackbar.showSnackbar(it) } })
            }
        }
    }
}

// ── Remotes ──────────────────────────────────────────────────────────────

@Composable
private fun RemotesTab(runner: RcloneRunner, types: List<RemoteType>, remotes: List<RcloneRemote>, onChanged: (List<RcloneRemote>) -> Unit, notify: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<RcloneRemote?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("rclone.conf · ${runner.configFile.absolutePath}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { adding = true }) { Text("Add remote") }
            }
        }
        if (remotes.isEmpty()) item { Text("No remotes configured. Add one — the secrets are obscured the way rclone config does it and stay in the app's private files.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
        items(remotes, key = { it.name }) { r ->
            ListItem(
                headlineContent = { Text("${r.name}:") },
                supportingContent = { Text("${r.type}  " + r.options.filterKeys { !it.contains("pass") && it != "token" && !it.contains("secret") && !it.contains("key") }.entries.joinToString("  ") { "${it.key}=${it.value}" }, style = MaterialTheme.typography.bodySmall) },
                trailingContent = {
                    Row {
                        if (testing == r.name) CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
                        else TextButton(onClick = {
                            testing = r.name
                            scope.launch {
                                val res = withContext(Dispatchers.IO) { runner.test(r.name) }
                                testing = null
                                notify(res.fold({ "${r.name}: reachable, ${it} entries at root" }, { "${r.name}: ${it.message?.lines()?.firstOrNull { l -> l.isNotBlank() } ?: it}" }))
                            }
                        }) { Text("Test") }
                        IconButton(onClick = { editing = r }) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                        IconButton(onClick = { confirmDelete = r.name }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                },
            )
            HorizontalDivider()
        }
    }
    if (adding || editing != null) {
        RemoteDialog(types, editing, runner, onDismiss = { adding = false; editing = null }) { remote ->
            adding = false; editing = null
            onChanged(RcloneConfig.upsert(runner.configFile, remote)); notify("saved ${remote.name}:")
        }
    }
    confirmDelete?.let { n ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete remote $n?") },
            text = { Text("Only the configuration entry is removed; nothing on the remote is touched.") },
            confirmButton = { TextButton(onClick = { confirmDelete = null; onChanged(RcloneConfig.remove(runner.configFile, n)); notify("deleted $n:") }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } })
    }
}

@Composable
private fun RemoteDialog(types: List<RemoteType>, existing: RcloneRemote?, runner: RcloneRunner, onDismiss: () -> Unit, onSave: (RcloneRemote) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var typeIndex by remember { mutableIntStateOf(types.indexOfFirst { it.type == existing?.type }.coerceAtLeast(0)) }
    val type = types.getOrNull(typeIndex)
    val values = remember(typeIndex) { mutableStateOf(type?.fields?.associate { f -> f.key to (existing?.options?.get(f.key)?.takeIf { !f.secret } ?: f.default) } ?: emptyMap()) }
    var extra by remember { mutableStateOf(existing?.options?.filterKeys { k -> type?.fields?.none { it.key == k } ?: true }?.entries?.joinToString("\n") { "${it.key} = ${it.value}" } ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (existing == null) "Add remote" else "Edit ${existing.name}:") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (used as name:path)") }, singleLine = true, enabled = existing == null, modifier = Modifier.fillMaxWidth())
                Text("Type", style = MaterialTheme.typography.labelLarge)
                types.forEachIndexed { i, t ->
                    Row(Modifier.fillMaxWidth().clickable { typeIndex = i }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = typeIndex == i, onClick = { typeIndex = i }); Text(t.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
                type?.fields?.forEach { f ->
                    OutlinedTextField(
                        values.value[f.key] ?: "", { v -> values.value = values.value + (f.key to v) },
                        label = { Text(f.label + (if (f.required) " *" else "") + (if (f.secret && existing != null) " (leave empty to keep)" else "")) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (f.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    )
                }
                OutlinedTextField(extra, { extra = it }, label = { Text("Extra options, one key = value per line") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && type != null && RcloneConfig.isValidName(name), onClick = {
                val t = type ?: return@TextButton
                busy = true; error = null
                scope.launch {
                    val built = withContext(Dispatchers.IO) {
                        runCatching {
                            val opts = LinkedHashMap<String, String>()
                            existing?.options?.forEach { (k, v) -> opts[k] = v }
                            for (f in t.fields) {
                                val v = values.value[f.key].orEmpty()
                                if (f.required && v.isBlank() && !(f.secret && existing != null)) error("${f.label} is required")
                                if (v.isBlank()) { if (!f.secret) opts.remove(f.key); continue }
                                opts[f.key] = if (f.secret) runner.obscure(v) else v
                            }
                            extra.lines().map { it.trim() }.filter { it.contains('=') }.forEach { l -> opts[l.substringBefore('=').trim()] = l.substringAfter('=').trim() }
                            RcloneRemote(name.trim(), t.type, opts)
                        }
                    }
                    busy = false
                    built.onSuccess(onSave).onFailure { error = it.message ?: it.toString() }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Jobs ─────────────────────────────────────────────────────────────────

private class RunState(val handle: RcloneRunner.Handle) {
    var stats by mutableStateOf<RcloneStats?>(null)
    var lines by mutableStateOf<List<String>>(emptyList())
    var exit by mutableStateOf<Int?>(null)
}

@Composable
private fun JobsTab(runner: RcloneRunner, store: RcloneJobStore, jobs: List<RcloneJob>, remotes: List<RcloneRemote>, selectedId: String?, onChanged: (List<RcloneJob>) -> Unit, notify: (String) -> Unit) {
    var runs by remember { mutableStateOf<Map<String, RunState>>(emptyMap()) }
    var editing by remember { mutableStateOf<RcloneJob?>(null) }
    var adding by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(selectedId) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    fun run(job: RcloneJob) {
        if (!runner.isAvailable) { notify(runner.unavailableReason ?: "rclone engine missing"); return }
        val state = RunState(runner.start(job,
            onStats = { s -> runs[job.id]?.stats = s },
            onLine = { l -> runs[job.id]?.let { st -> st.lines = (st.lines + l).takeLast(200) } },
            onExit = { rc ->
                runs[job.id]?.exit = rc
                val st = runs[job.id]
                val summary = when (rc) { 0 -> "ok"; -2 -> "cancelled"; else -> "exit $rc" } + (st?.stats?.let { " · ${RcloneOutput.humanBytes(it.bytes)}, ${it.transfers} transfer(s), ${it.errors} error(s)" } ?: "")
                onChanged(store.upsert(job.copy(lastRunEpochSeconds = System.currentTimeMillis() / 1000, lastRunSummary = summary)))
            }))
        runs = runs + (job.id to state)
        expanded = job.id
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${jobs.count { it.declared }} declared · ${jobs.count { !it.declared }} own", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { adding = true }) { Text("New job") }
            }
        }
        if (jobs.isEmpty()) item { Text("No jobs. Declared jobs come from the host's build-time data; your own are created here.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
        items(jobs, key = { it.id }) { job ->
            val state = runs[job.id]
            val running = state != null && state.exit == null
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable { expanded = if (expanded == job.id) null else job.id }) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(job.name + if (job.declared) "  (declared)" else "", style = MaterialTheme.typography.titleMedium)
                            Text("${job.op}  ${job.source}  →  ${job.destination}" + (if (job.flags.isNotEmpty()) "  " + job.flags.joinToString(" ") else ""), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            if (job.lastRunEpochSeconds > 0) Text("last run ${fmt.format(Date(job.lastRunEpochSeconds * 1000))}: ${job.lastRunSummary}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (running) IconButton(onClick = { state!!.handle.cancel() }) { Icon(Icons.Default.Stop, contentDescription = "Cancel") }
                        else IconButton(onClick = { run(job) }) { Icon(Icons.Default.PlayArrow, contentDescription = "Run") }
                        if (!job.declared) IconButton(onClick = { editing = job }) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                    }
                    state?.stats?.let { s ->
                        LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                        Text("${RcloneOutput.humanBytes(s.bytes)} / ${RcloneOutput.humanBytes(s.totalBytes)} · ${RcloneOutput.humanBytes(s.speedBytesPerSecond.toLong())}/s · ETA ${RcloneOutput.humanEta(s.etaSeconds)} · ${s.transfers}/${s.totalTransfers} files · ${s.errors} errors",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (expanded == job.id && state != null) {
                        Text(if (state.exit == null) "running…" else "finished: " + when (state.exit) { 0 -> "ok"; -2 -> "cancelled"; else -> "exit ${state.exit}" }, style = MaterialTheme.typography.labelSmall)
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp)) {
                            state.lines.takeLast(12).forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2) }
                        }
                    }
                }
            }
        }
    }
    if (adding || editing != null) {
        JobDialog(editing, remotes, onDismiss = { adding = false; editing = null },
            onSave = { j -> adding = false; editing = null; onChanged(store.upsert(j)) },
            onDelete = { j -> adding = false; editing = null; onChanged(store.remove(j.id)) })
    }
}

@Composable
private fun JobDialog(existing: RcloneJob?, remotes: List<RcloneRemote>, onDismiss: () -> Unit, onSave: (RcloneJob) -> Unit, onDelete: (RcloneJob) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var op by remember { mutableStateOf(existing?.op ?: "copy") }
    var source by remember { mutableStateOf(existing?.source ?: "") }
    var destination by remember { mutableStateOf(existing?.destination ?: "") }
    var flags by remember { mutableStateOf(existing?.flags?.joinToString(" ") ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New job" else "Edit job") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row { RcloneJob.OPS.forEach { o -> Row(Modifier.clickable { op = o }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = op == o, onClick = { op = o }); Text(o, style = MaterialTheme.typography.bodySmall) } } }
                OutlinedTextField(source, { source = it }, label = { Text("Source (remote:path or /local/path)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(destination, { destination = it }, label = { Text("Destination") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(flags, { flags = it }, label = { Text("Flags (e.g. --dry-run --exclude .git/**)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (remotes.isNotEmpty()) Text("remotes: " + remotes.joinToString(" ") { it.name + ":" }, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && source.isNotBlank() && destination.isNotBlank(), onClick = {
                onSave(RcloneJob(existing?.id ?: ("job-" + System.currentTimeMillis()), name.trim(), op, source.trim(), destination.trim(), flags.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null && !existing.declared) TextButton(onClick = { onDelete(existing) }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// ── Browse ───────────────────────────────────────────────────────────────

@Composable
private fun BrowseTab(runner: RcloneRunner, remotes: List<RcloneRemote>, onOpenFile: (String) -> Unit, notify: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var remote by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<RcloneEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(r: String, p: String) {
        busy = true; error = null
        scope.launch {
            val res = withContext(Dispatchers.IO) { runner.lsjson("$r:$p") }
            busy = false
            res.onSuccess { entries = it; remote = r; path = p }.onFailure { error = it.message ?: it.toString() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            remotes.forEach { r -> OutlinedButton(enabled = !busy, onClick = { load(r.name, "") }) { Text(r.name + ":") } }
        }
        if (remote == null && remotes.isEmpty()) Text("Add a remote first, then browse it here.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        remote?.let { r ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$r:/$path", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                if (path.isNotEmpty()) TextButton(enabled = !busy, onClick = { load(r, path.trimEnd('/').substringBeforeLast('/', "")) }) { Text("Up") }
            }
        }
        if (busy) CircularProgressIndicator(Modifier.padding(12.dp))
        error?.let { Text("cannot list: $it", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        LazyColumn(Modifier.fillMaxSize()) {
            if (remote != null && entries.isEmpty() && !busy && error == null) item { Text("(empty)", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            items(entries, key = { it.path }) { e ->
                ListItem(
                    headlineContent = { Text(e.name) },
                    supportingContent = { Text(if (e.isDir) "folder" else "${RcloneOutput.humanBytes(e.size)}  ${e.modTime.take(19).replace('T', ' ')}", style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { Icon(if (e.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile, contentDescription = null) },
                    modifier = Modifier.clickable {
                        val r = remote ?: return@clickable
                        if (e.isDir) load(r, if (path.isEmpty()) e.path else "$path/${e.name}")
                        else scope.launch {
                            notify("downloading ${e.name}…")
                            val res = withContext(Dispatchers.IO) { runner.download("$r:${if (path.isEmpty()) e.path else "$path/${e.name}"}", e.name) }
                            res.onSuccess { onOpenFile(it.absolutePath) }.onFailure { notify("download failed: ${it.message}") }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
    Spacer(Modifier.height(0.dp))
}
