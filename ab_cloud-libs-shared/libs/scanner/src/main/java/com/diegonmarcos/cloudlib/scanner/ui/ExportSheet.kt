package com.diegonmarcos.cloudlib.scanner.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudlib.scanner.ExportFormat
import com.diegonmarcos.cloudlib.scanner.NameTemplate
import com.diegonmarcos.cloudlib.scanner.Orientation
import com.diegonmarcos.cloudlib.scanner.PageSize
import com.diegonmarcos.cloudlib.scanner.R
import com.diegonmarcos.cloudlib.scanner.ScannerExport
import com.diegonmarcos.superapp.uikit.GroupTitle
import com.diegonmarcos.superapp.uikit.KitButton
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitOutlinedButton
import com.diegonmarcos.superapp.uikit.KitSegmented
import com.diegonmarcos.superapp.uikit.KitTonalButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.time.LocalDateTime

private val sizes = listOf(
    PageSize.A4 to R.string.scanner_s_a4, PageSize.LETTER to R.string.scanner_s_letter, PageSize.LEGAL to R.string.scanner_s_legal,
    PageSize.ID_CARD to R.string.scanner_s_id, PageSize.RECEIPT to R.string.scanner_s_receipt, PageSize.BUSINESS_CARD to R.string.scanner_s_card,
    PageSize.ORIGINAL to R.string.scanner_s_original,
)

/**
 * #1908 the export sheet, over any screen: which pages, the format (PDF searchable, JPEG, PNG, WebP, text), the sheet size,
 * orientation and margin, quality, DPI and a size ceiling, the file name with its naming template, and the destination (Files
 * and Cloud Drive through the system picker, or share to any app).
 */
