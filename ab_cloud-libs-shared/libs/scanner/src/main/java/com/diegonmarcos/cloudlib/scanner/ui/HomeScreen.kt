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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudlib.scanner.R
import com.diegonmarcos.superapp.uikit.KitButton
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitOutlinedButton
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitTonalButton
import com.diegonmarcos.superapp.uikit.GroupTitle
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #1908 the review screen (what the Scanner tab opens on): the scan in hand as a large preview, the review strip of its pages
 * with the page operations (edit, retake, insert, duplicate, move, split, delete), and the way to capture, import, open the
 * Tools hub and export. With no page yet it is the start screen: scan, import, tools.
 */
@Composable
fun HomeScreen(c: ScannerController) {
    val k = LocalKitPalette.current
    Column(Modifier.fillMaxSize().testTag(ScannerTags.HOME)) {
        ScannerTopBar(
            title = stringResource(R.string.scanner_title),
            caption = if (c.pages.isEmpty()) "" else stringResource(R.string.scanner_pages_count, c.pages.size),
            onBack = { c.host.close() }, backLabel = stringResource(R.string.scanner_back),
        ) {
            IconTile(Icons.Filled.Build, stringResource(R.string.scanner_tools), { c.route = Route.TOOLS }, tag = ScannerTags.action("tools"))
            if (c.pages.isNotEmpty()) IconTile(Icons.Filled.Share, stringResource(R.string.scanner_export), { c.showExport = true }, tag = ScannerTags.action("export"))
        }
        if (c.pages.isEmpty()) StartPane(c, Modifier.weight(1f)) else ReviewPane(c, Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().background(k.surface).padding(KitDensity.small),
            horizontalArrangement = Arrangement.spacedBy(KitDensity.small), verticalAlignment = Alignment.CenterVertically,
        ) {
            KitButton({ c.target = ScannerController.Target.Append; c.route = Route.CAPTURE }, Modifier.weight(1f).testTag("scanner:camera")) {
                Icon(Icons.Filled.PhotoCamera, null, Modifier.size(16.dp)); Text(" " + stringResource(R.string.scanner_camera))
            }
            KitTonalButton({ c.target = ScannerController.Target.Append; c.pickImages() }, Modifier.weight(1f).testTag("scanner:import")) {
                Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(16.dp)); Text(" " + stringResource(R.string.scanner_import))
            }
            if (c.pages.isNotEmpty()) KitTonalButton({ c.tab = EditorTab.CROP; c.route = Route.EDITOR }, Modifier.weight(1f).testTag("scanner:edit")) {
                Icon(Icons.Filled.Crop, null, Modifier.size(16.dp)); Text(" " + stringResource(R.string.scanner_edit))
            }
        }
    }
}

@Composable
private fun StartPane(c: ScannerController, modifier: Modifier) {
    val k = LocalKitPalette.current
    Column(modifier.fillMaxWidth().padding(KitDensity.large), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.scanner_start_title), color = k.textPrimary, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(R.string.scanner_empty), color = k.textSecondary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = KitDensity.small, bottom = KitDensity.large))
        Row(horizontalArrangement = Arrangement.spacedBy(KitDensity.medium)) {
            StartTile(Icons.Filled.PhotoCamera, stringResource(R.string.scanner_start_scan), "scanner:start_scan") { c.target = ScannerController.Target.Append; c.route = Route.CAPTURE }
            StartTile(Icons.Filled.PhotoLibrary, stringResource(R.string.scanner_start_import), "scanner:start_import") { c.target = ScannerController.Target.Append; c.pickImages() }
            StartTile(Icons.Filled.Build, stringResource(R.string.scanner_tools), "scanner:start_tools") { c.route = Route.TOOLS }
        }
    }
}

@Composable
private fun StartTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tag: String, onClick: () -> Unit) {
    val k = LocalKitPalette.current
    Column(
        Modifier.testTag(tag).size(width = 96.dp, height = 96.dp).clip(RoundedCornerShape(KitDensity.corner)).background(k.surface).clickable(onClick = onClick).padding(KitDensity.small),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = k.accent, modifier = Modifier.size(30.dp))
        Text(label, color = k.textPrimary, fontSize = KitDensity.body, textAlign = TextAlign.Center, modifier = Modifier.padding(top = KitDensity.small))
    }
}

