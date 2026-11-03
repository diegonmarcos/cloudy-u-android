package com.diegonmarcos.cloudme.health

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudme.R
import com.diegonmarcos.superapp.uiicons.UiIcons
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.IsoFields
import java.util.Date
import java.util.Locale

/** What every page gets: the declared content, the user's data, a way to change it (persisted), and a way to open another page. */
class HealthEnv(
    val content: HealthContent,
    val user: HealthUserData,
    val update: ((HealthUserData) -> HealthUserData) -> Unit,
    val go: (String) -> Unit,
    val pageLabel: (String) -> String,
)

private fun today() = LocalDate.now().toString()
private fun fmtDate(t: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))
private fun nowMinutes() = LocalTime.now().let { it.hour * 60 + it.minute }
private fun String.dec(): Double? = toDoubleOrNull()
private fun dstr(v: Double?): String = v?.let { HealthFormulas.num(it) }.orEmpty()

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HomePage(e: HealthEnv) {
    val h = e.content.home
    HTitle(h.title, h.subtitle)
    SectionLabel("Trackers")
    TwoColumns(listOf("tracked" to "steps", "activities" to "active", "hr" to "heart", "sleep" to "sleep", "bodycomp" to "weight", "workout" to "calories")) { (page, color), mod ->
        HCard(mod.testTag("me:health:home:tracker:$page"), onClick = { e.go(page) }) { MetricHead(MetricColor.of(color), e.pageLabel(page)) }
    }
    SectionLabel("Plan")
    h.clusters.forEachIndexed { i, cl ->
        HCard(Modifier.testTag("me:health:home:cluster:$i")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(UiIcons.vector(cl.icon), null, tint = accent())
                Text(cl.label, color = ink(), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp).weight(1f))
                Text("${cl.pages.size} pages", color = dim(), fontSize = 12.sp)
            }
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                cl.pages.forEach { p -> Pill(e.pageLabel(p)) { e.go(p) } }
            }
        }
    }
}

@Composable
fun BioPage(e: HealthEnv) {
    val ctx = LocalContext.current
    val f = e.content.formulas
    val latest = e.user.latestMeasure()
    val hc by produceState(Triple<Double?, Double?, Double?>(null, null, null)) {
        val wf = HealthHc.guarded(ctx, HealthHc.BODY) { c -> HealthHc.weight(c, 365).lastOrNull()?.value to HealthHc.bodyFat(c, 365).lastOrNull()?.value }
        val h = HealthHc.guarded(ctx, HealthHc.HEIGHT) { c -> HealthHc.height(c)?.value }
        value = Triple((wf as? HcState.Ready)?.data?.first, (h as? HcState.Ready)?.data, (wf as? HcState.Ready)?.data?.second)
    }
    var mass by remember(latest, hc) { mutableStateOf(dstr(latest?.massKg ?: hc.first)) }
    var height by remember(latest, hc) { mutableStateOf(dstr(latest?.heightCm ?: hc.second)) }
    var bf by remember(latest, hc) { mutableStateOf(dstr(latest?.bfPct ?: hc.third)) }
    val activity = e.user.activity ?: f.defaultActivity
    HTitle("Anthropometrics", "Lean mass and energy from your own numbers. LBM = mass x (1 - body fat). BMR = ${HealthFormulas.num(f.bmrBase)} + ${HealthFormulas.num(f.bmrPerLbm)} x LBM. TDEE = BMR x activity.")
    HCard {
        HHead("Parameters")
        if (latest == null && hc.first != null) HText("Prefilled from Health Connect. Save to keep it in your history.")
        Row { NumField("Mass (kg)", mass, { mass = it }, Modifier.weight(1f).padding(end = 8.dp)); NumField("Height (cm)", height, { height = it }, Modifier.weight(1f)) }
        NumField("Body fat (%)", bf, { bf = it })
        HText("Activity")
        Choice(f.activity.map { it.id to "${it.label} x${it.multiplier}" }, activity) { id -> e.update { it.copy(activity = id) } }
    }
    val a = HealthFormulas.anthro(f, mass.dec(), height.dec(), bf.dec(), activity)
    HCard {
        HHead("Diagnostics")
        if (a == null) HText("Enter mass, height and body fat to see LBM, FFMI, BMR and TDEE.")
        else {
            HRow("LBM", "%.1f kg".format(Locale.US, a.lbm))
            HRow("FFMI", "%.1f  (${a.ffmiBand})".format(Locale.US, a.ffmi))
            HRow("BMR", "${a.bmr} kcal")
            HRow("TDEE", "${a.tdee} kcal")
            Pill("Save to history", true) {
                e.update { it.withMeasurement(Measurement(System.currentTimeMillis(), mass.dec(), height.dec(), bf.dec())) }
            }
        }
    }
    HCard {
        HHead("History")
        if (e.user.measurements.isEmpty()) HText("No saved measurements yet.")
        e.user.measurements.takeLast(8).reversed().forEach { m ->
            val r = HealthFormulas.anthro(f, m.massKg, m.heightCm, m.bfPct, activity)
            HRow(fmtDate(m.t), "${dstr(m.massKg)} kg, ${dstr(m.bfPct)} %" + (r?.let { "  LBM %.1f".format(Locale.US, it.lbm) } ?: ""))
        }
    }
}

