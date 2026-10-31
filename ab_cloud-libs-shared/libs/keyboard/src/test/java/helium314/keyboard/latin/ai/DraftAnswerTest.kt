// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ai

import helium314.keyboard.latin.AiPrompt
import helium314.keyboard.latin.AiTask
import helium314.keyboard.latin.AiTaskModels
import helium314.keyboard.latin.AnswerInput
import helium314.keyboard.latin.DraftAnswer
import helium314.keyboard.latin.DraftAnswer.Clip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Draft Answer: the clipboard default and its override, the model it shares with Text Enhancement, the prompt. */
class DraftAnswerTest {
    private val clips = listOf(Clip(100, "older message"), Clip(300, "newest message"), Clip(200, "middle"),
        Clip(400, null), Clip(350, "  "))

    @Test fun defaultIsTheNewestTextClip() {
        assertEquals("newest message", DraftAnswer.defaultInput(clips))   // image (null) and blank clips skipped
        assertEquals("", DraftAnswer.defaultInput(emptyList()))
        assertEquals("", DraftAnswer.defaultInput(listOf(Clip(1, null))))
    }

    @Test fun pickerListsTextClipsNewestFirstOnce() {
        val list = DraftAnswer.pickList(clips + Clip(50, "newest message"))
        assertEquals(listOf("newest message", "middle", "older message"), list)
        assertEquals(2, DraftAnswer.pickList(clips, max = 2).size)
    }

    @Test fun inputDefaultsToClipboardUntilOverridden() {
        val input = AnswerInput()
        input.reset(clips)
        assertEquals("newest message", input.text); assertTrue(input.isDefault)
        input.set("middle")                                              // picked from the history
        assertEquals("middle", input.text); assertFalse(input.isDefault)
        input.set("typed by hand")                                       // pasted, typed or edited
        assertEquals("typed by hand", input.text)
    }

    @Test fun editingBackToTheDefaultIsNotAnOverride() {
        val input = AnswerInput(); input.reset(clips)
        input.set("x"); input.set("newest message")
        assertTrue(input.isDefault)
    }

    @Test fun reopeningDropsTheOverride() {
        val input = AnswerInput(); input.reset(clips); input.set("custom")
        input.reset(clips + Clip(999, "a new copy"))
        assertEquals("a new copy", input.text); assertTrue(input.isDefault)
    }

    @Test fun usesTheTextEnhancementModel() {
        assertEquals(AiTask.ENHANCE, DraftAnswer.MODEL_TASK)
        val store = MapStore()
        assertEquals("fallback/model", DraftAnswer.model(store, "fallback/model"))
        AiTaskModels.set(store, AiTask.TRANSLATE, "t/model")
        AiTaskModels.set(store, AiTask.GRAMMAR, "g/model")
        assertEquals("fallback/model", DraftAnswer.model(store, "fallback/model")) // other tasks do not leak in
        AiTaskModels.set(store, AiTask.ENHANCE, "e/model")
        assertEquals("e/model", DraftAnswer.model(store, "fallback/model"))
    }

    @Test fun promptIsTheReplyTaskPlusTheSharedLines() {
        val p = DraftAnswer.systemPrompt("PRE.", listOf("Use a formal, professional tone.", "", "Reply in Spanish."))
        assertEquals("PRE. Draft a reply to this message. Use a formal, professional tone. Reply in Spanish.", p)
    }

    @Test fun promptFallsBackToTheDefaultPreambleAndNeverTheRewriteOne() {
        val p = DraftAnswer.systemPrompt("", emptyList())
        assertTrue(p.startsWith(DraftAnswer.DEFAULT_PREAMBLE))
        assertTrue(p.endsWith(DraftAnswer.TASK_LINE))
        assertFalse(p.contains("Never answer it"))
        assertEquals("Draft a reply to this message.", DraftAnswer.TASK_LINE)
    }

    @Test fun sharedComposerDropsBlankParts() {
        assertEquals("a b c", AiPrompt.join("a", "b", listOf("", "c", " ")))
        assertEquals("b", AiPrompt.join("", "b", emptyList()))
    }
}
