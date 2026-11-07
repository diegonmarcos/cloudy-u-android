package com.diegonmarcos.cloudme

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudme.health.DossierSection
import com.diegonmarcos.cloudme.health.HealthDossier
import org.json.JSONObject
import java.time.LocalDate

/**
 * The on-device store of one record's imported values. Spelled with literal names so the fleet-config guard sees
 * both stores (declared in fleet-config.json, so the backup and the new-phone migration carry them).
 */
object ProfileStore {
    const val HEALTH = "profile_health_user"
    const val DD = "profile_dd_user"
    private const val KEY = "data"

    private fun prefs(ctx: Context, store: String) = when (store) {
        HEALTH -> ctx.getSharedPreferences("profile_health_user", Context.MODE_PRIVATE)
        DD -> ctx.getSharedPreferences("profile_dd_user", Context.MODE_PRIVATE)
        else -> throw IllegalArgumentException("not a profile store: $store")
    }

    fun load(ctx: Context, store: String): JSONObject? =
        prefs(ctx, store).getString(KEY, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun save(ctx: Context, store: String, data: JSONObject) {
        prefs(ctx, store).edit().putString(KEY, data.toString()).apply()
    }
}

/** The assets `profiles/<id>.schema.json`; null when the asset is lost (the page then says so). */
internal fun loadSchema(ctx: Context, id: String): JSONObject? =
    runCatching { JSONObject(CloudMeApiData.text(ctx, "profiles/$id.schema.json")) }.getOrNull()

/**
 * `{"kind":"profile_record","schema":"health","store":"profile_health_user"}`: the completeness header with its status
 * counts, one card per section with a status chip on every field, Import (a JSON file from the private store) and
 * Export PDF (written by the platform's PdfDocument, opened by the fleet's one PDF engine, Cloud Drive's reader).
 */
@Composable
fun ProfileRecordBlock(o: JSONObject) {
    val ctx = LocalContext.current
    val schema = remember(o) { loadSchema(ctx, o.optString("schema")) }
    val store = o.optString("store")
    var values by remember(o) { mutableStateOf(runCatching { ProfileStore.load(ctx, store) }.getOrNull()) }
    var message by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || schema == null) return@rememberLauncherForActivityResult
        message = runCatching {
            val data = JSONObject(ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            val bad = ProfileRecord.validate(schema, data)
            if (bad.isEmpty()) { ProfileStore.save(ctx, store, data); values = data; "Imported." } else "Not imported: " + bad.take(3).joinToString("; ")
        }.getOrElse { "Not imported: unreadable file" }
    }
    if (schema == null) { PNote("Schema missing", "The ${o.optString("schema")} schema asset was not found."); return }
    val doc = ProfileRecord.merge(schema, values, LocalDate.now().toString())

    PCard {
        Text("${doc.title} · ${doc.percent}%", color = colorResource(R.color.me_text), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("${doc.filled} of ${doc.total} fields filled", color = colorResource(R.color.me_text_dim), fontSize = 13.sp)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProfileStatus.ALL.forEach { PChip(it, doc.count(it)) }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PAction("Import data") { picker.launch(arrayOf("application/json", "text/plain", "*/*")) }
            PAction("Export PDF") {
                val ok = HealthDossier.export(ctx, "${doc.title} ${LocalDate.now()}",
                    ProfileRecord.exportSections(doc).map { (t, l) -> DossierSection(t, t, l) }, ProfileRecord.pdfName(doc))
                if (!ok) message = "No PDF reader answered; install Cloud Drive."
            }
        }
        if (message.isNotBlank()) Text(message, color = colorResource(R.color.me_text_dim), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
    doc.sections.forEach { s ->
        PCard {
            Text(s.title, color = colorResource(R.color.me_text), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            s.fields.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(f.label, color = colorResource(R.color.me_text_dim), fontSize = 12.sp)
                        if (f.filled) Text(f.value, color = colorResource(R.color.me_text), fontSize = 13.sp)
                        else Text(PENDING_TEXT, color = colorResource(R.color.me_text_dim), fontSize = 13.sp, fontStyle = FontStyle.Italic)
                    }
                    PChip(f.status, null)
                }
            }
        }
    }
}

private fun chipColor(status: String) = when (status) {
    ProfileStatus.VERIFIED -> Color(0xFF2E7D32)
    ProfileStatus.EXPIRED -> Color(0xFFC62828)
    ProfileStatus.DECLARED -> Color(0xFF1565C0)
    else -> Color(0xFF8D6E00)
}

@Composable
private fun PChip(status: String, count: Int?) {
    Text(status + (count?.let { " $it" } ?: ""), color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(chipColor(status)).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
private fun PAction(label: String, onTap: () -> Unit) =
    Text(label, color = colorResource(R.color.me_primary), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onTap))

@Composable
private fun PCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(colorResource(R.color.me_surface)).padding(14.dp),
    ) { content() }
}

@Composable
private fun PNote(title: String, body: String) = PCard {
    Text(title, color = colorResource(R.color.me_text), fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Text(body, color = colorResource(R.color.me_text_dim), fontSize = 13.sp)
}
