package com.diegonmarcos.cloudme.health

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.records.ExerciseSessionRecord
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.launch

private val zone: ZoneId get() = ZoneId.systemDefault()
private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)

private fun metricOf(id: String, label: String, unit: String, decimals: Int, avg: Boolean, goal: Double = 0.0) =
    TrackedMetric(id, label, unit, goal, decimals, avg, label, unit, 0)

private val WEIGHT = metricOf("weight", "Weight", "kg", 1, true)
private val BODY_FAT = metricOf("bodyfat", "Body fat", "%", 1, true)
private val RESTING_HR = metricOf("resting_hr", "Resting heart rate", "bpm", 0, true)
private val HRV = metricOf("hrv", "HRV (RMSSD)", "ms", 0, true)

/** A session's colour and kind, from its Health Connect exercise type. */
internal fun workoutKind(type: Int): Pair<String, Color> = when (type) {
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Run" to MetricColor.calories
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Ride" to MetricColor.distance
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Strength" to MetricColor.heart
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING, ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Walk" to MetricColor.steps
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA, ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "Mobility" to MetricColor.hrv
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Swim" to MetricColor.water
    else -> "Workout" to MetricColor.active
}

private fun minutes(w: Workout) = Duration.between(w.start, w.end).toMinutes()

/** The journal: sessions newest first, with the window's count, total and longest. */
@Composable
private fun JournalCard(title: String, sessions: List<Workout>, mock: Boolean, max: Int = 12) {
    HCard(Modifier.testTag("me:health:journal")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { MetricHead(MetricColor.active, title) }
            if (mock) MockBadge()
        }
        if (sessions.isEmpty()) { HText("No sessions in this window."); return@HCard }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Kpi("Sessions", "${sessions.size}", Modifier.weight(1f))
            Kpi("Minutes", "${sessions.sumOf { minutes(it) }}", Modifier.weight(1f))
            Kpi("Longest", "${sessions.maxOf { minutes(it) }} min", Modifier.weight(1f))
        }
        sessions.take(max).forEach { w ->
            val (kind, color) = workoutKind(w.type)
            LegendRow(color, w.title, "${minutes(w)} min", "$kind · ${w.start.atZone(zone).toLocalDate().format(dayFmt)}")
        }
        if (sessions.size > max) HText("and ${sessions.size - max} more.")
    }
}

