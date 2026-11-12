package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.lifecycle.Lifecycle
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #1629 The launcher's status strip runs only while the launcher is visible. Its model (the 10 s RAM / CPU / storage
 * ticker, the minute tick, the battery, Wi-Fi signal and network callbacks, the passive location listener) used to be
 * started for as long as the strip was COMPOSED, and the launcher stays composed while another app is in front.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StripLifecycleTest : KitPageHarness() {

    @Test
    fun `the strip is started while its host is visible and stopped when it is not`() {
        var starts = 0
        var stops = 0
        var resumes = 0
        showKit { StripLifecycle(Unit, onStart = { starts++ }, onResume = { resumes++ }, onStop = { stops++ }) }
        assertEquals("a visible host starts the strip once", 1, starts)
        assertEquals(0, stops)
        assertEquals(1, resumes)

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED) // another app comes to the front
        assertEquals("the strip kept running behind another app", 1, stops)
        assertEquals(1, starts)

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) // back on the launcher
        assertEquals("the strip was not started again on return", 2, starts)
        assertEquals(2, resumes)
        assertEquals(1, stops)
    }

    @Test
    fun `the model is idempotent and the strip content is wired to the lifecycle, not to the composition alone`() {
        val model = StatusStripModel(compose.activity)
        assertFalse(model.running)
        model.stop() // a stop with no start is a no-op, not a crash
        assertFalse(model.running)
        val src = File("src/main/java/com/diegonmarcos/superapp/launcher/LauncherStatusStrip.kt").readText()
        assertTrue(src.contains("StripLifecycle(model, onStart = { model.start() }, onResume = { model.refreshBroker(); model.refreshDoors() }, onStop = { model.stop() })"))
        assertFalse("the model is started by a composition-only effect again",
            Regex("""DisposableEffect\(model\)\s*\{\s*model\.start\(\)""").containsMatchIn(src))
    }
}
