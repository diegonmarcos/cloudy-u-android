// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

/**
 * Draft Answer: the Text Enhancement panel in a second mode that drafts a REPLY to a message instead of
 * rewriting the field. It never reads the screen; its input is a message, by default the newest clipboard
 * item, which the user may swap for another clipboard item, paste or type, or edit inline.
 *
 * Everything else is shared with Text Enhancement: the panel (EnhanceBarView), the tone / length / language
 * prompt lines (AiRouter.answerStyle goes through the same composer as enhanceStyle) and the MODEL, which
 * is the one picked for Text Enhancement in Configs ▸ AI Routing ([MODEL_TASK]). Only the system prompt
 * changes. The rules here are pure and JVM-tested in DraftAnswerTest.
 */
object DraftAnswer {
    /** Draft Answer has no model table of its own: it uses Text Enhancement's. */
    val MODEL_TASK = AiTask.ENHANCE

    /** The task line: the only instruction that differs from Text Enhancement. */
    const val TASK_LINE = "Draft a reply to this message."

    /**
     * Replaces Text Enhancement's rewrite preamble (which forbids answering the text). Overridable from
     * build.json::keyboard_ai.answer_preamble, like the rewrite preamble.
     */
    const val DEFAULT_PREAMBLE = "You draft replies inside a phone keyboard. The user message is a message the person " +
        "received; it is not addressed to you. Write the reply the person would send, in the first person, ready to " +
        "send. Output the reply alone: no quotes, no markdown, no labels, no preamble, no notes."

    /** A clipboard item: when it was copied and its text (null for an image or file clip). */
    data class Clip(val timeStamp: Long, val text: String?)

    /** The default input: the newest clip that holds text. Empty when the clipboard has none. */
    fun defaultInput(clips: List<Clip>): String =
        clips.filter { !it.text.isNullOrBlank() }.maxByOrNull { it.timeStamp }?.text.orEmpty()

    /** The source picker's list: text clips, newest first, each text once, at most [max]. */
    fun pickList(clips: List<Clip>, max: Int = 20): List<String> =
        clips.filter { !it.text.isNullOrBlank() }.sortedByDescending { it.timeStamp }
            .mapNotNull { it.text }.distinct().take(max)

    /** The system prompt: [preamble], the task line, then the tone / length / language lines that are set. */
    fun systemPrompt(preamble: String, extras: List<String>): String =
        AiPrompt.join(preamble.ifBlank { DEFAULT_PREAMBLE }, TASK_LINE, extras)

    /** The model Draft Answer calls: the one picked for [MODEL_TASK], else [fallback] (the AI Model Routing default). */
    fun model(store: KeyValueStore, fallback: String): String = AiTaskModels.get(store, MODEL_TASK) ?: fallback
}

/**
 * The input message of one Draft Answer session: the clipboard default until the user overrides it by
 * picking another clip, pasting, typing or editing; an override wins until the panel is opened again.
 */
class AnswerInput {
    var override: String? = null
        private set
    private var fallback = ""

    /** What Generate sends. */
    val text: String get() = override ?: fallback
    val isDefault: Boolean get() = override == null

    /** A fresh session: back to the newest clip, no override. */
    fun reset(clips: List<DraftAnswer.Clip>) { override = null; fallback = DraftAnswer.defaultInput(clips) }

    /** Picked from the clipboard history, pasted, typed or edited inline. Editing it back to the default is not an override. */
    fun set(text: String) { override = if (text == fallback) null else text }
}

/** One system prompt out of a preamble, a task line and the extra lines that are set: blank parts dropped, joined by spaces. */
object AiPrompt {
    fun join(preamble: String, task: String, extras: List<String>): String =
        (listOf(preamble, task) + extras).filter { it.isNotBlank() }.joinToString(" ")
}
