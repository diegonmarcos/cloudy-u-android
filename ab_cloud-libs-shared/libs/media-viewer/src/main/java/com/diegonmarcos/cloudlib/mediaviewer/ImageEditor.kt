package com.diegonmarcos.cloudlib.mediaviewer

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.diegonmarcos.superapp.uikit.KitDensity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ImageEditTags {
    const val EDITOR = "image_editor"
    const val CANVAS = "image_editor_canvas"
    const val ROTATE_LEFT = "image_editor_rotate_left"
    const val ROTATE_RIGHT = "image_editor_rotate_right"
    const val CROP = "image_editor_crop"
    const val DRAW = "image_editor_draw"
    const val COLOR = "image_editor_color"
    const val UNDO = "image_editor_undo"
    const val SAVE = "image_editor_save"
    const val STATUS = "image_editor_status"
    const val INFO = "image_viewer_info"
    const val INFO_PANEL = "image_viewer_info_panel"
}

/**
 * #1537 the basic image editor (Edit mode of an image tab): rotate in quarter turns, crop by trimming each side, annotate with a
 * freehand pen, then Save (ImageEditMath.savePlan: jpg/png/webp in place, anything else as a PNG copy). The preview is down-sampled;
 * Save re-reads the file at full size and applies the same turns, strokes (normalised) and crop.
 */
