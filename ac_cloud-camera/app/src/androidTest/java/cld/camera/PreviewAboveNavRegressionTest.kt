package cld.camera

import android.view.View
import androidx.test.core.app.ActivityScenario
import cld.camera.ui.NavLayout
import cld.camera.ui.activities.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real layout: the preview's bounds on screen never intersect the mode nav's, and the nav is the lowest view. */
class PreviewAboveNavRegressionTest {
    private fun View.box(): NavLayout.Box {
        val at = IntArray(2)
        getLocationOnScreen(at)
        return NavLayout.Box(at[0], at[1], at[0] + width, at[1] + height)
    }

    @Test fun previewDoesNotIntersectTheNav() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitModeTabs(scenario)
            scenario.onActivity { a ->
                val nav = a.modeNav.view.box()
                val preview = a.previewView.box()
                assertFalse("preview $preview intersects the nav $nav", preview.intersects(nav))
                assertTrue("preview ends at or above the nav's top edge", preview.bottom <= nav.top)
                val frame = a.findViewById<View>(R.id.main_frame).box()
                assertTrue("the preview frame ends at the nav's top edge", frame.bottom <= nav.top)
            }
        }
    }
}
