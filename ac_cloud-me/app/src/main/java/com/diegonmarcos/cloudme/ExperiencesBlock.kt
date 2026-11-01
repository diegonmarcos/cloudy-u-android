package com.diegonmarcos.cloudme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.KitButton
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.KitOutlinedButton
import com.diegonmarcos.superapp.uikit.KitSearchBar
import com.diegonmarcos.superapp.uikit.KitSegmented
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatePill
import com.diegonmarcos.superapp.uikit.KitTextButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object ExpTags {
    const val SUMMARY = "me:exp:summary"
    const val SIDE = "me:exp:side"
    const val MILESTONES = "me:exp:milestones"
    const val ADD = "me:exp:add"
    fun chip(c: String) = "me:exp:chip:$c"
    fun group(c: String) = "me:exp:group:$c"
    fun item(collection: String, id: String) = "me:exp:item:$collection:$id"
    fun done(collection: String, id: String) = "me:exp:done:$collection:$id"
}

/**
 * Experiences > every tab: a `ledger` block with `"view": "done_plan"` (StackScreen dispatches here). Same store and
 * collections as the plain ledger (so the backup, the migration and the old records are untouched), drawn as:
 * the progress summary (ring + per-category bars), the age timeline on By 30 / 40 / 50 (`milestones`), Geo's stats
 * (`geo`), then ONE side at a time — Done | Planned — with search, category chips and the items grouped by category
 * and sorted by date. Planned items can be started, edited and marked done (which stamps today's date).
 * A plain Column: it lives inside the page's one scroller.
 */