@Composable
internal fun ImageEditor(path: String, modifier: Modifier, labels: MediaLabels) {
    var turns by remember(path) { mutableIntStateOf(0) }
    var version by remember(path) { mutableIntStateOf(0) }
    var preview by remember(path) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path, turns, version) {
        val b = withContext(Dispatchers.IO) { ImageRender.base(path, turns, MAX_SIDE) }
        preview = b
        failed = b == null
    }
    var crop by remember(path) { mutableStateOf(CropFractions()) }
    var cropping by remember(path) { mutableStateOf(false) }
    var drawing by remember(path) { mutableStateOf(false) }
    val strokes = remember(path) { mutableStateListOf<Stroke>() }
    var current by remember(path) { mutableStateOf<List<P>>(emptyList()) }
    var colorIndex by remember(path) { mutableIntStateOf(0) }
    var status by remember(path) { mutableStateOf<String?>(null) }
    var saving by remember(path) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val color = ImageEditMath.PALETTE[colorIndex % ImageEditMath.PALETTE.size]

    fun turn(by: Int) {
        val moved = strokes.map { s -> Stroke(s.color, s.points.map { ImageEditMath.rotatePoint(it, by) }) }
        strokes.clear(); strokes.addAll(moved)
        crop = CropFractions()
        turns = Math.floorMod(turns + by, 4)
    }

    Column(modifier.fillMaxSize().testTag(ImageEditTags.EDITOR)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KitDensity.rowPadH), verticalAlignment = Alignment.CenterVertically) {
            Tool(labels.rotateLeft, ImageEditTags.ROTATE_LEFT, false) { turn(-1) }
            Tool(labels.rotateRight, ImageEditTags.ROTATE_RIGHT, false) { turn(1) }
            Tool(labels.crop, ImageEditTags.CROP, cropping) { cropping = !cropping; if (cropping) drawing = false }
            Tool(labels.draw, ImageEditTags.DRAW, drawing) { drawing = !drawing; if (drawing) cropping = false }
            Tool(labels.color, ImageEditTags.COLOR, false, swatch = Color(color)) { colorIndex++ }
            Tool(labels.undo, ImageEditTags.UNDO, false) { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }
            Tool(labels.save, ImageEditTags.SAVE, false) {
                if (!saving) {
                    saving = true
                    status = labels.saving
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { ImageRender.save(path, turns, crop, strokes.toList()) }
                        saving = false
                        if (r.isSuccess) {
                            status = labels.saved + ": " + r.getOrNull()?.name
                            turns = 0; crop = CropFractions(); strokes.clear(); version++
                        } else status = labels.cannotSave
                    }
                }
            }
        }
        if (cropping) {
            Column(Modifier.fillMaxWidth().padding(horizontal = KitDensity.rowPadH)) {
                CropSlider(labels.cropLeft, crop.left) { crop = crop.copy(left = it) }
                CropSlider(labels.cropTop, crop.top) { crop = crop.copy(top = it) }
                CropSlider(labels.cropRight, crop.right) { crop = crop.copy(right = it) }
                CropSlider(labels.cropBottom, crop.bottom) { crop = crop.copy(bottom = it) }
            }
        }
        val bmp = preview
        if (bmp != null) {
            val image = remember(bmp) { bmp.asImageBitmap() }
            Canvas(
                Modifier.weight(1f).fillMaxWidth().testTag(ImageEditTags.CANVAS)
                    .pointerInput(drawing, bmp, color) {
                        if (drawing) detectDragGestures(
                            onDragStart = { o -> current = listOf(ImageEditMath.normalize(o.x, o.y, ImageEditMath.fit(bmp.width, bmp.height, size.width.toFloat(), size.height.toFloat()))) },
                            onDragEnd = { if (current.isNotEmpty()) strokes.add(Stroke(color, current)); current = emptyList() },
                            onDragCancel = { current = emptyList() },
                            onDrag = { change, _ ->
                                change.consume()
                                current = current + ImageEditMath.normalize(change.position.x, change.position.y, ImageEditMath.fit(bmp.width, bmp.height, size.width.toFloat(), size.height.toFloat()))
                            },
                        )
                    },
            ) {
                val f = ImageEditMath.fit(bmp.width, bmp.height, size.width, size.height)
                drawImage(image, dstOffset = IntOffset(f[0].toInt(), f[1].toInt()), dstSize = IntSize(f[2].toInt().coerceAtLeast(1), f[3].toInt().coerceAtLeast(1)))
                val width = (minOf(f[2], f[3]) * ImageEditMath.PEN_FRACTION).coerceAtLeast(2f)
                fun draw(c: Int, pts: List<P>) {
                    if (pts.isEmpty()) return
                    if (pts.size == 1) { drawCircle(Color(c), radius = width / 2f, center = Offset(f[0] + pts[0].x * f[2], f[1] + pts[0].y * f[3])); return }
                    val path = Path()
                    pts.forEachIndexed { i, p -> val x = f[0] + p.x * f[2]; val y = f[1] + p.y * f[3]; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                    drawPath(path, Color(c), style = DrawStroke(width = width, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
                strokes.forEach { draw(it.color, it.points) }
                draw(color, current)
                if (cropping) {
                    val r = ImageEditMath.cropRect(bmp.width, bmp.height, crop)
                    val sx = f[2] / bmp.width; val sy = f[3] / bmp.height
                    val x0 = f[0] + r[0] * sx; val y0 = f[1] + r[1] * sy; val x1 = x0 + r[2] * sx; val y1 = y0 + r[3] * sy
                    val dim = Color.Black.copy(alpha = 0.55f)
                    drawRect(dim, Offset(f[0], f[1]), Size(f[2], y0 - f[1]))
                    drawRect(dim, Offset(f[0], y1), Size(f[2], f[1] + f[3] - y1))
                    drawRect(dim, Offset(f[0], y0), Size(x0 - f[0], y1 - y0))
                    drawRect(dim, Offset(x1, y0), Size(f[0] + f[2] - x1, y1 - y0))
                    drawRect(Color.White, Offset(x0, y0), Size(x1 - x0, y1 - y0), style = DrawStroke(width = 2f))
                }
            }
        } else if (failed) {
            Text(labels.cannotOpen, Modifier.padding(KitDensity.large).testTag(MediaViewerTags.ERROR), fontSize = KitDensity.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        status?.let { Text(it, Modifier.padding(horizontal = KitDensity.rowPadH, vertical = KitDensity.rowPadV / 2).testTag(ImageEditTags.STATUS), fontSize = KitDensity.caption, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Tool(label: String, tag: String, on: Boolean, swatch: Color? = null, onClick: () -> Unit) {
    Row(
        Modifier.testTag(tag).padding(end = KitDensity.rowPadH / 2, top = KitDensity.rowPadV / 2, bottom = KitDensity.rowPadV / 2)
            .clip(RoundedCornerShape(KitDensity.small))
            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = KitDensity.medium, vertical = KitDensity.rowPadV / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (swatch != null) Row(Modifier.padding(end = KitDensity.rowPadV / 2).clip(RoundedCornerShape(KitDensity.small)).background(swatch).padding(KitDensity.small / 2)) {}
        Text(label, fontSize = KitDensity.body, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CropSlider(label: String, value: Float, onValue: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.padding(end = KitDensity.small), fontSize = KitDensity.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = value, onValueChange = onValue, valueRange = 0f..ImageEditMath.MAX_CROP, modifier = Modifier.weight(1f))
    }
}

private const val MAX_SIDE = 2048
