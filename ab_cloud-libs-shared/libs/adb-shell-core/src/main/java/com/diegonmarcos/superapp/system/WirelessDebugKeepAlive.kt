package com.diegonmarcos.superapp.system

/**
 * The decisions behind the Wireless Debugging keep-alive, with every side
 * effect behind [Device] so a JVM test can drive it with a fake shell.
 * [WirelessDebugKeeper] is the Android glue around it.
 *
 * Deliberately absent from [Device]: pairing. The pairing lives in adbd's key
 * store and survives everything this watchdog reacts to; if it is ever gone,
 * re-pairing needs a code only the owner can read off the screen, so the
 * keep-alive reports the failure and never tries.
 */
object WirelessDebugKeepAlive {

    interface Device {
        /** `adb_wifi_enabled` as the platform reports it right now. */
        fun wirelessDebuggingOn(): Boolean

        /** Connected to a Wi-Fi network. Without one the platform refuses the
         *  setting outright, so writing it is pointless and nobody's fault. */
        fun onWifi(): Boolean

        /** Write it back on through whatever rung works (WRITE_SECURE_SETTINGS,
         *  then a shell channel), and return only once the platform has had
         *  time to REJECT it — AdbDebuggingManager clears it again on its own
         *  thread when there is no Wi-Fi or the network is not trusted. */
        fun enableWirelessDebugging()

        /** A real round trip over the embedded channel, not connection state:
         *  a socket left over from the previous network still reads as
         *  connected and answers nothing. */
        fun channelAnswers(): Boolean

        /** Close the embedded client so the next connect starts clean. */
        fun dropChannel()

        /** mDNS discovery of the new adbd port + connect with the stored keys. */
        fun reconnect(): Pair<Boolean, String>
    }

    /**
     * @param ready the shell channel answered at the end of this tick.
     * @param needsOwner Wireless Debugging is off and nothing this app holds
     *   can turn it on — the owner has to; recorded as status only, no notification.
     */
    data class Outcome(
        val ready: Boolean,
        val rearmed: Boolean,
        val reconnected: Boolean,
        val needsOwner: Boolean,
        val cause: String,
    )

    const val CAUSE_OFF = "keep-alive is off"
    const val CAUSE_NO_WIFI = "waiting for Wi-Fi"
    const val CAUSE_REJECTED = "Wireless debugging is off and could not be turned back on " +
        "(this Wi-Fi network is not allowed for debugging yet, or this app lacks WRITE_SECURE_SETTINGS)"

    fun tick(keepAliveOn: Boolean, d: Device): Outcome {
        // OFF means hands off: not a read-modify-write, not a reconnect.
        if (!keepAliveOn) return Outcome(false, false, false, false, CAUSE_OFF)

        var rearmed = false
        if (!d.wirelessDebuggingOn()) {
            if (!d.onWifi()) return Outcome(false, false, false, false, CAUSE_NO_WIFI)
            d.enableWirelessDebugging()
            if (!d.wirelessDebuggingOn()) return Outcome(false, false, false, true, CAUSE_REJECTED)
            rearmed = true
        }
        if (d.channelAnswers()) return Outcome(true, rearmed, false, false, "")
        d.dropChannel()
        val (ok, msg) = d.reconnect()
        return Outcome(ok, rearmed, ok, false, if (ok) "" else msg)
    }

    enum class Trigger { BOOT, PERIODIC, NETWORK_AVAILABLE, SETTING_CLEARED, SETTING_ON, SWITCHED_ON }

    /**
     * Whether an event should run a tick. The one event that must NOT is the
     * setting being cleared right after a re-arm the platform already
     * rejected: on a network the owner has not allowed, every write of 1 pops
     * Android's "allow on this network?" prompt and is immediately cleared
     * again, so re-arming on that very event would loop prompt-after-prompt.
     * A new network, the periodic pass, boot and the switch still try.
     */
    fun shouldTick(trigger: Trigger, lastNeedsOwner: Boolean): Boolean =
        !(trigger == Trigger.SETTING_CLEARED && lastNeedsOwner)
}
