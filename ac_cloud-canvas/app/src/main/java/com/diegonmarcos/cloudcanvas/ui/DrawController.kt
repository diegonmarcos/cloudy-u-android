package com.diegonmarcos.cloudcanvas.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.diegonmarcos.cloudcanvas.draw.DocRenderer
import com.diegonmarcos.cloudcanvas.draw.DrawModel
import com.diegonmarcos.cloudcanvas.draw.ExportNames
import com.diegonmarcos.cloudcanvas.draw.Op
import com.diegonmarcos.cloudcanvas.draw.Pixels
import com.diegonmarcos.cloudcanvas.draw.PngEncoder
import com.diegonmarcos.cloudcanvas.draw.Pt
import com.diegonmarcos.cloudcanvas.draw.RecentColors
import com.diegonmarcos.cloudcanvas.draw.Tool

/** The state of the Draw page: the document, its bitmaps, the tool settings and the view transform. Held by the shell so a drawing survives a page switch. */
class DrawController(private val ctx: Context) {
    val model = DrawModel()
    val renderer = DocRenderer()

    /** Bumped after every change of the document: the canvas reads it to redraw. */
    var version by mutableIntStateOf(0)
    var liveTick by mutableIntStateOf(0)

    var tool by mutableStateOf(Tool.Brush)
    var color by mutableIntStateOf(0xFF1E88E5.toInt())
    var size by mutableFloatStateOf(14f)
    var opacity by mutableFloatStateOf(1f)
    var filled by mutableStateOf(false)
    var recent by mutableStateOf(RecentColors.decode(prefs().getString("recent", null)))
    var message by mutableStateOf<String?>(null)
    var pendingText by mutableStateOf<Pt?>(null)

    /** The stroke being drawn (freehand points) or the shape being dragged. */
    val live = ArrayList<Pt>()
    var shapeA by mutableStateOf<Pt?>(null)
    var shapeB by mutableStateOf<Pt?>(null)
    var stylus = false

    var scale by mutableFloatStateOf(1f)
    var offX by mutableFloatStateOf(0f)
    var offY by mutableFloatStateOf(0f)
    private var viewW = 0
    private var viewH = 0
    private var fitted = ""

    init { sync() }

    private fun prefs() = ctx.getSharedPreferences("canvas_draw", Context.MODE_PRIVATE)

    fun sync() {
        renderer.sync(model.state)
        version++
    }

    /** Fits the document into the view the first time and whenever the document size or the view size changes. */
    fun fit(w: Int, h: Int) {
        viewW = w; viewH = h
        val st = model.state
        val key = "${st.width}x${st.height}@${w}x$h"
        if (key == fitted || w <= 0 || h <= 0) return
        fitted = key
        scale = minOf(w.toFloat() / st.width, h.toFloat() / st.height) * 0.94f
        offX = (w - st.width * scale) / 2f
        offY = (h - st.height * scale) / 2f
    }

    fun toDoc(screen: Offset, pressure: Float = 1f) = Pt((screen.x - offX) / scale, (screen.y - offY) / scale, pressure)

    /** Pinch zoom about [centroid] and pan, in screen pixels. */
    fun transform(centroid: Offset, zoom: Float, pan: Offset) {
        val s = (scale * zoom).coerceIn(0.1f, 16f)
        val k = s / scale
        offX = centroid.x - (centroid.x - offX) * k + pan.x
        offY = centroid.y - (centroid.y - offY) * k + pan.y
        scale = s
    }

    private fun inside(p: Pt): Boolean = p.x >= 0 && p.y >= 0 && p.x < model.state.width && p.y < model.state.height

    fun begin(p: Pt) {
        message = null
        when {
            tool.isFreehand -> { live.clear(); live.add(p); liveTick++ }
            tool.isShape -> { shapeA = p; shapeB = p }
            tool == Tool.Eyedropper -> sample(p)
            else -> live.clear()
        }
        if (tool == Tool.Fill || tool == Tool.Text) { live.clear(); live.add(p) }
    }

    fun move(p: Pt) {
        when {
            tool.isFreehand -> { live.add(p); liveTick++ }
            tool.isShape -> shapeB = p
            tool == Tool.Eyedropper -> sample(p)
            else -> { live.clear(); live.add(p) }
        }
    }

