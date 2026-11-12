package com.diegonmarcos.superapp.configs

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.perms.FleetPerms
import com.diegonmarcos.superapp.perms.GroupBy
import com.diegonmarcos.superapp.perms.PermCategory
import com.diegonmarcos.superapp.perms.PermsPageQuery
import com.diegonmarcos.superapp.perms.PermsQuery
import com.diegonmarcos.superapp.perms.Protection
import com.diegonmarcos.superapp.perms.SortBy
import com.diegonmarcos.superapp.perms.StatusFilter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.perms.AdbLine
import com.diegonmarcos.superapp.perms.AdbScript
import com.diegonmarcos.superapp.perms.PermEntry
import com.diegonmarcos.superapp.perms.PermKind
import com.diegonmarcos.superapp.perms.PermSection
import com.diegonmarcos.superapp.perms.PermState
import com.diegonmarcos.superapp.perms.PermStatus
import com.diegonmarcos.superapp.perms.PermStatusReader
import com.diegonmarcos.superapp.perms.Perms
import com.diegonmarcos.superapp.perms.PermsLink
import com.diegonmarcos.superapp.perms.PermsSettings
import com.diegonmarcos.superapp.perms.PermsPage
import com.diegonmarcos.superapp.perms.RowAction
import com.diegonmarcos.superapp.perms.RowChip
import com.diegonmarcos.superapp.perms.PermsShellGrant
import com.diegonmarcos.superapp.perms.PermsGrant
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreQuick
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** One row's resolved state for the catalogue section (#1274). */
data class PermRowState(val entry: PermEntry, val status: PermStatus)

/** Result of one ADB line run through the on-device channel. */
data class AdbRunResult(val line: AdbLine, val ok: Boolean, val output: String)

/** Pure: whether a shell answer means the line worked. pm grant / appops / cmd answer nothing on success. */
object AdbResult {
    private val BAD = Regex("(?i)exception|error|not allowed|unknown|failed|denied|bad argument|usage:|not found|invalid")
    fun ok(output: String?): Boolean = output != null && !BAD.containsMatchIn(output)
}

/** Reads every catalogue entry's state; the page calls it on open and on every resume. */
fun readPermRows(ctx: Context, activity: Activity?): List<PermRowState> {
    val vars = Perms.vars(ctx)
    return Perms.catalogue(ctx).entries.map { PermRowState(it, PermStatusReader.status(ctx, it, activity, vars)) }
}

/** The adb lines for every applicable entry (granted ones too: the block is the full grant, safe to re-run). */
fun adbLinesFor(ctx: Context, rows: List<PermRowState>): List<AdbLine> =
    AdbScript.lines(rows.filter { it.status.state != PermState.NOT_APPLICABLE }.map { it.entry }, Perms.vars(ctx))

/** #1519 the page's rows (libs:perms PermsPage): category, chip, reason, actions per catalogue entry. */
internal fun pageRows(ctx: Context, rows: List<PermRowState>): List<PermsPage.Row> {
    val by = rows.associateBy { it.entry.id }
    return PermsPage.rows(rows.map { it.entry }, { by.getValue(it.id).status }, { PermsShellGrant.commands(ctx.packageName, it) != null })
}

/**
 * #1510 / #1519 the shell broker as the page's grant channel: `up` = a privileged channel is armed; `exec` answers the
 * command's output, an `error:` line when the broker refused it or it exited non-zero, null when nothing is armed.
 */
class BrokerGrantChannel(private val ctx: Context) : PermsGrant.GrantChannel {
    override fun up() = com.diegonmarcos.superapp.system.AdbReadiness.check(ctx).usable
    override fun exec(command: String): String? =
        when (val r = com.diegonmarcos.superapp.shellbroker.ShellBroker.exec(ctx, com.diegonmarcos.superapp.shellbroker.ShellBroker.SELF, command)) {
            is com.diegonmarcos.superapp.shellclient.ShellResult.Ok ->
                (r.stdout + r.stderr).trim().ifEmpty { if (r.exitCode == 0) "" else "error: exit ${r.exitCode}" }
            is com.diegonmarcos.superapp.shellclient.ShellResult.Denied -> "error: refused by the shell broker (${r.reason})"
            is com.diegonmarcos.superapp.shellclient.ShellResult.Failed -> "error: ${r.reason}"
            else -> null
        }
}

