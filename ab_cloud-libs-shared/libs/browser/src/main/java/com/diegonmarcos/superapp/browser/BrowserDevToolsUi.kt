package com.diegonmarcos.superapp.browser

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import org.json.JSONObject

/** #1141 what the debugger panels need from the host: the live WebView and the clipboard. */
class DevToolsBridge(val webView: () -> WebView?, val copy: (label: String, text: String) -> Unit) {
    private fun say(level: String, msg: String) =
        DevTools.console.add(ConsoleLog.entry(System.currentTimeMillis(), level, msg, null, 0, webView()?.url.orEmpty()))

    /** REPL: evaluate [expr] in the page, log the expression and its result as REPL lines. */
    fun run(expr: String) {
        val wv = webView() ?: return say("REPL", "no page is open")
        say("REPL", "> $expr")
        wv.evaluateJavascript(expr) { r -> say("REPL", "< ${r ?: "undefined"}") }
    }

    class Storage(val cookies: List<String>, val local: List<String>, val session: List<String>, val workers: List<String>)

    fun loadStorage(done: (Storage) -> Unit) {
        val wv = webView() ?: return done(Storage(emptyList(), emptyList(), emptyList(), emptyList()))
        val cookies = (wv.url?.let { CookieManager.getInstance().getCookie(it) }).orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }
        BrowserJs.await(wv, STORAGE_JS) { raw ->
            val v = runCatching { JSONObject(raw ?: "{}").optJSONObject("v") }.getOrNull() ?: JSONObject()
            fun kv(o: JSONObject?): List<String> = if (o == null) emptyList() else o.keys().asSequence().map { "$it = ${o.optString(it)}" }.toList()
            val sw = v.optJSONArray("sw")
            done(Storage(cookies, kv(v.optJSONObject("local")), kv(v.optJSONObject("session")),
                if (sw == null) emptyList() else (0 until sw.length()).map { sw.optString(it) }))
        }
    }

    /** Graphics tab: inject [GraphicsStats.JS] (idempotent) and parse what it sampled; blocked / no page degrades to n/a. */
    fun loadGraphics(done: (GraphicsStats) -> Unit) {
        val wv = webView() ?: return done(GraphicsStats.parse(null))
        BrowserJs.await(wv, GraphicsStats.JS) { done(GraphicsStats.parse(it)) }
    }

    fun clear(what: String, done: () -> Unit) {
        val wv = webView() ?: return done()
        when (what) {
            "cookies" -> BrowserStorage.clearSiteCookies(wv.url.orEmpty()) { done() }
            "local" -> BrowserJs.await(wv, "(function(){localStorage.clear();return 1})()") { done() }
            "session" -> BrowserJs.await(wv, "(function(){sessionStorage.clear();return 1})()") { done() }
            else -> BrowserJs.await(wv, BrowserRefresh.pageScript(RefreshPlan(false, false, false, true))) { done() }
        }
    }

    companion object {
        const val STORAGE_JS = "(async function(){var o={local:{},session:{},sw:[]};" +
            "try{for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);o.local[k]=localStorage.getItem(k)}}catch(e){}" +
            "try{for(var j=0;j<sessionStorage.length;j++){var s=sessionStorage.key(j);o.session[s]=sessionStorage.getItem(s)}}catch(e){}" +
            "try{var rs=await navigator.serviceWorker.getRegistrations();o.sw=rs.map(function(r){return r.scope+' ('+((r.active||r.waiting||r.installing||{}).state||'none')+')'})}catch(e){}" +
            "return o})()"
    }
}

private val MONO = FontFamily.Monospace

