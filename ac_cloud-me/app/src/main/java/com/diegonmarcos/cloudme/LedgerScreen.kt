package com.diegonmarcos.cloudme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudme.health.Choice
import com.diegonmarcos.cloudme.health.HCard
import com.diegonmarcos.cloudme.health.HHead
import com.diegonmarcos.cloudme.health.HRow
import com.diegonmarcos.cloudme.health.HText
import com.diegonmarcos.cloudme.health.Pill
import com.diegonmarcos.cloudme.health.accent
import com.diegonmarcos.cloudme.health.dim
import com.diegonmarcos.cloudme.health.ink
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LedgerTags {
    fun add(collection: String) = "me:ledger:add:$collection"
    fun save(collection: String) = "me:ledger:save:$collection"
    fun field(collection: String, id: String) = "me:ledger:field:$collection:$id"
    fun row(collection: String, id: String) = "me:ledger:row:$collection:$id"
    fun remove(collection: String, id: String) = "me:ledger:remove:$collection:$id"
    fun edit(collection: String, id: String) = "me:ledger:edit:$collection:$id"
    fun move(collection: String, id: String) = "me:ledger:move:$collection:$id"
    fun accept(collection: String, n: Int) = "me:ledger:accept:$collection:$n"
    fun builtin(collection: String, n: Int) = "me:ledger:builtin:$collection:$n"
    fun empty(collection: String) = "me:ledger:empty:$collection"
    const val SUMMARY = "me:ledger:summary"
}

/**
 * The `ledger` block of a page stack (Buro and LLC): `{"kind":"ledger","store":"buro_user","currency":"EUR",
 * "reports":["budget_vs_actual"],"ledgers":[{collection,title,empty,fields:[{id,label,type,options,required}]}]}`.
 * Reports first (derived, read-only), then each collection as its records plus an add form. It is a plain Column
 * so it lives inside the page's ONE scroller ([StackScreen]) and never nests another. Records are typed in by the
 * owner; there are no seeded rows, and an empty collection says what it is for.
 */
@Composable
fun LedgerBlock(o: JSONObject) {
    // Experiences: the same ledger drawn as Done | Planned (ExperiencesBlock); Buro, LLC and Inventory keep this view.
    if (o.optString("view") == "done_plan") { ExperiencesBlock(o); return }
    val ctx = LocalContext.current
    val store = o.getString("store")
    val currency = o.optString("currency", "EUR")
    val defs = remember(o) { (o.optJSONArray("ledgers") ?: JSONArray()).let { a -> (0 until a.length()).map { LedgerDef.parse(a.getJSONObject(it)) } } }
    val reports = remember(o) { (o.optJSONArray("reports") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) } } }
    // #1367 a declared `seed` lands in the store once, on the first load that meets its collection (LedgerSeed).
    var data by remember(store, defs) {
        mutableStateOf(LedgerStore.load(ctx, store).let { d -> LedgerSeed.applied(d, defs).also { if (it !== d) LedgerStore.save(ctx, store, it) } })
    }
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    fun commit(d: LedgerData) { data = d; LedgerStore.save(ctx, store, d) }

    o.optString("title").takeIf { it.isNotBlank() }?.let { HCard { HHead(it) } }
    o.optJSONObject("summary")?.let { s ->
        SummaryCard(LedgerReports.summary(defs.flatMap { data.rows(it.collection) }, s.optString("value"), s.optString("monthly")),
            currency, s.optString("note"))
    }
    o.optJSONObject("geo")?.let { g -> GeoCard(GeoStats.compute(g, defs, data)) }
    for (r in reports) LedgerReports.build(r, data, today, currency)?.let { ReportCard(it) }
    for (def in defs) CollectionCard(def, data.rows(def.collection), currency,
        onAdd = { values -> commit(data.added(def.collection, values)) },
        onRemove = { id -> commit(data.removed(def.collection, id)) },
        onEdit = { id, values -> commit(data.replaced(def.collection, id, values)) },
        onMove = if (def.moveTo.isBlank()) null else { id -> commit(data.moved(def.collection, id, def.moveTo, def.moveStamp, today)) })
}

