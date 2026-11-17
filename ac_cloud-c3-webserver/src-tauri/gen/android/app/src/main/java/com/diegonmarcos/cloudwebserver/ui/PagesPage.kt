package com.diegonmarcos.cloudwebserver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudwebserver.Api
import com.diegonmarcos.cloudwebserver.Decl
import com.diegonmarcos.cloudwebserver.absolute
import org.json.JSONArray
import org.json.JSONObject

/** The web shell's ROOT_STATUS: badge tone and words per /__app__/api/roots status. */
private val ROOT_STATUS = mapOf(
    "ready" to (Tone.Up to "ready"),
    "missing" to (Tone.Warn to "not cloned"),
    "offline" to (Tone.Warn to "app not open"),
    "no_access" to (Tone.Warn to "no storage access"),
    "error" to (Tone.Warn to "error"),
)

/**
 * #1677 / #1682 THE PAGES SECTION, native: the second row All | File Explorer | Pages (build.json::ui pages of `pages`) over
 * build.json::roots (/__app__/api/roots). File Explorer opens a root as two columns (the lazily expanded tree left, the selected file
 * rendered right); Pages opens a root in a browser frame with a toolbar (back, location, reload, open in cloud-browser); All lists
 * every root of both kinds, each opening in its own mode, and keeps the served-pages list (its add form and the undeclared checker)
 * and the Git Sites rows below (rule 16).
 */
@Composable
fun PagesSection(sub: String, open: (String, String?) -> Unit = { _, _ -> }) {
    var openRoot by rememberSaveable(sub) { mutableStateOf<String?>(null) }
    val (tick, bump) = rememberTick()
    val roots = rememberObj(Api.ROOTS, tick)
    val list = (roots as? Load.Ok)?.value?.optJSONArray("roots").objs()
    val r = openRoot?.let { id -> list.firstOrNull { it.optString("id") == id } }
    if (r != null) {
        if (r.optString("mode") == "pages") BrowserRoot(r) { openRoot = null } else ExplorerRoot(r) { openRoot = null }
        return
    }
    PageColumn("c3_pages_$sub") {
        ChipRow { Chip("↻ Refresh", "c3_pages_refresh") { bump() } }
        Loaded(roots, "build.json::roots") { d ->
            if (d.has("error")) ErrorNote("build.json::roots: ${d.str("error")}")
            val all = d.optJSONArray("roots").objs()
            when (sub) {
                Decl.PAGE_EXPLORER -> RootList("File Explorer roots", all.filter { it.optString("mode") == "explorer" }, "no File Explorer root declared", bump) { openRoot = it }
                Decl.PAGE_BROWSER -> RootList("Pages: served like a browser", all.filter { it.optString("mode") == "pages" }, "no Pages root declared", bump) { openRoot = it }
                else -> RootList("All roots", all, "none declared", bump) { openRoot = it }
            }
        }
        if (sub == Decl.PAGE_ALL) {
            ServedPages(tick, bump, open)
            GitSites(tick, bump)
        }
    }
}

