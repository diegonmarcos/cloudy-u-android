package com.diegonmarcos.cloudlib.scanner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudlib.scanner.CropPreset
import com.diegonmarcos.cloudlib.scanner.FilterMode
import com.diegonmarcos.cloudlib.scanner.FilterSpec
import com.diegonmarcos.cloudlib.scanner.OcrText
import com.diegonmarcos.cloudlib.scanner.R
import com.diegonmarcos.cloudlib.scanner.ScanPage
import com.diegonmarcos.superapp.uikit.KitButton
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitOutlinedButton
import com.diegonmarcos.superapp.uikit.KitSegmented
import com.diegonmarcos.superapp.uikit.KitSwitchRow
import com.diegonmarcos.superapp.uikit.KitTonalButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #1908 the per-page editor. Crop: the photo with draggable corner handles and a magnifier, the crop presets (free, original, ratios,
 * A4, Letter, Legal, ID, receipt, business card), auto edges, rotate, deskew (auto-straighten). Filter: a carousel of live previews
 * (original, grayscale, B&W, colour document, magic colour) with brightness, contrast and sharpness. Text: the on-device OCR of the page.
 */
@Composable
fun EditorScreen(c: ScannerController) {
    val k = LocalKitPalette.current
    val p = c.current()
    if (p == null) { androidx.compose.runtime.SideEffect { c.route = Route.HOME }; return }
    Column(Modifier.fillMaxSize().testTag(ScannerTags.EDITOR)) {
        ScannerTopBar(
            title = stringResource(R.string.scanner_edit),
            caption = stringResource(R.string.scanner_page_of, c.selected + 1, c.pages.size),
            onBack = { c.route = Route.HOME }, backLabel = stringResource(R.string.scanner_back),
        ) {
            IconTile(Icons.Filled.ChevronLeft, stringResource(R.string.scanner_prev_page), { c.select(c.selected - 1) }, tag = ScannerTags.action("prev"))
            IconTile(Icons.Filled.ChevronRight, stringResource(R.string.scanner_next_page), { c.select(c.selected + 1) }, tag = ScannerTags.action("next"))
            IconTile(Icons.Filled.Delete, stringResource(R.string.scanner_delete), { c.deletePage(c.selected) }, tag = ScannerTags.action("delete"))
            IconTile(Icons.Filled.Done, stringResource(R.string.scanner_done), { c.route = Route.HOME }, on = true, tag = ScannerTags.action("done"))
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(k.surface), contentAlignment = Alignment.Center) {
            when (c.tab) {
                EditorTab.CROP -> CropStage(c, p)
                EditorTab.FILTER -> PageImage(c, p, 1100, true, Modifier.fillMaxSize().padding(KitDensity.small), ContentScale.Fit)
                EditorTab.TEXT -> PageImage(c, p, 600, true, Modifier.fillMaxSize().padding(KitDensity.small), ContentScale.Fit)
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 250.dp).verticalScroll(rememberScrollState()).padding(vertical = KitDensity.small)) {
            when (c.tab) {
                EditorTab.CROP -> CropPanel(c, p)
                EditorTab.FILTER -> FilterPanel(c, p)
                EditorTab.TEXT -> TextPanel(c, p)
            }
        }
        KitSegmented(
            listOf("crop" to stringResource(R.string.scanner_crop), "filter" to stringResource(R.string.scanner_filters), "text" to stringResource(R.string.scanner_tab_ocr)),
            when (c.tab) { EditorTab.CROP -> "crop"; EditorTab.FILTER -> "filter"; EditorTab.TEXT -> "text" },
            { id -> c.tab = when (id) { "filter" -> EditorTab.FILTER; "text" -> EditorTab.TEXT; else -> EditorTab.CROP } },
            Modifier.padding(KitDensity.small), tag = "scanner:tabs",
        )
    }
}

@Composable
private fun CropStage(c: ScannerController, p: ScanPage) {
    CropCanvas(p.source, p.quad, { q -> c.setQuad(p, q) }, Modifier.fillMaxSize())
}

