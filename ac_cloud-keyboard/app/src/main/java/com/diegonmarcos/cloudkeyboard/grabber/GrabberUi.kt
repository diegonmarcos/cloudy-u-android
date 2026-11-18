package com.diegonmarcos.cloudkeyboard.grabber

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The Grabber's operations-console palette: near-black blue surfaces, hairlines, one cyan accent, the fleet's three state colours. */
object GC {
    val bg = Color(0xFF080B0F)
    val sheet = Color(0xFF0E1319)
    val box = Color(0xFF121821)
    val boxHi = Color(0xFF18202B)
    val key = Color(0xFF1B2430)
    val keyHi = Color(0xFF283546)
    val hairline = Color(0xFF222C38)
    val text = Color(0xFFE6EDF4)
    val text2 = Color(0xFF8E9BA9)
    val text3 = Color(0xFF5D6A78)
    val accent = Color(0xFF4CC2FF)
    val ok = Color(0xFF7FC98F)
    val warn = Color(0xFFE2B85C)
    val bad = Color(0xFFE5737A)
    val skel = Color(0xFF18212B)
    val skelHi = Color(0xFF263341)

    fun kind(k: SelectionKind): Color = when (k) {
        SelectionKind.WORD -> accent
        SelectionKind.PHRASE -> Color(0xFF9B8CFF)
        SelectionKind.ADDRESS, SelectionKind.PLACE -> warn
        SelectionKind.EMPTY -> text3
    }

    fun group(g: GrabberGroup): Color = when (g) {
        GrabberGroup.CLIPBOARD -> text2
        GrabberGroup.TEXT -> text
        GrabberGroup.SEARCH -> Color(0xFFDE6A3B)
        GrabberGroup.AI -> Color(0xFFB08CFF)
        GrabberGroup.DRIVE -> warn
        GrabberGroup.WRITER -> accent
    }

    /** The brand dot of a search or AI action. */
    fun dot(a: GrabberAction): Color? = when (a) {
        GrabberAction.DUCKDUCKGO -> Color(0xFFDE5833)
        GrabberAction.GOOGLE -> Color(0xFF4C8BF5)
        GrabberAction.QWANT -> Color(0xFF7D6BFF)
        GrabberAction.GEMINI -> Color(0xFF8AB4F8)
        GrabberAction.CLAUDE -> Color(0xFFD97757)
        GrabberAction.CLOUD_SI -> accent
        else -> null
    }
}

val Mono: FontFamily = FontFamily.Monospace

/** Every Grabber screen sits in the fleet's dense kit theme (no 24sp lines, no 48dp floors) painted with the console palette. */
@Composable
fun GrabberTheme(content: @Composable () -> Unit) {
    val palette = remember {
        KitPalette.fromArgb(GC.sheet.toArgb(), GC.boxHi.toArgb(), GC.text.toArgb(), GC.text2.toArgb(), GC.accent.toArgb(), GC.hairline.toArgb(), GC.bg.toArgb(), GC.ok.toArgb(), GC.warn.toArgb(), GC.bad.toArgb())
    }
    CloudKitTheme(palette, content)
}

@Composable
private fun Caps(text: String, color: Color = GC.text3, modifier: Modifier = Modifier) =
    Text(text, modifier, color = color, fontFamily = Mono, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp, maxLines = 1)

/** A key of the panel: dense, keyboard-like, springs down when pressed. */
@Composable
fun KeyChip(
    label: String,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    accent: Boolean = false,
    enabled: Boolean = true,
    style: TextStyle = TextStyle.Default,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, spring(dampingRatio = 0.55f, stiffness = 700f), label = "key")
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.45f }
            .clip(shape)
            .background(if (accent) GC.accent.copy(alpha = if (pressed) 0.34f else 0.20f) else if (pressed) GC.keyHi else GC.key)
            .border(1.dp, if (accent) GC.accent.copy(alpha = 0.55f) else GC.hairline, shape)
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(label, style = style, color = if (accent) GC.accent else GC.text, fontSize = 11.5.sp, maxLines = 1)
    }
}

@Composable
fun shimmer(): Brush {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x")
    return Brush.linearGradient(listOf(GC.skel, GC.skelHi, GC.skel), start = Offset(x * 900f - 300f, 0f), end = Offset(x * 900f + 100f, 40f))
}

