package com.diegonmarcos.superapp.network

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The rows the status-strip network bubble is made of, in the dark-glass bubble's own look: 11 sp dim monospace labels, 12 sp
 * white monospace values, 12 sp bold accent links, a 10 dp status light, and a 25 %-white hairline between sections.
 */

internal val POP_GREEN = Color(0xFF35E07F)
internal val POP_AMBER = Color(0xFFE0A235)
internal val POP_DIM = Color(0x55FFFFFF)
private val POP_LINK = Color(0xFF7FB8FF)
private val POP_DIVIDER = Color(0x40FFFFFF)

@Composable
internal fun PopLabel(t: String) = Text(t, color = Color(0xAAFFFFFF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)

/** A section header: the label's look, bold. */
@Composable
internal fun PopTitle(t: String) =
    Text(t, color = Color(0xAAFFFFFF), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)

@Composable
internal fun PopValue(t: String) = Text(t, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)

/** A compact tappable action line ("ADB Shell ›"), accent-tinted, same text size as the values. */
@Composable
internal fun PopLink(t: String, onTap: () -> Unit) =
    Text(t, color = POP_LINK, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
        modifier = Modifier.clickable(onClick = onTap).padding(top = 2.dp, bottom = 2.dp, end = 10.dp).testTag("netpop:link:$t"))

/** Several [PopLink]s on one line. */
@Composable
internal fun PopButtons(vararg actions: Pair<String, () -> Unit>) {
    Row { for ((t, a) in actions) PopLink(t, a) }
}

/** The full-width 1 dp line between two sections, with 6 dp above and 8 dp below. */
@Composable
internal fun PopDivider() {
    Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp).height(1.dp).background(POP_DIVIDER))
}

/** #1684 the line between two network groups (the strip's separator): 2 dp, brighter, more air. */
@Composable
internal fun PopGroupDivider() {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp).height(2.dp).background(Color(0x66FFFFFF)).testTag("netpop:groupdivider"))
}

/** A status light: 10 dp. */
@Composable
internal fun PopDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}

/** Section header with a trailing on/off light; the light's hit area is tappable. Green = on, dim = off. */
@Composable
internal fun PopLightRow(title: String, on: Boolean, onTap: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PopTitle(title)
        Box(Modifier.clickable(onClick = onTap).padding(start = 8.dp, top = 5.dp, end = 5.dp, bottom = 5.dp)
            .testTag("netpop:light:$title")) {
            PopDot(if (on) POP_GREEN else POP_DIM)
        }
    }
}

/** A vertical block of rows. */
@Composable
internal fun PopGroup(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.Top) { content() }
}
