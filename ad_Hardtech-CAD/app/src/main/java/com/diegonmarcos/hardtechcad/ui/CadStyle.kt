package com.diegonmarcos.hardtechcad.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diegonmarcos.hardtechcad.R
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitWritingTags
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #1664 the type scale and the spacing of the MCAD pages (models table, cards, model page). The kit's dense Store ramp (8.6 to 11 sp) is a
 * list-row ramp: on a data page read on the phone it was micro-text. These sizes are the readable step of the same hierarchy (display > title >
 * body > label > caption, never below 12 sp); spacing is the kit's own steps (KitDensity) and colour is only the kit palette.
 */
object CadType {
    // the kit ramp, scaled to its readable step (never a free literal: the fleet scales from KitDensity)
    val display = KitDensity.display * 1.08f
    val title = KitDensity.head * 1.2f
    val body = KitDensity.body * 1.5f
    val label = KitDensity.title * 1.2f
    val caption = KitDensity.caption * 1.4f
    val gutter = KitDensity.large
    val gap = KitDensity.medium
    val tight = KitDensity.small
    val corner = 14.dp
    /** The viewer's own background (the colour every paint measurement compares to), also the thumbnail frame. */
    val stage = Color(0xFF0E1116)
}

/** A section heading of the MCAD pages: an eyebrow word in the accent and an optional count / hint on the right. */
@Composable
internal fun CadHeading(text: String, trailing: String = "", modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    androidx.compose.foundation.layout.Row(modifier.fillMaxWidth().padding(top = CadType.tight), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), color = p.textSecondary, fontSize = CadType.label, fontWeight = FontWeight.SemiBold, letterSpacing = KitDensity.eyebrowTracking, modifier = Modifier.weight(1f))
        if (trailing.isNotBlank()) Text(trailing, color = p.textSecondary, fontSize = CadType.label)
    }
}

/**
 * A one-of-few pick as readable chips that WRAP (the six extensions no longer squeeze into equal sixths): the selected one lit with the accent.
 * Same test tags as the kit's KitFilterChips, so a filter is addressed exactly as before.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CadChips(options: List<Pair<String, String>>, selected: String, group: String, onSelect: (String) -> Unit) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(50)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CadType.tight), verticalArrangement = Arrangement.spacedBy(CadType.tight)) {
        for ((id, label) in options) {
            val on = id == selected
            Box(
                Modifier.testTag(KitWritingTags.filter(group, id)).heightIn(min = 36.dp).clip(shape)
                    .background(if (on) p.accent else p.surfaceSelected)
                    .border(1.dp, if (on) p.accent else p.hairline, shape)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(id) })
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) p.tileInk else p.textPrimary, fontSize = CadType.label, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/** A small read-only tag (extension, software): a hairline pill. */
@Composable
internal fun CadTag(text: String, mono: Boolean = false, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(6.dp)
    Text(text, color = p.textPrimary, fontSize = CadType.caption, maxLines = 1, fontFamily = if (mono) FontFamily.Monospace else null,
        modifier = modifier.clip(shape).background(p.surfaceSelected).padding(horizontal = 7.dp, vertical = 3.dp))
}

/** What loading a preview image gave: the picture, or why there is none. */
private sealed class Thumb {
    data object Loading : Thumb()
    data class Ok(val img: ImageBitmap) : Thumb()
    data class None(val why: String) : Thumb()
}

/**
 * #1664 a model's preview image: the JPEG rendered from its GLB view (models.json view.thumb, bundled next to the GLB), decoded off the main thread.
 * A model with no declared picture, a missing file or one that does not decode shows the stage colour with the reason, never a crash.
 */
@Composable
internal fun ModelThumb(asset: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val noneDeclared = stringResource(R.string.thumb_none)
    val unreadable = stringResource(R.string.thumb_unreadable)
    var state by remember(asset) { mutableStateOf<Thumb>(if (asset.isBlank()) Thumb.None(noneDeclared) else Thumb.Loading) }
    LaunchedEffect(asset) {
        if (asset.isBlank()) return@LaunchedEffect
        state = withContext(Dispatchers.IO) {
            runCatching { ctx.assets.open(asset).use { BitmapFactory.decodeStream(it) } }.fold(
                { b -> if (b != null) Thumb.Ok(b.asImageBitmap()) else Thumb.None(unreadable) },
                { e -> Thumb.None(unreadable + " (" + (e.message ?: e.javaClass.simpleName) + ")") },
            )
        }
    }
    val p = LocalKitPalette.current
    Box(modifier.background(CadType.stage), contentAlignment = Alignment.Center) {
        when (val s = state) {
            is Thumb.Ok -> Image(s.img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().testTag("thumb_image"))
            is Thumb.None -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(CadType.gap).testTag("thumb_none")) {
                Text("◇", color = p.textSecondary, fontSize = CadType.display)
                Text(s.why, color = p.textSecondary, fontSize = CadType.caption, textAlign = TextAlign.Center)
            }
            Thumb.Loading -> Text("…", color = p.textSecondary, fontSize = CadType.title)
        }
    }
}
