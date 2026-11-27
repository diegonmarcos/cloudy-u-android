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
import androidx.compose.ui.text.font.FontWeight
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
}

/** One engine card's inputs: the engine, what the freshness route said, what its body gave. */
data class EngineItem(val engine: EnginesModel.Engine, val fresh: EnginesModel.Fresh?, val body: EnginesModel.Body)

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
            Text(e.dir + if (e.rust) "  [rust]" else "  [shell]", color = p.textPrimary, fontSize = KitStoreRow.body, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).clickable { open = !open })
            XChip(EngineTags.run(e.id), EnginesModel.runLabel(e), false) { if (EnginesModel.runBody(e.kind) != null) onRun(e) }
        }
        XLine(e.blurb)
        if (e.note.isNotBlank()) XLine(e.note, color = KitStoreRow.UPDATE)
        Text(EnginesModel.verdictText(i.body), color = when (got?.verdict) { "PASS" -> KitStoreRow.UP; null -> p.textSecondary; else -> KitStoreRow.UPDATE }, fontSize = KitStoreRow.caption,
            modifier = Modifier.padding(horizontal = KitStoreRow.padH, vertical = 1.dp).testTag(EngineTags.verdict(e.id)))
        if (got != null) {
            XLine("last run ${got.generated.ifBlank { "n/a" }} · ${EnginesModel.durationText(got.durationMs)} · pass ${got.pass} · warn ${got.warn} · fail ${got.fail}", mono = true)
        } else {
            XLine("last run: ${if (e.family == null) "none published as JSON" else EnginesModel.freshText(i.fresh)}", mono = true)
        }
        if (got != null) XLine(EnginesModel.freshText(i.fresh), mono = true)
        XLine(if (open) "hide phases and history" else "tap the name: phases, checks, history", color = p.accent)
        if (open) {
            got?.extra?.forEach { (k, v) -> XLine("$k: $v", mono = true) }
            got?.phases?.forEach { PhaseRows(e.id, it) }
            XLine("HISTORY (runs of the Cloud Health Reports workflow; the API lists the workflow, not one report)", color = p.accent)
            Column(Modifier.testTag(EngineTags.history(e.id))) {
                if (runs == null) XLine("history not read: $runsWhy")
                else if (runs.isEmpty()) XLine("no runs listed")
                else runs.take(10).forEach { XLine("${it.createdAt} · ${it.runner} · ${it.status}/${it.conclusion.ifBlank { "-" }} · ${EnginesModel.runSeconds(it)} · ${it.event} · #${it.id}", mono = true) }
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
