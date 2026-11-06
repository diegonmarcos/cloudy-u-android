package com.diegonmarcos.clouddrive.manage

import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.superapp.usbdrive.UsbVolumeModel
import java.io.File

/**
 * #1537 Manage ▸ Overview, the pure part: every volume with its usage, LOCAL then CLOUD, from the declared sources
 * (build.json ui.manage). No Android in here (the phone's StorageManager, StatFs and rclone arrive through [VolumeProbe] and a
 * lambda), so the JVM suite drives it with fake data. A volume that cannot be read is a row with `unavailable` = the reason
 * (n/a plus why), never a missing row and never a zero.
 */
data class VolumeUsage(
    val id: String,
    val label: String,
    val icon: String,
    val cloud: Boolean,
    val used: Long? = null,
    val free: Long? = null,
    val total: Long? = null,
    val path: String? = null,
    /** Non-null = n/a, and this is the reason. */
    val unavailable: String? = null,
) {
    /** The bar needs a known total. */
    val hasBar: Boolean get() = unavailable == null && (total ?: 0L) > 0L && (used != null || free != null)
    val fraction: Float get() = if (!hasBar) 0f else (((used ?: ((total ?: 0L) - (free ?: 0L))).toDouble() / total!!.toDouble()).toFloat()).coerceIn(0f, 1f)
}

/** One mounted external volume as StorageManager reports it. */
data class ExternalDrive(val id: String, val label: String, val path: String, val total: Long, val free: Long)

/** What the phone answers: the folders behind the declared kinds, the drives, and a folder's (free, total). */
interface VolumeProbe {
    fun emulated(): File?
    fun terminalHome(): File?
    /** `relative` under the data dir of the fleet app [fleet]; null when that app is not on this device. */
    fun appHome(fleet: String, relative: String): File?
    fun externalDrives(): List<ExternalDrive>
    /** (free, total) of the file system holding [dir]; null when unreadable. */
    fun stat(dir: File): Pair<Long, Long>?
}

/** `rclone about`: a field the backend does not report is null. */
data class CloudAbout(val used: Long?, val total: Long?, val free: Long?)

/** A cloud row before its usage is known (rclone about is slow, the page fills the rows in one by one). */
data class CloudTarget(val id: String, val label: String, val icon: String, val remote: String, val declared: Declarations.RemoteDecl?)

object VolumeOverview {
    const val KIND_EMULATED = "emulated"
    const val KIND_TERMINAL_HOME = "terminal_home"
    const val KIND_APP_HOME = "app_home"
    const val KIND_EXTERNAL = "external_drives"
    const val KIND_REMOTE = "remote"
    const val KIND_MOUNTS = "mount_providers"

    /** The external volumes of a StorageManager listing: removable, not the phone's own, mounted with a path (UsbVolumeModel's rule), SD or USB, USB first. */
    fun externalFrom(volumes: List<UsbVolumeModel.Volume>): List<ExternalDrive> =
        volumes.filter(UsbVolumeModel::isExternalDrive)
            .sortedWith(compareBy({ UsbVolumeModel.kindOf(it) != UsbVolumeModel.Kind.USB }, { UsbVolumeModel.labelOf(it).lowercase() }))
            .map { v -> ExternalDrive(v.uuid ?: v.path.orEmpty(), UsbVolumeModel.labelOf(v), v.path.orEmpty(), v.totalBytes, v.freeBytes) }

    /** The folders a local source stands for (empty when it has none here); an `external_drives` source stands for every drive. */
    fun rootsOf(s: Declarations.ManageSource, probe: VolumeProbe): List<File> = when (s.kind) {
        KIND_EMULATED -> listOfNotNull(probe.emulated())
        KIND_TERMINAL_HOME -> listOfNotNull(probe.terminalHome())
        KIND_APP_HOME -> listOfNotNull(probe.appHome(s.fleet, s.relative))
        KIND_EXTERNAL -> probe.externalDrives().map { File(it.path) }
        else -> emptyList()
    }

