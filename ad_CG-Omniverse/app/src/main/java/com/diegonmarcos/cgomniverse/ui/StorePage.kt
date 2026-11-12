@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.diegonmarcos.cgomniverse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cgomniverse.Decl
import com.diegonmarcos.cgomniverse.FilterGroup
import com.diegonmarcos.cgomniverse.LinkStatus
import com.diegonmarcos.cgomniverse.R
import com.diegonmarcos.cgomniverse.Store
import com.diegonmarcos.cgomniverse.World
import com.diegonmarcos.superapp.uikit.KitButton
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.KitOutlinedButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** The VR badge's colour: the one place it is spelled. */
private val VR_TINT = Color(0xFF8F7CFF)
/** The link-status badges' colours: offline is a warning red, unverified a muted amber (not known bad, only not proven good). */
private val OFFLINE_TINT = Color(0xFFFF6B6B)
private val UNVERIFIED_TINT = Color(0xFFE0B04F)

/** The Store: every world of data/store.json, three multi-select filter rows (all selected at first) and a card per world. */
@Composable
fun StorePage(onOpenInGalaxy: (World) -> Unit) {
    val ctx = LocalContext.current
    val worlds = Decl.worlds
    var sel by remember { mutableStateOf(Store.allSelected()) }
    val shown = remember(sel) { Store.filter(worlds, sel) }
    val p = LocalKitPalette.current
    LazyColumn(
        Modifier.fillMaxSize().testTag(Tags.STORE),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FilterGroup.entries.forEach { g ->
                    FilterRow(stringResource(groupLabel(g)), g, sel, onAll = { sel = Store.toggleAll(sel, g) }, onChip = { sel = Store.toggle(sel, g, it) })
                }
                Text(
                    stringResource(R.string.store_count, shown.size, worlds.size), style = MaterialTheme.typography.labelMedium,
                    color = p.textSecondary, modifier = Modifier.padding(top = 4.dp).testTag(Tags.STORE_COUNT),
                )
            }
        }
        items(shown, key = { it.id }) { w ->
            WorldCard(w, onGalaxy = { onOpenInGalaxy(w) }, onBrowser = { Web.openInBrowser(ctx, w.url) })
        }
        if (shown.isEmpty()) item(key = "empty") { KitEmptyState(null, stringResource(R.string.store_empty)) }
    }
}

private fun groupLabel(g: FilterGroup): Int = when (g) {
    FilterGroup.TYPE -> R.string.filter_type
    FilterGroup.DIMENSION -> R.string.filter_dimension
    FilterGroup.DEVICE -> R.string.filter_device
}

@Composable
private fun FilterRow(label: String, g: FilterGroup, sel: Map<FilterGroup, Set<String>>, onAll: () -> Unit, onChip: (String) -> Unit) {
    val p = LocalKitPalette.current
    // the label stays at the left; the chips wrap onto as many lines as they need, so every one is on screen (a
    // horizontally scrolled row hid the last chips past the edge: Type's "Others", Device's "VR Glasses")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = p.textSecondary,
            modifier = Modifier.width(72.dp).padding(top = 14.dp))
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(stringResource(R.string.filter_all), Store.isAll(sel, g), Tags.chip(g.name, "All"), onAll)
            g.chips.forEach { c -> Chip(c, c in sel[g].orEmpty(), Tags.chip(g.name, c)) { onChip(c) } }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) }, modifier = Modifier.testTag(tag),
        leadingIcon = if (selected) ({ Icon(Icons.Filled.Check, null) }) else null,
    )
}

/** A small rounded label; [tint] when it carries a meaning (VR). */
@Composable
fun Badge(text: String, tint: Color? = null) {
    val p = LocalKitPalette.current
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = tint ?: p.textSecondary, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background((tint ?: p.textSecondary).copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
fun WorldBadges(w: World) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Badge(w.type)
        Badge(w.dimension)
        if (w.vr) Badge(stringResource(R.string.badge_vr), VR_TINT)
        StatusBadge(w)
    }
}

/** Offline or Unverified from the link probe; an online world shows nothing extra. */
@Composable
fun StatusBadge(w: World) {
    val (label, tint) = when (w.status) {
        LinkStatus.OFFLINE -> R.string.badge_offline to OFFLINE_TINT
        LinkStatus.UNVERIFIED -> R.string.badge_unverified to UNVERIFIED_TINT
        LinkStatus.ONLINE -> return
    }
    Box(Modifier.testTag(Tags.statusBadge(w.id))) { Badge(stringResource(label), tint) }
}

@Composable
private fun WorldCard(w: World, onGalaxy: () -> Unit, onBrowser: () -> Unit) {
    val p = LocalKitPalette.current
    KitCard(Modifier.testTag(Tags.world(w.id))) {
        val model = w.model
        if (model != null) ModelPhoto(model, Modifier.fillMaxWidth().height(150.dp).padding(bottom = 8.dp))
        Text(w.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = p.textPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.padding(top = 6.dp))
        WorldBadges(w)
        if (model != null) Text(ModelWeb.facts(model), style = MaterialTheme.typography.labelMedium, color = p.textSecondary, modifier = Modifier.padding(top = 6.dp).testTag(Tags.modelFacts(w.id)))
        Text(w.framework, style = MaterialTheme.typography.labelMedium, color = p.accent, modifier = Modifier.padding(top = 8.dp))
        Text(w.description, style = MaterialTheme.typography.bodySmall, color = p.textSecondary, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KitButton(onClick = onGalaxy, modifier = Modifier.testTag(Tags.openGalaxy(w.id))) {
                Icon(Icons.Filled.Public, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.open_galaxy))
            }
            KitOutlinedButton(onClick = onBrowser, modifier = Modifier.testTag(Tags.openBrowser(w.id))) {
                Icon(Icons.Filled.OpenInBrowser, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.open_browser))
            }
        }
    }
}
