package com.diegonmarcos.cloudcanvas.ui

import android.graphics.Color as AColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudlib.canvas.core.R
import com.diegonmarcos.cloudcanvas.draw.CanvasPresets
import com.diegonmarcos.cloudcanvas.draw.DocRenderer
import com.diegonmarcos.cloudcanvas.draw.Tool
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.diegonmarcos.cloudcanvas.draw.Brushes
import com.diegonmarcos.cloudcanvas.draw.ColorWheel

private val PALETTE = listOf(
    0xFF000000, 0xFFFFFFFF, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047,
    0xFF00ACC1, 0xFF1E88E5, 0xFF5E35B1, 0xFFD81B60, 0xFF6D4C41, 0xFF757575,
).map { it.toInt() }

/**
 * The Draw page (#1865 canvas-first): the canvas fills the page; a floating top toolbar (Menu, Undo, Redo, Tools, Brush library, Colour,
 * Layers, Fullscreen) and a vertical brush rail (size and opacity bars, then Pencil, Marker, Brush, Airbrush, Ink, Smudge, Eraser) float
 * over it. Every earlier tool and action is still here: the tools grid holds every [Tool] and the shape fill, the menu every action of the
 * old actions row (Open, Export PNG, Folder, Share, canvas size), the brush library the size and opacity sliders and the recent colours.
 * [panel] is the launch extra `panel` (layers opens the layers panel), handed down by the shell.
 */
@Composable
fun DrawScreen(ctl: DrawController, panel: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var showLayers by rememberSaveable { mutableStateOf(panel == "layers") }
    var showColor by remember { mutableStateOf(false) }
    var showSizes by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { Folders.remember(ctx, "draw", uri); ctl.exportTo(uri) }
    }
    val openImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) ctl.openImage(uri) }
    ctl.version // the toolbar reads the history state below
    val ink = colorResource(R.color.app_text)
    val chrome = colorResource(R.color.app_surface).copy(alpha = 0.92f)

    Box(modifier.fillMaxSize()) {
        // full bleed: the canvas under everything
        DrawCanvas(ctl)
        if (!fullscreen) {
            // the floating top toolbar
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 64.dp, end = 8.dp).clip(RoundedCornerShape(14.dp)).background(chrome)
                    .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp).testTag("draw_toolbar"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    ToolbarButton(Icons.Filled.Menu, "Menu", ink) { showMenu = true }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Open") }, onClick = { showMenu = false; openImage.launch("image/*") })
                        DropdownMenuItem(text = { Text("Export PNG") }, onClick = { showMenu = false; val t = Folders.saved(ctx, "draw"); if (t == null) pickFolder.launch(null) else ctl.exportTo(t) })
                        DropdownMenuItem(text = { Text("Folder") }, onClick = { showMenu = false; pickFolder.launch(null) })
                        DropdownMenuItem(text = { Text("Share") }, onClick = { showMenu = false; ctl.shareIntent()?.let { ctx.startActivity(it) } })
                        DropdownMenuItem(text = { Text("Canvas ${ctl.model.state.width}x${ctl.model.state.height}") }, onClick = { showMenu = false; showSizes = true })
                        DropdownMenuItem(text = { Text("Undo") }, enabled = ctl.model.canUndo, onClick = { showMenu = false; ctl.undo() })
                        DropdownMenuItem(text = { Text("Redo") }, enabled = ctl.model.canRedo, onClick = { showMenu = false; ctl.redo() })
                        DropdownMenuItem(text = { Text("Layers") }, onClick = { showMenu = false; showLayers = !showLayers })
                    }
                }
                ToolbarButton(Icons.AutoMirrored.Filled.Undo, "Undo", ink, enabled = ctl.model.canUndo) { ctl.undo() }
                ToolbarButton(Icons.AutoMirrored.Filled.Redo, "Redo", ink, enabled = ctl.model.canRedo) { ctl.redo() }
                ToolbarButton(Icons.Filled.Apps, "Tools", ink, selected = showTools) { showTools = !showTools; showLibrary = false }
                ToolbarButton(Icons.Filled.Brush, "Brush library", ink, selected = showLibrary) { showLibrary = !showLibrary; showTools = false }
                Box(Modifier.padding(6.dp).size(30.dp).clip(CircleShape)
                    .background(Brush.sweepGradient(WHEEL_COLORS)).padding(5.dp).clip(CircleShape).background(Color(ctl.color))
                    .clickable { showColor = true }.semantics { contentDescription = "Colour" })
                ToolbarButton(Icons.Filled.Layers, "Layers", ink, selected = showLayers) { showLayers = !showLayers }
                ToolbarButton(Icons.Filled.Fullscreen, "Fullscreen", ink) { fullscreen = true }
            }
            // the vertical brush rail
            BrushRail(ctl, chrome, ink, Modifier.align(Alignment.CenterStart).padding(start = 6.dp, top = 64.dp, bottom = 8.dp))
            if (showTools) ToolsGrid(ctl, chrome, ink, Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 64.dp, end = 8.dp)) { showTools = false }
            if (showLibrary) BrushLibrary(ctl, chrome, ink, Modifier.align(Alignment.TopCenter).padding(top = 64.dp, start = 64.dp, end = 8.dp)) { showLibrary = false }
            if (showLayers) LayersPanel(ctl, Modifier.align(Alignment.BottomEnd).widthIn(max = 420.dp), onClose = { showLayers = false })
        } else {
            ToolbarButton(Icons.Filled.FullscreenExit, "Exit fullscreen", ink, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clip(CircleShape).background(chrome)) { fullscreen = false }
        }
        ctl.message?.let { m ->
            Text(m, color = colorResource(R.color.app_on_accent), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).background(colorResource(R.color.app_accent), CircleShape).padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
    if (showColor) ColorDialog(ctl, onDone = { showColor = false })
    if (showSizes) AlertDialog(
        onDismissRequest = { showSizes = false },
        title = { Text("Canvas size") },
        text = {
            Column {
                CanvasPresets.all.forEach { p ->
                    TextButton(onClick = { ctl.newDocument(p.width, p.height); showSizes = false }) { Text(p.name) }
                }
                Text("A new size starts a new blank drawing.", fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = { showSizes = false }) { Text("Cancel") } },
    )
    if (ctl.pendingText != null) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { ctl.pendingText = null },
            title = { Text("Text") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { ctl.commitText(text) }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { ctl.pendingText = null }) { Text("Cancel") } },
        )
    }
}

/** The hue ring of the toolbar's colour button and of the colour wheel picker (red at 0, clockwise, as HSV hue runs). */
private val WHEEL_COLORS = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

@Composable
private fun ToolbarButton(icon: ImageVector, label: String, tint: Color, modifier: Modifier = Modifier, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(icon, contentDescription = label, tint = if (!enabled) tint.copy(alpha = 0.35f) else if (selected) colorResource(R.color.app_accent) else tint)
    }
}

