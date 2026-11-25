package com.diegonmarcos.superapp.mlocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * #1418 ON-DEVICE OCR: ML Kit Text Recognition v2 with every bundled script model.
 *
 * Latin runs first. The other four scripts' models also read Latin, so each is tried only while
 * the text read so far holds no letters of its own and the best read is the one with the most
 * letters and digits: a page in Hindi is not returned as the handful of Latin characters the Latin
 * model found on it. BLOCKING (Tasks.await): call it from a worker thread, never the main thread.
 *
 * #1805 [text] is one script's whole ML Kit answer (blocks, lines and words, each with its box and
 * confidence), on the same recognizer singletons, for a caller that lays the text out itself: the
 * image engine's scene method (Cloud Camera > Objects) reads every script and keeps, per region,
 * the best read.
 */
object MlKitOcr {
    enum class Script { LATIN, CHINESE, DEVANAGARI, JAPANESE, KOREAN }

    class Read(val text: String?, val script: Script?, val error: String?)

    private val recognizers = mutableMapOf<Script, TextRecognizer>()

    @Synchronized
    private fun recognizer(s: Script): TextRecognizer = recognizers.getOrPut(s) {
        when (s) {
            Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            Script.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        }
    }

    private fun score(t: String): Int = t.count { it.isLetterOrDigit() }

    /** Characters a script's own model exists for; when the Latin read has none, the others are tried. */
    private fun nonLatinNeeded(latin: String): Boolean = score(latin) < 3 || latin.any { Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN && it.isLetter() }

    /** #1805 [script]'s structured read of [bitmap]; the failure when its recognizer threw or timed out. */
    fun text(bitmap: Bitmap, script: Script, timeoutSeconds: Long = 30): Result<Text> =
        runCatching { Tasks.await(recognizer(script).process(InputImage.fromBitmap(bitmap, 0)), timeoutSeconds, TimeUnit.SECONDS) }

    fun read(bitmap: Bitmap, timeoutSeconds: Long = 30): Read {
        val image = InputImage.fromBitmap(bitmap, 0)
        var best: Pair<Script, String>? = null
        var lastError: String? = null
        for (s in Script.values()) {
            if (s != Script.LATIN && best != null && !nonLatinNeeded(best.second)) break
            val text = runCatching { Tasks.await(recognizer(s).process(image), timeoutSeconds, TimeUnit.SECONDS).text }
                .onFailure { lastError = it.javaClass.simpleName + (it.message?.let { m -> ": $m" } ?: "") }
                .getOrNull() ?: continue
            if (best == null || score(text) > score(best.second)) best = s to text
        }
        val b = best
        return if (b != null) Read(b.second.trim(), b.first, null) else Read(null, null, lastError ?: "no recognizer answered")
    }

    /** #1908 one recognised word with its box in the bitmap's pixels (y down): no ML Kit type leaves this object. */
    class Word(val text: String, val x: Int, val y: Int, val w: Int, val h: Int)

    /** #1908 the best script's whole read: the text and every word with its box, for a caller that lays the text over the image (a searchable PDF). */
    class WordRead(val text: String?, val words: List<Word>, val script: Script?, val error: String?)

    /** Same script policy as [read] (Latin first, the others only when the Latin read holds no letters of its own), but keeps the words. BLOCKING. */
    fun readWords(bitmap: Bitmap, timeoutSeconds: Long = 30): WordRead {
        var best: Pair<Script, Text>? = null
        var lastError: String? = null
        for (s in Script.values()) {
            if (s != Script.LATIN && best != null && !nonLatinNeeded(best.second.text)) break
            val t = text(bitmap, s, timeoutSeconds)
                .onFailure { lastError = it.javaClass.simpleName + (it.message?.let { m -> ": $m" } ?: "") }
                .getOrNull() ?: continue
            if (best == null || score(t.text) > score(best.second.text)) best = s to t
        }
        val b = best ?: return WordRead(null, emptyList(), null, lastError ?: "no recognizer answered")
        val words = b.second.textBlocks.flatMap { blk -> blk.lines.flatMap { l -> l.elements } }.mapNotNull { el ->
            el.boundingBox?.let { r -> Word(el.text, r.left, r.top, r.width(), r.height()) }
        }
        return WordRead(b.second.text.trim(), words, b.first, null)
    }
}
