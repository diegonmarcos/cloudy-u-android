package com.diegonmarcos.superapp.mlocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
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
}
