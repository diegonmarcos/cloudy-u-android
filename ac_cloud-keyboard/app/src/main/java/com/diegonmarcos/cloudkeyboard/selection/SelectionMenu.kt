package com.diegonmarcos.cloudkeyboard.selection

import com.diegonmarcos.cloudkeyboard.grabber.ProcessTextReply
import com.diegonmarcos.superapp.translate.WriterMode

/**
 * #1824 the text-selection menu entries of Cloud Keyboard: Translator, Grammar, Enhancer and Drafter. Each is its own activity
 * (own label, own PROCESS_TEXT filter) that opens the #1818 shared writer panel in one mode over the app the text was selected in.
 * No Android class here: the routing of an entry to its mode and the replace-in-place rule are plain JVM tests (SelectionMenuTest).
 */
object SelectionMenu {
    /** The entry's activity class (simple name), the mode it opens and its label as the manifest declares it (EN; ES in values-es). */
    data class Entry(val activity: String, val mode: WriterMode, val label: String)

    /** The four Cloud Keyboard entries, in the order of the owner's list. */
    val ENTRIES: List<Entry> = listOf(
        Entry("SelectionTranslatorActivity", WriterMode.TRANSLATE, "Translator"),
        Entry("SelectionGrammarActivity", WriterMode.GRAMMAR, "Grammar"),
        Entry("SelectionEnhancerActivity", WriterMode.ENHANCE, "Enhancer"),
        Entry("SelectionDrafterActivity", WriterMode.DRAFT, "Drafter"),
    )

    /** The mode an activity (simple or full class name) opens; null for any other class. */
    fun modeOf(activityClass: String?): WriterMode? =
        ENTRIES.firstOrNull { activityClass != null && (activityClass == it.activity || activityClass.endsWith("." + it.activity)) }?.mode

    /**
     * Replace-in-place: the result goes back to the app (RESULT_OK + EXTRA_PROCESS_TEXT, which replaces the selection) only when the
     * source is editable ([readOnly] false) and there is a result; a read-only source gets nothing back and the result is copied or shared.
     */
    fun reply(readOnly: Boolean, result: String?): ProcessTextReply =
        if (!readOnly && !result.isNullOrBlank()) ProcessTextReply(ProcessTextReply.RESULT_OK, result) else ProcessTextReply.cancelled()
}
