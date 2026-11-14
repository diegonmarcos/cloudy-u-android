package com.diegonmarcos.clouddrive.files.convert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import com.diegonmarcos.clouddrive.files.FileOps
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HTML → PDF: libs:web-core's one web view (UNTRUSTED_HTML: no script, no storage, no file access, no
 * remote images) lays the page out at A4 width, then each page-high slice is drawn to a bitmap and
 * written by [PdfWriter.writeImages] as a raster page. The platform's vector print adapter is NOT used:
 * its callbacks cannot be subclassed outside android.print, and the platform PDF classes are what the
 * one-pdf-engine guard forbids. Known limit: pages are images (text is not selectable); local relative
 * images/styles do not load (file access off by profile). Blocking, off the main thread.
 */
object HtmlPdf {

    private const val CSS_WIDTH = 794          // A4 at 96 dpi
    private const val MAX_PAGES = 60
    private const val LOAD_TIMEOUT_S = 25L
    private const val SCALE = 1.5f             // raster pixels per CSS pixel

    fun render(ctx: Context, html: File, out: File, progress: FileOps.Progress, cancelled: () -> Boolean) {
        if (html.length() > 8L * 1024 * 1024) throw IOException("${html.name} is larger than 8 MB; it cannot be converted on the phone")
        val text = html.readText(Charsets.UTF_8)
        val main = Handler(Looper.getMainLooper())
        if (Looper.myLooper() == Looper.getMainLooper()) throw IOException("HTML conversion must run off the main thread")
        val loaded = CountDownLatch(1)
        var view: WebView? = null
        var failure: String? = null
        main.post {
            try {
                try { WebView.enableSlowWholeDocumentDraw() } catch (_: Throwable) {}
                val v = CloudWeb.create(ctx.applicationContext, WebProfile.UNTRUSTED_HTML)
                v.setBackgroundColor(Color.WHITE)
                v.webViewClient = object : CloudWebClient(WebProfile.UNTRUSTED_HTML) {
                    override fun onPageFinished(w: WebView?, u: String?) { super.onPageFinished(w, u); loaded.countDown() }
                }
                view = v
                val density = ctx.resources.displayMetrics.density
                val wPx = (CSS_WIDTH * density).toInt()
                v.measure(View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1, View.MeasureSpec.EXACTLY))
                v.layout(0, 0, wPx, 1)
                v.loadDataWithBaseURL(null, text, "text/html", "utf-8", null)
            } catch (t: Throwable) { failure = t.message ?: t.javaClass.simpleName; loaded.countDown() }
        }
        try {
            if (!loaded.await(LOAD_TIMEOUT_S, TimeUnit.SECONDS)) throw IOException("${html.name} did not finish loading in $LOAD_TIMEOUT_S s")
            failure?.let { throw IOException("the web view could not start: $it") }
            if (cancelled()) throw IOException("cancelled")
            Thread.sleep(400) // one more layout pass after onPageFinished
            val density = ctx.resources.displayMetrics.density
            val wPx = (CSS_WIDTH * density).toInt()
            val pageHPx = (wPx * PdfWriter.PAGE_H / PdfWriter.PAGE_W.toFloat()).toInt()
            val heightCss = onMain(main) { view!!.contentHeight }
            if (heightCss <= 0) throw IOException("${html.name} has no visible content")
            val totalPx = (heightCss * density).toInt()
            val pages = ((totalPx + pageHPx - 1) / pageHPx).coerceAtLeast(1)
            if (pages > MAX_PAGES) throw IOException("${html.name} would be $pages pages; the phone converts up to $MAX_PAGES")
            onMain(main) { view!!.layout(0, 0, wPx, pages * pageHPx) }
            Thread.sleep(150)
            val rasters = ArrayList<PdfWriter.ImagePage>(pages)
            for (i in 0 until pages) {
                if (cancelled()) throw IOException("cancelled")
                progress.report(i.toLong(), pages.toLong(), html.name)
                val outW = (CSS_WIDTH * SCALE).toInt(); val outH = (outW * PdfWriter.PAGE_H / PdfWriter.PAGE_W.toFloat()).toInt()
                val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                try {
                    onMain(main) {
                        val c = Canvas(bmp); c.drawColor(Color.WHITE)
                        val s = outW / wPx.toFloat()
                        c.scale(s, s); c.translate(0f, -(i * pageHPx).toFloat())
                        view!!.draw(c)
                    }
                    rasters += PdfWriter.ImagePage(ToPdf.jpeg(bmp), outW, outH)
                } finally { bmp.recycle() }
            }
            BufferedOutputStream(FileOutputStream(out)).use { PdfWriter.writeImages(rasters, it, cancelled) }
        } finally {
            main.post { runCatching { view?.destroy() } }
        }
    }

    private fun <T> onMain(h: Handler, block: () -> T): T {
        val done = CountDownLatch(1)
        var result: Result<T>? = null
        h.post { result = runCatching(block); done.countDown() }
        if (!done.await(20, TimeUnit.SECONDS)) throw IOException("the web view did not answer")
        return result!!.getOrElse { throw IOException(it.message ?: it.javaClass.simpleName) }
    }
}
