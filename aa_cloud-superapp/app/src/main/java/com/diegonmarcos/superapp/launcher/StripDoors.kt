package com.diegonmarcos.superapp.launcher

import com.diegonmarcos.superapp.network.LocalDoors
import com.diegonmarcos.superapp.network.UsbDataModel

/** #1684 the WebS / SSHd / TypC indicators' state -> tint mapping, pure so a JVM test pins on / off / unknown. */
internal object StripDoors {
    /** Not checked yet, or the build declared no port: neither green nor "down". */
    const val UNKNOWN_TINT: Int = 0x55FFFFFF
    /** A cable that charges but moves no data. */
    const val CHARGE_TINT: Int = 0xFFFBBF24.toInt()

    fun websState(p: LocalDoors.Probe?): LocalDoors.State = p?.state ?: LocalDoors.State.UNKNOWN
    fun sshdState(ps: List<LocalDoors.Probe>): LocalDoors.State = LocalDoors.combine(ps.map { it.state })

    fun tint(s: LocalDoors.State, on: Int, off: Int): Int = when (s) {
        LocalDoors.State.ON -> on; LocalDoors.State.OFF -> off; LocalDoors.State.UNKNOWN -> UNKNOWN_TINT
    }

    /** The one dot under WebS / SSHd: filled = up, outline = down, faint outline = not checked (ADB's dot style, #1721). */
    fun dot(s: LocalDoors.State): Int = AdbPeerDots.tint(when (s) { LocalDoors.State.ON -> true; LocalDoors.State.OFF -> false; LocalDoors.State.UNKNOWN -> null })

    fun describe(s: LocalDoors.State, ps: List<LocalDoors.Probe>): String =
        s.name.lowercase() + ps.joinToString("") { "; ${it.door.title} :${it.door.port ?: "?"} ${it.detail}" }

    fun cableTint(c: UsbDataModel.Cable, on: Int, off: Int): Int = when (c) {
        UsbDataModel.Cable.DATA -> on; UsbDataModel.Cable.CHARGE -> CHARGE_TINT; UsbDataModel.Cable.NONE -> off
    }
}
