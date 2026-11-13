package com.diegonmarcos.cloudwebserver

import com.diegonmarcos.superapp.fleetxray.perf.XrayBenchmark
import com.diegonmarcos.superapp.fleetxray.perf.XrayBenchmarks
import com.diegonmarcos.superapp.fleetxray.perf.XrayProfiler

/**
 * #1728 Cloud C3 Webserver's declared benchmarks (Configs > X-Ray App > Performance). The server is the Rust core, so they time the
 * calls the app really makes into it: its loopback admin API (127.0.0.1 only, GET only, no write header), the JNI bridge and the
 * keep-alive health read. The Rust core's own start is recorded once per launch as "web.core.start" (it cannot be re-run safely).
 * Caveat in the report: CPU is the calling thread's, so server-side work on the Rust threads shows as waiting here.
 */
object WebserverBenchmarks {
    const val ROUNDS = 10
    const val JNI_CALLS = 200

    /** The GET routes timed, as (operation, title, route). All read-only. */
    val ROUTES: List<Triple<String, String, String>> = listOf(
        Triple("web.api.catalogue", "API catalogue build (routes)", Api.ROUTES),
        Triple("web.pages.index-scan", "Page index / served roots scan", Api.SERVED),
        Triple("web.adb.status", "ADB status round trip", Api.ADB_SHELL),
        Triple("web.api.power", "Server power state read", Api.POWER),
    )

    private fun httpBenchmark(op: String, title: String, route: String) = XrayBenchmark(op, title, "read-only; $ROUNDS GET requests of $route to 127.0.0.1, no write header", 20_000, 2) { _ ->
        if (Core.state !is Core.CoreState.Up) return@XrayBenchmark "the Rust core is not up (state: ${Core.state})"
        XrayProfiler.span(op) { sp ->
            var bytes = 0L; var errors = 0
            repeat(ROUNDS) {
                when (val r = Api.getBlocking(route)) {
                    is Api.Reply.Ok -> bytes += r.value.toString().length
                    is Api.Reply.Err -> { errors++; if (sp.note.isEmpty()) sp.note = r.reason.take(100) }
                }
            }
            sp.add(items = (ROUNDS - errors).toLong(), bytes = bytes)
            if (errors == ROUNDS) sp.failed = true
        }
        null
    }

    fun all(): List<XrayBenchmark> = ROUTES.map { (op, title, route) -> httpBenchmark(op, title, route) } + listOf(
        XrayBenchmark("web.core.jni-call", "Rust core JNI call (UI sections)", "read-only; $JNI_CALLS calls of the core's UI-declaration reader", 15_000, 3) { _ ->
            if (Core.state !is Core.CoreState.Up) return@XrayBenchmark "the Rust core is not up (state: ${Core.state})"
            XrayProfiler.span("web.core.jni-call") { sp -> var n = 0L; repeat(JNI_CALLS) { n += Core.uiSections()?.length ?: 0 }; sp.add(items = JNI_CALLS.toLong(), bytes = n) }
            null
        },
        XrayBenchmark("web.keepalive.health", "Keep-alive health read", "read-only; the same requirement rows Home shows, read from the device", 15_000, 3) { ctx ->
            XrayProfiler.span("web.keepalive.health") { sp -> sp.add(items = KeepAliveHealth.evaluate(ContextProbe(ctx)).size.toLong()) }
            null
        },
    )

    fun register() { XrayBenchmarks.register(*all().toTypedArray()) }
}