@Composable
private fun RootList(title: String, rows: List<JSONObject>, empty: String, bump: () -> Unit, open: (String) -> Unit) {
    val actions = rememberActions(bump)
    val ctx = LocalContext.current
    Card("$title · ${rows.size}", "c3_roots") {
        if (rows.isEmpty()) Note(empty)
        rows.forEachIndexed { i, r ->
            if (i > 0) Hairline()
            val (tone, words) = ROOT_STATUS[r.optString("status")] ?: ROOT_STATUS.getValue("error")
            val ready = r.optString("status") == "ready"
            val pages = r.optString("mode") == "pages"
            val target = rootTarget(r)
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp).testTag("c3_root_${r.optString("id")}"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(ready)
                    Spacer(Modifier.width(7.dp))
                    Text(r.str("label"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Badge(if (pages) "Pages" else "File Explorer")
                    Badge(words, tone)
                }
                val where = when {
                    r.has("repo") && !r.isNull("repo") && r.optString("repo").isNotEmpty() -> "~/" + r.str("repo")
                    r.has("note") && !r.isNull("note") && r.optString("note").isNotEmpty() -> r.str("note")
                    else -> ""
                }
                Text(listOf(r.optString("source_label").ifEmpty { r.str("source") }, where).filter { it.isNotEmpty() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (r.has("reason") && !r.isNull("reason") && r.optString("reason").isNotEmpty()) Text(r.str("reason"), style = MaterialTheme.typography.labelSmall, color = Tone.Warn)
                val clone = r.optString("clone")
                if (r.optString("status") == "missing" && clone.isNotEmpty()) {
                    Text("Clone it on the phone (Cloud Termux), then reopen this tab:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(clone, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().background(Tone.Card, RoundedCornerShape(6.dp)).padding(6.dp).testTag("c3_clone_${r.optString("id")}"))
                }
                val perms = r.optJSONObject("perms"); val startApp = r.optJSONObject("start_app")
                val drive = r.optString("drive_url").takeIf { it.isNotEmpty() && !r.isNull("drive_url") }
                ChipRow(inset = false) {
                    if (ready) Chip(if (pages) "▶ Open page" else "▶ Explore", "c3_root_open_${r.optString("id")}", primary = true) { open(r.optString("id")) }
                    if (startApp != null) Chip("▶ Start app", "c3_root_start_app", primary = !ready) { actions.run(startApp) }
                    if (perms != null) Chip(perms.optString("label", "Perms"), "c3_root_perms") { actions.run(perms) }
                    if (r.optString("status") == "missing" && clone.isNotEmpty()) Chip("Copy command", "c3_copy_clone") { copyText(ctx, "clone", clone) }
                    if (ready) {
                        Chip("Open in Browser", "c3_root_browser") { actions.post(Api.OPEN, JSONObject().put("url", target)) { "Opening in cloud-browser" } }
                        Chip("Open Embedded", "c3_root_embedded") { Links.embedded = Links.embedUrl(target) }
                        if (drive != null) {
                            Chip("Open Folder (Drive)", "c3_root_folder") { openLink(ctx, drive, actions) }
                            Chip("Copy path", "c3_root_copy_path") { copyText(ctx, "path", "/storage/emulated/0/" + r.optString("repo")) }
                        }
                    }
                }
            }
        }
    }
}

/** A hairline between the rows of a card. */
@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tone.Line))
}

