// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import com.diegonmarcos.superapp.translate.AppField
import com.diegonmarcos.superapp.translate.BoxText
import com.diegonmarcos.superapp.translate.ClipPort
import com.diegonmarcos.superapp.translate.FieldOps
import com.diegonmarcos.superapp.translate.FieldOpsAppField
import com.diegonmarcos.superapp.translate.Generator
import com.diegonmarcos.superapp.translate.InputSource
import com.diegonmarcos.superapp.translate.KeyFocus
import com.diegonmarcos.superapp.translate.KeyOwner
import com.diegonmarcos.superapp.translate.Outcome
import com.diegonmarcos.superapp.translate.WriterMode
import com.diegonmarcos.superapp.translate.WriterPanelController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

internal fun repoFile(rel: String): File = generateSequence(File("").absoluteFile) { it.parentFile }
    .map { File(it, rel) }.first { it.isFile }

/** A host app's text field behind a fake InputConnection: text, a selection, commit over the selection, select-all. */
class FakeField(var text: String, var selStart: Int = text.length, var selEnd: Int = text.length) : FieldOps {
    var batches = 0
    override fun selectedText(): String? = if (selStart == selEnd) null else text.substring(minOf(selStart, selEnd), maxOf(selStart, selEnd))
    override fun allText(): String? = text
    override fun selectAll() { selStart = 0; selEnd = text.length }
    override fun commit(text: String) {
        val lo = minOf(selStart, selEnd); val hi = maxOf(selStart, selEnd)
        this.text = this.text.substring(0, lo) + text + this.text.substring(hi)
        selStart = lo + text.length; selEnd = selStart
    }
    override fun beginBatch() { batches++ }
    override fun endBatch() {}
}

class FakeClip(var text: String? = null) : ClipPort {
    override fun read(): String? = text
    override fun write(text: String) { this.text = text }
}

class Box(var value: String = "") : BoxText {
    override fun get() = value
    override fun set(text: String) { value = text }
}

/** #1818 the shared Writer panel logic, for each of the three modes, on the JVM. */
class WriterPanelTest {
    private class Rig(val mode: WriterMode, val field: FakeField? = FakeField("hello world"), clipText: String? = "from clipboard",
                       val reply: (String) -> Result<String> = { Result.success("<" + it + ">") }) {
        val clip = FakeClip(clipText)
        val input = Box()
        val output = Box()
        val resting = if (mode == WriterMode.TRANSLATE) KeyOwner.INPUT else KeyOwner.APP
        val focus = KeyFocus(resting)
        val app: AppField = FieldOpsAppField { field }
        val initial = when (mode) { WriterMode.ENHANCE -> InputSource.APP_BOX; WriterMode.DRAFT -> InputSource.CLIPBOARD; else -> InputSource.OWN }
        val calls = ArrayList<String>()
        val ctl = WriterPanelController(mode, app, clip, input, output, focus, Generator { text, done -> calls += text; done(reply(text)) }, initial)
    }

    private val modes = WriterMode.values().toList()

    @Test fun threeModesExist() = assertEquals(listOf("translate", "enhance", "draft"), modes.map { it.id })

    // ── a) focus hand-over, both directions, all three modes ─────────────────────────────────────────────────
    @Test fun tappingTheOwnInputBoxHandsTheKeysToItAndTappingTheAppHandsThemBack() {
        for (m in modes) {
            val r = Rig(m)
            r.ctl.onInputBoxTapped()
            assertEquals("$m: input box owns the keys after its tap", KeyOwner.INPUT, r.focus.owner)
            assertTrue(r.focus.consumesKeys)
            // the user taps into the app's text field: a selection change the panel did not cause
            assertTrue("$m: a host tap is seen as one", r.focus.onHostSelectionChange())
            assertEquals("$m: the app has the keys back", KeyOwner.APP, r.focus.owner)
            assertFalse(r.focus.consumesKeys)
            // and the box takes them again
            r.ctl.onInputBoxTapped()
            assertEquals(KeyOwner.INPUT, r.focus.owner)
        }
    }