/** The glyph of each rail brush. */
private fun railIcon(t: Tool): ImageVector = when (t) {
    Tool.Pencil -> Icons.Filled.Edit
    Tool.Marker -> Icons.Filled.BorderColor
    Tool.Airbrush -> Icons.Filled.BlurOn
    Tool.Ink -> Icons.Filled.Gesture
    Tool.Smudge -> Icons.Filled.Fingerprint
    Tool.Eraser -> Icons.Filled.AutoFixNormal
    else -> Icons.Filled.Brush
}

/** The vertical rail: the size bar and the opacity bar, then the freehand brushes ([Brushes.rail]). */
@Composable
private fun BrushRail(ctl: DrawController, chrome: Color, ink: Color, modifier: Modifier) {
    val accent = colorResource(R.color.app_accent)
    Column(
        modifier.width(52.dp).clip(RoundedCornerShape(14.dp)).background(chrome).verticalScroll(rememberScrollState()).padding(vertical = 6.dp).testTag("draw_rail"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VerticalBar("Size", ctl.size, 1f..120f, accent, ink) { ctl.size = it }
        Text("${ctl.size.toInt()}", color = ink, fontSize = 10.sp)
        VerticalBar("Opacity", ctl.opacity, 0.05f..1f, accent, ink) { ctl.opacity = it }
        Text("${(ctl.opacity * 100).toInt()}%", color = ink, fontSize = 10.sp)
        Brushes.rail.forEach { b ->
            val on = ctl.tool == b.tool
            Box(
                Modifier.padding(top = 4.dp).size(42.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (on) accent.copy(alpha = 0.25f) else Color.Transparent)
                    .border(if (on) 2.dp else 0.dp, if (on) accent else Color.Transparent, RoundedCornerShape(10.dp))
                    .clickable { ctl.tool = b.tool },
                contentAlignment = Alignment.Center,
            ) { Icon(railIcon(b.tool), contentDescription = b.label, tint = if (on) accent else ink) }
        }
    }
}

/** A vertical slider: drag or tap along the bar; the top is the maximum. */
@Composable
private fun VerticalBar(label: String, value: Float, range: ClosedFloatingPointRange<Float>, accent: Color, ink: Color, onChange: (Float) -> Unit) {
    val span = range.endInclusive - range.start
    fun valueAt(y: Float, h: Float): Float = range.start + span * (1f - (y / h).coerceIn(0f, 1f))
    Canvas(
        Modifier.padding(vertical = 4.dp).size(width = 28.dp, height = 96.dp).semantics { contentDescription = label }
            .pointerInput(range) { detectTapGestures { o -> onChange(valueAt(o.y, size.height.toFloat())) } }
            .pointerInput(range) { detectVerticalDragGestures { ch, _ -> onChange(valueAt(ch.position.y, size.height.toFloat())) } },
    ) {
        val x = size.width / 2f
        val frac = ((value - range.start) / span).coerceIn(0f, 1f)
        val y = size.height * (1f - frac)
        drawLine(ink.copy(alpha = 0.3f), Offset(x, 0f), Offset(x, size.height), 4.dp.toPx(), StrokeCap.Round)
        drawLine(accent, Offset(x, y), Offset(x, size.height), 4.dp.toPx(), StrokeCap.Round)
        drawCircle(accent, 7.dp.toPx(), Offset(x, y))
    }
}

/** The tools grid: every tool of the page (the rail's brushes, fill, the shapes, text, eyedropper) and the shape fill switch. */
@Composable
private fun ToolsGrid(ctl: DrawController, chrome: Color, ink: Color, modifier: Modifier, onClose: () -> Unit) {
    Card(modifier.widthIn(max = 420.dp).testTag("draw_tools"), colors = CardDefaults.cardColors(containerColor = chrome)) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tools", color = ink, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            Brushes.grid.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { t -> FilterChip(selected = ctl.tool == t, onClick = { ctl.tool = t }, label = { Text(t.label, fontSize = 12.sp) }) }
                }
            }
            if (ctl.tool.isShape && ctl.tool != Tool.Line) FilterChip(selected = ctl.filled, onClick = { ctl.filled = !ctl.filled }, label = { Text("Filled", fontSize = 12.sp) })
        }
    }
}

