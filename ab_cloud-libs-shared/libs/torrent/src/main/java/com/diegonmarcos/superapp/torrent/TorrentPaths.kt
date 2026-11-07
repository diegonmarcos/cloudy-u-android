package com.diegonmarcos.superapp.torrent

import java.io.File

/**
 * Where torrents are saved. The default is `Downloads/Cloudy-store/Torrent` in shared storage; a user-chosen
 * folder (an external drive's mount, any folder the app can write) replaces it. Pure JVM: the Android roots are
 * handed in by [TorrentSettings.forContext], so the rule is tested without a device.
 */
object TorrentPaths {
    private val VOLUME_ID = Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}|[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")

    /** The default folder below the public Downloads directory. */
    const val DEFAULT_RELATIVE = "Cloudy-store/Torrent"

    fun defaultDir(downloads: File): File = File(downloads, DEFAULT_RELATIVE)

    /** The outcome of preparing a folder: [dir] when [ok], else [why] in words. */
    class Prepared(val dir: File, val ok: Boolean, val why: String?)

    /** Creates [dir] when missing and proves it is writable. Never throws: a folder the app cannot use is a sentence. */
    fun prepare(dir: File): Prepared {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            return Prepared(dir, false, "Cannot create ${dir.path} (storage access not granted, or the drive is not mounted).")
        }
        return if (dir.canWrite()) Prepared(dir, true, null) else Prepared(dir, false, "${dir.path} is not writable.")
    }

    /** Free bytes at [dir] (or its nearest existing parent), -1 when unknown. */
    fun freeBytes(dir: File): Long {
        var d: File? = dir
        while (d != null && !d.exists()) d = d.parentFile
        return d?.usableSpace ?: -1L
    }

    /** Shared-storage volumes a user can pick as a download root, from the app-specific dirs Android lists per volume
     *  (`getExternalFilesDirs`): `/storage/XXXX-XXXX/Android/data/<pkg>/files` is trimmed to the volume root `/storage/XXXX-XXXX`. */
    fun volumeRoots(externalFilesDirs: List<File?>): List<File> =
        externalFilesDirs.filterNotNull().mapNotNull { f ->
            val p = f.path.substringBefore("/Android/", "")
            p.takeIf { it.isNotEmpty() }?.let(::File)
        }.distinct()

    /** Whether [dir] lies on a removable / external volume, i.e. under /storage/ but not the primary (`emulated`) one. */
    fun isExternalVolume(dir: File): Boolean {
        val p = dir.path
        return p.startsWith("/storage/") && !p.startsWith("/storage/emulated/") && !p.startsWith("/storage/self/")
    }

    /**
     * The file path of a folder picked with the system tree picker (a document-tree id such as `primary:Download/Torrents` or
     * `1A2B-3C4D:Torrents`), or null for a provider that is not a storage volume (Drive, Downloads provider...). libtorrent needs
     * a real path, so only volumes map: `primary` is the internal storage, anything else is `/storage/<volume id>`.
     */
    fun pathFromTreeDocId(docId: String, primaryRoot: File = File("/storage/emulated/0")): File? {
        val volume = docId.substringBefore(':', "")
        val rel = docId.substringAfter(':', "").trim('/')
        if (volume.isEmpty() || docId.indexOf(':') < 0) return null
        // A storage volume is `primary`, a FAT/exFAT serial (1A2B-3C4D) or a UUID; any other authority's ids (msf:1234, raw:/...) are not paths.
        if (!volume.equals("primary", true) && !VOLUME_ID.matches(volume)) return null
        if (rel.split('/').any { it == ".." }) return null
        val root = if (volume.equals("primary", true)) primaryRoot else File("/storage/$volume")
        return if (rel.isEmpty()) root else File(root, rel)
    }
}
