package com.diegonmarcos.superapp.vision

import android.content.Context
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.AbstractComposeView

/**
 * #1805 Cloud Camera > Objects' overlay, in Compose: a frame and a caption (label, confidence, tracking id) per detected
 * object, a frame labelled "Text" per text region, and — once the frame's text is read — every word's box, the
 * selection over them with its two handles. Every position goes through [FrameMapping] (rotation, the front camera's
 * mirror, the preview's FILL_CENTER crop, or FIT_CENTER for an opened image), both ways: boxes to the view, and taps
 * back into the frame for hit testing ([TextLayout.hit], [TextSelection]).
 *
 * Touch: a tap on a word selects it (again: its line, then its block); dragging a handle extends the selection; a tap
 * off the text clears it. [listener] hears each settled selection (the controller copies it). With no text read, the
 * overlay passes every touch through, so the preview underneath keeps its tap-to-focus.
 */
class SceneOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AbstractComposeView(context, attrs) {
    interface Listener {
        /** A selection settled (a tap, or a handle let go): its text. */
        fun onSelected(text: String, selection: TextSelection)
        fun onCleared()
    }

    /** One frame's geometry: the frame size, its rotation to upright, mirror and how the view scales it. */
    data class Geometry(val w: Int, val h: Int, val rotation: Int = 0, val mirrored: Boolean = false, val cover: Boolean = true)

    private class State(
        val objects: List<Detection>, val text: List<Detection>, val geo: Geometry,
        val layout: TextLayout?, val layoutGeo: Geometry?, val selection: TextSelection?, val showWords: Boolean,
    )

    private var state by mutableStateOf(State(emptyList(), emptyList(), Geometry(0, 0), null, null, null, true))

    var listener: Listener? = null

    /** The handle a drag holds, for the gesture's length. */
    private var dragging: TextSelection.Handle? = null

    val layout: TextLayout? get() = state.layout
    val selection: TextSelection? get() = state.selection
    val selectedText: String get() = state.selection?.let { s -> state.layout?.let { s.text(it) } }.orEmpty()

    /** The objects and text regions of the latest frame, boxes in [geo]'s frame. */
    fun show(objects: List<Detection>, textRegions: List<Detection>, geo: Geometry) {
        val s = state
        state = State(objects, textRegions, geo, s.layout, s.layoutGeo, s.selection, s.showWords)
    }

    /** The text read on a frame ([layout] in [geo]'s frame), or none; a new layout drops the old selection. */
    fun showText(layout: TextLayout?, geo: Geometry?) {
        val s = state
        val keep = s.selection.takeIf { layout != null && layout === s.layout }
        state = State(s.objects, s.text, s.geo, layout, geo, keep, s.showWords)
    }

    fun showWords(on: Boolean) {
        val s = state
        state = State(s.objects, s.text, s.geo, s.layout, s.layoutGeo, s.selection, on)
    }

    fun select(sel: TextSelection?) {
        val s = state
        state = State(s.objects, s.text, s.geo, s.layout, s.layoutGeo, sel, s.showWords)
    }

    /** Every word selected; the selection, or null when nothing is read. */
    fun selectAll(): TextSelection? = state.layout?.let { TextSelection.all(it) }.also { select(it) }

    fun clear() {
        state = State(emptyList(), emptyList(), Geometry(0, 0), null, null, null, state.showWords)
    }

    /** Each word's box in view pixels with its text: what an on-device or emulator check taps and drags. */
    fun wordBoxes(): List<Pair<String, Box>> {
        val s = state
        val l = s.layout ?: return emptyList()
        val g = s.layoutGeo ?: return emptyList()
        return l.words.map { w -> w.text to FrameMapping.boxToView(w.box, g.w, g.h, g.rotation, g.mirrored, width, height, g.cover) }
    }

