package com.diegonmarcos.superapp.perms

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * #1372 the host page's colours, so the Perms UI reads as part of it. Every colour left Unspecified is derived: from the
 * host's [ink] when it gives one (its page may not be the MaterialTheme's), else from MaterialTheme. Dark and light.
 */
data class PermsStyle(
    val ink: Color = Color.Unspecified,
    val dim: Color = Color.Unspecified,
    val ok: Color = Color.Unspecified,
    val bad: Color = Color.Unspecified,
    val warn: Color = Color.Unspecified,
    val accent: Color = Color.Unspecified,
    val surface: Color = Color.Unspecified,
    val hairline: Color = Color.Unspecified,
)

/** The resolved palette and type scale of the Perms UI (one place: no size or colour is written anywhere else). */
internal class PermsPalette(
    val dark: Boolean, val ink: Color, val dim: Color, val accent: Color, val onAccent: Color, val surface: Color,
    val hairline: Color, val tile: Color, val ok: Color, val warn: Color, val bad: Color,
) {
    fun tone(t: PermChip.Tone): Color = when (t) { PermChip.Tone.OK -> ok; PermChip.Tone.WARN -> warn; PermChip.Tone.BAD -> bad; PermChip.Tone.IDLE -> dim }
}

internal object PermsType {
    val headline = 18.sp; val title = 16.sp; val body = 14.sp; val caption = 13.sp; val label = 12.sp
    val gap = 4.dp; val pad = 8.dp; val inset = 16.dp; val tile = 36.dp; val icon = 20.dp; val corner = 16.dp; val rowMin = 56.dp
    /** Divider start: past the icon tile, so rows read as one list. */
    val dividerStart = 68.dp
}

@Composable
internal fun PermsStyle.resolve(): PermsPalette {
    val cs = MaterialTheme.colorScheme
    val ink = if (ink.isSpecified) ink else cs.onSurface
    val dark = ink.luminance() > 0.5f
    val accent = if (accent.isSpecified) accent else cs.primary
    fun pick(c: Color, d: Long, l: Long) = if (c.isSpecified) c else Color(if (dark) d else l)
    return PermsPalette(
        dark = dark, ink = ink,
        dim = if (dim.isSpecified) dim else cs.onSurfaceVariant,
        accent = accent,
        onAccent = if (accent.luminance() > 0.5f) Color(0xFF1B1B1F) else Color.White,
        surface = pick(surface, 0x14FFFFFF, 0xF2FFFFFF),
        hairline = pick(hairline, 0x24FFFFFF, 0x1F000000),
        tile = accent.copy(alpha = if (dark) 0.22f else 0.12f),
        ok = pick(ok, 0xFF81C995, 0xFF1E7B34),
        warn = pick(warn, 0xFFF2B84B, 0xFF9A5B00),
        bad = pick(bad, 0xFFF28B82, 0xFFC5221F),
    )
}

/** Row icons, drawn from the catalogue's `icons` path data (perms-catalogue.json); built once per name. */
object PermIcons {
    private val cache = HashMap<String, ImageVector>()

    fun vector(ctx: Context, name: String): ImageVector = synchronized(cache) {
        cache[name] ?: build(name, Perms.catalogue(ctx).icons[name] ?: Perms.catalogue(ctx).icons["security"].orEmpty()).also { cache[name] = it }
    }

