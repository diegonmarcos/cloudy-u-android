package com.diegonmarcos.cloudkgstore.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudkgstore.KgApps
import com.diegonmarcos.cloudkgstore.KgCalls
import com.diegonmarcos.cloudkgstore.KgConfig
import com.diegonmarcos.cloudkgstore.KgPage
import com.diegonmarcos.cloudkgstore.KgServices
import com.diegonmarcos.cloudkgstore.R
import com.diegonmarcos.cloudsearch.core.CgcConfig
import com.diegonmarcos.cloudsearch.core.CgcEdge
import com.diegonmarcos.cloudsearch.core.CgcHit
import com.diegonmarcos.cloudsearch.core.CgcNode
import com.diegonmarcos.cloudsearch.core.Cgc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Cloud section's pages: [page.kind] picks the reader, the kg dataset says what it is told. */
@Composable
fun CloudPage(page: KgPage, svc: KgServices) {
    when (page.kind) {
        KgConfig.KIND_BINARY -> BinaryPage(page, svc)
        KgConfig.KIND_DEBUG -> DebugPage(page, svc)
        KgConfig.KIND_MCP -> McpPage(page, svc)
        KgConfig.KIND_CONSOLE -> ConsolePage(page, svc)
        KgConfig.KIND_CODE -> CodePage(svc)
        KgConfig.KIND_CGC -> CgcPage(svc)
        KgConfig.KIND_SEMANTIC -> CgcQueryPage(page, svc, graph = false)
        KgConfig.KIND_GRAPH -> CgcQueryPage(page, svc, graph = true)
    }
}

/** Runs [block] off the main thread and hands the result back. */
private fun <T> CoroutineScope.io(block: () -> T, done: (T) -> Unit) {
    launch { done(withContext(Dispatchers.IO) { block() }) }
}

// ── Application ▸ Binary ──────────────────────────────────────────────────────────────────────────

@Composable
private fun BinaryPage(page: KgPage, svc: KgServices) {
    var q by rememberSaveable { mutableStateOf("") }
    var probe by remember { mutableStateOf<KgCalls.Result<KgCalls.Reply>?>(null) }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(page.id) {
        probe = withContext(Dispatchers.IO) { svc.calls.request(page.service, KgCalls.METHOD_GET, page.probePath, "") }
        busy = false
    }
    val rows = KgApps.filter(svc.apps, q)
    Column(Modifier.fillMaxSize()) {
        KgQueryBar(q, { q = it }, stringResource(R.string.binary_hint), false, {}, null)
        KgNote(stringResource(R.string.binary_probe, page.service, page.probePath) + " " + statusText(probe, busy), isError(probe))
        if (rows.isEmpty()) KgNote(stringResource(R.string.binary_none))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(rows, key = { it.id }) { a ->
                KgRow(
                    a.name.ifBlank { a.label }, a.pkg + if (a.version.isBlank()) "" else " · " + a.version,
                    listOf(a.asset, a.image).filter { it.isNotBlank() }.joinToString(" · "),
                    onClick = if (a.releaseUrl.isBlank()) null else ({ svc.open(a.releaseUrl) }),
                )
            }
        }
    }
}

// ── Application ▸ Web/Debug ───────────────────────────────────────────────────────────────────────

@Composable
private fun DebugPage(page: KgPage, svc: KgServices) {
    var q by rememberSaveable { mutableStateOf("") }
    val rows = KgApps.debugRows(svc.apps, svc.ports, q)
    Column(Modifier.fillMaxSize()) {
        KgQueryBar(q, { q = it }, stringResource(R.string.binary_hint), false, {}, null)
        KgNote(stringResource(R.string.debug_summary, rows.count { it.port != null }, rows.size))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(rows, key = { it.app.id }) { r ->
                KgRow(
                    r.app.name.ifBlank { r.app.label }, r.app.pkg,
                    if (r.port != null) stringResource(R.string.debug_port, r.port, KgApps.gatewayRoute(page.gatewayRoute, r.app)) else page.missing,
                    onClick = if (r.app.repoUrl.isBlank()) null else ({ svc.open(r.app.repoUrl) }),
                )
            }
        }
    }
}

