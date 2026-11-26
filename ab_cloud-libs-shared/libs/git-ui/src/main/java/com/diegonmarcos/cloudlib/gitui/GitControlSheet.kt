package com.diegonmarcos.cloudlib.gitui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.diegonmarcos.cloudlib.gitcore.CloneOptions
import com.diegonmarcos.cloudlib.gitcore.GitStep
import com.diegonmarcos.cloudlib.gitcore.SyncOptions

/** #1934 which part of the sheet a composition draws: the pinned top (header + tab row), the scrolling body, or both. */
enum class GitSheetPart { ALL, TOP, BODY }

/** #1934 the sheet's own state: the open tab and the two option models. The host keeps it across recomposition ([rememberGitSheetState]). */
@Stable
class GitSheetState(sync: SyncOptions, clone: CloneOptions, tab: String) {
    var tab by mutableStateOf(tab)
    var sync by mutableStateOf(sync)
    var clone by mutableStateOf(clone)
    var confirm by mutableStateOf<SheetControl?>(null)
    var confirmRun by mutableStateOf<String?>(null)
}

@Composable
fun rememberGitSheetState(sheet: GitSheet, key: Any? = null): GitSheetState = remember(key) { GitSheetState(sheet.syncDefaults, sheet.cloneDefaults, sheet.tabs.firstOrNull()?.id.orEmpty()) }

/** What the host knows about the repository (or the selection) the sheet is for. All of it is real state; a missing field draws the dash. */
data class GitSheetHost(
    val cloned: Boolean, val values: Map<String, String> = emptyMap(), val active: Set<String> = emptySet(), val busy: Boolean = false,
    val autoSyncOn: Boolean = false, val autoSyncMinutes: Int = 15, val output: String? = null,
    /** The clone URL and the three roots the Clone Destination group picks from. */
    val cloneUrl: String = "", val repoName: String = "", val storeRoot: String = "store", val workspaceRoot: String = "workspace", val mirrorRoot: String = "mirrors",
    /** Bulk: the repositories the sheet acts on (the destructive confirm lists them); empty = a single repository. */
    val targets: List<String> = emptyList(), val bulk: Boolean = false,
    /** Controls the host cannot run right now, with the reason (control id -> reason). */
    val unavailable: Map<String, String> = emptyMap(),
    val results: List<Pair<String, String>> = emptyList(),
    /** The remote refuses pushes (a read-only clone): every control that writes to the remote is drawn disabled with the reason. */
    val readOnly: Boolean = false,
)

/** What a tap on a control asks of the host. */
interface GitSheetActions {
    fun action(c: SheetControl)
    fun runSync(o: SyncOptions)
    fun runClone(o: CloneOptions)
    fun copy(text: String)
    fun setAutoSync(on: Boolean, minutes: Int)
}

private fun SyncOptions.value(key: String): String = when (key) { "direction" -> direction; "strategy" -> strategy; "winner" -> winner; "dirty" -> dirty; "scope" -> scope; else -> "" }
private fun CloneOptions.value(key: String): String = when (key) { "dest" -> dest; "workspace" -> workspace; "branches" -> branches; "history" -> history; else -> "" }
private fun SyncOptions.flagOn(f: String) = when (f) { "prune" -> prune; "tags" -> tags; "submodules" -> submodules; "forceLease" -> forceLease; "forceRaw" -> forceRaw; "dryRun" -> dryRun; else -> false }
private fun SyncOptions.withFlag(f: String, on: Boolean): SyncOptions = when (f) {
    "prune" -> copy(prune = on); "tags" -> copy(tags = on); "submodules" -> copy(submodules = on)
    "forceLease" -> copy(forceLease = on, forceRaw = if (on) false else forceRaw); "forceRaw" -> copy(forceRaw = on, forceLease = if (on) false else forceLease)
    "dryRun" -> copy(dryRun = on); else -> this
}

private fun SyncOptions.matches(c: Map<String, List<String>>) = c.all { (k, v) -> value(k) in v }

