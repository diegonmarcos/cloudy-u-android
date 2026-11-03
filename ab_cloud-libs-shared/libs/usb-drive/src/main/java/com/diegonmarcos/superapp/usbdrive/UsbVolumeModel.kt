package com.diegonmarcos.superapp.usbdrive

/**
 * #1375 USB-C drives, the PURE part: no Android, no IO, so the JVM suite (UsbVolumeModelTest) proves every
 * rule and drive-usb-mutation.yml proves the suite catches a break in each one.
 *
 * Two ways a drive reaches Cloud Drive:
 *  1. Android mounts it (most phones do for FAT32/exFAT, some for NTFS): it is a StorageVolume, read by
 *     [StorageVolumes] into [Volume] rows, mapped here into Explorer places with a label and a size.
 *  2. Android does not mount it: the raw USB device (mass-storage class 8) is opened by libaums
 *     ([RawUsbDrive], FAT32) after the per-device USB permission (libs:perms PermsUsb).
 */
object UsbVolumeModel {

    /** One storage volume as Android reports it (StorageVolume + StatFs), flattened to plain values. */
    data class Volume(
        val uuid: String?,
        val description: String,
        val path: String?,
        val state: String,
        val removable: Boolean,
        val primary: Boolean,
        val totalBytes: Long = 0,
        val freeBytes: Long = 0,
    )

    enum class Kind { USB, SD_CARD }

    /** A drive as Explorer shows it: a place with its label, kind, size line and root path. */
    data class Drive(val id: String, val label: String, val kind: Kind, val path: String, val sizeHint: String, val readOnly: Boolean)

    /** Environment.MEDIA_* states, spelled here so the model stays free of android.jar. */
    const val STATE_MOUNTED = "mounted"
    const val STATE_MOUNTED_READ_ONLY = "mounted_ro"

    fun isMounted(v: Volume): Boolean = v.state == STATE_MOUNTED || v.state == STATE_MOUNTED_READ_ONLY

    /** A drive the user plugged in or inserted: removable, not the phone's own storage, mounted with a path. */
    fun isExternalDrive(v: Volume): Boolean = v.removable && !v.primary && isMounted(v) && !v.path.isNullOrBlank()

    /**
     * USB or SD card. Android names USB volumes in their description ("SanDisk USB drive", "USB storage") and
     * older vendors mount them under /storage/usb*; anything else removable is an SD card.
     */
    fun kindOf(v: Volume): Kind {
        val d = v.description.lowercase()
        val p = v.path.orEmpty().lowercase()
        return if ("usb" in d || p.startsWith("/storage/usb") || p.startsWith("/mnt/media_rw/usb")) Kind.USB else Kind.SD_CARD
    }

    /** The volume's own label, else its UUID, else a word for its kind. */
    fun labelOf(v: Volume): String = v.description.trim().ifEmpty { v.uuid?.trim().orEmpty() }.ifEmpty {
        if (kindOf(v) == Kind.USB) "USB drive" else "SD card"
    }

    /** "12.3 GB free of 31.9 GB"; blank when the size is unknown. SI units, like Android's own Formatter. */
    fun sizeHint(total: Long, free: Long): String =
        if (total <= 0) "" else "${human(free.coerceIn(0, total))} free of ${human(total)}"

    fun human(bytes: Long): String {
        if (bytes < 1000) return "$bytes B"
        val units = listOf("kB", "MB", "GB", "TB")
        var v = bytes.toDouble() / 1000
        var i = 0
        while (v >= 1000 && i < units.lastIndex) { v /= 1000; i++ }
        val s = if (v >= 100) String.format(java.util.Locale.ROOT, "%.0f", v) else String.format(java.util.Locale.ROOT, "%.1f", v)
        return "$s ${units[i]}"
    }

    /** Explorer's drives: every external drive, USB first (the one just plugged in is what the user came for), then by label. */
    fun drives(volumes: List<Volume>): List<Drive> = volumes.filter(::isExternalDrive)
        .distinctBy { it.path }
        .map { v -> Drive("vol-" + v.path, labelOf(v), kindOf(v), v.path!!, sizeHint(v.totalBytes, v.freeBytes), v.state == STATE_MOUNTED_READ_ONLY) }
        .sortedWith(compareBy<Drive>({ if (it.kind == Kind.USB) 0 else 1 }, { it.label.lowercase() }))

    /**
     * The drive to open after an attach: a USB drive that is mounted now and was not before, else the only USB drive,
     * else null (the caller then waits, or opens the raw device).
     */
    fun toOpen(before: Set<String>, now: List<Volume>): Drive? {
        val usb = drives(now).filter { it.kind == Kind.USB }
        return usb.firstOrNull { it.path !in before } ?: usb.singleOrNull()
    }