    fun local(sources: List<Declarations.ManageSource>, probe: VolumeProbe): List<VolumeUsage> = sources.flatMap { s ->
        when (s.kind) {
            KIND_EMULATED -> listOf(dirRow(s, probe.emulated(), probe, "internal shared storage is not available"))
            KIND_TERMINAL_HOME -> listOf(dirRow(s, probe.terminalHome(), probe, "the terminal's home is not reachable (terminal not installed, or the shared uid is not in effect)"))
            KIND_APP_HOME -> listOf(dirRow(s, probe.appHome(s.fleet, s.relative), probe, "${s.fleet} is not installed or its home is not readable"))
            KIND_EXTERNAL -> {
                val drives = probe.externalDrives()
                if (drives.isEmpty()) listOf(VolumeUsage(s.id, s.label, s.icon, false, unavailable = "no external drive is mounted"))
                else drives.map { d ->
                    if (d.total <= 0) VolumeUsage("${s.id}:${d.id}", d.label, s.icon, false, path = d.path, unavailable = "the volume reports no size")
                    else VolumeUsage("${s.id}:${d.id}", d.label, s.icon, false, used = d.total - d.free.coerceIn(0, d.total), free = d.free.coerceIn(0, d.total), total = d.total, path = d.path)
                }
            }
            else -> emptyList()
        }
    }

    private fun dirRow(s: Declarations.ManageSource, dir: File?, probe: VolumeProbe, missing: String): VolumeUsage {
        if (dir == null || !dir.isDirectory) return VolumeUsage(s.id, s.label, s.icon, false, path = dir?.path, unavailable = missing)
        val st = probe.stat(dir) ?: return VolumeUsage(s.id, s.label, s.icon, false, path = dir.path, unavailable = "usage of ${dir.path} is unreadable")
        val (free, total) = st
        if (total <= 0) return VolumeUsage(s.id, s.label, s.icon, false, path = dir.path, unavailable = "the volume reports no size")
        return VolumeUsage(s.id, s.label, s.icon, false, used = total - free.coerceIn(0, total), free = free.coerceIn(0, total), total = total, path = dir.path)
    }

    /** The cloud rows: a `remote` source is one row, `mount_providers` adds every other provider of Mounts whose remote is not already a row. */
    fun cloudTargets(sources: List<Declarations.ManageSource>, remotes: List<Declarations.RemoteDecl>, providers: List<Declarations.MountProviderDecl>): List<CloudTarget> {
        val out = ArrayList<CloudTarget>()
        for (s in sources) when (s.kind) {
            KIND_REMOTE -> out += CloudTarget(s.id, s.label, s.icon, s.remote, remotes.firstOrNull { it.name == s.remote })
            KIND_MOUNTS -> providers.filter { !it.others && it.remote.isNotBlank() && out.none { t -> t.remote == it.remote } }
                .forEach { p -> out += CloudTarget("${s.id}:${p.id}", p.label, p.icon.ifBlank { s.icon }, p.remote, remotes.firstOrNull { it.name == p.remote }) }
        }
        return out
    }

    /** A cloud row's usage. A remote declared unreachable shows its declared reason without asking; otherwise [about] answers, and its failure is the reason. */
    fun cloudUsage(t: CloudTarget, about: (String) -> Result<CloudAbout>): VolumeUsage {
        val d = t.declared
        if (d != null && d.status == "unreachable") return VolumeUsage(t.id, t.label, t.icon, true, unavailable = d.reason.ifBlank { "declared unreachable" })
        return about(t.remote).fold(
            { a ->
                if (a.used == null && a.total == null && a.free == null) VolumeUsage(t.id, t.label, t.icon, true, unavailable = "this backend reports no usage")
                else VolumeUsage(t.id, t.label, t.icon, true, used = a.used ?: if (a.total != null && a.free != null) a.total - a.free else null, free = a.free, total = a.total)
            },
            { VolumeUsage(t.id, t.label, t.icon, true, unavailable = it.message?.lineSequence()?.firstOrNull { l -> l.isNotBlank() } ?: "rclone failed") },
        )
    }
}