@Composable
internal fun Kpi(label: String, value: String, modifier: Modifier = Modifier, color: Color = ink()) {
    Column(modifier) {
        Text(label.uppercase(), color = dim(), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Sessions per week over the last [weeks] weeks, oldest first, labelled by the week's Monday. */
private fun perWeek(sessions: List<Workout>, today: LocalDate, weeks: Int): List<Pair<String, Double>> {
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return (weeks - 1 downTo 0).map { back ->
        val from = monday.minusWeeks(back.toLong())
        val n = sessions.count { val d = it.start.atZone(zone).toLocalDate(); !d.isBefore(from) && d.isBefore(from.plusWeeks(1)) }
        "${from.dayOfMonth}/${from.monthValue}" to n.toDouble()
    }
}

data class ActivityData(val series: Map<String, List<Bucket>>, val sessions: List<Workout>)

@Composable
fun ActivitiesPage(@Suppress("UNUSED_PARAMETER") e: HealthEnv) {
    val ctx = LocalContext.current
    val ids = listOf("steps", "active", "distance", "calories")
    val metrics = remember { runCatching { Tracked.load(ctx) }.getOrDefault(emptyList()).filter { it.id in ids }.sortedBy { ids.indexOf(it.id) } }
    var range by rememberSaveable { mutableStateOf(TrackedRange.WEEK) }
    val today = LocalDate.now(zone)
    HTitle("Activities", "Movement from Health Connect: steps, active minutes, distance, calories and every session.")
    RangeToggle(range, { range = it }, listOf(TrackedRange.WEEK, TrackedRange.MONTH, TrackedRange.YEAR), MetricColor.steps)
    HealthData(
        TrackedHc.readPerms(ids), HealthSources.forPage("activities"), range,
        load = { c -> ActivityData(metrics.associate { it.id to TrackedHc.series(c, it.id, range, zone, it.avg) }, HealthHc.workouts(c, 30)) },
        empty = { d -> d.series.values.all { b -> b.all { it.value == null } } && d.sessions.isEmpty() },
        mock = { ActivityData(metrics.associate { it.id to HealthMock.series(it, range, today, zone) }, HealthMock.workouts(today, zone, 30)) },
    ) { d, mock ->
        metrics.forEach { MetricChartCard(it, range, d.series[it.id].orEmpty(), mock) }
        JournalCard("Journal, last 30 days", d.sessions, mock)
    }
}

@Composable
fun BodyCompPage(e: HealthEnv) {
    val f = e.content.formulas
    var range by rememberSaveable { mutableStateOf(TrackedRange.MONTH) }
    val today = LocalDate.now(zone)
    val mine = e.user.measurements.filter { it.massKg != null }.map { Reading(java.time.Instant.ofEpochMilli(it.t), it.massKg!!) }
    HTitle("Body composition", "Weight and body fat from Health Connect, with the entries you saved on Anthropometrics.")
    RangeToggle(range, { range = it }, listOf(TrackedRange.WEEK, TrackedRange.MONTH, TrackedRange.YEAR), MetricColor.weight)
    HealthData(
        HealthHc.BODY, HealthSources.forPage("bodycomp"), range,
        load = { c -> HealthHc.weight(c, 366) to HealthHc.bodyFat(c, 366) },
        empty = { (w, bf) -> w.isEmpty() && bf.isEmpty() && mine.isEmpty() },
        mock = { HealthMock.readings("weight", today, zone) to HealthMock.readings("bodyfat", today, zone) },
    ) { (w, bf), mock ->
        val all = if (mock) w else w + mine
        val wb = HealthAgg.bucketize(all, range, today, zone, true)
        val fb = HealthAgg.bucketize(bf, range, today, zone, true)
        val lastW = all.maxByOrNull { it.at }?.value
        val lastBf = bf.maxByOrNull { it.at }?.value ?: if (mock) null else e.user.measurements.lastOrNull { it.bfPct != null }?.bfPct
        val h = e.user.latestMeasure()?.heightCm
        HCard(Modifier.testTag("me:health:body:kpis")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { HHead("Latest") }
                if (mock) MockBadge()
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Kpi("Weight", lastW?.let { "%.1f kg".format(Locale.US, it) } ?: "--", Modifier.weight(1f), MetricColor.weight)
                Kpi("Body fat", lastBf?.let { "%.1f %%".format(Locale.US, it) } ?: "--", Modifier.weight(1f), MetricColor.bodyfat)
                Kpi("BMI", if (lastW != null && h != null && h > 0) "%.1f".format(Locale.US, lastW / (h / 100 * h / 100)) else "--", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                val dw = HealthAgg.change(wb); val df = HealthAgg.change(fb)
                Kpi("Weight change", dw?.let { "%+.1f kg".format(Locale.US, it) } ?: "--", Modifier.weight(1f), if ((dw ?: 0.0) <= 0) Good else Warn)
                Kpi("Fat change", df?.let { "%+.1f pt".format(Locale.US, it) } ?: "--", Modifier.weight(1f), if ((df ?: 0.0) <= 0) Good else Warn)
                Box(Modifier.weight(1f))
            }
            if (h == null) HText("Save your height on Anthropometrics to see BMI, LBM and FFMI.")
        }
        MetricChartCard(WEIGHT, range, wb, mock, null)
        MetricChartCard(BODY_FAT, range, fb, mock, null)
        val a = HealthFormulas.anthro(f, lastW, h, lastBf, e.user.activity)
        if (a != null) HCard {
            HHead("Derived from the latest values")
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Kpi("Lean body mass", "%.1f kg".format(Locale.US, a.lbm), Modifier.weight(1f))
                Kpi("FFMI", "%.1f".format(Locale.US, a.ffmi), Modifier.weight(1f))
            }
        }
    }
}

data class HrData(val resting: List<Reading>, val hrv: List<Reading>, val samples: List<Double>)

/** Max heart rate for the zones: age is not known here, so a fixed 190 bpm, said on the card. */
private const val MAX_HR = 190.0

@Composable
fun HrPage(@Suppress("UNUSED_PARAMETER") e: HealthEnv) {
    var range by rememberSaveable { mutableStateOf(TrackedRange.MONTH) }
    val today = LocalDate.now(zone)
    HTitle("Heart", "Resting heart rate, heart rate variability and time in heart-rate zones, from Health Connect.")
    RangeToggle(range, { range = it }, listOf(TrackedRange.WEEK, TrackedRange.MONTH, TrackedRange.YEAR), MetricColor.heart)
    HealthData(
        HealthHc.HEART_ZONES, HealthSources.forPage("hr"), range,
        load = { c -> HrData(HealthHc.restingHr(c, 366), HealthHc.hrv(c, 366), HealthHc.heartSamples(c, 7)) },
        empty = { d -> d.resting.isEmpty() && d.hrv.isEmpty() && d.samples.isEmpty() },
        mock = { HrData(HealthMock.readings("resting_hr", today, zone), HealthMock.readings("hrv", today, zone), HealthMock.hrSamples(today)) },
    ) { d, mock ->
        val rb = HealthAgg.bucketize(d.resting, range, today, zone, true)
        val hb = HealthAgg.bucketize(d.hrv, range, today, zone, true)
        ZonesCard(d.samples, mock)
        MetricChartCard(RESTING_HR, range, rb, mock, null)
        MetricChartCard(HRV, range, hb, mock, null)
        val base = HealthAgg.bucketize(d.hrv, TrackedRange.MONTH, today, zone, true).mapNotNull { it.value }
        if (base.size >= 8) HCard {
            HHead("HRV against your baseline")
            val recent = base.takeLast(7).average(); val all = base.average()
            val pct = (recent / all - 1) * 100
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Kpi("Last 7 days", "%.0f ms".format(Locale.US, recent), Modifier.weight(1f))
                Kpi("30-day base", "%.0f ms".format(Locale.US, all), Modifier.weight(1f))
                Kpi("Difference", "%+.0f %%".format(Locale.US, pct), Modifier.weight(1f), if (pct >= -5) Good else Warn)
            }
            HText(if (pct >= -5) "Within or above your usual range: recovered." else "Below your usual range: favour recovery.")
        }
    }
}

@Composable
private fun ZonesCard(samples: List<Double>, mock: Boolean) {
    val z = HealthAgg.zones(samples, MAX_HR)
    val total = z.sum()
    HCard(Modifier.testTag("me:health:zones")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { MetricHead(MetricColor.heart, "Heart-rate zones, last 7 days") }
            if (mock) MockBadge()
        }
        if (total == 0) { HText("No heart-rate samples above 50 % of max HR in the last 7 days."); return@HCard }
        SegmentBar(z.indices.map { MetricColor.zones[it] to z[it].toDouble() }, 18.dp)
        z.indices.forEach { i ->
            val lo = (MAX_HR * (0.5 + i * 0.1)).toInt(); val hi = if (i == 4) MAX_HR.toInt() else (MAX_HR * (0.6 + i * 0.1)).toInt()
            LegendRow(MetricColor.zones[i], "Z${i + 1} ${HealthAgg.ZONE_LABELS[i]}", "%.0f %%".format(Locale.US, z[i] * 100.0 / total), "$lo-$hi bpm")
        }
        HText("Share of active samples per zone, by % of a 190 bpm max heart rate (50-60, 60-70, 70-80, 80-90, 90+).")
    }
}

