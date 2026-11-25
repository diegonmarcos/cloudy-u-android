package com.diegonmarcos.cloudlib.scanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudlib.scanner.ImageFormat
import com.diegonmarcos.cloudlib.scanner.ImageTools
import com.diegonmarcos.cloudlib.scanner.PageSize
import com.diegonmarcos.cloudlib.scanner.R
import com.diegonmarcos.superapp.uikit.GroupTitle
import com.diegonmarcos.superapp.uikit.KitButton
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitOutlinedButton
import com.diegonmarcos.superapp.uikit.KitSegmented
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitSwitchRow
import com.diegonmarcos.superapp.uikit.KitTonalButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #1908 one file tool's screen: pick the input (PDFs or pictures), set its few parameters, run, then save or share the result.
 * One layout for merge, split, extract, rotate, compress, protect, PDF to images, PDF to pages, the image converter and image to PDF.
 */
@Composable
fun ToolScreen(c: ScannerController) {
    val t = c.tool
    if (t == null) { androidx.compose.runtime.SideEffect { c.route = Route.TOOLS }; return }
    val meta = toolGroups.flatMap { it.second }.first { it.id == t }
    val k = LocalKitPalette.current
    val p = c.params
    val imageTool = t == ToolId.IMG_CONVERT || t == ToolId.IMG_TO_PDF
    Column(Modifier.fillMaxSize().testTag(ScannerTags.TOOL)) {
        ScannerTopBar(stringResource(meta.title), stringResource(meta.sub), { c.route = Route.TOOLS }, stringResource(R.string.scanner_back))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = KitDensity.medium), verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
            GroupTitle(stringResource(R.string.scanner_input))
            Row(horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                KitButton({ if (imageTool) c.pickImagesForTool() else c.pickPdfs() }, Modifier.testTag("scanner:pick")) {
                    Text(stringResource(if (imageTool) R.string.scanner_pick_images else if (t == ToolId.PDF_MERGE) R.string.scanner_pick_pdfs else R.string.scanner_pick_pdf))
                }
                if (c.inputs.isNotEmpty() || c.imageUris.isNotEmpty()) KitOutlinedButton({ c.inputs = emptyList(); c.imageUris = emptyList(); c.results = emptyList() }) { Text(stringResource(R.string.scanner_clear)) }
            }
            for (i in c.inputs) KitListRow(i.name, secondary = stringResource(R.string.scanner_n_pages_kb, i.pages, i.bytes.size / 1024), leading = "PDF", tag = "in:${i.name}")
            for (u in c.imageUris) KitListRow(c.displayName(u), leading = "IMG", tag = "in:$u")

            val needsInput = if (imageTool) c.imageUris.isNotEmpty() else c.inputs.isNotEmpty()
            if (needsInput) {
                GroupTitle(stringResource(R.string.scanner_options))
                when (t) {
                    ToolId.PDF_SPLIT -> RangesField(c, R.string.scanner_ranges_split_hint)
                    ToolId.PDF_EXTRACT -> RangesField(c, R.string.scanner_ranges_hint)
                    ToolId.PDF_ROTATE -> {
                        RangesField(c, R.string.scanner_ranges_all_hint)
                        KitSegmented(listOf("90" to "90", "180" to "180", "270" to "270"), p.degrees.toString(), { c.params = p.copy(degrees = it.toInt()) }, tag = "scanner:degrees")
                    }
                    ToolId.PDF_COMPRESS -> {
                        Text(stringResource(R.string.scanner_quality, p.quality), color = k.textSecondary, fontSize = KitDensity.caption)
                        Slider(p.quality.toFloat(), { c.params = p.copy(quality = it.toInt()) }, valueRange = 10f..95f, modifier = Modifier.testTag("scanner:slider:quality"))
                        Text(stringResource(R.string.scanner_max_side, p.maxSide), color = k.textSecondary, fontSize = KitDensity.caption)
                        Slider(p.maxSide.toFloat(), { c.params = p.copy(maxSide = (it / 100).toInt() * 100) }, valueRange = 600f..3000f, modifier = Modifier.testTag("scanner:slider:maxside"))
                    }
                    ToolId.PDF_PROTECT -> {
                        KitSwitchRow(stringResource(R.string.scanner_remove_password), "", p.removePassword, { c.params = p.copy(removePassword = it) })
                        OutlinedTextField(p.password, { c.params = p.copy(password = it) }, Modifier.fillMaxWidth().testTag("scanner:password"), singleLine = true,
                            label = { Text(stringResource(R.string.scanner_password)) }, visualTransformation = PasswordVisualTransformation())
                    }
                    ToolId.PDF_TO_IMAGES -> {
                        KitSegmented(listOf("png" to "PNG", "jpeg" to "JPEG", "webp" to "WEBP"), p.imageFormat.id, { id -> c.params = p.copy(imageFormat = ImageFormat.values().first { f -> f.id == id }) }, tag = "scanner:img_format")
                        Chips(listOf(800, 1240, 1654, 2480), p.widthPx, { "$it px" }) { c.params = p.copy(widthPx = it) }
                    }
                    ToolId.IMG_CONVERT -> ConvertOptions(c)
                    ToolId.IMG_TO_PDF -> Chips(listOf(PageSize.A4, PageSize.LETTER, PageSize.LEGAL, PageSize.ORIGINAL), p.size, { it.id }) { c.params = p.copy(size = it) }
                    else -> {}
                }
                Row(horizontalArrangement = Arrangement.spacedBy(KitDensity.small), modifier = Modifier.padding(top = KitDensity.small)) {
                    if (t == ToolId.PDF_TO_PAGES) KitButton({ c.pdfToPages() }, Modifier.testTag("scanner:run")) { Text(stringResource(R.string.scanner_run)) }
                    else KitButton({ c.runTool() }, Modifier.testTag("scanner:run"), enabled = c.busy.isEmpty() && !(t == ToolId.PDF_PROTECT && p.password.isEmpty())) { Text(stringResource(R.string.scanner_run)) }
                }
            }
            if (c.results.isNotEmpty()) {
                GroupTitle(stringResource(R.string.scanner_result))
                if (c.resultNote.isNotBlank()) Text(c.resultNote, color = k.accent, fontSize = KitDensity.body)
                for (f in c.results) KitListRow(f.name, secondary = "${f.bytes.size / 1024} KB", leading = f.name.substringAfterLast('.').uppercase(), tag = "out:${f.name}")
                Row(horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                    KitButton({ c.onSave(c.results) }, Modifier.testTag("scanner:result_save")) { Text(stringResource(R.string.scanner_save_files)) }
                    KitTonalButton({ c.onShare(c.results) }, Modifier.testTag("scanner:result_share")) { Text(stringResource(R.string.scanner_share)) }
                }
            }
        }
    }
}