    /** USB mass storage: class 8 on the device, or on any interface (most drives say 0 on the device, 8 on the interface). */
    const val USB_CLASS_MASS_STORAGE = 8

    fun isMassStorage(deviceClass: Int, interfaceClasses: List<Int>): Boolean =
        deviceClass == USB_CLASS_MASS_STORAGE || interfaceClasses.any { it == USB_CLASS_MASS_STORAGE }

    // ── detach: what the drive card says ────────────────────────────────────────────────────────

    enum class DriveState { MOUNTED, EJECTING, SAFE_TO_REMOVE, REMOVED, REMOVED_UNSAFELY }

    const val ACTION_MEDIA_MOUNTED = "android.intent.action.MEDIA_MOUNTED"
    const val ACTION_MEDIA_EJECT = "android.intent.action.MEDIA_EJECT"
    const val ACTION_MEDIA_UNMOUNTED = "android.intent.action.MEDIA_UNMOUNTED"
    const val ACTION_MEDIA_REMOVED = "android.intent.action.MEDIA_REMOVED"
    const val ACTION_MEDIA_BAD_REMOVAL = "android.intent.action.MEDIA_BAD_REMOVAL"
    const val ACTION_USB_DEVICE_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED"

    /** Every broadcast the manifest receiver answers (UsbVolumeReceiver; test-drive-usb-drives.sh diffs it against the manifest). */
    val MEDIA_ACTIONS = listOf(ACTION_MEDIA_MOUNTED, ACTION_MEDIA_EJECT, ACTION_MEDIA_UNMOUNTED, ACTION_MEDIA_REMOVED, ACTION_MEDIA_BAD_REMOVAL)

    /**
     * The state after a broadcast. EJECT = Android is letting go (streams must close now); UNMOUNTED after that is the
     * moment the drive is safe to pull; REMOVED is gone; BAD_REMOVAL is gone while still mounted (the user was too fast).
     * A raw USB detach while our own session was open is the same as a bad removal unless the session was closed first.
     */
    fun next(current: DriveState, action: String, sessionClosed: Boolean = false): DriveState = when (action) {
        ACTION_MEDIA_MOUNTED -> DriveState.MOUNTED
        ACTION_MEDIA_EJECT -> DriveState.EJECTING
        ACTION_MEDIA_UNMOUNTED -> DriveState.SAFE_TO_REMOVE
        ACTION_MEDIA_REMOVED -> if (current == DriveState.SAFE_TO_REMOVE || current == DriveState.EJECTING) DriveState.REMOVED else DriveState.REMOVED_UNSAFELY
        ACTION_MEDIA_BAD_REMOVAL -> DriveState.REMOVED_UNSAFELY
        ACTION_USB_DEVICE_DETACHED -> if (sessionClosed || current == DriveState.SAFE_TO_REMOVE) DriveState.REMOVED else DriveState.REMOVED_UNSAFELY
        else -> current
    }

    /** Streams must be closed on these: Android is taking the drive away. */
    fun mustClose(action: String): Boolean = action == ACTION_MEDIA_EJECT || action == ACTION_MEDIA_UNMOUNTED ||
        action == ACTION_MEDIA_REMOVED || action == ACTION_MEDIA_BAD_REMOVAL || action == ACTION_USB_DEVICE_DETACHED

    /** The file:///storage/XXXX-XXXX data of a media broadcast, as a path. */
    fun pathOfMediaUri(uri: String?): String? = uri?.takeIf { it.startsWith("file://") }?.removePrefix("file://")?.trimEnd('/')?.ifEmpty { null }

    // ── raw browsing (libaums) path model ───────────────────────────────────────────────────────

    /** "/a/b" → "/a"; "/" stays "/". Raw USB paths are absolute inside the drive's file system. */
    fun rawParent(path: String): String {
        val t = path.trimEnd('/')
        val i = t.lastIndexOf('/')
        return if (i <= 0) "/" else t.substring(0, i)
    }

    fun rawChild(path: String, name: String): String {
        require(name.isNotEmpty() && '/' !in name && name != "." && name != "..") { "bad name: $name" }
        return if (path == "/" || path.isEmpty()) "/$name" else path.trimEnd('/') + "/" + name
    }

    /** A copied file never overwrites: "a.txt" → "a (1).txt" → "a (2).txt" while [exists]. */
    fun freeName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot); val ext = name.substring(dot)
        var n = 1
        while (exists("$base ($n)$ext")) n++
        return "$base ($n)$ext"
    }
}
