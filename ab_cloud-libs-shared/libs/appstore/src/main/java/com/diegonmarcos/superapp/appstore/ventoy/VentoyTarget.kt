package com.diegonmarcos.superapp.appstore.ventoy

import com.diegonmarcos.superapp.usbdrive.UsbVolumeModel

/**
 * #1405 the one rule every Ventoy write passes: the target is a REMOVABLE drive the user plugged in, never the phone's
 * own storage. The drive picker lists only what [check] accepts, [SafDriveFs] refuses a tree on the primary volume,
 * and [VentoyGate] will not arm on anything else, so internal storage is not a target by any path.
 */
object VentoyTarget {
    sealed class Verdict {
        object Ok : Verdict()
        data class Refused(val why: String) : Verdict()
    }

    /** Paths that are the phone's own storage, whatever a vendor's StorageManager says about them. */
    val INTERNAL_ROOTS = listOf("/storage/emulated", "/sdcard", "/mnt/sdcard", "/storage/self", "/data", "/mnt/user", "/mnt/runtime")

    fun isInternalPath(p: String): Boolean {
        val n = p.trim().trimEnd('/').ifEmpty { "/" }
        return n == "/" || INTERNAL_ROOTS.any { n == it || n.startsWith("$it/") }
    }

    fun check(v: UsbVolumeModel.Volume): Verdict {
        val p = v.path
        return when {
            v.primary -> Verdict.Refused("the phone's own storage")
            !v.removable -> Verdict.Refused("not a removable drive")
            p.isNullOrBlank() -> Verdict.Refused("no mount path")
            isInternalPath(p) -> Verdict.Refused("the phone's own storage ($p)")
            !UsbVolumeModel.isMounted(v) -> Verdict.Refused("not mounted")
            v.state == UsbVolumeModel.STATE_MOUNTED_READ_ONLY -> Verdict.Refused("mounted read-only")
            else -> Verdict.Ok
        }
    }

    /** The drives the picker offers. */
    fun targets(volumes: List<UsbVolumeModel.Volume>): List<UsbVolumeModel.Volume> =
        volumes.filter { check(it) == Verdict.Ok }.distinctBy { it.path }

    /**
     * A SAF tree on the external-storage provider names its volume before the colon: "primary:..." is the phone's own
     * storage, "1A2B-3C4D:" a removable volume. Accepted: the root of a removable volume, or its top-level ISO folder
     * (Android refuses a tree on the ROOT of an SD-card-class volume; images still go to /ISO, persistence needs the root).
     */
    const val ISO_FOLDER = "ISO"
    const val EXTERNAL_AUTHORITY = "com.android.externalstorage.documents"

    fun isDriveTree(authority: String?, treeDocId: String?): Boolean {
        if (authority != EXTERNAL_AUTHORITY || treeDocId.isNullOrBlank()) return false
        val volume = treeDocId.substringBefore(':', "")
        if (volume.isEmpty() || volume.equals("primary", ignoreCase = true) || volume.equals("home", ignoreCase = true)) return false
        return treeDocId.substringAfter(':', "x").let { it.isEmpty() || it == ISO_FOLDER }
    }
}
