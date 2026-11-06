package cld.camera

import com.diegonmarcos.superapp.vision.AnalysisGate
import com.diegonmarcos.superapp.vision.AnalyzerLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The analyzer runs only while its page is visible and not paused: hiding or pausing removes it at once. */
class AnalyzerLifecycleTest {
    private class FakeHost : AnalyzerLifecycle.Host {
        var attached = 0
        var detached = 0
        override fun attach() { attached++ }
        override fun detach() { detached++ }
    }

    private var now = 0L
    private val gate = AnalysisGate(150) { now }
    private val host = FakeHost()
    private val life = AnalyzerLifecycle(host, gate)

    @Test fun nothingRunsUntilThePageIsShown() {
        assertFalse(life.isAttached)
        assertFalse(gate.admits())
        assertEquals(0, host.attached)
    }

    @Test fun shownInstallsTheAnalyzerOnce() {
        life.onShown(); life.onShown()
        assertTrue(life.isAttached)
        assertEquals(1, host.attached)
        assertTrue(gate.admits())
    }

    @Test fun hiddenStopsTheAnalyzer() {
        life.onShown()
        life.onHidden()
        assertFalse(life.isAttached)
        assertEquals(1, host.detached)
        now += 1000
        assertFalse("a hidden page admits no frame", gate.admits())
    }

    @Test fun pauseStopsTheAnalyzerAndResumeRestartsIt() {
        life.onShown()
        life.setPaused(true)
        assertFalse(life.isAttached)
        assertEquals(1, host.detached)
        now += 1000
        assertFalse(gate.admits())
        life.setPaused(false)
        assertTrue(life.isAttached)
        assertEquals(2, host.attached)
        assertTrue(gate.admits())
    }

    @Test fun resumingWhileHiddenStaysStopped() {
        life.onShown()
        life.setPaused(true)
        life.onHidden()
        life.setPaused(false)
        assertFalse(life.isAttached)
        life.onShown()
        assertTrue(life.isAttached)
    }

    @Test fun shownWhilePausedStaysStopped() {
        life.setPaused(true)
        life.onShown()
        assertFalse(life.isAttached)
        assertEquals(0, host.attached)
    }

    @Test fun framesAreSpacedByTheInterval() {
        life.onShown()
        assertTrue(gate.admits())
        now += 100
        assertFalse(gate.admits())
        now += 50
        assertTrue(gate.admits())
    }
}
