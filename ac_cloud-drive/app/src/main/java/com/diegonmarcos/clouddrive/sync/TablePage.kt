package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitrepos.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.IslandAction
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.StatusLight

/**
 * #1195 / #1173 THE ONE DESIGN of the table pages: Git, Fsync and Mounts all draw the same three things, from
 * this file. (1) an actions bar on top, its Settings gear at the end; (2) the Settings sheet of auto-behaviours
 * ([AutoChecks], ALL OFF until the owner turns one on); (3) rows below that open an options / actions / metadata
 * panel on a tap and close it on the next ([TapPanel.toggle]).
 */
object TapPanel {
    /** Which row's panel is open after a tap on [tapped]: the same row closes it ("" = none). */
    fun toggle(open: String, tapped: String): String = if (open == tapped) "" else tapped
}

/** The page's auto-behaviour settings, live from [prefs] (all off when there is no store or nothing was switched on). */
@Composable
fun rememberAutoSettings(page: AutoChecks.Page, prefs: DrivePrefs?): AutoChecks.Settings {
    val snap = prefs?.snapshot?.collectAsState()
    return AutoChecks.Settings(page, snap?.value?.auto.orEmpty())
}

/**
 * The page-open and periodic refresh of a Fsync / Mounts page: [loadStored] (cheap stored lists) always, then [deep]
 * (probes, binaries, network) only when the Settings ask for it, again every interval while the page stays open.
 */
@Composable
fun AutoRefreshEffects(page: AutoChecks.Page, prefs: DrivePrefs?, loadStored: () -> Unit, deep: () -> Unit) {
    val settings = rememberAutoSettings(page, prefs)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        loadStored()
        if (AutoChecks.Work.REFRESH_STATE in AutoChecks.plan(settings, AutoChecks.Trigger.OPEN)) deep()
    }
    androidx.compose.runtime.LaunchedEffect(settings.intervalMinutes) {
        while (settings.intervalMinutes > 0) {
            kotlinx.coroutines.delay(settings.intervalMinutes * 60_000L)
            if (AutoChecks.Work.REFRESH_STATE in AutoChecks.plan(settings, AutoChecks.Trigger.TICK)) deep()
        }
    }
}

/** One button of an actions bar. */
data class BarAction(val label: String, val onClick: () -> Unit, val enabled: Boolean = true, val icon: ImageVector? = null)

@Composable
internal fun autoLabel(flag: AutoChecks.Flag): String = stringResource(when (flag) {
    AutoChecks.Flag.STATUS_ON_OPEN -> R.string.auto_status_on_open
    AutoChecks.Flag.DIRTY -> R.string.auto_check_dirty
    AutoChecks.Flag.BEHIND_AHEAD -> R.string.auto_check_behind_ahead
    AutoChecks.Flag.FETCH -> R.string.auto_fetch
    AutoChecks.Flag.PULL -> R.string.auto_pull
    AutoChecks.Flag.SIZE -> R.string.auto_size
    AutoChecks.Flag.STATE -> R.string.auto_state
})

/** The actions bar: the page's buttons, then the Settings gear. [prefs] null = a host with no settings store (the gear is then hidden). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PageActionsBar(page: AutoChecks.Page, prefs: DrivePrefs?, actions: List<BarAction>, modifier: Modifier = Modifier, tag: String = DriveTags.SYNC_ACTIONS_BAR) {
    var sheet by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
            actions.forEach { a -> Pill(a.label, a.onClick, icon = a.icon, enabled = a.enabled) }
        }
        if (prefs != null) Row(Modifier.testTag(DriveTags.SYNC_AUTO_SETTINGS)) {
            IslandAction(Icons.Filled.Settings, stringResource(R.string.auto_settings)) { sheet = true }
        }
    }
    if (sheet && prefs != null) AutoSettingsSheet(page, prefs) { sheet = false }
}

/** The Settings sheet: one switch per auto-behaviour of [page] (all off by default) and the periodic-check interval. Persisted in DrivePrefs, which Auto Backup carries. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoSettingsSheet(page: AutoChecks.Page, prefs: DrivePrefs, onDismiss: () -> Unit) {
    val snap by prefs.snapshot.collectAsState()
    val settings = AutoChecks.Settings(page, snap.auto)
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(DriveTags.SYNC_AUTO_SHEET)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = DriveMetrics.padWide).padding(bottom = DriveMetrics.padWide)) {
            Text(stringResource(R.string.auto_settings), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.auto_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AutoChecks.flags(page).forEach { f -> SwitchRow(autoLabel(f), settings.on(f), { prefs.setAutoCheck(page, f, it) }) }
            Text(stringResource(R.string.auto_interval), Modifier.padding(top = DriveMetrics.pad), style = MaterialTheme.typography.labelLarge)
            ChoiceRow(
                AutoChecks.INTERVALS.map { it.toString() to (if (it == 0) stringResource(R.string.auto_interval_off) else stringResource(R.string.sync_settings_minutes, it.toLong())) },
                settings.intervalMinutes.toString(), { prefs.setAutoInterval(page, it.toInt()) },
            )
        }
    }
}

/**
 * A row that opens on a tap: the collapsed form is the card's header (name, badge, light, one-line summary), the
 * expanded form adds the options / actions / metadata [body]; a second tap on the header closes it.
 */
@Composable
fun TapCard(
    header: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    tag: String,
    badge: String? = null,
    light: StatusLight.State? = null,
    summary: String? = null,
    summaryMonospace: Boolean = false,
    body: @Composable ColumnScope.() -> Unit,
) {
    DriveCard(header, badge = badge, light = light, summary = summary, summaryMonospace = summaryMonospace, onClick = onToggle, tag = tag) {
        TapChevron(expanded)
        if (expanded) body()
    }
}

/** The open / closed chevron of a tap row. */
@Composable
fun TapChevron(expanded: Boolean) {
    Icon(
        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
        contentDescription = stringResource(if (expanded) R.string.git_collapse else R.string.git_expand),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