/** The sticky top of the page: "N of M granted, K missing" and Ask all missing. Never says all granted while a row is missing. */
@Composable
internal fun PermsSummaryBar(rows: List<PermRowState>, ctx: Context, status: androidx.compose.runtime.MutableState<StatusFilter>? = null, onAskAll: () -> Unit) {
    val p = LocalKitPalette.current
    val page = remember(rows) { pageRows(ctx, rows) }
    val s = PermsPage.summary(page)
    Column(Modifier.fillMaxWidth().background(p.surface).testTag("perms:summary")) {
        // #1550 KPI strip: one tile per state with its count; a tap filters the list by it (tap again: all).
        val counts = remember(page) { PermsPageQuery.counts(page) }
        Row(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight).testTag("perms:kpi"),
            horizontalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
            for (k in PermsKpi.TILES) {
                val on = status?.value == k
                val ink = PermsKpi.ink(k, p)
                Column(Modifier.weight(1f).background(if (on) ink.copy(alpha = 0.22f) else p.textPrimary.copy(alpha = 0.05f))
                    .border(KitStoreRow.step(1), if (on) ink else p.hairline)
                    .clickable(role = androidx.compose.ui.semantics.Role.Tab) { status?.let { it.value = if (on) StatusFilter.ALL else k } }
                    .padding(horizontal = KitStoreRow.gap, vertical = KitStoreRow.tight).testTag("perms:kpi:${k.name.lowercase()}")
                    .androidxSemantics("${counts[k] ?: 0} ${k.word}" + if (on) ", filter on" else "")) {
                    Text("${counts[k] ?: 0}", fontSize = KitStoreRow.title, fontWeight = FontWeight.Bold, color = ink, fontFamily = FontFamily.Monospace)
                    KitStoreCaption(PermsKpi.label(k), color = p.textSecondary)
                }
            }
        }
        KitStoreRowLine {
            KitStoreName(s.text, Modifier.weight(1f).testTag("perms:progress"), color = if (s.allGranted) p.ok else p.warn)
            if (!s.allGranted) KitStoreQuick("Ask all missing", KitStoreRow.BLUE_FILL, Modifier.testTag("perms:ask-all"), onAskAll)
        }
        Box(Modifier.fillMaxWidth().height(KitStoreRow.tight).background(KitStoreRow.RULE)) {
            Box(Modifier.fillMaxWidth(s.fraction).height(KitStoreRow.tight).background(p.ok))
        }
    }
}

/**
 * #1546 the catalogue-driven body of the Permissions page, organized: every declaration (not-applicable ones too), the
 * search box, filter chips by status (with counts), type, section, category, protection, permission group, feature and
 * fleet app, the pre-#1519 "Missing only" chip, group-by and sort, bulk actions (Ask all again, Ask shown, Shell shown,
 * the adb block, Copy report) and one row per permission with its state icon, chip, reason, hints, actions and detail.
 */
