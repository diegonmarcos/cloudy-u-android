package com.diegonmarcos.superapp.network.mesh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.superapp.R

/**
 * #877 Log: the journal of tunnel state changes, handshakes, stale peers and the actions taken here,
 * stamped, newest first. It fills while the page is open and when an action runs - the engine has no
 * event stream to subscribe to, and the page says so rather than implying a history it does not hold.
 */
@Composable
fun MeshLogPage(store: MeshStore, modifier: Modifier = Modifier, embedded: Boolean = false) {
    val page = store.decl.page("log")
    @Suppress("UNUSED_VARIABLE") val rev = store.journalRev
    val all = store.journal.list()
    val lines = all.filter { MeshJournal.shows(it, store.logLevel) }.asReversed()
    Column(
        modifier.pageBody(embedded).testTag(MeshTags.page("log"))
            .padding(MeshDensity.dp(MeshDensity.S8)),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S6))) {
            for (id in listOf("log_copy", "log_clear", "log_export")) page?.control(id)?.let { c ->
                MButton(declLabel("ctl", c.id, c.label), { store.run(c.engine) }, Modifier.testTag(MeshTags.control(c.id)), enabled = all.isNotEmpty())
            }
        }
        page?.control("log_level")?.let { c ->
            ChoiceLine(declLabel("ctl", c.id, c.label), c.choices.map { it to declLabel("opt", it, it) }, store.logLevel,
                { store.run(c.engine, it) }, Modifier.testTag(MeshTags.control(c.id)))
        }
        Reason(stringResource(R.string.mesh_log_text), Modifier.padding(top = MeshDensity.dp(MeshDensity.S4)))
        if (lines.isEmpty()) MText(stringResource(R.string.mesh_log_empty), size = MeshDensity.T_META, muted = true, modifier = Modifier.testTag("mesh:log:empty"))
        lines.forEach { e ->
            Row(Modifier.padding(vertical = MeshDensity.dp(MeshDensity.S1)), horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S6))) {
                MText(MeshFormat.clock(e.atMs), size = MeshDensity.T_CAPTION, mono = true, muted = true)
                MText(MeshJournal.levelOf(e.kind).uppercase(), size = MeshDensity.T_CAPTION, mono = true, muted = true)
                MText(e.kind, size = MeshDensity.T_CAPTION, mono = true, bold = true)
                MText(e.text, Modifier.weight(1f), size = MeshDensity.T_CAPTION, mono = true)
            }
        }
    }
}
