package com.diegonmarcos.superapp.image.mlkit

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * #1805 the scene method's pure halves, kept off Android so the JVM suite runs them (SceneTest):
 * box overlap and NMS, the letterbox YOLO is fed through and its inverse, YOLO11n's output decode,
 * and the merge of the five OCR scripts' reads into one layout.
 */

/** A box in a frame's pixels: left, top, right, bottom. */
internal data class Rect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w: Float get() = max(0f, r - l)
    val h: Float get() = max(0f, b - t)
    val area: Float get() = w * h

    fun scaled(s: Float) = Rect(l * s, t * s, r * s, b * s)

    fun intersection(o: Rect): Float = max(0f, min(r, o.r) - max(l, o.l)) * max(0f, min(b, o.b) - max(t, o.t))

    fun iou(o: Rect): Float {
        val i = intersection(o)
        val u = area + o.area - i
        return if (u <= 0f) 0f else i / u
    }

    /** How much of the smaller box the other covers: 1 when one holds the other. */
    fun containment(o: Rect): Float {
        val m = min(area, o.area)
        return if (m <= 0f) 0f else intersection(o) / m
    }
}

/** One detected object: its class index and name, score, box in frame pixels. */
internal data class SceneObj(val cls: Int, val label: String, val p: Float, val box: Rect)

internal object SceneMath {
    /** Greedy per-class NMS over [objs], best first, at most [max] kept. */
    fun nms(objs: List<SceneObj>, iou: Float, max: Int): List<SceneObj> {
        val kept = ArrayList<SceneObj>()
        for (o in objs.sortedByDescending { it.p }) {
            if (kept.size >= max) break
            if (kept.none { it.cls == o.cls && it.box.iou(o.box) > iou }) kept += o
        }
        return kept
    }

    /** How a [w] x [h] frame sits inside a [side] square: scale, then centred padding. */
    data class Letterbox(val side: Int, val scale: Float, val padX: Int, val padY: Int, val newW: Int, val newH: Int) {
        /** A box in the square's pixels, back in the frame's (clamped to it). */
        fun toFrame(box: Rect, w: Int, h: Int) = Rect(
            ((box.l - padX) / scale).coerceIn(0f, w.toFloat()), ((box.t - padY) / scale).coerceIn(0f, h.toFloat()),
            ((box.r - padX) / scale).coerceIn(0f, w.toFloat()), ((box.b - padY) / scale).coerceIn(0f, h.toFloat()),
        )
    }

    fun letterbox(w: Int, h: Int, side: Int): Letterbox {
        val s = min(side.toFloat() / w, side.toFloat() / h)
        val nw = (w * s).roundToInt().coerceIn(1, side)
        val nh = (h * s).roundToInt().coerceIn(1, side)
        return Letterbox(side, s, (side - nw) / 2, (side - nh) / 2, nw, nh)
    }

    /**
     * YOLO11n's raw answer: [out] is [channels] x [anchors] (channels first, the LiteRT export's
     * [1, 84, 8400]) or anchors x channels; each anchor is centre x, y, w, h in the [side] square's
     * pixels (or 0..1, scaled up) then one score per class. Scores outside 0..1 are logits and pass
     * a sigmoid. Each anchor keeps its best class at or above [minConfidence]; NMS per class at
     * [iou]; boxes come back in the frame's pixels through [lb].
     */
    fun decodeYolo(
        out: FloatArray, channels: Int, anchors: Int, channelsFirst: Boolean, labels: List<String>,
        lb: Letterbox, frameW: Int, frameH: Int, minConfidence: Float, iou: Float, max: Int,
    ): List<SceneObj> {
        val nc = channels - 4
        require(nc > 0 && out.size >= channels * anchors) { "a YOLO output of $channels x $anchors needs ${channels * anchors} values, has ${out.size}" }
        fun at(c: Int, a: Int) = if (channelsFirst) out[c * anchors + a] else out[a * channels + c]
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (a in 0 until anchors) for (c in 4 until channels) { val v = at(c, a); if (v < lo) lo = v; if (v > hi) hi = v }
        val logits = lo < 0f || hi > 1f
        var unit = 0f
        for (a in 0 until anchors) for (c in 0 until 4) unit = max(unit, at(c, a))
        val k = if (unit <= 2f) lb.side.toFloat() else 1f
        val found = ArrayList<SceneObj>()
        for (a in 0 until anchors) {
            var best = -1
            var p = 0f
            for (c in 0 until nc) {
                val raw = at(4 + c, a)
                val s = if (logits) (1f / (1f + exp(-raw))) else raw
                if (s > p) { p = s; best = c }
            }
            if (best < 0 || p < minConfidence) continue
            val cx = at(0, a) * k; val cy = at(1, a) * k; val bw = at(2, a) * k; val bh = at(3, a) * k
            val box = lb.toFrame(Rect(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2), frameW, frameH)
            if (box.area <= 0f) continue
            found += SceneObj(best, labels.getOrElse(best) { "class $best" }, p, box)
        }
        return nms(found, iou, max)
    }