@Composable
fun ExportSheet(c: ScannerController) {
    val k = LocalKitPalette.current
    val o = c.options
    fun set(n: com.diegonmarcos.cloudlib.scanner.ExportOptions) { c.options = n }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { c.showExport = false }.testTag(ScannerTags.EXPORT), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(k.surface).clickable(enabled = false) {}) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(KitDensity.medium),
            verticalArrangement = Arrangement.spacedBy(KitDensity.small),
        ) {
            Text(stringResource(R.string.scanner_export), color = k.textPrimary, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            val n = c.exportPages().size
            Text(stringResource(R.string.scanner_export_n, n), color = k.textSecondary, fontSize = KitDensity.caption)
            KitSegmented(
                listOf("all" to stringResource(R.string.scanner_scope_all), "current" to stringResource(R.string.scanner_scope_current), "from" to stringResource(R.string.scanner_scope_from)),
                when (c.scope) { Scope.ALL -> "all"; Scope.CURRENT -> "current"; Scope.FROM_HERE -> "from" },
                { id -> c.scope = when (id) { "current" -> Scope.CURRENT; "from" -> Scope.FROM_HERE; else -> Scope.ALL } }, tag = "scanner:scope",
            )
            GroupTitle(stringResource(R.string.scanner_format))
            KitSegmented(ExportFormat.values().map { it.name to (if (it == ExportFormat.TXT) "TXT" else it.name) }, o.format.name,
                { id -> set(o.copy(format = ExportFormat.valueOf(id))) }, tag = "scanner:format")
            GroupTitle(stringResource(R.string.scanner_file_name))
            OutlinedTextField(o.name, { set(o.copy(name = it)) }, Modifier.fillMaxWidth().testTag("scanner:file_name"), singleLine = true,
                label = { Text(stringResource(R.string.scanner_file_name)) })
            LazyRow(horizontalArrangement = Arrangement.spacedBy(KitDensity.small), contentPadding = PaddingValues(vertical = KitDensity.small / 2)) {
                for (tok in NameTemplate.TOKENS) item {
                    FilterChip(false, { set(o.copy(template = (o.template.ifBlank { "" }) + tok)) }, label = { Text(tok, fontSize = KitDensity.caption) }, modifier = Modifier.testTag("scanner:token:$tok"))
                }
                item { FilterChip(false, { set(o.copy(template = "")) }, label = { Text(stringResource(R.string.scanner_clear), fontSize = KitDensity.caption) }) }
            }
            OutlinedTextField(o.template, { set(o.copy(template = it)) }, Modifier.fillMaxWidth().testTag("scanner:template"), singleLine = true,
                label = { Text(stringResource(R.string.scanner_template)) }, placeholder = { Text(NameTemplate.DEFAULT) })
            Text(stringResource(R.string.scanner_file_will_be, ScannerExport.baseName(o, 1, LocalDateTime.now()) + "." + o.format.ext), color = k.accent, fontSize = KitDensity.caption,
                modifier = Modifier.testTag("scanner:file_preview"))
            if (o.format == ExportFormat.PDF) {
                GroupTitle(stringResource(R.string.scanner_page_size))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(KitDensity.small), contentPadding = PaddingValues(vertical = KitDensity.small / 2)) {
                    for ((z, label) in sizes) item {
                        FilterChip(o.size == z, { set(o.copy(size = z)) }, label = { Text(stringResource(label), fontSize = KitDensity.caption) }, modifier = Modifier.testTag("scanner:size:${z.id}"))
                    }
                }
                KitSegmented(listOf("auto" to stringResource(R.string.scanner_o_auto), "portrait" to stringResource(R.string.scanner_o_portrait), "landscape" to stringResource(R.string.scanner_o_landscape)),
                    o.orientation.name.lowercase(), { id -> set(o.copy(orientation = Orientation.valueOf(id.uppercase()))) }, tag = "scanner:orientation")
                Text(stringResource(R.string.scanner_margin, o.marginMm.toInt()), color = k.textSecondary, fontSize = KitDensity.caption)
                Slider(o.marginMm.toFloat(), { set(o.copy(marginMm = it.toInt().toDouble())) }, valueRange = 0f..30f, modifier = Modifier.testTag("scanner:slider:margin"))
            }
            if (o.format != ExportFormat.PNG && o.format != ExportFormat.TXT) {
                Text(stringResource(R.string.scanner_quality, o.quality), color = k.textSecondary, fontSize = KitDensity.caption)
                Slider(o.quality.toFloat(), { set(o.copy(quality = it.toInt())) }, valueRange = 10f..100f, modifier = Modifier.testTag("scanner:slider:export_quality"))
            }
            if (o.format != ExportFormat.TXT) {
                Text(stringResource(R.string.scanner_target_size), color = k.textSecondary, fontSize = KitDensity.caption)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                    for (b in listOf(0, 500_000, 1_000_000, 2_000_000, 5_000_000)) item {
                        FilterChip(o.maxBytes == b, { set(o.copy(maxBytes = b)) }, label = { Text(if (b == 0) "-" else "${b / 1000} KB", fontSize = KitDensity.caption) })
                    }
                }
            }
            if (o.format == ExportFormat.JPEG || o.format == ExportFormat.PNG || o.format == ExportFormat.WEBP) {
                Text(stringResource(R.string.scanner_dpi), color = k.textSecondary, fontSize = KitDensity.caption)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                    for (d in listOf(0, 72, 150, 300, 600)) item {
                        FilterChip(o.dpi == d, { set(o.copy(dpi = d)) }, label = { Text(if (d == 0) "-" else "$d", fontSize = KitDensity.caption) })
                    }
                }
            }
        }
        // pinned: the destination stays in reach however long the options are
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(0.dp)).background(k.surface).padding(KitDensity.medium)) {
            GroupTitle(stringResource(R.string.scanner_destination))
            Row(horizontalArrangement = Arrangement.spacedBy(KitDensity.small), modifier = Modifier.fillMaxWidth()) {
                KitButton({ c.export(share = false) }, Modifier.weight(1f).testTag("scanner:save"), enabled = c.busy.isEmpty()) { Text(stringResource(R.string.scanner_save_files)) }
                KitTonalButton({ c.export(share = true) }, Modifier.weight(1f).testTag("scanner:share"), enabled = c.busy.isEmpty()) { Text(stringResource(R.string.scanner_share)) }
                KitOutlinedButton({ c.showExport = false }, Modifier.testTag("scanner:export_close")) { Text(stringResource(R.string.scanner_close)) }
            }
        }
        }
    }
}
