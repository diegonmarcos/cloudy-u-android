package com.diegonmarcos.superapp.ai

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The glyph a control is drawn with (Cloud Code draws its icon font's; the Compose page keeps to plain text, so no icon set is needed). */
internal fun controlGlyph(id: String): String = when (id) {
    "plus" -> "+"
    "task" -> "✔"
    "agent" -> "🤖"
    "model" -> "✨"
    "effort" -> "🎚"
    "mode" -> "⚡"
    "mcp" -> "🔌"
    "more" -> "⋮"
    "specialist" -> "🎓"
    "repos" -> "📂"
    "skills" -> "🧩"
    "mcp_kg" -> "🕸"
    "mcp_infra" -> "🛠"
    "mcp_user" -> "👤"
    "mcp_ext" -> "🌐"
    "perf" -> "🚀"
    else -> "•"
}

internal const val BAR_PREFS = "ai_composer"

/** The bar's collapsed state of one app, from its own SharedPreferences. */
internal fun loadCollapsed(ctx: Context, design: AiDesign, app: String): Boolean =
    ctx.getSharedPreferences(BAR_PREFS, Context.MODE_PRIVATE).getBoolean(design.collapseKey(app), design.collapsedByDefault)

/**
 * The composer's config bar (Cloud Code's, shared): the controls [decl] enables, always in the design's order
 * (+) · Task · Agent · Model · Effort · Perms · MCP · (More). A selected task greys Agent and Model: they show the task's
 * mapped agent and model and open nothing until the task is cleared. The chevron at the end collapses the whole bar to one compact
 * chip (the active task, else the model); the state is kept per app. [onCatalogue] replaces the Model button's page with the host's own (default: the shared Model Catalogue); [onAttach] answers the (+) menu's Attach items (files, images, notes, Drive: the app's pickers). The controls scroll sideways
 * only when they do not fit; the bar sits in a column, never inside another scroller.
 */
@Composable
fun AiComposerBar(
    chat: AiChatState,
    theme: AiTheme,
    design: AiDesign,
    decl: AiComposerDecl,
    modifier: Modifier = Modifier,
    onCatalogue: (() -> Unit)? = null,
    onAttach: ((String) -> Unit)? = null,
    /** The built-in Attach items (Files, Image, Camera) with the app's permission hooks; takes precedence over [onAttach]. */
    attach: AiAttachHost? = null,
    /** Two toolbar rows (row 1: Task, Specialist | Agent, Model, Effort, Mode; row 2: Repos, Plugins, MCP buckets, More); (+) belongs to the host's input row. False = the one-row bar. */
    twoRows: Boolean = false,
    /** The Specialist picker's entries (id to label), the selected id and the pick callback. */
    specialists: List<Pair<String, String>> = emptyList(),
    specialist: String = "",
    onSpecialist: (String) -> Unit = {},
    /** Usage & Cost lives in the host's top bar: the More sheet leaves it out. */
    usageInTopBar: Boolean = false,
) {
    val shown = if (twoRows) design.orderedLayout(decl.controls) else design.ordered(decl.controls)
    if (shown.isEmpty()) return
    val ctx = LocalContext.current
    LaunchedEffect(decl.app) { chat.collapsed = loadCollapsed(ctx, design, decl.app) }
    LaunchedEffect(Unit) {
        if (chat.models.isEmpty() && shown.any { it.id == "model" }) chat.models = withContext(Dispatchers.IO) { runCatching { chat.host().models() }.getOrDefault(emptyList()) }
    }
    fun toggle() {
        chat.collapsed = !chat.collapsed
        ctx.getSharedPreferences(BAR_PREFS, Context.MODE_PRIVATE).edit().putBoolean(design.collapseKey(decl.app), chat.collapsed).apply()
    }
    val gap = Arrangement.spacedBy(design.metric("ctl_gap").dp)
    @Composable
    fun Control(ctl: AiControl) {
        if (twoRows && ctl.dividerBefore) Box(Modifier.height(design.metric("ctl_height").dp).width(theme.metrics.hairline).background(theme.colors.tileBorder).testTag(AiTags.TOOLBAR_DIVIDER))
        when (ctl.id) {
            "plus" -> PlusControl(chat, theme, design, ctl, onAttach, attach)
            "task" -> TaskControl(chat, theme, design, ctl)
            "specialist" -> SpecialistControl(theme, design, ctl, specialists, specialist, onSpecialist)
            "agent" -> AgentControl(chat, theme, design, ctl)
            "model" -> ModelControl(chat, theme, design, ctl, onCatalogue)
            "effort" -> EffortControl(chat, theme, design, ctl)
            "mode" -> ModeControl(chat, theme, design, ctl)
            "repos" -> ReposControl(chat, theme, design, ctl, decl.repos)
            "skills", "perf" -> ItemMenu(chat, theme, design, ctl, pickerGroups(design, if (ctl.id == "skills") "skill" else "performance"))
            "mcp_kg", "mcp_infra", "mcp_user", "mcp_ext" -> McpBucketControl(chat, theme, design, ctl)
            "mcp" -> McpControl(chat, theme, design, ctl)
            "more" -> MoreControl(chat, theme, design, ctl, shown.map { it.id }.toSet(), usageInTopBar)
        }
    }
    if (twoRows) {
        // Two rows, each scrolling sideways on its own when it does not fit; the chevron collapses both to the one compact chip.
        val rows = listOf(1, 2).map { r -> shown.filter { it.row == r } }.filter { it.isNotEmpty() }
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (chat.collapsed) {
                CompactChip(chat, theme, design) { toggle() }
                Box(Modifier.weight(1f))
            } else Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(design.metric("ctl_gap").dp)) {
                rows.forEachIndexed { i, row ->
                    val n = i + 1
                    // Each row collapses on its own (its chevron) and remembers it across sessions, per app and per row.
                    val key = design.collapseKey(decl.app) + ".row$n"
                    var rowCollapsed by remember(key) { mutableStateOf(ctx.getSharedPreferences(BAR_PREFS, Context.MODE_PRIVATE).getBoolean(key, false)) }
                    fun flip() {
                        rowCollapsed = !rowCollapsed
                        ctx.getSharedPreferences(BAR_PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, rowCollapsed).apply()
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clickable { flip() }.padding(end = theme.metrics.small).testTag(AiTags.rowToggle(n)), contentAlignment = Alignment.Center) {
                            Text(if (rowCollapsed) "▸" else "▾", color = theme.colors.text2, fontSize = design.metric("ctl_icon").sp)
                        }
                        if (rowCollapsed) Text((if (n == 1) theme.strings.toolbarRow1 else theme.strings.toolbarRow2), color = theme.colors.text2, fontSize = design.metric("compact_font").sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).testTag(AiTags.toolbarRow(n)), horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) {
                            row.forEach { Control(it) }
                        }
                    }
                }
            }
            Box(
                Modifier.size(design.metric("bar_toggle_size").dp).clickable { toggle() }.testTag(AiTags.BAR_TOGGLE),
                contentAlignment = Alignment.Center,
            ) { Text(if (chat.collapsed) "▴" else "▾", color = theme.colors.text2, fontSize = design.metric("ctl_icon").sp) }
        }
        return
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (chat.collapsed) {
            CompactChip(chat, theme, design) { toggle() }
            Box(Modifier.weight(1f))
        } else {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = gap,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                shown.forEach { Control(it) }
            }
        }
        Box(
            Modifier.size(design.metric("bar_toggle_size").dp).clickable { toggle() }.testTag(AiTags.BAR_TOGGLE),
            contentAlignment = Alignment.Center,
        ) { Text(if (chat.collapsed) "▴" else "▾", color = theme.colors.text2, fontSize = design.metric("ctl_icon").sp) }
    }
}