private fun presetLabel(id: String): Int = when (id) {
    "free" -> R.string.scanner_crop_free; "original" -> R.string.scanner_f_original; "1_1" -> R.string.scanner_crop_square
    "4_3" -> R.string.scanner_crop_4_3; "3_4" -> R.string.scanner_crop_3_4; "16_9" -> R.string.scanner_crop_16_9
    "a4" -> R.string.scanner_s_a4; "letter" -> R.string.scanner_s_letter; "legal" -> R.string.scanner_s_legal
    "id_card" -> R.string.scanner_s_id; "receipt" -> R.string.scanner_s_receipt; else -> R.string.scanner_s_card
}

@Composable
private fun CropPanel(c: ScannerController, p: ScanPage) {
    var selectedPreset by remember(p.id) { mutableStateOf(CropPreset.FREE) }
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = KitDensity.small), horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
        for (preset in CropPreset.values()) item {
            FilterChip(
                selected = selectedPreset == preset, onClick = { selectedPreset = preset; c.applyCrop(p, preset) },
                label = { Text(stringResource(presetLabel(preset.id)), fontSize = KitDensity.caption) }, modifier = Modifier.testTag(ScannerTags.crop(preset.id)),
            )
        }
    }
    LazyRow(Modifier.fillMaxWidth().padding(top = KitDensity.small), contentPadding = PaddingValues(horizontal = KitDensity.small)) {
        item { IconTile(Icons.Filled.AutoFixHigh, stringResource(R.string.scanner_auto_edges), { c.autoEdges(p) }, tag = ScannerTags.action("auto_edges")) }
        item { IconTile(Icons.Filled.CropFree, stringResource(R.string.scanner_reset_corners), { c.wholePhoto(p) }, tag = ScannerTags.action("whole_photo")) }
        item { IconTile(Icons.Filled.RotateLeft, stringResource(R.string.scanner_rotate_left), { c.rotate(p, 3); c.say(c.context.getString(R.string.scanner_rotated)) }, tag = ScannerTags.action("rotate_left")) }
        item { IconTile(Icons.Filled.RotateRight, stringResource(R.string.scanner_rotate_right), { c.rotate(p, 1); c.say(c.context.getString(R.string.scanner_rotated)) }, tag = ScannerTags.action("rotate_right")) }
        item { IconTile(Icons.Filled.Straighten, stringResource(R.string.scanner_deskew), { c.deskew(p) { d -> c.say(c.context.getString(R.string.scanner_deskewed, d)) } }, tag = ScannerTags.action("deskew")) }
        item { IconTile(Icons.Filled.Transform, stringResource(R.string.scanner_preview), { c.tab = EditorTab.FILTER }, tag = ScannerTags.action("preview")) }
    }
    Text(stringResource(R.string.scanner_frame_hint), color = LocalKitPalette.current.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.padding(horizontal = KitDensity.medium, vertical = KitDensity.small))
}

private val modes = listOf(
    FilterMode.ORIGINAL to R.string.scanner_f_original, FilterMode.GRAYSCALE to R.string.scanner_f_gray, FilterMode.BW to R.string.scanner_f_bw,
    FilterMode.COLOR_DOC to R.string.scanner_f_color, FilterMode.MAGIC to R.string.scanner_f_magic,
)