// ── MCP (both groups) ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun McpPage(page: KgPage, svc: KgServices) {
    if (page.services.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(8.dp)) { KgNote(page.missing) }
        return
    }
    var results by remember { mutableStateOf<Map<String, KgCalls.Result<KgCalls.Tool>>>(emptyMap()) }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(page.id) {
        val got = withContext(Dispatchers.IO) { page.services.associateWith { svc.calls.tools(it) } }
        results = got
        busy = false
    }
    LazyColumn(Modifier.fillMaxSize()) {
        page.services.forEach { s ->
            val r = results[s]
            item(key = "h-$s") { KgHeading(s) }
            item(key = "n-$s") { KgNote(statusText(r, busy, R.string.mcp_empty), isError(r)) }
            items(r?.items.orEmpty(), key = { "t-" + it.service + "-" + it.name }) { t ->
                KgRow(t.name, t.description, if (t.inputs.isEmpty()) "" else stringResource(R.string.mcp_inputs, t.inputs.joinToString(", ")))
            }
        }
    }
}

// ── API consoles (Data, Debug) ────────────────────────────────────────────────────────────────────

@Composable
private fun ConsolePage(page: KgPage, svc: KgServices) {
    val scope = rememberCoroutineScope()
    var service by rememberSaveable(page.id) { mutableStateOf(page.services.firstOrNull().orEmpty()) }
    var method by rememberSaveable(page.id) { mutableStateOf(page.methods.firstOrNull() ?: KgCalls.METHOD_GET) }
    val paths = svc.calls.services[service]?.consolePaths().orEmpty()
    var path by rememberSaveable(page.id, service) { mutableStateOf(paths.firstOrNull().orEmpty()) }
    var body by rememberSaveable(page.id) { mutableStateOf("") }
    var result by remember { mutableStateOf<KgCalls.Result<KgCalls.Reply>?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun send() {
        busy = true
        scope.io({ svc.calls.request(service, method, path, if (method == KgCalls.METHOD_POST) body else "") }) { result = it; busy = false }
    }
    Column(Modifier.fillMaxSize()) {
        KgChips(page.services.map { it to it }, service) { service = it; result = null }
        KgChips(page.methods.map { it to it }, method) { method = it }
        KgChips(paths.map { it to it }, path) { path = it }
        KgQueryBar(path, { path = it }, stringResource(R.string.console_path), busy, ::send, R.string.send)
        if (method == KgCalls.METHOD_POST) {
            KgQueryBar(body, { body = it }, stringResource(R.string.console_body), busy, ::send, R.string.send)
        }
        if (page.services.none { svc.calls.services.containsKey(it) }) KgNote(stringResource(R.string.console_missing), true)
        KgNote(statusText(result, busy), isError(result))
        val reply = result?.items?.firstOrNull()
        if (reply != null) {
            KgNote(stringResource(R.string.console_http, reply.code))
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                item { KgNote(reply.body.take(svc.kg.responseChars), mono = true) }
            }
        }
    }
}

// ── Repo ▸ Code ───────────────────────────────────────────────────────────────────────────────────

@Composable
private fun CodePage(svc: KgServices) {
    var q by rememberSaveable { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val cloud = svc.search.cloud
    val none = stringResource(R.string.code_nothing)
    val opened = stringResource(R.string.code_opened)
    Column(Modifier.fillMaxSize()) {
        KgQueryBar(q, { q = it }, stringResource(R.string.code_hint), false, {
            val url = cloud?.codeUrl(q)
            if (url == null) note = none else { svc.open(url); note = opened + " " + url }
        })
        if (cloud == null) KgNote(stringResource(R.string.code_undeclared), true)
        KgNote(stringResource(R.string.code_body, cloud?.codeOwner.orEmpty()))
        KgNote(note)
    }
}

// ── Repo ▸ CGC ────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun CgcPage(svc: KgServices) {
    val indexes = svc.search.cgc?.indexes.orEmpty()
    var health by remember { mutableStateOf<Map<String, KgCalls.Result<String>>>(emptyMap()) }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        health = withContext(Dispatchers.IO) { indexes.associate { it.id to svc.calls.health(it) } }
        busy = false
    }
    if (indexes.isEmpty()) {
        Column(Modifier.fillMaxSize()) { KgNote(stringResource(R.string.cgc_undeclared), true) }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        indexes.forEach { ix ->
            val h = health[ix.id]
            item(key = "h-" + ix.id) { KgHeading(ix.label + " · " + ix.service) }
            item(key = "n-" + ix.id) {
                val line = if (h != null && h.kind == KgCalls.Kind.OK) stringResource(R.string.cgc_health, h.items.firstOrNull().orEmpty(), h.via) else statusText(h, busy)
                KgNote(line, isError(h))
            }
            items(ix.repos, key = { "r-" + ix.id + "-" + it }) { repo -> KgRow(repo, ix.web(repo, "", null).substringBefore("/blob/"), onClick = { svc.open(ix.web(repo, "", null).substringBefore("/blob/")) }) }
        }
    }
}