@Composable
private fun StatusDot(color: Color, pulse: Boolean = false) {
    if (!pulse) { Box(Modifier.size(7.dp).clip(CircleShape).background(color)); return }
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(7.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(color))
}

/** The frame of a box: a dot, a mono title, a right-hand reading (time, state), a hairline, then the content. */
@Composable
fun ConsoleBox(title: String, dot: Color, pulse: Boolean = false, reading: String = "", modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(GC.box).border(1.dp, GC.hairline, shape)) {
        Row(Modifier.fillMaxWidth().background(GC.boxHi.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            StatusDot(dot, pulse)
            Caps(title, GC.text2)
            Spacer(Modifier.weight(1f))
            Caps(reading, GC.text3)
        }
        Box(Modifier.hairline(GC.hairline))
        Box(Modifier.fillMaxWidth().padding(10.dp).animateContentSize(spring(stiffness = 500f))) { content() }
    }
}

private fun Modifier.hairline(c: Color): Modifier = this.fillMaxWidth().height(1.dp).background(c)

private fun <T> stateDot(s: Load<T>): Color = when (s) {
    is Load.Idle -> GC.text3
    is Load.Loading -> GC.warn
    is Load.Ready -> GC.ok
    is Load.Failed -> if (s.reason == FailReason.EMPTY) GC.warn else GC.bad
}

private fun <T> stateReading(s: Load<T>): String = when (s) {
    is Load.Idle -> "IDLE"
    is Load.Loading -> "LOADING"
    is Load.Ready -> "${s.ms} MS"
    is Load.Failed -> s.reason.label
}

// ── the panel ───────────────────────────────────────────────────────────────

/** The panel shared by the sheet over another app and both pages: selection on top, then the three boxes. */
@Composable
fun GrabberPanel(model: GrabberModel, onReply: (ProcessTextReply) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectionBlock(model)
        ActionsBox(model, onReply)
        SearchBox(model)
        MediaBox(model)
        // #1793 a place card carries its distances, from the position Cloud Nav shares
        val m = model.media
        if (m is Load.Ready && m.value is MediaResult.MapView) TripBox(model)
    }
}

@Composable
private fun SelectionBlock(model: GrabberModel) {
    val kind = model.kind
    val text = model.draft.text
    val words = text.trim().split(Regex("""\s+""")).count { it.isNotEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Caps("SELECTION", GC.text2)
            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(GC.kind(kind).copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(kind.label, color = GC.kind(kind), fontFamily = Mono, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Caps("${text.length} ${if (text.length == 1) "CHAR" else "CHARS"} · $words ${if (words == 1) "WORD" else "WORDS"}", GC.text3)
            Spacer(Modifier.weight(1f))
            if (model.readOnly) Caps("READ-ONLY", GC.text3) else Caps("FIELD · REPLACES SELECTION", GC.ok)
        }
        val shape = RoundedCornerShape(10.dp)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(shape).background(GC.box).border(1.dp, GC.hairline, shape), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(GC.kind(kind)))
            BasicTextField(
                value = model.draft,
                onValueChange = { model.draft = it },
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 9.dp),
                textStyle = TextStyle(color = GC.text, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(GC.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { model.grab() }),
                maxLines = 5,
                decorationBox = { inner ->
                    Box { if (text.isEmpty()) Text("Type or paste the text to grab", color = GC.text3, fontSize = 14.sp); inner() }
                },
            )
            KeyChip("Grab", Modifier.padding(end = 6.dp), accent = true) { model.grab() }
        }
    }
}