/** View source / Elements: line numbers, selectable text, search with next / previous, and a range copy by tapping line numbers. */
@Composable
fun BrowserSourceViewer(title: String, text: String, onCopy: (String) -> Unit, onClose: () -> Unit) {
    val p = LocalKitPalette.current
    val doc = remember(text) { SourceDoc(text) }
    var query by remember { mutableStateOf("") }
    var cur by remember { mutableIntStateOf(-1) }
    var sel by remember { mutableStateOf(LineSelection()) }
    val matches = remember(doc, query) { doc.search(query) }
    val list = rememberLazyListState()
    LaunchedEffect(cur, matches) { matches.getOrNull(cur)?.let { list.animateScrollToItem(it) } }
    Column(Modifier.fillMaxSize().background(p.surface).padding(8.dp).testTag("browser:source")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClose) { Text("Close") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it; cur = if (it.isEmpty()) -1 else 0 }, Modifier.weight(1f).testTag("browser:source:search"),
                singleLine = true, placeholder = { Text("Search") })
            Text(if (query.isEmpty()) "" else " ${if (matches.isEmpty()) 0 else cur + 1}/${matches.size} ", color = p.textSecondary)
            TextButton({ cur = doc.step(matches, cur, false) }) { Text("▲") }
            TextButton({ cur = doc.step(matches, cur, true) }) { Text("▼") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton({ onCopy(doc.text()) }) { Text("Copy all") }
            if (sel.active) TextButton({ onCopy(sel.text(doc)) }, modifier = Modifier.testTag("browser:source:copy-range")) { Text("Copy ${sel.count()} line(s)") }
            if (sel.active) TextButton({ sel = LineSelection() }) { Text("Clear selection") }
            if (!sel.active) Text("Tap line numbers to select a range", color = p.textSecondary, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 12.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            itemsIndexed(doc.lines) { i, line ->
                Row(Modifier.fillMaxWidth().background(if (sel.contains(i)) p.surfaceSelected else p.surface)) {
                    Text(doc.number(i) + " ", color = p.textSecondary, fontFamily = MONO, fontSize = 11.sp,
                        modifier = Modifier.clickable { sel = sel.tap(i) }.padding(end = 4.dp))
                    val shown = if (query.isEmpty()) null else buildAnnotatedString {
                        append(line)
                        doc.ranges(i, query).forEach { addStyle(SpanStyle(background = p.accent), it.first, it.last + 1) }
                    }
                    SelectionContainer(Modifier.weight(1f)) {
                        if (shown != null) Text(shown, color = p.textPrimary, fontFamily = MONO, fontSize = 11.sp)
                        else Text(line, color = p.textPrimary, fontFamily = MONO, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/** Console + JS REPL, Network, Storage and Graphics for the current page. [startTab]: 0 console, 1 network, 2 storage, 3 graphics. */
@Composable
fun BrowserDevToolsScreen(startTab: Int, bridge: DevToolsBridge, onClose: () -> Unit) {
    val p = LocalKitPalette.current
    var tab by remember { mutableIntStateOf(startTab) }
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val h = Handler(Looper.getMainLooper())
        var seen = -1
        val poll = object : Runnable {
            override fun run() {
                val v = DevTools.console.version * 31 + DevTools.net.version
                if (v != seen) { seen = v; tick++ }
                h.postDelayed(this, 400)
            }
        }
        h.post(poll)
        onDispose { h.removeCallbacks(poll) }
    }
    Column(Modifier.fillMaxSize().background(p.surface).padding(8.dp).testTag("browser:devtools")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Console", "Network", "Storage", "Graphics").forEachIndexed { i, t ->
                FilterChip(tab == i, { tab = i }, { Text(t) }, modifier = Modifier.testTag("browser:devtools:tab:$i"))
            }
            Text("", Modifier.weight(1f))
            TextButton(onClose) { Text("Close") }
        }
        when (tab) {
            0 -> ConsoleTab(tick, bridge)
            1 -> NetworkTab(tick, bridge)
            2 -> StorageTab(bridge)
            else -> GraphicsTab(bridge)
        }
    }
}

@Composable
private fun LogList(lines: List<Pair<String, androidx.compose.ui.graphics.Color>>, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.scrollToItem(lines.lastIndex) }
    SelectionContainer(modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            itemsIndexed(lines) { _, l -> Text(l.first, color = l.second, fontFamily = MONO, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

@Composable
private fun ColumnScope.ConsoleTab(tick: Int, bridge: DevToolsBridge) {
    val p = LocalKitPalette.current
    var levels by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    var expr by remember { mutableStateOf("") }
    val shown = ConsoleLog.filter(DevTools.console.snapshot(), levels, query)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ConsoleLog.LEVELS.forEach { l -> FilterChip(l in levels, { levels = if (l in levels) levels - l else levels + l }, { Text(l.lowercase()) }) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Filter") })
        TextButton({ DevTools.console.clear() }) { Text("Clear") }
        TextButton({ bridge.copy("console", ConsoleLog.asText(shown)) }) { Text("Copy") }
    }
    LogList(shown.map { e ->
        val at = e.location().let { if (it.isEmpty()) "" else "  $it" }
        "[${e.level}] ${e.message}$at" to when (e.level) {
            "ERROR" -> p.bad; "WARNING" -> p.warn; "REPL" -> p.accent; else -> p.textPrimary
        }
    }, Modifier.weight(1f).fillMaxWidth().testTag("browser:devtools:console"))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(expr, { expr = it }, Modifier.weight(1f).testTag("browser:devtools:repl"), placeholder = { Text("JavaScript, run in the page") }, maxLines = 3)
        TextButton({ if (expr.isNotBlank()) { bridge.run(expr); expr = "" } }) { Text("Run") }
    }
}

@Composable
private fun ColumnScope.NetworkTab(tick: Int, bridge: DevToolsBridge) {
    val p = LocalKitPalette.current
    var types by remember { mutableStateOf(emptySet<String>()) }
    var blocked by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val shown = NetLog.filter(DevTools.net.snapshot(), types, query, blocked)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(blocked, { blocked = !blocked }, { Text("blocked") })
        NetLog.TYPES.forEach { t -> FilterChip(t in types, { types = if (t in types) types - t else types + t }, { Text(t) }) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Filter by URL") })
        TextButton({ DevTools.net.clear() }) { Text("Clear") }
        TextButton({ bridge.copy("network", NetLog.asText(shown)) }) { Text("Copy") }
    }
    Text("${shown.size} request(s). The status shows for errors (4xx, 5xx) only: WebView reports no other.", color = p.textSecondary, style = MaterialTheme.typography.labelSmall)
    LogList(shown.map { NetLog.line(it) to (if (it.blocked) p.warn else if ((it.status ?: 0) >= 400) p.bad else p.textPrimary) },
        Modifier.weight(1f).fillMaxWidth().testTag("browser:devtools:network"))
}

@Composable
private fun StorageTab(bridge: DevToolsBridge) {
    val p = LocalKitPalette.current
    var data by remember { mutableStateOf<DevToolsBridge.Storage?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { bridge.loadStorage { data = it } }
    val d = data
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("browser:devtools:storage")) {
        TextButton({ reload++ }) { Text("Refresh") }
        if (d == null) Text("Reading the page…", color = p.textSecondary)
        else listOf(
            Triple("Cookies", "cookies", d.cookies), Triple("localStorage", "local", d.local),
            Triple("sessionStorage", "session", d.session), Triple("Service workers", "workers", d.workers),
        ).forEach { (title, key, rows) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$title (${rows.size})", color = p.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton({ bridge.clear(key) { reload++ } }) { Text("Clear") }
            }
            SelectionContainer {
                Text(if (rows.isEmpty()) "(none)" else rows.joinToString("\n") { it.take(300) }, color = p.textSecondary, fontFamily = MONO, fontSize = 11.sp)
            }
        }
    }
}

/** Graphics HUD: FPS, frame time, WebGL draw calls / triangles per frame and the GPU of the current page, sampled by injected JS. */
@Composable
private fun GraphicsTab(bridge: DevToolsBridge) {
    val p = LocalKitPalette.current
    var stats by remember { mutableStateOf<GraphicsStats?>(null) }
    var live by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload, live) {
        bridge.loadGraphics { stats = it }
        if (live) { kotlinx.coroutines.delay(1000); reload++ }
    }
    val s = stats
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("browser:devtools:graphics")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(live, { live = !live }, { Text("Live") }, modifier = Modifier.testTag("browser:devtools:graphics:live"))
            TextButton({ reload++ }) { Text("Sample") }
            TextButton({ s?.let { bridge.copy("graphics", it.asText()) } }) { Text("Copy") }
        }
        if (s == null) Text("Injecting the sampler…", color = p.textSecondary)
        else {
            s.rows().forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(k, color = p.textSecondary, modifier = Modifier.weight(1f))
                    Text(v, color = if (v == GraphicsStats.NA) p.textSecondary else p.textPrimary, fontFamily = MONO,
                        modifier = Modifier.testTag("browser:devtools:graphics:$k"))
                }
            }
            s.note?.let { Text(it, color = p.warn, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}
