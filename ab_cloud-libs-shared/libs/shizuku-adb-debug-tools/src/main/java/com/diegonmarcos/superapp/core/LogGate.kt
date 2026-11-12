package com.diegonmarcos.superapp.core

/**
 * An OPT-IN dedupe / rate-limit wrapper around a log sink.
 *
 * ## Why
 * A heartbeat that logs the same line on every tick (the WdKeeper `tick[...]`
 * pass is the worst offender) floods logcat: the same identical string, hundreds
 * of times an hour, burying every other line and the one diagnostic surface
 * ([com.diegonmarcos.superapp.devtools.LogPipe]'s ring) built to read them. The
 * line still matters — it is how "the keeper is alive and deciding" is seen — so
 * silencing it is wrong; what is wrong is repeating it unchanged.
 *
 * [log] emits a line the FIRST time it is seen and then AT MOST once per
 * [windowMs]; the identical lines in between are counted, not printed, and the
 * next emission carries that count and how long ago the last repeat was. A caller
 * that logs `tick[service]: NOTHING` every two seconds then produces one line a
 * minute reading `tick[service]: NOTHING (+29 more in 60s, last 0s ago)` instead
 * of thirty, and a line that changes (`tick[service]: REARM`) is a different key
 * and emits at once.
 *
 * ## Shape
 * Pure Kotlin — no `android.util.Log` — so it is unit-testable on the JVM
 * ([clock] and [sink] are injected, the real caller passing `{ Log.i(TAG, it) }`).
 * [sink] is the LAST parameter so a trailing-lambda construction binds to it and
 * not to a non-function parameter (test-kotlin-lambda-arg-position). Guard:
 * LogGateTest.
 */
class LogGate(
    private val windowMs: Long = 60_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sink: (String) -> Unit,
) {
    /** One key's state: when the current window opened, how many repeats it has
     *  swallowed since the last emission, and when the last repeat arrived. */
    private class Window(val openedAtMs: Long, var suppressed: Int, var lastSeenMs: Long)

    private val windows = HashMap<String, Window>()

    /**
     * Emit [message] unless [key] already emitted within [windowMs]; within the
     * window the repeat is counted and swallowed. [key] is what collapses, so two
     * lines that must stay distinct need distinct keys; [log] with one argument
     * keys on the message itself, which is the common "collapse identical lines".
     */
    @Synchronized
    fun log(key: String, message: String) {
        val now = clock()
        val w = windows[key]
        if (w == null || now - w.openedAtMs >= windowMs) {
            sink(message + suffix(w, now))
            windows[key] = Window(now, 0, now)
        } else {
            w.suppressed++
            w.lastSeenMs = now
        }
    }

    /** Collapse on the message itself: identical lines fold together. */
    fun log(message: String) = log(message, message)

    private fun suffix(prev: Window?, now: Long): String {
        if (prev == null || prev.suppressed <= 0) return ""
        val spanS = (now - prev.openedAtMs) / 1000
        val lastAgoS = (now - prev.lastSeenMs) / 1000
        return " (+${prev.suppressed} more in ${spanS}s, last ${lastAgoS}s ago)"
    }
}
