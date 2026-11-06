@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.diegonmarcos.superapp.configs

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.diegonmarcos.superapp.shellbroker.ShellBroker
import com.diegonmarcos.superapp.shellclient.ShellResult
import com.diegonmarcos.superapp.shellclient.ShellStatus
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreQuick
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** #1550 One terminal tab. */
internal data class TermTab(val id: Int, val title: String, val entries: List<TerminalModel.Entry> = emptyList(), val history: TerminalModel.History = TerminalModel.History())

/** #1550 Everything the terminal draws; [AdbTerminalContent] is stateless over it (screenshots and behaviour tests pass a fixture). */
internal data class TermUi(
    val status: ShellStatus? = null,
    val device: String = android.os.Build.MODEL ?: "device",
    val uid: String? = null,
    val tabs: List<TermTab> = listOf(TermTab(1, "shell 1")),
    val active: Int = 1,
    val input: String = "",
    val search: String = "",
    val wrap: Boolean = true,
    val palette: Boolean = true,
    val favourites: Set<String> = emptySet(),
    val pending: Pair<String, TerminalModel.ConfirmRule>? = null,
) {
    val tab: TermTab get() = tabs.firstOrNull { it.id == active } ?: tabs.first()
    val running: Boolean get() = tab.entries.any { it.state == TerminalModel.State.RUNNING }
}

/** #1550 The terminal's verbs; the stateful wrapper wires them, a test passes no-ops. */
internal class TermActions(
    val input: (String) -> Unit = {}, val run: (String) -> Unit = {}, val stop: () -> Unit = {}, val historyUp: () -> Unit = {},
    val historyDown: () -> Unit = {}, val search: (String) -> Unit = {}, val toggleWrap: () -> Unit = {}, val clear: () -> Unit = {},
    val export: () -> Unit = {}, val togglePalette: () -> Unit = {}, val favourite: (String) -> Unit = {}, val newTab: () -> Unit = {},
    val selectTab: (Int) -> Unit = {}, val closeTab: (Int) -> Unit = {}, val confirm: () -> Unit = {}, val cancelConfirm: () -> Unit = {},
    val reconnect: () -> Unit = {},
)

