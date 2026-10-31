package com.diegonmarcos.superapp.batterystats

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.battery.BatteryHistory
import com.diegonmarcos.superapp.battery.BatteryPrivileged
import com.diegonmarcos.superapp.battery.BatteryReport
import com.diegonmarcos.superapp.battery.BatteryRepository
import com.diegonmarcos.superapp.battery.BatteryRows
import com.diegonmarcos.superapp.battery.BatterySample
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Configs › About › Battery (page:config/battery): the full battery stats page,
 * and THE view of the battery Source of Truth. Everything here is one
 * [BatteryReport] (BatteryRepository.report — the same object the Battery badge
 * and the home-screen popup draw) plus the history it was computed from:
 *
 *   Now · Since last charge          the popup's two sections, the same rows
 *   History                          level over 24 h / 7 d, on-power spans shaded
 *   Capacity                         estimated (charge counter ÷ level) vs design,
 *                                    cycles, technology, health
 *   Temperature & voltage            min / avg / max over the graph's window
 *   Discharge cycles                 when, start→end %, time, avg %/h · W, max temp
 *   Charge sessions                  when, from→to %, source, time, avg W in
 *   Top apps since charge            per-app mAh — privileged
 *
 * The privileged parts (gauge sysfs, batterystats) come through the fleet's
 * privileged shell channel when it is up, as the network popup's hotspot and
 * cellular sections do; without it they print "—".
 */
class BatteryStatsFragment : KitComposeFragment() {

    override fun palette() = LauncherPalette.kit(requireContext())

    private class Privileged(val facts: BatteryPrivileged.Facts?, val channel: String?, val apps: List<Pair<String, Double>>)

