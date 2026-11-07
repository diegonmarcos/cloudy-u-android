package org.fossify.phone.sim

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import org.fossify.phone.helpers.rememberFleetDataset
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.superapp.themebg.ThemeColors
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreDot
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreQuick
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which SIM's sub-page the SIMs tab shows (null = the list); MainActivity's back clears it first. */
object SimsNavState {
    var selected: String? by mutableStateOf(null)
}

object SimSubPages {
    const val TAG_LIST_ROW = "dialer_sim_row:"
    const val TAG_DETAIL = "dialer_sim_detail"
    const val TAG_BACK = "dialer_sim_back"
    const val TAG_FIELD = "dialer_sim_field:"
    const val LABEL_WEIGHT = 0.4f
    const val CLIP_SENSITIVE = "android.content.extra.IS_SENSITIVE"
}

/** Long-press copy; a secret is flagged sensitive so the system hides it from the clipboard preview. */
fun copyToClipboard(ctx: Context, label: String, text: String, sensitive: Boolean) {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(SimSubPages.CLIP_SENSITIVE, true) }
    cm.setPrimaryClip(clip)
    Toast.makeText(ctx, "Copied $label", Toast.LENGTH_SHORT).show()
}

/** The SIMs tab: the list of slots and eSIM profiles, or one SIM's sub-page. Also Configs > SIM. */
@Composable
fun SimsOverviewPage(c: ThemeColors) = SimPermsGate(c) {
    val ctx = LocalContext.current
    val schema = rememberFleetDataset(SimSchema.DATASET) { SimSchema.parse(it) }
    val store = remember(schema) { schema?.let { SimManualStore(ctx, it) } }
    val data = rememberUsage()
    val sel = SimsNavState.selected
    val block = data.firstOrNull { SimFields.key(it.card) == sel }
    if (block != null && store != null && schema != null) SimDetailPage(block, store, schema, c) else SimListPage(data, c)
}

