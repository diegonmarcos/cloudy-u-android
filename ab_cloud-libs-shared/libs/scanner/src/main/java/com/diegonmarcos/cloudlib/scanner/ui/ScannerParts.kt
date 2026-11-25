package com.diegonmarcos.cloudlib.scanner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudlib.scanner.ScanPage
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Test tags of the Scanner screens: the UI tests and the emulator walk find controls by these. */
object ScannerTags {
    const val HOME = "scanner:home"
    const val CAPTURE = "scanner:capture"
    const val EDITOR = "scanner:editor"
    const val TOOLS = "scanner:tools"
    const val TOOL = "scanner:tool"
    const val EXPORT = "scanner:export"
    const val STRIP = "scanner:strip"
    fun page(i: Int) = "scanner:page:$i"
    fun tool(id: String) = "scanner:tool:$id"
    fun tab(id: String) = "scanner:tab:$id"
    fun filter(id: String) = "scanner:filter:$id"
    fun crop(id: String) = "scanner:crop:$id"
    fun action(id: String) = "scanner:action:$id"
}

/** A page drawn from the controller's caches (off the main thread), [filtered] = as exported, else the photo. */
@Composable
fun PageImage(c: ScannerController, p: ScanPage, side: Int, filtered: Boolean, modifier: Modifier = Modifier, fit: ContentScale = ContentScale.Fit) {
    val key = c.rev
    val bmp by produceState<Bitmap?>(null, p.id, key, side, filtered) {
        value = withContext(Dispatchers.Default) { runCatching { if (filtered) c.rendered(p, side) else p.previewSource() }.getOrNull() }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = fit)
    else Box(modifier.background(LocalKitPalette.current.surfaceSelected))
}

/** A round icon button on the kit's selected surface; [on] draws it lit. */
@Composable
fun IconTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false, tag: String = label) {
    val p = LocalKitPalette.current
    Column(
        modifier.testTag(tag).clip(RoundedCornerShape(KitDensity.corner)).clickable(onClick = onClick)
            .padding(horizontal = KitDensity.small, vertical = KitDensity.small / 2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(if (on) p.accent else p.surfaceSelected),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = label, tint = if (on) p.tileInk else p.textPrimary, modifier = Modifier.size(20.dp)) }
        Text(label, color = if (on) p.accent else p.textSecondary, fontSize = KitDensity.caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
    }
}

/** The top bar of every Scanner screen: back, title (and a caption), actions. */
@Composable
fun ScannerTopBar(title: String, caption: String, onBack: () -> Unit, backLabel: String, actions: @Composable () -> Unit = {}) {
    val p = LocalKitPalette.current
    Row(
        Modifier.fillMaxWidth().background(p.surface).padding(horizontal = KitDensity.small, vertical = KitDensity.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel, tint = p.textPrimary,
            modifier = Modifier.testTag("scanner:back").clip(CircleShape).clickable(onClick = onBack).padding(KitDensity.small).size(24.dp),
        )
        Column(Modifier.weight(1f).padding(start = KitDensity.small)) {
            Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (caption.isNotBlank()) Text(caption, color = p.textSecondary, fontSize = KitDensity.caption, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KitDensity.small / 2), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

/** A thin rule in the kit's hairline. */
@Composable
fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().size(KitDensity.rule).background(LocalKitPalette.current.hairline))
}

/** A page thumbnail in the review strip: its number, lit when selected. */
@Composable
fun PageThumb(c: ScannerController, p: ScanPage, index: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val k = LocalKitPalette.current
    Box(
        modifier.testTag(ScannerTags.page(index)).size(width = 56.dp, height = 76.dp).clip(RoundedCornerShape(8.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) k.accent else k.hairline, RoundedCornerShape(8.dp))
            .background(k.surface).clickable(onClick = onClick),
    ) {
        PageImage(c, p, 200, true, Modifier.size(width = 56.dp, height = 76.dp), ContentScale.Crop)
        Text(
            "${index + 1}", color = Color.White, fontSize = KitDensity.caption,
            modifier = Modifier.align(Alignment.BottomStart).padding(3.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xAA000000)).padding(horizontal = 5.dp),
        )
    }
}
