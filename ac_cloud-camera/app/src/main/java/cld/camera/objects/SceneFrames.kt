package cld.camera.objects

import com.diegonmarcos.superapp.image.mlkit.Scene
import com.diegonmarcos.superapp.vision.Box
import com.diegonmarcos.superapp.vision.Detection
import com.diegonmarcos.superapp.vision.FrameResult
import com.diegonmarcos.superapp.vision.TextLayout

/**
 * #1805 the engine's scene answer in the shapes Objects draws and selects: the objects (boxes in the frame's pixels,
 * as the vision lib's [FrameResult]), the text regions (each a [Detection] labelled "Text"), and the OCR [TextLayout].
 * Pure, so the JVM suite runs it (SceneFramesTest).
 */
object SceneFrames {
    fun objects(s: Scene): FrameResult = FrameResult(s.width, s.height, s.objects.filterNot { it.isText }.map(::detection), s.model)

    fun textRegions(s: Scene): List<Detection> = s.objects.filter { it.isText }.map(::detection)

    private fun detection(o: Scene.Obj) = Detection(o.label, o.p.toFloat(), null, o.x, o.y, o.x + o.w, o.y + o.h)

    private fun box(x: Float, y: Float, w: Float, h: Float) = Box(x, y, x + w, y + h)

    /** The OCR layout of [s] (null when it carries none), in its own frame's pixels. */
    fun layout(s: Scene): TextLayout? {
        val ocr = s.ocr ?: return null
        val b = TextLayout.Builder(s.width, s.height)
        ocr.blocks.forEach { k ->
            b.block(k.text, box(k.x, k.y, k.w, k.h), k.script, k.lines.map { l ->
                TextLayout.Builder.L(l.text, box(l.x, l.y, l.w, l.h), l.p.toFloat(), l.words.map { w -> TextLayout.Builder.W(w.text, box(w.x, w.y, w.w, w.h), w.p.toFloat()) })
            })
        }
        return b.build()
    }
}

/**
 * #1805 when the live stream asks for what: every admitted frame gets objects; text regions at most every
 * [textIntervalMs]; the full OCR only while the last text check found text, at most every [ocrIntervalMs], and never
 * while one is still running (it runs beside the detection, on its own thread). Pure, clock injected.
 */
class SceneThrottle(private val textIntervalMs: Long, private val ocrIntervalMs: Long) {
    private var textAt = Long.MIN_VALUE / 2
    private var ocrAt = Long.MIN_VALUE / 2
    @Volatile var textPresent = false
        private set
    @Volatile var ocrRunning = false
        private set

    /** Whether the frame at [now] should look for text regions; if so the check counts from [now]. */
    @Synchronized fun wantText(now: Long): Boolean = (now - textAt >= textIntervalMs).also { if (it) textAt = now }

    /** The text check's answer. */
    fun sawText(present: Boolean) { textPresent = present }

    /** Whether an OCR pass starts at [now]; if so it is running until [ocrDone]. */
    @Synchronized fun startOcr(now: Long): Boolean {
        if (!textPresent || ocrRunning || now - ocrAt < ocrIntervalMs) return false
        ocrAt = now
        ocrRunning = true
        return true
    }

    @Synchronized fun ocrDone() { ocrRunning = false }

    @Synchronized fun reset() { textAt = Long.MIN_VALUE / 2; ocrAt = Long.MIN_VALUE / 2; textPresent = false; ocrRunning = false }
}

/** #1805 frames per second over the last [window] frames, for the status line and the benchmark. */
class FpsMeter(private val window: Int = 20) {
    private val times = ArrayDeque<Long>()

    @Synchronized fun tick(now: Long) {
        times.addLast(now)
        while (times.size > window) times.removeFirst()
    }

    @Synchronized fun fps(): Double {
        if (times.size < 2) return 0.0
        val span = times.last() - times.first()
        return if (span <= 0) 0.0 else (times.size - 1) * 1000.0 / span
    }

    @Synchronized fun reset() = times.clear()
}