@Composable
private fun RangesField(c: ScannerController, hint: Int) {
    OutlinedTextField(c.params.rangesText, { c.params = c.params.copy(rangesText = it) }, Modifier.fillMaxWidth().testTag("scanner:ranges"), singleLine = true,
        label = { Text(stringResource(R.string.scanner_pages_field)) }, placeholder = { Text(stringResource(hint)) })
}

@Composable
private fun <T> Chips(values: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(KitDensity.small), contentPadding = PaddingValues(vertical = KitDensity.small / 2)) {
        for (v in values) item { FilterChip(selected == v, { onPick(v) }, label = { Text(label(v), fontSize = KitDensity.caption) }) }
    }
}

/** The image converter's options: format, quality, longest side, DPI, grayscale, size ceiling. */
@Composable
private fun ConvertOptions(c: ScannerController) {
    val k = LocalKitPalette.current
    val cv = c.params.convert
    fun set(n: ImageTools.Convert) { c.params = c.params.copy(convert = n) }
    KitSegmented(ImageFormat.values().map { it.id to it.ext.uppercase() }, cv.format.id, { id -> set(cv.copy(format = ImageFormat.values().first { f -> f.id == id })) }, tag = "scanner:conv_format")
    Text(stringResource(R.string.scanner_quality, cv.quality), color = k.textSecondary, fontSize = KitDensity.caption)
    Slider(cv.quality.toFloat(), { set(cv.copy(quality = it.toInt())) }, valueRange = 10f..100f, modifier = Modifier.testTag("scanner:slider:conv_quality"))
    Text(stringResource(R.string.scanner_longest_side), color = k.textSecondary, fontSize = KitDensity.caption)
    Chips(listOf(0, 800, 1280, 1920, 3000), cv.maxSide, { if (it == 0) "-" else "$it px" }) { set(cv.copy(maxSide = it)) }
    Text(stringResource(R.string.scanner_dpi), color = k.textSecondary, fontSize = KitDensity.caption)
    Chips(listOf(0, 72, 150, 300, 600), cv.dpi, { if (it == 0) "-" else "$it" }) { set(cv.copy(dpi = it)) }
    Text(stringResource(R.string.scanner_target_size), color = k.textSecondary, fontSize = KitDensity.caption)
    Chips(listOf(0, 200_000, 500_000, 1_000_000, 2_000_000), cv.maxBytes, { if (it == 0) "-" else "${it / 1000} KB" }) { set(cv.copy(maxBytes = it)) }
    KitSwitchRow(stringResource(R.string.scanner_grayscale), "", cv.gray, { set(cv.copy(gray = it)) })
}