@Composable
private fun ReviewPane(c: ScannerController, modifier: Modifier) {
    val k = LocalKitPalette.current
    val cur = c.current()
    Column(modifier.fillMaxWidth()) {
        if (c.parked.isNotEmpty()) {
            KitStatusBanner(
                stringResource(R.string.scanner_parked, c.parked.size), KitState.IDLE, Modifier.padding(KitDensity.small),
                action = com.diegonmarcos.superapp.uikit.KitAction(stringResource(R.string.scanner_restore), "parked:restore") { c.restoreParked() },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(KitDensity.small), contentAlignment = Alignment.Center) {
            if (cur != null) {
                PageImage(c, cur, 1100, true, Modifier.fillMaxSize().clip(RoundedCornerShape(KitDensity.corner)).clickable { c.tab = EditorTab.CROP; c.route = Route.EDITOR }, ContentScale.Fit)
                Text(
                    stringResource(R.string.scanner_page_of, c.selected + 1, c.pages.size), color = k.textPrimary, fontSize = KitDensity.caption,
                    modifier = Modifier.align(Alignment.TopStart).padding(KitDensity.small).clip(RoundedCornerShape(KitDensity.corner)).background(k.surface).padding(horizontal = KitDensity.small, vertical = 2.dp),
                )
            }
        }
        // page operations on the selected page
        if (cur != null) {
            LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = KitDensity.small)) {
                item { IconTile(Icons.Filled.Crop, stringResource(R.string.scanner_edit), { c.tab = EditorTab.CROP; c.route = Route.EDITOR }, tag = ScannerTags.action("edit")) }
                item { IconTile(Icons.Filled.Refresh, stringResource(R.string.scanner_retake), { c.beginRetake(c.selected) }, tag = ScannerTags.action("retake")) }
                item { IconTile(Icons.Filled.Add, stringResource(R.string.scanner_insert), { c.beginInsertAfter(c.selected) }, tag = ScannerTags.action("insert")) }
                item { IconTile(Icons.Filled.ContentCopy, stringResource(R.string.scanner_duplicate), { c.duplicatePage(c.selected) }, tag = ScannerTags.action("duplicate")) }
                item { IconTile(Icons.Filled.ArrowBack, stringResource(R.string.scanner_move_left), { c.movePage(c.selected, c.selected - 1) }, tag = ScannerTags.action("move_left")) }
                item { IconTile(Icons.Filled.ArrowForward, stringResource(R.string.scanner_move_right), { c.movePage(c.selected, c.selected + 1) }, tag = ScannerTags.action("move_right")) }
                item { IconTile(Icons.Filled.CallSplit, stringResource(R.string.scanner_split), { c.splitAfter(c.selected) }, tag = ScannerTags.action("split")) }
                item { IconTile(Icons.Filled.Delete, stringResource(R.string.scanner_delete), { c.deletePage(c.selected) }, tag = ScannerTags.action("delete")) }
            }
        }
        // the review strip
        LazyRow(
            Modifier.fillMaxWidth().testTag(ScannerTags.STRIP).padding(vertical = KitDensity.small),
            contentPadding = PaddingValues(horizontal = KitDensity.small), horizontalArrangement = Arrangement.spacedBy(KitDensity.small),
        ) {
            itemsIndexed(c.pages) { i, p -> PageThumb(c, p, i, i == c.selected, { c.select(i) }) }
            item {
                Box(
                    Modifier.testTag("scanner:add_page").size(width = 56.dp, height = 76.dp).clip(RoundedCornerShape(8.dp)).background(k.surface)
                        .clickable { c.target = ScannerController.Target.Append; c.route = Route.CAPTURE },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.scanner_add_page), tint = k.accent) }
            }
        }
    }
}