/** The action chips of one row, wrapped: dense, one visual group per row. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(inset: Boolean = true, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(start = if (inset) 12.dp else 0.dp, end = if (inset) 12.dp else 0.dp, top = 2.dp)) { content() }
}

/** A dense action chip (console grade): 28 dp high, hairline border, the accent fill for the row's primary action. Colours are kit / theme tokens only. */
@Composable
private fun Chip(label: String, tag: String, primary: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    val frame = Modifier.padding(end = 6.dp, bottom = 5.dp).height(28.dp).clip(shape)
        .let { if (primary) it.background(MaterialTheme.colorScheme.primary, shape) else it.background(Tone.Card, shape).border(1.dp, Tone.Line, shape) }
    Box(frame.clickable(onClick = onClick).padding(horizontal = 10.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
            color = if (primary) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/** The URL a root is opened at outside the in-app frame: its own origin (the shared server's full UI) when it has one, else the admin root path. */
private fun rootTarget(r: JSONObject): String =
    r.optString("frame_url").takeIf { it.isNotEmpty() && !r.isNull("frame_url") } ?: Api.url(Api.rootPath(r.optString("id"), ""))

/** The toolbar over an open root (renderRootBar): back to the list, the name, where it is, reload, open in cloud-browser, open embedded, and the view switch. */
@Composable
private fun RootBar(r: JSONObject, path: String, handle: FrameHandle, close: () -> Unit, extUrl: String, view: (@Composable () -> Unit)? = null) {
    val actions = rememberActions {}
    Column(Modifier.fillMaxWidth().background(Tone.Card).testTag("c3_rbar")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("‹ Roots", "c3_root_close") { if (!handle.back()) close() }
            Text(r.str("label"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 6.dp))
            Text("/$path", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("c3_rloc"))
            if (handle.view != null) LinkText("✕", "c3_root_exit") { close() }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 8.dp, top = 0.dp)) {
            view?.invoke()
            Chip("↻ Reload", "c3_root_reload") { handle.reload() }
            Chip("↗ Browser", "c3_root_ext") { actions.post(Api.OPEN, JSONObject().put("url", extUrl)) { "Opening in cloud-browser" } }
            Chip("⧉ Embedded", "c3_root_embed") { Links.embedded = Links.embedUrl(extUrl) }
        }
        Hairline()
    }
}

/** Pages mode: the root served like a browser would; its index opens, its links navigate inside /__app__/root/<id>/. */
@Composable
private fun BrowserRoot(r: JSONObject, close: () -> Unit) {
    val handle = remember(r.optString("id")) { FrameHandle() }
    var path by remember(r.optString("id")) { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val actions = rememberActions {}
    val prefix = Api.url(Api.rootPath(r.optString("id"), ""))
    Column(Modifier.fillMaxSize().testTag("c3_browser_${r.optString("id")}")) {
        RootBar(r, path, handle, close, Api.url(Api.rootPath(r.optString("id"), path)))
        err?.let { ErrorNote(it) }
        WebFrame(prefix, handle, Modifier.weight(1f), onLocation = { u -> path = java.net.URLDecoder.decode(u.removePrefix(prefix).substringBefore('?'), "UTF-8") }, onError = { err = it }, onLink = { openLink(ctx, it, actions) })
    }
}

/**
 * File Explorer, rendered by the webserver's own framework (#1710): the root opens on its own origin, where the shared fleet-webserver
 * serves its full UI (folder tree, Markdown / JSON / YAML viewer, Eruda). Folder tree is the compact native view of the same root
 * (folders lazily expanded through /__app__/api/roots/ls, the selected file rendered by the same server beside it); it is the view
 * of a root that has no origin of its own and a switch away for the rest (rule 16: nothing of it is dropped).
 */
@Composable
private fun ExplorerRoot(r: JSONObject, close: () -> Unit) {
    val id = r.optString("id")
    val frameUrl = r.optString("frame_url").takeIf { it.isNotEmpty() && !r.isNull("frame_url") }
    var framework by rememberSaveable(id) { mutableStateOf(frameUrl != null) }
    val tree = remember(id) { mutableStateMapOf<String, JSONObject>() }
    var sel by remember(id) { mutableStateOf("") }
    val handle = remember(id, framework) { FrameHandle() }
    val ctx = LocalContext.current
    val actions = rememberActions {}
    var err by remember { mutableStateOf<String?>(null) }
    suspend fun ls(path: String) {
        tree[path] = when (val x = Api.get(Api.ROOTS_LS + "?root=" + Api.enc(id) + "&path=" + Api.enc(path))) {
            is Api.Reply.Ok -> (x.value as? JSONObject) ?: JSONObject().put("status", "error").put("reason", "not an object")
            is Api.Reply.Err -> JSONObject().put("status", "error").put("reason", x.reason).put("entries", JSONArray())
        }
    }
    var want by remember(id) { mutableStateOf<String?>("") }
    LaunchedEffect(want) { want?.let { if (!tree.containsKey(it)) ls(it) } }
    val ext = if (framework && frameUrl != null) frameUrl else Api.url(Api.rootPath(id, sel))
    Column(Modifier.fillMaxSize().testTag("c3_explorer_$id")) {
        RootBar(r, if (framework) "" else sel, handle, close, ext, view = {
            if (frameUrl != null) Chip(if (framework) "Tree view" else "Framework view", "c3_root_view", primary = true) { framework = !framework }
        })
        err?.let { ErrorNote(it) }
        if (framework && frameUrl != null) {
            WebFrame(frameUrl, handle, Modifier.weight(1f).testTag("c3_xframework"), onError = { err = it }, onLink = { openLink(ctx, it, actions) })
        } else Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(150.dp).fillMaxHeight().background(Tone.Card).verticalScroll(rememberScrollState()).testTag("c3_xtree")) {
                TreeLevel(tree, "", 0, sel, onDir = { p -> if (tree.containsKey(p)) { tree.keys.filter { it == p || it.startsWith("$p/") }.forEach { tree.remove(it) } } else { want = p } }, onFile = { sel = it })
            }
            Box(Modifier.weight(1f).fillMaxHeight().testTag("c3_xprev")) {
                if (sel.isEmpty()) Note("Select a file on the left: it renders here (Markdown and HTML rendered, other files as the server serves them).")
                else WebFrame(Api.url(Api.rootPath(id, sel)), handle, onLink = { openLink(ctx, it, actions) })
            }
        }
    }
}

