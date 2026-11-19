package com.diegonmarcos.superapp.translate

/**
 * #1818 the ONE behaviour of the three Writer panels in the keyboard (Translate, Enhance, Draft Answer).
 *
 * Every mode has an INPUT box and an OUTPUT box, three input sources (its own box, the Clipboard, the host
 * app's field = the "App box") and the actions Generate, Auto Replace, Replace, Paste, Copy. A mode only
 * supplies its [Generator] (the model / engine call) and its config selectors; everything else is here, once.
 * Who owns the keystrokes ([KeyFocus]) is part of it, in both directions, for every mode.
 *
 * No android.* import on purpose: this is the part whose correctness is executed on the JVM (WriterPanelTest).
 */
enum class WriterMode(val id: String) { TRANSLATE("translate"), ENHANCE("enhance"), DRAFT("draft") }

/** Where the text of the INPUT box comes from. */
enum class InputSource(val id: String) { OWN("own"), CLIPBOARD("clipboard"), APP_BOX("app_box") }

/** The five actions of the panel, in the order the shared panel draws them. */
enum class WriterAction(val id: String) {
    GENERATE("generate"), AUTO_REPLACE("auto_replace"), REPLACE("replace"), PASTE("paste"), COPY("copy")
}

/** Who gets the keys: the host app's field, the panel's input box or its output box. */
enum class KeyOwner { APP, INPUT, OUTPUT }

/** What an action did, so the host view can say why nothing happened. */
enum class Outcome { OK, EMPTY_INPUT, EMPTY_OUTPUT, NO_FIELD, NOT_APPLICABLE, FAILED }

/**
 * Key ownership, both directions, on top of [KeyHandoff] (which tells a host tap from the panel's own write).
 * Tapping the panel's input box -> [inputTapped]; its output box -> [outputTapped]; tapping into the host app's
 * field arrives as a selection change the panel did not cause -> [onHostSelectionChange]. [resting] is where a
 * fresh session starts: Translate holds the keys from the start, Enhance and Draft Answer leave them with the app.
 */
class KeyFocus(private val resting: KeyOwner = KeyOwner.INPUT) {
    private val handoff = KeyHandoff()
    private var box = KeyOwner.INPUT

    init { reset() }

    val owner: KeyOwner get() = if (handoff.heldByBox) box else KeyOwner.APP
    val consumesKeys: Boolean get() = owner != KeyOwner.APP

    fun reset() {
        handoff.reset()
        box = if (resting == KeyOwner.OUTPUT) KeyOwner.OUTPUT else KeyOwner.INPUT
        if (resting == KeyOwner.APP) handoff.release()
    }

    fun inputTapped() { box = KeyOwner.INPUT; handoff.reclaim() }
    fun outputTapped() { box = KeyOwner.OUTPUT; handoff.reclaim() }
    /** The user (or an action that finished in the app) gives the keys to the host field. */
    fun appTapped() = handoff.release()
    /** Announce a write to the host field BEFORE making it, so its echo is not read as a host tap. */
    fun onSelfWrite() = handoff.onSelfWrite()
    fun cancelSelfWrite() = handoff.cancelSelfWrite()
    /** The host reported a selection change with no composing region; true when the host caused it (the keys went back to the app). */
    fun onHostSelectionChange(): Boolean = handoff.onSelectionChange()
}

/** A text box of the panel (its editor), as the controller sees it. */
interface BoxText {
    fun get(): String
    fun set(text: String)
}

/** The host application's field: the "App box". */
interface AppField {
    /** What the App box holds for the panel: the selection if there is one, else the whole field; null with no field. */
    fun read(): String?
    /** Replace the selection, or the whole field when nothing is selected, with [text]. False when there is no field. */
    fun replace(text: String): Boolean
    /** Insert [text] at the cursor (over the selection). False when there is no field. */
    fun insert(text: String): Boolean
}

/** The system clipboard as text. */
interface ClipPort {
    fun read(): String?
    fun write(text: String)
}

/** One mode's model / engine call. [done] may be called later and from the host's main thread. */
fun interface Generator {
    fun generate(input: String, done: (Result<String>) -> Unit)
}

/**
 * The shared panel logic. A view creates one per mode, hands in its boxes, the field, the clipboard and its
 * [Generator], and forwards taps to it. [lastError] holds the reason of the last [Outcome.FAILED].
 */
