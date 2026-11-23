package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test

/** #1869 no spinner over cached rows: the tern answers the pull or an empty list, a background reconcile draws a line. */
class RefreshDisplayTest {

    @Test fun `the whole truth table`() {
        data class Case(val indicator: Boolean, val gesture: Boolean, val rows: Int, val tern: Boolean, val line: Boolean)
        val cases = listOf(
            Case(indicator = false, gesture = false, rows = 0, tern = false, line = false),
            Case(indicator = false, gesture = true, rows = 40, tern = false, line = false),
            Case(indicator = false, gesture = false, rows = 40, tern = false, line = false),
            Case(indicator = true, gesture = false, rows = 0, tern = true, line = false),
            Case(indicator = true, gesture = true, rows = 0, tern = true, line = false),
            Case(indicator = true, gesture = true, rows = 40, tern = true, line = false),
            Case(indicator = true, gesture = false, rows = 40, tern = false, line = true),
            Case(indicator = true, gesture = false, rows = 1, tern = false, line = true),
        )
        for (c in cases) {
            assertEquals("tern for $c", c.tern, ternDrawn(c.indicator, c.gesture, c.rows))
            assertEquals("line for $c", c.line, syncLineDrawn(c.indicator, c.gesture, c.rows))
        }
    }

    @Test fun `the two are never drawn together`() {
        for (indicator in listOf(false, true)) for (gesture in listOf(false, true)) for (rows in listOf(0, 1, 500)) {
            assertEquals(false, ternDrawn(indicator, gesture, rows) && syncLineDrawn(indicator, gesture, rows))
        }
    }
}