@Composable
private fun ActionsBox(model: GrabberModel, onReply: (ProcessTextReply) -> Unit) {
    val busy = model.busy
    ConsoleBox("ACTIONS", if (busy != null) GC.warn else GC.accent, pulse = busy != null, reading = busy?.let { "RUNNING · ${it.uppercase()}" } ?: "${GrabberAction.entries.size} ACTIONS") {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            GrabberGroup.entries.forEach { g ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Caps(g.label.uppercase(), GC.group(g), Modifier.width(66.dp))
                    Box(Modifier.weight(1f)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        GrabberAction.of(g).forEach { a ->
                            val style = when (a) {
                                GrabberAction.BOLD -> TextStyle(fontWeight = FontWeight.Black)
                                GrabberAction.ITALIC -> TextStyle(fontStyle = FontStyle.Italic)
                                GrabberAction.UNDERLINE -> TextStyle(textDecoration = TextDecoration.Underline)
                                GrabberAction.STRIKE -> TextStyle(textDecoration = TextDecoration.LineThrough)
                                else -> TextStyle.Default
                            }
                            KeyChip(a.label, dot = GC.dot(a) ?: if (g == GrabberGroup.DRIVE || g == GrabberGroup.WRITER) GC.group(g) else null,
                                enabled = busy == null, style = style) { model.run(a, onReply) }
                        }
                    }
                    // a fade on the right edge says the row scrolls
                    Box(Modifier.align(Alignment.CenterEnd).size(width = 22.dp, height = 30.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, GC.box))))
                    }
                }
            }
            Box(Modifier.hairline(GC.hairline))
            LogStrip(model)
        }
    }
}

@Composable
private fun LogStrip(model: GrabberModel) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (model.log.isEmpty()) Text("› ready · pick an action", color = GC.text3, fontFamily = Mono, fontSize = 10.sp)
        model.log.take(3).forEachIndexed { i, l ->
            Text("${l.time} › ${l.what} · ${l.outcome}", color = if (!l.ok) GC.bad else if (i == 0) GC.ok else GC.text3,
                fontFamily = Mono, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SearchBox(model: GrabberModel) {
    val s = model.search
    ConsoleBox("TEXT SEARCH · DUCKDUCKGO", stateDot(s), pulse = s is Load.Loading, reading = stateReading(s)) {
        AnimatedContent(targetState = s, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(120)) }, label = "search") { st ->
            when (st) {
                is Load.Idle -> Hint("Grab a word or a phrase to see its first web result here.")
                is Load.Loading -> SkeletonLines()
                is Load.Ready -> ResultCard(st.value)
                is Load.Failed -> ErrorState(st, onRetry = { model.grab(remember = false) })
            }
        }
    }
}

@Composable
private fun MediaBox(model: GrabberModel) {
    val m = model.media
    // #1793 the title names what the card turned out to be: a place (geocoded) or an image for the term, and its source
    val title = when (val v = (m as? Load.Ready<*>)?.value) {
        is MediaResult.MapView -> "MEDIA · MAP · PLACE · OPENSTREETMAP"
        is MediaResult.Photo -> "MEDIA · IMAGE · " + (v.source.uppercase().ifBlank { "WEB" }.let { if (it.contains('.')) "FIRST IMAGE · DUCKDUCKGO" else it })
        is MediaResult.Placeholder -> "MEDIA · NOT A PLACE · NO IMAGE"
        else -> if (model.kind.showsMap) "MEDIA · MAP · OPENSTREETMAP" else "MEDIA · PLACE OR IMAGE · NOMINATIM"
    }
    ConsoleBox(title, stateDot(m), pulse = m is Load.Loading, reading = stateReading(m)) {
        AnimatedContent(targetState = m, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(120)) }, label = "media") { st ->
            when (st) {
                is Load.Idle -> Hint("The text is looked up on OpenStreetMap: a place shows its map, address, type and distances; anything else shows an image for it (Wikipedia, Wikimedia Commons).")
                is Load.Loading -> SkeletonImage()
                is Load.Ready -> MediaCard(st.value)
                is Load.Failed -> ErrorState(st, onRetry = { model.grab(remember = false) })
            }
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, color = GC.text3, fontSize = 12.sp)

@Composable
private fun SkeletonLines() {
    val b = shimmer()
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.fillMaxWidth(0.55f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(b))
        Box(Modifier.fillMaxWidth(0.3f).height(9.dp).clip(RoundedCornerShape(4.dp)).background(b))
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(4.dp)).background(b))
        Box(Modifier.fillMaxWidth(0.82f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(b))
    }
}

@Composable
private fun SkeletonImage() {
    Box(Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(8.dp)).background(shimmer()))
}

private fun open(ctx: Context, intent: Intent) {
    try { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Toast.makeText(ctx, "No app can open this", Toast.LENGTH_SHORT).show() }
}

