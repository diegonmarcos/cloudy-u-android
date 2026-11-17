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

    @Test fun `an offset jump up while the finger drags down the screen is layout, not a scroll`() {
        assertFalse(ChromeScrollHooks.countsAsOffsetScroll(85, fingerHeld = true, fingerDy = 120f))
    }

    @Test fun `a reader scrolling down, a scroll back up and a settling fling still count`() {
        assertTrue(ChromeScrollHooks.countsAsOffsetScroll(85, fingerHeld = true, fingerDy = -200f))
        assertTrue(ChromeScrollHooks.countsAsOffsetScroll(-34, fingerHeld = true, fingerDy = 120f))
        assertTrue(ChromeScrollHooks.countsAsOffsetScroll(60, fingerHeld = false, fingerDy = 120f))
        assertFalse(ChromeScrollHooks.countsAsOffsetScroll(0, fingerHeld = true, fingerDy = -200f))
    }
}
