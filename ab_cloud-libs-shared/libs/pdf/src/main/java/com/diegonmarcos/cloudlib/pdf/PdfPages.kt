package com.diegonmarcos.cloudlib.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/** The platform's PdfRenderer over one file: page count, page sizes and page bitmaps. PdfRenderer holds one open page at a time, so access is serialised. */
class PdfPages(file: File) : AutoCloseable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)

    val count: Int get() = renderer.pageCount

    /** The page's size in points, as the renderer reports it (rotation already applied). */
    @Synchronized
    fun size(index: Int): Pair<Int, Int> = renderer.openPage(index).use { it.width to it.height }

    /** The page drawn on white, [widthPx] wide. */
    @Synchronized
    fun render(index: Int, widthPx: Int): Bitmap = renderer.openPage(index).use { page ->
        val w = widthPx.coerceAtLeast(1)
        val h = (page.height.toFloat() * w / page.width).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bmp
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
}
