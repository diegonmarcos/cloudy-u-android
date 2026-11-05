package com.diegonmarcos.superapp.ocr

/** [left, top, right, bottom] in source pixels. */
data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
    val w get() = r - l
    val h get() = b - t
    val cy get() = (t + b) / 2
    fun union(o: Box) = Box(minOf(l, o.l), minOf(t, o.t), maxOf(r, o.r), maxOf(b, o.b))
    fun list() = listOf(l, t, r, b)
}

/** A word. [estimated] = the box was derived from its line (the engine reports lines), not measured. */
data class OcrWord(val text: String, val box: Box, val confidence: Double? = null, val estimated: Boolean = false)

data class OcrLine(val text: String, val box: Box, val confidence: Double? = null, val words: List<OcrWord> = emptyList())

data class OcrBlock(val lines: List<OcrLine>) {
    val box: Box get() = lines.map { it.box }.reduce { a, b -> a.union(b) }
    val text: String get() = lines.joinToString("\n") { it.text }
}

/** One table cell; [row] and [col] are 0-based, row 0 is the header. */
data class OcrCell(val text: String, val row: Int, val col: Int, val box: Box)

data class OcrTable(val box: Box, val rows: List<List<OcrCell>>, val columns: Int, val lines: List<OcrLine>)

data class OcrResult(
    val width: Int,
    val height: Int,
    val blocks: List<OcrBlock>,
    val tables: List<OcrTable>,
    val languages: List<String>,
    val engine: String,
    val engineVersion: String = "",
) {
    /** Mean of the line confidences that are known; null when the engine reported none. */
    val meanConfidence: Double?
        get() = blocks.flatMap { it.lines }.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }?.average()
}
