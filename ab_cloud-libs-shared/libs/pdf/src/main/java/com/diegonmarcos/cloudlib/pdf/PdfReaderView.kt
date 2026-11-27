package com.diegonmarcos.cloudlib.pdf

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** One match of a search: [count] chars from char [start] of [page] (0-based). */
class SearchHit(val page: Int, val start: Int, val count: Int)

/**
 * The reader surface (#577): every page of a PDF in one scroll space, drawn from pdfium bitmaps.
 *
 * WHY A CUSTOM VIEW. The pdf.js reader drew ONE page on ONE canvas and turned page changes into
 * button presses; zoom was a CSS transform on that canvas, so it stayed blurry until a re-render.
 * A reader that scrolls smoothly has to own its gestures and its bitmaps, so this does:
 *
 *  - continuous mode: the whole document is one column, fitted to the view width;
 *  - paged mode: one page per screen, fitted whole; a release snaps to the nearest page and a
 *    fling moves one page (vertical, so it never fights the zoomed-page pan);
 *  - pinch and double-tap zoom, 1x-6x, about the finger; a settled zoom re-renders just the
 *    visible WINDOW of each page at the real zoom, so text stays sharp without a page-sized bitmap;
 *  - night mode is a colour matrix on the page paint (invert + hue-preserve): toggling never
 *    re-renders, and search highlights and selection are drawn after it so they keep their colour;
 *  - the page sizes are read on a background scan while the first pages are already on screen.
 *
 * Threads: everything that touches views or caches is on the main thread. Rendering has its own
 * single thread (pdfium serialises natively anyway), the size scan another so a 5,000-page scan
 * never queues ahead of a tap, and short interactive jobs (highlight rects, links, selection) a third.
 */
class PdfReaderView(context: Context) : View(context) {

    interface Host {
        /** The page at the middle of the view changed (0-based). */
        fun onPageChanged(page: Int, count: Int)

        /** A long press selected [text]. */
        fun onWordSelected(text: String)

        /** A tap landed on an external link. */
        fun onOpenLink(uri: String)
    }

    var host: Host? = null

