package com.diegonmarcos.superapp.vision

/**
 * Whether an analysis page may spend CPU right now: only while it is visible and not paused, and no closer to the
 * previous frame than [minIntervalMs]. The battery rule of every analysis page (Objects, VR): the analyzer runs only
 * while its page is on screen.
 */
class AnalysisGate(private val minIntervalMs: Long, private val clock: () -> Long) {
    @Volatile var visible: Boolean = false
    @Volatile var paused: Boolean = false
    @Volatile private var lastAt: Long = Long.MIN_VALUE

    /** True when the frame at hand should be analysed (and counts it as analysed). */
    fun admits(): Boolean {
        if (!visible || paused) return false
        val now = clock()
        if (lastAt != Long.MIN_VALUE && now - lastAt < minIntervalMs) return false
        lastAt = now
        return true
    }
}

/**
 * Installs the analyzer on its use case while the page is shown and running, and REMOVES it the moment the page is
 * hidden or paused (the use case then drops frames without calling anyone). [host] is the thin adapter over CameraX's
 * ImageAnalysis (setAnalyzer / clearAnalyzer); the state machine here is plain and JVM-testable.
 */
class AnalyzerLifecycle(private val host: Host, private val gate: AnalysisGate) {
    interface Host {
        fun attach()
        fun detach()
    }

    private var attached = false

    val isAttached: Boolean get() = attached

    /** The page became visible (mode picked, activity resumed): analyse again unless paused. */
    fun onShown() { gate.visible = true; sync() }

    /** The page is hidden (mode left, activity paused, screen off): stop analysing at once. */
    fun onHidden() { gate.visible = false; sync() }

    /** The user's pause toggle. */
    fun setPaused(paused: Boolean) { gate.paused = paused; sync() }

    private fun sync() {
        val want = gate.visible && !gate.paused
        if (want == attached) return
        attached = want
        if (want) host.attach() else host.detach()
    }
}
