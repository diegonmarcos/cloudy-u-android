package com.diegonmarcos.superapp.system

import android.os.Handler
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Pure sliding-window frame counter: frames stamped inside the last [windowNs] are the frames per second. Fewer than
 * [minFrames] in the window reads 0 (a lone redraw is not an animation, and the readout's own repaint must not sustain itself).
 */
class FpsCounter(private val windowNs: Long = 1_000_000_000L, private val minFrames: Int = 2) {
    private val stamps = ArrayDeque<Long>()
    private fun trim(now: Long) { while (stamps.isNotEmpty() && now - stamps.first() >= windowNs) stamps.removeFirst() }
    fun onFrame(nowNs: Long) { stamps.addLast(nowNs); trim(nowNs) }
    fun fps(nowNs: Long): Int { trim(nowNs); return if (stamps.size < minFrames) 0 else Math.round(stamps.size * 1e9 / windowNs).toInt() }
}

/**
 * The live frame rate of what the home window is drawing (the home-screen animation, or whatever animation is on screen).
 * Measured from the window's own FrameMetrics callback (the same per-frame Choreographer/RenderThread pipeline): one callback per
 * frame ACTUALLY drawn, so an idle screen reads 0 and costs nothing, where a self-reposting Choreographer callback would force
 * a frame every vsync and only ever read the panel's refresh rate. [fps] is Compose state, published at most every
 * [PUBLISH_MS] while frames flow, plus one trailing publish after they stop so the readout falls back to 0.
 */
object FpsMeter {
    const val PUBLISH_MS = 500L
    private val counter = FpsCounter()
    private var handler: Handler? = null
    private var window: Window? = null
    private var lastPublishNs = 0L

    /** The last published reading. */
    var fps by mutableIntStateOf(0)
        private set

    private val trailing = Runnable { publish(System.nanoTime()) }
    private val listener = Window.OnFrameMetricsAvailableListener { _, _, _ -> frame(System.nanoTime()) }

    /** One drawn frame at [nowNs]. Public so a test (or any other render loop) can feed it. */
    fun frame(nowNs: Long) {
        counter.onFrame(nowNs)
        if (nowNs - lastPublishNs >= PUBLISH_MS * 1_000_000L) publish(nowNs)
        handler?.let { it.removeCallbacks(trailing); it.postDelayed(trailing, 2 * PUBLISH_MS) }
    }

    /** Publish the window's current rate now. */
    fun publish(nowNs: Long): Int {
        lastPublishNs = nowNs
        val f = counter.fps(nowNs)
        if (f != fps) fps = f
        return f
    }

    fun attach(w: Window, h: Handler) {
        detach()
        handler = h; window = w
        runCatching { w.addOnFrameMetricsAvailableListener(listener, h) }
    }

    fun detach() {
        runCatching { window?.removeOnFrameMetricsAvailableListener(listener) }
        handler?.removeCallbacks(trailing)
        window = null; handler = null
    }
}
