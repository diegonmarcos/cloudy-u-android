package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

/** #1800: the compose templates store round-trips, edits in place, deletes, and never throws on junk. */
class ComposeTemplateCodecTest {
    private val a = ComposeTemplate("t1", "Thanks", "Thank you for your message.")
    private val b = ComposeTemplate("t2", "Later", "I will answer later today.")

    @Test fun `round trip keeps order and fields`() {
        assertEquals(listOf(a, b), ComposeTemplateCodec.decode(ComposeTemplateCodec.encode(listOf(a, b))))
    }

    @Test fun `upsert replaces by id and appends a new one`() {
        val edited = a.copy(text = "Thanks!")
        assertEquals(listOf(edited, b), ComposeTemplateCodec.upsert(listOf(a, b), edited))
        assertEquals(listOf(a, b), ComposeTemplateCodec.upsert(listOf(a), b))
    }

    @Test fun `remove drops only that id`() {
        assertEquals(listOf(b), ComposeTemplateCodec.remove(listOf(a, b), "t1"))
    }

    @Test fun `junk, blanks and nameless entries`() {
        assertEquals(emptyList<ComposeTemplate>(), ComposeTemplateCodec.decode("not json"))
        assertEquals(emptyList<ComposeTemplate>(), ComposeTemplateCodec.decode(null))
        val decoded = ComposeTemplateCodec.decode("""[{"id":"x","name":"","text":"Hello there\nmore"},{"id":"","text":"y"},{"id":"z","text":""}]""")
        assertEquals(listOf(ComposeTemplate("x", "Hello there", "Hello there\nmore")), decoded)
    }
}
