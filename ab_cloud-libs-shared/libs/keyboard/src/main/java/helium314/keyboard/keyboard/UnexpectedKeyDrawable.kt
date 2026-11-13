// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The Unexpected theme's key background: a flat rounded rectangle (corner radius [radiusPx]) filled with the theme colour for the key's
 * state, drawn [expandPx] beyond the key's own bounds on each side so the gap between two keys is the design's thin one, not the
 * stock style's. The colour comes through the tint list the theme colours apply (pressed keys are lighter), so it behaves like the
 * stock drawables everywhere else. Original code.
 */
class UnexpectedKeyDrawable(private val radiusPx: Float, private val expandPx: Rect) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private var tint: ColorStateList? = null
    private val rect = RectF()

    private fun resolveColor() {
        val t = tint ?: return
        paint.color = t.getColorForState(state, t.defaultColor)
    }

    override fun draw(canvas: Canvas) {
        resolveColor()
        rect.set(bounds)
        canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
    }

    /** The key view draws the background this much larger than the key on each side (see KeyboardView.onDrawKeyBackground). */
    override fun getPadding(padding: Rect): Boolean { padding.set(expandPx); return true }

    override fun isStateful(): Boolean = tint?.isStateful == true
    override fun onStateChange(stateSet: IntArray): Boolean {
        val before = paint.color
        resolveColor()
        return before != paint.color
    }

    override fun setTintList(tint: ColorStateList?) { this.tint = tint; resolveColor(); invalidateSelf() }
    override fun setTintMode(tintMode: PorterDuff.Mode?) { /* the fill is the tint colour itself */ }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
