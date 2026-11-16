package org.fossify.phone.nav

import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1760 a list's own re-layout is not the reader scrolling: only a dragged or settling RecyclerView feeds the chrome collapse. */
class ChromeScrollHooksTest {
    @Test fun `a dragged or settling list is a scroll`() {
        assertTrue(ChromeScrollHooks.countsAsScroll(RecyclerView.SCROLL_STATE_DRAGGING, 40))
        assertTrue(ChromeScrollHooks.countsAsScroll(RecyclerView.SCROLL_STATE_SETTLING, -25))
    }

    @Test fun `an idle list's layout shift is not, nor is a zero delta`() {
        assertFalse(ChromeScrollHooks.countsAsScroll(RecyclerView.SCROLL_STATE_IDLE, 40))
        assertFalse(ChromeScrollHooks.countsAsScroll(RecyclerView.SCROLL_STATE_DRAGGING, 0))
    }
}
