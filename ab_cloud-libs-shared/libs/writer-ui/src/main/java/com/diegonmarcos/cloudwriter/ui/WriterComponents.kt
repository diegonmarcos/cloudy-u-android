package com.diegonmarcos.cloudwriter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudwriter.core.ControlKind
import com.diegonmarcos.cloudwriter.core.ControlState
import com.diegonmarcos.cloudwriter.core.Controls
import com.diegonmarcos.cloudwriter.core.TokenSet
import com.diegonmarcos.cloudwriter.core.WriterGrid
import com.diegonmarcos.superapp.uikit.KitDensity

/**
 * #1532 the premium component set of Cloud Writer: the pieces the document list, the editor and the toolbars are drawn
 * from, every size taken from core [WriterGrid] (the 8 dp grid, 12 to 16 dp radii, 40 to 48 dp controls) and every colour
 * from the theme's roles (the tonal surface levels and the accent), so nothing here picks a number or a colour of its own
 * and the AA contrast test on the tokens covers what is drawn.
 *
 * Three weights of control, as the design asks: filled primary (the accent, one per screen: New document, Enhance),
 * tonal secondary (imports, the other tools, filters) and text tertiary (a quiet action). Each has a pressed state (the
 * Material state layer, core Controls.PRESSED_LAYER), a disabled state (38% ink) and a ripple.
 */
object WriterDims {
    val gutter = WriterGrid.GUTTER.dp
    val gap = WriterGrid.UNIT.dp
    val half = WriterGrid.HALF.dp
    val section = 24.dp
    val touch = WriterGrid.TOUCH.dp
    val control = WriterGrid.CONTROL_HEIGHT.dp
    val controlLarge = WriterGrid.CONTROL_HEIGHT_LARGE.dp
    val controlShape = RoundedCornerShape(WriterGrid.RADIUS_CONTROL.dp)
    val cardShape = RoundedCornerShape(WriterGrid.RADIUS_CARD.dp)
    /** The editor's measure: a comfortable line length, centred when the screen is wider. */
    val measure = WriterGrid.MEASURE.dp
    /** Clearance under the list for the floating action button. */
    val fabClearance = 96.dp
    val illustration = 160.dp
}

/** The token set matching the current theme, for the control states core resolves. */
@Composable
private fun tokens(): TokenSet = if (WriterTheme.isDark()) TokenSet.DARK else TokenSet.LIGHT

/** A button: [kind] picks filled primary, tonal secondary or text tertiary; [icon] goes before the label. */
@Composable
fun WriterButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ControlKind = ControlKind.PRIMARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    large: Boolean = false,
) {
    val sized = modifier.heightIn(min = if (large) WriterDims.controlLarge else WriterDims.control)
    val padding = if (icon != null) ButtonDefaults.ButtonWithIconContentPadding else ButtonDefaults.ContentPadding
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    when (kind) {
        ControlKind.PRIMARY -> Button(onClick = onClick, modifier = sized, enabled = enabled, shape = WriterDims.controlShape, contentPadding = padding, content = content)
        ControlKind.TONAL -> FilledTonalButton(onClick = onClick, modifier = sized, enabled = enabled, shape = WriterDims.controlShape, contentPadding = padding, content = content)
        ControlKind.TEXT -> TextButton(onClick = onClick, modifier = sized, enabled = enabled, shape = WriterDims.controlShape, contentPadding = padding, content = content)
    }
}

/** An icon-only button with the 48 dp touch floor. */
@Composable
fun WriterIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    IconButton(onClick = onClick, modifier = modifier.size(WriterDims.touch)) {
        Icon(icon, contentDescription = description, tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint)
    }
}

/**
 * A toolbar chip: an icon (and a label when [showLabel]) on a 12 dp rounded shape at least 40 dp tall and 48 dp wide.
 * [kind] is its weight, [active] lights it with the accent (the format at the caret, the running tool). The idle text
 * chip is transparent on the bar; pressed and disabled come from core Controls so the chip's states are the tested ones.
 */
