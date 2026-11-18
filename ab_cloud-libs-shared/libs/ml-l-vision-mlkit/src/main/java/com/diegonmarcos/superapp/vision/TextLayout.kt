package com.diegonmarcos.superapp.vision

/**
 * #1805 the recognised text of ONE frame or image, laid out: blocks, lines and words, each with its
 * box in the pixels of a [frameWidth] x [frameHeight] frame. Words are numbered in reading order
 * (block by block, line by line), so a selection is a range of word indices and its text is the
 * words joined with the spaces and line breaks they had. Pure Kotlin: the JVM suite runs the hit
 * testing and the selection (TextSelectionTest).
 */
class TextLayout(val frameWidth: Int, val frameHeight: Int, val blocks: List<Block>) {
    data class Word(val index: Int, val text: String, val box: Box, val confidence: Float, val line: Int, val block: Int)
    data class Line(val index: Int, val text: String, val box: Box, val confidence: Float, val block: Int, val first: Int, val last: Int)
    data class Block(val index: Int, val text: String, val box: Box, val script: String, val lines: List<Line>, val words: List<Word>) {
        val first: Int get() = words.firstOrNull()?.index ?: -1
        val last: Int get() = words.lastOrNull()?.index ?: -1
    }

    /** What a tap landed on, smallest first: a word, else the line around the point, else the block. */
    enum class Level { WORD, LINE, BLOCK }
    data class Hit(val level: Level, val index: Int)

    val lines: List<Line> = blocks.flatMap { it.lines }
    val words: List<Word> = blocks.flatMap { it.words }
    val isEmpty: Boolean get() = words.isEmpty()

    /** Every word, as read: words of a line by spaces, lines by a newline, blocks by a blank line. */
    val allText: String get() = if (words.isEmpty()) "" else textOf(0, words.size - 1)

    /** Words [from]..[to] (inclusive, either order) as the text they make. */
    fun textOf(from: Int, to: Int): String {
        if (words.isEmpty()) return ""
        val a = minOf(from, to).coerceIn(0, words.size - 1)
        val b = maxOf(from, to).coerceIn(0, words.size - 1)
        val sb = StringBuilder()
        for (i in a..b) {
            val w = words[i]
            if (i > a) {
                val p = words[i - 1]
                sb.append(if (p.block != w.block) "\n\n" else if (p.line != w.line) "\n" else " ")
            }
            sb.append(w.text)
        }
        return sb.toString()
    }

    fun rangeOf(hit: Hit): IntRange = when (hit.level) {
        Level.WORD -> hit.index..hit.index
        Level.LINE -> lines[hit.index].first..lines[hit.index].last
        Level.BLOCK -> blocks[hit.index].first..blocks[hit.index].last
    }

    /**
     * The element at frame point ([x], [y]): the word whose box (grown by [slop] frame pixels)
     * holds it — the nearest centre when several do — else the line, else the block. Null off
     * every text box.
     */
    fun hit(x: Float, y: Float, slop: Float = 0f): Hit? {
        fun inside(b: Box, s: Float) = x >= b.left - s && x <= b.right + s && y >= b.top - s && y <= b.bottom + s
        fun d2(b: Box) = (b.centerX - x) * (b.centerX - x) + (b.centerY - y) * (b.centerY - y)
        words.filter { inside(it.box, slop) }.minByOrNull { d2(it.box) }?.let { return Hit(Level.WORD, it.index) }
        lines.filter { it.last >= it.first && inside(it.box, slop) }.minByOrNull { d2(it.box) }?.let { return Hit(Level.LINE, it.index) }
        blocks.filter { it.words.isNotEmpty() && inside(it.box, slop) }.minByOrNull { d2(it.box) }?.let { return Hit(Level.BLOCK, it.index) }
        return null
    }

    /** The word nearest frame point ([x], [y]) in reading terms: on the nearest line, the nearest word along it. A handle drag snaps here. */
    fun nearestWord(x: Float, y: Float): Int? {
        if (words.isEmpty()) return null
        val line = lines.filter { it.last >= it.first }.minByOrNull { l ->
            val dy = if (y < l.box.top) l.box.top - y else if (y > l.box.bottom) y - l.box.bottom else 0f
            val dx = if (x < l.box.left) l.box.left - x else if (x > l.box.right) x - l.box.right else 0f
            dy * 4f + dx
        } ?: return null
        return (line.first..line.last).minByOrNull { i ->
            val b = words[i].box
            if (x < b.left) b.left - x else if (x > b.right) x - b.right else 0f
        }
    }

    /** The same layout in a frame scaled by [s] (an OCR frame read larger than the detection frame, drawn over it). */
    fun scaled(s: Float, width: Int = Math.round(frameWidth * s), height: Int = Math.round(frameHeight * s)): TextLayout {
        fun b(x: Box) = Box(x.left * s, x.top * s, x.right * s, x.bottom * s)
        return TextLayout(width, height, blocks.map { k ->
            k.copy(box = b(k.box), lines = k.lines.map { it.copy(box = b(it.box)) }, words = k.words.map { it.copy(box = b(it.box)) })
        })
    }

