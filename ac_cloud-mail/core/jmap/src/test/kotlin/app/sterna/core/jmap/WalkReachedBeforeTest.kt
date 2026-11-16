package app.sterna.core.jmap

import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1763: an age window ends its walk on the rows, not on a filter (a filter would change the queryState). */
class WalkReachedBeforeTest {
    private fun mail(receivedAt: String?) = Email(id = "x", receivedAt = receivedAt)
    private val cutoff = java.time.Instant.parse("2026-08-18T00:00:00Z").toEpochMilli()

    @Test fun `a page whose oldest row is before the cutoff ends the walk`() {
        assertTrue(walkReachedBefore(listOf(mail("2026-09-01T00:00:00Z"), mail("2026-08-17T23:59:59Z")), cutoff))
    }

    @Test fun `a page entirely inside the window lets the walk go on`() {
        assertFalse(walkReachedBefore(listOf(mail("2026-09-01T00:00:00Z"), mail("2026-08-18T00:00:00Z")), cutoff))
    }

    @Test fun `an empty or undated page never ends the walk`() {
        assertFalse(walkReachedBefore(emptyList(), cutoff))
        assertFalse(walkReachedBefore(listOf(mail(null)), cutoff))
        assertFalse(walkReachedBefore(listOf(mail("not a date")), cutoff))
    }
}
