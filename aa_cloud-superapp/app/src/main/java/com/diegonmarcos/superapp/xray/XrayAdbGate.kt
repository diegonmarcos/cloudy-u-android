package com.diegonmarcos.superapp.xray

/**
 * #1504 Export needs ADB. When the privileged channel is not up, the export asks: re-arm the channel, open wireless debugging, or
 * export a partial report ("partial: no adb"). The loop is pure (every effect is a lambda) so the prompt and the partial mode are
 * unit-tested; the screen supplies a dialog for [ask].
 */
object XrayAdbGate {
    enum class Choice { REARM, OPEN_WIRELESS_DEBUGGING, PARTIAL, CANCEL }

    sealed class Result {
        object Ready : Result()
        data class Partial(val reason: String) : Result()
        object Cancelled : Result()
    }

    const val MAX_ASKS = 4

    suspend fun run(
        ready: () -> Boolean,
        rearm: () -> Boolean,
        openWirelessDebugging: () -> Unit,
        ask: suspend (message: String) -> Choice,
    ): Result {
        if (ready()) return Result.Ready
        var message = "X-Ray needs the ADB shell for the full battery and system diagnosis, and it is not connected."
        repeat(MAX_ASKS) {
            when (ask(message)) {
                Choice.REARM -> { rearm(); if (ready()) return Result.Ready; message = "The channel is still not connected. Turn on wireless debugging and try again." }
                Choice.OPEN_WIRELESS_DEBUGGING -> {
                    openWirelessDebugging()
                    if (ready()) return Result.Ready
                    message = "Turn on wireless debugging in Developer options, come back, then re-arm."
                }
                Choice.PARTIAL -> return Result.Partial("user exported without adb")
                Choice.CANCEL -> return Result.Cancelled
            }
        }
        return Result.Partial("adb still not connected after $MAX_ASKS attempts")
    }
}
