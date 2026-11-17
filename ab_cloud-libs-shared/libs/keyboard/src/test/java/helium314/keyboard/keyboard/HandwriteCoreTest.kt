// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import helium314.keyboard.keyboard.KeyboardViewState.Mode
import helium314.keyboard.keyboard.KeyboardViewState.PanelKind
import helium314.keyboard.keyboard.KeyboardViewState.Piece
import helium314.keyboard.keyboard.handwrite.HandwriteController
import helium314.keyboard.keyboard.handwrite.HandwriteSettings
import helium314.keyboard.keyboard.handwrite.HandwriteSink
import helium314.keyboard.keyboard.handwrite.HandwritingEngine
import helium314.keyboard.keyboard.handwrite.InkStroke
import helium314.keyboard.keyboard.handwrite.Recognition
import helium314.keyboard.keyboard.handwrite.StrokeCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1781 HandWrite: stroke capture, the candidate commit path, the panel in the view-state model. */
class HandwriteCoreTest {
    private class Sink : HandwriteSink {
        val log = ArrayList<String>()
        override fun commit(text: String) { log += "commit:$text" }
        override fun space() { log += "space" }
        override fun backspace() { log += "backspace" }
    }

    private class FakeEngine(var reply: Recognition) : HandwritingEngine {
        var calls = 0; var lastStrokes = 0; var lastLang = ""
        override fun isModelReady(languageTag: String, onResult: (Boolean) -> Unit) = onResult(true)
        override fun download(languageTag: String, onResult: (Boolean, String) -> Unit) = onResult(true, "ok")
        override fun delete(languageTag: String, onResult: (Boolean) -> Unit) = onResult(true)
        override fun recognize(strokes: List<InkStroke>, languageTag: String, preContext: String, onResult: (Recognition) -> Unit) {
            calls++; lastStrokes = strokes.size; lastLang = languageTag; onResult(reply)
        }
    }

    private fun draw(c: StrokeCapture, vararg pts: Pair<Float, Float>) {
        pts.forEachIndexed { i, (x, y) ->
            when (i) { 0 -> c.down(x, y, 10L * i); pts.size - 1 -> c.up(x, y, 10L * i); else -> c.move(x, y, 10L * i) }
        }
    }

    @Test fun strokesAreCapturedPointByPointAndOnePerPenDown() {
        val c = StrokeCapture()
        draw(c, 0f to 0f, 1f to 2f, 3f to 4f)
        draw(c, 5f to 5f, 6f to 6f)
        assertEquals(2, c.strokes.size)
        assertEquals(listOf(0f to 0f, 1f to 2f, 3f to 4f), c.strokes[0].points.map { it.x to it.y })
        assertEquals(listOf(0L, 10L, 20L), c.strokes[0].points.map { it.t })
        assertFalse(c.isDrawing)
        c.undoLast(); assertEquals(1, c.strokes.size)
        c.clear(); assertTrue(c.isEmpty)
    }

    @Test fun aTappedCandidateIsCommittedAndThePadIsEmptied() {
        val sink = Sink(); val engine = FakeEngine(Recognition.Candidates(listOf("hello", "hallo")))
        val c = HandwriteController(StrokeCapture(), { engine }, { "en-US" }, { "" }, sink)
        draw(c.capture, 0f to 0f, 9f to 9f); c.strokeEnded()
        assertEquals(listOf("hello", "hallo"), c.candidates)
        assertEquals("en-US", engine.lastLang)
        c.commitCandidate(1)
        assertEquals(listOf("commit:hallo"), sink.log)
        assertTrue(c.capture.isEmpty); assertTrue(c.candidates.isEmpty())
    }

    @Test fun autoCommitTakesTheFirstCandidateAndDoesNothingWithoutOne() {
        val sink = Sink(); val engine = FakeEngine(Recognition.Candidates(listOf("cat", "cot")))
        val c = HandwriteController(StrokeCapture(), { engine }, { "en-US" }, { "" }, sink)
        c.autoCommit(); assertTrue(sink.log.isEmpty())
        draw(c.capture, 0f to 0f, 5f to 5f); c.strokeEnded(); c.autoCommit()
        assertEquals(listOf("commit:cat"), sink.log)
    }

