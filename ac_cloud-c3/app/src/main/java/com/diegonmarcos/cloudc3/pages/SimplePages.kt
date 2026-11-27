package com.diegonmarcos.cloudc3.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudc3.BuildConfig
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.R
import com.diegonmarcos.cloudc3.cloud.C3HealthFragment
import com.diegonmarcos.cloudc3.cloud.ContainerSheet
import com.diegonmarcos.cloudc3.cloud.FleetDown
import com.diegonmarcos.cloudc3.cloud.Services
import com.diegonmarcos.cloudc3.cloud.StatusLight
import com.diegonmarcos.superapp.ops.dagu.DaguPrefs
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * #648 the two tabs that are a single page each: Home and Configs ▸ About.
 *
 * Both report only what they can MEASURE. Home counts the declared estate and the declared
 * pages; About reads what gradle baked. Neither claims a status it has not established —
 * on an ops surface a confident number nobody verified is the defect, not the feature.
 *
 * 2026-10-16 owner decision: all apps must be Compose — both pages are [KitComposeFragment]s.
 */

/**
 * HOME — the centre tab, the position every fleet five-tab shell gives it.
 *
 * FIRST, every declared container that is DOWN across the fleet ([FleetDown]), grouped by
 * VM; a row opens the same [ContainerSheet] the Topology dashboards open, so start / stop /
 * restart / logs are one implementation. The fleet runs with compose restart "no", so a box
 * that exits stays exited until someone looks — this card is where they look. It has three
 * honest faces: the API did not answer (said so, with its reason), some VMs could not be
 * listed (named, with their declared count, never folded into "up"), or the list — and an
 * EMPTY list only ever means every declared container was seen running.
 *
 * Below it, a summary of the other tabs' DECLARATIONS plus the real size of the estate, so
 * it cannot drift from what the app offers: declaring a page adds it here with no edit.
 */
class HomeFragment : KitComposeFragment() {

    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        // Re-measured on open, on Refresh, and whenever a container sheet closes.
        var measureKey by remember { mutableIntStateOf(0) }
        var report by remember { mutableStateOf<FleetDown.Report?>(null) }
        var sheet by remember { mutableStateOf<ContainerSheet.Target?>(null) }
        LaunchedEffect(measureKey) {
            report = null
            val bearer = runCatching { DaguPrefs(ctx).bearerToken }.getOrDefault("")
            report = withContext(Dispatchers.IO) { FleetDown.load(bearer) }
        }

        // #1969 the per-page live rows: each source is read once here, on open and on Refresh; a failure keeps its reason
        val measuredNames = remember { FleetDown.declared().map { it.name }.toSet() }
        var live by remember { mutableStateOf(HomeLive.Inputs(measured = measuredNames)) }
        LaunchedEffect(measureKey) {
            val c = ctx.applicationContext
            live = withContext(Dispatchers.IO) { HomeLiveLoader.load(c, Declarations.topology.release).copy(measured = measuredNames) }
        }
        val pub = Services.publicServices().size
        val priv = Services.privateServices().size
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            DownCard(report, onRefresh = { measureKey++ }) { name -> sheet = ContainerSheet.Target(name, name, "") }