    @Test fun theOutputBoxOwnsTheKeysToo() {
        for (m in modes) {
            val r = Rig(m)
            r.ctl.onOutputBoxTapped()
            assertEquals(KeyOwner.OUTPUT, r.focus.owner)
            r.focus.onHostSelectionChange()
            assertEquals(KeyOwner.APP, r.focus.owner)
        }
    }

    @Test fun thePanelsOwnWriteIsNotMistakenForAHostTap() {
        for (m in modes) {
            val r = Rig(m)
            r.input.value = "x"; r.output.value = "y"
            r.ctl.onInputBoxTapped()
            assertEquals(Outcome.OK, r.ctl.paste())          // announces its write, then writes
            assertFalse("$m: the echo of the panel's own write is not a host tap", r.focus.onHostSelectionChange())
            assertEquals(KeyOwner.INPUT, r.focus.owner)
            assertTrue("$m: the next real tap is", r.focus.onHostSelectionChange())
            assertEquals(KeyOwner.APP, r.focus.owner)
        }
    }

    @Test fun aFreshSessionStartsWhereTheModeRests() {
        assertEquals(KeyOwner.INPUT, Rig(WriterMode.TRANSLATE).focus.owner)
        assertEquals(KeyOwner.APP, Rig(WriterMode.ENHANCE).focus.owner)
        assertEquals(KeyOwner.APP, Rig(WriterMode.DRAFT).focus.owner)
        val r = Rig(WriterMode.DRAFT); r.ctl.onInputBoxTapped(); r.ctl.reset()
        assertEquals(KeyOwner.APP, r.focus.owner)
    }

    // ── b) the three input sources feed the input box ────────────────────────────────────────────────────────
    @Test fun typingIntoTheOwnBoxMakesTheSourceOwn() {
        for (m in modes) {
            val r = Rig(m)
            r.ctl.selectSource(InputSource.CLIPBOARD)
            r.ctl.onInputBoxTapped()
            assertEquals(InputSource.OWN, r.ctl.source)
            assertEquals(KeyOwner.INPUT, r.focus.owner)
            r.ctl.selectSource(InputSource.APP_BOX); r.ctl.onInputEdited()
            assertEquals(InputSource.OWN, r.ctl.source)
        }
    }

    @Test fun theClipboardFeedsTheInputBox() {
        for (m in modes) {
            val r = Rig(m)
            assertEquals(Outcome.OK, r.ctl.selectSource(InputSource.CLIPBOARD))
            assertEquals("from clipboard", r.input.value)
            assertEquals(InputSource.CLIPBOARD, r.ctl.source)
            assertEquals(Outcome.EMPTY_INPUT, Rig(m, clipText = " ").ctl.selectSource(InputSource.CLIPBOARD))
        }
    }

    @Test fun theAppBoxFeedsTheInputBoxWithTheFieldOrTheSelection() {
        for (m in modes) {
            val whole = Rig(m)
            assertEquals(Outcome.OK, whole.ctl.selectSource(InputSource.APP_BOX))
            assertEquals("hello world", whole.input.value)
            val sel = Rig(m, FakeField("hello world", 6, 11))
            sel.ctl.selectSource(InputSource.APP_BOX)
            assertEquals("the selection wins", "world", sel.input.value)
            assertEquals(Outcome.NO_FIELD, Rig(m, field = null).ctl.selectSource(InputSource.APP_BOX))
        }
    }

    @Test fun theOwnBoxIsWhatYouTypedAndGenerateSendsIt() {
        for (m in modes) {
            val r = Rig(m)
            r.input.value = "typed text"; r.ctl.onInputEdited()
            var out: Outcome? = null
            r.ctl.generate { out = it }
            assertEquals(Outcome.OK, out)
            assertEquals(listOf("typed text"), r.calls)
            assertEquals("<typed text>", r.output.value)
        }
    }

    @Test fun withTheAppBoxAsSourceGenerateReadsTheFieldAgain() {
        for (m in modes) {
            val r = Rig(m)
            r.ctl.selectSource(InputSource.APP_BOX)
            r.field!!.text = "edited meanwhile"
            r.ctl.generate { }
            assertEquals(listOf("edited meanwhile"), r.calls)
        }
    }