@Composable
private fun FilterPanel(c: ScannerController, p: ScanPage) {
    val k = LocalKitPalette.current
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = KitDensity.small), horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
        for ((mode, label) in modes) item {
            val spec = p.filter.copy(mode = mode)
            val on = p.filter.mode == mode
            val bmp by produceState<Bitmap?>(null, p.id, p.quad, p.quarters, p.deskew, spec) {
                value = withContext(Dispatchers.Default) { runCatching { c.filtered(p, spec, 160) }.getOrNull() }
            }
            Column(
                Modifier.testTag(ScannerTags.filter(mode.id)).clip(RoundedCornerShape(KitDensity.corner)).border(if (on) 2.dp else 1.dp, if (on) k.accent else k.hairline, RoundedCornerShape(KitDensity.corner))
                    .clickable { c.setFilter(p) { it.copy(mode = mode) } }.padding(KitDensity.small / 2),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val b = bmp
                if (b != null) Image(b.asImageBitmap(), null, Modifier.size(width = 64.dp, height = 84.dp), contentScale = ContentScale.Fit)
                else Box(Modifier.size(width = 64.dp, height = 84.dp).background(k.surfaceSelected))
                Text(stringResource(label), color = if (on) k.accent else k.textSecondary, fontSize = KitDensity.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    AdjustSlider(stringResource(R.string.scanner_brightness), p.filter.brightness, -100, 100, "brightness") { v -> c.setFilter(p) { it.copy(brightness = v) } }
    AdjustSlider(stringResource(R.string.scanner_contrast), p.filter.contrast, -100, 100, "contrast") { v -> c.setFilter(p) { it.copy(contrast = v) } }
    AdjustSlider(stringResource(R.string.scanner_sharpness), p.filter.sharpness, 0, 100, "sharpness") { v -> c.setFilter(p) { it.copy(sharpness = v) } }
    KitSwitchRow(stringResource(R.string.scanner_all_pages), "", c.applyAll, { on -> c.applyAll = on; if (on) c.pages.forEach { it.filter = p.filter; it.touch() }; c.bump() }, Modifier.padding(horizontal = KitDensity.medium).testTag("scanner:apply_all"))
}

/** A slider that re-renders only when released (the filters are per-pixel): the value shows live, the page follows on release. */
@Composable
private fun AdjustSlider(label: String, value: Int, min: Int, max: Int, tag: String, onSet: (Int) -> Unit) {
    var live by remember(value) { mutableStateOf(value.toFloat()) }
    val k = LocalKitPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = KitDensity.medium), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = k.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.size(width = 76.dp, height = 20.dp))
        Slider(live, { live = it }, Modifier.weight(1f).testTag("scanner:slider:$tag"), valueRange = min.toFloat()..max.toFloat(), onValueChangeFinished = { onSet(live.toInt()) })
        Text("${live.toInt()}", color = k.textPrimary, fontSize = KitDensity.caption, modifier = Modifier.size(width = 32.dp, height = 20.dp))
    }
}

@Composable
private fun TextPanel(c: ScannerController, p: ScanPage) {
    val k = LocalKitPalette.current
    val ctx = LocalContext.current
    var text by remember(p.id, p.ocrDone, c.rev) { mutableStateOf(p.text) }
    Row(Modifier.fillMaxWidth().padding(horizontal = KitDensity.small), horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
        KitButton({ c.runOcr(listOf(p)) }, Modifier.weight(1f).testTag("scanner:ocr_page")) { Text(stringResource(R.string.scanner_ocr_page)) }
        KitTonalButton({ c.runOcr(c.pages.toList()) }, Modifier.weight(1f).testTag("scanner:ocr_all")) { Text(stringResource(R.string.scanner_ocr_all)) }
    }
    OutlinedTextField(
        text, { text = it; c.setText(p, it) },
        Modifier.fillMaxWidth().padding(horizontal = KitDensity.small, vertical = KitDensity.small).heightIn(min = 96.dp).testTag("scanner:ocr_text"),
        placeholder = { Text(stringResource(R.string.scanner_ocr_hint), fontSize = KitDensity.body) },
        textStyle = MaterialTheme.typography.bodySmall.copy(color = k.textPrimary),
    )
    val (words, chars) = OcrText.counts(text)
    Text(stringResource(R.string.scanner_counts, words, chars), color = k.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.padding(horizontal = KitDensity.medium))
    Row(Modifier.fillMaxWidth().padding(KitDensity.small), horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
        KitOutlinedButton({
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("scan", text)); c.say(ctx.getString(R.string.scanner_copied))
        }, Modifier.weight(1f).testTag("scanner:copy_text")) { Text(stringResource(R.string.scanner_copy_text)) }
        KitOutlinedButton({
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), ctx.getString(R.string.scanner_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, Modifier.weight(1f).testTag("scanner:share_text")) { Text(stringResource(R.string.scanner_share_text)) }
    }
}