/**
 * #1934 THE UNIFIED REPO CONTROL SHEET: the header (name, visibility, owner, clone state, Refresh, the action row, the not-cloned banner with Clone
 * Now), the sticky tab strip and the open tab's groups, all drawn from the declared [sheet]. [bulk] draws the same five tabs for the selection.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun GitControlSheetView(
    sheet: GitSheet, state: GitSheetState, host: GitSheetHost, actions: GitSheetActions, modifier: Modifier = Modifier, onTabSelected: (String) -> Unit = {},
    /** TOP = the header and the tab row (the host pins them outside its scrolling body), BODY = the open tab's groups and the confirm dialogs, ALL = both. */
    part: GitSheetPart = GitSheetPart.ALL,
) {
    val k = LocalGitUiKit.current
    val tabs = sheet.tabsFor(host.bulk)
    val open = tabs.firstOrNull { it.id == state.tab } ?: tabs.firstOrNull()
    val whyNoClone = sheet.reasons["needs_clone"].orEmpty()
    fun blocked(c: SheetControl): String? = when {
        c.disabled -> c.reason.ifBlank { null } ?: "-"
        host.unavailable[c.id] != null -> host.unavailable[c.id]
        host.readOnly && c.writesRemote -> sheet.reasons["read_only"].orEmpty().ifBlank { "-" }
        host.busy && c.kind in setOf("run") -> "…"
        c.needsClone && !host.cloned && !host.bulk -> whyNoClone
        else -> null
    }
    Column(modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }.testTag("git_control_sheet_" + part.name.lowercase()), verticalArrangement = Arrangement.spacedBy(k.gapWide)) {
        if (part != GitSheetPart.BODY && !host.bulk) Header(sheet, host, actions, ::blocked)
        if (part != GitSheetPart.BODY) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("git_sheet_tabs"), horizontalArrangement = Arrangement.spacedBy(k.gapWide)) {
            tabs.forEach { t -> k.pill(GitPill(t.label, { state.tab = t.id; onTabSelected(t.id) }, Modifier.testTag("git_sheet_tab_" + t.id), filled = t.id == open?.id)) }
        }
        if (part != GitSheetPart.TOP) open?.groups?.forEach { g -> GroupView(sheet, g, tabs, state, host, actions, ::blocked) }
    }
    if (part != GitSheetPart.TOP) state.confirm?.let { c ->
        ConfirmDialog(c.label, host.targets, { state.confirm = null }) { state.confirm = null; actions.action(c) }
    }
    if (part != GitSheetPart.TOP) state.confirmRun?.let { which ->
        ConfirmDialog(which.substringAfter('|'), host.targets, { state.confirmRun = null }) { state.confirmRun = null; if (which.startsWith("sync")) actions.runSync(state.sync) else actions.runClone(state.clone) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(sheet: GitSheet, host: GitSheetHost, actions: GitSheetActions, blocked: (SheetControl) -> String?) {
    val k = LocalGitUiKit.current
    val h = sheet.header
    Column(Modifier.testTag("git_sheet_header"), verticalArrangement = Arrangement.spacedBy(k.gap)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(k.pad)) {
            Column(Modifier.weight(1f)) {
                h.fields.filter { it.source == "name" }.forEach { Text(host.values[it.source].orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                Row(horizontalArrangement = Arrangement.spacedBy(k.pad)) {
                    h.fields.filter { it.source == "visibility" || it.source == "clone_state" }.forEach { Text(host.values[it.source].orEmpty(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("git_sheet_" + it.source)) }
                }
                h.fields.filter { it.source == "summary" }.forEach { Text(host.values[it.source].orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            h.refresh?.let { r -> k.pill(GitPill(r.label, { actions.action(r) }, Modifier.testTag("git_sheet_" + r.id))) }
            h.close?.let { c -> k.pill(GitPill(c.label, { actions.action(c) }, Modifier.testTag("git_sheet_" + c.id))) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(k.gapWide), verticalArrangement = Arrangement.spacedBy(k.gapWide)) {
            h.actions.forEach { a -> val why = blocked(a); k.pill(GitPill(a.label, { actions.action(a) }, Modifier.testTag("git_sheet_" + a.id), enabled = why == null)) }
        }
        if (!host.cloned) {
            h.banner?.let { b ->
                Row(Modifier.testTag("git_sheet_banner"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(k.pad)) {
                    Text(b.label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    h.cloneNow?.let { c -> k.pill(GitPill(c.label, { actions.action(c) }, Modifier.testTag("git_sheet_" + c.id), filled = true, enabled = blocked(c) == null)) }
                }
            }
        }
    }
}

@Composable
private fun Reasons(items: List<Pair<String, String>>) {
    items.distinct().forEach { (label, why) -> Text("$label: $why", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupView(
    sheet: GitSheet, g: SheetGroup, tabs: List<SheetTab>, state: GitSheetState, host: GitSheetHost, actions: GitSheetActions, blocked: (SheetControl) -> String?,
) {
    val k = LocalGitUiKit.current
    val s = state.sync
    // The bulk sheet reuses the per-repo group of the same tab for the option groups it shares.
    if (g.sameAs.isNotBlank()) {
        sheet.tabs.firstOrNull { it.id == g.sameAs }?.groups?.filter { it.layout == "segmented" || it.layout == "flags" }
            ?.forEach { GroupView(sheet, it, tabs, state, host, actions, blocked) }
        return
    }
    if (g.shownWhen.isNotEmpty() && !s.matches(g.shownWhen)) return
    if (g.hiddenWhen.isNotEmpty() && s.matches(g.hiddenWhen)) return
    Column(Modifier.testTag("git_sheet_group_" + g.id), verticalArrangement = Arrangement.spacedBy(k.gap)) {
        Text(g.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = if (g.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        when (g.layout) {
            "fields", "counts" -> g.controls.forEach { c ->
                if (c.kind == "action") {
                    val why = blocked(c); k.pill(GitPill(c.label, { if (c.confirm) state.confirm = c else actions.action(c) }, Modifier.testTag("git_sheet_ctl_" + c.id), enabled = why == null))
                } else Row(Modifier.fillMaxWidth().testTag("git_sheet_ctl_" + c.id), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(c.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(host.values[c.source] ?: GitTable.DASH, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                }
            }
            "run" -> RunGroup(sheet, g, state, host, actions, blocked)
            "output" -> Column(Modifier.testTag("git_sheet_output")) {
                Text(host.output ?: "", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                host.results.forEach { (n, r) -> Text("$n  $r", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
            }
            "autosync" -> AutoSyncGroup(g, host, actions)
            else -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(k.gapWide), verticalArrangement = Arrangement.spacedBy(k.gapWide)) {
                    g.controls.forEach { c -> ControlPill(sheet, c, g, state, host, actions, blocked) }
                }
                g.chips?.let { ch -> if (ch.shownWhenOption.all { (key, v) -> state.clone.value(key) == v }) DepthChips(ch, state) }
                g.input?.let { inp -> if (inp.shownWhenOption.all { (key, v) -> (state.sync.value(key).ifBlank { state.clone.value(key) }) == v }) InputField(inp, state) }
                Reasons(g.controls.mapNotNull { c -> blocked(c)?.takeIf { c.disabled || host.unavailable[c.id] != null || c.needsClone && !host.cloned }?.let { c.label to it } })
            }
        }
    }
}

@Composable
private fun ControlPill(sheet: GitSheet, c: SheetControl, g: SheetGroup, state: GitSheetState, host: GitSheetHost, actions: GitSheetActions, blocked: (SheetControl) -> String?) {
    val k = LocalGitUiKit.current
    var why = blocked(c)
    val s = state.sync
    val cl = state.clone
    val tag = Modifier.testTag("git_sheet_ctl_" + c.id)
    when (c.kind) {
        "preset" -> {
            val isSync = sheet.syncPresets.any { it.id == c.preset } && c.id.startsWith("sy.")
            val p = (if (isSync) sheet.syncPresets else sheet.clonePresets).firstOrNull { it.id == c.preset } ?: return
            val on = if (isSync) SheetPresets.activeSync(s, p) else SheetPresets.activeClone(cl, p)
            k.pill(GitPill(c.label, { if (isSync) state.sync = SheetPresets.sync(SyncOptions(commitMessage = s.commitMessage), p) else state.clone = SheetPresets.clone(cl, p) }, tag, filled = on))
        }
        "option" -> if (c.group.isNotBlank()) {
            val isSync = c.id.startsWith("sy.")
            val on = (if (isSync) s.value(c.group) else cl.value(c.group)) == c.value
            k.pill(GitPill(c.label, {
                if (isSync) state.sync = when (c.group) { "direction" -> s.copy(direction = c.value); "strategy" -> s.copy(strategy = c.value); "winner" -> s.copy(winner = c.value); "dirty" -> s.copy(dirty = c.value); else -> s.copy(scope = c.value) }
                else state.clone = when (c.group) { "dest" -> cl.copy(dest = c.value); "workspace" -> cl.copy(workspace = c.value); "branches" -> cl.copy(branches = c.value); else -> cl.copy(history = c.value) }
            }, tag, filled = on, enabled = why == null, danger = on && c.danger))
        } else k.pill(GitPill(c.label, { actions.action(c) }, tag, filled = c.id in host.active || c.action in host.active, enabled = why == null))
        "flag" -> {
            val isSync = c.id.startsWith("sy.")
            if (isSync && !s.applicable(c.flag)) why = why ?: sheet.reasons["not_applicable"].orEmpty().ifBlank { "-" }
            val on = if (isSync) s.flagOn(c.flag) else when (c.flag) { "trees" -> cl.trees; "blobs" -> cl.blobs; else -> true }
            k.pill(GitPill((if (on) "✓ " else "○ ") + c.label, {
                if (isSync) state.sync = s.withFlag(c.flag, !on)
                else if (!c.locked) state.clone = when (c.flag) { "trees" -> cl.copy(trees = !on, blobs = if (on) false else cl.blobs); "blobs" -> cl.copy(blobs = !on); else -> cl }
            }, tag, filled = on, enabled = why == null && !c.locked, danger = on && c.danger))
        }
        else -> k.pill(GitPill(c.label, { if (c.confirm) state.confirm = c else actions.action(c) }, tag, filled = c.id in host.active || c.action in host.active, enabled = why == null, danger = c.danger))
    }
}

@Composable
private fun DepthChips(ch: SheetChips, state: GitSheetState) {
    val k = LocalGitUiKit.current
    Row(horizontalArrangement = Arrangement.spacedBy(k.gapWide), verticalAlignment = Alignment.CenterVertically) {
        ch.values.forEach { v -> k.pill(GitPill(v.toString(), { state.clone = state.clone.copy(depth = v) }, Modifier.testTag("git_sheet_depth_$v"), filled = state.clone.depth == v)) }
        if (ch.custom) OutlinedTextField(state.clone.depth.toString(), { t -> t.toIntOrNull()?.let { state.clone = state.clone.copy(depth = it.coerceIn(1, 9999)) } }, Modifier.testTag("git_sheet_depth_custom"), singleLine = true, label = { Text("#") })
    }
}

@Composable
private fun InputField(inp: SheetInput, state: GitSheetState) {
    val value = if (inp.key == "commitMessage") state.sync.commitMessage else state.clone.branch
    OutlinedTextField(value, { t -> if (inp.key == "commitMessage") state.sync = state.sync.copy(commitMessage = t) else state.clone = state.clone.copy(branch = t) },
        Modifier.fillMaxWidth().testTag("git_sheet_input_" + inp.id), singleLine = true, label = { Text(inp.label) })
}

@Composable
private fun AutoSyncGroup(g: SheetGroup, host: GitSheetHost, actions: GitSheetActions) {
    val k = LocalGitUiKit.current
    val interval = g.controls.firstOrNull { it.kind == "select" }
    val toggle = g.controls.firstOrNull { it.kind == "toggle" }
    Row(horizontalArrangement = Arrangement.spacedBy(k.gapWide), verticalAlignment = Alignment.CenterVertically) {
        interval?.values?.forEach { m -> k.pill(GitPill("$m", { actions.setAutoSync(host.autoSyncOn, m) }, Modifier.testTag("git_sheet_interval_$m"), filled = host.autoSyncMinutes == m, enabled = host.cloned || host.bulk)) }
        toggle?.let { t -> k.pill(GitPill(if (host.autoSyncOn) "ON" else "OFF", { actions.setAutoSync(!host.autoSyncOn, host.autoSyncMinutes) }, Modifier.testTag("git_sheet_ctl_" + t.id), filled = host.autoSyncOn, enabled = host.cloned || host.bulk)) }
    }
    interval?.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunGroup(sheet: GitSheet, g: SheetGroup, state: GitSheetState, host: GitSheetHost, actions: GitSheetActions, blocked: (SheetControl) -> String?) {
    val k = LocalGitUiKit.current
    val s = state.sync; val cl = state.clone
    g.controls.forEach { c ->
        when (c.kind) {
            "preview" -> {
                val text = commandPreview(c.plan, s, cl, host)
                Text(text, Modifier.testTag("git_sheet_preview_" + c.plan), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                if (c.plan == "sync") Text(stringResource(badgeRes(s.badge())), Modifier.testTag("git_sheet_badge"), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                    color = if (s.badge() == "destructive") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                s.unsupported()?.takeIf { c.plan == "sync" }?.let { Text(stringResource(R.string.git_sheet_unsupported, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
            "run" -> {
                val isSync = c.action.startsWith("run_sync")
                val why = blocked(c) ?: (if (isSync) s.unsupported() else null) ?: (if (isSync && host.readOnly && s.normalized().pushes) sheet.reasons["read_only"] else null)
                val label = if (c.labelFrom == "profile") stringResource(R.string.git_sheet_clone_profile, cl.profileName()) else c.label
                k.pill(GitPill(label, {
                    val ask = if (isSync) (s.destructive() || host.bulk) else host.bulk
                    if (ask) { state.confirmRun = (if (isSync) "sync" else "clone") + "|" + label } else if (isSync) { actions.runSync(s) } else { actions.runClone(cl) }
                },
                    Modifier.testTag("git_sheet_ctl_" + c.id), filled = true, enabled = why == null && !host.busy, danger = isSync && s.destructive()))
                if (why != null) Text(why, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> k.pill(GitPill(c.label, { actions.copy(commandPreview(if (g.id.startsWith("cl") || g.id.startsWith("bcl")) "clone" else "sync", s, cl, host)) }, Modifier.testTag("git_sheet_ctl_" + c.id)))
        }
    }
}

/** The command preview of the sync or clone plan: the SAME argv the host executes, joined as [GitStep.preview] prints it. */
fun commandPreview(plan: String, s: SyncOptions, cl: CloneOptions, host: GitSheetHost): String =
    if (plan == "clone") GitStep.preview(listOf(cl.step(host.cloneUrl.ifBlank { "<url>" }, cl.destPath(host.repoName.ifBlank { "<name>" }, host.storeRoot, host.workspaceRoot, host.mirrorRoot))))
    else GitStep.preview(s.steps())

private fun badgeRes(b: String): Int = when (b) {
    "preview" -> R.string.git_sheet_badge_preview; "destructive" -> R.string.git_sheet_badge_destructive; "caution" -> R.string.git_sheet_badge_caution
    "read_only" -> R.string.git_sheet_badge_read_only; else -> R.string.git_sheet_badge_safe
}

/** The explicit confirm of a destructive control: in the bulk sheet it lists every repository it will touch. */
@Composable
private fun ConfirmDialog(what: String, targets: List<String>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, modifier = Modifier.testTag("git_sheet_confirm"),
        title = { Text(what) },
        text = {
            Column {
                Text(stringResource(R.string.git_sheet_confirm_body, targets.size.coerceAtLeast(1)))
                targets.forEach { Text("• $it", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("git_sheet_confirm_yes")) { Text(stringResource(R.string.git_sheet_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.git_wf_cancel)) } },
    )
}
