// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1818 rule 16 (NEVER SIMPLIFY): the shared panel only adds. Every option, setting and action the three Writer modes had before it is still
 * wired: each previous item is listed here with the token that proves it in the sources, and the strings behind them are still declared.
 */
class WriterPanelParityTest {
    private val translateSrc = "ab_cloud-libs-shared/libs/translate/src/main/java/com/diegonmarcos/superapp/translate"
    private val kb = repoFile("ab_cloud-libs-shared/libs/keyboard/src/main/java/helium314/keyboard/latin/EnhanceBarView.kt").readText()
    private val tr = repoFile("$translateSrc/TranslateBarView.kt").readText()
    private val kbStrings = repoFile("ab_cloud-libs-shared/libs/keyboard/src/main/res/values/strings.xml").readText()
    private val trStrings = repoFile("ab_cloud-libs-shared/libs/translate/src/main/res/values/strings.xml").readText()

    private fun has(src: String, what: String, tokens: List<String>) = tokens.forEach { assertTrue("$what: lost '$it'", src.contains(it)) }

    @Test fun enhanceKeepsEveryOptionAndSetting() = has(kb, "Enhance", listOf(
        "Option(Settings.PREF_ENHANCE_STYLE, AiRouter.styles", "Option(Settings.PREF_ENHANCE_TONE", "Option(Settings.PREF_ENHANCE_LENGTH",
        "Option(Settings.PREF_ENHANCE_LANGUAGE", "AiRouter.enhanceStyle(context)", "AiTask.ENHANCE", "TextEnhancer.rewrite(",
        "PREF_ENHANCE_AUTO_PASTE", "TextEnhancer.target(", "TextEnhancer.stillThere(", "TextEnhancer.apply(", "sizeOf(", "FIELD_SETTLE_MS",
        "OptionPanel(", "MenuPanel(", "showEditMenu", "Held.SUMMARY", "AiRouter.summarise(", "AiRouter.summaryStyle(",
    ))

    @Test fun enhanceKeepsEveryAction() = has(kb, "Enhance actions", listOf(
        "WriterAction.GENERATE", "WriterAction.AUTO_REPLACE", "WriterAction.REPLACE", "WriterAction.PASTE", "WriterAction.COPY",
        "R.string.resume_bar_resume", "R.string.enhance_bar_undo", "R.string.enhance_bar_clear", "private fun undo()", "private fun clear()",
        "R.string.enhance_bar_copied",
    ))

    @Test fun draftKeepsEveryOptionAndAction() = has(kb, "Draft Answer", listOf(
        "Option(Settings.PREF_ANSWER_TONE", "Option(Settings.PREF_ANSWER_LENGTH", "Option(Settings.PREF_ANSWER_LANGUAGE",
        "AiRouter.answerStyle(context)", "DraftAnswer.MODEL_TASK", "AnswerInput()", "DraftAnswer.pickList(", "DraftAnswer.defaultInput(",
        "R.string.answer_bar_pick", "R.string.answer_bar_edit", "R.string.answer_bar_regenerate", "fun showSourcePicker()", "fun showAnswer(",
        "R.string.answer_bar_inserted", "private fun pasteAnswerInput()", "fun debugState()",
    ))

    @Test fun translateKeepsEveryOptionAndAction() = has(tr, "Translate", listOf(
        "showLangMenu(true)", "showLangMenu(false)", "private fun swap()", "TranslatePrefs.recentPairs(", "TranslatePrefs.pushRecentPair(",
        "TranslatePrefs.defaultTarget(", "TranslatePrefs.autoDetect(", "TranslatePrefs.liveCommit(", "TranslatePrefs.applyMode(",
        "TranslatePrefs.APPLY_INSERT", "TranslatePrefs.APPLY_REPLACE", "private fun pushOutput(", "private fun releaseOutput(",
        "fun onHostOutputDropped()", "R.string.translate_bar_clear", "R.string.translate_bar_copied", "private fun paste()", "private fun cutSelection()",
        "Translator.liveTranslate(", "PickerPanel(", "MenuPanel(",
    ))

    @Test fun everyPreviousStringIsStillDeclared() {
        for (k in listOf("enhance_bar_generate", "enhance_bar_copy", "enhance_bar_paste", "enhance_bar_replace", "enhance_bar_undo",
            "enhance_bar_clear", "resume_bar_resume", "answer_bar_pick", "answer_bar_edit", "answer_bar_insert", "answer_bar_regenerate",
            "answer_bar_input_clipboard", "answer_bar_input_custom", "answer_bar_input_hint", "answer_bar_title"))
            assertTrue("keyboard string $k", kbStrings.contains("name=\"$k\""))
        for (k in listOf("translate_bar_insert", "translate_bar_replace", "translate_bar_paste", "translate_bar_copy", "translate_bar_clear", "translate_bar_swap", "translate_bar_close"))
            assertTrue("translate string $k", trStrings.contains("name=\"$k\""))
    }
}