@Composable
private fun TreeLevel(tree: Map<String, JSONObject>, path: String, depth: Int, sel: String, onDir: (String) -> Unit, onFile: (String) -> Unit) {
    val node = tree[path] ?: return
    val pad = Modifier.padding(start = (6 + depth * 12).dp, top = 4.dp, bottom = 4.dp, end = 4.dp)
    val status = node.optString("status", "ready")
    if (status != "ready") { Text(node.optString("reason").ifEmpty { status }, style = MaterialTheme.typography.labelSmall, color = Tone.Warn, modifier = pad); return }
    val entries = node.optJSONArray("entries").objs()
    if (entries.isEmpty()) { Text("empty folder", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = pad); return }
    entries.forEach { e ->
        val name = e.optString("name")
        val p = if (path.isEmpty()) name else "$path/$name"
        if (e.optBoolean("dir")) {
            val open = tree.containsKey(p)
            Text((if (open) "▾ " else "▸ ") + "$name/", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { onDir(p) }.then(pad).testTag("c3_tree_dir"))
            if (open) TreeLevel(tree, p, depth + 1, sel, onDir, onFile)
        } else {
            val on = p == sel
            Text("· $name", style = MaterialTheme.typography.labelMedium, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().let { if (on) it.background(Tone.Line) else it }.clickable { onFile(p) }.then(pad).testTag("c3_tree_file"))
        }
    }
}

