package com.diegonmarcos.cloudlib.scanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudlib.scanner.R
import com.diegonmarcos.superapp.uikit.GroupTitle
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.LocalKitPalette

internal class ToolMeta(val id: ToolId, val icon: ImageVector, val title: Int, val sub: Int)

/** #1908 every tool of the hub, in its groups (this is the one list; the Tools screen and the parity test read it). */
internal val toolGroups: List<Pair<Int, List<ToolMeta>>> = listOf(
    R.string.scanner_grp_scan to listOf(
        ToolMeta(ToolId.CROP, Icons.Filled.Crop, R.string.scanner_t_crop, R.string.scanner_t_crop_sub),
        ToolMeta(ToolId.PERSPECTIVE, Icons.Filled.Transform, R.string.scanner_t_perspective, R.string.scanner_t_perspective_sub),
        ToolMeta(ToolId.FILTERS, Icons.Filled.Tune, R.string.scanner_t_filters, R.string.scanner_t_filters_sub),
        ToolMeta(ToolId.PAGE_OPS, Icons.Filled.Layers, R.string.scanner_t_page_ops, R.string.scanner_t_page_ops_sub),
        ToolMeta(ToolId.SCAN_PDF, Icons.Filled.PictureAsPdf, R.string.scanner_t_scan_pdf, R.string.scanner_t_scan_pdf_sub),
        ToolMeta(ToolId.OCR, Icons.Filled.TextFields, R.string.scanner_t_ocr, R.string.scanner_t_ocr_sub),
        ToolMeta(ToolId.EXPORT, Icons.Filled.Share, R.string.scanner_t_export, R.string.scanner_t_export_sub),
    ),
    R.string.scanner_grp_pdf to listOf(
        ToolMeta(ToolId.PDF_MERGE, Icons.Filled.MergeType, R.string.scanner_t_merge, R.string.scanner_t_merge_sub),
        ToolMeta(ToolId.PDF_SPLIT, Icons.Filled.CallSplit, R.string.scanner_t_split, R.string.scanner_t_split_sub),
        ToolMeta(ToolId.PDF_EXTRACT, Icons.Filled.ContentCut, R.string.scanner_t_extract, R.string.scanner_t_extract_sub),
        ToolMeta(ToolId.PDF_ROTATE, Icons.Filled.RotateRight, R.string.scanner_t_rotate, R.string.scanner_t_rotate_sub),
        ToolMeta(ToolId.PDF_COMPRESS, Icons.Filled.Compress, R.string.scanner_t_compress, R.string.scanner_t_compress_sub),
        ToolMeta(ToolId.PDF_PROTECT, Icons.Filled.Lock, R.string.scanner_t_protect, R.string.scanner_t_protect_sub),
        ToolMeta(ToolId.PDF_TO_IMAGES, Icons.Filled.Collections, R.string.scanner_t_pdf_images, R.string.scanner_t_pdf_images_sub),
        ToolMeta(ToolId.PDF_TO_PAGES, Icons.Filled.FileCopy, R.string.scanner_t_pdf_pages, R.string.scanner_t_pdf_pages_sub),
    ),
    R.string.scanner_grp_images to listOf(
        ToolMeta(ToolId.IMG_CONVERT, Icons.Filled.SwapHoriz, R.string.scanner_t_convert, R.string.scanner_t_convert_sub),
        ToolMeta(ToolId.IMG_TO_PDF, Icons.Filled.Image, R.string.scanner_t_img_pdf, R.string.scanner_t_img_pdf_sub),
    ),
)

/** #1908 the Tools hub: every tool of the scanner, grouped, one tap to open. */
@Composable
fun ToolsScreen(c: ScannerController) {
    val k = LocalKitPalette.current
    Column(Modifier.fillMaxSize().testTag(ScannerTags.TOOLS)) {
        ScannerTopBar(stringResource(R.string.scanner_tools), stringResource(R.string.scanner_tools_sub), { c.route = Route.HOME }, stringResource(R.string.scanner_back))
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize().padding(horizontal = KitDensity.small),
            contentPadding = PaddingValues(vertical = KitDensity.small), horizontalArrangement = Arrangement.spacedBy(KitDensity.small), verticalArrangement = Arrangement.spacedBy(KitDensity.small),
        ) {
            for ((group, tools) in toolGroups) {
                item(span = { GridItemSpan(maxLineSpan) }) { GroupTitle(stringResource(group)) }
                for (t in tools) item {
                    Row(
                        Modifier.testTag(ScannerTags.tool(t.id.id)).fillMaxWidth().clip(RoundedCornerShape(KitDensity.corner)).background(k.surface).clickable { c.openTool(t.id) }.padding(KitDensity.medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(t.icon, contentDescription = null, tint = k.accent, modifier = Modifier.size(26.dp))
                        Column(Modifier.weight(1f).padding(start = KitDensity.medium)) {
                            Text(stringResource(t.title), color = k.textPrimary, fontSize = KitDensity.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(t.sub), color = k.textSecondary, fontSize = KitDensity.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
