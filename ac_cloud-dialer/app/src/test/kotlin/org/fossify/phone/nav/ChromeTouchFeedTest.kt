package org.fossify.phone.nav

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** #1760 the View pages report the finger to the chrome collapse: a swipe down at the top of Home moves no scroll but must restore the bars. */
class ChromeTouchFeedTest {
    private val log = mutableListOf<String>()
    private val feed = ChromeTouchFeed({ log += "finger=$it" }, { log += "up" }, { log += "release" })

    @Test fun `a touch reports finger down, the downward travel and the release`() {
        feed.onTouch(MotionEvent.ACTION_DOWN, 400f)
        feed.onTouch(MotionEvent.ACTION_MOVE, 520f)
        feed.onTouch(MotionEvent.ACTION_MOVE, 640f)
        feed.onTouch(MotionEvent.ACTION_UP, 640f)
        assertEquals(listOf("finger=true", "up", "up", "finger=false", "release"), log)
    }

    @Test fun `a drag up the screen or a wobble inside the slop restores nothing`() {
        feed.onTouch(MotionEvent.ACTION_DOWN, 800f)
        feed.onTouch(MotionEvent.ACTION_MOVE, 600f)
        feed.onTouch(MotionEvent.ACTION_MOVE, 605f)
        feed.onTouch(MotionEvent.ACTION_CANCEL, 605f)
        assertEquals(listOf("finger=true", "finger=false", "release"), log)
    }
}
