package com.diegonmarcos.superapp.vision

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import androidx.core.graphics.PathParser

/** What one frame is composited from: the faces and objects found, the person mask, and the filters switched on. */
class VrScene(val faces: List<FaceInfo>, val objects: List<Detection>, val mask: PersonMask?, val filters: List<FilterDecl>) {
    val needsFaces: Boolean get() = filters.any { it.kind == FilterKind.HAT || it.kind == FilterKind.GLASSES || it.kind == FilterKind.MASK }
}

/**
 * Draws the declared filters onto a camera frame: the background (blur / colour / gradient behind the segmented person),
 * the colour look (a real LUT), then the overlays (hats, glasses, masks on the faces; stickers on the detected objects), each
 * placed by [FilterAnchors]. It works in the frame's own pixels, so the same call composites a 640 px live frame and a full
 * resolution photo. Not thread-safe: one compositor per thread.
 */
class VrCompositor(private val assets: AssetManager? = null) {
    private val paths = HashMap<String, Path>()
    private val pngs = HashMap<String, Bitmap?>()
    private val luts = HashMap<String, ColorLut>()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun composite(frame: Bitmap, scene: VrScene): Bitmap {
        val w = frame.width
        val h = frame.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val bg = scene.filters.firstOrNull { it.kind == FilterKind.BACKGROUND }?.background
        val mask = scene.mask
        if (bg != null && mask != null) {
            drawBackground(c, frame, bg, w, h)
            drawPerson(c, frame, mask, w, h)
        } else {
            c.drawBitmap(frame, 0f, 0f, null)
        }
        scene.filters.firstOrNull { it.kind == FilterKind.LUT }?.let { f -> applyLut(out, f) }
        for (f in scene.filters) {
            val anchor = f.anchor ?: continue
            val art = f.art ?: continue
            when (f.kind) {
                FilterKind.HAT, FilterKind.GLASSES, FilterKind.MASK ->
                    scene.faces.forEach { face -> drawArt(c, art, FilterAnchors.place(anchor, art.width, art.height, face)) }
                FilterKind.STICKER ->
                    scene.objects.filter { d -> f.labels.isEmpty() || f.labels.any { l -> d.label.contains(l, ignoreCase = true) } }
                        .forEach { d -> drawArt(c, art, FilterAnchors.placeOnBox(anchor, art.width, art.height, d.box)) }
                else -> {}
            }
        }
        return out
    }

    private fun drawBackground(c: Canvas, frame: Bitmap, bg: BackgroundDecl, w: Int, h: Int) {
        when (bg.mode) {
            "blur" -> {
                val small = Bitmap.createScaledBitmap(frame, maxOf(1, w / bg.blurDownscale), maxOf(1, h / bg.blurDownscale), true)
                c.drawBitmap(small, null, Rect(0, 0, w, h), bitmapPaint)
                small.recycle()
            }
            "gradient" -> c.drawRect(0f, 0f, w.toFloat(), h.toFloat(),
                Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), bg.color, bg.color2, Shader.TileMode.CLAMP) })
            else -> c.drawColor(bg.color)
        }
    }

    /** The person pixels of [frame], through the mask, over what is already drawn. */
    private fun drawPerson(c: Canvas, frame: Bitmap, mask: PersonMask, w: Int, h: Int) {
        val alpha = IntArray(mask.width * mask.height) { i -> (mask.confidence[i].coerceIn(0f, 1f) * 255f).toInt() shl 24 }
        val maskBitmap = Bitmap.createBitmap(alpha, mask.width, mask.height, Bitmap.Config.ARGB_8888)
        val layer = c.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
        c.drawBitmap(frame, 0f, 0f, null)
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        c.drawBitmap(maskBitmap, null, Rect(0, 0, w, h), p)
        c.restoreToCount(layer)
        maskBitmap.recycle()
    }

    private fun applyLut(bitmap: Bitmap, f: FilterDecl) {
        val decl = f.lut ?: return
        val lut = luts.getOrPut(f.id) { ColorLut.from(decl) }
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        lut.apply(px)
        bitmap.setPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun drawArt(c: Canvas, art: Art, p: Placement) {
        c.save()
        c.translate(p.anchorX, p.anchorY)
        c.rotate(p.rotationDegrees)
        c.translate(-p.pivotX * p.width, -p.pivotY * p.height)
        c.scale(p.width / art.width, p.height / art.height)
        art.png?.let { name ->
            pngs.getOrPut(name) { assets?.open(name)?.use { BitmapFactory.decodeStream(it) } }?.let { bmp ->
                c.drawBitmap(bmp, null, Rect(0, 0, art.width.toInt(), art.height.toInt()), bitmapPaint)
            }
        }
        for (ap in art.paths) {
            val path = paths.getOrPut(ap.d) { PathParser.createPathFromPathData(ap.d) }
            path.fillType = if (ap.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            ap.fill?.let { c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = it }) }
            ap.stroke?.let {
                c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; color = it; strokeWidth = ap.strokeWidth; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                })
            }
        }
        c.restore()
    }
}
