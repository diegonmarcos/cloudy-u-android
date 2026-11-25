package com.diegonmarcos.superapp.network.mesh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.superapp.ui.StatusLight
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #877 The Cloud Mesh page's primitives. Every size is a [MeshDensity] step or ramp entry; every colour
 * is a kit palette role or a status-light state. A page is built from these and from nothing else,
 * so the density and the look of every tab are decided in two files.
 */

/** Test tags of the mesh page, for compose tests and the debug API. */
object MeshTags {
    fun page(id: String) = "mesh:page:$id"
    fun tab(id: String) = "mesh:tab:$id"
    fun control(id: String) = "mesh:ctl:$id"
    /** #1928 A subpage's body, and its "Updated" line. */
    fun sub(id: String) = "mesh:sub:$id"
    fun stamp(id: String) = "mesh:stamp:$id"
    fun row(id: String) = "mesh:row:$id"
    fun peer(i: Int) = "mesh:peer:$i"
    const val NOTICE = "mesh:notice"
}

/** A label from the string table named `mesh_<kind>_<id>`, else the declared English [fallback]. */
@Composable
fun declLabel(kind: String, id: String, fallback: String): String {
    val ctx = LocalContext.current
    val rid = remember(kind, id) { ctx.resources.getIdentifier("mesh_${kind}_$id", "string", ctx.packageName) }
    return if (rid != 0) stringResource(rid) else fallback
}

/** The three role colours of the fleet topology (hub / client / spoke). */
object MeshRoles {
    val HUB = Color(0xFF2E7D32)
    val CLIENT = Color(0xFFEF6C00)
    val SPOKE = Color(0xFF1565C0)
    fun of(role: String) = when (role) { "hub" -> HUB; "client" -> CLIENT; else -> SPOKE }
}

@Composable
fun MText(
    text: String,
    modifier: Modifier = Modifier,
    size: Float = MeshDensity.T_BODY,
    mono: Boolean = false,
    muted: Boolean = false,
    bold: Boolean = false,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    val p = LocalKitPalette.current
    Text(
        text, modifier,
        color = if (color != Color.Unspecified) color else if (muted) p.textSecondary else p.textPrimary,
        style = TextStyle(
            fontSize = MeshDensity.sp(size),
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The status disc: shape-coded through the glyph in its description, coloured by the fleet's one light. */
@Composable
fun Light(state: StatusLight.State, label: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Box(
        modifier
            .size(MeshDensity.dp(MeshDensity.GLYPH))
            .clip(CircleShape)
            .background(Color(StatusLight.colour(ctx, state)))
            .semantics { contentDescription = StatusLight.glyph(state) + " " + label },
    )
}

fun lightOf(h: Health): StatusLight.State = when (h) {
    Health.FRESH, Health.AGING -> StatusLight.State.ON
    Health.DOWN -> StatusLight.State.OFF
    Health.STALE, Health.NONE, Health.UNKNOWN -> StatusLight.State.UNKNOWN
}

fun lightOf(l: Light): StatusLight.State = when (l) {
    Light.UP -> StatusLight.State.ON
    Light.DOWN -> StatusLight.State.OFF
    Light.WARN, Light.UNKNOWN -> StatusLight.State.UNKNOWN
}

@Composable
fun MHeader(title: String, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    Column(modifier.fillMaxWidth().padding(top = MeshDensity.dp(MeshDensity.S12), bottom = MeshDensity.dp(MeshDensity.S4))) {
        MText(title.uppercase(), size = MeshDensity.T_CAPTION, bold = true, color = p.accent)
        Box(Modifier.fillMaxWidth().padding(top = MeshDensity.dp(MeshDensity.S2)).size(MeshDensity.dp(MeshDensity.S1)).background(p.hairline))
    }
}

/** key | value on one dense line; the value is a monospaced readout, optionally tap-to-copy. */
@Composable
fun KvRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    tag: String = "",
    onCopy: (() -> Unit)? = null,
    valueColor: Color = Color.Unspecified,
) {
    Row(
        modifier.fillMaxWidth()
            .then(if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier)
            .semantics(mergeDescendants = true) {}
            .then(if (onCopy != null) Modifier.clickable(role = Role.Button, onClick = onCopy) else Modifier)
            .padding(vertical = MeshDensity.dp(MeshDensity.S2)),
        horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S8)),
        verticalAlignment = Alignment.Top,
    ) {
        MText(key, Modifier.width(MeshDensity.dp(MeshDensity.COLUMN)), size = MeshDensity.T_META, muted = true)
        MText(value, Modifier.weight(1f), size = MeshDensity.T_META, mono = true, color = valueColor)
    }
}

/** A compact button: a hairline-edged label, [MeshDensity.TAP] tall at most, never Material's 48dp box. */
@Composable
fun MButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasised: Boolean = false,
) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(MeshDensity.dp(MeshDensity.S6))
    Box(
        modifier
            .defaultMinSize(minHeight = MeshDensity.dp(MeshDensity.TAP))
            .clip(shape)
            .background(if (emphasised) p.surfaceSelected else p.surface)
            .border(MeshDensity.dp(MeshDensity.S1), if (emphasised) p.accent else p.hairline, shape)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (!enabled) disabled() }
            .padding(horizontal = MeshDensity.dp(MeshDensity.S8), vertical = MeshDensity.dp(MeshDensity.S4)),
        contentAlignment = Alignment.Center,
    ) { MText(label, size = MeshDensity.T_META, maxLines = 1) }
}

@Composable
fun Reason(text: String, modifier: Modifier = Modifier) {
    MText(text, modifier.padding(bottom = MeshDensity.dp(MeshDensity.S2)), size = MeshDensity.T_MICRO, muted = true)
}

