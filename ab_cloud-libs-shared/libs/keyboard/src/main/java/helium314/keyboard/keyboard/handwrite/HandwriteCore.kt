// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.handwrite

/**
 * #1781 HandWrite: the engine-free core of the handwriting panel (pure Kotlin, JVM-testable). The panel captures strokes,
 * hands them to a [HandwritingEngine] (Cloud Keyboard registers the ML Kit Digital Ink one; libs:keyboard stays free of the
 * proprietary SDK) and commits a candidate to the field through a [Sink].
 */
class InkPoint(val x: Float, val y: Float, val t: Long)

class InkStroke { val points = ArrayList<InkPoint>() }

/** Finger / stylus strokes as they are drawn: down starts a stroke, move and up extend it. */
class StrokeCapture {
    val strokes = ArrayList<InkStroke>()
    private var current: InkStroke? = null

    val isEmpty get() = strokes.isEmpty()
    val isDrawing get() = current != null

    fun down(x: Float, y: Float, t: Long) {
        val s = InkStroke().also { it.points.add(InkPoint(x, y, t)) }
        strokes.add(s); current = s
    }

    fun move(x: Float, y: Float, t: Long) { current?.points?.add(InkPoint(x, y, t)) }

    fun up(x: Float, y: Float, t: Long) { move(x, y, t); current = null }

    fun undoLast() { if (current == null && strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1) }

    fun clear() { strokes.clear(); current = null }
}

sealed class Recognition {
    class Candidates(val texts: List<String>) : Recognition()
    /** The language's model is not on the device: the panel shows the prompt to download it. */
    object NoModel : Recognition()
    object NoEngine : Recognition()
    class Failed(val why: String) : Recognition()
}

interface HandwritingEngine {
    fun isModelReady(languageTag: String, onResult: (Boolean) -> Unit)
    /** Downloads the language's model (the only network use of HandWrite); [onResult] gets success and a short reason. Main thread. */
    fun download(languageTag: String, onResult: (Boolean, String) -> Unit)
    fun delete(languageTag: String, onResult: (Boolean) -> Unit)
    /** [preContext] is the text before the cursor (it improves the ranking). [onResult] is called once, on the main thread. */
    fun recognize(strokes: List<InkStroke>, languageTag: String, preContext: String, onResult: (Recognition) -> Unit)
}

object Handwriting {
    @Volatile var engine: HandwritingEngine? = null
}

/** What the panel's buttons do to the text field. */
interface HandwriteSink {
    fun commit(text: String)
    fun space()
    fun backspace()
}

/** The candidate and commit logic of the panel, without any View. */
class HandwriteController(
    val capture: StrokeCapture,
    private val engine: () -> HandwritingEngine?,
    private val language: () -> String,
    private val preContext: () -> String,
    private val sink: HandwriteSink,
    private val onChange: () -> Unit = {},
) {
    var recognition: Recognition? = null
        private set
    private var generation = 0

    val candidates: List<String> get() = (recognition as? Recognition.Candidates)?.texts ?: emptyList()

    /** A stroke was finished: recognise everything written so far. */
    fun strokeEnded() = recognise()

    private fun recognise() {
        val gen = ++generation
        if (capture.isEmpty) { recognition = null; onChange(); return }
        val e = engine()
        if (e == null) { recognition = Recognition.NoEngine; onChange(); return }
        e.recognize(capture.strokes.toList(), language(), preContext()) { r ->
            if (gen == generation) { recognition = r; onChange() }
        }
    }

    /** A candidate chip was tapped: its text goes to the field and the pad is emptied. */
    fun commitCandidate(index: Int) {
        val text = candidates.getOrNull(index) ?: return
        sink.commit(text)
        reset()
    }

    /** The auto-commit delay ran out after the last stroke: the first candidate is committed. */
    fun autoCommit() { if (candidates.isNotEmpty()) commitCandidate(0) }

    fun space() { autoCommit(); sink.space() }

    /** Backspace removes the last stroke while there is ink; with an empty pad it deletes in the field. */
    fun backspace() {
        if (capture.isEmpty) { sink.backspace(); return }
        capture.undoLast()
        recognise()
    }

    fun reset() { generation++; capture.clear(); recognition = null; onChange() }
}

/** The handwriting settings the HandWrite tab edits and the panel reads (the keyboard's default prefs, declared in the fleet config manifest). */
object HandwriteSettings {
    const val PREF_LANGUAGES = "handwrite_languages"
    const val PREF_ACTIVE_LANGUAGE = "handwrite_active_language"
    const val PREF_AUTOCOMMIT_MS = "handwrite_autocommit_ms"
    const val PREF_STROKE_DP = "handwrite_stroke_width_dp"
    const val DEFAULT_LANGUAGE = "en-US"
    const val DEFAULT_AUTOCOMMIT_MS = 900
    const val DEFAULT_STROKE_DP = 4

    fun languages(csv: String?): List<String> =
        csv?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.ifEmpty { null } ?: listOf(DEFAULT_LANGUAGE)

    /** The language the pad recognises: the remembered one when still enabled, else the first enabled. */
    fun active(csv: String?, remembered: String?): String =
        languages(csv).let { l -> if (remembered != null && remembered in l) remembered else l.first() }

    fun next(csv: String?, current: String): String =
        languages(csv).let { l -> l[(l.indexOf(current) + 1) % l.size] }

    fun autoCommitMs(v: Int) = v.coerceIn(200, 10000)
    fun strokeDp(v: Int) = v.coerceIn(1, 12)
}