@Composable
fun SleepPage(e: HealthEnv) {
    var range by rememberSaveable { mutableStateOf(TrackedRange.WEEK) }
    var target by remember(e.user.sleepTargetH) { mutableStateOf(e.user.sleepTargetH?.let { HealthFormulas.num(it) }.orEmpty()) }
    val today = LocalDate.now(zone)
    val goal = e.user.sleepTargetH ?: 8.0
    HTitle("Sleep", "Nights and stages from Health Connect.")
    RangeToggle(range, { range = it }, listOf(TrackedRange.WEEK, TrackedRange.MONTH), MetricColor.sleep)
    HealthData(
        HealthHc.SLEEP, HealthSources.forPage("sleep"), range,
        load = { c -> HealthHc.sleep(c, range.days.toLong(), zone) },
        empty = { it.isEmpty() },
        mock = { HealthMock.nights(today, range.days) },
    ) { nights, mock ->
        val stages = MetricColor.stages.keys.toList()
        val labels = nights.map { if (range == TrackedRange.WEEK) it.day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US) else "${it.day.dayOfMonth}" }
        nights.last().let { n ->
            HCard(Modifier.testTag("me:health:sleep:last")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { MetricHead(MetricColor.sleep, "Last night, ${n.day.format(dayFmt)}") }
                    if (mock) MockBadge()
                }
                val hh = (n.totalMin / 60).toInt(); val mm = (n.totalMin % 60).toInt()
                BigNumber("Asleep", "${hh}h ${"%02d".format(mm)}", "", MetricColor.sleep)
                if (n.stageMin.isEmpty()) HText("The sleep app did not record stages for this night.")
                else {
                    val sum = stages.sumOf { n.stageMin[it] ?: 0.0 }
                    SegmentBar(stages.map { MetricColor.stages.getValue(it) to (n.stageMin[it] ?: 0.0) }, 18.dp)
                    stages.forEach { k -> n.stageMin[k]?.let { v -> LegendRow(MetricColor.stages.getValue(k), MetricColor.stageLabels.getValue(k), "%.0f min".format(Locale.US, v), "%.0f %%".format(Locale.US, v * 100 / sum)) } }
                }
            }
        }
        HCard(Modifier.testTag("me:health:sleep:hours")) {
            MetricHead(MetricColor.sleep, "Hours asleep per night")
            BarsChart(nights.map { it.totalMin / 60.0 }, labels, MetricColor.sleep, goal)
            StatStrip(HealthAgg.stats(nights.map { Bucket(it.day.atStartOfDay(), "", it.totalMin / 60.0) }), { "%.1f".format(Locale.US, it) }, "h")
            HText("Goal reached on ${nights.count { it.totalMin / 60.0 >= goal }} of ${nights.size} nights.")
        }
        if (nights.any { it.stageMin.isNotEmpty() }) HCard(Modifier.testTag("me:health:sleep:stages")) {
            MetricHead(MetricColor.sleep, "Stages per night")
            StackedBars(nights.map { n -> stages.filter { it != "awake" }.map { MetricColor.stages.getValue(it) to (n.stageMin[it] ?: 0.0) / 60.0 } }, labels, goal)
            val avg = stages.associateWith { k -> nights.map { it.stageMin[k] ?: 0.0 }.average() }
            val asleep = stages.filter { it != "awake" }.sumOf { avg.getValue(it) }.coerceAtLeast(1.0)
            stages.forEach { k ->
                val pct = if (k == "awake") null else avg.getValue(k) * 100 / asleep
                LegendRow(MetricColor.stages.getValue(k), MetricColor.stageLabels.getValue(k), "%.0f min".format(Locale.US, avg.getValue(k)), pct?.let { "%.0f %% of sleep".format(Locale.US, it) } ?: "average")
            }
            HText("Typical adult shares: deep 13-23 %, REM 20-25 %, the rest light sleep.")
        }
        HCard {
            HHead("Sleep debt")
            NumField("Target (hours per night)", target, { target = it }, Modifier.fillMaxWidth())
            Pill("Save target", target.toDoubleOrNull() != null, MetricColor.sleep) { e.update { it.copy(sleepTargetH = target.toDoubleOrNull()) } }
            e.user.sleepTargetH?.let { t ->
                val debt = HealthSeries.sleepDebtH(nights, t)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Kpi("Debt, these nights", "%.1f h".format(Locale.US, debt), Modifier.weight(1f), if (debt < 2) Good else Warn)
                    Kpi("Target", "%.1f h".format(Locale.US, t), Modifier.weight(1f))
                }
            } ?: HText("Set a target to see the debt. It is your number; the chart's goal line uses 8 h until you do.")
        }
    }
}

