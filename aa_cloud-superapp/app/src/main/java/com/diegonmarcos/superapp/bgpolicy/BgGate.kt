package com.diegonmarcos.superapp.bgpolicy

/**
 * #1506 Every Background-policy action needs the shell. When the channel is down the user is asked: open the ADB Shell page
 * (its re-arm flow), jump to the wireless-debugging switch, or give up. Arming itself lives on that one page (the channel has a
 * single control surface), so after either choice the action ends and the user taps again once it is up. Pure (each effect is a
 * lambda), so the prompt and the give-up path are unit-tested.
 */
object BgGate {
    enum class Choice { REARM, OPEN_WIRELESS_DEBUGGING, CANCEL }
    enum class Result { READY, SENT_TO_ARM, CANCELLED }

    const val MESSAGE = "Background policy needs the ADB shell and it is not connected. Re-arm it on the ADB Shell page, or turn on wireless debugging first, then tap again."

    suspend fun run(
        ready: () -> Boolean,
        openArmFlow: () -> Unit,
        openWirelessDebugging: () -> Unit,
        ask: suspend (message: String) -> Choice,
    ): Result {
        if (ready()) return Result.READY
        return when (ask(MESSAGE)) {
            Choice.REARM -> { openArmFlow(); Result.SENT_TO_ARM }
            Choice.OPEN_WIRELESS_DEBUGGING -> { openWirelessDebugging(); Result.SENT_TO_ARM }
            Choice.CANCEL -> Result.CANCELLED
        }
    }
}