// ── Repo ▸ API Semantic / API GraphRAG ────────────────────────────────────────────────────────────

@Composable
private fun CgcQueryPage(page: KgPage, svc: KgServices, graph: Boolean) {
    val cfg = svc.search.cgc
    val indexes = cfg?.indexes.orEmpty()
    if (cfg == null || indexes.isEmpty()) {
        Column(Modifier.fillMaxSize()) { KgNote(stringResource(R.string.cgc_undeclared), true) }
        return
    }
    val scope = rememberCoroutineScope()
    var ixId by rememberSaveable(page.id) { mutableStateOf(indexes.first().id) }
    val ix = indexes.firstOrNull { it.id == ixId } ?: indexes.first()
    var repo by rememberSaveable(page.id, ix.id) { mutableStateOf(ix.repos.first()) }
    var q by rememberSaveable(page.id) { mutableStateOf("") }
    var hits by remember { mutableStateOf<KgCalls.Result<CgcHit>?>(null) }
    var scores by remember { mutableStateOf<KgCalls.Result<Double>?>(null) }
    var nodes by remember { mutableStateOf<KgCalls.Result<CgcNode>?>(null) }
    var openNode by remember { mutableStateOf<CgcNode?>(null) }
    var edges by remember { mutableStateOf<KgCalls.Result<CgcEdge>?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun go() {
        if (q.isBlank()) return
        busy = true; openNode = null; edges = null
        if (graph) scope.io({ svc.calls.graph(ix, repo, q) }) { nodes = it; busy = false }
        else scope.io({
            val h = svc.calls.semantic(ix, repo, q)
            val sc = page.rerank?.let { r -> if (h.kind == KgCalls.Kind.OK) svc.calls.rerank(r, q, h.items.map { it.snippet.ifBlank { it.path } }) else null }
            h to sc
        }) { (h, sc) -> hits = h; scores = sc; busy = false }
    }
    // The hits in the reranker's order when it answered, else in the code index's.
    val ordered = hits?.items.orEmpty().let { list ->
        val s = scores
        if (s != null && s.kind == KgCalls.Kind.OK && s.items.size == list.size) list.zip(s.items).sortedByDescending { it.second }.map { it.first } else list
    }
    Column(Modifier.fillMaxSize()) {
        KgChips(indexes.map { it.id to it.label }, ix.id) { ixId = it; hits = null; nodes = null; scores = null }
        KgChips(ix.repos.map { it to (ix.repoNames[it] ?: it) }, repo) { repo = it }
        KgQueryBar(q, { q = it }, stringResource(if (graph) R.string.graph_hint else R.string.semantic_hint), busy, ::go)
        val main: KgCalls.Result<*>? = if (graph) nodes else hits
        KgNote(statusText(main, busy, if (ix.needsAccount) R.string.cgc_private_empty else R.string.status_empty), isError(main))
        if (!graph) {
            val s = scores
            if (s != null) KgNote(
                if (s.kind == KgCalls.Kind.OK) stringResource(R.string.semantic_reranked, s.via) else stringResource(R.string.semantic_not_reranked, s.detail.ifBlank { stringResource(R.string.status_empty) }),
                false,
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (graph) {
                items(nodes?.items.orEmpty()) { n ->
                    KgRow(n.path, n.kind, n.description, onClick = {
                        openNode = n; edges = null
                        scope.io({ svc.calls.related(ix, repo, n.id) }) { edges = it }
                    })
                    if (openNode?.id == n.id) {
                        val e = edges
                        KgNote(statusText(e, e == null, R.string.cgc_related_none), isError(e))
                        val (callers, callees) = Cgc.split(n.id, e?.items.orEmpty())
                        callers.forEach { KgRow(stringResource(R.string.graph_from, it.source), it.type, it.description) }
                        callees.forEach { KgRow(stringResource(R.string.graph_to, it.target), it.type, it.description) }
                    }
                }
            } else {
                items(ordered) { h ->
                    KgRow(h.path + (h.line?.let { ":$it" } ?: ""), h.snippet, h.symbols.joinToString(", "), onClick = { svc.open(ix.web(h.repo, h.path, h.line)) })
                }
            }
        }
    }
}
