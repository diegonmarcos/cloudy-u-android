package com.diegonmarcos.cloudlib.wysiwyg

/** The edit operations of the engine, pure functions over [Block] and [Doc] (the Compose layer only calls them). */
object Ops {

    /**
     * [block] with [newText] typed over it: the marks follow the edit. A mark before the edit stays, one after it shifts,
     * one that ends where text is typed grows (typing at the end of bold stays bold), one that starts there shifts.
     */
    fun withText(block: Block, newText: String): Block {
        val old = block.text
        if (old == newText) return block
        val max = minOf(old.length, newText.length)
        var p = 0
        while (p < max && old[p] == newText[p]) p++
        var s = 0
        while (s < max - p && old[old.length - 1 - s] == newText[newText.length - 1 - s]) s++
        val removedEnd = old.length - s
        val ins = newText.length - p - s
        val delta = ins - (removedEnd - p)
        fun start(x: Int) = if (x < p) x else if (x >= removedEnd) x + delta else p
        fun end(x: Int) = if (x < p) x else if (x >= removedEnd) x + delta else p + ins
        val marks = block.marks.mapNotNull { m ->
            val a = start(m.start).coerceIn(0, newText.length)
            val b = end(m.end).coerceIn(0, newText.length)
            if (b > a) m.copy(start = a, end = b) else null
        }
        return block.copy(text = newText, marks = normalize(marks))
    }

    /**
     * Toggles [type] over [start]..[end]: when every character of the range already has it, it is removed there; otherwise it
     * is added over the whole range. A Link is never toggled (see [setLink]).
     */
    fun toggle(block: Block, type: MarkType, start: Int, end: Int): Block {
        require(type != MarkType.Link) { "a link needs a url: use setLink" }
        val a = start.coerceIn(0, block.text.length)
        val b = end.coerceIn(0, block.text.length)
        if (b <= a) return block
        val cover = BooleanArray(block.text.length)
        for (m in block.marks) if (m.type == type) for (i in m.start until minOf(m.end, cover.size)) cover[i] = true
        val all = (a until b).all { cover[it] }
        for (i in a until b) cover[i] = !all
        val rebuilt = ArrayList<Mark>()
        var i = 0
        while (i < cover.size) {
            if (cover[i]) {
                var j = i
                while (j < cover.size && cover[j]) j++
                rebuilt += Mark(type, i, j)
                i = j
            } else i++
        }
        return block.copy(marks = normalize(block.marks.filter { it.type != type } + rebuilt))
    }

    /** Links [start]..[end] to [url]; a blank url removes the links overlapping the range. */
    fun setLink(block: Block, start: Int, end: Int, url: String): Block {
        val a = start.coerceIn(0, block.text.length)
        val b = end.coerceIn(0, block.text.length)
        if (b <= a) return block
        val kept = block.marks.filter { !(it.type == MarkType.Link && it.start < b && it.end > a) }
        return block.copy(marks = normalize(if (url.isBlank()) kept else kept + Mark(MarkType.Link, a, b, url)))
    }

    /** The block turned into [kind] (a heading takes [level]); marks stay, a Rule loses its text. */
    fun setKind(block: Block, kind: BlockKind, level: Int = 0): Block = block.copy(
        kind = kind,
        level = if (kind == BlockKind.Heading) level.coerceIn(1, 6) else 0,
        text = if (kind == BlockKind.Rule) "" else block.text,
        marks = if (kind == BlockKind.Rule || kind == BlockKind.Code) emptyList() else block.marks,
    )

    /** Splits the block at [offset] (Enter): the head keeps the id, the tail is a new block of the same kind (a heading continues as a paragraph). */
    fun split(block: Block, offset: Int): Pair<Block, Block> {
        val at = offset.coerceIn(0, block.text.length)
        val head = block.copy(
            text = block.text.substring(0, at),
            marks = block.marks.mapNotNull { m -> if (m.start < at) m.copy(end = minOf(m.end, at)) else null },
        )
        val tail = Block(
            kind = if (block.kind == BlockKind.Heading) BlockKind.Paragraph else block.kind,
            text = block.text.substring(at),
            marks = block.marks.mapNotNull { m -> if (m.end > at) m.copy(start = maxOf(m.start, at) - at, end = m.end - at) else null },
        )
        return head to tail
    }

    /** Joins [second] onto the end of [first] (Backspace at the start of a block). */
    fun merge(first: Block, second: Block): Block {
        val shift = first.text.length
        return first.copy(text = first.text + second.text, marks = normalize(first.marks + second.marks.map { it.copy(start = it.start + shift, end = it.end + shift) }))
    }

    fun replace(doc: Doc, index: Int, block: Block): Doc = Doc(doc.blocks.mapIndexed { i, b -> if (i == index) block else b })

    fun insertAfter(doc: Doc, index: Int, block: Block): Doc = Doc(doc.blocks.take(index + 1) + block + doc.blocks.drop(index + 1))

    fun remove(doc: Doc, index: Int): Doc = Doc(doc.blocks.filterIndexed { i, _ -> i != index })

    /** Marks sorted, each type's touching or overlapping runs merged (links merge only with the same url). */
    fun normalize(marks: List<Mark>): List<Mark> {
        val out = ArrayList<Mark>()
        for (m in marks.filter { it.end > it.start }.sortedWith(compareBy({ it.type }, { it.url.orEmpty() }, { it.start }))) {
            val last = out.lastOrNull()
            if (last != null && last.type == m.type && last.url == m.url && m.start <= last.end) out[out.size - 1] = last.copy(end = maxOf(last.end, m.end))
            else out += m
        }
        return out.sortedWith(compareBy({ it.start }, { it.end }, { it.type }))
    }

    /** The number each Numbered block shows (0 for every other block): 1, 2, 3 within a run, restarting after any other block. */
    fun numbering(blocks: List<Block>): List<Int> {
        val counters = IntArray(8)
        return blocks.map { b ->
            if (b.kind == BlockKind.Numbered) {
                val l = b.level.coerceIn(0, 6)
                counters[l]++
                for (d in l + 1 until counters.size) counters[d] = 0
                counters[l]
            } else {
                if (b.kind != BlockKind.Bullet) counters.fill(0)
                0
            }
        }
    }
}