/** The brush library: every freehand brush with its glyph, the size and opacity sliders and the recent colours. */
@Composable
private fun BrushLibrary(ctl: DrawController, chrome: Color, ink: Color, modifier: Modifier, onClose: () -> Unit) {
    Card(modifier.widthIn(max = 420.dp).testTag("draw_library"), colors = CardDefaults.cardColors(containerColor = chrome)) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Brush library", color = ink, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Brushes.rail.forEach { b ->
                    FilterChip(selected = ctl.tool == b.tool, onClick = { ctl.tool = b.tool }, leadingIcon = { Icon(railIcon(b.tool), contentDescription = null) }, label = { Text(b.label, fontSize = 12.sp) })
                }
            }
            Text("Size ${ctl.size.toInt()}", color = ink, fontSize = 11.sp)
            Slider(value = ctl.size, onValueChange = { ctl.size = it }, valueRange = 1f..120f)
            Text("Opacity ${(ctl.opacity * 100).toInt()}%", color = ink, fontSize = 11.sp)
            Slider(value = ctl.opacity, onValueChange = { ctl.opacity = it }, valueRange = 0.05f..1f)
            if (ctl.recent.list.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ctl.recent.list.forEach { c ->
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Color(c)).border(1.dp, colorResource(R.color.app_outline), CircleShape).clickable { ctl.color = c })
                }
            }
        }
    }
}

