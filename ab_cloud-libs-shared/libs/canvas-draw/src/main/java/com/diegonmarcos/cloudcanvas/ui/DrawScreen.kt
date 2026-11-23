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

private val PALETTE = listOf(
    0xFF000000, 0xFFFFFFFF, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047,
    0xFF00ACC1, 0xFF1E88E5, 0xFF5E35B1, 0xFFD81B60, 0xFF6D4C41, 0xFF757575,
).map { it.toInt() }

/** The Draw page: a paint app on the Compose canvas (no WebView). Tool rows on top, the canvas, the actions row at the bottom. [panel] is the launch extra `panel` (layers opens the layers panel), handed down by the shell. */
@Composable
fun DrawScreen(ctl: DrawController, panel: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var showLayers by rememberSaveable { mutableStateOf(panel == "layers") }
    var showColor by remember { mutableStateOf(false) }
    var showSizes by remember { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { Folders.remember(ctx, "draw", uri); ctl.exportTo(uri) }
    }
    val openImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) ctl.openImage(uri) }
    ctl.version // the actions row reads the history state below
    val ink = colorResource(R.color.app_text)

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tool.entries.forEach { t -> FilterChip(selected = ctl.tool == t, onClick = { ctl.tool = t }, label = { Text(t.label, fontSize = 12.sp) }) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(Color(ctl.color)).border(2.dp, ink, CircleShape).clickable { showColor = true })
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ctl.recent.list.forEach { c ->
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Color(c)).border(1.dp, colorResource(R.color.app_outline), CircleShape).clickable { ctl.color = c })
                }
            }
            if (ctl.tool.isShape && ctl.tool != Tool.Line) FilterChip(selected = ctl.filled, onClick = { ctl.filled = !ctl.filled }, label = { Text("Filled", fontSize = 12.sp) })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Size ${ctl.size.toInt()}", color = ink, fontSize = 11.sp)
                Slider(value = ctl.size, onValueChange = { ctl.size = it }, valueRange = 1f..120f)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Opacity ${(ctl.opacity * 100).toInt()}%", color = ink, fontSize = 11.sp)
                Slider(value = ctl.opacity, onValueChange = { ctl.opacity = it }, valueRange = 0.05f..1f)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            DrawCanvas(ctl)
            if (showLayers) LayersPanel(ctl, Modifier.align(Alignment.BottomCenter), onClose = { showLayers = false })
            ctl.message?.let { m ->
                Text(m, color = colorResource(R.color.app_on_accent), fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(6.dp).background(colorResource(R.color.app_accent), CircleShape).padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { ctl.undo() }, enabled = ctl.model.canUndo, label = { Text("Undo") })
            AssistChip(onClick = { ctl.redo() }, enabled = ctl.model.canRedo, label = { Text("Redo") })
            AssistChip(onClick = { showLayers = !showLayers }, label = { Text("Layers") })
            AssistChip(onClick = { showSizes = true }, label = { Text("Canvas ${ctl.model.state.width}x${ctl.model.state.height}") })
            AssistChip(onClick = { openImage.launch("image/*") }, label = { Text("Open") })
            AssistChip(onClick = { val t = Folders.saved(ctx, "draw"); if (t == null) pickFolder.launch(null) else ctl.exportTo(t) }, label = { Text("Export PNG") })
            AssistChip(onClick = { pickFolder.launch(null) }, label = { Text("Folder") })
            AssistChip(onClick = { ctl.shareIntent()?.let { ctx.startActivity(it) } }, label = { Text("Share") })
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
            if (ctl.tool.isFreehand && ctl.live.isNotEmpty()) {
                val pts = ctl.live.toList()
                val erase = ctl.tool == Tool.Eraser
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
