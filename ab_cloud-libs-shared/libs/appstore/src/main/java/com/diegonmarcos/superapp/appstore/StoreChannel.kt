package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.shellclient.AdbLink
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

    /** #1550 [adb] = the adb transport (AdbLink), which alone decides the chip's colour; [state] stays the broker's (gates shell work). */
    data class ChannelStatus(val state: Channel, val via: String? = null, val reason: String? = null, val checkedAt: Long = 0L,
                             val adb: AdbLink = AdbLink.UNKNOWN, val adbDetail: String = "") {
        /** " HH:mm:ss" of the live check, or "". */
        val at: String get() = if (checkedAt > 0) " (checked " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(checkedAt)) + ")" else ""
        /** The broker executes right now (gates shell work, never the colour). */
        val shellUsable: Boolean get() = state == Channel.UP
    }

    /**
     * #1556 A DEFINITE "cannot reach the broker" status, so the Store chip never sits on grey "checking" when the probe threw or
     * timed out. state NOT_PAIRED makes [ink] RED (it is checked before the adb colour), so it is never read as "still checking".
     */
    fun unreachable(reason: String): ChannelStatus =
        ChannelStatus(Channel.NOT_PAIRED, null, reason, System.currentTimeMillis(), AdbLink.UNKNOWN, "")

    fun fromBroker(s: ShellStatus): ChannelStatus = when {
        !s.brokerInstalled -> ChannelStatus(Channel.NOT_PAIRED, null, s.reason.ifBlank { "Cloud C3 Webserver (the ADB owner) is not installed" }, s.checkedAt)
        s.armed -> ChannelStatus(Channel.UP, s.channel, null, s.checkedAt, s.adb, s.adbDetail)
        else -> ChannelStatus(Channel.DOWN, null, s.reason.ifBlank { s.detail }.ifBlank { "not armed" }, s.checkedAt, s.adb, s.adbDetail)
    }

    /** #1550 The chip's colour: green ONLY when adb is connected; red when the broker is missing; amber otherwise; grey unknown. */
    enum class Ink { GREEN, AMBER, RED, GREY }

    fun ink(s: ChannelStatus?): Ink = when {
        s == null -> Ink.GREY
        s.state == Channel.NOT_PAIRED -> Ink.RED
        s.adb.up -> Ink.GREEN
        s.adb == AdbLink.UNKNOWN -> Ink.GREY
        else -> Ink.AMBER
    }

    /** The chip's one line: green says the route, the others say why and what a tap does. */
    private fun adbPart(s: ChannelStatus): String = s.adb.label + if (s.adbDetail.isNotBlank()) " (${s.adbDetail})" else ""

    fun label(s: ChannelStatus): String = when (s.state) {
        Channel.UP -> "${adbPart(s)} · Shell: Cloud C3 Webserver (${s.via ?: "armed"})${s.at}"
        Channel.DOWN -> "${adbPart(s)} · Shell broker: ${s.reason ?: "not armed"} - Arm in Cloud C3 Webserver${s.at}"
        Channel.NOT_PAIRED -> "Shell broker: ${s.reason ?: "Cloud C3 Webserver missing"}${s.at}"
    }
}
