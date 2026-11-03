package com.diegonmarcos.cloudme.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.health.HealthConnectGateway
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val histDay = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)
private val histHour = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val histMonth = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
private val todayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.US)

/** A Tracked card's data: today's value and the last 7 days, for the sparkline. */
data class MetricDay(val today: Double?, val week: List<Bucket>)

/** The Browse tiles: each tracker page with its colour and what it holds. */
private val BROWSE = listOf(
    Triple("activities", "steps", "Steps, active minutes, distance, calories, sessions"),
    Triple("hr", "heart", "Resting HR, HRV and heart-rate zones"),
    Triple("sleep", "sleep", "Hours, stages per night, sleep debt"),
    Triple("bodycomp", "weight", "Weight, body fat, LBM and FFMI"),
    Triple("workout", "active", "Your plan, weekly goal and history"),
)

/**
 * Health > Tracked, the landing page of Health: today's goal rings (steps, active minutes, calories), mini rings (sleep, water,
 * distance), a card per metric with its 7-day sparkline, Browse into the tracker pages, and the sources. A card or ring opens its
 * detail: D / W / M / Y chart with the goal line, min / avg / max, history and a manual entry written to Health Connect.
 */
@Composable
fun TrackedPage(e: HealthEnv) {
    val ctx = LocalContext.current
    val metrics = remember { runCatching { Tracked.load(ctx) }.getOrDefault(emptyList()) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val selected = metrics.firstOrNull { it.id == open }
    if (selected != null) { TrackedDetail(selected, tick, { tick++ }) { open = null }; return }
    HTitle("Today", today.format(todayFmt))
    if (metrics.isEmpty()) { HEmpty("health_tracked.json is missing from this build."); return }
    HealthData(
        TrackedHc.readPerms(metrics.map { it.id }), HealthSources.forPage("tracked"), tick,
        load = { c ->
            metrics.associate { m ->
                m.id to MetricDay(runCatching { TrackedHc.today(c, m.id, zone) }.getOrNull(), runCatching { TrackedHc.series(c, m.id, TrackedRange.WEEK, zone, m.avg) }.getOrDefault(emptyList()))
            }
        },
        empty = { d -> d.values.all { it.today == null && it.week.all { b -> b.value == null } } },
        mock = {
            metrics.associate { m ->
                val week = HealthMock.series(m, TrackedRange.WEEK, today, zone)
                m.id to MetricDay(HealthAgg.headline(m, TrackedRange.DAY, HealthMock.series(m, TrackedRange.DAY, today, zone)) ?: week.last().value, week)
            }
        },
    ) { data, mock ->
        val byId = metrics.associateBy { it.id }
        fun v(id: String) = data[id]?.today
        fun p(id: String) = byId[id]?.let { Tracked.progress(v(id), it.goal) }
        HCard(Modifier.testTag("me:tracked:rings"), onClick = { open = "steps" }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GoalRings(listOf(RingSpec(p("steps") ?: 0f, MetricColor.steps), RingSpec(p("active") ?: 0f, MetricColor.active), RingSpec(p("calories") ?: 0f, MetricColor.calories)))
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    listOf("steps", "active", "calories").mapNotNull { byId[it] }.forEach { m ->
                        Text(m.label.uppercase(), color = MetricColor.of(m.id), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(Tracked.format(m, v(m.id)), color = ink(), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(" / ${Tracked.format(m, m.goal)} ${m.unit}", color = dim(), fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (mock) Box(Modifier.padding(top = 10.dp)) { MockBadge() }
        }
        HCard(Modifier.testTag("me:tracked:goals")) {
            Row(Modifier.fillMaxWidth()) {
                listOf("sleep", "water", "distance").mapNotNull { byId[it] }.forEach { m ->
                    MiniRing(p(m.id), MetricColor.of(m.id), m.label, "${Tracked.format(m, v(m.id))} ${m.unit}", Modifier.weight(1f).clickable { open = m.id }.testTag("me:tracked:ring:${m.id}"))
                }
            }
        }
        SectionLabel("Your metrics")
        TwoColumns(metrics) { m, mod -> MetricTile(m, data[m.id], mock, mod) { open = m.id } }
        SectionLabel("Browse")
        TwoColumns(BROWSE) { (page, color, what), mod ->
            HCard(mod.testTag("me:tracked:browse:$page"), onClick = { e.go(page) }) {
                MetricHead(MetricColor.of(color), e.pageLabel(page))
                Text(what, color = dim(), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String) =
    Text(text.uppercase(), color = dim(), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 2.dp))

@Composable
private fun MetricTile(m: TrackedMetric, d: MetricDay?, mock: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = MetricColor.of(m.id)
    HCard(modifier.testTag("me:tracked:card:${m.id}"), onClick = onClick) {
        MetricHead(color, m.label, if (mock) "mock" else "")
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
            Text(Tracked.format(m, d?.today), color = ink(), fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(" ${m.unit}", color = dim(), fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp), maxLines = 1)
        }
        Tracked.progress(d?.today, m.goal)?.let { pr -> SegmentBar(listOf(color to pr.toDouble(), Color.Transparent to (1.0 - pr)), 6.dp) }
        Box(Modifier.padding(top = 8.dp)) { Sparkline(d?.week.orEmpty().map { it.value }, color) }
        Text("7 days", color = dim(), fontSize = 10.sp)
    }
}

/**
 * One metric's chart card, shared by Tracked's detail and every tracker page: the headline (today's total, or the daily average of
 * the range), bars for sum metrics or a line for avg metrics with the goal line, and the min / avg / max strip.
 */
@Composable
internal fun MetricChartCard(m: TrackedMetric, range: TrackedRange, buckets: List<Bucket>, mock: Boolean, goal: Double? = m.goal.takeIf { it > 0 }) {
    val color = MetricColor.of(m.id)
    HCard(Modifier.testTag("me:tracked:chart:${m.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { MetricHead(color, m.label) }
            if (mock) MockBadge()
        }
        BigNumber(HealthAgg.headlineLabel(m, range), Tracked.format(m, HealthAgg.headline(m, range, buckets)), m.unit, color)
        val g = if (range == TrackedRange.DAY && !m.avg) null else goal
        if (m.avg) TrendLine(buckets.map { it.value }, buckets.map { it.label }, color, g)
        else BarsChart(buckets.map { it.value }, buckets.map { it.label }, color, g)
        StatStrip(HealthAgg.stats(buckets), { Tracked.format(m, it) }, m.unit)
        if (g != null && !m.avg) {
            val hit = buckets.count { (it.value ?: 0.0) >= g }
            HText("Goal reached on $hit of ${buckets.size} ${if (range == TrackedRange.YEAR) "months (daily average)" else "days"}.")
        }
    }
}

@Composable
private fun TrackedDetail(m: TrackedMetric, tick: Int, changed: () -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    var range by rememberSaveable { mutableStateOf(TrackedRange.WEEK) }
    var entry by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val ask = rememberLauncherForActivityResult(HealthConnectGateway.requestPermissionsContract()) { changed() }
    Pill("< Tracked") { back() }
    HTitle(m.label, "Health Connect, merged across every app that writes it.")
    RangeToggle(range, { range = it }, color = MetricColor.of(m.id))
    HealthData(
        TrackedHc.readPerms(listOf(m.id)), listOfNotNull(HealthSources.of(m.id)), "$tick/${range.name}",
        load = { c -> TrackedHc.series(c, m.id, range, zone, m.avg) },
        empty = { b -> b.all { it.value == null } },
        mock = { HealthMock.series(m, range, LocalDate.now(zone), zone) },
    ) { buckets, mock ->
        MetricChartCard(m, range, buckets, mock)
        HCard(Modifier.testTag("me:tracked:history:${m.id}")) {
            HHead("History")
            val hist = Tracked.history(buckets).take(10)
            if (hist.isEmpty()) HText("Nothing for this ${range.label.lowercase()}.")
            hist.forEach { b ->
                val label = when (range) { TrackedRange.DAY -> b.start.format(histHour); TrackedRange.YEAR -> b.start.format(histMonth); else -> b.start.format(histDay) }
                HRow(label, "${Tracked.format(m, b.value)} ${m.unit}${if (range == TrackedRange.YEAR && !m.avg) " / day" else ""}")
            }
        }
    }
    HCard(Modifier.testTag("me:tracked:add:${m.id}")) {
        HHead("Add an entry")
        NumField("${m.entryLabel} (${m.entryUnit})", entry, { entry = it }, Modifier.fillMaxWidth())
        Pill("Save to Health Connect", Tracked.validEntry(m, entry.toDoubleOrNull()), MetricColor.of(m.id)) {
            val v = entry.toDoubleOrNull()
            if (!Tracked.validEntry(m, v)) { note = "Enter a positive ${m.entryUnit} value."; return@Pill }
            scope.launch {
                val perm = TrackedHc.writePerm(m.id)
                val c = HealthConnectGateway.client(ctx)
                when {
                    c == null -> note = "Health Connect is not available on this phone."
                    perm !in HealthConnectGateway.grantedPermissions(ctx) -> { note = "Allow Cloud Me to write ${m.label.lowercase()} in Health Connect, then save again."; ask.launch(setOf(perm)) }
                    else -> runCatching { TrackedHc.add(c, m, v!!, zone) }
                        .onSuccess { note = "Saved ${Tracked.format(m, v)} ${m.entryUnit}."; entry = ""; changed() }
                        .onFailure { note = "Health Connect refused the entry: ${it.javaClass.simpleName}" }
                }
            }
        }
        if (note.isNotBlank()) HText(note)
    }
}
