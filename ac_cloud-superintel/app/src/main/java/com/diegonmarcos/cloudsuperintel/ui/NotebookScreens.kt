package com.diegonmarcos.cloudsuperintel.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudsuperintel.ChatTurn
import com.diegonmarcos.cloudsuperintel.FleetRank
import com.diegonmarcos.cloudsuperintel.Notebook
import com.diegonmarcos.cloudsuperintel.NotebookConfig
import com.diegonmarcos.cloudsuperintel.NotebookDoc
import com.diegonmarcos.cloudsuperintel.NotebookStore
import com.diegonmarcos.cloudsuperintel.R
import com.diegonmarcos.cloudsuperintel.SiAi
import com.diegonmarcos.cloudsuperintel.Source
import com.diegonmarcos.superapp.ai.AiMarkdownText
import com.diegonmarcos.superapp.ai.AiTheme
import com.diegonmarcos.superapp.ai.AiUrlHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object NotebookTags {
    const val NAME = "nb_name"
    const val CREATE = "nb_create"
    const val URL = "nb_url"
    const val ADD_URL = "nb_add_url"
    const val ADD_NOTE = "nb_add_note"
    const val ASK = "nb_ask"
    const val SEND = "nb_send"
    const val REFUSAL = "nb_refusal"
    fun open(id: String) = "nb_open_$id"
}

/** The notebooks of the process, in `<filesDir>/notebooks.json`; the open one is [selected]. */
class NotebookModel(ctx: Context) {
    private val store = NotebookStore(File(ctx.applicationContext.filesDir, "notebooks.json"))
    /** The served notebook config (#1547); [NotebookConfig.DEFAULT] while it loads or is not served, replaced when it arrives ([SiShell] collects [NotebookConfig.observe] into it). */
    var cfg by mutableStateOf(NotebookConfig.DEFAULT)
    var docs by mutableStateOf(store.all())
        private set
    var selected by mutableStateOf<String?>(null)
    val current: NotebookDoc? get() = docs.firstOrNull { it.id == selected }

    fun save(d: NotebookDoc) { store.put(d); docs = store.all() }
    fun create(name: String): NotebookDoc = NotebookDoc(UUID.randomUUID().toString(), name.trim()).also { save(it); selected = it.id }
    fun delete(id: String) { store.delete(id); docs = store.all(); if (selected == id) selected = null }
}

/** The notebook section's pages: list, sources, grounded chat, studio. [go] switches to another page of the section. */
@Composable
fun NotebookPage(model: NotebookModel, page: String, theme: AiTheme, go: (String) -> Unit, ids: NotebookIds) {
    val d = model.current
    when {
        page == ids.notebooks -> NotebookList(model, go, ids)
        d == null -> Hint(stringResource(R.string.nb_pick))
        page == ids.sources -> SourcesPage(model, d)
        page == ids.chat -> NotebookChat(model, d, theme)
        page == ids.studio -> StudioPage(model, d, theme)
    }
}

/** The page ids the shell dispatches the notebook section on (Decl.PAGE_NB_*). */
data class NotebookIds(val notebooks: String, val sources: String, val chat: String, val studio: String)

@Composable
private fun Hint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
}

@Composable
private fun NotebookList(model: NotebookModel, go: (String) -> Unit, ids: NotebookIds) {
    var name by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.weight(1f).testTag(NotebookTags.NAME), singleLine = true, label = { Text(stringResource(R.string.nb_name)) })
                Button({ if (name.isNotBlank()) { model.create(name); name = ""; go(ids.sources) } }, Modifier.testTag(NotebookTags.CREATE)) { Text(stringResource(R.string.nb_new)) }
            }
        }
        if (model.docs.isEmpty()) item { Hint(stringResource(R.string.nb_none)) }
        items(model.docs, key = { it.id }) { d ->
            Row(Modifier.fillMaxWidth().clickable { model.selected = d.id; go(ids.sources) }.testTag(NotebookTags.open(d.id)), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.nb_count, d.sources.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton({ model.delete(d.id) }) { Text(stringResource(R.string.nb_delete)) }
            }
        }
    }
}