/** Achieves > Geo: the counts, the share of the world, first and last visit, then every place by continent and country. */
@Composable
private fun GeoCard(g: GeoSummary) {
    HCard {
        HHead("🌍 Geo stats")
        HRow("Cities", g.cities.toString())
        HRow("Countries", g.countries.toString())
        HRow("Continents", "${g.continents} of ${g.worldContinents}")
        HRow("% of world", "${g.pctWorld} of ${g.worldCountries} countries")
        g.first?.let { HRow("First visit", it) }
        g.last?.let { HRow("Last visit", it) }
        HRow("Wishlist", g.wishlist.toString())
        g.declared?.let { HText(it) }
        g.byContinent.forEach { (continent, countries) ->
            Text("$continent · ${countries.size} countries", color = ink(), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
            countries.forEach { (country, cities) -> HRow(country, cities.joinToString(", ")) }
        }
    }
}

/** #1367 Inventory header: items, total value, monthly recurring (and the year it makes). */
@Composable
private fun SummaryCard(s: LedgerSummary, currency: String, note: String) {
    HCard(Modifier.testTag(LedgerTags.SUMMARY)) {
        HHead("Summary")
        HRow("Items", s.count.toString())
        HRow("Value", LedgerModel.fmt(s.value, currency))
        HRow("Monthly recurring", LedgerModel.fmt(s.monthly, currency))
        HRow("Per year", LedgerModel.fmt(s.monthly.multiply(java.math.BigDecimal(12)), currency))
        if (note.isNotBlank()) HText(note)
    }
}

@Composable
private fun ReportCard(t: ReportTable) {
    HCard {
        HHead(t.title)
        HText(t.note)
        if (t.rows.isEmpty()) HText("Nothing to report until records are added below.")
        // One card per row (label on top, one line per figure): a table of six columns does not fit a phone.
        t.rows.forEach { r -> ReportRow(t.columns, r) }
        t.total?.let { ReportRow(t.columns, it, bold = true) }
    }
}

@Composable
private fun ReportRow(columns: List<String>, cells: List<String>, bold: Boolean = false) {
    Text(cells.first(), color = ink(), fontSize = 14.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
    for (i in 1 until columns.size) if (cells.getOrElse(i) { "" }.isNotBlank()) HRow(columns[i], cells[i])
}

@Composable
private fun CollectionCard(def: LedgerDef, rows: List<Rec>, currency: String, onAdd: (Rec) -> Unit, onRemove: (String) -> Unit,
                           onEdit: (String, Rec) -> Unit, onMove: ((String) -> Unit)?) {
    var adding by remember(def.collection) { mutableStateOf(false) }
    var editing by remember(def.collection) { mutableStateOf<String?>(null) }
    val first = def.fields.first().id
    HCard {
        HHead(def.title)
        def.builtin.forEachIndexed { n, r ->
            Column(Modifier.fillMaxWidth().padding(top = 10.dp).testTag(LedgerTags.builtin(def.collection, n))) { RecLines(def, r, currency) }
        }
        if (rows.isEmpty() && def.builtin.isEmpty()) HText(def.empty, modifier = Modifier.testTag(LedgerTags.empty(def.collection)))
        rows.forEach { r ->
            val id = r["id"].orEmpty()
            if (editing == id) {
                AddForm(def, r, onCancel = { editing = null }, onSave = { onEdit(id, it); editing = null })
                return@forEach
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).testTag(LedgerTags.row(def.collection, id))) {
                Column(Modifier.weight(1f)) {
                    RecLines(def, r, currency)
                    LedgerSeed.badge(r)?.let { Text(it, color = accent(), fontSize = 11.sp, modifier = Modifier.testTag(LedgerSeed.tag(def.collection, id))) }
                }
                onMove?.let { mv -> Text(def.moveLabel, color = accent(), fontSize = 12.sp, modifier = Modifier.clickable { mv(id) }.padding(8.dp).testTag(LedgerTags.move(def.collection, id))) }
                Text("Edit", color = dim(), fontSize = 12.sp, modifier = Modifier.clickable { editing = id }.padding(8.dp).testTag(LedgerTags.edit(def.collection, id)))
                Text("Remove", color = dim(), fontSize = 12.sp, modifier = Modifier.clickable { onRemove(id) }.padding(8.dp).testTag(LedgerTags.remove(def.collection, id)))
            }
        }
        val open = def.suggestions.withIndex().filter { (_, sg) -> rows.none { it[first] == sg[first] } }
        if (open.isNotEmpty()) HText("Suggestions")
        open.forEach { (n, sg) ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(Modifier.weight(1f)) { RecLines(def, sg, currency) }
                Text("Accept", color = accent(), fontSize = 12.sp, modifier = Modifier.clickable { onAdd(sg) }.padding(8.dp).testTag(LedgerTags.accept(def.collection, n)))
            }
        }
        if (!adding) {
            Pill("Add", true, accent()) { adding = true }
        } else {
            AddForm(def, emptyMap(), onCancel = { adding = false }, onSave = { onAdd(it); adding = false })
        }
    }
}

@Composable
private fun RecLines(def: LedgerDef, r: Rec, currency: String) {
    Text(r[def.fields.first().id].orEmpty(), color = ink(), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    def.fields.drop(1).forEach { f ->
        r[f.id]?.let { v -> Text("${f.label}: ${if (f.type == "money") LedgerModel.fmt(LedgerModel.money(v) ?: java.math.BigDecimal.ZERO, currency) else v}", color = dim(), fontSize = 12.sp) }
    }
}

@Composable
internal fun AddForm(def: LedgerDef, initial: Rec, onCancel: () -> Unit, onSave: (Rec) -> Unit) {
    var values by remember(def.collection, initial) { mutableStateOf<Rec>(initial) }
    var errors by remember(def.collection) { mutableStateOf<Map<String, String>>(emptyMap()) }
    for (f in def.fields) {
        if (f.type == "choice") {
            HText(f.label)
            Choice(f.options.map { it to it }, values[f.id]) { values = values + (f.id to it) }
        } else {
            OutlinedTextField(
                value = values[f.id].orEmpty(), onValueChange = { values = values + (f.id to it) }, singleLine = true,
                label = { Text(f.label + if (f.type == "date") " (yyyy-mm-dd)" else "") },
                keyboardOptions = KeyboardOptions(keyboardType = if (f.type == "money") KeyboardType.Decimal else KeyboardType.Text),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = ink(), unfocusedTextColor = ink(), cursorColor = accent(),
                    focusedBorderColor = accent(), unfocusedBorderColor = colorResource(R.color.me_outline),
                    focusedLabelColor = accent(), unfocusedLabelColor = dim()),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(LedgerTags.field(def.collection, f.id)),
            )
        }
        errors[f.id]?.let { Text(it, color = com.diegonmarcos.cloudme.health.Bad, fontSize = 12.sp) }
    }
    Row {
        Pill("Save", true, accent()) {
            val e = LedgerModel.validate(def, values)
            errors = e
            if (e.isEmpty()) onSave(LedgerModel.clean(def, values))
        }
        Pill("Cancel", false) { onCancel() }
    }
}