@Composable
private fun ResultCard(hit: SearchHit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clickable { open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(hit.url))) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(hit.title, color = GC.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(GC.ok))
            Text(hit.host.ifEmpty { hit.url }, color = GC.ok, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Caps("· ${hit.source.uppercase()}", GC.text3)
        }
        if (hit.snippet.isNotBlank()) Text(hit.snippet, color = GC.text2, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.size(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            KeyChip("Open", accent = true) { open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(hit.url))) }
            KeyChip("Copy link") {
                ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Grabber", hit.url))
                Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** Offline, walled, empty or failed: each says which, says why, and offers the retry. */
@Composable
private fun ErrorState(f: Load.Failed, onRetry: () -> Unit) {
    val color = if (f.reason == FailReason.EMPTY) GC.warn else GC.bad
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)).border(1.dp, color.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
            Text(if (f.reason == FailReason.OFFLINE) "×" else "!", color = color, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Caps(f.reason.label, color)
            Text(f.detail.ifEmpty { "Nothing came back." }, color = GC.text2, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        KeyChip("Retry", onClick = onRetry)
    }
}

@Composable
private fun MediaCard(m: MediaResult) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (m) {
            is MediaResult.MapView -> MapCanvas(m, Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(8.dp)))
            is MediaResult.Photo -> Image(m.image, contentDescription = m.caption, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(8.dp)).background(GC.skel))
            is MediaResult.Placeholder -> PlaceholderFrame(m)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(m.caption, color = GC.text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                when (m) {
                    is MediaResult.MapView -> Text("Z${m.zoom} · ${"%.5f".format(java.util.Locale.ROOT, m.lat)}, ${"%.5f".format(java.util.Locale.ROOT, m.lon)}", color = GC.text3, fontFamily = Mono, fontSize = 10.sp)
                    is MediaResult.Photo -> if (m.source.isNotEmpty()) Text(m.source, color = GC.text3, fontFamily = Mono, fontSize = 10.sp)
                    is MediaResult.Placeholder -> Text("NOT A PLACE", color = GC.text3, fontFamily = Mono, fontSize = 10.sp)
                }
            }
            when (m) {
                is MediaResult.MapView -> KeyChip("Open in maps") {
                    open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:${m.lat},${m.lon}?q=${m.lat},${m.lon}(${Uri.encode(m.caption.take(60))})")))
                }
                is MediaResult.Photo -> if (m.pageUrl.isNotBlank()) KeyChip("Source") { open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(m.pageUrl))) }
                is MediaResult.Placeholder -> {}
            }
        }
        // #1793 the place: its address line and its type (Nominatim's addresstype, then category/type)
        if (m is MediaResult.MapView && m.place != null) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Caps("TYPE", GC.text3, Modifier.width(64.dp))
                    Caps(m.place.type, GC.accent)
                    if (m.place.detail.isNotBlank()) Text(m.place.detail, color = GC.text3, fontFamily = Mono, fontSize = 10.sp, maxLines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Caps("ADDRESS", GC.text3, Modifier.width(64.dp))
                    Text(m.place.address, color = GC.text2, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                if (m.tiles.none { it.image != null }) Text("Map tiles unavailable: the place and its distances still hold.", color = GC.warn, fontSize = 10.sp)
                Text("Map data © OpenStreetMap contributors (ODbL) · geocoding Nominatim", color = GC.text3, fontSize = 9.sp)
            }
        }
        // #1793 an image for a term carries who made it and under which licence (Wikimedia's terms)
        if (m is MediaResult.Photo && m.attribution.isNotBlank()) Text(m.attribution, color = GC.text3, fontSize = 9.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** #1793 not a place and no image: a neutral frame (an image glyph and the reason), never an empty box. */
@Composable
private fun PlaceholderFrame(m: MediaResult.Placeholder) {
    Box(Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(8.dp)).background(GC.skel).border(1.dp, GC.hairline, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(16.dp)) {
            Canvas(Modifier.size(width = 44.dp, height = 34.dp)) {
                val st = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                drawRoundRect(GC.text3, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = st)
                drawCircle(GC.text3, radius = 4.dp.toPx(), center = Offset(size.width * 0.70f, size.height * 0.32f))
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.10f, size.height * 0.85f); lineTo(size.width * 0.40f, size.height * 0.45f)
                    lineTo(size.width * 0.62f, size.height * 0.72f); lineTo(size.width * 0.75f, size.height * 0.58f); lineTo(size.width * 0.90f, size.height * 0.85f)
                }
                drawPath(p, GC.text3, style = st)
            }
            Caps("NO IMAGE FOR THIS TEXT", GC.text2)
            Text(m.reason, color = GC.text3, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * #1793 the distances of a place, from the position Cloud Nav shares (the keyboard never locates): by car, on foot and by
 * bicycle on the free public OSRM servers, and by air as the great-circle distance with an ESTIMATED flight time
 * (~800 km/h + 1 h). Without a position the card keeps the place and its map and says why there are no distances.
 */
@Composable
private fun TripBox(model: GrabberModel) {
    val ctx = LocalContext.current
    val t = model.trip
    ConsoleBox("DISTANCES · FROM YOUR LOCATION", stateDot(t), pulse = t is Load.Loading, reading = stateReading(t)) {
        when (t) {
            is Load.Idle, is Load.Loading -> SkeletonLines()
            is Load.Failed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Caps("NO POSITION · PLACE AND MAP ONLY", GC.warn)
                Text(t.detail, color = GC.text2, fontSize = 12.sp)
                Text("The position comes from Cloud Nav, the fleet's location app: it asks for approximate location first and precise location only if you allow it. The keyboard itself never locates.",
                    color = GC.text3, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!model.navMissing) KeyChip("Open Cloud Nav to refresh", accent = true) {
                        if (!GrabberLocation.openNav(ctx)) Toast.makeText(ctx, "Cloud Nav is not installed", Toast.LENGTH_SHORT).show()
                    }
                    KeyChip("Retry") { model.retryTrip() }
                }
                if (!model.navMissing) Text("Watching for Cloud Nav's new position: the distances appear by themselves when it has one.", color = GC.text3, fontSize = 10.sp)
            }
            is Load.Ready -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val p = t.value
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Caps("FROM", GC.text3, Modifier.width(86.dp))
                    Text("${"%.4f".format(java.util.Locale.ROOT, p.fromLat)}, ${"%.4f".format(java.util.Locale.ROOT, p.fromLon)}", color = GC.text2, fontFamily = Mono, fontSize = 10.sp)
                }
                // #1793 the fix's age and source on its own line: "from your position 12 min ago · CLOUD NAV · ±20 m"
                Text(p.fromNote, color = if (p.stale) GC.warn else GC.text2, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // #1793 a fix over 30 min old still measures, but says so and offers the refresh (recomputed when Nav has a newer one)
                if (p.stale) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(GC.warn))
                    Caps("STALE · OVER 30 MIN", GC.warn, Modifier.weight(1f))
                    KeyChip("Open Cloud Nav to refresh", accent = true) {
                        if (!GrabberLocation.openNav(ctx)) Toast.makeText(ctx, "Cloud Nav is not installed", Toast.LENGTH_SHORT).show()
                    }
                }
                Box(Modifier.hairline(GC.hairline))
                TravelMode.entries.filter { it.routed }.forEach { mode ->
                    val leg = model.legs[mode] ?: Load.Idle
                    TripRow(mode.label, leg)
                }
                Box(Modifier.hairline(GC.hairline))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(GC.accent))
                    Caps(TravelMode.AIR.label, GC.text2, Modifier.width(118.dp))
                    Text(GeoMath.km(p.airKm * 1000.0), color = GC.text, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.width(78.dp))
                    Text("≈ " + GeoMath.duration(p.flightHours * 3600.0), color = GC.warn, fontFamily = Mono, fontSize = 11.sp)
                }
                Text("Air: great-circle distance; the flight time is an ESTIMATE (~800 km/h cruise + 1 h for take-off, landing and taxi), not a schedule.",
                    color = GC.text3, fontSize = 9.5.sp)
                Text("Routes: OSRM on routing.openstreetmap.de (FOSSGIS), OpenStreetMap data.", color = GC.text3, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun TripRow(label: String, leg: Load<Leg>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(stateDot(leg)))
        Caps(label, GC.text2, Modifier.width(118.dp))
        when (leg) {
            is Load.Ready -> {
                Text(GeoMath.km(leg.value.meters), color = GC.text, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.width(78.dp))
                Text(GeoMath.duration(leg.value.seconds), color = GC.ok, fontFamily = Mono, fontSize = 11.sp)
            }
            is Load.Failed -> Text(if (leg.reason == FailReason.EMPTY) "no route" else "${leg.reason.label} · ${leg.detail}", color = GC.text3, fontFamily = Mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else -> Box(Modifier.width(120.dp).height(10.dp).clip(RoundedCornerShape(4.dp)).background(shimmer()))
        }
    }
}