/** The (+) attachments dropdown (Files, Image, Camera, New chat) for a host that draws it in its input row. */
@Composable
fun AiPlusMenu(chat: AiChatState, theme: AiTheme, design: AiDesign, decl: AiComposerDecl, onAttach: ((String) -> Unit)? = null, attach: AiAttachHost? = null) {
    val ctl = design.controls.firstOrNull { it.id == "plus" } ?: return
    if ("plus" in decl.controls) PlusControl(chat, theme, design, ctl, onAttach, attach)
}

/** The Camera button of an input row: takes a picture into a temp file and attaches it, through the same Perms-guarded action as the (+) menu. */
@Composable
fun rememberCameraAction(chat: AiChatState, theme: AiTheme, design: AiDesign, attach: AiAttachHost?): (() -> Unit)? {
    val actions = attach?.let { rememberAttachActions(chat, theme, it) } ?: return null
    val need = design.attachPerms["camera"].orEmpty()
    // Camera needs its Perms row: blocked (no action) while it is missing, exactly as in the (+) menu.
    if (need.isNotEmpty() && "camera" in design.attachRequires && attach!!.missing(need).isNotEmpty()) return null
    return { actions.run("camera") }
}

/** The collapsed bar: one chip with the active task, else the model. */
@Composable
private fun CompactChip(chat: AiChatState, theme: AiTheme, design: AiDesign, onClick: () -> Unit) {
    val shape = RoundedCornerShape(design.metric("ctl_radius").dp)
    val label = chat.selectedTask?.label ?: chat.models.firstOrNull { it.id == chat.model }?.name ?: chat.model
    Row(
        Modifier.height(design.metric("ctl_height").dp).clip(shape).border(theme.metrics.hairline, theme.colors.tileBorder, shape)
            .clickable(onClick = onClick).testTag(AiTags.BAR_COMPACT).padding(horizontal = design.metric("ctl_pad_h").dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(design.metric("ctl_gap").dp),
    ) {
        Text(controlGlyph(if (chat.selectedTask != null) "task" else "model"), fontSize = design.metric("ctl_icon").sp, color = theme.colors.text)
        Text(label.substringAfterLast('/'), color = theme.colors.text, fontSize = design.metric("compact_font").sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = theme.metrics.modelMaxWidth))
    }
}