@Composable
private fun SourcesPage(model: NotebookModel, d: NotebookDoc) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var noteTitle by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val unsupported = stringResource(R.string.nb_unsupported)
    val failed = stringResource(R.string.nb_failed)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val s = withContext(Dispatchers.IO) { runCatching { readDocument(ctx, uri, model.cfg) }.getOrNull() }
            if (s == null) status = unsupported else { model.save(d.copy(sources = d.sources + s)); status = "" }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(d.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it }, Modifier.weight(1f).testTag(NotebookTags.URL), singleLine = true, label = { Text(stringResource(R.string.nb_url)) })
                Button({
                    val u = url.trim()
                    if (u.startsWith("http://") || u.startsWith("https://")) scope.launch {
                        status = ""
                        val s = withContext(Dispatchers.IO) { runCatching { fetchUrl(u, model.cfg) }.getOrNull() }
                        if (s == null) status = failed else { model.save(d.copy(sources = d.sources + s)); url = "" }
                    }
                }, Modifier.testTag(NotebookTags.ADD_URL)) { Text(stringResource(R.string.nb_add)) }
            }
        }
        item {
            OutlinedTextField(noteTitle, { noteTitle = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nb_note_title)) })
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(noteText, { noteText = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.nb_note)) })
                Button({
                    if (noteText.isNotBlank()) {
                        model.save(d.copy(sources = d.sources + Source(UUID.randomUUID().toString(), noteTitle.ifBlank { noteText.take(32) }, "note", noteText.trim())))
                        noteTitle = ""; noteText = ""
                    }
                }, Modifier.testTag(NotebookTags.ADD_NOTE)) { Text(stringResource(R.string.nb_add)) }
            }
        }
        item { OutlinedButton({ picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.nb_file)) } }
        if (status.isNotEmpty()) item { Text(status, color = MaterialTheme.colorScheme.error) }
        if (d.sources.isEmpty()) item { Hint(stringResource(R.string.nb_no_sources)) }
        items(d.sources, key = { it.id }) { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("[${d.sources.indexOf(s) + 1}] ${s.title}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${s.kind} · ${s.text.length}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(s.text.take(160), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton({ model.save(d.copy(sources = d.sources.filterNot { it.id == s.id })) }) { Text(stringResource(R.string.nb_delete)) }
            }
        }
    }
}

/**
 * A document the user picked (any provider, Drive included, through the system picker); null when it is not readable text.
 * A PDF goes to the fleet extractor ([FleetRank.pdfText], mesh only): null when that does not answer.
 */
private fun readDocument(ctx: Context, uri: Uri, cfg: NotebookConfig): Source? {
    val r = ctx.contentResolver
    val mime = r.getType(uri).orEmpty()
    val name = r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment.orEmpty()
    if (mime == "application/pdf" || (mime.isEmpty() && name.endsWith(".pdf", ignoreCase = true))) {
        val bytes = r.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val pdf = FleetRank.shared(cfg)?.pdfText(bytes)?.take(cfg.urlMaxChars) ?: return null
        return Source(UUID.randomUUID().toString(), name, "doc", pdf, uri.toString())
    }
    if (!(mime.startsWith("text/") || mime in cfg.docMime)) return null
    val raw = r.openInputStream(uri)?.bufferedReader()?.use { it.readText() }?.take(cfg.urlMaxChars) ?: return null
    val text = if (mime == "text/html") Notebook.htmlText(raw) else raw
    return if (text.isBlank()) null else Source(UUID.randomUUID().toString(), name, "doc", text, uri.toString())
}

