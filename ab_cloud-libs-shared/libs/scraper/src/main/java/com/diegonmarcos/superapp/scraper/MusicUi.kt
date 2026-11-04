package com.diegonmarcos.superapp.scraper

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.WebProfile
import com.diegonmarcos.superapp.themebg.ThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val LIKED = Playlist("::liked", "Liked songs")

private fun connectColor(s: ConnectStatus) = when (s) {
    ConnectStatus.CONNECTED -> Color(0xFF4CAF50)
    ConnectStatus.WEB -> Color(0xFF42A5F5)
    ConnectStatus.EXPIRED -> Color(0xFFFFB300)
    ConnectStatus.DISCONNECTED -> Color(0xFF757575)
}

private fun fileStem(s: String) = s.replace(Regex("""[^\p{L}\p{N}._-]+"""), "_").trim('_').ifEmpty { "music" }

/** A full-screen web view: the sign-in page (client intercepts the redirect) or the web player ([extra] are its buttons). */
@Composable
private fun WebSheet(url: String, title: String, c: ThemeColors, client: WebViewClient?, onClose: () -> Unit, extra: @Composable RowScope.(WebView?) -> Unit = {}) {
    var wv by remember { mutableStateOf<WebView?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.top).testTag("music:web")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = c.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                extra(wv)
                TextButton(onClick = onClose) { Text("Close", color = c.accent) }
            }
            AndroidView(
                factory = { cx -> CloudWeb.create(cx, WebProfile.BROWSER) { w -> if (client != null) w.webViewClient = client }.also { wv = it; it.loadUrl(url) } },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

/** #1459 Music: one dense row per service (status dot, collapsed actions), its playlists below, sync and export per playlist. */
@Composable
fun MusicPage(c: ThemeColors) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val http = remember { MusicHttp() }
    val accounts = remember { MusicAccounts(EncryptedTokenStore(ctx)) }
    var rev by remember { mutableStateOf(0) }            // bumps when a token changes, so the status dots redraw
    var open by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val lists = remember { mutableStateMapOf<String, List<Playlist>>() }
    val webRows = remember { mutableStateMapOf<String, List<Track>>() }
    var webOf by remember { mutableStateOf<ServiceSpec?>(null) }
    var oauth by remember { mutableStateOf<Triple<ServiceSpec, String, String>?>(null) }   // service, state, PKCE verifier
    var sync by remember { mutableStateOf<Pair<ServiceSpec, Playlist>?>(null) }
    var target by remember { mutableStateOf<String?>(null) }
    var dry by remember { mutableStateOf(true) }
    var plan by remember { mutableStateOf<SyncPlan?>(null) }
    var pendingText by remember { mutableStateOf("") }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(pendingText.toByteArray()) }; "Saved" }.getOrElse { "Save failed: ${it.message}" }
            }
        }
    }

    /** The connector of [spec]: its API when signed in (a refresh token renews an expired one), else the web rows read so far. */
    suspend fun connector(spec: ServiceSpec): MusicConnector? = withContext(Dispatchers.IO) {
        if (spec.auth == AuthKind.WEB) return@withContext webRows[spec.id]?.let { SnapshotConnector(spec.id, spec.label + " page", it) }
        var t = accounts.access(spec.id)
        val r = accounts.refreshToken(spec.id)
        if (t == null && r != null) t = runCatching {
            MusicOAuth.refresh(http, spec, accounts.clientId(spec.id), accounts.clientSecret(spec.id), r).also { accounts.save(spec.id, it) }.access
        }.getOrNull()
        t?.let { MusicConnectors.create(spec, it, http) }
    }

    fun load(spec: ServiceSpec) = scope.launch {
        busy = "Reading ${spec.label}…"
        val r = runCatching { connector(spec)?.let { cn -> withContext(Dispatchers.IO) { cn.playlists() } } }
        busy = ""
        r.onSuccess { p -> if (p == null) message = "${spec.label}: not connected" else lists[spec.id] = (if (spec.auth == AuthKind.WEB) emptyList() else listOf(LIKED)) + p }
            .onFailure { message = "${spec.label}: ${it.message}" }
    }

    fun tracksOf(cn: MusicConnector, p: Playlist) = if (p.id == LIKED.id) cn.liked() else cn.tracks(p)

    fun export(spec: ServiceSpec, p: Playlist, json: Boolean) = scope.launch {
        busy = "Reading ${p.name}…"
        val r = runCatching { val cn = connector(spec) ?: error("not connected"); withContext(Dispatchers.IO) { tracksOf(cn, p) } }
        busy = ""
        r.onSuccess { ts ->
            val rows = ts.map { p.name to it }
            val at = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
            pendingText = if (json) MusicExport.json(rows, spec.id, at) else MusicExport.csv(rows)
            saver.launch(fileStem("${spec.id}-${p.name}") + if (json) ".json" else ".csv")
        }.onFailure { message = "Export failed: ${it.message}" }
    }

    fun preview(spec: ServiceSpec, p: Playlist, to: ServiceSpec) = scope.launch {
        plan = null
        val r = runCatching {
            val src = connector(spec) ?: error("${spec.label} is not connected")
            val dst = connector(to) ?: error("${to.label} is not connected")
            withContext(Dispatchers.IO) {
                val ts = tracksOf(src, p)
                MusicSync.plan(p.name, ts, dst) { d, n -> busy = "Matching $d / $n" }
            }
        }
        busy = ""
        r.onSuccess { plan = it; message = it.summary() }.onFailure { message = "Preview failed: ${it.message}" }
    }

    fun run(to: ServiceSpec) = scope.launch {
        val pl = plan ?: return@launch
        val r = runCatching { val dst = connector(to) ?: error("not connected"); withContext(Dispatchers.IO) { MusicSync.run(pl, dst, dry) } }
        r.onSuccess { message = if (it.dryRun) "Dry run: nothing was written. ${pl.summary()}" else "Added ${it.added} tracks to ${to.label}" }
            .onFailure { message = "Sync failed: ${it.message}" }
    }

    fun device(spec: ServiceSpec) = scope.launch {
        val id = accounts.clientId(spec.id)
        val r = runCatching {
            val dc = withContext(Dispatchers.IO) { MusicOAuth.startDevice(http, spec, id) }
            message = "Open ${dc.url} and enter ${dc.userCode}"
            var waited = 0L
            var t: TokenSet? = null
            while (t == null && waited < dc.expiresInSec) {
                delay(dc.intervalSec * 1000); waited += dc.intervalSec
                t = withContext(Dispatchers.IO) { MusicOAuth.pollDevice(http, spec, id, accounts.clientSecret(spec.id), dc.deviceCode) }
            }
            t ?: error("the code expired")
        }
        r.onSuccess { accounts.save(spec.id, it); rev++; message = "${spec.label} connected"; load(spec) }.onFailure { message = "${spec.label}: ${it.message}" }
    }

    oauth?.let { (spec, state, verifier) ->
        val client = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val cb = MusicOAuth.callback(request.url.toString(), spec, state) ?: return false
                oauth = null
                scope.launch {
                    val r = runCatching {
                        cb.error?.let { error(it) }
                        if (cb.token != null) TokenSet(cb.token, null, cb.expiresInSec)
                        else withContext(Dispatchers.IO) { MusicOAuth.exchange(http, spec, accounts.clientId(spec.id), cb.code ?: error("no code"), verifier) }
                    }
                    r.onSuccess { accounts.save(spec.id, it); rev++; message = "${spec.label} connected"; load(spec) }.onFailure { message = "${spec.label}: ${it.message}" }
                }
                return true
            }
        }
        val url = MusicOAuth.authUrl(spec, accounts.clientId(spec.id), state, if (spec.auth == AuthKind.PKCE) MusicOAuth.challenge(verifier) else null)
        WebSheet(url, "Sign in to ${spec.label}", c, client, onClose = { oauth = null })
    }

    webOf?.let { spec ->
        val web = spec.web!!
        WebSheet(web.url, "${spec.label} web player", c, null, onClose = { webOf = null; if (webRows[spec.id] != null) load(spec) }) { wv ->
            Text("${webRows[spec.id]?.size ?: 0} read", color = c.inkDim, modifier = Modifier.padding(end = 8.dp).testTag("music:web:count"))
            TextButton(onClick = {
                wv?.evaluateJavascript(WebPlayer.script(web)) { raw ->
                    webRows[spec.id] = WebPlayer.merge(webRows[spec.id].orEmpty(), runCatching { WebPlayer.parse(raw) }.getOrDefault(emptyList()))
                }
            }, modifier = Modifier.testTag("music:web:read")) { Text("Read this page", color = c.accent) }
        }
    }

    val specs = MusicCatalog.services
    LazyColumn(Modifier.fillMaxSize().testTag("music:list")) {
        item(key = "notice") {
            Text(MusicCatalog.NOTICE, color = c.inkDim, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("music:notice"))
        }
        if (busy.isNotEmpty() || message.isNotEmpty()) item(key = "status") {
            Text(busy.ifEmpty { message }, color = c.ink, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("music:status"))
        }
        for (spec in specs) {
            val st = rev.let { accounts.status(spec) }   // reading rev redraws the dot after a connect
            val isOpen = open == spec.id
            item(key = "svc:" + spec.id) {
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clickable { open = if (isOpen) null else spec.id; if (!isOpen && lists[spec.id] == null && st == ConnectStatus.CONNECTED) load(spec) }
                    .padding(horizontal = 12.dp, vertical = 6.dp).testTag("music:svc:${spec.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isOpen) "▾" else "▸", color = c.accent, modifier = Modifier.width(18.dp))
                    Box(Modifier.size(9.dp).background(connectColor(st), CircleShape).testTag("music:dot:${spec.id}"))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(spec.label, color = c.ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(st.label + if (spec.isrc) " · ISRC" else "", color = c.inkDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("music:status:${spec.id}"))
                    }
                    Box {
                        TextButton(onClick = { menu = true }, modifier = Modifier.testTag("music:menu:${spec.id}")) { Text("⋮", color = c.ink) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (spec.auth == AuthKind.WEB) {
                                DropdownMenuItem(text = { Text("Open web player") }, onClick = { menu = false; webOf = spec })
                            } else {
                                DropdownMenuItem(text = { Text(if (st == ConnectStatus.CONNECTED) "Reconnect" else "Connect") }, onClick = { menu = false; connecting = spec.id; open = spec.id })
                                if (st != ConnectStatus.DISCONNECTED) {
                                    DropdownMenuItem(text = { Text("Reload playlists") }, onClick = { menu = false; open = spec.id; load(spec) })
                                    DropdownMenuItem(text = { Text("Disconnect") }, onClick = { menu = false; accounts.disconnect(spec.id); lists.remove(spec.id); rev++ })
                                }
                            }
                        }
                    }
                }
            }
            if (isOpen && spec.auth != AuthKind.WEB && (connecting == spec.id || st != ConnectStatus.CONNECTED)) item(key = "conn:" + spec.id) {
                var cid by remember { mutableStateOf(accounts.clientId(spec.id)) }
                var sec by remember { mutableStateOf("") }
                Column(Modifier.fillMaxWidth().padding(start = 40.dp, end = 12.dp, bottom = 8.dp).testTag("music:connect:${spec.id}")) {
                    Text(spec.help, color = c.inkDim, style = MaterialTheme.typography.bodySmall)
                    if (spec.redirect.isNotEmpty()) Text("Redirect URI: ${spec.redirect}", color = c.ink, style = MaterialTheme.typography.bodySmall)
                    val colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.ink, unfocusedTextColor = c.ink, focusedLabelColor = c.accent, unfocusedLabelColor = c.inkDim)
                    OutlinedTextField(cid, { cid = it }, label = { Text("Client id") }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth().testTag("music:cid:${spec.id}"))
                    if (spec.needsSecret) OutlinedTextField(sec, { sec = it }, label = { Text("Client secret (stored encrypted)") }, singleLine = true, colors = colors,
                        modifier = Modifier.fillMaxWidth().testTag("music:secret:${spec.id}"))
                    Button(onClick = {
                        accounts.setClient(spec.id, cid, sec)
                        if (spec.auth == AuthKind.DEVICE) device(spec) else oauth = Triple(spec, MusicOAuth.verifier().take(16), MusicOAuth.verifier())
                        connecting = null
                    }, enabled = cid.isNotBlank() && (!spec.needsSecret || sec.isNotBlank() || accounts.clientSecret(spec.id).isNotEmpty()),
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent), modifier = Modifier.padding(top = 4.dp).testTag("music:signin:${spec.id}")) { Text("Sign in") }
                }
            }
            if (isOpen) {
                val ps = lists[spec.id]
                if (ps == null && st == ConnectStatus.WEB) item(key = "hint:" + spec.id) {
                    Text(if (webRows[spec.id] == null) "Open the web player from ⋮, sign in, open a playlist, tap Read this page." else "Reading…", color = c.inkDim,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 40.dp, end = 12.dp, bottom = 8.dp))
                }
                for (p in ps.orEmpty()) item(key = "pl:" + spec.id + ":" + p.id) {
                    var menu by remember { mutableStateOf(false) }
                    val others = specs.filter { it.write && it.id != spec.id && accounts.status(it) == ConnectStatus.CONNECTED }
                    Column(Modifier.fillMaxWidth().testTag("music:pl:${p.id}")) {
                        Row(Modifier.fillMaxWidth().padding(start = 40.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, color = c.ink, maxLines = 1, modifier = Modifier.weight(1f))
                            if (p.count > 0) Text("${p.count}", color = c.inkDim, style = MaterialTheme.typography.bodySmall)
                            Box {
                                TextButton(onClick = { menu = true }) { Text("⋮", color = c.ink) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Export CSV") }, onClick = { menu = false; export(spec, p, false) })
                                    DropdownMenuItem(text = { Text("Export JSON") }, onClick = { menu = false; export(spec, p, true) })
                                    DropdownMenuItem(text = { Text("Sync to…") }, enabled = others.isNotEmpty(),
                                        onClick = { menu = false; sync = spec to p; target = others.firstOrNull()?.id; plan = null; dry = true })
                                }
                            }
                        }
                        val sy = sync
                        if (sy != null && sy.first.id == spec.id && sy.second.id == p.id) SyncPanel(c, others, target, dry, plan,
                            onTarget = { target = it; plan = null }, onDry = { dry = it },
                            onPreview = { specs.firstOrNull { s -> s.id == target }?.let { preview(spec, p, it) } },
                            onRun = { specs.firstOrNull { s -> s.id == target }?.let { run(it) } },
                            onClose = { sync = null; plan = null })
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncPanel(
    c: ThemeColors, targets: List<ServiceSpec>, target: String?, dry: Boolean, plan: SyncPlan?,
    onTarget: (String) -> Unit, onDry: (Boolean) -> Unit, onPreview: () -> Unit, onRun: () -> Unit, onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 40.dp, end = 12.dp, bottom = 8.dp).background(c.island, RoundedCornerShape(10.dp)).padding(10.dp).testTag("music:sync")) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Text("To", color = c.onIsland, modifier = Modifier.padding(end = 8.dp))
            for (t in targets) Chip(t.label, target == t.id, c, "music:target:${t.id}") { onTarget(t.id) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(dry, onDry, modifier = Modifier.testTag("music:dry"))
            Text("Dry run (write nothing)", color = c.onIsland, style = MaterialTheme.typography.bodySmall)
        }
        Row {
            Button(onClick = onPreview, colors = ButtonDefaults.buttonColors(containerColor = c.accent), modifier = Modifier.testTag("music:preview")) { Text("Preview") }
            Button(onClick = onRun, enabled = plan != null, colors = ButtonDefaults.buttonColors(containerColor = c.accent),
                modifier = Modifier.padding(start = 8.dp).testTag("music:run")) { Text(if (dry) "Dry run" else "Sync") }
            TextButton(onClick = onClose) { Text("Close", color = c.onIsland) }
        }
        if (plan != null) {
            Text(plan.summary(), color = c.onIsland, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            for (e in plan.entries.take(200)) {
                val mark = when (e.match.how) { How.ISRC -> "ISRC"; How.FUZZY -> "%.2f".format(e.match.score); How.NONE -> "none" }
                Text("$mark  ${e.source.title} – ${e.source.artist}" + (e.match.candidate?.let { "  →  ${it.title} – ${it.artist}" } ?: ""),
                    color = if (e.match.how == How.NONE) Color(0xFFFF8A80) else c.onIsland, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            if (plan.entries.size > 200) Text("+${plan.entries.size - 200} more", color = c.onIsland, style = MaterialTheme.typography.bodySmall)
        }
    }
}