/** One chip of the row with its menu. [menu] gets a `close`. A disabled chip is greyed and opens nothing. */
@Composable
private fun Ctl(
    ctl: AiControl, theme: AiTheme, design: AiDesign, label: String, enabled: Boolean = true,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(design.metric("ctl_radius").dp)
    val font = design.metric("ctl_font").sp
    Box {
        Row(
            Modifier.height(design.metric("ctl_height").dp)
                .alpha(if (enabled) 1f else design.metric("ctl_disabled_alpha"))
                .clip(shape).border(theme.metrics.hairline, theme.colors.tileBorder, shape)
                .clickable(enabled = enabled) { open = true }.testTag(AiTags.ctl(ctl.id))
                .padding(horizontal = design.metric("ctl_pad_h").dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(design.metric("ctl_gap").dp),
        ) {
            Text(controlGlyph(ctl.id), fontSize = design.metric("ctl_icon").sp, color = theme.colors.text)
            if (label.isNotEmpty()) Text(label, color = theme.colors.text, fontSize = font, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = theme.metrics.modelMaxWidth))
        }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) { menu { open = false } }
    }
}

/** One chip of the row that acts on a tap (no menu). A disabled chip is greyed and does nothing. */
@Composable
private fun Btn(ctl: AiControl, theme: AiTheme, design: AiDesign, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(design.metric("ctl_radius").dp)
    Row(
        Modifier.height(design.metric("ctl_height").dp)
            .alpha(if (enabled) 1f else design.metric("ctl_disabled_alpha"))
            .clip(shape).border(theme.metrics.hairline, theme.colors.tileBorder, shape)
            .clickable(enabled = enabled, onClick = onClick).testTag(AiTags.ctl(ctl.id))
            .padding(horizontal = design.metric("ctl_pad_h").dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(design.metric("ctl_gap").dp),
    ) {
        Text(controlGlyph(ctl.id), fontSize = design.metric("ctl_icon").sp, color = theme.colors.text)
        if (label.isNotEmpty()) Text(label, color = theme.colors.text, fontSize = design.metric("ctl_font").sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = theme.metrics.modelMaxWidth))
    }
}

@Composable
private fun PlusControl(chat: AiChatState, theme: AiTheme, design: AiDesign, ctl: AiControl, onAttach: ((String) -> Unit)?, attach: AiAttachHost?) {
    val t = theme.type
    val actions = attach?.let { rememberAttachActions(chat, theme, it) }
    Ctl(ctl, theme, design, "") { close ->
        if (attach != null && actions != null) design.attachKinds.forEach { (id, label) ->
            val need = design.attachPerms[id].orEmpty()
            val missing = if (need.isEmpty()) emptyList() else attach.missing(need)
            val blocked = missing.isNotEmpty() && id in design.attachRequires
            DropdownMenuItem(
                text = { Text(label, style = t.style(t.small)) }, enabled = !blocked,
                onClick = { close(); actions.run(id) }, modifier = Modifier.testTag(AiTags.attach(id)),
            )
            if (missing.isNotEmpty()) Box(Modifier.padding(horizontal = theme.metrics.gutter, vertical = theme.metrics.small)) { attach.fix(missing) }
        } else if (onAttach != null) design.attachKinds.forEach { (id, label) ->
            DropdownMenuItem(text = { Text(label, style = t.style(t.small)) }, onClick = { close(); onAttach(id) }, modifier = Modifier.testTag(AiTags.attach(id)))
        }
        DropdownMenuItem(text = { Text(design.newChatLabel, style = t.style(t.small, FontWeight.SemiBold)) }, onClick = { chat.fresh(); close() }, modifier = Modifier.testTag(AiTags.NEW_CHAT))
    }
}