/** renderPages: the ONE served list (/__app__/api/served) with each page's status, metadata, source, links and actions, the add form and the undeclared checker. */
@Composable
fun ServedPages(tick: Int, bump: () -> Unit, open: (String, String?) -> Unit = { _, _ -> }) {
    val served = rememberObj(Api.SERVED, tick)
    val actions = rememberActions(bump)
    val ctx = LocalContext.current
    Loaded(served, "served pages") { pg ->
        val rows = pg.objs("rows")
        Card("Served pages · ${rows.size}", "c3_served") {
            rows.forEachIndexed { i, r ->
                if (i > 0) Hairline()
                val on = r.optBoolean("serving")
                val links = r.objs("links")
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp).testTag("c3_served_${r.optString("id")}"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(on); Spacer(Modifier.width(7.dp))
                        Text(r.str("name"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Badge(":" + r.str("port")); Badge(r.str("role")); Badge(if (on) "serving" else if (r.optBoolean("paused")) "stopped" else "not serving", if (on) Tone.Up else Tone.Down)
                    }
                    val facts = listOf("serving " + if (on) "on" else "off") + (if (r.has("app")) listOf("app " + r.str("app")) else emptyList()) +
                        listOf("auth " + r.str("auth"), r.str("exposure"), r.str("host"))
                    Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(r.str("source"), style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (links.isEmpty()) Text("links: none while stopped", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChipRow(inset = false) {
                        r.optJSONObject("toggle")?.let { t ->
                            Chip(t.optString("label", "Run"), "c3_served_toggle", primary = !t.optBoolean("danger", false)) { if (t.optBoolean("disabled", false)) toast(ctx, t.optString("why", "not available")) else actions.run(t) }
                        }
                        r.optJSONObject("start_app")?.let { a -> Chip("▶ Start app", "c3_served_start_app") { actions.run(a) } }
                        links.forEach { l ->
                            Chip(l.str("label") + " ↗", "c3_served_link") { openLink(ctx, l.optString("href"), actions) }
                            Chip("⧉", "c3_served_link_embedded") { Links.embedded = Links.embedUrl(Api.absolute(l.optString("href"))) }
                        }
                        r.optString("source_open").takeIf { it.isNotEmpty() }?.let { u -> Chip("source ↗", "c3_served_source") { openLink(ctx, u, actions) } }
                        Chip("⚙ config", "c3_served_config") {
                            if (r.optString("config") == "form") toast(ctx, "the add form is below the list") else open(Decl.SECTION_CONFIGS, Decl.PAGE_SERVER)
                        }
                        r.optJSONObject("remove")?.let { x -> Chip(x.optString("label", "Remove"), "c3_served_remove") { if (x.optBoolean("disabled", false)) toast(ctx, x.optString("why", "not available")) else actions.run(x) } }
                    }
                }
            }
            val dns = pg.optJSONObject("dns")
            Note("Names resolve through this app's private DNS (udp ${dns.str("host")}:${dns.str("port")}), only inside our apps. Termux and Nix pages are served by each terminal's own my-webserver: if the app is not open its page cannot answer, so Start app wakes it.")
        }
        pg.optJSONObject("form")?.let { FormCard(it, actions) }
        pg.optJSONObject("undeclared")?.let { u ->
            val items = u.objs("items")
            Card("⚠ Undeclared served pages · ${items.size}", "c3_undeclared", trailing = { Chip("Rescan", "c3_rescan") { actions.post(Api.SERVED, JSONObject().put("op", "rescan")) { "Rescanned" } } }) {
                if (items.isEmpty()) Note("none: every port found is in the declared list above (${u.str("declared")} declared ports).")
                items.forEach { x ->
                    Kv(":" + x.str("port"), "${x.str("addr")} — ${x.str("label")}", up = true)
                    ChipRow {
                        Chip("Declare", "c3_undeclared_declare", primary = true) { actions.post(Api.SERVED, JSONObject().put("op", "declare").put("port", x.opt("port"))) }
                        Chip("Stop", "c3_undeclared_stop") {
                            if (!x.optBoolean("ours")) toast(ctx, "held by another app: Android does not let this app stop it")
                            else actions.post(Api.SERVED, JSONObject().put("op", "stop_undeclared").put("port", x.opt("port")))
                        }
                    }
                }
                Note("Checked by ${u.str("method")}. ${u.str("scope")} Tap Declare to adopt a port into the list, Stop to end a listener this app holds.")
            }
        }
    }
}

/** renderForm: a server-described form ({title, post, fields[{id,label,kind,options}], submit{label, body}}). */
@Composable
fun FormCard(f: JSONObject, actions: Actions) {
    val values = remember(f.toString()) { mutableStateMapOf<String, String>() }
    Card(f.str("title"), "c3_form") {
        f.objs("fields").forEach { x ->
            val id = x.optString("id")
            val options = x.optJSONArray("options").objs()
            if (x.optString("kind") == "select" && options.isNotEmpty()) {
                if (values[id] == null) values[id] = options.first().optString("value")
                Kv(x.str("label"), "")
                ChipRow { options.forEach { o -> Chip((if (values[id] == o.optString("value")) "● " else "○ ") + o.str("label"), "c3_form_opt_${o.optString("value")}") { values[id] = o.optString("value") } } }
            } else {
                OutlinedTextField(values[id].orEmpty(), { values[id] = it }, label = { Text(x.str("label")) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).testTag("c3_form_$id"))
            }
        }
        ChipRow {
            Chip(f.optJSONObject("submit").str("label", "Add"), "c3_form_submit", primary = true) {
                val body = JSONObject((f.optJSONObject("submit")?.optJSONObject("body") ?: JSONObject()).toString())
                values.forEach { (k, v) -> body.put(k, v) }
                actions.post(f.optString("post"), body) { "Added" }
            }
        }
    }
}

/** The Git Sites rows (build.json::subpages tab pages, id git), kept under All (#1615 / #1677). */
@Composable
private fun GitSites(tick: Int, bump: () -> Unit) {
    val subs = rememberArr(Api.SUBPAGES, tick)
    val git = (subs as? Load.Ok)?.value.objs().firstOrNull { it.optString("tab") == "pages" && it.optString("id") == "git" }
    if (git != null) SubPage(git.optString("route"), tick, bump)
}

/** renderSubPage: {title, note, groups:[{title, rows:[{head, badges, lines, links, actions}]}], form?}. */
@Composable
fun SubPage(route: String, tick: Int, bump: () -> Unit) {
    val pg = rememberObj(route, tick)
    val actions = rememberActions(bump)
    val ctx = LocalContext.current
    Loaded(pg, route) { p ->
        p.optString("note").takeIf { it.isNotEmpty() }?.let { Note(it) }
        p.objs("groups").forEach { g ->
            Card(g.str("title"), "c3_sub_group") {
                val rows = g.objs("rows")
                if (rows.isEmpty()) Note("none")
                rows.forEachIndexed { i, r ->
                    if (i > 0) Hairline()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.str("head") + " ", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            r.optJSONArray("badges").strs().forEach { Badge(it) }
                        }
                        r.optJSONArray("lines").strs().filter { it.isNotBlank() && it != "null" }.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        val links = r.objs("links"); val acts = r.objs("actions")
                        if (links.isNotEmpty() || acts.isNotEmpty()) ChipRow(inset = false) {
                            links.forEach { l -> Chip(l.str("label") + " ↗", "c3_sub_link") { openLink(ctx, l.optString("href"), actions) } }
                            acts.forEach { a -> Chip(a.optString("label", "Run"), "c3_sub_act") { if (a.optBoolean("disabled", false)) toast(ctx, a.optString("why", "not available")) else actions.run(a) } }
                        }
                    }
                }
            }
        }
        p.optJSONObject("form")?.let { FormCard(it, actions) }
    }
}