@Composable
fun ExperiencesBlock(o: JSONObject) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val store = o.getString("store")
    val group = o.optString("group", "category")
    val fallbackIcon = o.optString("icon", "⭐")
    val defs = remember(o) { (o.optJSONArray("ledgers") ?: JSONArray()).let { a -> (0 until a.length()).map { LedgerDef.parse(a.getJSONObject(it)) } } }
    val doneDef = remember(defs) { ExperiencesModel.doneDef(defs) }
    val planDef = remember(defs) { ExperiencesModel.planDef(defs) }
    var data by remember(store, defs) {
        mutableStateOf(LedgerStore.load(ctx, store).let { d -> LedgerSeed.applied(d, defs).also { if (it !== d) LedgerStore.save(ctx, store, it) } })
    }
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    fun commit(d: LedgerData) { data = d; LedgerStore.save(ctx, store, d) }

    val key = doneDef?.collection ?: store
    var side by rememberSaveable(key) { mutableStateOf("done") }
    var query by rememberSaveable(key) { mutableStateOf("") }
    var category by rememberSaveable(key) { mutableStateOf("") }
    var adding by remember(key) { mutableStateOf(false) }
    var editing by remember(key) { mutableStateOf<String?>(null) }

    val all = ExperiencesModel.items(defs, data, group)
    val (doneItems, planItems) = ExperiencesModel.split(all)

    // 1 · the summary: title, ring, totals and one bar per category.
    Gap()
    KitCard(Modifier.testTag(ExpTags.SUMMARY)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(o.optString("title"), color = p.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("${doneItems.size} done · ${planItems.size} planned", color = p.textSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp))
                planItems.count { it.status == ExpStatus.IN_PROGRESS }.takeIf { it > 0 }?.let {
                    Text("$it in progress", color = p.warn, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
            ProgressRing(ExperiencesModel.progress(doneItems.size, planItems.size))
        }
        ExperiencesModel.counts(all).forEach { c ->
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ExperiencesModel.icon(c.category, fallbackIcon), fontSize = 14.sp, modifier = Modifier.width(24.dp))
                    Text(ExperiencesModel.name(c.category), color = p.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(c.label, color = p.textSecondary, fontSize = 12.sp)
                }
                Bar(ExperiencesModel.progress(c.done, c.planned), Modifier.padding(start = 24.dp, top = 4.dp))
            }
        }
    }

    // 2 · By 30 / 40 / 50: the age timeline over all three tabs.
    o.optJSONObject("milestones")?.let { m ->
        val born = m.optInt("born")
        val ages = (m.optJSONArray("ages") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getInt(it) } }
        if (born > 0 && ages.isNotEmpty()) {
            val thisYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
            Gap()
            MilestoneCard(ExperiencesModel.milestones(born, ages, data, thisYear), m.optInt("current", -1), m.optString("note"))
        }
    }

    // 3 · Geo: the place counts (the full continent breakdown stays on the Done side below, grouped by continent).
    o.optJSONObject("geo")?.let { g ->
        val s = GeoStats.compute(g, defs, data)
        Gap()
        KitCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(s.cities.toString(), "cities", Modifier.weight(1f))
                Stat(s.countries.toString(), "countries", Modifier.weight(1f))
                Stat("${s.continents}/${s.worldContinents}", "continents", Modifier.weight(1f))
                Stat(s.pctWorld, "of world", Modifier.weight(1f))
            }
            s.declared?.let { Text(it, color = p.textSecondary, fontSize = 12.sp) }
            if (s.first != null || s.last != null) Text(listOfNotNull(s.first?.let { "First $it" }, s.last?.let { "last $it" }).joinToString(" · "),
                color = p.textSecondary, fontSize = 12.sp)
        }
    }

    // 4 · Done | Planned, search, category chips.
    Gap()
    KitSegmented(listOf("done" to "✅ Done (${doneItems.size})", "plan" to "🎯 Planned (${planItems.size})"), side,
        { side = it; adding = false; editing = null; category = "" }, tag = ExpTags.SIDE)
    val shown = if (side == "done") doneItems else planItems
    Gap(8)
    KitSearchBar(query, { query = it }, {}, placeholder = "Search ${if (side == "done") "done" else "planned"}")
    val cats = ExperiencesModel.counts(shown).map { it.category }
    if (cats.size > 1) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("All · ${shown.size}", category.isBlank(), ExpTags.chip("")) { category = "" }
            ExperiencesModel.counts(shown).forEach { c ->
                Chip("${ExperiencesModel.icon(c.category, fallbackIcon)} ${ExperiencesModel.name(c.category)} · ${if (side == "done") c.done else c.planned}",
                    category == c.category, ExpTags.chip(c.category)) { category = if (category == c.category) "" else c.category }
            }
        }
    }

    // 5 · the add form for the side on screen.
    val sideDef = if (side == "done") doneDef else planDef
    if (sideDef != null) {
        Gap()
        if (!adding) {
            KitButton({ adding = true; editing = null }, Modifier.fillMaxWidth().testTag(ExpTags.ADD)) {
                Text(if (side == "done") "+ Add something done" else "+ Add a plan")
            }
        } else {
            KitCard { AddForm(sideDef, emptyMap(), onCancel = { adding = false }, onSave = { commit(data.added(sideDef.collection, it)); adding = false }) }
        }
    }

    // 6 · the items, grouped by category, by date.
    val filtered = ExperiencesModel.filter(shown, query, category)
    if (filtered.isEmpty()) {
        Gap()
        KitCard {
            KitEmptyState(null, when {
                shown.isNotEmpty() -> "Nothing matches the search or filter."
                side == "done" -> doneDef?.empty ?: "Nothing done recorded yet."
                else -> planDef?.empty?.takeIf { !it.startsWith("Nothing done") } ?: "Nothing planned yet. Add a plan with its target date."
            })
        }
    }
    ExperiencesModel.groups(filtered).forEach { (cat, items) ->
        val c = ExperiencesModel.counts(all).firstOrNull { it.category == cat }
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 2.dp).testTag(ExpTags.group(cat)), verticalAlignment = Alignment.CenterVertically) {
            Text("${ExperiencesModel.icon(cat, fallbackIcon)}  ${ExperiencesModel.name(cat)}".uppercase(Locale.ROOT), color = p.textSecondary,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            c?.let { Text(it.label, color = p.textSecondary, fontSize = 11.sp) }
        }
        items.forEach { item ->
            val def = if (item.status == ExpStatus.DONE) doneDef else planDef
            if (def != null && editing == item.id && !item.readOnly && item.collection == def.collection) {
                Gap(8)
                KitCard { AddForm(def, item.rec - "id", onCancel = { editing = null }, onSave = { commit(data.replaced(def.collection, item.id, it)); editing = null }) }
            } else {
                ItemCard(item, ExperiencesModel.icon(item.category, fallbackIcon),
                    onDone = { commit(ExperiencesModel.markDone(data, item.collection, doneDef!!.collection, item.id, today)) },
                    onUndone = { commit(ExperiencesModel.markUndone(data, item.collection, planDef!!.collection, item.id)) },
                    onToggleStart = { commit(ExperiencesModel.withStatus(data, item.collection, item.id,
                        if (item.status == ExpStatus.IN_PROGRESS) ExpStatus.PLANNED else ExpStatus.IN_PROGRESS)) },
                    onAccept = { commit(data.added(item.collection, item.rec)) },
                    onEdit = { editing = item.id; adding = false },
                    onRemove = { commit(data.removed(item.collection, item.id)) },
                    canMove = doneDef != null && planDef != null)
            }
        }
    }
}