class WriterPanelController(
    val mode: WriterMode,
    private val app: AppField,
    private val clip: ClipPort,
    private val input: BoxText,
    private val output: BoxText,
    val focus: KeyFocus,
    private val generator: Generator,
    private val initialSource: InputSource = InputSource.OWN,
) {
    var source: InputSource = initialSource
        private set
    var lastError: Throwable? = null
        private set
    /** False while the output may not be written into the app (Enhance's Resume summary). */
    var outputApplicable: Boolean = true

    fun reset() { focus.reset(); source = initialSource; outputApplicable = true; lastError = null }

    /** Taking an input source: the Clipboard and the App box fill the input box with their text. */
    fun selectSource(s: InputSource): Outcome {
        source = s
        return when (s) {
            InputSource.OWN -> { focus.inputTapped(); Outcome.OK }
            InputSource.CLIPBOARD -> clip.read().orEmpty().let { if (it.isBlank()) Outcome.EMPTY_INPUT else { input.set(it); Outcome.OK } }
            InputSource.APP_BOX -> {
                val text = app.read() ?: return Outcome.NO_FIELD
                if (text.isBlank()) Outcome.EMPTY_INPUT else { input.set(text); Outcome.OK }
            }
        }
    }

    /** The user tapped the panel's own input box: typing goes into it, and the source is theirs. */
    fun onInputBoxTapped() { focus.inputTapped(); source = InputSource.OWN }
    fun onOutputBoxTapped() { focus.outputTapped() }
    /** The user typed into the input box. */
    fun onInputEdited() { source = InputSource.OWN }

    /** Generate into the output box. With the App box as source the field is read again first. */
    fun generate(done: (Outcome) -> Unit) {
        if (source == InputSource.APP_BOX) app.read()?.takeIf { it.isNotBlank() }?.let { input.set(it) }
        val text = input.get()
        if (text.isBlank()) { done(Outcome.EMPTY_INPUT); return }
        lastError = null
        generator.generate(text) { r ->
            r.onSuccess { output.set(it); done(Outcome.OK) }
                .onFailure { lastError = it; done(Outcome.FAILED) }
        }
    }

    /** Replace: the App box's text (or selection) becomes the output. */
    fun replace(): Outcome {
        val out = output.get()
        if (out.isBlank()) return Outcome.EMPTY_OUTPUT
        if (!outputApplicable) return Outcome.NOT_APPLICABLE
        focus.onSelfWrite()
        return if (app.replace(out)) Outcome.OK else { focus.cancelSelfWrite(); Outcome.NO_FIELD }
    }

    /** Paste: the output is inserted at the app's cursor. */
    fun paste(): Outcome {
        val out = output.get()
        if (out.isBlank()) return Outcome.EMPTY_OUTPUT
        if (!outputApplicable) return Outcome.NOT_APPLICABLE
        focus.onSelfWrite()
        return if (app.insert(out)) Outcome.OK else { focus.cancelSelfWrite(); Outcome.NO_FIELD }
    }

    /** Copy: the output goes on the clipboard. */
    fun copy(): Outcome {
        val out = output.get()
        if (out.isBlank()) return Outcome.EMPTY_OUTPUT
        clip.write(out)
        return Outcome.OK
    }

    /** Auto Replace: generate, then immediately replace the App box with the output, in one tap. */
    fun autoReplace(done: (Outcome) -> Unit) = generate { o -> done(if (o == Outcome.OK) replace() else o) }
}

/** The text operations the App box needs from an InputConnection, so the logic runs without one. */
interface FieldOps {
    fun selectedText(): String?
    /** The whole field (extracted text, else before + after the cursor); null with no field. */
    fun allText(): String?
    fun selectAll()
    fun commit(text: String)
    fun beginBatch()
    fun endBatch()
}

/** [AppField] over [FieldOps]; [ops] is asked per call, so a field that went away reads as "no field". */
class FieldOpsAppField(private val ops: () -> FieldOps?) : AppField {
    override fun read(): String? {
        val o = ops() ?: return null
        return o.selectedText()?.takeIf { it.isNotEmpty() } ?: o.allText()
    }

    override fun replace(text: String): Boolean {
        val o = ops() ?: return false
        val hadSelection = !o.selectedText().isNullOrEmpty()
        o.beginBatch()
        if (!hadSelection) o.selectAll()
        o.commit(text)
        o.endBatch()
        return true
    }

    override fun insert(text: String): Boolean {
        val o = ops() ?: return false
        o.commit(text)
        return true
    }
}

/** The declared shape of the shared panel: what a view of any mode must show, in this order. */
object WriterPanelLayout {
    val ACTION_ORDER: List<WriterAction> = listOf(
        WriterAction.GENERATE, WriterAction.AUTO_REPLACE, WriterAction.REPLACE, WriterAction.PASTE, WriterAction.COPY,
    )
    val SOURCE_ORDER: List<InputSource> = listOf(InputSource.OWN, InputSource.CLIPBOARD, InputSource.APP_BOX)
    /** Both boxes are drawn inside a visible 1dp frame. */
    const val FRAMED = true
}
