package com.diegonmarcos.superapp.shellbroker

import com.diegonmarcos.superapp.devtools.RetryBackoff

/**
 * #1510 The broker never loops to reconnect. A call that finds no channel may try ONE arm attempt, and a failed attempt shuts
 * the gate for [RetryBackoff.afterFailures] (never under the 30 s floor, doubling to 10 min). Pure: the clock is a parameter.
 */
class ArmGate(private val backoff: (Int) -> Long = RetryBackoff::afterFailures) {
    private var failures = 0
    private var notBefore = 0L

    @Synchronized fun mayTry(nowMs: Long): Boolean = nowMs >= notBefore

    @Synchronized fun failed(nowMs: Long) { failures++; notBefore = nowMs + backoff(failures) }

    @Synchronized fun succeeded() { failures = 0; notBefore = 0L }
}