@Composable
internal fun PermsCenterSection(
    rows: List<PermRowState>,
    highlight: PermsLink.Request?,
    channelName: String?,
    adbResults: List<AdbRunResult>,
    adbRunning: Boolean,
    shellNote: String?,
    onClearHighlight: () -> Unit,
    onGrant: (PermEntry) -> Unit,
    onShell: (PermEntry) -> Unit,
    onRunAdb: () -> Unit,
    onOpenAdbShell: () -> Unit,
    ctx: Context,
    onAskAgain: () -> Unit = {},
    onAskMany: (List<PermEntry>) -> Unit = {},
    onShellMany: (List<PermEntry>) -> Unit = {},
    statusState: androidx.compose.runtime.MutableState<StatusFilter>? = null,
    wide: Boolean? = null,
) {
    val p = LocalKitPalette.current
    val page = remember(rows) { pageRows(ctx, rows) }
    val fleet = remember { FleetPerms.index(ctx) }
    val appsOf = remember(fleet) { { id: String -> fleet.appsUsing(id) } }
    var text by rememberSaveable { mutableStateOf("") }
    val localStatus = rememberSaveable { mutableStateOf(StatusFilter.ALL) }
    var status by (statusState ?: localStatus)
    var advanced by rememberSaveable { mutableStateOf(true) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var missingOnly by rememberSaveable { mutableStateOf(highlight != null) }
    var groupBy by rememberSaveable { mutableStateOf(GroupBy.CATEGORY) }
    var sortBy by rememberSaveable { mutableStateOf(SortBy.MISSING_FIRST) }
    var kinds by remember { mutableStateOf(emptySet<PermKind>()) }
    var sections by remember { mutableStateOf(emptySet<PermSection>()) }
    var cats by remember { mutableStateOf(emptySet<PermCategory>()) }
    var prots by remember { mutableStateOf(emptySet<Protection>()) }
    var groups by remember { mutableStateOf(emptySet<String>()) }
    var features by remember { mutableStateOf(emptySet<String>()) }
    var apps by remember { mutableStateOf(emptySet<String>()) }
    val q = PermsQuery(text, status, kinds, sections, cats, prots, groups, features, apps, missingOnly, highlight?.ids?.toSet(), groupBy, sortBy)
    // #1550 computed fresh every recomposition (NOT remember-keyed): a stale cleared/full after a filter toggle hid the
    // "nothing missing" groups under Missing only. The work is a few list passes over the page.
    val shown = PermsPageQuery.query(page, q, appsOf)
    val shownRows = shown.flatMap { it.rows }.distinctBy { it.entry.id }
    val full = PermsPageQuery.fullGroups(page, q, appsOf)
    val cleared = PermsPageQuery.nothingMissing(page, q, appsOf)
    val counts = remember(page) { PermsPageQuery.counts(page) }
    fun <T> Set<T>.flip(v: T) = if (v in this) this - v else this + v

    // #1550 phone: controls then the table, one column. Tablet / landscape (>= 840 dp): two panes, controls | table.
    val controls: @Composable () -> Unit = {
        if (highlight != null) {
            val n = rows.count { it.entry.id in highlight.ids }
            KitStoreRowLine(Modifier.background(p.accent.copy(alpha = 0.18f)).testTag("perms:highlight")) {
                KitStoreCaption("Requested by ${highlight.feature}: $n permission(s) highlighted below", Modifier.weight(1f), color = p.textPrimary)
                KitStoreQuick("Show all", KitStoreRow.BLUE_FILL, Modifier.testTag("perms:highlight-clear"), onClearHighlight)
            }
        }
        // ── search ──
        androidx.compose.foundation.text.BasicTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = p.textPrimary, fontSize = 12.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(p.accent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight)
                .background(p.textPrimary.copy(alpha = 0.07f)).padding(6.dp).testTag("perms:search"),
            decorationBox = { inner -> Box { if (text.isEmpty()) Text("Search name, permission, group, app, reason…", fontSize = 12.sp, color = p.textSecondary); inner() } },
        )
        // ── filters ──
        PermsChipRow("Status") {
            // #1550 Missing only leads the row (a primary filter) so it is always in view, never scrolled off the right.
            PermsFilterPill(if (missingOnly) "✓ Missing only" else "Missing only", missingOnly, "perms:filter-missing") { missingOnly = !missingOnly }
            for (f in StatusFilter.values()) PermsFilterPill("${f.word} ${counts[f] ?: 0}", status == f, "perms:filter:status:${f.name.lowercase()}") { status = f }
        }
        val advActive = listOf(kinds, sections, cats, prots, groups, features, apps).count { it.isNotEmpty() }
        KitStoreRowLine(Modifier.testTag("perms:advanced-toggle"), onClick = { advanced = !advanced }) {
            KitStoreCaption((if (advanced) "▾ " else "▸ ") + "Advanced filters" + (if (advActive > 0) " · $advActive active" else "") +
                "  (type, section, category, protection, group, feature, app)", Modifier.weight(1f), color = p.accent)
        }
        if (advanced) Column(Modifier.fillMaxWidth().background(p.textPrimary.copy(alpha = 0.03f)).testTag("perms:advanced")) {
        PermsChipRow("Type") { for (k in PermsPageQuery.kindsPresent(page)) PermsFilterPill("${PermsPageQuery.kindTitle(k)} ${page.count { it.entry.kind == k }}", k in kinds, "perms:filter:type:${k.name.lowercase()}") { kinds = kinds.flip(k) } }
        PermsChipRow("Section") { for (s in PermsPageQuery.sectionsPresent(page)) PermsFilterPill("${s.title} ${page.count { it.entry.section == s }}", s in sections, "perms:filter:section:${s.name.lowercase()}") { sections = sections.flip(s) } }
        PermsChipRow("Category") { for (c in PermsPageQuery.categoriesPresent(page)) PermsFilterPill("${c.title} ${page.count { it.category == c }}", c in cats, "perms:filter:category:${c.name.lowercase()}") { cats = cats.flip(c) } }
        PermsChipRow("Protection") { for (x in Protection.values()) { val n = page.count { PermsPageQuery.protection(it.entry) == x }; if (n > 0) PermsFilterPill("${x.word} $n", x in prots, "perms:filter:protection:${x.name.lowercase()}") { prots = prots.flip(x) } } }
        PermsChipRow("Group") { for (g in PermsPageQuery.groupsPresent(page)) PermsFilterPill(g.lowercase().replace('_', ' '), g in groups, "perms:filter:group:${g.lowercase()}") { groups = groups.flip(g) } }
        PermsChipRow("Feature") { for (f in PermsPageQuery.featuresPresent(page)) PermsFilterPill(f, f in features, "perms:filter:feature:$f") { features = features.flip(f) } }
        if (fleet.apps.isNotEmpty()) PermsChipRow("App") { for (a in fleet.labels()) PermsFilterPill(a, a in apps, "perms:filter:app:$a") { apps = apps.flip(a) } }
        }
        PermsChipRow("Group by") { for (g in GroupBy.values()) PermsFilterPill(g.word, groupBy == g, "perms:groupby:${g.name.lowercase()}") { groupBy = g } }
        PermsChipRow("Sort") {
            for (s in SortBy.values()) PermsFilterPill(s.word, sortBy == s, "perms:sort:${s.name.lowercase()}") { sortBy = s }
            if (q.active) PermsFilterPill("Clear filters ✕", false, "perms:filter-clear") {
                text = ""; status = StatusFilter.ALL; missingOnly = false; kinds = emptySet(); sections = emptySet(); cats = emptySet()
                prots = emptySet(); groups = emptySet(); features = emptySet(); apps = emptySet()
            }
        }
        KitStoreRowLine {
            KitStoreCaption("Showing ${shownRows.size} of ${page.size} declarations · ${shown.size} groups", Modifier.weight(1f).testTag("perms:shown"), color = p.textSecondary)
            KitStoreQuick(if (selecting) "Done selecting" else "Select", KitStoreRow.BLUE_FILL, Modifier.testTag("perms:select")) { selecting = !selecting; if (!selecting) selected = emptySet() }
        }
        // #1550 multi-select bulk bar: what the selection can do, with counts.
        if (selecting) {
            val sel = shownRows.filter { it.entry.id in selected }
            val askSel = PermsPageQuery.dialogTargets(sel); val shellSel = PermsPageQuery.shellTargets(sel)
            KitStoreRowLine(Modifier.background(p.accent.copy(alpha = 0.14f)).testTag("perms:bulkbar")) {
                KitStoreCaption("${sel.size} selected", Modifier.weight(1f), color = p.textPrimary)
                KitStoreQuick("All shown", KitStoreRow.BLUE_FILL, Modifier.testTag("perms:select-all")) { selected = shownRows.map { it.entry.id }.toSet() }
                Box(Modifier.width(KitStoreRow.tight))
                KitStoreQuick("Grant (${askSel.size})", KitStoreRow.BLUE_FILL, Modifier.testTag("perms:bulk-grant")) { if (askSel.isNotEmpty()) onAskMany(askSel) }
                Box(Modifier.width(KitStoreRow.tight))
                KitStoreQuick("Shell (${shellSel.size})", KitStoreRow.AMBER_FILL, Modifier.testTag("perms:bulk-shell")) { if (shellSel.isNotEmpty()) onShellMany(shellSel) }
                Box(Modifier.width(KitStoreRow.tight))
                KitStoreQuick("Copy adb", KitStoreRow.BLUE_FILL, Modifier.testTag("perms:bulk-copy")) {
                    copy(ctx, AdbScript.lines(sel.map { it.entry }.filter { it.adb.isNotEmpty() }, Perms.vars(ctx)).joinToString("\n") { it.forComputer() })
                    Toast.makeText(ctx, "Copied ${sel.size} rows", Toast.LENGTH_SHORT).show()
                }
                Box(Modifier.width(KitStoreRow.tight))
                KitStoreQuick("Clear", KitStoreRow.BLUE_FILL, Modifier.testTag("perms:select-clear")) { selected = emptySet() }
            }
        }
        // ── bulk actions ──
        val dialogable = rows.filter { it.entry.kind == PermKind.RUNTIME && it.entry.autoRequest && it.status.state == PermState.DENIED }
        val askShown = PermsPageQuery.dialogTargets(shownRows)
        val shellShown = PermsPageQuery.shellTargets(shownRows)
        KitStoreRowLine {
            KitStoreQuick("Ask all again (${dialogable.size})", KitStoreRow.BLUE_FILL, Modifier.weight(1f).testTag("perms:ask-again"), onAskAgain)
            Box(Modifier.width(KitStoreRow.tight))
            KitStoreQuick("Ask shown (${askShown.size})", KitStoreRow.BLUE_FILL, Modifier.weight(1f).testTag("perms:ask-shown")) { if (askShown.isNotEmpty()) onAskMany(askShown) }
            Box(Modifier.width(KitStoreRow.tight))
            KitStoreQuick("Shell shown (${shellShown.size})", KitStoreRow.AMBER_FILL, Modifier.weight(1f).testTag("perms:shell-shown")) { if (shellShown.isNotEmpty()) onShellMany(shellShown) }
        }
        val lines = remember(rows) { adbLinesFor(ctx, rows) }
        KitStoreRowLine {
            KitStoreQuick("Copy ADB block (${lines.size})", KitStoreRow.BLUE_FILL, Modifier.weight(1f).testTag("perms:adb-copy")) {
                copy(ctx, AdbScript.block(lines, ctx.packageName))
                Toast.makeText(ctx, "Copied ${lines.size} adb commands", Toast.LENGTH_SHORT).show()
            }
            Box(Modifier.width(KitStoreRow.tight))
            if (channelName != null) KitStoreQuick(if (adbRunning) "Running…" else "Run via $channelName", KitStoreRow.BLUE_FILL,
                Modifier.weight(1f).testTag("perms:adb-run")) { if (!adbRunning) onRunAdb() }
            else KitStoreQuick("ADB not connected ›", KitStoreRow.AMBER_FILL, Modifier.weight(1f).testTag("perms:adb-connect"), onOpenAdbShell)
            Box(Modifier.width(KitStoreRow.tight))
            KitStoreQuick("Copy report", KitStoreRow.BLUE_FILL, Modifier.weight(1f).testTag("perms:copy-report")) {
                copy(ctx, permsReport(shownRows, appsOf))
                Toast.makeText(ctx, "Copied ${shownRows.size} permission rows", Toast.LENGTH_SHORT).show()
            }
        }
        if (shellNote != null) KitStoreCaption(shellNote, Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH).testTag("perms:shell-note"), color = p.textPrimary)
        if (adbResults.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH).testTag("perms:adb-results")) {
            KitStoreCaption("ADB: ${adbResults.count { it.ok }} ok · ${adbResults.count { !it.ok }} failed", color = p.textSecondary)
            for (r in adbResults) KitStoreCaption((if (r.ok) "✓ " else "✗ ") + r.line.command.take(90) + (if (r.output.isNotBlank()) "  → ${r.output.trim().take(80)}" else ""),
                mono = true, color = if (r.ok) p.ok else p.bad)
        }
    }
    val table: @Composable () -> Unit = {
        // ── the groups ──
        if (shown.isEmpty() && cleared.isEmpty()) KitStoreCaption("nothing matches these filters", Modifier.padding(horizontal = KitStoreRow.padH), color = p.textSecondary)
        for (g in shown) {
            val key = g.key.lowercase()
            Column(Modifier.fillMaxWidth().testTag("perms:group:$key")) {
                KitStoreRowLine(Modifier.testTag("perms:section:$key")) {
                    KitStoreCaption("${g.title.uppercase()} — ${(full[g.key] ?: g).count}" + (if (g.missing > 0) " · ${g.missing} missing" else "") + " · ${g.rows.size} rows",
                        Modifier.weight(1f), color = p.accent)
                    val ask = PermsPageQuery.dialogTargets(g.rows)
                    if (ask.isNotEmpty()) KitStoreQuick("Ask group (${ask.size})", KitStoreRow.BLUE_FILL, Modifier.testTag("perms:ask-group:$key")) { onAskMany(ask) }
                }
                GroupProgress(full[g.key] ?: g, key)
                for (r in g.rows) PermRowLine(r, highlight?.ids?.contains(r.entry.id) == true, ctx, appsOf(r.entry.id), onGrant, onShell,
                    select = if (selecting) (r.entry.id in selected) else null, onSelect = { id -> selected = if (id in selected) selected - id else selected + id })
            }
        }
        // #1550 (pre-#1519) a group Missing only emptied stays, with its full count and "nothing missing".
        for (g in cleared) {
            val key = g.key.lowercase()
            Column(Modifier.fillMaxWidth().testTag("perms:group:$key")) {
                KitStoreRowLine(Modifier.testTag("perms:section:$key")) {
                    KitStoreCaption("${g.title.uppercase()} — ${g.count}", Modifier.weight(1f), color = p.accent)
                }
                GroupProgress(g, key)
                KitStoreCaption("nothing missing", Modifier.padding(horizontal = KitStoreRow.padH).testTag("perms:nothing-missing:$key"), color = p.textSecondary)
            }
        }
    }
    // #1550 width from the configuration, NOT BoxWithConstraints: a SubcomposeLayout breaks performScrollTo/performClick
    // hit-testing in Compose tests (and adds a measure-time subcomposition the page does not need).
    val twoPane = wide ?: (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= TWO_PANE_MIN_DP)
    Column(Modifier.fillMaxWidth().testTag("perms:center")) {
        if (twoPane) Row(Modifier.fillMaxWidth().testTag("perms:two-pane")) {
            Column(Modifier.weight(0.36f).testTag("perms:pane:controls")) { controls() }
            Box(Modifier.width(KitStoreRow.step(1)).height(KitStoreRow.step(1)).background(p.hairline))
            Column(Modifier.weight(0.64f).testTag("perms:pane:table")) { table() }
        } else Column(Modifier.fillMaxWidth()) { controls(); table() }
    }
}