/** The Task button: opens the full Task Catalogue (AiTaskCatalogue) in the chat's place. */
@Composable
private fun TaskControl(chat: AiChatState, theme: AiTheme, design: AiDesign, ctl: AiControl) {
    Btn(ctl, theme, design, chat.selectedTask?.label ?: ctl.label, enabled = chat.tasks.isNotEmpty()) { chat.page = AiChatState.PAGE_TASKS }
}

@Composable
private fun AgentControl(chat: AiChatState, theme: AiTheme, design: AiDesign, ctl: AiControl) {
    val agents = remember { chat.host().agents() }
    // A host with no agent gateway (Cloud Tools) still shows the greyed chip of the agent a task maps to.
    if (agents.isEmpty() && !chat.locked) return
    val c = theme.colors
    val t = theme.type
    val label = agents.firstOrNull { it.id == chat.agent }?.label ?: design.extraAgents.firstOrNull { it.id == chat.agent }?.label ?: chat.agent.ifEmpty { theme.strings.agentNone }
    Ctl(ctl, theme, design, label, enabled = !chat.locked) { close ->
        DropdownMenuItem(text = { Text(theme.strings.agentNone, style = t.style(t.small)) },
            onClick = { chat.chooseAgent(""); close() }, modifier = Modifier.testTag(AiTags.agent("none")))
        agents.forEach { a ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(a.label, style = t.style(t.small, FontWeight.SemiBold), color = if (a.id == chat.agent) c.accent else c.text)
                        Text(a.sub, style = t.style(t.small), color = c.text2)
                    }
                },
                onClick = { chat.chooseAgent(a.id); close() }, modifier = Modifier.testTag(AiTags.agent(a.id)),
            )
        }
    }
}

/** The Model button: opens the FULL Model Catalogue (AiModelCatalogue), or the host's own page when it passes [onCatalogue]. Greyed while a task is selected. */
@Composable
private fun ModelControl(chat: AiChatState, theme: AiTheme, design: AiDesign, ctl: AiControl, onCatalogue: (() -> Unit)?) {
    val current = chat.models.firstOrNull { it.id == chat.model }
    Btn(ctl, theme, design, (current?.name ?: chat.model).substringAfterLast('/'), enabled = !chat.locked) { if (onCatalogue != null) onCatalogue() else chat.page = AiChatState.PAGE_MODELS }
}

@Composable
private fun EffortControl(chat: AiChatState, theme: AiTheme, design: AiDesign, ctl: AiControl) {
    val t = theme.type
    val id = chat.effort.ifEmpty { design.defaultEffort }
    Ctl(ctl, theme, design, if (id == design.defaultEffort) ctl.label else design.effort(id)?.label ?: ctl.label) { close ->
        design.efforts.forEach { e ->
            DropdownMenuItem(
                text = { Text(e.label, style = t.style(t.small, FontWeight.SemiBold), color = if (e.id == id) theme.colors.accent else theme.colors.text) },
                onClick = { chat.chooseEffort(e); close() }, modifier = Modifier.testTag(AiTags.effort(e.id)),
            )
        }
    }
}

@Composable
private fun ModeControl(chat: AiChatState, theme: AiTheme, design: AiDesign, ctl: AiControl) {
    val t = theme.type
    val id = chat.mode.ifEmpty { design.defaultMode }
    LaunchedEffect(Unit) { if (chat.mode.isEmpty()) chat.chooseMode(design.defaultMode) }
    Ctl(ctl, theme, design, design.mode(id)?.short ?: ctl.label) { close ->
        design.modes.forEach { m ->
            DropdownMenuItem(
                text = { Text(m.label, style = t.style(t.small, FontWeight.SemiBold), color = if (m.id == id) theme.colors.accent else theme.colors.text) },
                onClick = { chat.chooseMode(m.id); close() }, modifier = Modifier.testTag(AiTags.mode(m.id)),
            )
        }
    }
}