@Composable
fun WriterChip(
    id: String,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ControlKind = ControlKind.TEXT,
    active: Boolean = false,
    enabled: Boolean = true,
    showLabel: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val state = if (!enabled) ControlState.DISABLED else if (pressed) ControlState.PRESSED else ControlState.ENABLED
    val effective = if (active) ControlKind.PRIMARY else kind
    val c = Controls.colors(tokens(), effective, state)
    val container = if (effective == ControlKind.TEXT && state == ControlState.ENABLED) Color.Transparent else Color(c.container)
    val ink = Color(c.content)
    Row(
        modifier
            .testTag(com.diegonmarcos.superapp.uikit.KitWritingTags.tool(id))
            .alpha(c.alpha)
            .defaultMinSize(minWidth = WriterDims.touch, minHeight = WriterDims.control)
            .clip(WriterDims.controlShape)
            .background(container)
            .clickable(interactionSource = source, indication = ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (showLabel) WriterDims.gutter else WriterDims.gap),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = ink, modifier = Modifier.size(KitDensity.glyph - KitDensity.small))
        if (showLabel) {
            Spacer(Modifier.width(WriterDims.gap))
            Text(label, color = ink, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** A floating bar of chips on the tonal "high" surface, 16 dp rounded and scrolling sideways when it overflows. */
@Composable
fun WriterBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = WriterDims.gap, vertical = WriterDims.half),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = WriterDims.cardShape,
        shadowElevation = WriterDims.half,
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = WriterDims.half, vertical = WriterDims.half),
            horizontalArrangement = Arrangement.spacedBy(WriterDims.half),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** The hairline between two groups of chips. */
@Composable
fun BarDivider() {
    Box(
        Modifier.padding(horizontal = WriterDims.half).width(KitDensity.rule).height(WriterDims.gutter + WriterDims.gap)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** A one-of-few filter chip, 40 dp tall: the selected one is tonal-filled with a check, the others outlined. */
@Composable
fun WriterFilterChip(id: String, group: String, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val shape = WriterDims.controlShape
    Row(
        modifier
            .testTag(com.diegonmarcos.superapp.uikit.KitWritingTags.filter(group, id))
            .heightIn(min = WriterDims.control)
            .clip(shape)
            .background(if (selected) cs.secondaryContainer else Color.Transparent)
            .border(KitDensity.rule, if (selected) Color.Transparent else cs.outlineVariant, shape)
            .selectable(selected = selected, interactionSource = source, indication = ripple(), role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = WriterDims.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = cs.onSecondaryContainer, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(WriterDims.half))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** A small read-only tag on a card: `#idea` in the primary-container pair. */
@Composable
fun WriterTagPill(text: String) {
    val cs = MaterialTheme.colorScheme
    Text(
        "#$text",
        style = MaterialTheme.typography.labelMedium,
        color = cs.onPrimaryContainer,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(WriterDims.gap)).background(cs.primaryContainer)
            .padding(horizontal = WriterDims.gap, vertical = WriterDims.half),
    )
}

/** The search field: a 16 dp rounded tonal container with a search icon and a clear action, no underline. */
@Composable
fun WriterSearchField(query: String, onQueryChange: (String) -> Unit, placeholder: String, clearLabel: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    TextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        modifier = modifier.fillMaxWidth().testTag("writer:search"),
        shape = WriterDims.cardShape,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) WriterIconButton(Icons.Rounded.Close, clearLabel, { onQueryChange("") })
        },
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = cs.surfaceContainerHigh,
            unfocusedContainerColor = cs.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = cs.primary,
        ),
    )
}

/**
 * A document in the list as a card (surface container low, 16 dp, hairline edge so it still separates on the OLED black):
 * the title, a two-line preview, then updated time, words and reading time on one line and the tags under it.
 */