    /** One OCR word, line and block, boxes in the frame's pixels; [p] is ML Kit's confidence (1 when it gives none). */
    data class Word(val text: String, val box: Rect, val p: Float)
    data class Line(val text: String, val box: Rect, val p: Float, val words: List<Word>)
    data class Block(val text: String, val box: Rect, val script: String, val lang: String, val lines: List<Line>) {
        val p: Float get() = lines.flatMap { it.words }.map { it.p }.average().toFloat().takeUnless { it.isNaN() } ?: lines.map { it.p }.average().toFloat().takeUnless { it.isNaN() } ?: 0f
        fun scaled(s: Float) = copy(box = box.scaled(s), lines = lines.map { l -> l.copy(box = l.box.scaled(s), words = l.words.map { it.copy(box = it.box.scaled(s)) }) })
    }

    /** What a script's read of a region is worth: its letters and digits, weighed by its confidence. */
    fun score(b: Block): Float = b.text.count { it.isLetterOrDigit() } * (0.5f + 0.5f * b.p)

    /**
     * The five scripts' reads of one frame, in [order] (Latin first), into one layout: every block,
     * best first by [score] (a tie goes to the earlier script), is kept unless a kept block already
     * covers its region (IoU over [iou] or one holding the other over [contain]). The Chinese and
     * Japanese models also read Latin, so a Latin sign is not doubled, and a Hindi sign keeps the
     * Devanagari read over the few letters the Latin model saw. Reading order: top to bottom, then
     * left to right within a line's height.
     */
    fun mergeScripts(reads: Map<String, List<Block>>, order: List<String>, iou: Float = 0.3f, contain: Float = 0.6f): List<Block> {
        val all = order.flatMapIndexed { i, s -> reads[s].orEmpty().filter { it.text.isNotBlank() }.map { Triple(i, s, it) } }
        val kept = ArrayList<Block>()
        for ((_, _, b) in all.sortedWith(compareByDescending<Triple<Int, String, Block>> { score(it.third) }.thenBy { it.first })) {
            if (kept.none { it.box.iou(b.box) > iou || it.box.containment(b.box) > contain }) kept += b
        }
        return readingOrder(kept)
    }

    fun readingOrder(blocks: List<Block>): List<Block> {
        if (blocks.isEmpty()) return blocks
        val unit = blocks.map { b -> b.lines.map { it.box.h }.average().toFloat().takeUnless { it.isNaN() } ?: b.box.h }.sorted()[blocks.size / 2].coerceAtLeast(1f)
        return blocks.sortedWith(compareBy<Block> { (it.box.t / unit).toInt() }.thenBy { it.box.l })
    }

    /** Percentile [q] (0..1) of [ms], nearest rank. */
    fun percentile(ms: List<Long>, q: Double): Long {
        if (ms.isEmpty()) return 0
        val s = ms.sorted()
        return s[((q * s.size).toInt()).coerceIn(0, s.size - 1)]
    }
}