/** A labelled switch line; disabled it still shows its state and says WHY beneath. */
@Composable
fun SwitchLine(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    reason: String? = null,
) {
    val p = LocalKitPalette.current
    // One node for the line (label, state, reason): a screen reader and a test read the switch with its reason,
    // and enabled / disabled / click sit on the same node the tag names.
    Column(
        modifier.fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .semantics { stateDescription = if (checked) "on" else "off"; if (!enabled) disabled() },
    ) {
        Row(
            Modifier.fillMaxWidth()
                .alpha(if (enabled) 1f else 0.55f)
                .padding(vertical = MeshDensity.dp(MeshDensity.S6)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MText(label, Modifier.weight(1f), size = MeshDensity.T_BODY)
            val shape = RoundedCornerShape(MeshDensity.dp(MeshDensity.S12))
            Box(
                Modifier.width(MeshDensity.dp(MeshDensity.TAP)).size(width = MeshDensity.dp(MeshDensity.TAP), height = MeshDensity.dp(MeshDensity.S12 + MeshDensity.S4))
                    .clip(shape).background(if (checked) p.accent else p.surface)
                    .border(MeshDensity.dp(MeshDensity.S1), p.hairline, shape),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(Modifier.padding(MeshDensity.dp(MeshDensity.S2)).size(MeshDensity.dp(MeshDensity.S12)).clip(CircleShape).background(if (checked) p.tileInk else p.textSecondary))
            }
        }
        if (reason != null) Reason(reason)
    }
}

/** A row of mutually exclusive options; an option may be individually unavailable with its reason in [unavailable]. */
@Composable
fun ChoiceLine(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    unavailable: Map<String, String> = emptyMap(),
) {
    val p = LocalKitPalette.current
    Column(modifier.fillMaxWidth().padding(vertical = MeshDensity.dp(MeshDensity.S2))) {
        MText(label, size = MeshDensity.T_META, muted = true)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = MeshDensity.dp(MeshDensity.S2)),
            horizontalArrangement = Arrangement.spacedBy(MeshDensity.dp(MeshDensity.S4)),
        ) {
            for ((id, text) in options) {
                val on = id == selected
                val ok = enabled && id !in unavailable
                val shape = RoundedCornerShape(MeshDensity.dp(MeshDensity.S6))
                Box(
                    Modifier.clip(shape)
                        .background(if (on) p.surfaceSelected else p.surface)
                        .border(MeshDensity.dp(MeshDensity.S1), if (on) p.accent else p.hairline, shape)
                        .alpha(if (ok) 1f else 0.45f)
                        .clickable(enabled = ok, role = Role.RadioButton) { onPick(id) }
                        .semantics { this.selected = on; if (!ok) disabled() }
                        .testTag("mesh:opt:$id")
                        .padding(horizontal = MeshDensity.dp(MeshDensity.S8), vertical = MeshDensity.dp(MeshDensity.S6)),
                ) { MText(text, size = MeshDensity.T_META, bold = on, maxLines = 1) }
            }
        }
        for ((id, why) in unavailable) {
            val name = options.firstOrNull { it.first == id }?.second ?: id
            Reason("$name: $why")
        }
    }
}

/**
 * A single-line editable value. Typing edits a local draft; the value is COMMITTED (and the page told)
 * on Done and when focus leaves, never per keystroke, so a half-typed MTU never reaches the engine.
 * [secret] masks the box and starts it empty: a stored key is never shown or pre-filled.
 */
@Composable
fun MField(
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secret: Boolean = false,
    numeric: Boolean = false,
    hint: String = "",
    mono: Boolean = true,
    onChange: (String) -> Unit = {},
) {
    val p = LocalKitPalette.current
    var draft by remember(value, secret) { mutableStateOf(if (secret) "" else value) }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(MeshDensity.dp(MeshDensity.S4))
    fun commit() { if (draft != (if (secret) "" else value) && (!secret || draft.isNotBlank())) onCommit(draft) }
    BasicTextField(
        value = draft,
        onValueChange = { draft = it; onChange(it) },
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(
            color = p.textPrimary, fontSize = MeshDensity.sp(MeshDensity.T_META),
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(p.accent),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else if (secret) KeyboardType.Password else KeyboardType.Text,
            imeAction = ImeAction.Done,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier.fillMaxWidth()
            .onFocusChanged { if (focused && !it.isFocused) commit(); focused = it.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(shape).background(p.surface)
                    .border(MeshDensity.dp(MeshDensity.S1), if (focused) p.accent else p.hairline, shape)
                    .alpha(if (enabled) 1f else 0.5f)
                    .padding(horizontal = MeshDensity.dp(MeshDensity.S6), vertical = MeshDensity.dp(MeshDensity.S6)),
            ) {
                if (draft.isEmpty() && hint.isNotEmpty()) MText(hint, size = MeshDensity.T_META, muted = true, mono = mono)
                inner()
            }
        },
    )
}

/** Wraps a labelled field line: caption above, field below. */
@Composable
fun FieldLine(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().padding(vertical = MeshDensity.dp(MeshDensity.S2))) {
        MText(label, size = MeshDensity.T_META, muted = true)
        Box(Modifier.padding(top = MeshDensity.dp(MeshDensity.S2))) { content() }
    }
}

/**
 * #1074 A section fills the screen and scrolls on its own; embedded in a tab it is one block of the
 * tab's single scroll (a scroller inside a scroller has no height to measure).
 */
@Composable
fun Modifier.pageBody(embedded: Boolean): Modifier =
    if (embedded) this else this.fillMaxSize().verticalScroll(rememberScrollState())
