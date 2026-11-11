package com.diegonmarcos.superapp.adbdebug

/** The buttons of the "Active connection" section. */
enum class ChannelAction(val label: String) {
    DISCONNECT("Disconnect"),
    RECONNECT("Reconnect"),
    RESTART_SERVER("Restart server"),
    STOP_SERVER("Stop server"),
    TEST("Test round-trip"),
}

/** Whether a button is live, and when it is not, the reason it shows. */
data class Enablement(val enabled: Boolean, val why: String? = null)

/** Which buttons make sense in the current state: a pure function of [ChannelFacts]. */
object ChannelActions {

    private fun no(why: String) = Enablement(false, why)
    private val yes = Enablement(true)

    fun enablement(f: ChannelFacts): Map<ChannelAction, Enablement> {
        if (ChannelState.connState(f) == ConnState.CONNECTING) {
            val busy = no("a connect is running")
            return ChannelAction.values().associateWith { busy }
        }
        val usesServer = f.ownsServer && (f.mode == ChannelMode.LOCAL_SERVER || f.mode == ChannelMode.AUTO)
        return mapOf(
            ChannelAction.DISCONNECT to when {
                f.adbConnected -> yes
                f.mode == ChannelMode.SHIZUKU -> no("Shizuku is stopped from the Shizuku app")
                else -> no("embedded adb is not connected")
            },
            ChannelAction.RECONNECT to yes,
            ChannelAction.RESTART_SERVER to when {
                !f.ownsServer -> no("this app runs no server of its own")
                !usesServer -> no("${f.mode.title()} mode does not use the local server")
                // The launch line goes over the mode's OWN channel: Local server mode never reaches for Shizuku.
                !ChannelState.canLaunchServer(f) ->
                    no(if (f.mode == ChannelMode.AUTO) "no adb or Shizuku session to start it with" else "no adb session to start it with")
                else -> yes
            },
            ChannelAction.STOP_SERVER to when {
                !f.ownsServer -> no("this app runs no server of its own")
                f.serverRunning -> yes
                else -> no("the local server is not running")
            },
            ChannelAction.TEST to when {
                ChannelState.route(f) != null -> yes
                else -> no("no route to test")
            },
        )
    }
}