@Composable
private fun McpControl(chat: AiChatState, theme: AiTheme, design: AiDesign, ctl: AiControl) {
    val c = theme.colors
    val t = theme.type
    LaunchedEffect(Unit) {
        if (chat.mcpServers.isEmpty()) chat.mcpServers = withContext(Dispatchers.IO) { runCatching { chat.host().mcpServers() }.getOrDefault(emptyList()) }
    }
    Ctl(ctl, theme, design, if (chat.mcpOn.isEmpty()) ctl.label else "${ctl.label} ${chat.mcpOn.size}") { _ ->
        if (chat.mcpServers.isEmpty()) DropdownMenuItem(text = { Text(theme.strings.noMcp, style = t.style(t.small), color = c.text2) }, onClick = {})
        chat.mcpServers.forEach { sv ->
            DropdownMenuItem(
                text = { Text(sv.name + (sv.tools?.let { " · $it" } ?: ""), style = t.style(t.small), color = c.text) },
                trailingIcon = {
                    Switch(sv.name in chat.mcpOn, { chat.toggleMcp(sv.name) },
                        colors = SwitchDefaults.colors(checkedTrackColor = c.accent, uncheckedTrackColor = c.field))
                },
                onClick = { chat.toggleMcp(sv.name) }, modifier = Modifier.testTag(AiTags.mcp(sv.name)),
            )
        }
    }
}

/** The More sheet: the declared groups under their headers, less what a two-row bar shows elsewhere (plugins in their pickers, Usage & Cost in the top bar). */
@Composable
private fun MoreControl(chat: AiChatState, theme: AiTheme, design: AiDesign, ctl: AiControl, enabled: Set<String>, usageInTopBar: Boolean) {
    val hidden = design.pluginClassControl.filterValues { it in enabled }.keys
    val groups = design.moreGroups.map { g ->
        g.copy(items = g.items.filterNot { (it.cls.isNotEmpty() && it.cls in hidden) || (usageInTopBar && it.kind == "usage") })
    }.filter { it.items.isNotEmpty() }
    ItemMenu(chat, theme, design, ctl, groups)
}

/** The items of one plugin class ("skill" or "performance") as a single headerless group: the Plugins -> Skills and Plugins: performance pickers. */
internal fun pickerGroups(design: AiDesign, cls: String): List<AiMoreGroup> =
    listOf(AiMoreGroup(cls, "", design.moreGroups.flatMap { it.items }.filter { it.cls == cls }))

/** A chip whose menu lists [groups] (plugin toggles, prompts, session actions, info dialogs): the More sheet and the two plugin pickers. */
@Composable
private fun ItemMenu(chat: AiChatState, theme: AiTheme, design: AiDesign, ctl: AiControl, groups: List<AiMoreGroup>) {
    val c = theme.colors
    val t = theme.type
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var live by remember { mutableStateOf<Map<String, Boolean>?>(null) }
    Ctl(ctl, theme, design, if (ctl.id == "more") "" else ctl.label) { close ->
        LaunchedEffect(Unit) { live = withContext(Dispatchers.IO) { runCatching { chat.host().plugins() }.getOrDefault(emptyMap()) } }
        groups.forEach { g ->
            if (g.label.isNotEmpty()) Text(g.label, color = c.text2, style = t.style(t.label, FontWeight.Bold), modifier = Modifier.padding(horizontal = theme.metrics.gutter, vertical = theme.metrics.small).testTag("ai_more_group_${g.id}"))
            g.items.forEach { item ->
                if (item.kind == "plugin") {
                    val listed = live?.containsKey(item.plugin) == true
                    val on = chat.pluginChoice[item.id] ?: (live?.get(item.plugin) == true)
                    DropdownMenuItem(
                        text = { Text(item.label + if (listed) "" else " · " + theme.strings.notListed, style = t.style(t.small), color = if (listed) c.text else c.text2) },
                        trailingIcon = { Switch(on, { chat.setPlugin(item, it) }, enabled = listed, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, uncheckedTrackColor = c.field)) },
                        enabled = listed, onClick = { chat.setPlugin(item, !on) }, modifier = Modifier.testTag(AiTags.morePlugin(item.id)),
                    )
                } else DropdownMenuItem(
                    text = { Text(item.label, style = t.style(t.small), color = c.text) },
                    onClick = {
                        close()
                        when (item.kind) {
                            "usage" -> dialog = item.label to chat.host().usage(chat.session.id).joinToString("\n") { "${it.label}: ${it.value}" }.ifBlank { theme.strings.noUsage }
                            "export" -> dialog = item.label to AiExport.markdown(chat.session)
                            "system" -> dialog = item.label to chat.host().systemPrompt().ifBlank { theme.strings.noSystem }
                            "info" -> dialog = item.label to item.text
                            "prompt" -> chat.draft = item.prompt
                            "undo" -> chat.undoLast()
                            "retry" -> chat.undoLast()?.let { ask -> scope.launch { chat.send(ask) } }
                        }
                        Unit
                    },
                    modifier = Modifier.testTag(AiTags.more(item.id)),
                )
            }
        }
    }
    dialog?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()).testTag(AiTags.MORE_DIALOG)) { Text(body) } },
            confirmButton = { TextButton({ dialog = null }) { Text(theme.strings.close) } },
        )
    }
}

