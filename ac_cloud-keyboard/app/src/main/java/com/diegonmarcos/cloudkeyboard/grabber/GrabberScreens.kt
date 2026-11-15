package com.diegonmarcos.cloudkeyboard.grabber

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * #1754 the two Grabber subpages (Grabber > Text Selection and Screen Selection), drawn inside the settings chrome through
 * KeyboardSettingsExtras.hostPage (App.kt). One model per page, kept for the process so a move between subpages loses nothing.
 */
object GrabberHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var text: GrabberModel? = null
    private var screen: GrabberModel? = null

    @Composable
    fun Page(id: String) {
        val app = LocalContext.current.applicationContext
        LaunchedEffect(Unit) { GrabberBus.recents = GrabberStore.load(app) }
        GrabberTheme {
            when (id) {
                "grabber.screen" -> ScreenSelectionPage(screen ?: GrabberModel(app, scope, "screen").also { screen = it })
                else -> TextSelectionPage(text ?: GrabberModel(app, scope, "page").also { text = it })
            }
        }
    }
}

@Composable
private fun Caps(text: String, color: Color = GC.text3, modifier: Modifier = Modifier) =
    Text(text, modifier, color = color, fontFamily = Mono, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp, maxLines = 1)

/** The head of a page: what it is, in one console line, and the real state of its entry points. */
@Composable
private fun Banner(title: String, blurb: String, readings: List<Triple<String, String, Color>>) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Brush.horizontalGradient(listOf(GC.accent.copy(alpha = 0.16f), GC.box))).border(1.dp, GC.hairline, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(GC.accent))
            Caps("GRABBER", GC.accent)
            Caps("/ $title", GC.text2)
        }
        Text(blurb, color = GC.text2, fontSize = 12.sp)
        readings.forEach { (k, v, c) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(c))
                Caps(k, GC.text3, Modifier.width(92.dp))
                Text(v, color = GC.text, fontFamily = Mono, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun menuEntryRegistered(ctx: Context): Boolean = runCatching {
    val probe = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
    ctx.packageManager.queryIntentActivities(probe, 0).any { it.activityInfo.name == GrabberActivity::class.java.name && it.activityInfo.packageName == ctx.packageName }
}.getOrDefault(false)

@Composable
private fun TextSelectionPage(model: GrabberModel) {
    val ctx = LocalContext.current
    val registered = remember { menuEntryRegistered(ctx) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Banner(
            "TEXT SELECTION",
            "Select a word, a phrase or an address in any app, open the selection menu and pick Cloud Keyboard: this panel opens over that app. Here it takes typed or pasted text.",
            listOf(
                Triple("MENU ENTRY", if (registered) "Cloud Keyboard · registered for text/plain" else "not registered on this device", if (registered) GC.ok else GC.bad),
                Triple("RESULT", "editable field: the result replaces the selection", GC.accent),
            ),
        )
        GrabberPanel(model, onReply = {})
        RecentsBox(GrabberBus.recents.filter { it.source != "screen" }, onPick = { model.setText(it.text); model.grab() })
    }
}

@Composable
private fun ScreenSelectionPage(model: GrabberModel) {
    val ctx = LocalContext.current
    val shot = GrabberBus.lastShot
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Banner(
            "SCREEN SELECTION",
            "Circle to search: capture the screen, drag around the text you want, and it is read on this phone and fed to the same panel. Start it here or from the Grabber tile in Quick Settings, over any app.",
            listOf(
                Triple("CAPTURE", "MediaProjection · you confirm every capture", GC.ok),
                Triple("TEXT READER", "ML Kit on device · Latin, Chinese, Devanagari, Japanese, Korean", GC.ok),
                Triple("TILE", "Quick Settings > Edit > Grabber", GC.accent),
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyChip("Capture screen", accent = true) { startCapture(ctx, null) }
            if (Build.VERSION.SDK_INT >= 33) KeyChip("Add Quick Settings tile") { addTile(ctx) }
            if (shot != null && File(shot.path).isFile) KeyChip("Reopen last capture") { startCapture(ctx, shot.path) }
        }
        LastShot(shot)
        GrabberPanel(model, onReply = {})
        RecentsBox(GrabberBus.recents.filter { it.source == "screen" }, title = "RECENT SCREEN GRABS", onPick = { model.setText(it.text); model.grab() })
    }
}

private fun startCapture(ctx: Context, path: String?) {
    val i = Intent(ctx, GrabberScreenActivity::class.java).putExtra(GrabberScreenActivity.EXTRA_DELAY, 500L)
    if (path != null) i.putExtra(GrabberScreenActivity.EXTRA_PATH, path)
    try { ctx.startActivity(i) } catch (e: Exception) { Toast.makeText(ctx, "Could not start the capture: ${e.message}", Toast.LENGTH_LONG).show() }
}

private fun addTile(ctx: Context) {
    try {
        val sbm = ctx.getSystemService(StatusBarManager::class.java)
        sbm.requestAddTileService(ComponentName(ctx, GrabberTileService::class.java), "Grabber",
            Icon.createWithResource(ctx, android.R.drawable.ic_menu_crop), ctx.mainExecutor) { _ -> }
    } catch (e: Exception) {
        Toast.makeText(ctx, "Add it by hand: Quick Settings > Edit > Grabber", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun LastShot(shot: ShotInfo?) {
    val thumb = remember(shot?.path) {
        shot?.let { s -> runCatching { BitmapFactory.decodeFile(s.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() }.getOrNull() }
    }
    ConsoleBox("LAST CAPTURE", if (shot != null) GC.ok else GC.text3, reading = shot?.let { "${it.width}×${it.height}" } ?: "NONE") {
        if (shot == null || thumb == null) {
            Text("No capture yet. Tap Capture screen, or use the tile.", color = GC.text3, fontSize = 12.sp)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(thumb, null, Modifier.height(96.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, GC.hairline, RoundedCornerShape(6.dp)), contentScale = ContentScale.Fit)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(DateUtils.getRelativeTimeSpanString(shot.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(), color = GC.text, fontSize = 12.sp)
                    Caps("${shot.width} × ${shot.height} PX", GC.text3)
                    Text("Kept in this app's cache only.", color = GC.text3, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun RecentsBox(list: List<Recent>, title: String = "RECENT GRABS", onPick: (Recent) -> Unit) {
    val ctx = LocalContext.current
    ConsoleBox(title, if (list.isEmpty()) GC.text3 else GC.ok, reading = "${list.size} / ${GrabberRecents.MAX}") {
        if (list.isEmpty()) Text("Everything you grab is listed here, newest first, and stays on this phone.", color = GC.text3, fontSize = 12.sp)
        else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            list.forEach { r ->
                Row(Modifier.fillMaxWidth().clickable { onPick(r) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(GC.kind(runCatching { SelectionKind.valueOf(r.kind) }.getOrDefault(SelectionKind.PHRASE)).copy(alpha = 0.16f)).padding(horizontal = 5.dp, vertical = 2.dp)) {
                        Text(r.kind.ifEmpty { "TEXT" }, color = GC.kind(runCatching { SelectionKind.valueOf(r.kind) }.getOrDefault(SelectionKind.PHRASE)), fontFamily = Mono, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(r.text, color = GC.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Caps(DateUtils.getRelativeTimeSpanString(r.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().uppercase(), GC.text3)
                    Text("×", color = GC.text3, fontSize = 16.sp, modifier = Modifier.clickable { GrabberStore.remove(ctx, r.text) }.padding(horizontal = 6.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            KeyChip("Clear all") { GrabberStore.clear(ctx) }
        }
    }
}

// ── drag a region over the captured screen ──────────────────────────────────

/**
 * The captured screen full-bleed, dimmed; the user draws around the text (any shape: the bounding box of the stroke is the region,
 * like circle to search). [onRegion] gets the box in view pixels and the view size; [reading] shows the scan while the text is read.
 */
@Composable
fun RegionPicker(
    bitmap: Bitmap,
    reading: Boolean,
    onRegion: (android.graphics.Rect, IntSize) -> Unit,
    onWhole: () -> Unit,
    onCancel: () -> Unit,
) {
    val img = remember(bitmap) { bitmap.asImageBitmap() }
    val points = remember { mutableStateListOf<Offset>() }
    var view by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableStateOf(false) }
    val t = rememberInfiniteTransition(label = "scan")
    val sweep by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    val pulse by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulse")

    fun box(): androidx.compose.ui.geometry.Rect? {
        if (points.size < 2) return null
        val pad = 10f
        return androidx.compose.ui.geometry.Rect(
            max(0f, points.minOf { it.x } - pad), max(0f, points.minOf { it.y } - pad),
            min(view.width.toFloat(), points.maxOf { it.x } + pad), min(view.height.toFloat(), points.maxOf { it.y } + pad),
        )
    }

    Box(Modifier.fillMaxSize().onSizeChanged { view = it }) {
        Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(
            Modifier.fillMaxSize().pointerInput(reading) {
                if (reading) return@pointerInput
                detectDragGestures(
                    onDragStart = { o -> points.clear(); points.add(o); dragging = true },
                    onDrag = { c, _ -> points.add(c.position) },
                    onDragEnd = {
                        dragging = false
                        val b = box()
                        if (b == null || b.width < 28f || b.height < 28f) points.clear()
                        else onRegion(android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()), view)
                    },
                    onDragCancel = { dragging = false; points.clear() },
                )
            },
        ) {
            val b = box()
            val dim = Color.Black
            if (b == null) {
                drawRect(dim.copy(alpha = 0.38f))
            } else {
                val a = 0.64f
                drawRect(dim.copy(alpha = a), Offset.Zero, Size(size.width, b.top))
                drawRect(dim.copy(alpha = a), Offset(0f, b.bottom), Size(size.width, size.height - b.bottom))
                drawRect(dim.copy(alpha = a), Offset(0f, b.top), Size(b.left, b.height))
                drawRect(dim.copy(alpha = a), Offset(b.right, b.top), Size(size.width - b.right, b.height))
                drawRect(GC.accent.copy(alpha = 0.10f), b.topLeft, b.size, style = Stroke(10.dp.toPx()))
                drawRect(GC.accent.copy(alpha = 0.75f), b.topLeft, b.size, style = Stroke(1.5.dp.toPx()))
                val l = 22.dp.toPx(); val w = 3.dp.toPx()
                fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                    drawLine(GC.accent, Offset(x, y), Offset(x + dx * l, y), w, StrokeCap.Round)
                    drawLine(GC.accent, Offset(x, y), Offset(x, y + dy * l), w, StrokeCap.Round)
                }
                corner(b.left, b.top, 1f, 1f); corner(b.right, b.top, -1f, 1f); corner(b.left, b.bottom, 1f, -1f); corner(b.right, b.bottom, -1f, -1f)
                if (reading) {
                    val y = b.top + b.height * sweep
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, GC.accent.copy(alpha = 0.45f), Color.Transparent), startY = y - 28.dp.toPx(), endY = y + 28.dp.toPx()), Offset(b.left, max(b.top, y - 28.dp.toPx())), Size(b.width, min(b.bottom, y + 28.dp.toPx()) - max(b.top, y - 28.dp.toPx())))
                    drawLine(GC.accent, Offset(b.left, y), Offset(b.right, y), 2.dp.toPx())
                }
            }
            if (points.size >= 2) {
                val p = Path().apply { moveTo(points[0].x, points[0].y); for (i in 1 until points.size) lineTo(points[i].x, points[i].y) }
                drawPath(p, GC.accent.copy(alpha = if (dragging) 0.28f else 0.12f), style = Stroke(14.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(p, GC.accent, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        val b = box()
        if (reading && b != null) {
            Caps("READING THE TEXT ON THIS DEVICE", GC.accent, Modifier.offset { IntOffset(b.left.roundToInt(), (b.bottom + 8.dp.toPx()).roundToInt()) })
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(GC.accent))
            Caps("GRABBER · SCREEN SELECTION", GC.text)
            Spacer(Modifier.weight(1f))
            KeyChip("Whole screen", enabled = !reading) { onWhole() }
            KeyChip("Cancel") { onCancel() }
        }
        if (b == null && !reading) {
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp).clip(RoundedCornerShape(20.dp)).background(GC.sheet.copy(alpha = 0.92f)).border(1.dp, GC.hairline, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(GC.accent.copy(alpha = pulse)))
                Text("Drag around the text to read it", color = GC.text, fontSize = 13.sp)
            }
        }
    }
}