private fun hms(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

/** The terminal, stateless. [decl] = the declared palette. */
@Composable
internal fun AdbTerminalContent(ui: TermUi, decl: TerminalModel.Decl, a: TermActions, modifier: Modifier = Modifier, outputHeight: androidx.compose.ui.unit.Dp = KitStoreRow.step(520)) {
    val p = LocalKitPalette.current
    // Dark theme = light ink on the page; the ANSI table and the terminal well follow it (dark first, light correct).
    val dark = p.textPrimary.luminance() > 0.5f
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = KitStoreRow.meta, color = p.textPrimary)
    val term = if (dark) Color.Black.copy(alpha = 0.45f) else p.textPrimary.copy(alpha = 0.04f)
    Column(modifier.fillMaxWidth().testTag("term:root")) {
        // ── session header: one dot = the true adb transport; device, privilege, transport, reconnect ──
        val link = ui.status?.adb
        val ink = when (link?.verdict) { true -> p.ok; false -> p.bad; null -> p.textSecondary }
        Row(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight).testTag("term:header"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitStoreRow.gap)) {
            Box(Modifier.size(KitStoreRow.step(10)).background(ink, CircleShape)
                .semantics { contentDescription = (link?.label ?: "ADB: checking") })
            Column(Modifier.weight(1f)) {
                Text((link?.label ?: "ADB: checking") + "  ·  " + ui.device, fontSize = KitStoreRow.body, fontWeight = FontWeight.Bold, color = p.textPrimary,
                    modifier = Modifier.testTag("term:link"))
                KitStoreCaption(TerminalModel.privilege(ui.uid, ui.status?.channel ?: "none") + "  ·  transport " +
                    (if (link?.up == true) "adb" + (ui.status?.adbDetail?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "") else ui.status?.channel ?: "none") +
                    (ui.status?.checkedAt?.takeIf { it > 0 }?.let { "  ·  checked " + hms(it) } ?: ""),
                    Modifier.testTag("term:privilege"), color = p.textSecondary)
            }
            KitStoreQuick("Reconnect", KitStoreRow.BLUE_FILL, Modifier.testTag("term:reconnect"), a.reconnect)
        }
        // ── tabs ──
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
            for (t in ui.tabs) {
                val on = t.id == ui.active
                Row(Modifier.background(if (on) p.surfaceSelected else Color.Transparent).border(KitStoreRow.step(1), p.hairline)
                    .clickable(role = Role.Tab) { a.selectTab(t.id) }.padding(horizontal = KitStoreRow.gap, vertical = KitStoreRow.tight).testTag("term:tab:${t.id}"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(t.title + (if (t.entries.isNotEmpty()) "  ${t.entries.size}" else ""), fontSize = KitStoreRow.meta, fontFamily = FontFamily.Monospace,
                        color = if (on) p.textPrimary else p.textSecondary)
                    if (ui.tabs.size > 1) Text("  ✕", fontSize = KitStoreRow.meta, color = p.textSecondary,
                        modifier = Modifier.clickable { a.closeTab(t.id) }.semantics { contentDescription = "Close ${t.title}" })
                }
            }
            Text("＋ tab", fontSize = KitStoreRow.meta, color = p.accent, modifier = Modifier.clickable(role = Role.Button) { a.newTab() }
                .padding(horizontal = KitStoreRow.gap, vertical = KitStoreRow.tight).testTag("term:newtab"))
        }
        // ── output tools: search, wrap, clear, export, palette ──
        Row(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
            BasicTextField(ui.search, a.search, singleLine = true, textStyle = mono, cursorBrush = SolidColor(p.accent),
                modifier = Modifier.weight(1f).background(p.textPrimary.copy(alpha = 0.07f)).padding(KitStoreRow.tight).testTag("term:search"),
                decorationBox = { inner -> Box { if (ui.search.isEmpty()) Text("Filter output…", style = mono.copy(color = p.textSecondary)); inner() } })
            if (ui.search.isNotBlank()) KitStoreCaption("${TerminalModel.hits(ui.tab.entries, ui.search)} hits", Modifier.testTag("term:hits"), color = p.textSecondary)
            Tool(if (ui.wrap) "Wrap ✓" else "Wrap", "term:wrap", a.toggleWrap)
            Tool("Clear", "term:clear", a.clear)
            Tool("Export", "term:export", a.export)
            Tool(if (ui.palette) "Snippets ▾" else "Snippets ▸", "term:palette-toggle", a.togglePalette)
        }
        // ── output: scrollback, selectable, ANSI colour, exit + duration + timestamp per command ──
        val shown = TerminalModel.search(ui.tab.entries, ui.search)
        val scroll = rememberScrollState()
        LaunchedEffect(ui.tab.entries.size, ui.tab.entries.lastOrNull()?.state) { scroll.animateScrollTo(scroll.maxValue) }
        Box(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH).height(outputHeight).background(term).border(KitStoreRow.step(1), p.hairline)
            .testTag("term:output")) {
            val h = if (ui.wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())
            SelectionContainer {
                Column(Modifier.verticalScroll(scroll).then(h).padding(KitStoreRow.gap)) {
                    if (ui.tab.entries.isEmpty()) Text("No commands yet. Type one below or pick a snippet; it runs as the SuperApp through the shell broker, under its allowlist.",
                        style = mono.copy(color = p.textSecondary), modifier = Modifier.testTag("term:empty"))
                    else if (shown.isEmpty()) Text("No output matches \"${ui.search}\".", style = mono.copy(color = p.textSecondary))
                    for (e in shown) EntryBlock(e, mono, dark, ui.wrap, ui.search)
                }
            }
        }
        // ── confirm a destructive command (declared rules) ──
        ui.pending?.let { (cmd, rule) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight).background(p.warn.copy(alpha = 0.18f))
                .padding(KitStoreRow.gap).testTag("term:confirm"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitStoreRow.gap)) {
                Column(Modifier.weight(1f)) {
                    Text("Destructive: $cmd", style = mono.copy(fontWeight = FontWeight.Bold))
                    KitStoreCaption("This ${rule.why} (rule ${rule.id}). Run it?", color = p.textPrimary)
                }
                KitStoreQuick("Run anyway", KitStoreRow.AMBER_FILL, Modifier.testTag("term:confirm-run"), a.confirm)
                KitStoreQuick("Cancel", KitStoreRow.BLUE_FILL, Modifier.testTag("term:confirm-cancel"), a.cancelConfirm)
            }
        }
        // ── sticky input bar: prompt, history up/down, run/stop, autocomplete ──
        Row(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight).background(term).border(KitStoreRow.step(1), p.accent.copy(alpha = 0.6f))
            .padding(KitStoreRow.tight).testTag("term:inputbar"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
            Text(if ((ui.uid ?: "") == "0") "#" else "$", style = mono.copy(color = p.accent, fontWeight = FontWeight.Bold))
            BasicTextField(ui.input, a.input, singleLine = true, textStyle = mono, cursorBrush = SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false), keyboardActions = KeyboardActions(onGo = { a.run(ui.input) }),
                modifier = Modifier.weight(1f).testTag("term:input").semantics { contentDescription = "Command" }.onPreviewKeyEvent { k ->
                    if (k.type != KeyEventType.KeyDown) false else when (k.key) {
                        Key.DirectionUp -> { a.historyUp(); true }
                        Key.DirectionDown -> { a.historyDown(); true }
                        Key.Enter, Key.NumPadEnter -> { a.run(ui.input); true }
                        else -> false
                    }
                },
                decorationBox = { inner -> Box { if (ui.input.isEmpty()) Text("adb shell command…  (↑ ↓ history)", style = mono.copy(color = p.textSecondary)); inner() } })
            Tool("↑", "term:hist-up", a.historyUp, "Previous command")
            Tool("↓", "term:hist-down", a.historyDown, "Next command")
            if (ui.running) KitStoreQuick("■ Stop", KitStoreRow.AMBER_FILL, Modifier.testTag("term:stop"), a.stop)
            else KitStoreQuick("▶ Run", KitStoreRow.BLUE_FILL, Modifier.testTag("term:run")) { a.run(ui.input) }
        }
        val completions = TerminalModel.complete(ui.input, decl, ui.tab.history.items)
        if (completions.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH).testTag("term:complete"),
            horizontalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
            for (c in completions) Text(c, style = mono.copy(color = p.textPrimary), modifier = Modifier.background(p.textPrimary.copy(alpha = 0.08f))
                .clickable { a.input(c) }.padding(horizontal = KitStoreRow.gap, vertical = KitStoreRow.tight))
        }
        // ── command palette: declared snippets by group, favourites first ──
        if (ui.palette) Column(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight).testTag("term:palette")) {
            for ((g, list) in TerminalModel.byGroup(decl, ui.favourites)) {
                KitStoreCaption((if (g == TerminalModel.FAVOURITES) "★ FAVOURITES" else g.uppercase()) + "  ${list.size}", Modifier.padding(top = KitStoreRow.tight).testTag("term:group:$g"), color = p.accent)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(KitStoreRow.tight), verticalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
                    for (s in list) Row(Modifier.border(KitStoreRow.step(1), p.hairline).testTag("term:snippet:${s.id}"), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (s.id in ui.favourites) "★" else "☆", fontSize = KitStoreRow.meta, color = if (s.id in ui.favourites) p.warn else p.textSecondary,
                            modifier = Modifier.clickable { a.favourite(s.id) }.padding(horizontal = KitStoreRow.tight, vertical = KitStoreRow.tight)
                                .semantics { contentDescription = (if (s.id in ui.favourites) "Unfavourite " else "Favourite ") + s.label })
                        Column(Modifier.clickable { a.input(s.cmd) }.padding(end = KitStoreRow.gap, top = KitStoreRow.tight, bottom = KitStoreRow.tight)) {
                            Text(s.label, fontSize = KitStoreRow.meta, color = p.textPrimary)
                            Text(s.cmd, style = mono.copy(fontSize = KitStoreRow.caption, color = p.textSecondary))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tool(label: String, tag: String, onClick: () -> Unit, description: String? = null) {
    val p = LocalKitPalette.current
    Text(label, fontSize = KitStoreRow.meta, color = p.textPrimary, modifier = Modifier.background(p.textPrimary.copy(alpha = 0.08f))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = KitStoreRow.gap, vertical = KitStoreRow.tight).testTag(tag)
        .let { m -> if (description != null) m.semantics { contentDescription = description } else m })
}

@Composable
private fun EntryBlock(e: TerminalModel.Entry, mono: TextStyle, dark: Boolean, wrap: Boolean, q: String) {
    val p = LocalKitPalette.current
    val ok = TerminalModel.ok(e)
    val ink = when (ok) { true -> p.ok; false -> p.bad; null -> p.warn }
    Column(Modifier.fillMaxWidth().padding(bottom = KitStoreRow.gap).testTag("term:entry:${e.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = p.textSecondary)) { append("[" + hms(e.startedAt) + "] ") }
                withStyle(SpanStyle(color = p.accent, fontWeight = FontWeight.Bold)) { append("$ ") }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(e.cmd) }
            }, style = mono, modifier = Modifier.weight(1f, fill = wrap), softWrap = wrap)
            Text("  " + TerminalModel.status(e) + " · " + TerminalModel.duration(e), style = mono.copy(color = ink, fontSize = KitStoreRow.caption),
                modifier = Modifier.testTag("term:status:${e.id}"))
        }
        if (e.output.isNotEmpty()) Text(ansi(e.output, dark, p.textPrimary, q, p.warn), style = mono, softWrap = wrap)
    }
}