    private val density = resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())

    private val renderExec = Executors.newSingleThreadExecutor()
    private val scanExec = Executors.newSingleThreadExecutor()
    private val workExec = Executors.newSingleThreadExecutor()

    private var engine: PdfEngine? = null
    private var layout: PdfLayout? = null

    /** Bumped when the DOCUMENT changes or closes: scan, selection and highlight jobs drop out. */
    @Volatile private var engineGen = 0

    /** Bumped whenever cached pixels stop being valid (new layout, new size): renders drop out. */
    @Volatile private var renderGen = 0

    var paged = false
        private set
    var night = false
        private set

    private var zoom = 1f
    private var offX = 0f
    private var offY = 0f
    private var currentPage = -1
    private var downPage = 0

    @Volatile private var visFirst = 0
    @Volatile private var visLast = -1

    // ── pixels ───────────────────────────────────────────────────────────────────────────────

    private class Rendered(val bmp: Bitmap, val w: Int, val h: Int)

    /** A window of one page rendered at [zoom]; [cl]..[cb] is the window in content space. */
    private class Tile(val bmp: Bitmap, val zoom: Float, val cl: Float, val ct: Float, val cr: Float, val cb: Float)

    private val baseBudget = Math.max(16 * 1024 * 1024L, Runtime.getRuntime().maxMemory() / 6)

    private val bases = object : LruCache<Int, Rendered>(baseBudget.toInt()) {
        override fun sizeOf(key: Int, value: Rendered): Int = value.bmp.allocationByteCount

        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Rendered, newValue: Rendered?) {
            if (oldValue !== newValue) oldValue.bmp.recycle()
        }
    }

    private val tiles = HashMap<Int, Tile>()
    private val pending = HashSet<Int>()

    private val pagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paperPaint = Paint().apply { color = PAPER_DAY }
    private val markPaint = Paint()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99B0BEC5.toInt() }
    private val dst = RectF()
    private val tileDst = RectF()
    private val tmp = RectF()

    // ── search, selection, links ─────────────────────────────────────────────────────────────

    private var hits: List<SearchHit> = emptyList()
    private var hitsByPage: Map<Int, List<Int>> = emptyMap()
    private var currentHit = -1
    private var searchId = -1
    private val markRects = ConcurrentHashMap<Int, List<RectF>>()
    private val markPending = HashSet<Int>()

    private class Selection(val page: Int, val rects: List<RectF>)

    private var selection: Selection? = null
    private val linkCache = ConcurrentHashMap<Int, List<PdfEngine.PageLink>>()

    private class PageHit(val page: Int, val fx: Float, val fy: Float)

    // ── motion ───────────────────────────────────────────────────────────────────────────────

    private val scroller = OverScroller(context)
    private var zoomAnimator: ValueAnimator? = null
    private var draggingBar = false
    private var scrolling = false

    private val settleRunnable = Runnable { requestSharp() }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scroller.forceFinished(true)
            zoomAnimator?.cancel()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            setZoomAround(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) = scheduleSettle()
    }).apply { isQuickScaleEnabled = false }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            scrolling = false
            val lay = layout
            if (lay != null) downPage = lay.pageAt((height / 2f - offY) / zoom)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            offX -= distanceX
            offY -= distanceY
            clampOffsets()
            afterOffsetChanged()
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val lay = layout ?: return false
            if (paged && zoom <= 1.02f) {
                val dir = if (velocityY < -FLING_PAGE_VELOCITY) 1 else if (velocityY > FLING_PAGE_VELOCITY) -1 else 0
                goToPage(downPage + dir)
                return true
            }
            scroller.fling(
                offX.toInt(), offY.toInt(), velocityX.toInt(), velocityY.toInt(),
                minOffX().toInt(), maxOffX().toInt(), minOffY().toInt(), maxOffY().toInt()
            )
            scrolling = true
            postInvalidateOnAnimation()
            return lay.pageCount > 0
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            animateZoom(if (zoom > 1.3f) 1f else DOUBLE_TAP_ZOOM, e.x, e.y)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (selection != null) {
                selection = null
                invalidate()
                return true
            }
            tapLink(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) = selectWordAt(e.x, e.y)
    })

    init {
        isFocusable = true
    }

    // ── lifecycle ────────────────────────────────────────────────────────────────────────────

    /**
     * Shows [document]. [firstPage] is page 1's size in points: the whole stack is laid out on it
     * at once and the real sizes stream in behind, which is what makes a huge file open instantly.
     */
    fun open(document: PdfEngine, firstPage: FloatArray, startPage: Int, pagedMode: Boolean, nightMode: Boolean) {
        engineGen++
        renderGen++
        clearCaches()
        hits = emptyList()
        hitsByPage = emptyMap()
        markRects.clear()
        linkCache.clear()
        selection = null
        engine = document
        paged = pagedMode
        night = nightMode
        applyNightFilter()
        zoom = 1f
        offX = 0f
        offY = 0f
        currentPage = -1
        guessW = firstPage[0]
        guessH = firstPage[1]
        layout = null
        pendingStartPage = startPage
        if (width > 0 && height > 0) buildLayout(null)
    }

    private var guessW = 612f
    private var guessH = 792f
    private var pendingStartPage = 0

    /** Stops every background job. The view cannot be reused afterwards. */
    fun destroy() {
        engineGen++
        renderGen++
        removeCallbacks(settleRunnable)
        zoomAnimator?.cancel()
        scroller.forceFinished(true)
        renderExec.shutdownNow()
        scanExec.shutdownNow()
        workExec.shutdownNow()
        engine = null
        layout = null
        clearCaches()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (engine == null || w <= 0 || h <= 0) return
        buildLayout(layout)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(settleRunnable)
        zoomAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    /**
     * (Re)creates the layout for the current view size and mode, carrying over every page size
     * already learned and the reading position (which page, how far into it).
     */
    private fun buildLayout(previous: PdfLayout?) {
        val e = engine ?: return
        val anchorPage: Int
        val anchorFrac: Float
        if (previous != null) {
            val y = -offY / zoom
            anchorPage = previous.pageAt(y)
            anchorFrac = (y - previous.slotTop(anchorPage)) / previous.slotHeight(anchorPage)
        } else {
            anchorPage = pendingStartPage.coerceIn(0, Math.max(0, e.pageCount - 1))
            anchorFrac = 0f
        }
        val lay = PdfLayout(e.pageCount, width, height, paged, GAP_DP * density, guessW, guessH)
        // Room for about three page bitmaps: read-ahead must never evict what is on screen, or a
        // wide view (a tablet, a landscape phone) would render the same page again every frame.
        val threePages = Math.min(width.toLong() * width * 20, Runtime.getRuntime().maxMemory() / 3)
        bases.resize(Math.max(baseBudget, threePages).toInt())
        if (previous != null) lay.copySizesFrom(previous)
        layout = lay
        renderGen++
        clearCaches()
        val top = lay.slotTop(anchorPage) + anchorFrac * lay.slotHeight(anchorPage)
        offY = -top * zoom
        clampOffsets()
        afterOffsetChanged()
        invalidate()
        if (previous == null) startSizeScan(e, engineGen)
    }

    private fun clearCaches() {
        bases.evictAll()
        for (t in tiles.values) t.bmp.recycle()
        tiles.clear()
        pending.clear()
    }

    // ── page sizes ───────────────────────────────────────────────────────────────────────────

    private fun startSizeScan(e: PdfEngine, gen: Int) {
        scanExec.execute {
            val n = e.pageCount
            val idx = IntArray(SCAN_BATCH)
            val w = FloatArray(SCAN_BATCH)
            val h = FloatArray(SCAN_BATCH)
            var filled = 0
            var i = 0
            while (i < n && gen == engineGen) {
                val size = e.pageSize(i)
                if (size != null) {
                    idx[filled] = i
                    w[filled] = size[0]
                    h[filled] = size[1]
                    filled++
                }
                i++
                if (filled == SCAN_BATCH || (i == n && filled > 0)) {
                    val count = filled
                    val bi = idx.copyOf(count)
                    val bw = w.copyOf(count)
                    val bh = h.copyOf(count)
                    filled = 0
                    mainHandler.post { applySizes(gen, bi, bw, bh) }
                }
            }
        }
    }

    private fun applySizes(gen: Int, idx: IntArray, w: FloatArray, h: FloatArray) {
        val lay = layout ?: return
        if (gen != engineGen) return
        val y = -offY / zoom
        val anchor = lay.pageAt(y)
        val frac = (y - lay.slotTop(anchor)) / lay.slotHeight(anchor)
        var changed = false
        for (k in idx.indices) if (lay.setSize(idx[k], w[k], h[k])) changed = true
        if (!changed) return
        lay.rebuild()
        offY = -(lay.slotTop(anchor) + frac * lay.slotHeight(anchor)) * zoom
        clampOffsets()
        afterOffsetChanged()
        invalidate()
    }

    // ── drawing ──────────────────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(if (night) BACKDROP_NIGHT else BACKDROP_DAY)
        val lay = layout ?: return
        val z = zoom
        val first = lay.pageAt(-offY / z)
        val last = lay.pageAt((height - offY) / z)
        visFirst = first
        visLast = last
        for (i in first..last) drawPage(canvas, lay, i, z)
        // One page of read-ahead each way, so a scroll never waits on the first frame of a page.
        if (first > 0) requestBase(first - 1, lay)
        if (last < lay.pageCount - 1) requestBase(last + 1, lay)
        drawScrollbar(canvas, lay)
    }

    private fun drawPage(canvas: Canvas, lay: PdfLayout, i: Int, z: Float) {
        val left = lay.pageLeft(i) * z + offX
        val top = lay.pageTop(i) * z + offY
        dst.set(left, top, left + lay.pageWidth(i) * z, top + lay.pageHeight(i) * z)
        val pw = Math.max(1, Math.round(lay.pageWidth(i)))
        val ph = Math.max(1, Math.round(lay.pageHeight(i)))
        val base = bases.get(i)
        if (base != null) canvas.drawBitmap(base.bmp, null, dst, pagePaint)
        else canvas.drawRect(dst, paperPaint)
        if (base == null || base.w != pw || base.h != ph) requestBase(i, lay)

        val tile = tiles[i]
        if (tile != null) {
            tileDst.set(
                tile.cl * z + offX, tile.ct * z + offY,
                tile.cr * z + offX, tile.cb * z + offY
            )
            canvas.drawBitmap(tile.bmp, null, tileDst, pagePaint)
        }
        drawMarks(canvas, i)
    }

    private fun drawMarks(canvas: Canvas, page: Int) {
        val indexes = hitsByPage[page]
        if (indexes != null) {
            for (idx in indexes) {
                val rects = markRects[idx]
                if (rects == null) {
                    ensureMarkRects(idx)
                    continue
                }
                markPaint.color = if (idx == currentHit) MARK_CURRENT else MARK_OTHER
                for (r in rects) canvas.drawRect(toScreen(r), markPaint)
            }
        }
        val sel = selection
        if (sel != null && sel.page == page) {
            markPaint.color = SELECTION
            for (r in sel.rects) canvas.drawRect(toScreen(r), markPaint)
        }
    }

    /** [r] is a page fraction; [dst] is the page on screen. Reuses one RectF: draw-only. */
    private fun toScreen(r: RectF): RectF {
        tmp.set(
            dst.left + r.left * dst.width(), dst.top + r.top * dst.height(),
            dst.left + r.right * dst.width(), dst.top + r.bottom * dst.height()
        )
        return tmp
    }

    private fun drawScrollbar(canvas: Canvas, lay: PdfLayout) {
        val total = lay.totalHeight * zoom
        if (total <= height * 1.5f) return
        val thumb = Math.max(THUMB_MIN_DP * density, height * height / total)
        val y = (-offY) / (total - height) * (height - thumb)
        val w = SCROLLBAR_DP * density
        canvas.drawRoundRect(width - w - 2 * density, y, width - 2 * density, y + thumb, w / 2, w / 2, barPaint)
    }

    // ── rendering ────────────────────────────────────────────────────────────────────────────

    private fun wanted(page: Int): Boolean = page >= visFirst - 1 && page <= visLast + 1

    private fun requestBase(page: Int, lay: PdfLayout) {
        val e = engine ?: return
        val pw = Math.max(1, Math.round(lay.pageWidth(page)))
        val ph = Math.max(1, Math.round(lay.pageHeight(page)))
        val cached = bases.get(page)
        if (cached != null && cached.w == pw && cached.h == ph) return
        val key = page * 2
        if (!pending.add(key)) return
        val gen = renderGen
        renderExec.execute {
            var bmp: Bitmap? = null
            var ok = false
            if (gen == renderGen && wanted(page)) {
                try {
                    bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
                    ok = e.render(page, bmp, 0, 0, pw, ph)
                } catch (oom: OutOfMemoryError) {
                    mainHandler.post { clearCaches() }
                }
            }
            val result = if (ok) bmp else null
            if (result == null) bmp?.recycle()
            mainHandler.post {
                pending.remove(key)
                if (result != null && gen == renderGen) {
                    bases.put(page, Rendered(result, pw, ph))
                    invalidate()
                } else result?.recycle()
            }
        }
    }

    private fun scheduleSettle() {
        removeCallbacks(settleRunnable)
        postDelayed(settleRunnable, SETTLE_MS)
    }

    /** After motion stops: render the visible window of each visible page at the real zoom. */
    private fun requestSharp() {
        val lay = layout ?: return
        val e = engine ?: return
        if (zoom < SHARP_ZOOM) {
            for (t in tiles.values) t.bmp.recycle()
            tiles.clear()
            invalidate()
            return
        }
        val z = zoom
        val first = lay.pageAt(-offY / z)
        val last = lay.pageAt((height - offY) / z)
        val gone = tiles.keys.filter { it < first || it > last }
        for (k in gone) tiles.remove(k)?.bmp?.recycle()
        for (i in first..last) {
            val pageL = lay.pageLeft(i) * z + offX
            val pageT = lay.pageTop(i) * z + offY
            val pageW = lay.pageWidth(i) * z
            val pageH = lay.pageHeight(i) * z
            val visL = Math.max(pageL, 0f)
            val visT = Math.max(pageT, 0f)
            val visR = Math.min(pageL + pageW, width.toFloat())
            val visB = Math.min(pageT + pageH, height.toFloat())
            if (visR <= visL || visB <= visT) continue
            val have = tiles[i]
            if (have != null && Math.abs(have.zoom - z) < z * 0.03f &&
                have.cl <= (visL - offX) / z + 1f && have.cr >= (visR - offX) / z - 1f &&
                have.ct <= (visT - offY) / z + 1f && have.cb >= (visB - offY) / z - 1f
            ) continue
            val key = i * 2 + 1
            if (!pending.add(key)) continue
            var mx = width * TILE_MARGIN
            var my = height * TILE_MARGIN
            var x0 = Math.floor(Math.max(pageL, -mx).toDouble()).toInt()
            var x1 = Math.ceil(Math.min(pageL + pageW, width + mx).toDouble()).toInt()
            var y0 = Math.floor(Math.max(pageT, -my).toDouble()).toInt()
            var y1 = Math.ceil(Math.min(pageT + pageH, height + my).toDouble()).toInt()
            if ((x1 - x0).toLong() * (y1 - y0) * 4 > TILE_BYTE_CEILING) {
                mx = 0f
                my = 0f
                x0 = Math.floor(visL.toDouble()).toInt()
                x1 = Math.ceil(visR.toDouble()).toInt()
                y0 = Math.floor(visT.toDouble()).toInt()
                y1 = Math.ceil(visB.toDouble()).toInt()
            }
            val bw = x1 - x0
            val bh = y1 - y0
            val startX = Math.round(pageL - x0)
            val startY = Math.round(pageT - y0)
            val drawW = Math.max(1, Math.round(pageW))
            val drawH = Math.max(1, Math.round(pageH))
            val cl = (x0 - offX) / z
            val ct = (y0 - offY) / z
            val cr = (x1 - offX) / z
            val cb = (y1 - offY) / z
            val gen = renderGen
            renderExec.execute {
                var bmp: Bitmap? = null
                var ok = false
                if (gen == renderGen && wanted(i) && bw > 0 && bh > 0) {
                    try {
                        bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                        ok = e.render(i, bmp, startX, startY, drawW, drawH)
                    } catch (oom: OutOfMemoryError) {
                        mainHandler.post { clearCaches() }
                    }
                }
                val result = if (ok) bmp else null
                if (result == null) bmp?.recycle()
                mainHandler.post {
                    pending.remove(key)
                    if (result != null && gen == renderGen) {
                        tiles.put(i, Tile(result, z, cl, ct, cr, cb))?.bmp?.recycle()
                        invalidate()
                    } else result?.recycle()
                    // The finger may have moved on while this rendered; one more look is cheap
                    // and does nothing when the tile still covers the view.
                    scheduleSettle()
                }
            }
        }
    }

    // ── gestures ─────────────────────────────────────────────────────────────────────────────

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val lay = layout ?: return false
        val action = ev.actionMasked
        if (draggingBar) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                draggingBar = false
                scheduleSettle()
            } else dragBar(ev.y, lay)
            return true
        }
        if (action == MotionEvent.ACTION_DOWN && ev.x > width - BAR_TOUCH_DP * density &&
            lay.totalHeight * zoom > height * 1.5f
        ) {
            draggingBar = true
            scroller.forceFinished(true)
            dragBar(ev.y, lay)
            return true
        }
        scaleDetector.onTouchEvent(ev)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(ev)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (paged && zoom <= 1.02f && scroller.isFinished && !scrolling) goToPage(lay.pageAt((height / 2f - offY) / zoom))
            scheduleSettle()
        }
        return true
    }

    private fun dragBar(y: Float, lay: PdfLayout) {
        val total = lay.totalHeight * zoom
        val thumb = Math.max(THUMB_MIN_DP * density, height * height / total)
        val frac = ((y - thumb / 2f) / (height - thumb)).coerceIn(0f, 1f)
        offY = -frac * (total - height)
        clampOffsets()
        afterOffsetChanged()
        invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offX = scroller.currX.toFloat()
            offY = scroller.currY.toFloat()
            clampOffsets()
            afterOffsetChanged()
            postInvalidateOnAnimation()
        } else if (scrolling) {
            scrolling = false
            scheduleSettle()
        }
    }

    private fun setZoomAround(target: Float, fx: Float, fy: Float) {
        val z = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val cx = (fx - offX) / zoom
        val cy = (fy - offY) / zoom
        zoom = z
        offX = fx - cx * z
        offY = fy - cy * z
        clampOffsets()
        afterOffsetChanged()
        invalidate()
    }

    private fun animateZoom(target: Float, fx: Float, fy: Float) {
        zoomAnimator?.cancel()
        scroller.forceFinished(true)
        val animator = ValueAnimator.ofFloat(zoom, target).setDuration(ZOOM_ANIMATION_MS)
        animator.addUpdateListener { setZoomAround(it.animatedValue as Float, fx, fy) }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = scheduleSettle()
        })
        zoomAnimator = animator
        animator.start()
    }

    private fun minOffX(): Float {
        val cw = width * zoom
        return if (cw <= width) (width - cw) / 2f else width - cw
    }

    private fun maxOffX(): Float {
        val cw = width * zoom
        return if (cw <= width) (width - cw) / 2f else 0f
    }

    private fun minOffY(): Float {
        val ch = (layout?.totalHeight ?: 0f) * zoom
        return if (ch <= height) (height - ch) / 2f else height - ch
    }

    private fun maxOffY(): Float {
        val ch = (layout?.totalHeight ?: 0f) * zoom
        return if (ch <= height) (height - ch) / 2f else 0f
    }

    private fun clampOffsets() {
        offX = offX.coerceIn(minOffX(), maxOffX())
        offY = offY.coerceIn(minOffY(), maxOffY())
    }

    private fun afterOffsetChanged() {
        val lay = layout ?: return
        val page = lay.pageAt((height / 2f - offY) / zoom)
        if (page != currentPage) {
            currentPage = page
            host?.onPageChanged(page, lay.pageCount)
        }
    }

    // ── public controls ──────────────────────────────────────────────────────────────────────

    fun goToPage(page: Int) {
        val lay = layout ?: return
        val target = page.coerceIn(0, lay.pageCount - 1)
        scrollTo(offX, -lay.slotTop(target) * zoom)
    }

    /** Glides to ([x], [y]) — clamped — and lets the settle pass sharpen whatever it lands on. */
    private fun scrollTo(x: Float, y: Float) {
        val tx = x.coerceIn(minOffX(), maxOffX())
        val ty = y.coerceIn(minOffY(), maxOffY())
        scroller.forceFinished(true)
        scroller.startScroll(offX.toInt(), offY.toInt(), (tx - offX).toInt(), (ty - offY).toInt(), SCROLL_ANIMATION_MS)
        scrolling = true
        postInvalidateOnAnimation()
    }

    fun setPaged(value: Boolean) {
        if (paged == value) return
        paged = value
        val lay = layout ?: return
        // A different mode is a different stack of slots: rebuild and stay on the same page.
        val keep = lay.pageAt((height / 2f - offY) / zoom)
        zoom = 1f
        offX = 0f
        offY = -lay.slotTop(keep)
        buildLayout(lay)
    }

    /** The page at the middle of the view (0-based); what the reader resumes on next time. */
    fun currentPageIndex(): Int = Math.max(0, currentPage)

    fun setNight(value: Boolean) {
        night = value
        applyNightFilter()
        invalidate()
    }

    private fun applyNightFilter() {
        pagePaint.colorFilter = if (night) ColorMatrixColorFilter(ColorMatrix(NIGHT_MATRIX)) else null
        paperPaint.color = if (night) PAPER_NIGHT else PAPER_DAY
    }

    // ── search highlights ────────────────────────────────────────────────────────────────────

    /** Replaces the highlighted matches. A new [id] means a new search: cached rects are dropped. */
    fun setHits(id: Int, list: List<SearchHit>, current: Int) {
        if (id != searchId) {
            searchId = id
            markRects.clear()
            markPending.clear()
        }
        hits = list
        val byPage = HashMap<Int, ArrayList<Int>>()
        for (k in list.indices) byPage.getOrPut(list[k].page) { ArrayList() }.add(k)
        hitsByPage = byPage
        currentHit = current
        invalidate()
    }

    private fun ensureMarkRects(index: Int) {
        val e = engine ?: return
        val hit = hits.getOrNull(index) ?: return
        if (!markPending.add(index)) return
        val gen = engineGen
        val id = searchId
        workExec.execute {
            val rects = e.rangeRects(hit.page, hit.start, hit.count)
            mainHandler.post {
                markPending.remove(index)
                if (gen == engineGen && id == searchId) {
                    markRects[index] = rects
                    invalidate()
                }
            }
        }
    }

    /** Makes [index] the current match and scrolls it into view (a third of the way down). */
    fun showHit(index: Int) {
        currentHit = index
        invalidate()
        val hit = hits.getOrNull(index) ?: return
        val e = engine ?: return
        val gen = engineGen
        val id = searchId
        workExec.execute {
            val rects = markRects[index] ?: e.rangeRects(hit.page, hit.start, hit.count)
            mainHandler.post {
                if (gen != engineGen || id != searchId) return@post
                markRects[index] = rects
                val lay = layout ?: return@post
                val r = rects.firstOrNull()
                val y = lay.pageTop(hit.page) + (r?.top ?: 0f) * lay.pageHeight(hit.page)
                val x = lay.pageLeft(hit.page) + (r?.centerX() ?: 0.5f) * lay.pageWidth(hit.page)
                scrollTo(width / 2f - x * zoom, height * 0.3f - y * zoom)
            }
        }
    }

    // ── selection and links ──────────────────────────────────────────────────────────────────

    private fun pageHit(x: Float, y: Float): PageHit? {
        val lay = layout ?: return null
        val cx = (x - offX) / zoom
        val cy = (y - offY) / zoom
        val i = lay.pageAt(cy)
        val l = lay.pageLeft(i)
        val t = lay.pageTop(i)
        val w = lay.pageWidth(i)
        val h = lay.pageHeight(i)
        if (cx < l || cx > l + w || cy < t || cy > t + h) return null
        return PageHit(i, (cx - l) / w, (cy - t) / h)
    }

    private fun selectWordAt(x: Float, y: Float) {
        val hit = pageHit(x, y) ?: return
        val e = engine ?: return
        val gen = engineGen
        workExec.execute {
            val index = e.charAt(hit.page, hit.fx, hit.fy)
            if (index < 0) return@execute
            val text = e.pageText(hit.page)
            val range = PdfWords.wordRange(text, index) ?: return@execute
            val word = text.substring(range.first, range.last + 1)
            val rects = e.rangeRects(hit.page, range.first, range.last - range.first + 1)
            mainHandler.post {
                if (gen != engineGen) return@post
                selection = Selection(hit.page, rects)
                invalidate()
                host?.onWordSelected(word)
            }
        }
    }

    private fun tapLink(x: Float, y: Float) {
        val hit = pageHit(x, y) ?: return
        val e = engine ?: return
        val gen = engineGen
        workExec.execute {
            val links = linkCache.getOrPut(hit.page) { e.links(hit.page) }
            val link = links.firstOrNull { it.rect.contains(hit.fx, hit.fy) } ?: return@execute
            mainHandler.post {
                if (gen != engineGen) return@post
                if (link.page != null) goToPage(link.page)
                else link.uri?.let { host?.onOpenLink(it) }
            }
        }
    }

    private companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 6f
        const val DOUBLE_TAP_ZOOM = 2.5f

        /** Below this a settled zoom keeps the 1x page bitmap: re-rendering would gain nothing. */
        const val SHARP_ZOOM = 1.08f

        const val SETTLE_MS = 110L
        const val ZOOM_ANIMATION_MS = 200L
        const val SCROLL_ANIMATION_MS = 260

        /** Gap between pages in continuous mode, dp. */
        const val GAP_DP = 8f

        /** Fraction of the view kept around the visible window when a sharp tile is cut. */
        const val TILE_MARGIN = 0.15f
        const val TILE_BYTE_CEILING = 40L * 1024 * 1024

        /** Paged mode: a fling faster than this turns a page. px/s. */
        const val FLING_PAGE_VELOCITY = 600f

        const val SCAN_BATCH = 96
        const val SCROLLBAR_DP = 4f
        const val THUMB_MIN_DP = 48f
        const val BAR_TOUCH_DP = 36f

        val BACKDROP_DAY = 0xFF3A3F45.toInt()
        val BACKDROP_NIGHT = 0xFF000000.toInt()
        const val PAPER_DAY = -1
        val PAPER_NIGHT = 0xFF121212.toInt()
        val MARK_OTHER = 0x66FFEB3B
        val MARK_CURRENT = 0x99FF9800.toInt()
        val SELECTION = 0x662979FF

        /**
         * Invert, then rotate the hue back half a turn (what CSS `invert(1) hue-rotate(180deg)`
         * does): white paper goes black and black text goes white, but a red stamp stays red-ish
         * instead of turning cyan. Rows are R', G', B', A' over R, G, B, A, offset (0..255).
         */
        val NIGHT_MATRIX = floatArrayOf(
            0.574f, -1.430f, -0.144f, 0f, 255f,
            -0.426f, -0.430f, -0.144f, 0f, 255f,
            -0.426f, -1.430f, 0.856f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    }
}
