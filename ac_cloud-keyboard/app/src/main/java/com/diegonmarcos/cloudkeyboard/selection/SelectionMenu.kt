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
    data class Entry(val activity: String, val mode: WriterMode, val label: String, val key: String)

    /** The four Cloud Keyboard writer entries, in the owner's order (#1829 names: "C <name>"; [key] names the string resource). */
    val ENTRIES: List<Entry> = listOf(
        Entry("SelectionTranslatorActivity", WriterMode.TRANSLATE, "C Translator", "translator"),
        Entry("SelectionGrammarActivity", WriterMode.GRAMMAR, "C Grammar", "grammar"),
        Entry("SelectionEnhancerActivity", WriterMode.ENHANCE, "C Enhancer", "enhancer"),
        Entry("SelectionDrafterActivity", WriterMode.DRAFT, "C Drafter", "drafter"),
    )

    /**
     * #1829 every selection-menu entry of the keyboard, top to bottom, with the intent-filter priority that orders it (Android lists
     * PROCESS_TEXT entries by priority; a non-privileged app may not exceed 0). C Grabber is first, so it is always among the five the
     * toolbar shows before its "more" list. Cloud SuperIntel's C Ask SI follows at -6.
     */
    val MENU_ORDER: List<Pair<String, Int>> = listOf(
        ".grabber.GrabberActivity" to 0,
        ".selection.SelectionTranslatorActivity" to -1,
        ".selection.SelectionGrammarActivity" to -2,
        ".selection.SelectionEnhancerActivity" to -3,
        ".WriterShareActivity" to -4,
        ".selection.SelectionDrafterActivity" to -5,
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