/** #1550 the width at or above which a page splits into two panes (840 dp: tablet / landscape). */
private const val TWO_PANE_MIN_DP = 840

/** A labelled, horizontally scrolling row of chips. */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun PermsChipRow(label: String, content: @Composable () -> Unit) {
    val p = LocalKitPalette.current
    // #1550 the chips WRAP (FlowRow) instead of scrolling off the right: every filter stays on screen and reachable
    // (a horizontalScroll hid later chips and broke performScrollTo/performClick — it only scrolls its own axis).
    Row(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = 1.dp)) {
        Text(label, fontSize = 10.sp, color = p.textSecondary, modifier = Modifier.width(62.dp).padding(top = 2.dp))
        androidx.compose.foundation.layout.FlowRow(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) { content() }
    }
}

@Composable
private fun PermsFilterPill(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    Text(label, fontSize = 10.5.sp, maxLines = 1, color = if (selected) Color.White else p.textPrimary,
        modifier = Modifier.padding(end = 3.dp).background(if (selected) p.accent else p.textPrimary.copy(alpha = 0.08f))
            .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp).testTag(tag))
}

/** The plain-text report of the rows on screen (Copy report). */
internal fun permsReport(rows: List<PermsPage.Row>, appsOf: (String) -> List<String>): String = rows.joinToString("\n") { r ->
    val e = r.entry
    "${PermsPageQuery.status(r).word.padEnd(18)} ${e.label} [${e.id}] ${e.permission.ifEmpty { e.check }} · ${PermsPageQuery.kindTitle(e.kind)} · " +
        "${PermsPageQuery.protection(e).word} · ${e.section.title} · ${r.category.title}" +
        (appsOf(e.id).takeIf { it.isNotEmpty() }?.let { " · apps: ${it.joinToString(",")}" } ?: "") +
        (r.status.reason.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
}

/**
 * One row: the pre-#1519 state icon and word with the status reason, the #1519 chip and reason, the look-for / how hints,
 * the actions that apply (Grant, Settings, Shell, Copy adb) and, on tap, the detail (what it is, protection, apps, state).
 */
@Composable
private fun PermRowLine(r: PermsPage.Row, highlighted: Boolean, ctx: Context, apps: List<String>, onGrant: (PermEntry) -> Unit, onShell: (PermEntry) -> Unit,
                        select: Boolean? = null, onSelect: (String) -> Unit = {}) {
    val p = LocalKitPalette.current
    val e = r.entry
    var open by rememberSaveable(e.id) { mutableStateOf(false) }
    val tone = when (r.chip) {
        RowChip.GRANTED -> p.ok
        RowChip.MISSING, RowChip.NEEDS_SHELL -> p.warn
        RowChip.DENIED_PERMANENTLY -> p.bad
        RowChip.INFO, RowChip.NOT_APPLICABLE -> p.textSecondary
    }
    val (icon, stateWord) = when (r.status.state) {
        PermState.GRANTED -> "✓" to "granted"; PermState.DENIED -> "◯" to "denied"; PermState.PERMANENTLY_DENIED -> "✗" to "permanently denied"
        PermState.NOT_APPLICABLE -> "–" to "not applicable"; PermState.UNKNOWN -> "?" to "check manually"
    }
    var mod = Modifier.fillMaxWidth()
    if (highlighted) mod = mod.border(1.dp, p.accent).background(p.accent.copy(alpha = 0.12f))
    Column(mod.testTag("perms:row:${e.id}")) {
        KitStoreRowLine(onClick = { if (select != null) onSelect(e.id) else open = !open }) {
            if (select != null) Text(if (select) "☑" else "☐", fontSize = KitStoreRow.title, color = if (select) p.accent else p.textSecondary,
                modifier = Modifier.padding(end = KitStoreRow.gap).testTag("perms:selectbox:${e.id}").androidxSemantics((if (select) "Selected " else "Select ") + e.label))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(icon, fontSize = 12.sp, color = tone, modifier = Modifier.padding(end = 4.dp).testTag("perms:icon:${e.id}"))
                    KitStoreName(e.label, Modifier.weight(1f, fill = false), color = p.textPrimary)
                    KitStoreCaption(r.chip.word, Modifier.padding(start = KitStoreRow.gap).background(tone.copy(alpha = 0.18f))
                        .padding(horizontal = KitStoreRow.tight).testTag("perms:chip:${e.id}"), color = tone)
                    KitStoreCaption(PermsPageQuery.kindTitle(e.kind), Modifier.padding(start = KitStoreRow.tight).testTag("perms:type:${e.id}"), color = p.textSecondary)
                    val prot = PermsPageQuery.protection(e)
                    KitStoreCaption(prot.word, Modifier.padding(start = KitStoreRow.tight).border(KitStoreRow.step(1), PermsKpi.protectionInk(prot, p))
                        .padding(horizontal = KitStoreRow.tight).testTag("perms:protection:${e.id}"), color = PermsKpi.protectionInk(prot, p))
                }
                // #1550 the permission id (or the check it is read by), monospace: the table's id column.
                KitStoreCaption(e.permission.ifEmpty { e.check }.ifEmpty { e.id }, Modifier.fillMaxWidth().testTag("perms:id:${e.id}"), color = p.textSecondary, mono = true)
                KitStoreCaption("$stateWord" + (if (r.status.reason.isNotBlank()) " (${r.status.reason})" else "") + " · ${r.reason}",
                    Modifier.fillMaxWidth().testTag("perms:state:${e.id}"), color = p.textSecondary)
                if (r.missing || r.status.state == PermState.UNKNOWN) {
                    val hint = listOf(e.lookFor.takeIf { it.isNotBlank() }?.let { "Look for: $it" }, e.instruction.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · ")
                    val text = if (r.status.state == PermState.PERMANENTLY_DENIED && hint.isBlank()) "Open › App info > Permissions > ${e.label} > Allow" else hint
                    if (text.isNotBlank()) KitStoreCaption(text, Modifier.fillMaxWidth().testTag("perms:hint:${e.id}"), color = p.textSecondary)
                }
            }
            for (a in r.actions) KitStoreQuick(a.word, if (a == RowAction.SHELL) KitStoreRow.AMBER_FILL else KitStoreRow.BLUE_FILL,
                Modifier.padding(start = KitStoreRow.tight).testTag("perms:${a.name.lowercase()}:${e.id}")) {
                when (a) {
                    RowAction.GRANT -> onGrant(e)
                    RowAction.SHELL -> onShell(e)
                    RowAction.SETTINGS -> {
                        if (!PermsSettings.open(ctx, e)) Toast.makeText(ctx, "No settings page for ${e.label} on this device", Toast.LENGTH_LONG).show()
                        else if (e.lookFor.isNotBlank()) Toast.makeText(ctx, "Look for: ${e.lookFor}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            if (e.adb.isNotEmpty() && r.status.state != PermState.NOT_APPLICABLE) KitStoreQuick("Copy adb", KitStoreRow.BLUE_FILL,
                Modifier.padding(start = KitStoreRow.tight).testTag("perms:copy:${e.id}")) {
                copy(ctx, AdbScript.lines(listOf(e), Perms.vars(ctx)).joinToString("\n") { it.forComputer() })
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        if (open) {
            val live = remember(e.permission) { FleetPerms.liveProtection(ctx, e.permission) }
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = KitStoreRow.padH, bottom = KitStoreRow.tight).testTag("perms:detail:${e.id}")) {
                for ((k, v) in PermsPageQuery.detail(r, apps, live)) Row {
                    Text(k, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = p.textSecondary, modifier = Modifier.width(96.dp))
                    Text(v, fontSize = 10.sp, color = p.textPrimary, fontFamily = if (k == "Permission" || k == "Shell") FontFamily.Monospace else null)
                }
            }
        }
    }
}

/** #1550 a group's granted / applicable as a thin bar under its header. */
@Composable
private fun GroupProgress(g: com.diegonmarcos.superapp.perms.QueryGroup, key: String) {
    val p = LocalKitPalette.current
    val n = g.counted.size; val f = if (n == 0) 1f else g.granted.toFloat() / n
    Box(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH).height(KitStoreRow.step(3)).background(p.hairline).testTag("perms:progress:$key")) {
        Box(Modifier.fillMaxWidth(f).height(KitStoreRow.step(3)).background(if (f >= 1f) p.ok else p.warn))
    }
}

/** #1550 the KPI tiles and the one status colour system of the page (state tokens of the kit palette). */
internal object PermsKpi {
    val TILES = listOf(StatusFilter.GRANTED, StatusFilter.MISSING, StatusFilter.DENIED_PERMANENTLY, StatusFilter.NEEDS_SHELL, StatusFilter.UNKNOWN, StatusFilter.NOT_APPLICABLE)
    fun label(k: StatusFilter): String = when (k) {
        StatusFilter.DENIED_PERMANENTLY -> "Denied"; StatusFilter.NOT_APPLICABLE -> "N/A"; StatusFilter.UNKNOWN -> "Manual"
        else -> k.word
    }
    fun ink(k: StatusFilter, p: com.diegonmarcos.superapp.uikit.KitPalette): Color = when (k) {
        StatusFilter.GRANTED -> p.ok
        StatusFilter.MISSING, StatusFilter.NEEDS_SHELL -> p.warn
        StatusFilter.DENIED_PERMANENTLY -> p.bad
        else -> p.textSecondary
    }
    fun protectionInk(x: Protection, p: com.diegonmarcos.superapp.uikit.KitPalette): Color = when (x) {
        Protection.DANGEROUS -> p.warn
        Protection.SIGNATURE_DEV -> p.bad
        else -> p.accent
    }
}

/** #1550 a spoken description for a tile / checkbox (accessibility). */
private fun Modifier.androidxSemantics(desc: String): Modifier = semantics { contentDescription = desc }

private fun copy(ctx: Context, v: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("perms", v))
}