    private fun build(name: String, d: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(runCatching { addPathNodes(d) }.getOrDefault(emptyList()), fill = SolidColor(Color.Black)).build()
}

object PermsTags {
    const val SECTION = "perms:section"
    const val SUMMARY = "perms:summary"
    const val PROGRESS = "perms:progress"
    const val GRANT_ALL = "perms:grant-all"
    const val ALL_GRANTED = "perms:all-granted"
    const val HELPERS = "perms:helpers"
    const val HEALTH_EXPAND = "perms:health:expand"
    const val PROMPT = "perms:prompt"
    const val PROMPT_ACTION = "perms:prompt:grant"
    const val DISMISS = "perms:prompt:dismiss"
    const val SHEET = "perms:sheet"
    const val LATER = "perms:later"
    const val TITLE = "perms:title"
    fun row(id: String) = "perms:row:$id"
    fun state(id: String) = "perms:state:$id"
    fun grant(id: String) = "perms:grant:$id"
    fun group(title: String) = "perms:group:$title"
}

/**
 * THE Perms list of every app: the summary card (X of N granted, the bar, Grant all), then one card per group. [onAction]
 * runs a row's Grant / Open; [onGrantAll] null = no Grant all. [iconOf] lets a host draw its own icon set by name (Cloud
 * Me's libs:ui-icons); null falls back to the catalogue's.
 */
@Composable
fun PermsList(
    items: List<PermItem>,
    onAction: (PermItem) -> Unit,
    onGrantAll: (() -> Unit)?,
    modifier: Modifier = Modifier,
    style: PermsStyle = PermsStyle(),
    iconOf: (String) -> ImageVector? = { null },
) {
    val p = style.resolve()
    val prog = PermsView.progress(items)
    Column(modifier.fillMaxWidth().testTag(PermsTags.SECTION)) {
        SummaryCard(items.isEmpty(), prog, onGrantAll, p)
        for (g in PermsView.groups(items)) GroupBlock(g, items, onAction, p, iconOf)
    }
}

@Composable
private fun PermCard(p: PermsPalette, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(PermsType.corner)
    Column(modifier.fillMaxWidth().clip(shape).background(p.surface).border(1.dp, p.hairline, shape)) { content() }
}

@Composable
private fun SummaryCard(empty: Boolean, prog: PermsView.Progress, onGrantAll: (() -> Unit)?, p: PermsPalette) {
    PermCard(p, Modifier.testTag(PermsTags.SUMMARY)) {
        Column(Modifier.padding(PermsType.inset)) {
            if (empty || prog.total == 0) {
                Text("This app needs no permission.", color = p.ink, fontSize = PermsType.title, modifier = Modifier.testTag(PermsTags.ALL_GRANTED))
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(prog.label, color = p.ink, fontSize = PermsType.headline, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { heading() }.testTag(PermsTags.PROGRESS))
                Pill(if (prog.missing == 0) "All set" else "${prog.missing} missing", p.tone(if (prog.missing == 0) PermChip.Tone.OK else PermChip.Tone.WARN))
            }
            LinearProgressIndicator(
                progress = { prog.fraction }, color = if (prog.missing == 0) p.ok else p.accent, trackColor = p.hairline,
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().padding(top = PermsType.inset - PermsType.gap).height(PermsType.pad).clip(CircleShape)
                    .semantics { stateDescription = prog.label },
            )
            if (prog.missing == 0) Text("Everything this app needs is granted.", color = p.dim, fontSize = PermsType.caption,
                modifier = Modifier.padding(top = PermsType.inset - PermsType.gap).testTag(PermsTags.ALL_GRANTED))
            else if (onGrantAll != null) Button(
                onClick = onGrantAll,
                colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
                modifier = Modifier.fillMaxWidth().padding(top = PermsType.inset).heightIn(min = 48.dp).testTag(PermsTags.GRANT_ALL),
            ) { Text("Grant all", fontSize = PermsType.title, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun GroupBlock(g: PermsView.Group, all: List<PermItem>, onAction: (PermItem) -> Unit, p: PermsPalette, iconOf: (String) -> ImageVector?) {
    var open by rememberSaveable(g.title) { mutableStateOf(!g.collapsed) }
    val ctx = LocalContext.current
    val head = Modifier.fillMaxWidth().padding(top = PermsType.pad, bottom = 0.dp).heightIn(min = 40.dp)
        .testTag(PermsTags.group(g.title)).then(if (g.title == PermsView.HELPERS) Modifier.testTag(PermsTags.HELPERS) else Modifier)
    Row(
        if (g.collapsed) head.clickable(onClickLabel = if (open) "Collapse" else "Expand", role = Role.Button) { open = !open }
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" } else head,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(g.title.uppercase(), color = p.dim, fontSize = PermsType.label, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = PermsType.gap).weight(1f).semantics { heading() })
        Text(g.count, color = p.dim, fontSize = PermsType.label, modifier = Modifier.padding(end = if (g.collapsed) 0.dp else PermsType.gap))
        if (g.collapsed) Icon(PermIcons.vector(ctx, if (open) "expand_less" else "expand_more"), contentDescription = null, tint = p.dim,
            modifier = Modifier.padding(start = PermsType.gap).size(20.dp))
    }
    if (!open) return
    PermCard(p) {
        if (g.title == PermsView.NOT_USED) Text("On the list for features that are not built yet. Grant all still asks for them.",
            color = p.dim, fontSize = PermsType.caption, modifier = Modifier.padding(start = PermsType.inset, end = PermsType.inset, top = PermsType.inset - PermsType.gap))
        if (g.title == PermsView.HEALTH) HealthBlock(g.items, onAction, p, iconOf)
        else g.items.forEachIndexed { i, it ->
            if (i > 0) HorizontalDivider(color = p.hairline, thickness = 1.dp, modifier = Modifier.padding(start = PermsType.dividerStart))
            PermRowView(it, onAction, p, iconOf)
        }
    }
}

/** Health Connect: one row (one Health Connect screen grants every type), the types folded under "Show N types". */
@Composable
private fun HealthBlock(health: List<PermItem>, onAction: (PermItem) -> Unit, p: PermsPalette, iconOf: (String) -> ImageVector?) {
    var open by rememberSaveable { mutableStateOf(false) }
    val first = health.first()
    val head = PermItem("health_connect", PermsView.HEALTH, PermsView.healthLine(health), first.icon, PermItemKind.HEALTH, PermsView.healthState(health),
        action = health.firstOrNull { it.missing && it.action != null }?.action)
    PermRowView(head, { _ -> health.firstOrNull { it.missing && it.action != null }?.let(onAction) }, p, iconOf)
    HorizontalDivider(color = p.hairline, thickness = 1.dp, modifier = Modifier.padding(start = PermsType.dividerStart))
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = if (open) "Hide data types" else "Show data types", role = Role.Button) { open = !open }
            .padding(start = PermsType.dividerStart, end = PermsType.inset).testTag(PermsTags.HEALTH_EXPAND),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(PermsView.typesToggle(health.size, open), color = p.accent, fontSize = PermsType.body, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(PermIcons.vector(ctx, if (open) "expand_less" else "expand_more"), contentDescription = null, tint = p.accent, modifier = Modifier.size(20.dp))
    }
    if (open) {
        for (t in health) Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = PermsType.dividerStart, end = PermsType.inset).testTag(PermsTags.row(t.id))
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(t.title, color = p.ink, fontSize = PermsType.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            ChipView(PermsView.chip(t.state), p, Modifier.testTag(PermsTags.state(t.id)))
        }
        Spacer(Modifier.height(PermsType.pad))
    }
}

/** One row: icon tile, title, one-line reason, the state chip, and the one trailing action. */
@Composable
private fun PermRowView(it: PermItem, onAction: (PermItem) -> Unit, p: PermsPalette, iconOf: (String) -> ImageVector?) {
    val ctx = LocalContext.current
    val chip = PermsView.chip(it.state)
    val stateChip: @Composable (Modifier) -> Unit = { m -> ChipView(chip, p, m.testTag(PermsTags.state(it.id))) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = PermsType.rowMin).padding(horizontal = PermsType.inset, vertical = PermsType.pad)
            .testTag(PermsTags.row(it.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(PermsType.tile).clip(RoundedCornerShape(12.dp)).background(p.tile), contentAlignment = Alignment.Center) {
            Icon(iconOf(it.icon) ?: PermIcons.vector(ctx, it.icon.ifBlank { "security" }), contentDescription = null, tint = p.accent,
                modifier = Modifier.size(PermsType.icon))
        }
        Column(Modifier.weight(1f).padding(start = PermsType.inset, end = PermsType.pad).semantics(mergeDescendants = true) {}) {
            Text(it.title, color = p.ink, fontSize = PermsType.title, fontWeight = if (it.highlight) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = PermsView.reasonLine(it)
            if (line.isNotBlank()) Text(line, color = p.dim, fontSize = PermsType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp))
            // dense: the chip sits under the title only when the row also carries its Grant / Open button
            if (showsAction(it)) stateChip(Modifier.padding(top = PermsType.gap))
        }
        val a = it.action
        if (!showsAction(it)) stateChip(Modifier)
        else if (a != null) FilledTonalButton(
            onClick = { onAction(it) },
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = p.tile, contentColor = p.accent),
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "${a.word} ${it.title}" }.testTag(PermsTags.grant(it.id)),
        ) { Text(a.word, fontSize = PermsType.body, fontWeight = FontWeight.SemiBold) }
    }
}

private fun showsAction(it: PermItem) = it.action != null && (it.missing || it.state == PermState.UNKNOWN)

@Composable
internal fun ChipView(chip: PermChip, p: PermsPalette, modifier: Modifier = Modifier) = Pill(chip.word, p.tone(chip.tone), modifier)

@Composable
private fun Pill(word: String, c: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(c.copy(alpha = 0.14f)).padding(horizontal = PermsType.pad, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(6.dp))
        Text(word, color = c, fontSize = PermsType.label, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * THE compact in-place prompt (PermsGate, Cloud Drive's git engine grant, Cloud Me's banner): an icon, one sentence, a
 * Grant button and a close. Dismissed per [dismissKey]: a different set of missing grants brings it back.
 */
@Composable
fun PermsPromptCard(
    text: String,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
    button: String = PermsGate.BUTTON,
    icon: String = "security",
    dismissKey: String? = text,
    style: PermsStyle = PermsStyle(),
    tag: String = PermsTags.PROMPT,
) {
    if (text.isBlank()) return
    var dismissed by rememberSaveable { mutableStateOf<String?>(null) }
    if (dismissKey != null && dismissed == dismissKey) return
    val p = style.resolve()
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(PermsType.corner)
    Row(
        modifier.fillMaxWidth().clip(shape).background(p.surface).border(1.dp, p.warn.copy(alpha = 0.6f), shape)
            .padding(start = PermsType.pad + PermsType.gap, top = PermsType.pad, bottom = PermsType.pad, end = PermsType.gap).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(p.warn.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(PermIcons.vector(ctx, icon), contentDescription = null, tint = p.warn, modifier = Modifier.size(20.dp))
        }
        Text(text, color = p.ink, fontSize = PermsType.body, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = PermsType.pad + PermsType.gap))
        TextButton(onClick = onGrant, modifier = Modifier.heightIn(min = 48.dp).testTag(PermsTags.PROMPT_ACTION)) {
            Text(button, color = p.accent, fontSize = PermsType.body, fontWeight = FontWeight.SemiBold)
        }
        if (dismissKey != null) IconButton(onClick = { dismissed = dismissKey }, modifier = Modifier.size(48.dp).testTag(PermsTags.DISMISS)) {
            Icon(PermIcons.vector(ctx, "close"), contentDescription = "Dismiss", tint = p.dim, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * The place of a [PermsPromptCard] on a page that refreshes or loads: the card ABOVE the content, never inside a
 * pull-to-refresh box or under a progress indicator, so neither ever draws over it. [content] fills what is left.
 */
@Composable
fun PermsPromptAbove(prompt: @Composable () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        prompt()
        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
    }
}

/**
 * The first-run sheet's body: what is missing, in plain words, with Grant all and Later. Drawn inside PermsActivity's
 * bottom sheet; never a chain of system dialogs without this context first.
 */
@Composable
internal fun FirstRunSheetBody(appName: String, items: List<PermItem>, onGrantAll: () -> Unit, onLater: () -> Unit, style: PermsStyle = PermsStyle()) {
    val p = style.resolve()
    val ctx = LocalContext.current
    val missing = PermsView.missing(items)
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp).testTag(PermsTags.SHEET), verticalArrangement = Arrangement.spacedBy(PermsType.pad)) {
        Text("Before you start", color = p.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() }.testTag(PermsTags.TITLE))
        Text("$appName needs ${if (missing.size == 1) "one permission" else "${missing.size} permissions"} to work fully. You can change them any time in Configs, Perms.",
            color = p.dim, fontSize = PermsType.body)
        Spacer(Modifier.height(PermsType.gap))
        for (it in missing.take(SHEET_ROWS)) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {}.testTag(PermsTags.row(it.id)),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(p.tile), contentAlignment = Alignment.Center) {
                Icon(PermIcons.vector(ctx, it.icon.ifBlank { "security" }), contentDescription = null, tint = p.accent, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).padding(start = PermsType.inset - PermsType.gap)) {
                Text(it.title, color = p.ink, fontSize = PermsType.body, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(PermsView.reasonLine(it), color = p.dim, fontSize = PermsType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (missing.size > SHEET_ROWS) Text("and ${missing.size - SHEET_ROWS} more", color = p.dim, fontSize = PermsType.caption,
            modifier = Modifier.padding(start = 48.dp))
        Row(Modifier.fillMaxWidth().padding(top = PermsType.pad), horizontalArrangement = Arrangement.spacedBy(PermsType.pad), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onLater, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(PermsTags.LATER)) {
                Text("Later", color = p.accent, fontSize = PermsType.title)
            }
            Button(onClick = onGrantAll, colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
                modifier = Modifier.weight(2f).heightIn(min = 48.dp).testTag(PermsTags.GRANT_ALL)) {
                Text("Grant all", fontSize = PermsType.title, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private const val SHEET_ROWS = 6