/** The Specialist chip: the persona (specialist) of the thread, a menu of every specialist. */
@Composable
private fun SpecialistControl(theme: AiTheme, design: AiDesign, ctl: AiControl, specialists: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val t = theme.type
    if (specialists.isEmpty()) return
    Ctl(ctl, theme, design, specialists.firstOrNull { it.first == selected }?.second ?: ctl.label) { close ->
        specialists.forEach { (id, label) ->
            DropdownMenuItem(
                text = { Text(label, style = t.style(t.small, FontWeight.SemiBold), color = if (id == selected) theme.colors.accent else theme.colors.text) },
                onClick = { onSelect(id); close() }, modifier = Modifier.testTag(AiTags.specialist(id)),
            )
        }
    }
}

/** The Repos chip: which repositories the model may see (the app's composer.json::repos), each a switch. */
@Composable
private fun ReposControl(chat: AiChatState, theme: AiTheme, design: AiDesign, ctl: AiControl, repos: List<String>) {
    val c = theme.colors
    val t = theme.type
    Ctl(ctl, theme, design, if (chat.reposOn.isEmpty()) ctl.label else "${ctl.label} ${chat.reposOn.size}") { _ ->
        if (repos.isEmpty()) DropdownMenuItem(text = { Text(theme.strings.noRepos, style = t.style(t.small), color = c.text2) }, onClick = {})
        repos.forEach { name ->
            DropdownMenuItem(
                text = { Text(name, style = t.style(t.small), color = c.text) },
                trailingIcon = { Switch(name in chat.reposOn, { chat.toggleRepo(name) }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, uncheckedTrackColor = c.field)) },
                onClick = { chat.toggleRepo(name) }, modifier = Modifier.testTag(AiTags.repo(name)),
            )
        }
    }
}

/** One of the four MCP pickers: the servers of its bucket (ai-design.json mcp.buckets), the gateway's and the declared ones not listed yet (greyed). */
@Composable
private fun McpBucketControl(chat: AiChatState, theme: AiTheme, design: AiDesign, ctl: AiControl) {
    val c = theme.colors
    val t = theme.type
    val bucket = design.mcpBuckets.firstOrNull { it.control == ctl.id } ?: return
    LaunchedEffect(Unit) {
        if (chat.mcpServers.isEmpty()) chat.mcpServers = withContext(Dispatchers.IO) { runCatching { chat.host().mcpServers() }.getOrDefault(emptyList()) }
    }
    val listed = chat.mcpServers.filter { design.mcpBucket(it.name)?.id == bucket.id }
    val offline = bucket.known.filter { k -> listed.none { it.name == k } }
    val on = (listed.map { it.name } + offline).count { it in chat.mcpOn }
    Ctl(ctl, theme, design, if (on == 0) ctl.label else "${ctl.label} $on") { _ ->
        if (listed.isEmpty() && offline.isEmpty()) DropdownMenuItem(text = { Text(theme.strings.noMcp, style = t.style(t.small), color = c.text2) }, onClick = {})
        listed.forEach { sv ->
            DropdownMenuItem(
                text = { Text(sv.name + (sv.tools?.let { " · $it" } ?: ""), style = t.style(t.small), color = c.text) },
                trailingIcon = { Switch(sv.name in chat.mcpOn, { chat.toggleMcp(sv.name) }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, uncheckedTrackColor = c.field)) },
                onClick = { chat.toggleMcp(sv.name) }, modifier = Modifier.testTag(AiTags.mcp(sv.name)),
            )
        }
        offline.forEach { name ->
            DropdownMenuItem(
                text = { Text(name + " · " + theme.strings.notListed, style = t.style(t.small), color = c.text2) },
                trailingIcon = { Switch(false, {}, enabled = false) },
                enabled = false, onClick = {}, modifier = Modifier.testTag(AiTags.mcp(name)),
            )
        }
    }
}
