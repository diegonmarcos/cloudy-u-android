package com.diegonmarcos.cloudc3.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette

object EngineTags {
    const val ROOT = "engines_root"
    fun card(id: String) = "eng_card_$id"
    fun run(id: String) = "eng_run_$id"
    fun verdict(id: String) = "eng_verdict_$id"
    fun phase(id: String, phase: String) = "eng_phase_${id}_$phase"
    fun check(id: String, phase: String, i: Int) = "eng_check_${id}_${phase}_$i"
    fun history(id: String) = "eng_history_$id"
    fun noToken(id: String) = "eng_notoken_$id"
}

/**
 * The "this device has no C3 token" reason as an action: a tap opens Configs > Profile > Config import (Cloud Account's import page). [onOpen] is the
 * seam the tests replace; the default opens it and toasts the reason when it could not.
 */
@Composable
internal fun NoTokenLine(text: String, tag: String, onOpen: (() -> Unit)? = null) {
    val p = LocalKitPalette.current
    val ctx = LocalContext.current
    Text(text, color = KitStoreRow.UPDATE, fontSize = KitStoreRow.caption, textDecoration = TextDecoration.Underline,
        modifier = Modifier.padding(horizontal = KitStoreRow.padH, vertical = 1.dp).clickable {
            if (onOpen != null) onOpen() else ConfigImport.open(ctx)?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show() }
        }.testTag(tag))
}

/** One engine card's inputs: the engine, what the freshness route said, what its body gave. */
data class EngineItem(
    val engine: EnginesModel.Engine, val fresh: EnginesModel.Fresh?, val body: EnginesModel.Body,
    /** The card's own history (the runs of its workflow); null = the Cloud Health Reports runs the API lists. */
    val runs: List<EnginesModel.Run>? = null,
    /** Called instead of opening Cloud Account when the no-token line is tapped (tests). */
    val onNoToken: (() -> Unit)? = null,
)

private const val MAX_ROWS = 300

/**
 * Every engine as a card (name, last run, verdict, duration, pass/warn/fail), tap for the drill-down (phases, then each check with its evidence
 * fields), the history of past runs and the Run button. A missing report prints its reason, never a number.
 */
@Composable
fun EnginesBody(items: List<EngineItem>, runs: List<EnginesModel.Run>?, runsWhy: String, onRun: (EnginesModel.Engine) -> Unit) {
    items.forEach { EngineCard(it, runs, runsWhy, onRun) }
}

@Composable
private fun EngineCard(i: EngineItem, runs: List<EnginesModel.Run>?, runsWhy: String, onRun: (EnginesModel.Engine) -> Unit) {
    val p = LocalKitPalette.current
    val e = i.engine
    var open by remember { mutableStateOf(false) }
    val got = (i.body as? EnginesModel.Body.Got)?.report
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(EngineTags.card(e.id))) {
        Row(Modifier.padding(horizontal = KitStoreRow.padH), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(e.dir + "  [${e.tag}]", color = p.textPrimary, fontSize = KitStoreRow.body, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).clickable { open = !open })
            XChip(EngineTags.run(e.id), EnginesModel.runLabel(e), false) { if (EnginesModel.canRun(e)) onRun(e) }
        }
        XLine(e.blurb)
        if (e.needsRewrite) XLine("[${e.tag}] ${EnginesModel.REWRITE}", color = KitStoreRow.UPDATE)
        if (e.note.isNotBlank()) XLine(e.note, color = KitStoreRow.UPDATE)
        EnginesModel.replacedLines(e.id).forEach { XLine(it, mono = true) }
        if (EnginesModel.isNoToken(i.body)) NoTokenLine(EnginesModel.verdictText(i.body), EngineTags.noToken(e.id), i.onNoToken)
        else Text(EnginesModel.verdictText(i.body), color = when (EnginesModel.verdictWord(i.body)) { "PASS" -> KitStoreRow.UP; null -> p.textSecondary; else -> KitStoreRow.UPDATE }, fontSize = KitStoreRow.caption,
            modifier = Modifier.padding(horizontal = KitStoreRow.padH, vertical = 1.dp).testTag(EngineTags.verdict(e.id)))
        val last = (i.body as? EnginesModel.Body.Last)?.run
        if (last != null) {
            XLine("last run ${last.createdAt} · ${EnginesModel.runSeconds(last)} · ${last.event} · #${last.id} · ${last.status}/${last.conclusion.ifBlank { "-" }}", mono = true)
        } else if (got != null) {
            XLine("last run ${got.generated.ifBlank { "n/a" }} · ${EnginesModel.durationText(got.durationMs)} · pass ${got.pass} · warn ${got.warn} · fail ${got.fail}", mono = true)
        } else {
            XLine("last run: ${if (e.family == null) (if (e.workflow != null) "not read (workflow ${e.workflow})" else "none published as JSON") else EnginesModel.freshText(i.fresh)}", mono = true)
        }
        if (got != null) XLine(EnginesModel.freshText(i.fresh), mono = true)
        XLine(if (open) "hide phases and history" else "tap the name: phases, checks, history", color = p.accent)
        if (open) {
            got?.extra?.forEach { (k, v) -> XLine("$k: $v", mono = true) }
            got?.phases?.forEach { PhaseRows(e.id, it) }
            val own = e.origin != "reports"
            val hist = if (own) i.runs else runs
            XLine(if (own) "HISTORY (${e.workflow?.let { "runs of $it" } ?: "no workflow runs this"})" else "HISTORY (runs of the Cloud Health Reports workflow; the API lists the workflow, not one report)", color = p.accent)
            Column(Modifier.testTag(EngineTags.history(e.id))) {
                if (hist == null) XLine("history not read: ${if (own) EnginesModel.verdictText(i.body) else runsWhy}")
                else if (hist.isEmpty()) XLine("no runs listed")
                else hist.take(10).forEach { XLine("${it.createdAt} · ${it.runner} · ${it.status}/${it.conclusion.ifBlank { "-" }} · ${EnginesModel.runSeconds(it)} · ${it.event} · #${it.id}", mono = true) }
            }
        }
    }
}

@Composable
private fun PhaseRows(engine: String, ph: EnginesModel.Phase) {
    val p = LocalKitPalette.current
    var open by remember { mutableStateOf(false) }
    Text("${ph.id}   pass ${ph.pass} · warn ${ph.warn} · fail ${ph.fail} · ${ph.checks.size}", color = p.accent, fontSize = KitStoreRow.caption,
        modifier = Modifier.padding(horizontal = KitStoreRow.padH, vertical = 2.dp).clickable { open = !open }.testTag(EngineTags.phase(engine, ph.id)))
    if (open) {
        ph.checks.take(MAX_ROWS).forEachIndexed { n, c ->
            Column(Modifier.testTag(EngineTags.check(engine, ph.id, n))) {
                XLine("${c.status.uppercase()} ${c.name}", color = when (c.status) { "pass" -> KitStoreRow.UP; "info" -> p.textSecondary; else -> KitStoreRow.UPDATE })
                c.fields.forEach { (k, v) -> XLine("    $k: $v", mono = true) }
            }
        }
        if (ph.checks.size > MAX_ROWS) XLine("+${ph.checks.size - MAX_ROWS} more checks in the report")
    }
}
