package com.diegonmarcos.cloudlib.scanner

import android.graphics.Bitmap
import com.diegonmarcos.superapp.mlocr.MlKitOcr

/** #1908 what an OCR engine hands back for one page: the text, every word with its box in the page's pixels, or the reason it could not. */
class OcrResult(val text: String, val words: List<OcrWord>, val error: String?)

/** One OCR engine. Blocking: call off the main thread. The host picks the engine (the fleet image engine, or [MlKitPageOcr]). */
fun interface PageOcr {
    fun read(page: Bitmap): OcrResult
}

/** #1908 the bundled ML Kit text recognition (libs:ml-l-image-ocr), on device and with no engine APK; every bundled script model. */
class MlKitPageOcr : PageOcr {
    override fun read(page: Bitmap): OcrResult {
        val r = MlKitOcr.readWords(page)
        val text = r.text ?: return OcrResult("", emptyList(), r.error ?: "no text")
        return OcrResult(text, r.words.map { OcrWord(it.text, it.x.toDouble(), it.y.toDouble(), it.w.toDouble(), it.h.toDouble()) }, null)
    }
}

/** #1908 OCR text helpers of the Tools hub and the export sheet. */
object OcrText {
    /** The whole text of [pages], one headed section per page when there are several. */
    fun joined(texts: List<String>): String =
        if (texts.size == 1) texts[0] else texts.mapIndexed { i, t -> "--- ${i + 1} ---\n$t" }.joinToString("\n\n")

    /** Word count and character count, the line under the OCR box. */
    fun counts(text: String): Pair<Int, Int> = text.split(Regex("\\s+")).count { it.isNotEmpty() } to text.length
}