    @Composable
    override fun Content() {
        val ctx = LocalContext.current.applicationContext
        var report by remember { mutableStateOf<BatteryReport?>(null) }
        var samples by remember { mutableStateOf<List<BatterySample>>(emptyList()) }
        var priv by remember { mutableStateOf<Privileged?>(null) }
        var week by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            priv = withContext(Dispatchers.IO) { readPrivileged(ctx) }
        }
        LaunchedEffect(Unit) {
            while (true) {
                withContext(Dispatchers.IO) {
                    report = runCatching { BatteryRepository.report(ctx) }.getOrNull()
                    samples = BatteryRepository.samples(ctx)
                }
                delay(REFRESH_MS)
            }
        }
        val p = LocalKitPalette.current
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp).testTag("battery-stats")) {
            Text("Battery", color = p.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("Configs › About › Battery · the battery Source of Truth", color = p.textSecondary, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
            val r = report
            if (r == null) Line("Reading the battery…") else Body(r, samples, priv, week) { week = it }
        }
    }

    @Composable
    private fun Body(r: BatteryReport, samples: List<BatterySample>, priv: Privileged?, week: Boolean, setWeek: (Boolean) -> Unit) {
        Column {
            val now = r.reading.nowMs
            val from = now - if (week) WEEK_MS else DAY_MS

            Section(BatteryRows.now(r))
            Section(BatteryRows.since(r))

            Divider()
            Header("History")
            Row {
                Toggle("24 h", !week) { setWeek(false) }
                Toggle("7 d", week) { setWeek(true) }
            }
            LevelGraph(BatteryHistory.levelSeries(samples, from, now), from, now)
            Line("${fmtWhen(from)}  →  now · ${samples.count { it.ts >= from }} samples, kept ${BatteryHistory.RETENTION_MS / DAY_MS} d")

            val f = priv?.facts
            val design = f?.designMah ?: f?.statsCapacityMah
            Section(BatteryRows.capacity(r, design, f?.fullMah, f?.cycles))
            Section(BatteryRows.spreads(BatteryHistory.tempSpread(samples, from), BatteryHistory.voltageSpread(samples, from),
                if (week) "7 d" else "24 h"))

            val cycles = r.sessions.filter { !it.charging && it.durationMs > 0 }.asReversed().take(MAX_ROWS)
            Divider()
            Header("Discharge cycles (${cycles.size})")
            Table(listOf("When", "%", "Time", "Avg %/h · W", "Max T"),
                cycles.map { listOf(fmtWhen(it.startTs)) + BatteryRows.cycleRow(it) })

            val charges = r.sessions.filter { it.charging && it.durationMs > 0 }.asReversed().take(MAX_ROWS)
            Divider()
            Header("Charge sessions (${charges.size})")
            Table(listOf("When", "%", "Source", "Time", "Avg W in"),
                charges.map { listOf(fmtWhen(it.startTs)) + BatteryRows.chargeRow(it) })

            Divider()
            Header("Top apps since charge")
            val pv = priv
            when {
                pv == null -> Line("asking the privileged channel…")
                pv.channel == null -> Line("${BatteryRows.DASH} (needs the privileged shell channel: Configs › Network › ADB Shell)")
                pv.apps.isEmpty() -> Line("${BatteryRows.DASH} (no per-app block from ${pv.channel})")
                else -> pv.apps.forEach { (label, mah) -> Kv(label, String.format(Locale.US, "%.1f mAh", mah)) }
            }
            Kv("Privileged channel", pv?.channel ?: BatteryRows.DASH)
            Divider()
            Line("Estimates (est) are projections, not measurements. Rates are signed: + into the battery, − out. " +
                "History: battery_sot.db, sampled on battery events, screen on/off and a 15-min tick (no wakelock).")
        }
    }

    private fun readPrivileged(ctx: Context): Privileged {
        val channel = BatteryRepository.privilegedChannel(ctx) ?: return Privileged(null, null, emptyList())
        val facts = runCatching { BatteryRepository.privileged(ctx) }.getOrNull()
        val apps = facts?.apps.orEmpty().take(MAX_APPS).map { a -> BatteryRepository.appLabel(ctx, a.uid).second to a.mAh }
        return Privileged(facts, channel, apps)
    }

    // ── the page's dense parts ───────────────────────────────────────────

    @Composable
    private fun Section(s: BatteryRows.Section) {
        Divider()
        Header(s.title)
        for (row in s.rows) Kv(row.label, row.value)
    }

    @Composable
    private fun Header(t: String) {
        Text(t, color = LocalKitPalette.current.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 4.dp))
    }

    @Composable
    private fun Kv(label: String, value: String) {
        val p = LocalKitPalette.current
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
            Text(label, color = p.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.38f))
            Text(value, color = p.textPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.62f))
        }
    }

    @Composable
    private fun Line(t: String) {
        Text(t, color = LocalKitPalette.current.textSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 2.dp))
    }

    @Composable
    private fun Divider() {
        Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp).height(1.dp).background(LocalKitPalette.current.hairline))
    }

    @Composable
    private fun Toggle(t: String, on: Boolean, pick: () -> Unit) {
        val p = LocalKitPalette.current
        Text(t, color = if (on) p.accent else p.textSecondary, fontSize = 12.sp,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable(onClick = pick).padding(end = 16.dp, top = 2.dp, bottom = 4.dp))
    }

    @Composable
    private fun Table(head: List<String>, rows: List<List<String>>) {
        val p = LocalKitPalette.current
        val weights = listOf(0.22f, 0.16f, 0.14f, 0.34f, 0.14f)
        @Composable fun cells(c: List<String>, bold: Boolean) = Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
            c.forEachIndexed { i, t ->
                Text(t, color = if (bold) p.textSecondary else p.textPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(weights.getOrElse(i) { 0.15f }))
            }
        }
        if (rows.isEmpty()) { Line("${BatteryRows.DASH} none recorded yet"); return }
        cells(head, true)
        for (r in rows) cells(r, false)
    }

    /** The level over the window: a line, with the on-power spans shaded. */
    @Composable
    private fun LevelGraph(points: List<BatteryHistory.Point>, from: Long, to: Long) {
        val p = LocalKitPalette.current
        if (points.size < 2) { Line("${BatteryRows.DASH} not enough history yet for a graph"); return }
        Canvas(Modifier.fillMaxWidth().height(140.dp).padding(vertical = 4.dp).testTag("battery-graph")) {
            val span = (to - from).coerceAtLeast(1L).toFloat()
            fun x(ts: Long) = (ts - from) / span * size.width
            fun y(pct: Int) = size.height - pct / 100f * size.height
            for (g in listOf(0, 25, 50, 75, 100)) drawLine(p.hairline, Offset(0f, y(g)), Offset(size.width, y(g)), 1f)
            for (i in 0 until points.size - 1) if (points[i].onPower) {
                val a = x(points[i].ts); val b = x(points[i + 1].ts)
                drawRect(p.ok.copy(alpha = 0.18f), Offset(a, 0f), Size((b - a).coerceAtLeast(1f), size.height))
            }
            val path = Path()
            points.forEachIndexed { i, pt -> if (i == 0) path.moveTo(x(pt.ts), y(pt.levelPct)) else path.lineTo(x(pt.ts), y(pt.levelPct)) }
            drawPath(path, p.accent, style = Stroke(width = 2.dp.toPx()))
        }
    }

    private fun fmtWhen(ts: Long): String = SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(ts))

    companion object {
        const val REFRESH_MS = 5_000L
        const val DAY_MS = 24 * 3_600_000L
        const val WEEK_MS = 7 * DAY_MS
        const val MAX_ROWS = 30
        const val MAX_APPS = 10
        fun newInstance() = BatteryStatsFragment()
    }
}