@Composable
fun WriterDocCard(
    tag: String,
    title: String,
    preview: String,
    meta: String,
    tags: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth().testTag(com.diegonmarcos.superapp.uikit.KitWritingTags.docRow(tag))
            .clickable(role = Role.Button, onClick = onClick),
        shape = WriterDims.cardShape,
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
        border = BorderStroke(KitDensity.rule, cs.outlineVariant),
    ) {
        Column(Modifier.padding(start = WriterDims.gutter, top = WriterDims.gutter, bottom = WriterDims.gutter, end = WriterDims.gap)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(top = WriterDims.gap, end = WriterDims.gap),
                )
                trailing()
            }
            if (preview.isNotBlank()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = WriterDims.half, end = WriterDims.gutter),
                )
            }
            Text(
                meta,
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = WriterDims.gap, end = WriterDims.gutter),
            )
            if (tags.isNotEmpty()) {
                Row(Modifier.padding(top = WriterDims.gap), horizontalArrangement = Arrangement.spacedBy(WriterDims.half)) {
                    tags.forEach { WriterTagPill(it) }
                }
            }
        }
    }
}

/** A heading of a block on the list: small, quiet and spaced, so the cards carry the weight. */
@Composable
fun WriterLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = WriterDims.gap),
    )
}

/**
 * The empty list's illustration, drawn as vectors in the theme's own colours (so it follows light and dark): a soft
 * disc, two pages and a pen in the accent. Proportions are fractions of the canvas, so no size is a literal.
 */
@Composable
fun EmptyDocsIllustration(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val backdrop = cs.primaryContainer
    val back = cs.surfaceContainerHighest
    val front = cs.surfaceContainerHigh
    val ruling = cs.outlineVariant
    val accent = cs.primary
    Canvas(modifier.size(WriterDims.illustration)) {
        val w = size.width
        val h = size.height
        drawCircle(backdrop, radius = w * 0.46f, center = Offset(w * 0.5f, h * 0.5f))
        rotate(-9f, pivot = Offset(w * 0.5f, h * 0.5f)) {
            drawRoundRect(back, topLeft = Offset(w * 0.26f, h * 0.20f), size = Size(w * 0.42f, h * 0.56f), cornerRadius = CornerRadius(w * 0.05f))
        }
        drawRoundRect(front, topLeft = Offset(w * 0.31f, h * 0.17f), size = Size(w * 0.42f, h * 0.56f), cornerRadius = CornerRadius(w * 0.05f))
        for (i in 0 until 4) {
            val y = h * (0.28f + i * 0.085f)
            val len = if (i == 3) 0.14f else 0.26f
            drawRoundRect(ruling, topLeft = Offset(w * 0.37f, y), size = Size(w * len, h * 0.025f), cornerRadius = CornerRadius(w * 0.012f))
        }
        rotate(40f, pivot = Offset(w * 0.665f, h * 0.60f)) {
            drawRoundRect(accent, topLeft = Offset(w * 0.63f, h * 0.38f), size = Size(w * 0.07f, h * 0.32f), cornerRadius = CornerRadius(w * 0.02f))
            val tip = Path().apply {
                moveTo(w * 0.63f, h * 0.70f)
                lineTo(w * 0.70f, h * 0.70f)
                lineTo(w * 0.665f, h * 0.79f)
                close()
            }
            drawPath(tip, accent)
        }
    }
}

/** The empty (or no-match) state: the illustration, a title, a caption and the calls to action under them. */
@Composable
fun WriterEmptyState(title: String, caption: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Column(
        modifier.fillMaxWidth().padding(vertical = WriterDims.section),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyDocsIllustration()
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = WriterDims.gutter),
        )
        Text(
            caption,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = WriterDims.section, vertical = WriterDims.gap),
        )
        Column(Modifier.padding(top = WriterDims.gutter), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(WriterDims.gap)) {
            actions()
        }
    }
}