@Composable
fun CircadianPage(e: HealthEnv) {
    val f = e.content.formulas
    val plan = e.content.plan
    HTitle("24 h hormone kinetics", "The reference curve of the plan against your protocol. Orange lines are the plan's windows.")
    val now = nowMinutes()
    f.circadian.forEach { s ->
        val pts = (0..48).map { i -> (i * 30.0) to HealthFormulas.circadianAt(s.points, i * 30.0) }
        HCard {
            HHead("${s.label} (${s.unit})")
            LineChart(listOf(ChartSeries(pts, accent())), plan.timeline.map { it.minutes.toDouble() })
            Row(Modifier.fillMaxWidth()) { listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEach { Text(it, color = dim(), fontSize = 11.sp, modifier = Modifier.weight(1f)) } }
            HRow("Now (%02d:%02d)".format(now / 60, now % 60), "%.0f %s".format(Locale.US, HealthFormulas.circadianAt(s.points, now.toDouble()), s.unit))
        }
    }
    HealthFormulas.nextEvent(plan, now)?.let { n -> HCard { HHead("Next in your protocol"); HRow(n.time, n.title) } }
}

@Composable
fun BiomarkersPage(e: HealthEnv) {
    HTitle("Target biomarkers", "Plan targets and your lab results. Results are stored on this phone and travel with the fleet backup.")
    e.content.plan.biomarkers.forEach { b ->
        val hist = e.user.labsFor(b.id)
        val last = hist.lastOrNull()
        var entry by remember(b.id, hist.size) { mutableStateOf("") }
        HCard {
            HHead(b.label)
            HText(b.note)
            HRow("Target", HealthFormulas.rangeText(b))
            if (last == null) HRow("Latest", "no result yet")
            else {
                val st = HealthFormulas.biomarkerStatus(b, last.value)
                val col = if (st == HealthFormulas.Status.InRange) Good else Bad
                HRow("Latest (${fmtDate(last.t)})", "${HealthFormulas.num(last.value)} ${b.unit}  ${st.name}", col)
                hist.getOrNull(hist.size - 2)?.let { HRow("Change since previous", "%+.1f".format(Locale.US, last.value - it.value) + " ${b.unit}") }
            }
            NumField("New result (${b.unit})", entry, { entry = it })
            entry.dec()?.let { v -> Pill("Save result", true) { e.update { it.withLab(LabResult(b.id, v, System.currentTimeMillis())) } } }
            hist.takeLast(6).reversed().forEach { HRow(fmtDate(it.t), "${HealthFormulas.num(it.value)} ${b.unit}") }
        }
    }
}

@Composable
fun TimelinePage(e: HealthEnv) {
    val plan = e.content.plan
    val day = today()
    val doneCount = plan.timeline.count { "$day:${it.id}" in e.user.done }
    val next = HealthFormulas.nextEvent(plan, nowMinutes())
    HTitle("24-hour execution timeline", "$doneCount of ${plan.timeline.size} done today." + (next?.let { " Next: ${it.time} ${it.window}." } ?: ""))
    plan.timeline.forEach { ev ->
        val key = "$day:${ev.id}"
        val shape = RoundedCornerShape(14.dp)
        HCard(Modifier.then(if (ev.id == next?.id) Modifier.border(1.dp, accent(), shape) else Modifier)) {
            Row { Text(ev.time, color = accent(), fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(end = 12.dp)); HHead(ev.title) }
            HText(ev.body)
            (ev.items + ev.supplements).forEach { HRow(it.name, "${HealthFormulas.num(it.qty)} ${it.unit}") }
            Tick("Done today", key in e.user.done) { e.update { u -> u.copy(done = u.toggled(u.done, key)) } }
        }
    }
}