@Composable
private fun SimListPage(data: List<SimUsageBlock>, c: ThemeColors) {
    LazyColumn(Modifier.fillMaxSize().testTag(SimPages.TAG_SIMS)) {
        if (data.isEmpty()) item { KitStoreRowLine { KitStoreCaption("No SIM detected", color = c.inkDim) } }
        items(data, key = { SimFields.key(it.card) }) { b ->
            val card = b.card
            val key = SimFields.key(card)
            KitStoreRowLine(Modifier.testTag(SimSubPages.TAG_LIST_ROW + key), onClick = { SimsNavState.selected = key }) {
                KitStoreDot(if (card.active) "✓" else "◯", if (card.active) KitStoreRow.UP else KitStoreRow.MISSING)
                KitStoreName(SimFields.title(card), Modifier.weight(1f), color = c.ink)
                KitStoreCaption(listOfNotNull(
                    card.carrier, card.iccid?.takeLast(4)?.let { "…$it" },
                    if (card.active) null else if (card.subscriptionId == null) "empty" else "inactive",
                ).joinToString(" · "), color = c.inkDim)
                KitStoreCaption("  ›", color = c.inkDim)
            }
        }
        item { Spacer(Modifier.height(KitStoreRow.step(96))) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SimLine(tag: String, label: String, value: String, c: ThemeColors, onClick: (() -> Unit)?, onCopy: () -> Unit, trailing: @Composable () -> Unit = {}) {
    KitStoreRowLine(Modifier.testTag(tag).combinedClickable(onClick = onClick ?: {}, onLongClick = onCopy)) {
        KitStoreCaption(label, Modifier.weight(SimSubPages.LABEL_WEIGHT), color = c.inkDim)
        Text(value, Modifier.weight(1f - SimSubPages.LABEL_WEIGHT), color = c.ink, fontSize = KitStoreRow.body, maxLines = 4, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

@Composable
private fun SimEditor(f: SimField, initial: String, c: ThemeColors, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember(f.id) { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight)) {
        if (f.kind == "choice") {
            (f.options + "").forEach { o ->
                KitStoreRowLine(onClick = { onSave(o) }) { KitStoreName(o.ifEmpty { "Clear" }, Modifier.weight(1f), color = if (o == initial) c.accent else c.ink) }
            }
        } else {
            OutlinedTextField(
                text, { text = it }, Modifier.fillMaxWidth(), label = { KitStoreCaption(f.label) },
                textStyle = TextStyle(fontSize = KitStoreRow.body, color = c.ink), singleLine = f.kind != "multiline",
                keyboardOptions = KeyboardOptions(keyboardType = if (f.kind == "number") KeyboardType.Number else KeyboardType.Text),
            )
            KitStoreRowLine {
                KitStoreQuick("Save", KitStoreRow.BLUE_FILL, onClick = { onSave(text) })
                Spacer(Modifier.padding(horizontal = KitStoreRow.tight))
                KitStoreQuick("Cancel", KitStoreRow.RULE, onClick = onCancel)
            }
        }
    }
}

/** One SIM or eSIM profile: what Android detects, then the declared editable fields; long-press copies any row. */
@Composable
private fun SimDetailPage(b: SimUsageBlock, store: SimManualStore, schema: SimSchema, c: ThemeColors) {
    val ctx = LocalContext.current
    val card = b.card
    val key = SimFields.key(card)
    var manual by remember(key) { mutableStateOf(b.manual) }
    var editing by remember(key) { mutableStateOf<String?>(null) }
    var revealed by remember(key) { mutableStateOf(setOf<String>()) }
    var secrets by remember(key) { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var note by remember(key) { mutableStateOf("") }
    LaunchedEffect(key) {
        secrets = withContext(Dispatchers.IO) { schema.secretIds.associateWith { store.secret(key, it) } }
    }
    val shown = SimFields.detected(card, manual)
    val copyable = SimFields.detected(card, manual, reveal = true)
    LazyColumn(Modifier.fillMaxSize().testTag(SimSubPages.TAG_DETAIL)) {
        item {
            KitStoreRowLine(Modifier.testTag(SimSubPages.TAG_BACK), onClick = { SimsNavState.selected = null }) {
                KitStoreName("‹ SIMs", color = c.accent)
                KitStoreName("  " + SimFields.title(card), Modifier.weight(1f), color = c.ink)
            }
        }
        item { KitStoreRowLine { KitStoreCaption("DETECTED", color = c.accent) } }
        items(shown.size, key = { "d_" + shown[it].label }) { i ->
            SimLine(SimSubPages.TAG_FIELD + "detected:" + shown[i].label, shown[i].label, shown[i].value, c, null,
                onCopy = { copyToClipboard(ctx, shown[i].label, copyable[i].value, false) })
        }
        schema.groups.forEach { g ->
            item(key = "g_" + g.id) { KitStoreRowLine { KitStoreCaption(g.label.uppercase(), color = c.accent) } }
            items(schema.inGroup(g.id), key = { "f_" + it.id }) { f ->
                val secret = f.secret
                val stored: String? = if (secret) secrets[f.id] else manual[f.id]
                val value = when {
                    secret && stored == null -> schema.vault.unavailable
                    stored.isNullOrEmpty() -> "-"
                    secret && f.id !in revealed -> SimMask.secret(stored)
                    else -> stored.orEmpty()
                }
                val tag = SimSubPages.TAG_FIELD + f.id
                if (editing == f.id) {
                    SimEditor(f, stored.orEmpty(), c, onCancel = { editing = null }, onSave = { v ->
                        editing = null
                        if (store.save(key, f.id, v)) {
                            if (secret) secrets = secrets + (f.id to v) else manual = SimManual(manual.values + (f.id to v))
                        } else note = "Not saved: " + schema.vault.unavailable
                    })
                } else {
                    SimLine(tag, f.label, value, c,
                        onClick = if (secret) ({ revealed = if (f.id in revealed) revealed - f.id else revealed + f.id }) else ({ editing = f.id }),
                        onCopy = { if (!stored.isNullOrEmpty()) copyToClipboard(ctx, f.label, stored, secret) },
                        trailing = {
                            if (secret && stored != null) KitStoreQuick("Edit", KitStoreRow.BLUE_FILL, onClick = { editing = f.id })
                        })
                }
            }
        }
        if (note.isNotEmpty()) item { KitStoreRowLine { KitStoreCaption(note, color = KitStoreRow.BAD) } }
        if (card.active) item(key = "usage") { UsageCard(b, c) }
        item { Spacer(Modifier.height(KitStoreRow.step(96))) }
    }
}
