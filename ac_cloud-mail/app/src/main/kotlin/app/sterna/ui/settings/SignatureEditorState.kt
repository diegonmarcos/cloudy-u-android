package app.sterna.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.sterna.ui.settings.SignatureHtmlTools.Edit

/**
 * Everything the full-screen signature editor edits, apart from what the screen merely shows.
 *
 * The SOURCE is the single model (see [SignatureListEditor]): the toolbar, the visual pane and the
 * import/reset actions all produce a new source, each as one step of [history]. [baseline] is what is
 * stored, so [dirty] is a comparison and not a flag that can drift.
 */
internal class SignatureEditorState(
    val baselineSource: String,
    val baselineName: String,
    val baselineDefault: Boolean,
    source: String = baselineSource,
    selection: TextRange = TextRange(source.length),
    name: String = baselineName,
    makeDefault: Boolean = baselineDefault,
) {
    var textField by mutableStateOf(TextFieldValue(source, selection))
        private set
    var name by mutableStateOf(name)
    var makeDefault by mutableStateOf(makeDefault)

    private val history = SignatureHtmlTools.History(Edit(source, selection.min, selection.max))
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    val source: String get() = textField.text
    val dirty: Boolean get() = source != baselineSource || name != baselineName || makeDefault != baselineDefault

    private fun show(edit: Edit) {
        textField = TextFieldValue(edit.text, TextRange(edit.start.coerceAtMost(edit.text.length), edit.end.coerceAtMost(edit.text.length)))
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    /** The field as the keyboard changed it: typing assists apply, a burst of typing is one undo step. */
    fun typed(incoming: TextFieldValue, clip: (() -> SignatureClip)? = null) {
        val v = if (clip != null) substitutePaste(textField, incoming, clip) else incoming
        if (v.text == textField.text) {
            textField = v
            return
        }
        val before = Edit(textField.text, textField.selection.min, textField.selection.max)
        val raw = Edit(v.text, v.selection.min, v.selection.max)
        val assisted = SignatureEditing.afterTyping(before, raw)
        history.record(assisted ?: raw, merge = assisted == null)
        show(assisted ?: raw)
    }

    /** A toolbar action is a step of its own; null (unusable input) changes nothing and reports false. */
    fun commit(edit: Edit?): Boolean {
        if (edit == null) return false
        history.record(edit)
        show(edit)
        return true
    }

    /** Tab / Shift+Tab. */
    fun tab(outdent: Boolean) {
        commit(SignatureEditing.tab(textField.text, textField.selection.start, textField.selection.end, outdent))
    }

    /** Import, reset, reload: the whole source replaced, as one undoable step. */
    fun replaceAll(text: String) {
        commit(Edit(text, text.length, text.length))
    }

    /** What the visual pane reported. A burst of edits there is one step, like typing. */
    fun fromVisual(text: String) {
        if (text == textField.text) return
        val edit = Edit(text, text.length, text.length)
        history.record(edit, merge = true)
        show(edit)
    }

    fun select(start: Int, end: Int) {
        val s = start.coerceIn(0, source.length)
        val e = end.coerceIn(0, source.length)
        textField = textField.copy(selection = TextRange(s, e))
    }

    fun undo() {
        history.undo()?.let(::show)
    }

    fun redo() {
        history.redo()?.let(::show)
    }

    companion object {
        /** Survives rotation and process death; the undo history is rebuilt from the saved text. */
        fun saver(baselineSource: String, baselineName: String, baselineDefault: Boolean) = listSaver<SignatureEditorState, Any>(
            save = { listOf(it.source, it.textField.selection.start, it.textField.selection.end, it.name, it.makeDefault) },
            restore = {
                SignatureEditorState(
                    baselineSource, baselineName, baselineDefault,
                    source = it[0] as String,
                    selection = TextRange(it[1] as Int, it[2] as Int),
                    name = it[3] as String,
                    makeDefault = it[4] as Boolean,
                )
            },
        )
    }
}
