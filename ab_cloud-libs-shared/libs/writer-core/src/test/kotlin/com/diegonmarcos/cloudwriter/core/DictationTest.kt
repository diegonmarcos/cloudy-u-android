package com.diegonmarcos.cloudwriter.core

import com.diegonmarcos.cloudwriter.core.Dictation.TranslateMode
import org.junit.Assert.assertEquals
import org.junit.Test

class DictationTest {
    @Test fun `on-device words get a capital and a full stop`() {
        assertEquals("Hello world.", Dictation.punctuate("  hello   world "))
        assertEquals("X.", Dictation.punctuate("x"))
        assertEquals("", Dictation.punctuate(""))
        assertEquals("", Dictation.punctuate("   "))
    }

    @Test fun `finished sentences are left alone`() {
        assertEquals("Done!", Dictation.punctuate("Done!"))
        assertEquals("Really?", Dictation.punctuate("really?"))
        assertEquals("He said \"go\"", Dictation.punctuate("He said \"go\""))
        assertEquals("Note:", Dictation.punctuate("Note:"))
        assertEquals("Wait…", Dictation.punctuate("Wait…"))
        assertEquals("Ends.", Dictation.punctuate("Ends."))
    }

    @Test fun `segments are spaced as prose and speakers start a line`() {
        assertEquals("", Dictation.separator("", "Hi."))
        assertEquals("", Dictation.separator("Line\n", "Hi."))
        assertEquals("", Dictation.separator("Hi. ", "There."))
        assertEquals(" ", Dictation.separator("Hi.", "There."))
        assertEquals("\n", Dictation.separator("Hi.", "Speaker 2: yes."))
        assertEquals("\n", Dictation.separator("Hi. ", "  Speaker 10: no."))
        assertEquals(" ", Dictation.separator("Hi.", "The Speaker 2: said"))
    }

    @Test fun `a translation goes under the original or replaces it`() {
        assertEquals("Hola.", Dictation.withTranslation("Hola.", null, TranslateMode.ALONGSIDE))
        assertEquals("Hola.", Dictation.withTranslation("Hola.", "  ", TranslateMode.INSTEAD))
        assertEquals("Hello.", Dictation.withTranslation("Hola.", " Hello. ", TranslateMode.INSTEAD))
        assertEquals("Hola.\n→ Hello.\n", Dictation.withTranslation("Hola.", " Hello. ", TranslateMode.ALONGSIDE))
    }

    @Test fun `translate modes parse with alongside as the default`() {
        assertEquals(TranslateMode.INSTEAD, TranslateMode.of("instead"))
        assertEquals(TranslateMode.ALONGSIDE, TranslateMode.of("alongside"))
        assertEquals(TranslateMode.ALONGSIDE, TranslateMode.of(null))
        assertEquals(TranslateMode.ALONGSIDE, TranslateMode.of("both"))
    }
}
