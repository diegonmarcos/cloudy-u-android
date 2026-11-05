package com.diegonmarcos.superapp.configs

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    override fun up() = runCatching { com.diegonmarcos.superapp.shellbroker.ShellBroker.status(ctx).armed }.getOrDefault(false)
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
internal fun PermsSummaryBar(rows: List<PermRowState>, ctx: Context, onAskAll: () -> Unit) {
    val p = LocalKitPalette.current
    val s = PermsPage.summary(pageRows(ctx, rows))
    Column(Modifier.fillMaxWidth().background(p.surface).testTag("perms:summary")) {
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
 * The catalogue-driven body of the Permissions page: the "Requested by" highlight, the adb block (copy / run through the
 * shell broker) and the rows grouped by category (PermsPage), missing first, one dense KitStoreRow each.
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
) {
    val p = LocalKitPalette.current
    val page = remember(rows) { pageRows(ctx, rows) }
    Column(Modifier.fillMaxWidth().testTag("perms:center")) {
        if (highlight != null) {
            val n = rows.count { it.entry.id in highlight.ids }
            KitStoreRowLine(Modifier.background(p.accent.copy(alpha = 0.18f)).testTag("perms:highlight")) {
                KitStoreCaption("Requested by ${highlight.feature}: $n highlighted", Modifier.weight(1f), color = p.textPrimary)
                KitStoreQuick("Show all", KitStoreRow.BLUE_FILL, Modifier.testTag("perms:highlight-clear"), onClearHighlight)
            }
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
        }
        if (shellNote != null) KitStoreCaption(shellNote, Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH).testTag("perms:shell-note"), color = p.textPrimary)
        if (adbResults.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH).testTag("perms:adb-results")) {
            KitStoreCaption("ADB: ${adbResults.count { it.ok }} ok · ${adbResults.count { !it.ok }} failed", color = p.textSecondary)
            for (r in adbResults) KitStoreCaption((if (r.ok) "✓ " else "✗ ") + r.line.command.take(90) + (if (r.output.isNotBlank()) "  → ${r.output.trim().take(80)}" else ""),
                mono = true, color = if (r.ok) p.ok else p.bad)
        }
        for (g in PermsPage.groups(page)) {
            KitStoreCaption("${g.category.title.uppercase()} · ${g.count}" + (if (g.missing > 0) " · ${g.missing} missing" else ""),
                Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.gap).testTag("perms:group:${g.category.name.lowercase()}"),
                color = p.accent)
            for (r in g.rows) PermRowLine(r, highlight?.ids?.contains(r.entry.id) == true, ctx, onGrant, onShell)
        }
    }
}

/** One row: name and chip, the reason under them, and the actions (Grant / Settings / Shell) that apply. */
@Composable
private fun PermRowLine(r: PermsPage.Row, highlighted: Boolean, ctx: Context, onGrant: (PermEntry) -> Unit, onShell: (PermEntry) -> Unit) {
    val p = LocalKitPalette.current
    val e = r.entry
    val tone = when (r.chip) {
        RowChip.GRANTED -> p.ok
        RowChip.MISSING, RowChip.NEEDS_SHELL -> p.warn
        RowChip.DENIED_PERMANENTLY -> p.bad
        RowChip.INFO, RowChip.NOT_APPLICABLE -> p.textSecondary
    }
    KitStoreRowLine(Modifier.let { if (highlighted) it.background(p.accent.copy(alpha = 0.12f)) else it }.testTag("perms:row:${e.id}")) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KitStoreName(e.label, Modifier.weight(1f, fill = false), color = p.textPrimary)
                KitStoreCaption(r.chip.word, Modifier.padding(start = KitStoreRow.gap).background(tone.copy(alpha = 0.18f))
                    .padding(horizontal = KitStoreRow.tight).testTag("perms:chip:${e.id}"), color = tone)
            }
            KitStoreCaption(r.reason, Modifier.fillMaxWidth(), color = p.textSecondary)
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
    }
}

private fun copy(ctx: Context, v: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("perms", v))
}
