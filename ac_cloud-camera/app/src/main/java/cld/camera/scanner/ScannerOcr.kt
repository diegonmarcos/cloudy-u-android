package cld.camera.scanner

import android.content.Context
import android.graphics.Bitmap
import cld.camera.analyzer.ImageContentScanner
import com.diegonmarcos.cloudlib.scanner.OcrWord
import com.diegonmarcos.superapp.image.mlkit.Scene

/**
 * #1839 on-device OCR of a scanned page through the fleet's ONE image engine (the same ML Kit text recognition Objects reads
 * text with, run in the engine process, no network): the page text and every word with its box, which the searchable PDF's
 * invisible text layer is made of. Blocking; call off the main thread.
 */
class ScannerOcr(context: Context) {
    private val scanner = ImageContentScanner(context)

    class Result(val text: String, val words: List<OcrWord>, val error: String?)

    fun read(page: Bitmap): Result {
        runCatching { scanner.sceneStatus() }.getOrElse { it.message }?.let { return Result("", emptyList(), it) }
        val side = maxOf(page.width, page.height)
        val req = Scene.request(Scene.defaultDetector(), objects = false, text = false, ocr = true)
        req.getJSONObject("scene").put("live_side", side).put("ocr_side", side)
        val s = scanner.scene(page, req)
        if (!s.ok) return Result("", emptyList(), s.error ?: "no answer")
        val ocr = s.ocr ?: return Result("", emptyList(), null)
        return Result(ocr.text.trim(), wordsOf(ocr, page.width, page.height, s.width, s.height), null)
    }

    companion object {
        /** The engine answers in the pixels of the image it was handed (maybe resized): bring every word back to the page bitmap's. */
        fun wordsOf(ocr: Scene.Ocr, pageW: Int, pageH: Int, sceneW: Int, sceneH: Int): List<OcrWord> {
            val sx = if (sceneW > 0) pageW.toDouble() / sceneW else 1.0
            val sy = if (sceneH > 0) pageH.toDouble() / sceneH else 1.0
            return ocr.blocks.flatMap { b -> b.lines.flatMap { l -> l.words } }
                .map { OcrWord(it.text, it.x * sx, it.y * sy, it.w * sx, it.h * sy) }
        }
    }
}
