package com.diegonmarcos.cloudme

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1937/#1938 what the emulator render proof (test/emulator-me-render-1937.sh) found on real pixels:
 *  - a Home banner (CENTER_CROP covers its box and so overflows it) must not paint outside its own bounds: it painted over the headers and tiles of the page;
 *  - the embedded pages are readable in the dark theme (a dark scheme is chosen) and the hidden-until-95%-visible sections of a page are revealed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderBounds1937Test {
    private fun draw(v: ImageView, w: Int, h: Int): Bitmap {
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, 300, Bitmap.Config.ARGB_8888)   // a canvas much taller than the view, like the page the banner sits in
        v.draw(Canvas(bmp))
        return bmp
    }

    @Test fun `a banner paints inside its bounds only`() {
        val ctx = RuntimeEnvironment.getApplication()
        val big = object : ColorDrawable(Color.RED) { override fun getIntrinsicWidth() = 200; override fun getIntrinsicHeight() = 200 }
        val banner = BannerImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageDrawable(big) }
        val b = draw(banner, 200, 60)
        assertEquals("inside the bounds the banner is drawn", Color.RED, b.getPixel(100, 30))
        assertEquals("below the bounds nothing is drawn (it covered the tiles)", Color.TRANSPARENT, b.getPixel(100, 61 + 20))
        assertEquals(Color.TRANSPARENT, b.getPixel(100, 250))
        assertTrue("sanity: the banner height is declared and bounded", ProjBanners.HEIGHT_DP in 60..260)
    }

    @Test fun `embedded pages choose a dark scheme and reveal the sections a page keeps hidden`() {
        assertEquals(WebDarkMode.Plan.ALGORITHMIC_PREFER_WEB, WebDarkMode.plan(true, true))
        assertEquals(WebDarkMode.Plan.FORCE_DARK_PREFER_WEB, WebDarkMode.plan(false, true))
        assertEquals(WebDarkMode.Plan.NONE, WebDarkMode.plan(false, false))
        assertTrue(WebReveal.JS.contains(".animated-section") && WebReveal.JS.contains("visibility='visible'") && WebReveal.JS.contains("opacity='1'"))
        assertTrue("the reveal repeats and follows scrolling", WebReveal.JS.contains("setInterval") && WebReveal.JS.contains("'scroll'"))
        assertTrue(WebReveal.JS.contains("window.__cmReveal"))
    }
}
