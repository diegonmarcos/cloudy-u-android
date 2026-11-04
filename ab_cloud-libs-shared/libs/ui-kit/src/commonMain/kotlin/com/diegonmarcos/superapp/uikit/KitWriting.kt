package com.diegonmarcos.superapp.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/*
 * #1457 the writing-app parts of the kit, extracted from Cloud Writer so any document app draws the same dense look as the Store:
 * a document row with badges, a filter-chip row, a data-driven toolbar, an editor status bar and a loading skeleton. Like every
 * kit part they hold no colour of their own (LocalKitPalette) and no dp or sp literal outside KitDensity, except the toolbar's
 * 20dp glyph.
 */

/** Test tags of the writing parts. */
object KitWritingTags {
    fun docRow(id: String) = "kit:docrow:$id"
    fun filter(group: String, id: String) = "kit:filter:$group:$id"
    fun tool(id: String) = "kit:tool:$id"
    fun status(id: String) = "kit:editstatus:$id"
    const val SKELETON = "kit:skeleton"
}

/** A small read-only label on a row: [text], with a state dot when [state] is set. */
data class KitBadge(val text: String, val state: KitState? = null)

@Composable
fun KitBadgeChip(badge: KitBadge, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(KitDensity.small / 2)
    Row(
        modifier.clip(shape).border(KitDensity.rule, p.hairline, shape)
            .padding(horizontal = KitDensity.small / 4 * 3, vertical = KitDensity.small / 8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (badge.state != null) Box(Modifier.padding(end = KitDensity.small / 2).size(KitDensity.dot / 4 * 3).clip(CircleShape).background(kitStateColor(badge.state)))
        Text(badge.text, color = p.textSecondary, fontSize = KitDensity.caption, maxLines = 1)
    }
}

/**
 * A document in a list: an initial on an accent tile, the title, a [snippet] of up to two lines, then [badges] (words, updated
 * time, a state) on one row. [trailing] is the row's own menu. [onClick] opens it.
 */
@Composable
fun KitDocRow(
    title: String,
    snippet: String,
    badges: List<KitBadge>,
    initial: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(KitDensity.corner)
    Row(
        modifier.fillMaxWidth().testTag(KitWritingTags.docRow(tag)).clip(shape).background(p.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = KitDensity.medium, top = KitDensity.small, bottom = KitDensity.small, end = KitDensity.small),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(KitDensity.glyph + KitDensity.small).clip(RoundedCornerShape(KitDensity.small)).background(p.surfaceSelected),
            contentAlignment = Alignment.Center,
        ) { Text(initial, color = p.accent, fontSize = KitDensity.head, fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f).padding(start = KitDensity.medium), verticalArrangement = Arrangement.spacedBy(KitDensity.small / 2)) {
            Text(title, color = p.textPrimary, fontSize = KitDensity.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (snippet.isNotBlank()) Text(snippet, color = p.textSecondary, fontSize = KitDensity.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(KitDensity.small / 2)) { badges.forEach { KitBadgeChip(it) } }
        }
        if (trailing != null) Box(Modifier.padding(start = KitDensity.small / 2)) { trailing() }
    }
}

/** A one-of-few pick drawn as a row of chips (the Store's All | Updates | Missing look): the selected chip is lit with the accent. */
@Composable
fun KitFilterChips(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    group: String = "filter",
) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(KitDensity.small / 2)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KitDensity.small / 2)) {
        for ((id, label) in options) {
            val on = id == selected
            Box(
                Modifier.weight(1f).testTag(KitWritingTags.filter(group, id)).clip(shape)
                    .background(if (on) p.accent else p.surfaceSelected)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(id) })
                    .padding(vertical = KitDensity.rowPadV),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) p.tileInk else p.textSecondary, fontSize = KitDensity.body,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** One toolbar action. [active] lights it (the format at the caret); [showLabel] draws a chip with the label beside the icon. */
data class KitToolAction(
    val id: String,
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val active: Boolean = false,
    val showLabel: Boolean = false,
    val onClick: () -> Unit,
)

/** A scrolling toolbar of [groups] of actions, a hairline between groups: the editor's format strip and its tool chips. */
@Composable
fun KitToolbar(groups: List<List<KitToolAction>>, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KitDensity.small, vertical = KitDensity.small / 2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KitDensity.small / 2),
    ) {
        groups.filter { it.isNotEmpty() }.forEachIndexed { i, group ->
            if (i > 0) Box(Modifier.padding(horizontal = KitDensity.small / 2).width(KitDensity.rule).height(KitDensity.glyph - KitDensity.small).background(p.hairline))
            for (a in group) {
                val shape = RoundedCornerShape(KitDensity.small)
                Row(
                    Modifier.testTag(KitWritingTags.tool(a.id)).clip(shape)
                        .background(if (a.active) p.accent else if (a.showLabel) p.surfaceSelected else p.surface)
                        .clickable(enabled = a.enabled, role = Role.Button, onClick = a.onClick)
                        .padding(horizontal = if (a.showLabel) KitDensity.medium else KitDensity.small, vertical = KitDensity.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val ink = if (a.active) p.tileInk else if (a.enabled) p.textPrimary else p.textSecondary
                    Icon(a.icon, contentDescription = a.label, tint = ink, modifier = Modifier.size(KitDensity.glyph - KitDensity.small))
                    if (a.showLabel) Text(a.label, color = ink, fontSize = KitDensity.body, maxLines = 1, modifier = Modifier.padding(start = KitDensity.small / 2 + KitDensity.small / 4))
                }
            }
        }
    }
}

/** The editor's status line: the [facts] (words, characters, reading time) on the left, the save state as a pill on the right. */
@Composable
fun KitEditorStatus(facts: List<String>, saveWord: String, saveState: KitState, modifier: Modifier = Modifier, tag: String = "doc") {
    val p = LocalKitPalette.current
    Row(
        modifier.fillMaxWidth().testTag(KitWritingTags.status(tag)).background(p.surface)
            .padding(horizontal = KitDensity.medium, vertical = KitDensity.small / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(facts.joinToString("  ·  "), color = p.textSecondary, fontSize = KitDensity.caption, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        KitStatePill(saveWord, saveState)
    }
}

/** The loading state of a list: [count] placeholder rows in the document row's shape. */
@Composable
fun KitSkeletonRows(count: Int = 5, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    Column(modifier.fillMaxWidth().testTag(KitWritingTags.SKELETON), verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
        repeat(count) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(KitDensity.corner)).background(p.surface).padding(KitDensity.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(KitDensity.glyph + KitDensity.small).clip(RoundedCornerShape(KitDensity.small)).background(p.surfaceSelected))
                Column(Modifier.padding(start = KitDensity.medium), verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                    Box(Modifier.fillMaxWidth(0.55f).height(KitDensity.medium).clip(RoundedCornerShape(KitDensity.small / 2)).background(p.surfaceSelected))
                    Box(Modifier.fillMaxWidth(0.9f).height(KitDensity.small).clip(RoundedCornerShape(KitDensity.small / 2)).background(p.surfaceSelected))
                }
            }
        }
    }
}
