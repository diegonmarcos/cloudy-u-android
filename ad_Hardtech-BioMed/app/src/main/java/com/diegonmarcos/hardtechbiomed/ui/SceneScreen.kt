package com.diegonmarcos.hardtechbiomed.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.hardtechbiomed.BioContent
import com.diegonmarcos.hardtechbiomed.BioParse
import com.diegonmarcos.hardtechbiomed.BioStore
import com.diegonmarcos.hardtechbiomed.BuildConfig
import com.diegonmarcos.hardtechbiomed.Entry
import com.diegonmarcos.hardtechbiomed.SceneTab
import com.diegonmarcos.hardtechbiomed.Topic
import com.diegonmarcos.superapp.uikit.GroupTitle
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The type scale of the scene pages: the kit ramp at its readable step (as Hardtech-CAD's CadType), colour only from the kit palette. */
object BioType {
    val title = KitDensity.head * 1.2f
    val body = KitDensity.body * 1.5f
    val label = KitDensity.title * 1.2f
    val caption = KitDensity.caption * 1.4f
    val tight = KitDensity.small
    val gap = KitDensity.medium
}

private sealed class Load {
    data object Reading : Load()
    data class Ready(val content: BioContent) : Load()
    data object Unavailable : Load()
}

/**
 * One tab of Hardtech-BioMed (a `scene` entry): the 3D model of the tab, its layers (show / hide a body system), and its topics grouped as chips.
 * A topic lights its parts on the model (the rest dims) and frames them; a tap on the model names the part under the finger and lights it.
 * Everything taught comes from the served content (data/fleet/biomed/content.json), read at run time with an on-device cache.
 */
@Composable
fun SceneScreen(entry: Entry) {
    val ctx = LocalContext.current
    val dir = remember { File(ctx.cacheDir, "biomed-models") }
    var load by remember { mutableStateOf<Load>(Load.Reading) }
    LaunchedEffect(Unit) {
        load = withContext(Dispatchers.IO) {
            val text = BioStore.contentText(BuildConfig.MODELS_BASE, dir)
            val c = text?.let { BioParse.parse(it) }
            if (c == null) Load.Unavailable else Load.Ready(c)
        }
    }
    val p = LocalKitPalette.current
    when (val l = load) {
        Load.Reading -> Text("Reading the model data…", color = p.textSecondary, fontSize = BioType.body, modifier = Modifier.padding(BioType.gap).testTag("scene_reading"))
        Load.Unavailable -> Text("The model data could not be read (offline and nothing cached yet). Open this tab once with a network connection.", color = p.warn, fontSize = BioType.body, modifier = Modifier.padding(BioType.gap).testTag("scene_unavailable"))
        is Load.Ready -> {
            val tab = l.content.tabs[entry.scene]
            val model = tab?.let { l.content.models[it.model] }
            if (tab == null || model == null) Text("This tab has no content in the served data.", color = p.warn, fontSize = BioType.body, modifier = Modifier.padding(BioType.gap).testTag("scene_missing"))
            else SceneBody(tab, model.file, model.sha256, model.id, model.licence, model.credit, l.content.notice, dir)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SceneBody(tab: SceneTab, file: String, sha: String, modelId: String, licence: String, credit: String, notice: String, dir: File) {
    val p = LocalKitPalette.current
    val ctl = remember(tab.id) { BioViewerController() }
    var topicId by rememberSaveable(tab.id) { mutableStateOf<String?>(null) }
    var picked by remember(tab.id) { mutableStateOf<Picked?>(null) }
    var hiddenLayers by rememberSaveable(tab.id) { mutableStateOf(tab.layers.filter { l -> l.match.any { it in tab.hidden } }.map { it.id }.toSet()) }
    val topic: Topic? = tab.groups.flatMap { it.topics }.firstOrNull { it.id == topicId }
    // apply the layers and the topic whenever the viewer has the model (and again when either changes)
    LaunchedEffect(ctl.shown, hiddenLayers) {
        if (!ctl.shown) return@LaunchedEffect
        tab.layers.forEach { l -> ctl.show(l.match, l.id !in hiddenLayers) }
        tab.hidden.filter { h -> tab.layers.none { l -> h in l.match } }.takeIf { it.isNotEmpty() }?.let { ctl.show(it, false) }
    }
    LaunchedEffect(ctl.shown, topicId, picked) {
        if (!ctl.shown) return@LaunchedEffect
        val pk = picked
        when {
            pk != null -> { ctl.highlight(listOf(pk.name)); }
            topic != null && topic.highlight.isNotEmpty() -> { ctl.highlight(topic.highlight, topic.isolate); ctl.focus(topic.highlight) }
            else -> { ctl.highlight(emptyList()); ctl.fit() }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("scene:" + tab.id), verticalArrangement = Arrangement.spacedBy(BioType.gap)) {
        Text(tab.title, color = p.textPrimary, fontSize = BioType.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("scene_title"))
        if (tab.intro.isNotBlank()) Text(tab.intro, color = p.textSecondary, fontSize = BioType.body, modifier = Modifier.testTag("scene_intro"))
        BioViewer(ctl, modelId, file, sha, dir, onPick = { pk -> picked = pk; if (pk != null) topicId = null })
        BioRenderLine(ctl)
        Controls(ctl)
        picked?.let { pk ->
            Text("Selected: " + pk.label + (if (pk.system.isNotBlank()) " (" + pk.system + ")" else ""), color = p.accent, fontSize = BioType.label, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { picked = null }.testTag("scene_picked"))
        }
        if (tab.layers.isNotEmpty()) {
            GroupTitle("Layers")
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BioType.tight), verticalArrangement = Arrangement.spacedBy(BioType.tight)) {
                tab.layers.forEach { l ->
                    val on = l.id !in hiddenLayers
                    Chip(l.label, on, Modifier.testTag("layer:" + l.id)) { hiddenLayers = if (on) hiddenLayers + l.id else hiddenLayers - l.id }
                }
            }
        }
        tab.groups.forEach { g ->
            if (g.label.isNotBlank()) GroupTitle(g.label)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BioType.tight), verticalArrangement = Arrangement.spacedBy(BioType.tight)) {
                g.topics.forEach { t -> Chip(t.title, t.id == topicId, Modifier.testTag("topic:" + t.id)) { picked = null; topicId = if (t.id == topicId) null else t.id } }
            }
        }
        topic?.let { t ->
            KitCard(Modifier.testTag("topic_card")) {
                Column(Modifier.padding(BioType.gap), verticalArrangement = Arrangement.spacedBy(BioType.tight)) {
                    Text(t.title, color = p.textPrimary, fontSize = BioType.title, fontWeight = FontWeight.SemiBold)
                    t.text.split("\n").filter { it.isNotBlank() }.forEach { Text(it, color = p.textPrimary, fontSize = BioType.body) }
                }
            }
        }
        Text("Model: $licence. $credit", color = p.textSecondary, fontSize = BioType.caption, modifier = Modifier.testTag("scene_credit"))
        if (notice.isNotBlank()) Text(notice, color = p.textSecondary, fontSize = BioType.caption, modifier = Modifier.testTag("scene_notice"))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(ctl: BioViewerController) {
    var wire by remember { mutableStateOf(false) }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BioType.tight), verticalArrangement = Arrangement.spacedBy(BioType.tight)) {
        Chip("Fit", false, Modifier.testTag("viewer_fit")) { ctl.fit() }
        Chip("Front", false, Modifier.testTag("viewer_view:front")) { ctl.view("front") }
        Chip("Side", false, Modifier.testTag("viewer_view:side")) { ctl.view("side") }
        Chip("Top", false, Modifier.testTag("viewer_view:top")) { ctl.view("top") }
        Chip("Reset", false, Modifier.testTag("viewer_reset")) { ctl.reset() }
        Chip("Wireframe", wire, Modifier.testTag("viewer_wire")) { wire = !wire; ctl.wire(wire) }
    }
    Text("One finger turns the model, two fingers move it, a pinch zooms, a tap names a part, a double tap fits.", color = LocalKitPalette.current.textSecondary, fontSize = BioType.caption)
}

@Composable
private fun Chip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(50)
    Box(modifier.heightIn(min = 36.dp).clip(shape).background(if (on) p.accent else p.surfaceSelected).border(1.dp, if (on) p.accent else p.hairline, shape)
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (on) p.tileInk else p.textPrimary, fontSize = BioType.label, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}