/** The painting surface: the layer bitmaps under the pan/zoom transform, the stroke in progress on top, one finger paints, two pinch and pan. */
@Composable
private fun DrawCanvas(ctl: DrawController) {
    Canvas(
        Modifier.fillMaxSize().clipToBounds()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var multi = false
                    ctl.stylus = down.type == PointerType.Stylus
                    ctl.begin(ctl.toDoc(down.position, if (ctl.stylus) down.pressure else 1f))
                    down.consume()
                    do {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.count { it.pressed }
                        if (pressed >= 2) {
                            if (!multi) { multi = true; ctl.cancel() }
                            ctl.transform(ev.calculateCentroid(), ev.calculateZoom(), ev.calculatePan())
                            ev.changes.forEach { it.consume() }
                        } else if (!multi) {
                            val ch = ev.changes.firstOrNull()
                            if (ch != null && ch.pressed) {
                                ctl.move(ctl.toDoc(ch.position, if (ctl.stylus) ch.pressure else 1f))
                                ch.consume()
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (!multi) ctl.end()
                }
            },
    ) {
        ctl.fit(size.width.toInt(), size.height.toInt())
        ctl.version; ctl.liveTick
        val st = ctl.model.state
        withTransform({ translate(ctl.offX, ctl.offY); scale(ctl.scale, ctl.scale, Offset.Zero) }) {
            drawRect(Color(st.background), size = Size(st.width.toFloat(), st.height.toFloat()))
            for (l in st.layers) {
                if (!l.visible) continue
                ctl.renderer.bitmap(l.id)?.let { drawImage(it.asImageBitmap(), alpha = l.opacity) }
            }
            val ink = Color(ctl.color).copy(alpha = ctl.opacity)
            if (ctl.tool == Tool.Airbrush && ctl.live.isNotEmpty()) {
                // #1865 the airbrush previews the same deterministic spray it commits
                val spray = Color(ctl.color).copy(alpha = ctl.opacity * 0.35f)
                for (d in Brushes.airbrushDabs(ctl.live.toList(), ctl.size)) drawCircle(spray, d.r, Offset(d.x, d.y))
            } else if (ctl.tool.isFreehand && ctl.live.isNotEmpty()) {
                val pts = ctl.live.toList()
                val erase = ctl.tool == Tool.Eraser || ctl.tool == Tool.Smudge
                val c = if (erase) Color(0x88888888) else if (ctl.tool == Tool.Marker) ink.copy(alpha = ctl.opacity * 0.6f) else ink
                val w = DocRenderer.widthOf(ctl.tool, ctl.size)
                if (pts.size == 1) drawCircle(c, w / 2f, Offset(pts[0].x, pts[0].y))
                else if (pts.any { it.p != 1f }) for (i in 1 until pts.size) {
                    drawLine(c, Offset(pts[i - 1].x, pts[i - 1].y), Offset(pts[i].x, pts[i].y), maxOf(w * (pts[i - 1].p + pts[i].p) / 2f, 1f), StrokeCap.Round)
                } else drawPath(
                    DocRenderer.smoothPath(pts).asComposePath(), c,
                    style = Stroke(w, cap = if (ctl.tool == Tool.Marker) StrokeCap.Square else StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            val a = ctl.shapeA
            val b = ctl.shapeB
            if (ctl.tool.isShape && a != null && b != null) {
                val tl = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
                val sz = Size(Math.abs(a.x - b.x), Math.abs(a.y - b.y))
                val style = if (ctl.filled && ctl.tool != Tool.Line) androidx.compose.ui.graphics.drawscope.Fill else Stroke(ctl.size, cap = StrokeCap.Round)
                when (ctl.tool) {
                    Tool.Line -> drawLine(ink, Offset(a.x, a.y), Offset(b.x, b.y), ctl.size, StrokeCap.Round)
                    Tool.Ellipse -> drawOval(ink, tl, sz, style = style)
                    else -> drawRect(ink, tl, sz, style = style)
                }
            }
        }
    }
}

@Composable
private fun LayersPanel(ctl: DrawController, modifier: Modifier, onClose: () -> Unit) {
    ctl.version
    val st = ctl.model.state
    Card(modifier.fillMaxWidth().padding(8.dp).heightIn(max = 300.dp), colors = CardDefaults.cardColors(containerColor = colorResource(R.color.app_surface))) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Layers", color = colorResource(R.color.app_text), modifier = Modifier.weight(1f))
                TextButton(onClick = { ctl.addLayer() }) { Text("Add layer") }
                TextButton(onClick = onClose) { Text("Close") }
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // the top layer first, as in every paint app
                st.layers.reversed().forEach { l ->
                    val idx = st.layers.indexOfFirst { it.id == l.id }
                    Row(
                        Modifier.fillMaxWidth().background(if (l.id == st.activeId) colorResource(R.color.app_surface_raised) else Color.Transparent).clickable { ctl.select(l.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = l.visible, onCheckedChange = { ctl.setVisible(l.id, it) })
                        Column(Modifier.weight(1f)) {
                            Text(l.name, color = colorResource(R.color.app_text), fontSize = 13.sp)
                            Slider(value = l.opacity, onValueChange = { ctl.setLayerOpacity(l.id, it) }, valueRange = 0f..1f)
                        }
                        TextButton(onClick = { ctl.move(l.id, idx + 1) }, enabled = idx < st.layers.size - 1) { Text("Up") }
                        TextButton(onClick = { ctl.move(l.id, idx - 1) }, enabled = idx > 0) { Text("Down") }
                        TextButton(onClick = { ctl.removeLayer(l.id) }, enabled = st.layers.size > 1) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorDialog(ctl: DrawController, onDone: () -> Unit) {
    val hsv = remember { FloatArray(3).also { AColor.colorToHSV(ctl.color, it) } }
    var h by remember { mutableStateOf(hsv[0]) }
    var s by remember { mutableStateOf(hsv[1]) }
    var v by remember { mutableStateOf(hsv[2]) }
    val picked = AColor.HSVToColor(floatArrayOf(h, s, v))
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Colour") },
        text = {
            Column {
                // #1865 the colour wheel: hue around, saturation outwards (tap or drag), brightness below
                Canvas(
                    Modifier.fillMaxWidth().height(200.dp).testTag("draw_wheel")
                        .pointerInput(Unit) { detectTapGestures { o -> val r = minOf(size.width, size.height) / 2f; val (hh, ss) = ColorWheel.hueSat(o.x, o.y, size.width / 2f, size.height / 2f, r); h = hh; s = ss } }
                        .pointerInput(Unit) { detectDragGestures { ch, _ -> val r = minOf(size.width, size.height) / 2f; val (hh, ss) = ColorWheel.hueSat(ch.position.x, ch.position.y, size.width / 2f, size.height / 2f, r); h = hh; s = ss } },
                ) {
                    val r = minOf(size.width, size.height) / 2f
                    val c = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(Brush.sweepGradient(WHEEL_COLORS, c), r, c)
                    drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), c, r), r, c)
                    drawCircle(Color.Black.copy(alpha = 1f - v), r, c)
                    val (tx, ty) = ColorWheel.pointOf(h, s, c.x, c.y, r)
                    drawCircle(Color.White, 9.dp.toPx(), Offset(tx, ty), style = Stroke(3.dp.toPx()))
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 36.dp).background(Color(picked)).border(1.dp, Color.Gray))
                Text("Hue", fontSize = 11.sp); Slider(value = h, onValueChange = { h = it }, valueRange = 0f..360f)
                Text("Saturation", fontSize = 11.sp); Slider(value = s, onValueChange = { s = it }, valueRange = 0f..1f)
                Text("Brightness", fontSize = 11.sp); Slider(value = v, onValueChange = { v = it }, valueRange = 0f..1f)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PALETTE.forEach { c -> Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c)).border(1.dp, Color.Gray, CircleShape).clickable {
                        val f = FloatArray(3); AColor.colorToHSV(c, f); h = f[0]; s = f[1]; v = f[2]
                    }) }
                }
                if (ctl.recent.list.isNotEmpty()) {
                    Text("Recent", fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ctl.recent.list.forEach { c -> Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c)).border(1.dp, Color.Gray, CircleShape).clickable {
                            val f = FloatArray(3); AColor.colorToHSV(c, f); h = f[0]; s = f[1]; v = f[2]
                        }) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { ctl.useColor(picked); onDone() }) { Text("Use colour") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