    @Test fun spaceCommitsPendingInkFirstAndBackspaceUndoesInkBeforeItDeletes() {
        val sink = Sink(); val engine = FakeEngine(Recognition.Candidates(listOf("hi")))
        val c = HandwriteController(StrokeCapture(), { engine }, { "en-US" }, { "" }, sink)
        draw(c.capture, 0f to 0f, 5f to 5f); c.strokeEnded()
        c.backspace()                           // ink on the pad: it takes the last stroke away, the field is not touched
        assertTrue(sink.log.isEmpty()); assertTrue(c.capture.isEmpty)
        c.backspace(); assertEquals(listOf("backspace"), sink.log)   // empty pad: a real delete
        draw(c.capture, 0f to 0f, 5f to 5f); c.strokeEnded(); c.space()
        assertEquals(listOf("backspace", "commit:hi", "space"), sink.log)
    }

    @Test fun aMissingModelAndAMissingEngineAreStatesNotCrashes() {
        val c = HandwriteController(StrokeCapture(), { FakeEngine(Recognition.NoModel) }, { "ja-JP" }, { "" }, Sink())
        draw(c.capture, 0f to 0f, 5f to 5f); c.strokeEnded()
        assertTrue(c.recognition is Recognition.NoModel); assertTrue(c.candidates.isEmpty())
        val none = HandwriteController(StrokeCapture(), { null }, { "en-US" }, { "" }, Sink())
        draw(none.capture, 0f to 0f, 5f to 5f); none.strokeEnded()
        assertTrue(none.recognition is Recognition.NoEngine)
    }

    @Test fun settingsPickTheActiveLanguageAndClampTheNumbers() {
        assertEquals(listOf("en-US"), HandwriteSettings.languages(null))
        assertEquals(listOf("fr-FR", "de-DE"), HandwriteSettings.languages("fr-FR, de-DE,fr-FR"))
        assertEquals("de-DE", HandwriteSettings.active("fr-FR,de-DE", "de-DE"))
        assertEquals("fr-FR", HandwriteSettings.active("fr-FR,de-DE", "ja-JP")) // a language switched off falls back to the first one
        assertEquals("fr-FR", HandwriteSettings.next("fr-FR,de-DE", "de-DE"))
        assertEquals(200, HandwriteSettings.autoCommitMs(5)); assertEquals(10000, HandwriteSettings.autoCommitMs(99999))
        assertEquals(1, HandwriteSettings.strokeDp(0)); assertEquals(12, HandwriteSettings.strokeDp(40))
    }

    @Test fun theHandWritePanelOwnsItsViewAndExitsThroughItsBottomRow() {
        val inputs = KeyboardViewState.Inputs(stripAvailable = true, secondaryStripVisible = true, imeSuppressed = false, showToolbarOnly = false)
        val plan = KeyboardViewState.plan(Mode.Panel(PanelKind.HANDWRITE), false, inputs)
        assertTrue(plan.shows(Piece.HANDWRITE_VIEW))
        assertFalse(plan.shows(Piece.MAIN_KEYBOARD)); assertFalse(plan.shows(Piece.AUTOCOMPLETE_VIEW)); assertFalse(plan.shows(Piece.CLIPBOARD_HISTORY))
        assertTrue(KeyboardViewState.Exit.BOTTOM_ROW_ALPHA in plan.exits)
        for (kind in listOf(PanelKind.CLIPBOARD, PanelKind.AUTOCOMPLETE, PanelKind.EMOJI))
            assertFalse(KeyboardViewState.plan(Mode.Panel(kind), false, inputs).shows(Piece.HANDWRITE_VIEW))
        assertEquals(Mode.Typing, KeyboardViewState().also { it.openPanel(PanelKind.HANDWRITE) }.toggleTarget(PanelKind.HANDWRITE))
    }
}