    fun end() {
        when {
            tool.isFreehand && live.isNotEmpty() -> {
                model.addOp(Op.Stroke(tool, color, size, opacity, live.toList()))
                recent.use(color); persistRecent()
            }
            tool.isShape && shapeA != null && shapeB != null -> {
                model.addOp(Op.Shape(tool, color, size, opacity, shapeA!!, shapeB!!, filled))
                recent.use(color); persistRecent()
            }
            tool == Tool.Fill && live.isNotEmpty() && inside(live[0]) -> {
                model.addOp(Op.Fill(live[0], color, opacity)); recent.use(color); persistRecent()
            }
            tool == Tool.Text && live.isNotEmpty() && inside(live[0]) -> pendingText = live[0]
        }
        cancel()
        sync()
    }

    /** Drops the stroke or shape in progress (a second finger arrived, or the gesture ended). */
    fun cancel() {
        live.clear(); shapeA = null; shapeB = null; liveTick++
    }

    private fun persistRecent() {
        recent = RecentColors(12, recent.list)
        prefs().edit().putString("recent", recent.encode()).apply()
    }

    fun commitText(text: String) {
        val at = pendingText
        pendingText = null
        if (at != null && text.isNotBlank()) {
            model.addOp(Op.Text(text, color, size, opacity, at)); recent.use(color); persistRecent(); sync()
        }
    }

    fun useColor(c: Int) { color = c; recent.use(c); persistRecent() }

    /** The eyedropper: the colour of the flattened picture under [p]. */
    private fun sample(p: Pt) {
        if (!inside(p)) return
        val st = model.state
        var c = st.background
        for (l in st.layers) {
            if (!l.visible) continue
            val px = renderer.bitmap(l.id)?.getPixel(p.x.toInt(), p.y.toInt()) ?: continue
            c = Pixels.over(c, px, l.opacity)
        }
        color = c or (0xFF shl 24)
    }

    fun undo() { if (model.undo()) sync() }
    fun redo() { if (model.redo()) sync() }
    fun newDocument(w: Int, h: Int) { model.newDocument(w, h); renderer.release(); fitted = ""; fit(viewW, viewH); sync() }

    fun addLayer() { model.addLayer(); sync() }
    fun removeLayer(id: Int) { if (model.removeLayer(id)) sync() }
    fun select(id: Int) { model.select(id); version++ }
    fun setVisible(id: Int, v: Boolean) { model.setVisible(id, v); sync() }
    fun setLayerOpacity(id: Int, o: Float) { model.setOpacity(id, o); sync() }
    fun move(id: Int, to: Int) { model.move(id, to); sync() }

    /** Opens a picture as a new layer to paint over, scaled down when it is large. */
    fun openImage(uri: Uri) {
        val bmp = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }.getOrNull()
        if (bmp == null) { message = "That file could not be opened as a picture."; return }
        model.addLayer("Picture", listOf(Op.Image(bmp, bmp.width, bmp.height)))
        sync()
        message = "Picture added as a layer."
    }

    /** The flattened picture as PNG bytes: the visible layers over the background. */
    fun png(): ByteArray {
        val st = model.state
        return PngEncoder.encode(st.width, st.height, renderer.flatten(st))
    }

    /** The flattened picture as a bitmap (the Draw Enhancer's input). */
    fun flattenBitmap(): android.graphics.Bitmap {
        val st = model.state
        return android.graphics.Bitmap.createBitmap(renderer.flatten(st), st.width, st.height, android.graphics.Bitmap.Config.ARGB_8888)
    }

    /** Adds a picture (the Enhancer's result) as a new layer on top, in one undoable step. */
    fun addImageLayer(name: String, bmp: android.graphics.Bitmap) {
        model.addLayer(name, listOf(Op.Image(bmp, bmp.width, bmp.height)))
        sync()
        message = "Added as layer $name."
    }

    fun exportTo(tree: Uri) {
        val name = ExportNames.png(System.currentTimeMillis() / 1000)
        val out = Folders.write(ctx, tree, name, "image/png", png())
        message = if (out != null) "Saved $name" else "The folder could not be written. Pick it again."
    }

    fun shareIntent(): Intent? = Folders.share(ctx, png(), "cloud-canvas.png", "image/png")
}