/** Light OSM tiles turned into a dark console map: invert, then rotate the hue 180 degrees so water stays blue; dimmed. */
private val DARK_MAP: ColorFilter = run {
    val d = 0.88f
    val m = floatArrayOf(
        0.574f * d, -1.430f * d, -0.144f * d, 0f, 255f * d,
        -0.426f * d, -0.430f * d, -0.144f * d, 0f, 255f * d,
        -0.426f * d, -1.430f * d, 0.856f * d, 0f, 255f * d,
        0f, 0f, 0f, 1f, 0f,
    )
    ColorFilter.colorMatrix(ColorMatrix(m))
}

@Composable
private fun MapCanvas(m: MediaResult.MapView, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "pin")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1700, easing = LinearOutSlowInEasing), RepeatMode.Restart), label = "pulse")
    Canvas(modifier.background(GC.skel)) {
        val s = size.width / GrabberNet.MAP_W
        m.tiles.forEach { lt ->
            val img = lt.image ?: return@forEach
            drawImage(
                img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                dstOffset = IntOffset((lt.tile.dx * s).roundToInt(), (lt.tile.dy * s).roundToInt()),
                dstSize = IntSize(ceil(TileMath.TILE * s).toInt(), ceil(TileMath.TILE * s).toInt()),
                colorFilter = DARK_MAP,
            )
        }
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, GC.bg.copy(alpha = 0.45f))))
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(GC.accent.copy(alpha = 0.32f * (1f - pulse)), radius = 9.dp.toPx() + 26.dp.toPx() * pulse, center = c)
        drawCircle(Color.Black.copy(alpha = 0.55f), radius = 9.dp.toPx(), center = c)
        drawCircle(GC.accent, radius = 6.5.dp.toPx(), center = c)
        drawCircle(Color.White, radius = 2.5.dp.toPx(), center = c)
    }
}

