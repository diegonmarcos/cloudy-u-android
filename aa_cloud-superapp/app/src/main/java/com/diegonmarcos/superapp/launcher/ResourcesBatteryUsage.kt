package com.diegonmarcos.superapp.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.adbdebug.AdbShellLink
import com.diegonmarcos.superapp.battery.BatteryCycles
import com.diegonmarcos.superapp.battery.BatteryRepository
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitSegmented
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resources › Battery: per-app battery use per charge cycle or per calendar month (libs:battery BatteryCycles,
 * recorded at every plug edge and on the 15-min tick). Cycle | Month picks the window, the list picks one cycle or
 * month, and its apps follow heaviest first with their share bar. Per-app mAh needs the app's privileged adb channel;
 * without it the row opens the ADB Shell page, the one place that pairs.
 */
@Composable
internal fun BatteryUsageBody() {
    val ctx = LocalContext.current
    var window by rememberSaveable { mutableStateOf(CYCLE) }
    var picked by rememberSaveable { mutableIntStateOf(0) }
    val data by produceState<Usage?>(null) {
        value = withContext(Dispatchers.IO) {
            val cycles = BatteryRepository.cycles(ctx)
            val uids = cycles.flatMap { it.apps.keys }.toSet()
            Usage(cycles, BatteryRepository.privilegedChannel(ctx), uids.associateWith { BatteryRepository.appLabel(ctx, it).second })
        }
    }
    Column(Modifier.fillMaxWidth().testTag(TAG)) {
        AggCaption("Battery use per app")
        KitSegmented(listOf(CYCLE to "Cycle", MONTH to "Month"), window, { window = it; picked = 0 }, tag = "$TAG:window")
        val d = data ?: run { AggCaption("Reading…"); return@Column }
        if (d.channel == null) KitListRow("Per-app mAh needs the ADB channel", secondary = "Pair it on the ADB Shell page",
            pill = "Not paired" to KitState.IDLE, tag = "$TAG:pair", onClick = { AdbShellLink.open(ctx) })
        val periods = if (window == CYCLE) d.cycles.take(MAX_PERIODS).map(::cyclePeriod)
            else BatteryCycles.months(d.cycles).take(MAX_PERIODS).map(::monthPeriod)
        if (periods.isEmpty()) { AggCaption("No cycle recorded yet: one starts at the next unplug or full charge."); return@Column }
        periods.forEachIndexed { i, p ->
            KitListRow(p.label, secondary = p.detail, leading = if (i == picked) "●" else "○", tag = "$TAG:period:$i",
                onClick = { picked = i })
        }
        val sel = periods.getOrElse(picked) { periods.first() }
        val rows = BatteryCycles.shares(sel.apps).take(MAX_APPS)
        AggCaption(sel.label + " · per app")
        if (rows.isEmpty()) AggCaption("No per-app reading in this ${if (window == CYCLE) "cycle" else "month"}.")
        for (s in rows) KitListRow(d.labels[s.uid] ?: "uid ${s.uid}",
            secondary = String.format(Locale.US, "%.1f mAh · %d%%", s.mAh, Math.round(s.share * 100)),
            tag = "$TAG:app:${s.uid}", trailing = { ShareBar(s.share) })
    }
}

private class Usage(val cycles: List<BatteryCycles.Cycle>, val channel: String?, val labels: Map<Int, String>)
private class Period(val label: String, val detail: String, val apps: Map<Int, Double>)

private fun cyclePeriod(c: BatteryCycles.Cycle): Period {
    val f = SimpleDateFormat("dd MMM HH:mm", Locale.US)
    val end = c.endTs?.let { f.format(Date(it)) } ?: "now"
    return Period("${f.format(Date(c.startTs))} → $end",
        String.format(Locale.US, "%d%% → %d%% · −%d%% · %.0f mAh", c.startPct, c.endPct, c.usedPct, c.totalMah), c.apps)
}

private fun monthPeriod(m: BatteryCycles.Month): Period {
    val label = runCatching { SimpleDateFormat("MMMM yyyy", Locale.US).format(SimpleDateFormat("yyyy-MM", Locale.US).parse(m.key)!!) }
        .getOrDefault(m.key)
    return Period(label, String.format(Locale.US, "%d cycles · −%d%% · %.0f mAh", m.cycles, m.usedPct, m.totalMah), m.apps)
}

/** The row's share of the period, as a filled bar. */
@Composable
private fun ShareBar(share: Double) {
    val p = LocalKitPalette.current
    Box(Modifier.width(64.dp).height(6.dp).background(p.hairline)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(share.toFloat().coerceIn(0f, 1f)).background(p.accent))
    }
}

private const val TAG = "resources:battery:usage"
private const val CYCLE = "cycle"
private const val MONTH = "month"
private const val MAX_PERIODS = 12
private const val MAX_APPS = 15