private fun fetchUrl(url: String, cfg: NotebookConfig): Source? {
    val r = AiUrlHttp.get(url, mapOf("User-Agent" to "Mozilla/5.0 (Android) CloudSuperIntel"), 30000)
    if (r.code !in 200..299) return null
    val html = r.body.take(cfg.urlMaxChars)
    val text = if (html.contains("<html", ignoreCase = true) || html.contains("<body", ignoreCase = true)) Notebook.htmlText(html) else html
    return if (text.isBlank()) null else Source(UUID.randomUUID().toString(), Notebook.title(html, url), "url", text, url)
}

@Composable
private fun NotebookChat(model: NotebookModel, d: NotebookDoc, theme: AiTheme) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (d.chat.isEmpty()) item { Hint(stringResource(if (d.sources.isEmpty()) R.string.nb_no_sources else R.string.nb_ask_hint)) }
            items(d.chat.size) { i -> Turn(d.chat[i], theme) }
            if (busy) item { Text(stringResource(R.string.nb_reading), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            error?.let { e -> item { Text(stringResource(R.string.nb_error, e), color = MaterialTheme.colorScheme.error) } }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(ask, { ask = it }, Modifier.weight(1f).testTag(NotebookTags.ASK), label = { Text(stringResource(R.string.nb_ask)) })
            Button({
                val q = ask.trim()
                if (q.isNotEmpty() && !busy) {
                    ask = ""; busy = true; error = null
                    val withQ = d.copy(chat = d.chat + ChatTurn("user", q))
                    model.save(withQ)
                    scope.launch {
                        val g = withContext(Dispatchers.IO) {
                            runCatching { Notebook.answerWith(q, d.sources, model.cfg, FleetRank.shared(model.cfg)) { sys, user -> SiAi.complete(ctx, sys, user).getOrThrow() } }
                        }
                        g.onSuccess { r ->
                            val t = ChatTurn("assistant", r.answer, r.grounded, r.cited.map { it.n }.distinct(), passages = r.cited.map { "${it.source.title}: ${it.text.take(240)}" })
                            model.save(withQ.copy(chat = withQ.chat + t))
                        }
                        g.onFailure { error = it.message ?: it.javaClass.simpleName }
                        busy = false
                    }
                }
            }, Modifier.testTag(NotebookTags.SEND), enabled = !busy) { Text(stringResource(R.string.chat_send)) }
        }
    }
}

@Composable
private fun Turn(t: ChatTurn, theme: AiTheme) {
    if (t.fromUser) {
        Text(t.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!t.grounded) {
            Text(stringResource(R.string.nb_not_in_sources), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(NotebookTags.REFUSAL))
            Text(t.text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            AiMarkdownText(t.text, theme)
            t.passages.forEachIndexed { i, p ->
                Text("[${t.cited.getOrNull(i) ?: ""}] $p", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun StudioPage(model: NotebookModel, d: NotebookDoc, theme: AiTheme) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                model.cfg.generators.forEach { g ->
                    OutlinedButton({
                        if (busy == null && d.sources.isNotEmpty()) {
                            busy = g.id; error = null
                            scope.launch {
                                val (sys, user) = Notebook.generatorPrompt(g, d.sources, model.cfg)
                                val r = withContext(Dispatchers.IO) { SiAi.complete(ctx, sys, user) }
                                r.onSuccess { model.save(d.copy(outputs = listOf(ChatTurn("assistant", it, title = g.label)) + d.outputs)) }
                                r.onFailure { error = it.message ?: it.javaClass.simpleName }
                                busy = null
                            }
                        }
                    }, enabled = busy == null) { Text(g.label) }
                }
            }
        }
        if (d.sources.isEmpty()) item { Hint(stringResource(R.string.nb_no_sources)) }
        busy?.let { item { Text(stringResource(R.string.nb_reading), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        error?.let { e -> item { Text(stringResource(R.string.nb_error, e), color = MaterialTheme.colorScheme.error) } }
        items(d.outputs.size) { i ->
            val o = d.outputs[i]
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(o.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                AiMarkdownText(o.text, theme)
            }
        }
    }
}
