package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1837 phase 3: the one-row-per-message filing the projection of every folder follows (the rule itself is tested in Rust). */
class FolderFilingTest {
    private fun e(id: String) = Email(id = id)
    private val none: (String) -> Boolean = { false }

    @Test fun `a message in the inbox and a label is filed under the core's choice from either feed`() {
        val primary = mapOf("m1" to "inbox", "m2" to "lbl")
        val fromLabel = FolderFiling.plan("lbl", listOf(e("m1"), e("m2")), emptyList(), emptyList(), primary, emptySet(), none)
        val fromInbox = FolderFiling.plan("inbox", listOf(e("m1")), emptyList(), emptyList(), primary, emptySet(), none)
        assertEquals(listOf("m1" to "inbox", "m2" to "lbl"), fromLabel.rows.map { it.first.id to it.second })
        // The same filing of m1 whichever folder was synced: no flip-flop.
        assertEquals("inbox", fromInbox.rows.single().second)
    }

    @Test fun `a message the feed gives no primary for is filed under the folder being projected`() {
        val p = FolderFiling.plan("lbl", listOf(e("m1")), emptyList(), emptyList(), emptyMap(), emptySet(), none)
        assertEquals("lbl", p.rows.single().second)
    }

    @Test fun `a membership that ended on a message still held elsewhere re-files it instead of deleting it`() {
        val p = FolderFiling.plan("inbox", emptyList(), listOf(e("m1")), listOf("m1", "m2"), mapOf("m1" to "trash"), setOf("m1", "m2"), none)
        assertEquals(listOf("m1" to "trash"), p.rows.map { it.first.id to it.second })
        assertEquals("only the message that left the store is deleted", listOf("m2"), p.gone)
    }

    @Test fun `only rows the app holds in this folder are deleted, and never a protected one`() {
        val p = FolderFiling.plan("inbox", emptyList(), emptyList(), listOf("a", "b", "c"), emptyMap(), setOf("a", "c"), { it == "c" })
        assertEquals(listOf("a"), p.gone)
        assertTrue(p.rows.isEmpty())
    }

    @Test fun `a protected message is neither written nor re-filed`() {
        val p = FolderFiling.plan("inbox", listOf(e("m1")), listOf(e("m2")), emptyList(), mapOf("m1" to "inbox", "m2" to "trash"), emptySet(), { true })
        assertTrue(p.rows.isEmpty())
    }
}
