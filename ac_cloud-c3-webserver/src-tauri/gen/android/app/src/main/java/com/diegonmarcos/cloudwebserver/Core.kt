package com.diegonmarcos.cloudwebserver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * #1682 P1 the Rust core (core/, the cdylib build.json::native.lib_name) as the UI sees it: loaded and attached once per activity,
 * off the main thread; [state] is what the UI shows while it binds and when it could not ([CoreState.Failed] carries the reason, the
 * app never crashes on a missing or broken library). The servers' ports are build.json's: the core answers the admin port it bound.
 */
object Core {
    sealed interface CoreState {
        data object Starting : CoreState
        data class Up(val port: Int) : CoreState
        data class Failed(val reason: String) : CoreState
    }

    var state: CoreState by mutableStateOf(CoreState.Starting)
        private set

    private const val LIB = "cloud_c3_webserver_lib"

    fun attach(activity: MainActivity) {
        Thread {
            state = runCatching {
                // #1728 the Rust core's start (library load + attach) is recorded once per launch for X-Ray App > Performance
                com.diegonmarcos.superapp.fleetxray.perf.XrayProfiler.span("web.core.start") { sp ->
                    System.loadLibrary(LIB)
                    val port = activity.nativeAttach(activity)
                    sp.add(items = 1)
                    if (port > 0) CoreState.Up(port) else CoreState.Failed("the Rust core did not report its port")
                }
            }.getOrElse { CoreState.Failed("the Rust core did not load: ${it.message ?: it.javaClass.simpleName}") }
        }.apply { name = "c3-core-attach" }.start()
    }

    fun detach(activity: MainActivity) {
        if (state is CoreState.Up) runCatching { activity.nativeDetach() }
    }

    /** Bound by name from the Rust core (core/src/lib.rs): build.json::ui.sections as JSON text. */
    external fun nativeUiSections(): String

    /** The nav declaration's sections, from the core; null when the library cannot load (the shell then draws the bottom ids alone). */
    fun uiSections(): String? = runCatching { System.loadLibrary(LIB); nativeUiSections() }.getOrNull()?.takeIf { it.startsWith("[") }

    /** The admin port: the bound one, else the declared one (BuildConfig.ADMIN_PORT, build.json::runtime.port). */
    val port: Int get() = (state as? CoreState.Up)?.port ?: BuildConfig.ADMIN_PORT
}