@Composable private fun Gap(dp: Int = 12) = Spacer(Modifier.height(dp.dp))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemCard(item: ExpItem, icon: String, onDone: () -> Unit, onUndone: () -> Unit, onToggleStart: () -> Unit,
                     onAccept: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit, canMove: Boolean) {
    val p = LocalKitPalette.current
    val uri = LocalUriHandler.current
    Gap(8)
    KitCard(Modifier.testTag(ExpTags.item(item.collection, item.id))) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(p.surfaceSelected), contentAlignment = Alignment.Center) {
                Text(icon, fontSize = 17.sp)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                Text(item.title, color = p.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                val date = when {
                    item.date.isBlank() -> if (item.status == ExpStatus.DONE) "Date not recorded" else "No target date"
                    item.status == ExpStatus.DONE -> "Done ${item.date}" + (item.rec[ExperiencesModel.TARGET]?.let { " · target was $it" } ?: "")
                    else -> "Target ${item.date}"
                }
                Text(date, color = p.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                listOfNotNull(item.rec["place"], item.rec["country"], item.rec["continent"]?.takeIf { it != item.category }, item.rec["last"]?.let { "last $it" })
                    .filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
                    ?.let { Text(it.joinToString(" · "), color = p.textSecondary, fontSize = 12.sp) }
                if (item.note.isNotBlank()) Text(item.note, color = p.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                if (item.link.isNotBlank()) Text("🔗 ${item.link}", color = p.accent, fontSize = 12.sp, textDecoration = TextDecoration.Underline,
                    modifier = Modifier.padding(top = 4.dp).clickable { runCatching { uri.openUri(item.link) } })
            }
            KitStatePill(if (item.suggestion >= 0) "Suggested" else item.status.word, when (item.status) {
                ExpStatus.DONE -> KitState.OK
                ExpStatus.IN_PROGRESS -> KitState.WARN
                ExpStatus.PLANNED -> KitState.IDLE
            })
        }
        // Wraps on a narrow phone: four actions never squeeze or clip the row.
        FlowRow(Modifier.fillMaxWidth().padding(start = 44.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                item.suggestion >= 0 -> KitOutlinedButton(onAccept) { Text("Accept as a plan") }
                item.readOnly -> Text("From the profile", color = p.textSecondary, fontSize = 11.sp)
                item.status == ExpStatus.DONE -> {
                    if (canMove) KitTextButton(onUndone) { Text("Mark undone") }
                    KitTextButton(onEdit) { Text("Edit") }
                    KitTextButton(onRemove) { Text("Remove") }
                }
                else -> {
                    if (canMove) KitOutlinedButton(onDone, Modifier.testTag(ExpTags.done(item.collection, item.id))) { Text("✓ Mark done") }
                    KitTextButton(onToggleStart) { Text(if (item.status == ExpStatus.IN_PROGRESS) "Pause" else "Start") }
                    KitTextButton(onEdit) { Text("Edit") }
                    KitTextButton(onRemove) { Text("Remove") }
                }
            }
        }
    }
}

/** The By 30 / 40 / 50 timeline: one node per age, filled once reached, the current tab's age highlighted. */
@Composable
private fun MilestoneCard(ms: List<Milestone>, current: Int, note: String) {
    val p = LocalKitPalette.current
    KitCard(Modifier.testTag(ExpTags.MILESTONES)) {
        Text("Age milestones", color = p.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        ms.forEachIndexed { i, m ->
            Row(verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(20.dp)) {
                    Box(Modifier.size(14.dp).clip(CircleShape)
                        .background(if (m.reached) p.ok else p.surface)
                        .border(2.dp, if (m.age == current) p.accent else p.hairline, CircleShape))
                    if (i < ms.lastIndex) Box(Modifier.width(2.dp).height(38.dp).background(p.hairline))
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("By ${m.age} · ${m.year}" + if (m.reached) " · reached" else "", color = if (m.age == current) p.accent else p.textPrimary,
                        fontSize = 14.sp, fontWeight = if (m.age == current) FontWeight.SemiBold else FontWeight.Normal)
                    Text("${m.done} done before ${m.age} · ${m.planned} planned by ${m.age}", color = p.textSecondary, fontSize = 12.sp)
                    Bar(ExperiencesModel.progress(m.done, m.planned), Modifier.padding(top = 4.dp))
                }
            }
        }
        if (note.isNotBlank()) Text(note, color = p.textSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun Stat(value: String, caption: String, modifier: Modifier) {
    val p = LocalKitPalette.current
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(p.surfaceSelected).padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = p.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(caption, color = p.textSecondary, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun Chip(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(16.dp)
    Text(label, color = if (on) p.textPrimary else p.textSecondary, fontSize = 12.sp, maxLines = 1,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.testTag(tag).clip(shape).background(if (on) p.surfaceSelected else p.surface)
            .border(1.dp, if (on) p.accent else p.hairline, shape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
private fun Bar(share: Float, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.surfaceSelected)) {
        if (share > 0f) Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.ok))
    }
}

@Composable
private fun ProgressRing(share: Float, color: Color = LocalKitPalette.current.ok) {
    val p = LocalKitPalette.current
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(64.dp)) {
            val w = 7.dp.toPx()
            val inset = w / 2
            val arc = Size(size.width - w, size.height - w)
            drawArc(p.surfaceSelected, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
            drawArc(color, -90f, 360f * share.coerceIn(0f, 1f), false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
        }
        Text("${(share * 100).toInt()}%", color = p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
