package app.sterna.ui.settings

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignatureClipboardTest {

    @Test fun htmlIsPreferredOverText() {
        assertEquals("<b>x</b>", clipToSignatureSource("<b>x</b>", "x"))
    }

    @Test fun blankHtmlFallsBackToText() {
        assertEquals("x", clipToSignatureSource("  \n", "x"))
        assertEquals("x", clipToSignatureSource(null, "x"))
    }

    @Test fun nothingOnTheClipboardGivesNull() {
        assertNull(clipToSignatureSource(null, null))
        assertNull(clipToSignatureSource("", null))
    }

    @Test fun fragmentHtmlIsKeptAsIs() {
        assertEquals("<div>a</div><div>b</div>", clipToSignatureSource("<div>a</div><div>b</div>", "a\nb"))
    }

    private val html = "<div><b>Ann</b></div>"
    private val clipBoth = { SignatureClip(html, "Ann") }
    private val clipTextOnly = { SignatureClip(null, "Ann") }

    private fun state() = SignatureEditorState("ab", "n", false, selection = TextRange(1))

    private fun pasteAt1(clip: () -> SignatureClip): SignatureEditorState {
        val s = state()
        s.typed(TextFieldValue("aAnnb", TextRange(4)), clip)
        return s
    }

    @Test fun pasteWithHtmlInsertsTheHtmlAtTheSelection() {
        val s = pasteAt1(clipBoth)
        assertEquals("a${html}b", s.source)
        assertEquals(TextRange(1 + html.length), s.textField.selection)
    }

    @Test fun pasteWithoutHtmlInsertsThePlainText() {
        val s = pasteAt1(clipTextOnly)
        assertEquals("aAnnb", s.source)
        assertEquals(TextRange(4), s.textField.selection)
    }

    @Test fun pasteOverASelectionReplacesIt() {
        val s = SignatureEditorState("abc", "n", false, selection = TextRange(1, 2))
        s.typed(TextFieldValue("aAnnc", TextRange(4)), clipBoth)
        assertEquals("a${html}c", s.source)
    }

    @Test fun aSingleTypedCharacterIsUntouched() {
        val s = state()
        s.typed(TextFieldValue("aXb", TextRange(2)), clipBoth)
        assertEquals("aXb", s.source)
    }

    @Test fun anInsertionThatIsNotTheClipboardTextIsUntouched() {
        val s = state()
        s.typed(TextFieldValue("aZZZb", TextRange(4)), clipBoth)
        assertEquals("aZZZb", s.source)
    }

    @Test fun theHtmlPasteIsOneUndoStep() {
        val s = pasteAt1(clipBoth)
        s.undo()
        assertEquals("ab", s.source)
    }
}
