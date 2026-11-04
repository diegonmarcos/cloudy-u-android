package com.diegonmarcos.superapp.appstore.ventoy

import com.diegonmarcos.superapp.usbdrive.UsbVolumeModel

/**
 * #1405 the destructive gate. Installing Ventoy onto a drive erases it, so the only way to the raw installer is an
 * [Armed] token, and the only way to get one is [arm]: the drive must pass [VentoyTarget], the owner must have typed
 * the exact phrase (it names the drive), and the dialog must have shown the drive's name, size and [WARNING].
 * [Armed]'s constructor is private, so no other code path can produce one; nothing runs on its own.
 */
object VentoyGate {
    const val WARNING = "ALL DATA WILL BE ERASED"

    data class Drive(val label: String, val sizeBytes: Long, val volume: UsbVolumeModel.Volume)

    fun phrase(d: Drive) = "ERASE ${d.label.trim()}"

    /** What the dialog shows: the drive, its size and the warning, then what to type. */
    fun prompt(d: Drive) = "${d.label.trim()} (${UsbVolumeModel.human(d.sizeBytes)}): $WARNING. Type \"${phrase(d)}\" to continue."

    class Armed private constructor(val drive: Drive) {
        companion object {
            internal fun of(d: Drive) = Armed(d)
        }
    }

    /** An [Armed] token, or null. [shown] is the text the dialog displayed: it must be [prompt] of this drive. */
    fun arm(d: Drive, typed: String, shown: String): Armed? = when {
        VentoyTarget.check(d.volume) != VentoyTarget.Verdict.Ok -> null
        d.label.isBlank() || d.sizeBytes <= 0 -> null
        shown != prompt(d) -> null
        typed != phrase(d) -> null
        else -> Armed.of(d)
    }
}
