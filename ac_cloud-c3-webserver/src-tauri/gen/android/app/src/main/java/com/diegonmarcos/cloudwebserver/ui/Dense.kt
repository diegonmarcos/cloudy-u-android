package com.diegonmarcos.cloudwebserver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitDensity

/**
 * #1723 the console-grade density of the Home page: compact table rows on the fleet kit's density tokens (KitDensity type ramp and row
 * padding), a fixed key column so every value lines up, small headings and thin panels instead of big cards. Colours are only
 * MaterialTheme / Tone tokens (#1712 guard).
 */
object Dense {
    /** The key column of every table row: all values of the page start at the same x. */
    val KEY_COL = 92.dp
    val ROW_V = 2.dp
}

private val bodyStyle: TextStyle @Composable get() = MaterialTheme.typography.bodySmall.copy(fontSize = KitDensity.body, lineHeight = KitDensity.body * 1.25f)
private val capStyle: TextStyle @Composable get() = MaterialTheme.typography.bodySmall.copy(fontSize = KitDensity.caption, lineHeight = KitDensity.caption * 1.25f)

@Composable
fun DensePage(tag: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

/** A thin panel: a small uppercase heading line (badges, an optional link at the right), a hairline, then the table rows. */
@Composable
fun DenseCard(title: String, tag: String = "", badges: @Composable RowScope.() -> Unit = {}, trailing: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(KitDensity.rule * 6)
    Column(Modifier.fillMaxWidth().background(Tone.Card, shape).border(KitDensity.rule, Tone.Line, shape).let { if (tag.isNotEmpty()) it.testTag(tag) else it }) {
        Row(Modifier.fillMaxWidth().height(22.dp).padding(horizontal = KitDensity.rowPadH), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), style = capStyle, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = KitDensity.eyebrowTracking, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.dp))
            badges()
            Spacer(Modifier.weight(1f))
            trailing()
        }
        Box(Modifier.fillMaxWidth().height(KitDensity.rule).background(Tone.Line))
        content()
    }
}

/** One table row: [dot] key | value, optionally tappable (a trailing chevron says so). */
@Composable
fun DRow(k: String, v: String, up: Boolean? = null, mono: Boolean = false, tag: String = "", onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }.let { if (tag.isNotEmpty()) it.testTag(tag) else it }
            .padding(horizontal = KitDensity.rowPadH, vertical = Dense.ROW_V),
        verticalAlignment = Alignment.Top,
    ) {
        Row(Modifier.width(Dense.KEY_COL), verticalAlignment = Alignment.CenterVertically) {
            if (up != null) { DDot(up); Spacer(Modifier.width(4.dp)) }
            Text(k, style = capStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(v, style = bodyStyle.copy(fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (onClick != null) Text("›", style = bodyStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
    }
}

/** A multi-column table row (served pages): cells of fixed widths (null = takes the rest), same type ramp. */
@Composable
fun DCols(cells: List<String>, widths: List<Int?>, up: Boolean? = null, head: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = KitDensity.rowPadH, vertical = Dense.ROW_V), verticalAlignment = Alignment.Top) {
        if (up != null) { DDot(up); Spacer(Modifier.width(4.dp)) }
        cells.forEachIndexed { i, c ->
            val w = widths.getOrNull(i)
            Text(
                c, style = if (head) capStyle else bodyStyle, color = if (head) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = if (w == null) Modifier.weight(1f) else Modifier.width(w.dp).padding(end = 4.dp),
            )
        }
    }
}

@Composable
fun DDot(up: Boolean) {
    Box(Modifier.size(KitDensity.dot - 2.dp).background(if (up) Tone.Up else Tone.Down, CircleShape))
}

/** A compact action: bordered text, no Material 48dp touch floor. */
@Composable
fun DAct(label: String, tag: String = "", onClick: () -> Unit) {
    Text(
        label, style = capStyle.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(end = 4.dp).border(KitDensity.rule, Tone.Line, RoundedCornerShape(5.dp)).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp).let { if (tag.isNotEmpty()) it.testTag(tag) else it },
    )
}

/** A heading-line link (the old LinkText, without the Material button chrome). */
@Composable
fun DLink(label: String, tag: String = "", onClick: () -> Unit) {
    Text(label, style = capStyle, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp, vertical = 3.dp).let { if (tag.isNotEmpty()) it.testTag(tag) else it })
}

@Composable
fun DNote(text: String) {
    Text(text, style = capStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = KitDensity.rowPadH, vertical = 1.dp))
}

@Composable
fun DErr(text: String) {
    Text(text, style = capStyle, color = Tone.Bad, modifier = Modifier.padding(horizontal = KitDensity.rowPadH, vertical = 2.dp).testTag("c3_error"))
}

/** [Loaded] with a one-line placeholder instead of a padded spinner row. */
@Composable
fun <T> DLoaded(load: Load<T>, what: String, body: @Composable (T) -> Unit) {
    when (load) {
        Load.Loading -> DNote("Loading $what…")
        is Load.Err -> DErr("$what: ${load.reason}")
        is Load.Ok -> body(load.value)
    }
}

@Composable
fun DActs(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = KitDensity.rowPadH, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}