@Composable
fun ObjectivesPage(e: HealthEnv) {
    HTitle("The molecular rationale", "Why each window of the protocol is set the way it is.")
    val byId = e.content.plan.timeline.associateBy { it.id }
    e.content.plan.objectives.forEach { o ->
        var open by remember(o.id) { mutableStateOf<String?>(null) }
        HCard {
            HHead(o.title)
            HText(o.body)
            HText("Applies to")
            Row { o.applies.mapNotNull { byId[it] }.forEach { ev -> Pill("${ev.time} ${ev.window}", open == ev.id) { open = if (open == ev.id) null else ev.id } } }
            open?.let { id -> byId[id]?.let { ev -> HText("${ev.title}: ${ev.body}", ink()) } }
        }
    }
}

@Composable
fun MatrixPage(e: HealthEnv) {
    var q by remember { mutableStateOf("") }
    val now = nowMinutes()
    HTitle("Master clinical matrix", "Window, meal, pharmacology and rule, derived from the timeline.")
    OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("Filter") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedTextColor = ink(), unfocusedTextColor = ink(), cursorColor = accent(), focusedBorderColor = accent(), unfocusedBorderColor = colorResource(R.color.me_outline), focusedLabelColor = accent(), unfocusedLabelColor = dim()))
    val rows = e.content.plan.timeline.map { ev ->
        listOf("${ev.window} (${ev.time})", ev.items.joinToString(", ") { it.name }.ifBlank { "-" }, ev.supplements.joinToString(", ") { it.name }.ifBlank { "None" }, ev.rule)
    }.filter { r -> q.isBlank() || r.any { it.contains(q, ignoreCase = true) } }
    val current = e.content.plan.timeline.filter { it.minutes <= now }.maxByOrNull { it.minutes }
    HCard {
        Row { listOf("Window", "Meal", "Pharm", "Rule").forEach { Text(it, color = accent(), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)) } }
        if (rows.isEmpty()) HText("No row matches.")
        rows.forEach { r ->
            val isNow = current != null && r[0].startsWith(current.window)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                r.forEach { Text(it, color = if (isNow) accent() else ink(), fontSize = 12.sp, modifier = Modifier.weight(1f).padding(end = 4.dp)) }
            }
        }
        if (current != null) HText("The highlighted row is the window you are in now.")
    }
}

@Composable
fun CulinaryPage(e: HealthEnv) {
    val all = e.content.plan.culinary
    var nutrient by remember { mutableStateOf<String?>(null) }
    HTitle("Culinary chemistry", "Cooking methods chosen to raise bioavailability.")
    Choice(listOf("" to "All") + all.map { it.nutrient }.distinct().map { it to it }, nutrient ?: "") { nutrient = it.ifBlank { null } }
    all.filter { nutrient == null || it.nutrient == nutrient }.forEach { c ->
        HCard { HHead(c.method); HRow("Nutrient", c.nutrient); HText(c.effect); HText("How: ${c.how}", ink()) }
    }
}

@Composable
fun ClashPage(e: HealthEnv) {
    val d = e.content.plan.clash
    var p by remember { mutableStateOf(d.proteins.first().id) }
    var c by remember { mutableStateOf(d.cofactors.first().id) }
    HTitle("Absorption clash simulator", "Protein source against a co-factor: do they compete for the same gut transporter?")
    HCard {
        HHead("Protein source"); Choice(d.proteins.map { it.id to it.label }, p) { p = it }
        HHead("Co-factor / addition"); Choice(d.cofactors.map { it.id to it.label }, c) { c = it }
    }
    val v = HealthFormulas.clash(d, d.proteins.first { it.id == p }.tags, d.cofactors.first { it.id == c }.tags)
    if (v != null) VerdictCard(v)
    HHead("Your plan, window by window")
    e.content.plan.timeline.forEach { ev ->
        HealthFormulas.clashForWindow(d, ev)?.let { w -> HCard { HRow("${ev.time} ${ev.window}", w.title, levelColor(w.level)); HText(w.text) } }
    }
}

private fun levelColor(level: String) = when (level) { "antagonism" -> Bad; "suboptimal" -> Warn; else -> Good }

@Composable
private fun VerdictCard(v: HealthFormulas.Verdict) = HCard { HHead(v.title.uppercase()); HText(v.text, levelColor(v.level)) }

