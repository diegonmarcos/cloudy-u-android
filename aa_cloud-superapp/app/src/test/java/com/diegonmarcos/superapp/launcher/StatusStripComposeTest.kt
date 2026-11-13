package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.cal.TasksPopup
import com.diegonmarcos.superapp.network.NetworkSections
import com.diegonmarcos.superapp.settings.LauncherSettingsPrefs
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.ui.ShellFxState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The launcher status strip in Compose (was LauncherStatusStripView, a 935-line hand-built LinearLayout).
 *
 *   S1 every network icon is drawn, left to right in NetworkSections.ORDER, and the model has a chip for each
 *   S2 the clock reads dd-MM-yyyy HH:mm EEE and sits at the true centre of the strip
 *   S3 the clock clears the camera cutout by the cutout's own pixels, and drops back when there is none (#407)
 *   S4 the right cluster draws RAM, storage and CPU
 *   S5 tapping the clock opens its popup without throwing
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StatusStripComposeTest : KitPageHarness() {

    // #1668 the stopwatch is libs:clock-tools' store: on Robolectric's clock (moved by ShadowSystemClock.advanceBy) for these tests.
    private val swApp: Application get() = ApplicationProvider.getApplicationContext()
    @Before fun clockOnShadowTime() { com.diegonmarcos.clocktools.ClockEngine.now = { android.os.SystemClock.elapsedRealtime() } }
    @org.junit.After fun clockBack() { com.diegonmarcos.clocktools.ClockEngine.now = { System.currentTimeMillis() } }

    @Before fun petsOff() {
        // A pet is a GIF on a frame clock; Compose's idle wait never returns while one plays.
        val app = ApplicationProvider.getApplicationContext<Application>()
        LauncherSettingsPrefs(app).setToggle("pets_anim", false)
        ShellFxState.refresh(app)
    }

    private fun showStrip(): StatusStripModel {
        val model = StatusStripModel(compose.activity)
        showKit { androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.wrapContentSize()) { StatusStripBody(model, compose.activity) } }
        return model
    }

    private fun left(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left

    @Test
    fun `S1 every network icon is drawn in the shared order`() {
        val model = showStrip()
        assertEquals(NetworkSections.ORDER.toSet(), model.chips.keys)
        val xs = NetworkSections.ORDER.map { left("strip:$it") }
        assertEquals("icons run left to right in ORDER: $xs", xs.sorted(), xs)
    }

    @Test
    fun `S1b #1684 a separator between each two groups, nowhere else`() {
        showStrip()
        for (pair in listOf("gps|mesh", "kde|webs", "ssh|data")) {
            val (a, b) = pair.split("|")
            val x = compose.onNodeWithTag("strip:sep:$pair", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
            assertTrue("separator $pair between its neighbours", left("strip:$a") < x && x < left("strip:$b"))
        }
        assertEquals(3, compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("sep") { n -> androidx.compose.ui.semantics.SemanticsConfiguration::class.let { n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null } }?.startsWith("strip:sep:") == true }, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `S1c #1721 resources line shares the network labels line, access row shares the date line`() {
        val model = showStrip()
        compose.runOnUiThread { model.noteCutout(160); model.refreshTime() }   // a camera cutout puts the date on its own line below the first
        compose.waitForIdle()
        val d = compose.activity.resources.displayMetrics.density
        fun b(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        // the network labels' own text line: the chip's top, label height = its height minus the dots row (3 dp)
        val net = b("strip:cellular").let { it.top + (it.height - 3f * d) / 2f }
        val res = b("strip:ram").center.y
        assertEquals("resources line centre $res vs network labels centre $net", net, res, 2f * d)
        assertEquals("access row on the date line", b("strip:clock").center.y, b("permaccess:row").center.y, 2f * d)
        assertTrue("resources top ${b("strip:ram").top} not above network top ${b("strip:cellular").top} by more than 2dp", b("strip:ram").top >= b("strip:cellular").top - 2f * d)
    }

    @Test
    fun `S2 the clock is dated and centred`() {
        val model = showStrip()
        compose.runOnUiThread { model.refreshTime() }
        compose.waitForIdle()
        val node = compose.onNodeWithTag("strip:clock").fetchSemanticsNode()
        val text = node.config[SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue("clock was '$text'", Regex("""\d\d-\d\d-\d{4} \d\d:\d\d [A-Za-z]{3}""").matches(text))
        val rootW = compose.onRoot().fetchSemanticsNode().size.width
        val mid = (node.boundsInRoot.left + node.boundsInRoot.right) / 2f
        assertEquals("clock centre vs strip centre", rootW / 2f, mid, 2f)
    }

    @Test
    fun `S3 the clock clears the cutout by its own pixels`() {
        val model = showStrip()
        fun reserve() = compose.onNodeWithTag("strip:reserve", useUnmergedTree = true).fetchSemanticsNode().size.height
        val base = reserve()
        assertEquals("with no cutout the reserve is the pet row it always was", model.clockReservePx(), base)
        compose.runOnUiThread { model.noteCutout(160) }
        compose.waitForIdle()
        assertEquals("reserve is the cutout less Line 1's own top pad", 160 - model.innerRowTopPadPx, reserve())
        compose.runOnUiThread { model.noteCutout(0) }
        compose.waitForIdle()
        assertEquals("back to the pet row", base, reserve())
    }

    @Test
    fun `S4 the right cluster draws RAM, storage and CPU`() {
        showStrip()
        for (t in listOf("strip:ram", "strip:storage", "strip:cpu")) compose.onNodeWithTag(t).assertExists()
    }

    @Test
    fun `S5 tapping the clock opens its popup without throwing`() {
        showStrip()
        compose.onNodeWithTag("strip:clock").performClick()
        compose.waitForIdle()
    }

    // ── #1646 the live stopwatch readout, left of the date ──

    private fun swText() = compose.onNodeWithTag("strip:stopwatch").fetchSemanticsNode()
        .config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun advance(ms: Long) {
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(ms))
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    @Test
    fun `W1 no readout while the stopwatch is idle`() {
        TasksPopup.resetStopwatchForTest(swApp)
        showStrip()
        compose.onNodeWithTag("strip:stopwatch").assertDoesNotExist()
    }

    @Test
    fun `W2 the readout shows from the shared stopwatch, left of the date, and goes away when it stops`() {
        TasksPopup.resetStopwatchForTest(swApp)
        val model = showStrip()
        compose.runOnUiThread { model.refreshTime(); TasksPopup.toggleStopwatchForTest(swApp) }  // the dropdown's own state, no second timer
        compose.waitForIdle()
        val sw = compose.onNodeWithTag("strip:stopwatch").fetchSemanticsNode().boundsInRoot
        val date = compose.onNodeWithTag("strip:clock").fetchSemanticsNode().boundsInRoot
        assertTrue("readout $sw is left of the date $date", sw.right <= date.left)
        assertTrue("readout reads m:ss: ${swText()}", Regex("""\d+:\d\d""").matches(swText()))
        assertEquals("date stays centred", compose.onRoot().fetchSemanticsNode().size.width / 2f, (date.left + date.right) / 2f, 2f)
        compose.runOnUiThread { TasksPopup.toggleStopwatchForTest(swApp) }
        compose.waitForIdle()
        compose.onNodeWithTag("strip:stopwatch").assertDoesNotExist()
        TasksPopup.resetStopwatchForTest(swApp)
    }

    @Test
    fun `W3 it advances while started and running, reads the shared state, and is frozen once stopped`() {
        TasksPopup.resetStopwatchForTest(swApp)
        showStrip()
        compose.runOnUiThread { TasksPopup.toggleStopwatchForTest(swApp) }
        compose.waitForIdle()
        fun secs(t: String) = t.split(':').map { it.toInt() }.fold(0) { acc, n -> acc * 60 + n }
        val before = secs(swText())
        advance(3_000)
        val after = secs(swText())
        assertTrue("readout advanced while started and running: $before -> $after", after > before)
        // It shows the shared stopwatch's own time (within the one-second tick), not a count of its own.
        val shared = secs(TasksPopup.formatStopwatchBar(TasksPopup.stopwatchElapsedMs()))
        assertTrue("readout $after tracks the shared state $shared", shared - after in 0..1)
        // Stopped: the readout is gone and the shared elapsed time does not move.
        compose.runOnUiThread { TasksPopup.toggleStopwatchForTest(swApp) }
        compose.waitForIdle()
        val frozen = TasksPopup.stopwatchElapsedMs()
        advance(5_000)
        compose.onNodeWithTag("strip:stopwatch").assertDoesNotExist()
        assertEquals("no tick while stopped", frozen, TasksPopup.stopwatchElapsedMs())
        TasksPopup.resetStopwatchForTest(swApp)
    }

    @Test
    fun `W4 the tick is gated on started AND running`() {
        assertTrue(stopwatchShouldTick(running = true, started = true))
        assertTrue(!stopwatchShouldTick(running = true, started = false))
        assertTrue(!stopwatchShouldTick(running = false, started = true))
        assertTrue(!stopwatchShouldTick(running = false, started = false))
    }
}