    // ── c) the action buttons on a fake InputConnection and clipboard ─────────────────────────────────────────
    @Test fun replaceOverwritesTheWholeFieldWhenNothingIsSelected() {
        for (m in modes) {
            val r = Rig(m); r.output.value = "NEW"
            assertEquals(Outcome.OK, r.ctl.replace())
            assertEquals("NEW", r.field!!.text)
            assertEquals(1, r.field.batches)
        }
    }

    @Test fun replaceOverwritesOnlyTheSelection() {
        for (m in modes) {
            val r = Rig(m, FakeField("hello world", 0, 5)); r.output.value = "bye"
            assertEquals(Outcome.OK, r.ctl.replace())
            assertEquals("bye world", r.field!!.text)
        }
    }

    @Test fun pasteInsertsAtTheCursor() {
        for (m in modes) {
            val r = Rig(m, FakeField("hello world", 5, 5)); r.output.value = ","
            assertEquals(Outcome.OK, r.ctl.paste())
            assertEquals("hello, world", r.field!!.text)
        }
    }

    @Test fun copyPutsTheOutputOnTheClipboard() {
        for (m in modes) {
            val r = Rig(m); r.output.value = "copied"
            assertEquals(Outcome.OK, r.ctl.copy())
            assertEquals("copied", r.clip.text)
        }
    }

    @Test fun emptyOutputDoesNothing() {
        for (m in modes) {
            val r = Rig(m)
            assertEquals(Outcome.EMPTY_OUTPUT, r.ctl.replace())
            assertEquals(Outcome.EMPTY_OUTPUT, r.ctl.paste())
            assertEquals(Outcome.EMPTY_OUTPUT, r.ctl.copy())
            assertEquals("hello world", r.field!!.text)
            assertEquals("from clipboard", r.clip.text)
        }
    }

    @Test fun noFieldMeansNoWriteAndNoStuckEchoGuard() {
        for (m in modes) {
            val r = Rig(m, field = null); r.output.value = "x"
            r.ctl.onInputBoxTapped()
            assertEquals(Outcome.NO_FIELD, r.ctl.replace())
            assertEquals(Outcome.NO_FIELD, r.ctl.paste())
            assertTrue("a refused write leaves no announcement behind", r.focus.onHostSelectionChange())
        }
    }

    @Test fun autoReplaceIsGenerateThenReplaceInOneTap() {
        for (m in modes) {
            val r = Rig(m)
            r.ctl.selectSource(InputSource.APP_BOX)
            var out: Outcome? = null
            r.ctl.autoReplace { out = it }
            assertEquals(Outcome.OK, out)
            assertEquals(listOf("hello world"), r.calls)
            assertEquals("<hello world>", r.output.value)
            assertEquals("the app box now holds the output", "<hello world>", r.field!!.text)
        }
    }

    @Test fun autoReplaceLeavesTheFieldAloneWhenGenerateFails() {
        for (m in modes) {
            val r = Rig(m, reply = { Result.failure(IllegalStateException("offline")) })
            r.ctl.selectSource(InputSource.APP_BOX)
            var out: Outcome? = null
            r.ctl.autoReplace { out = it }
            assertEquals(Outcome.FAILED, out)
            assertEquals("offline", r.ctl.lastError?.message)
            assertEquals("hello world", r.field!!.text)
        }
    }

    @Test fun autoReplaceWithNothingToSendDoesNotTouchTheField() {
        val r = Rig(WriterMode.TRANSLATE, FakeField(""), clipText = null)
        var out: Outcome? = null
        r.ctl.autoReplace { out = it }
        assertEquals(Outcome.EMPTY_INPUT, out)
        assertEquals("", r.field!!.text)
    }

    @Test fun aSummaryIsNeverWrittenOverTheField() {
        val r = Rig(WriterMode.ENHANCE); r.output.value = "summary"
        r.ctl.outputApplicable = false
        assertEquals(Outcome.NOT_APPLICABLE, r.ctl.replace())
        assertEquals("hello world", r.field!!.text)
        assertEquals("still copyable", Outcome.OK, r.ctl.copy())
    }

    @Test fun nothingIsReadAfterTheFieldGoesAway() {
        val r = Rig(WriterMode.DRAFT, field = null)
        assertNull(r.app.read())
        assertFalse(r.app.replace("x"))
        assertFalse(r.app.insert("x"))
    }
}