    /** No text read: every touch goes to the views underneath (the preview's tap-to-focus). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = if (state.layout == null) false else super.dispatchTouchEvent(ev)

    private fun toFrame(o: Offset, g: Geometry): Pair<Float, Float> = FrameMapping.viewToImage(o.x, o.y, g.w, g.h, g.rotation, g.mirrored, width, height, g.cover)

    private fun handlesInView(s: State): Pair<Offset, Offset>? {
        val l = s.layout ?: return null
        val g = s.layoutGeo ?: return null
        val sel = s.selection ?: return null
        val a = FrameMapping.boxToView(l.words[sel.start].box, g.w, g.h, g.rotation, g.mirrored, width, height, g.cover)
        val b = FrameMapping.boxToView(l.words[sel.end].box, g.w, g.h, g.rotation, g.mirrored, width, height, g.cover)
        return Offset(a.left, a.bottom) to Offset(b.right, b.bottom)
    }

    @Composable
    override fun Content() {
        val s = state
        val density = resources.displayMetrics.density
        val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 13 * density }
        val handleRadius = 9 * density
        val slopView = 12 * density
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(s.layout, s.layoutGeo) {
                    detectTapGestures(onTap = { o ->
                        val cur = state
                        val l = cur.layout ?: return@detectTapGestures
                        val g = cur.layoutGeo ?: return@detectTapGestures
                        val (x, y) = toFrame(o, g)
                        val slop = slopView / FrameMapping.viewScale(g.w, g.h, g.rotation, width, height, g.cover)
                        val sel = TextSelection.tap(l, cur.selection, x, y, slop)
                        select(sel)
                        if (sel == null) listener?.onCleared() else listener?.onSelected(sel.text(l), sel)
                    })
                }
                .pointerInput(s.layout, s.layoutGeo, "handles") {
                    detectDragGestures(
                        onDragStart = { o ->
                            val h = handlesInView(state)
                            dragging = when {
                                h == null -> null
                                (h.first - o).getDistance() < handleRadius * 3 -> TextSelection.Handle.START
                                (h.second - o).getDistance() < handleRadius * 3 -> TextSelection.Handle.END
                                else -> null
                            }
                        },
                        onDrag = { change, _ ->
                            val hd = dragging ?: return@detectDragGestures
                            val cur = state
                            val l = cur.layout ?: return@detectDragGestures
                            val g = cur.layoutGeo ?: return@detectDragGestures
                            val sel = cur.selection ?: return@detectDragGestures
                            change.consume()
                            val (x, y) = toFrame(change.position, g)
                            select(TextSelection.drag(l, sel, hd, x, y))
                        },
                        onDragEnd = {
                            if (dragging != null) {
                                val cur = state
                                val l = cur.layout
                                val sel = cur.selection
                                if (l != null && sel != null) listener?.onSelected(sel.text(l), sel)
                            }
                            dragging = null
                        },
                        onDragCancel = { dragging = null },
                    )
                },
        ) {
            val vw = size.width.toInt()
            val vh = size.height.toInt()
            val g = s.geo
            fun frame(d: Detection, colour: Color, label: String) {
                val r = FrameMapping.boxToView(d.box, g.w, g.h, g.rotation, g.mirrored, vw, vh, g.cover)
                drawRect(colour, Offset(r.left, r.top), Size(r.width, r.height), style = Stroke(3 * density))
                val tw = caption.measureText(label)
                val top = maxOf(0f, r.top - caption.textSize - 6 * density)
                drawRect(Color(0xAA000000), Offset(r.left, top), Size(tw + 8 * density, caption.textSize + 6 * density))
                drawContext.canvas.nativeCanvas.drawText(label, r.left + 4 * density, top + caption.textSize, caption)
            }
            s.objects.forEach { frame(it, OBJECT, it.caption()) }
            s.text.forEach { frame(it, TEXT, it.caption()) }
            val l = s.layout
            val lg = s.layoutGeo
            if (l != null && lg != null) {
                val sel = s.selection
                l.words.forEach { w ->
                    val r = FrameMapping.boxToView(w.box, lg.w, lg.h, lg.rotation, lg.mirrored, vw, vh, lg.cover)
                    if (sel != null && w.index in sel.range) drawRect(SELECTED, Offset(r.left, r.top), Size(r.width, r.height))
                    else if (s.showWords) drawRect(WORD, Offset(r.left, r.top), Size(r.width, r.height), style = Stroke(1.2f * density))
                }
                handlesInView(s)?.let { (a, b) ->
                    drawCircle(HANDLE, handleRadius, Offset(a.x, a.y + handleRadius))
                    drawCircle(HANDLE, handleRadius, Offset(b.x, b.y + handleRadius))
                }
            }
        }
    }

    private companion object {
        val OBJECT = Color.Yellow
        val TEXT = Color(0xFF00E5FF)
        val WORD = Color(0x99FFFFFF)
        val SELECTED = Color(0x664FC3F7)
        val HANDLE = Color(0xFF29B6F6)
    }
}
