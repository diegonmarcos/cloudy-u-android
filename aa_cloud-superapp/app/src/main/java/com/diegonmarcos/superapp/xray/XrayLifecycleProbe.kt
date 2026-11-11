package com.diegonmarcos.superapp.xray

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager

/**
 * #1649 What the platform itself tells the engine profiler, with no code in any engine:
 *  - ui/frame: the real duration of every frame the app draws, from the window's own FrameMetrics (TOTAL_DURATION, the number
 *    `dumpsys gfxinfo` reports) for every resumed activity. One callback per frame ACTUALLY drawn, on its own thread: an idle
 *    screen costs nothing and nothing here forces a frame. A window's first-draw frame is left out, as the platform's jank
 *    count does.
 *  - <engine>/page: how long a page took to open, from the fragment being attached to it being started (its view built and
 *    laid out), booked to the engine that owns the fragment's package ([EngineProfiles.engineOfPackage]).
 *  - <engine>/activity: how long an activity's onCreate took (Android 10+, where the platform reports both ends), same booking.
 *  - whether the app is in the foreground (an activity is started), which splits every engine's CPU into foreground / background.
 */
object XrayLifecycleProbe : Application.ActivityLifecycleCallbacks {
    private var started = 0
    private val thread by lazy { HandlerThread("xray-frames").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }
    private val listeners = java.util.WeakHashMap<Window, Window.OnFrameMetricsAvailableListener>()
    private val opening = java.util.WeakHashMap<Any, Long>()

    @Volatile private var installed = false

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(this)
    }

    /** One frame's metrics: its total duration, unless it is the window's first draw. Returns the recorded ns, or -1. */
    internal fun onFrame(firstDraw: Boolean, totalNs: Long): Long {
        if (firstDraw || totalNs <= 0L) return -1L
        EngineProfiler.record("ui", "frame", totalNs, 0L, ledgerTag = "")
        return totalNs
    }

    /** [who] (a fragment or an activity) began opening. Main thread. */
    internal fun begin(who: Any) { opening[who] = EngineProfiler.wallClock() }

    /** [who] finished opening: book the time as [path] of the engine that owns its class. Returns that engine, or null. */
    internal fun end(who: Any, path: String): String? {
        val t0 = opening.remove(who) ?: return null
        val engine = EngineProfiles.engineOfPackage(who.javaClass.name.substringBeforeLast('.', "")) ?: return null
        EngineProfiler.record(engine, path, EngineProfiler.wallClock() - t0, 0L, ledgerTag = "")
        return engine
    }

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentPreAttached(fm: FragmentManager, f: Fragment, context: Context) = begin(f)
        override fun onFragmentStarted(fm: FragmentManager, f: Fragment) { end(f, "page") }
    }

    override fun onActivityPreCreated(a: Activity, b: Bundle?) = begin(a)
    override fun onActivityPostCreated(a: Activity, b: Bundle?) { end(a, "activity") }

    override fun onActivityCreated(a: Activity, b: Bundle?) {
        (a as? FragmentActivity)?.supportFragmentManager?.registerFragmentLifecycleCallbacks(fragments, true)
    }

    override fun onActivityStarted(a: Activity) { if (++started == 1) EngineProfiler.foreground = true }
    override fun onActivityStopped(a: Activity) { if (--started <= 0) { started = 0; EngineProfiler.foreground = false } }

    override fun onActivityResumed(a: Activity) {
        val w = a.window ?: return
        if (listeners.containsKey(w)) return
        val l = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            onFrame(m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L, m.getMetric(FrameMetrics.TOTAL_DURATION))
        }
        if (runCatching { w.addOnFrameMetricsAvailableListener(l, handler) }.isSuccess) listeners[w] = l
    }

    override fun onActivityPaused(a: Activity) {
        val w = a.window ?: return
        listeners.remove(w)?.let { l -> runCatching { w.removeOnFrameMetricsAvailableListener(l) } }
    }

    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}