/** ANSI SGR spans to an AnnotatedString; [q] matches are highlighted. */
internal fun ansi(s: String, dark: Boolean, base: Color, q: String = "", hl: Color = Color.Yellow): AnnotatedString = buildAnnotatedString {
    for (sp in TerminalModel.spans(s)) withStyle(SpanStyle(
        color = sp.fg?.let { Color(TerminalModel.ansiArgb(it, dark)) } ?: if (sp.dim) base.copy(alpha = 0.6f) else base,
        background = sp.bg?.let { Color(TerminalModel.ansiArgb(it, dark)).copy(alpha = 0.35f) } ?: Color.Unspecified,
        fontWeight = if (sp.bold) FontWeight.Bold else null,
        textDecoration = if (sp.underline) TextDecoration.Underline else null,
    )) { append(sp.text) }
    if (q.isNotBlank()) {
        val t = toString(); var i = t.indexOf(q, ignoreCase = true)
        while (i >= 0) { addStyle(SpanStyle(background = hl.copy(alpha = 0.45f)), i, i + q.length); i = t.indexOf(q, i + q.length, ignoreCase = true) }
    }
}

/** #1550 The stateful terminal: runs each command through the shell broker as the SuperApp (allowlisted, audited, timed out). */
@Composable
internal fun AdbTerminal(status: ShellStatus?, onReconnect: () -> Unit, modifier: Modifier = Modifier, outputHeight: androidx.compose.ui.unit.Dp = KitStoreRow.step(520)) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val decl = remember { runCatching { TerminalModel.parse(ctx.assets.open(ALLOWLIST).bufferedReader().use { it.readText() }) }.getOrDefault(TerminalModel.Decl(emptyList(), emptyList(), emptyList(), emptyList())) }
    val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var ui by remember { mutableStateOf(TermUi(favourites = prefs.getStringSet(KEY_FAV, emptySet()).orEmpty().toSet())) }
    val jobs = remember { HashMap<Long, Job>() }
    var seq by remember { mutableStateOf(0L) }
    LaunchedEffect(status) { ui = ui.copy(status = status) }
    LaunchedEffect(status?.armed) {
        if (status?.armed == true) {
            val r = withContext(Dispatchers.IO) { runCatching { ShellBroker.exec(ctx, ShellBroker.SELF, "id -u", 5_000) }.getOrNull() }
            (r as? ShellResult.Ok)?.let { ui = ui.copy(uid = it.stdout.trim().lineSequence().firstOrNull()) }
        }
    }
    fun updateTab(f: (TermTab) -> TermTab) { ui = ui.copy(tabs = ui.tabs.map { if (it.id == ui.active) f(it) else it }) }
    fun finish(tabId: Int, id: Long, f: (TerminalModel.Entry) -> TerminalModel.Entry) {
        ui = ui.copy(tabs = ui.tabs.map { t -> if (t.id != tabId) t else t.copy(entries = t.entries.map { if (it.id == id && it.state == TerminalModel.State.RUNNING) f(it) else it }) })
    }
    fun launch(cmd: String) {
        val tabId = ui.active; val id = ++seq; val t0 = System.currentTimeMillis()
        updateTab { it.copy(entries = (it.entries + TerminalModel.Entry(id, cmd, t0)).takeLast(MAX_ENTRIES), history = it.history.push(cmd)) }
        ui = ui.copy(input = "", pending = null)
        jobs[id] = scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ShellBroker.exec(ctx, ShellBroker.SELF, cmd, TIMEOUT_MS) }.getOrElse { ShellResult.Failed(it.message ?: "failed") } }
            val now = System.currentTimeMillis()
            finish(tabId, id) { e -> when (r) {
                is ShellResult.Ok -> e.copy(endedAt = now, state = if (r.exitCode == 0) TerminalModel.State.OK else TerminalModel.State.EXIT, exit = r.exitCode,
                    stdout = r.stdout.take(MAX_OUTPUT), stderr = r.stderr.take(MAX_OUTPUT))
                is ShellResult.Denied -> e.copy(endedAt = now, state = TerminalModel.State.DENIED, note = "refused by the shell broker: ${r.reason}")
                is ShellResult.Failed -> e.copy(endedAt = now, state = TerminalModel.State.FAILED, note = (if (r.timedOut) "timed out: " else "failed: ") + r.reason)
                else -> e.copy(endedAt = now, state = TerminalModel.State.FAILED, note = "no privileged channel is armed: Reconnect, or Re-arm below")
            } }
            jobs.remove(id)
        }
    }
    val a = TermActions(
        input = { ui = ui.copy(input = it) },
        run = { raw -> val c = raw.trim(); if (c.isNotEmpty() && !ui.running) TerminalModel.confirmFor(decl, c)?.let { ui = ui.copy(pending = c to it) } ?: launch(c) },
        stop = {
            val now = System.currentTimeMillis()
            ui.tab.entries.filter { it.state == TerminalModel.State.RUNNING }.forEach { e -> jobs.remove(e.id)?.cancel()
                finish(ui.active, e.id) { it.copy(endedAt = now, state = TerminalModel.State.STOPPED, note = "stopped here; the broker's own timeout ends the command") } }
        },
        historyUp = { val (h, t) = ui.tab.history.up(ui.input); updateTab { it.copy(history = h) }; ui = ui.copy(input = t) },
        historyDown = { val (h, t) = ui.tab.history.down(ui.input); updateTab { it.copy(history = h) }; ui = ui.copy(input = t) },
        search = { ui = ui.copy(search = it) },
        toggleWrap = { ui = ui.copy(wrap = !ui.wrap) },
        clear = { updateTab { it.copy(entries = it.entries.filter { e -> e.state == TerminalModel.State.RUNNING }) } },
        export = {
            val text = TerminalModel.export("ADB Shell · ${ui.device} · ${ui.tab.title}", ui.tab.entries) { hms(it) }
            runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                .putExtra(Intent.EXTRA_SUBJECT, "ADB Shell session"), "Export session").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        },
        togglePalette = { ui = ui.copy(palette = !ui.palette) },
        favourite = { id -> val f = if (id in ui.favourites) ui.favourites - id else ui.favourites + id; prefs.edit().putStringSet(KEY_FAV, f).apply(); ui = ui.copy(favourites = f) },
        newTab = { val n = (ui.tabs.maxOf { it.id }) + 1; ui = ui.copy(tabs = ui.tabs + TermTab(n, "shell $n"), active = n) },
        selectTab = { ui = ui.copy(active = it) },
        closeTab = { id -> if (ui.tabs.size > 1) { val rest = ui.tabs.filter { it.id != id }; ui = ui.copy(tabs = rest, active = if (ui.active == id) rest.last().id else ui.active) } },
        confirm = { ui.pending?.let { launch(it.first) } },
        cancelConfirm = { ui = ui.copy(pending = null) },
        reconnect = onReconnect,
    )
    AdbTerminalContent(ui, decl, a, modifier, outputHeight)
}

private const val ALLOWLIST = "shell-allowlist.json"
private const val PREFS = "adb_terminal"
private const val KEY_FAV = "favourites"
private const val TIMEOUT_MS = 60_000L
private const val MAX_OUTPUT = 256 * 1024
private const val MAX_ENTRIES = 300