@Composable
fun ThermalPage(e: HealthEnv) {
    val f = e.content.formulas
    var min by remember { mutableFloatStateOf(20f) }
    var pre by remember { mutableStateOf("") }
    var post by remember { mutableStateOf("") }
    val s = HealthFormulas.sauna(f, min.toInt())
    HTitle("Sauna osmolarity engine", "Sodium to replace after a sauna session.")
    HCard {
        HHead("Sauna duration: ${min.toInt()} min")
        Slide(min, f.saunaMin.toFloat()..f.saunaMax.toFloat(), (f.saunaMax - f.saunaMin) / f.saunaStep - 1) { min = it }
        HRow("Sodium chloride lost", "~${s.naclMg} mg")
        HRow("Of which sodium", "~${s.sodiumMg} mg")
        HRow("Table salt to replace", "%.2f teaspoon".format(Locale.US, s.saltTeaspoons))
    }
    HCard {
        HHead("Fluid to replace")
        HText("Weigh yourself before and after. Fluid to drink is ${HealthFormulas.num(f.fluidReplaceRatio * 100)} % of the mass lost.")
        Row { NumField("Before (kg)", pre, { pre = it }, Modifier.weight(1f).padding(end = 8.dp)); NumField("After (kg)", post, { post = it }, Modifier.weight(1f)) }
        val ml = HealthFormulas.fluidToReplaceMl(f, pre.dec(), post.dec())
        HText(if (ml != null) "Drink about $ml mL." else "Enter both weights (after lower than before).", ink())
    }
}

@Composable
fun HemoPage(e: HealthEnv) {
    val f = e.content.formulas
    var x by remember { mutableFloatStateOf(50f) }
    HTitle("GI clearance dynamics", "Blood is diverted from the gut as exertion rises, so what is still in the stomach stalls.")
    HCard {
        HHead("Exertion: ${x.toInt()} % of VO2max")
        Slide(x, 0f..100f, 19) { x = it }
        HRow("Gut blood flow", "%.0f %% of resting".format(Locale.US, HealthFormulas.gutFlowPercent(f, x.toDouble())))
        LineChart(listOf(ChartSeries(f.hemoCurve, accent())), listOf(x.toDouble()), yMin = 0.0, yMax = 100.0)
    }
    HHead("Meal to training gaps in your plan")
    val gaps = HealthFormulas.mealGaps(f, e.content.plan)
    if (gaps.isEmpty()) HEmpty("The plan has no meal before a training window.")
    gaps.forEach { g ->
        HCard {
            HRow("${g.meal.time} ${g.meal.window}", "${g.minutes} min before ${g.trainingTitle}", if (g.ok) Good else Warn)
            HText(if (g.ok) "Clears the ${g.needed} min the declared rule asks for." else "Under the ${g.needed} min the declared rule asks for.")
        }
    }
}

@Composable
fun ShopPage(e: HealthEnv) {
    val plan = e.content.plan
    val needs = HealthFormulas.shopping(plan)
    val d = LocalDate.now()
    val week = "${d.get(IsoFields.WEEK_BASED_YEAR)}-W${d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
    var extra by remember { mutableStateOf("") }
    val total = needs.size + e.user.shopExtra.size
    val bought = needs.count { "$week:${it.item.id}" in e.user.shopChecked } + e.user.shopExtra.count { "$week:+$it" in e.user.shopChecked }
    HTitle("Procurement matrix", "The week's list, derived from the plan: ${plan.daysPerWeek} days of every item, rounded up to whole packs. $bought of $total bought this week.")
    needs.groupBy { it.item.category }.forEach { (cat, list) ->
        HCard {
            HHead(cat)
            list.forEach { n ->
                val key = "$week:${n.item.id}"
                Tick("${n.packs} x ${n.item.pack.label} (${HealthFormulas.num(n.item.pack.size)} ${n.item.pack.unit})  ${n.item.name}", key in e.user.shopChecked) { e.update { u -> u.copy(shopChecked = u.toggled(u.shopChecked, key)) } }
                HText("Need ${HealthFormulas.num(n.perWeek)} ${n.item.unit} this week", dim())
            }
        }
    }
    HCard {
        HHead("Extra items")
        e.user.shopExtra.forEach { t ->
            val key = "$week:+$t"
            Tick(t, key in e.user.shopChecked) { e.update { u -> u.copy(shopChecked = u.toggled(u.shopChecked, key)) } }
        }
        OutlinedTextField(extra, { extra = it }, singleLine = true, label = { Text("Add an item") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedTextColor = ink(), unfocusedTextColor = ink(), cursorColor = accent(), focusedBorderColor = accent(), unfocusedBorderColor = colorResource(R.color.me_outline), focusedLabelColor = accent(), unfocusedLabelColor = dim()))
        if (extra.isNotBlank()) Pill("Add", true) { e.update { u -> u.copy(shopExtra = u.shopExtra + extra.trim()) }; extra = "" }
        if (e.user.shopExtra.isNotEmpty()) Pill("Clear extra items") { e.update { u -> u.copy(shopExtra = emptyList()) } }
    }
}