    /** Builds a layout block by block; word, line and block indices are assigned in the order given. */
    class Builder(private val w: Int, private val h: Int) {
        private val blocks = ArrayList<Block>()
        private var nextWord = 0
        private var nextLine = 0

        /** One word: its text, box, confidence. */
        data class W(val text: String, val box: Box, val confidence: Float = 1f)
        /** One line: its text, box, confidence and words (a line ML Kit split into none becomes one word). */
        data class L(val text: String, val box: Box, val confidence: Float, val words: List<W>)

        fun block(text: String, box: Box, script: String, lines: List<L>): Builder {
            val bi = blocks.size
            val ls = ArrayList<Line>()
            val ws = ArrayList<Word>()
            for (l in lines) {
                val li = nextLine++
                val first = nextWord
                val lw = l.words.ifEmpty { listOf(W(l.text, l.box, l.confidence)) }.filter { it.text.isNotBlank() }
                lw.forEach { ws += Word(nextWord++, it.text, it.box, it.confidence, li, bi) }
                ls += Line(li, l.text, l.box, l.confidence, bi, first, nextWord - 1)
            }
            blocks += Block(bi, text, box, script, ls, ws)
            return this
        }

        fun build() = TextLayout(w, h, blocks)
    }
}

/**
 * #1805 a selection over one [TextLayout]: words [start]..[end] in reading order (start <= end).
 * Tapping selects a word; tapping inside the selection again grows it to its line, then its block;
 * a handle drag moves one end to the word nearest the finger, never past the other end.
 */
data class TextSelection(val start: Int, val end: Int) {
    val range: IntRange get() = start..end
    fun text(layout: TextLayout): String = layout.textOf(start, end)

    enum class Handle { START, END }

    companion object {
        fun of(r: IntRange) = TextSelection(minOf(r.first, r.last), maxOf(r.first, r.last))

        /**
         * What a tap at frame point ([x], [y]) selects, given what is [current]ly selected: the element
         * the tap hit, or — when the tap lands on a word already inside the selection — the next level
         * up that is larger than the selection (word → line → block). Null when the tap hit no text.
         */
        fun tap(layout: TextLayout, current: TextSelection?, x: Float, y: Float, slop: Float = 0f): TextSelection? {
            val hit = layout.hit(x, y, slop) ?: return null
            val picked = of(layout.rangeOf(hit))
            if (current == null || hit.level != TextLayout.Level.WORD || hit.index !in current.range) return picked
            val w = layout.words[hit.index]
            val line = of(layout.rangeOf(TextLayout.Hit(TextLayout.Level.LINE, w.line)))
            val block = of(layout.rangeOf(TextLayout.Hit(TextLayout.Level.BLOCK, w.block)))
            return when {
                current.size() < line.size() -> line
                current.size() < block.size() -> block
                else -> picked
            }
        }

        /** [sel] with its [handle] moved to the word nearest frame point ([x], [y]). */
        fun drag(layout: TextLayout, sel: TextSelection, handle: Handle, x: Float, y: Float): TextSelection {
            val w = layout.nearestWord(x, y) ?: return sel
            return when (handle) {
                Handle.START -> TextSelection(minOf(w, sel.end), sel.end)
                Handle.END -> TextSelection(sel.start, maxOf(w, sel.start))
            }
        }

        fun all(layout: TextLayout): TextSelection? = if (layout.isEmpty) null else TextSelection(0, layout.words.size - 1)
    }

    fun size() = end - start + 1
}

/**
 * #1805 the recognised text CACHED per frame: an OCR pass is the expensive step, so its layout is
 * kept with the frame it read (and that frame's pixels, for a freeze), keyed by the frame's id. A
 * frozen, captured or opened frame looks its text up here and is never read twice. Least recently
 * used entries go first beyond [capacity]; [onEvict] lets the owner free a bitmap.
 */
class OcrCache<P>(private val capacity: Int = 4, private val onEvict: (P) -> Unit = {}) {
    data class Entry<P>(val frameId: Long, val layout: TextLayout, val pixels: P, val atMs: Long)

    private val map = LinkedHashMap<Long, Entry<P>>(16, 0.75f, true)

    @Synchronized fun put(frameId: Long, layout: TextLayout, pixels: P, atMs: Long) {
        map.remove(frameId)?.let { if (it.pixels !== pixels) onEvict(it.pixels) }
        map[frameId] = Entry(frameId, layout, pixels, atMs)
        while (map.size > capacity) {
            val eldest = map.entries.iterator().next()
            map.remove(eldest.key)
            onEvict(eldest.value.pixels)
        }
    }

    @Synchronized fun get(frameId: Long): Entry<P>? = map[frameId]

    /** The newest entry by time, without touching the order. */
    @Synchronized fun latest(): Entry<P>? = map.values.maxByOrNull { it.atMs }

    /** The newest entry read at or after [sinceMs]: what a freeze can reuse instead of reading again. */
    @Synchronized fun freshSince(sinceMs: Long): Entry<P>? = latest()?.takeIf { it.atMs >= sinceMs }

    @Synchronized fun size(): Int = map.size

    @Synchronized fun clear() {
        map.values.forEach { onEvict(it.pixels) }
        map.clear()
    }
}