            Card(
                label("containers"),
                listOf(
                    stringResource(R.string.c3_health_section_public, pub),
                    stringResource(R.string.c3_health_section_private, priv),
                ),
            )
            Card(label("orch"), Declarations.orchPages.map { it.label })
            Card(label("ops"), Declarations.externalApps.map { "${it.display} · ${it.packageName}" })
            HomePagesCard(
                HomeLive.rows(Declarations.nav, Declarations.home, live.copy(down = report?.let { HomeLive.Live.Ok(it) } ?: live.down), Declarations.containerProjects, ContainersSource.current(), Declarations.fleet),
            ) { t -> (activity as? com.diegonmarcos.cloudc3.cloud.C3StackFragment.TargetListener)?.onTargetClicked(if (t.startsWith("page:")) t else "page:c3/$t") }
        }
        sheet?.let { target ->
            ContainerSheet.Sheet(
                target = target,
                onOpenUrl = { url -> (activity as? C3HealthFragment.UrlClickListener)?.onUrlClicked(url) },
                onDismiss = { sheet = null; measureKey++ },
            )
        }
    }

    @Composable
    private fun DownCard(report: FleetDown.Report?, onRefresh: () -> Unit, onOpen: (String) -> Unit) {
        val ctx = LocalContext.current
        C3Card {
            Title(stringResource(R.string.down_title))
            if (report == null) {
                val declared = remember { FleetDown.declared() }
                Line(stringResource(R.string.down_measuring, declared.size, declared.map { it.vm }.distinct().size))
                return@C3Card
            }
            when (report) {
                is FleetDown.Report.Unreachable -> Line(
                    stringResource(R.string.down_unreachable, report.kind.name, report.detail), StatusLight.State.UNKNOWN)
                FleetDown.Report.NothingDeclared -> Line(stringResource(R.string.down_nothing_declared), StatusLight.State.UNKNOWN)
                is FleetDown.Report.Measured -> {
                    val downCount = report.down.values.sumOf { it.size }
                    if (downCount == 0 && report.blind.isEmpty()) {
                        Line(stringResource(R.string.down_all_up, report.declared, report.declaredPerVm.size), StatusLight.State.ON)
                    } else {
                        Line(stringResource(R.string.down_summary, downCount, report.declared))
                    }
                    for (vm in (report.down.keys + report.blind.keys).sorted()) {
                        report.blind[vm]?.let {
                            Line(stringResource(R.string.down_vm_blind, vm, report.declaredPerVm[vm] ?: 0, it),
                                StatusLight.State.UNKNOWN)
                        }
                        val rows = report.down[vm] ?: continue
                        Line(stringResource(R.string.down_vm_heading, vm, rows.size))
                        for (r in rows) {
                            Line(stringResource(R.string.down_row, StatusLight.glyph(StatusLight.State.OFF), r.name,
                                                ContainerSheet.stateText(ctx, r.state)), StatusLight.State.OFF) { onOpen(r.name) }
                        }
                    }
                }
            }
            Line(stringResource(R.string.down_refresh, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())),
                onTap = onRefresh)
        }
    }

    /** A card title names a TAB, so it is read from the tab declaration, never spelled. */
    private fun label(tabId: String): String =
        Declarations.tabs.firstOrNull { it.id == tabId }?.label ?: tabId
}

/** CONFIGS ▸ ABOUT — everything here was baked by gradle, so it reports and never guesses. */
class AboutFragment : KitComposeFragment() {

    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Card(
                stringResource(R.string.about_title),
                listOf(
                    "${stringResource(R.string.about_version)}: ${BuildConfig.VERSION_NAME}",
                    "${stringResource(R.string.about_commit)}: ${BuildConfig.GIT_SHORT_SHA}",
                    "${stringResource(R.string.about_build)}: ${BuildConfig.BUILD_TIMESTAMP}",
                ),
            )
        }
    }
}

/** The card title: the accent-on-card subhead both pages use. */
@Composable
private fun ColumnScope.Title(text: String) {
    val p = LocalKitPalette.current
    Text(text, color = p.accent, style = MaterialTheme.typography.titleMedium)
}

/** One line of a card; [light] colours it with the StatusLight, [onTap] makes it a button (else it is selectable). */
@Composable
private fun ColumnScope.Line(text: String, light: StatusLight.State? = null, onTap: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val colour = light?.let { Color(StatusLight.colour(ctx, it)) } ?: p.textSecondary
    val line = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    if (onTap != null) {
        Text(text, color = colour, style = MaterialTheme.typography.bodySmall,
            modifier = line.testTag("c3:line:$text").clickable { onTap() })
    } else {
        SelectionContainer { Text(text, color = colour, style = MaterialTheme.typography.bodySmall, modifier = line) }
    }
}

/**
 * The one card shape both pages use. Declared here once rather than per page, for the same
 * reason the colours live in colors.xml: a second copy is a second thing to keep in step.
 */
@Composable
private fun Card(title: String, lines: List<String>) {
    val p = LocalKitPalette.current
    C3Card {
        Title(title)
        for (line in lines) {
            Text(line, color = p.textSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}
