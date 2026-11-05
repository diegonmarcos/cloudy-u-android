package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.shellclient.ShellStatus

/**
 * #1512 What the Store bar's two device controls SAY, as pure rules (no Android): the Play Protect reading and the shell broker's
 * state. The broker (SuperApp) owns the channel, so there is nothing to pair or reconnect here: the chip reports it and a tap opens
 * the SuperApp's "Arm" flow.
 */
internal object ControlStatus {

    /** A reading the device may not give: UNKNOWN is a state, not a default. */
    enum class Tri { ON, OFF, UNKNOWN }

    /** The three verifier values as the device STORES them; null = no such key (or unreadable). */
    data class Reading(val consent: Int?, val enable: Int?, val adb: Int?)

    /** `settings get` prints "null" for an absent key; anything non-numeric is no answer. */
    fun parseSetting(out: String?): Int? = out?.trim()?.toIntOrNull()

    /** Play Protect's install scan. Any key still arming it reads ON; none readable at all reads UNKNOWN. */
    fun playProtect(r: Reading): Tri {
        if (listOf(r.consent, r.enable, r.adb).all { it == null }) return Tri.UNKNOWN
        val armed = (r.consent != null && r.consent >= 0) || (r.enable != null && r.enable != 0) || (r.adb != null && r.adb != 0)
        return if (armed) Tri.ON else Tri.OFF
    }

    /** UP = the broker is armed; DOWN = reachable but not armed (arm it in the SuperApp); NOT_PAIRED = no broker (SuperApp missing). */
    enum class Channel { UP, DOWN, NOT_PAIRED }

    data class ChannelStatus(val state: Channel, val via: String? = null, val reason: String? = null, val checkedAt: Long = 0L) {
        /** " HH:mm:ss" of the live check, or "". */
        val at: String get() = if (checkedAt > 0) " (checked " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(checkedAt)) + ")" else ""
        /** The broker executes right now (gates shell work, never the colour). */
        val shellUsable: Boolean get() = state == Channel.UP
    }

    fun fromBroker(s: ShellStatus): ChannelStatus = when {
        !s.brokerInstalled -> ChannelStatus(Channel.NOT_PAIRED, null, s.reason.ifBlank { "SuperApp (the shell broker) is not installed" }, s.checkedAt)
        s.armed -> ChannelStatus(Channel.UP, s.channel, null, s.checkedAt)
        else -> ChannelStatus(Channel.DOWN, null, s.reason.ifBlank { s.detail }.ifBlank { "not armed" }, s.checkedAt)
    }

    /** The chip's one line: green says the route, the others say why and what a tap does. */
    fun label(s: ChannelStatus): String = when (s.state) {
        Channel.UP -> "Shell: SuperApp broker (${s.via ?: "armed"})${s.at}"
        Channel.DOWN -> "Shell broker: ${s.reason ?: "not armed"} - Arm in SuperApp${s.at}"
        Channel.NOT_PAIRED -> "Shell broker: ${s.reason ?: "SuperApp missing"}${s.at}"
    }
}