@Composable
fun WorkoutPage(e: HealthEnv) {
    val day = LocalDate.now().toString()
    val today = LocalDate.now(zone)
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val loggedThisWeek = e.user.workout.filter { it.done && runCatching { !LocalDate.parse(it.date).isBefore(monday) }.getOrDefault(false) }.map { it.date }.distinct().size
    val planDays = e.content.workout.size.coerceAtLeast(1)
    HTitle("Workout plan", "Tick what you did today and log the load. Your log stays on this phone and travels with the fleet backup.")
    HCard(Modifier.testTag("me:health:workout:week")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GoalRings(listOf(RingSpec(loggedThisWeek.toFloat() / planDays, MetricColor.active)), 96.dp, 10.dp) {
                Text("$loggedThisWeek/$planDays", color = ink(), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text("THIS WEEK", color = dim(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("$loggedThisWeek of $planDays plan days logged", color = ink(), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                val total = e.user.workout.filter { it.done }.map { it.date }.distinct().size
                Text("$total sessions logged in total", color = dim(), fontSize = 12.sp)
            }
        }
    }
    e.content.workout.forEachIndexed { i, d ->
        val doneToday = d.exercises.count { ex -> e.user.workout.any { it.date == day && it.day == d.id && it.exercise == ex.id && it.done } }
        val color = listOf(MetricColor.active, MetricColor.distance, MetricColor.calories, MetricColor.hrv, MetricColor.heart)[i % 5]
        HCard(Modifier.testTag("me:health:workout:day:${d.id}")) {
            MetricHead(color, d.label, "$doneToday/${d.exercises.size} today")
            SegmentBar(listOf(color to doneToday.toDouble(), Color.Transparent to (d.exercises.size - doneToday).toDouble()), 6.dp)
            d.exercises.forEach { ex ->
                val entry = e.user.workout.firstOrNull { it.date == day && it.day == d.id && it.exercise == ex.id }
                val last = e.user.workout.filter { it.day == d.id && it.exercise == ex.id && it.loadKg != null && it.date != day }.maxByOrNull { it.date }
                Tick("${ex.name}: ${ex.scheme}" + if (ex.note.isNotBlank()) " (${ex.note})" else "", entry?.done == true) { on ->
                    e.update { it.withWorkout(WorkoutEntry(day, d.id, ex.id, on, entry?.loadKg)) }
                }
                if (ex.load) {
                    var kg by remember(entry?.loadKg) { mutableStateOf(entry?.loadKg?.let { HealthFormulas.num(it) }.orEmpty()) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NumField("Load (kg)", kg, { kg = it }, Modifier.weight(1f).padding(end = 8.dp))
                        Pill("Save", kg.toDoubleOrNull() != null, color) { kg.toDoubleOrNull()?.let { v -> e.update { it.withWorkout(WorkoutEntry(day, d.id, ex.id, entry?.done == true, v)) } } }
                    }
                    last?.let { HText("Last logged ${it.date}: ${HealthFormulas.num(it.loadKg ?: 0.0)} kg") }
                }
            }
        }
    }
    SectionLabel("Recorded by your devices")
    HealthData(
        HealthHc.ACTIVITY, HealthSources.forPage("workout"), Unit,
        load = { c -> HealthHc.workouts(c, 56) },
        empty = { it.isEmpty() },
        mock = { HealthMock.workouts(today, zone, 56) },
    ) { list, mock ->
        HCard(Modifier.testTag("me:health:workout:weeks")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { MetricHead(MetricColor.active, "Sessions per week, last 8 weeks") }
                if (mock) MockBadge()
            }
            val weeks = perWeek(list, today, 8)
            BarsChart(weeks.map { it.second }, weeks.map { it.first }, MetricColor.active, planDays.toDouble())
            StatStrip(HealthAgg.stats(weeks.map { Bucket(today.atStartOfDay(), it.first, it.second) }), { "%.0f".format(Locale.US, it) }, "")
        }
        JournalCard("Journal, last 8 weeks", list, mock, 10)
    }
}

/** One summary line per tracker that Health Connect can answer right now, for the dossier; a source that cannot answer says why. */
suspend fun trackerSummary(ctx: android.content.Context): List<String> {
    fun <T> line(name: String, s: HcState<T>, ok: (T) -> String?): String = when (s) {
        is HcState.Ready -> ok(s.data) ?: "$name: no data in the last 30 days"
        is HcState.NeedPermission -> "$name: Health Connect permission not granted"
        is HcState.Unavailable -> "$name: ${s.why}"
        HcState.Loading -> "$name: not read"
    }
    val out = mutableListOf<String>()
    out += line("Weight", HealthHc.guarded(ctx, setOf(HealthHc.perm(androidx.health.connect.client.records.WeightRecord::class))) { HealthHc.weight(it, 30) }) { r ->
        r.lastOrNull()?.let { "Weight: %.1f kg (latest), %+.1f kg over 30 days".format(Locale.US, it.value, it.value - r.first().value) }
    }
    out += line("Resting heart rate", HealthHc.guarded(ctx, setOf(HealthHc.perm(androidx.health.connect.client.records.RestingHeartRateRecord::class))) { HealthHc.restingHr(it, 30) }) { r ->
        if (r.isEmpty()) null else "Resting heart rate: %.0f bpm average over 30 days".format(Locale.US, r.map { it.value }.average())
    }
    out += line("HRV", HealthHc.guarded(ctx, setOf(HealthHc.perm(androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord::class))) { HealthHc.hrv(it, 30) }) { r ->
        if (r.isEmpty()) null else "HRV RMSSD: %.0f ms average over 30 days".format(Locale.US, r.map { it.value }.average())
    }
    out += line("Sleep", HealthHc.guarded(ctx, HealthHc.SLEEP) { HealthHc.sleep(it, 30, zone) }) { n ->
        if (n.isEmpty()) null else "Sleep: %.1f h average over %d nights".format(Locale.US, n.map { it.totalMin }.average() / 60, n.size)
    }
    out += line("Steps", HealthHc.guarded(ctx, setOf(HealthHc.perm(androidx.health.connect.client.records.StepsRecord::class))) { HealthSeries.dailySum(HealthHc.steps(it, 30), zone) }) { d ->
        if (d.isEmpty()) null else "Steps: %.0f per day average over 30 days".format(Locale.US, d.map { it.value }.average())
    }
    return out
}

@Composable
fun DossierPage(e: HealthEnv) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val include = e.user.dossierSections ?: HealthDossier.ALL_SECTIONS.toSet()
    var notes by remember(e.user.dossierNotes) { mutableStateOf(e.user.dossierNotes) }
    var msg by remember { mutableStateOf("") }
    val tracker by produceState(emptyList<String>()) { value = trackerSummary(ctx) }
    val sections = HealthDossier.build(e.content, e.user, include, tracker)
    HTitle("Physician dossier", "A clinical summary for medical review, exported as a PDF.")
    HCard {
        HHead("Include")
        HealthDossier.ALL_SECTIONS.forEach { id ->
            Tick(HealthDossier.TITLES.getValue(id), id in include) { on -> e.update { it.copy(dossierSections = if (on) include + id else include - id) } }
        }
        TextBox("Notes for the physician", notes, { notes = it })
        Pill("Save notes", notes != e.user.dossierNotes) { e.update { it.copy(dossierNotes = notes) } }
    }
    sections.forEach { s -> HCard { HHead(s.title); s.lines.forEach { HText(it, ink()) } } }
    HCard {
        Pill("Export PDF", true) {
            scope.launch {
                val ok = HealthDossier.export(ctx, "Physician dossier ${LocalDate.now()}", HealthDossier.build(e.content, e.user, include, tracker))
                msg = if (ok) "" else "No app on this phone can open a PDF. Install Cloud Drive."
            }
        }
        if (msg.isNotBlank()) HText(msg, Bad)
        HText("The PDF opens in the fleet's PDF reader (Cloud Drive), where it can be printed or shared.")
    }
}