// ── the bottom sheet ────────────────────────────────────────────────────────

/**
 * The sheet that opens over the app a text was selected in: a scrim, a rounded panel that springs up like the keyboard, drag down
 * or tap outside to close. [onReply] is called when an action returned a result for the field; [onClose] after the exit animation.
 */
@Composable
fun GrabberSheet(model: GrabberModel, onReply: (ProcessTextReply) -> Unit, onClose: () -> Unit, heading: String = "GRABBER · CLOUD KEYBOARD") {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    fun dismiss() { state.targetState = false }
    BackHandler { dismiss() }
    LaunchedEffect(state.currentState, state.isIdle) { if (state.isIdle && !state.currentState) onClose() }
    val scrim by animateFloatAsState(if (state.targetState) 0.62f else 0f, tween(280), label = "scrim")
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { dismiss() })
        AnimatedVisibility(
            visibleState = state,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.82f, stiffness = 360f)) { it } + fadeIn(tween(180)),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(180)),
        ) {
            val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxH).clip(shape).background(GC.sheet).border(1.dp, GC.hairline, shape)
                    .navigationBarsPadding().imePadding(),
            ) {
                var drag by remember { mutableFloatStateOf(0f) }
                Box(
                    Modifier.fillMaxWidth().pointerInput(Unit) {
                        detectVerticalDragGestures(onDragEnd = { if (drag > 90f) dismiss(); drag = 0f }, onDragCancel = { drag = 0f }) { _, dy -> drag += dy }
                    }.padding(top = 8.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(width = 38.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(GC.keyHi)) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(GC.accent)
                    Caps(heading, GC.text2)
                    Spacer(Modifier.weight(1f))
                    KeyChip("Close") { dismiss() }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 12.dp, top = 4.dp)) {
                    GrabberPanel(model, onReply)
                }
            }
        }
    }
}
