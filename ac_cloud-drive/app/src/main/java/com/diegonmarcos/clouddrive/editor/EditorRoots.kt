package com.diegonmarcos.clouddrive.editor

import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.manage.ExternalDrive
import com.diegonmarcos.clouddrive.manage.VolumeOverview
import com.diegonmarcos.clouddrive.manage.VolumeProbe
import java.io.File

/**
 * #1714 the Editor's file explorer roots, PURE Kotlin (EditorRootsTest drives it with fakes). The roots are the declared local
 * volume sources of Manage (build.json ui.manage.overview.local, switcher order: Emulated, Termux, Nix, External); the phone's
 * answers arrive through [VolumeProbe] (the same probe Manage uses) and lambdas, so nothing here touches Android or the disk.
 * A root this app cannot list is NOT dropped: it stays in the selector with a [Reason] the panel prints beside its grant action.
 */
object EditorRoots {
    /** Why a root cannot be browsed; the panel maps each to a sentence and an action. */
    enum class Reason { NO_STORAGE_GRANT, APP_NOT_INSTALLED, NOT_READABLE, NO_DRIVE }

    /** One browsable place: a root with its folder(s), or a root with a [reason]. External carries one [Place] per mounted drive. */
    data class Place(val label: String, val path: String)
    data class Root(val id: String, val label: String, val icon: String, val places: List<Place>, val reason: Reason?, val fleet: String = "") {
        val accessible: Boolean get() = reason == null && places.isNotEmpty()
    }

    /** What the phone answers beyond the probe. */
    interface Access {
        /** All-files access (or the legacy storage permission) is granted. */
        val hasAllFiles: Boolean
        /** A real list attempt on [path] (FilesController.canList). */
        fun canList(path: String): Boolean
        /** The fleet app [fleet] is installed (null = the terminal that owns the shared store). */
        fun installed(fleet: String?): Boolean
    }

    /** The roots in the declared switcher order; a switcher id with no declared source is skipped, an empty declaration yields no roots. */
    fun roots(decl: Declarations.ManageDecl, probe: VolumeProbe, access: Access): List<Root> {
        val byId = decl.local.associateBy { it.id }
        return decl.switcher.mapNotNull { byId[it] }.mapNotNull { s -> runCatching { root(s, probe, access) }.getOrNull() }
    }

    private fun root(s: Declarations.ManageSource, probe: VolumeProbe, access: Access): Root? = when (s.kind) {
        VolumeOverview.KIND_EMULATED -> dirRoot(s, probe.emulated(), access, needsGrant = true, missing = Reason.NOT_READABLE)
        VolumeOverview.KIND_TERMINAL_HOME -> dirRoot(s, probe.terminalHome(), access, needsGrant = false, missing = if (access.installed(null)) Reason.NOT_READABLE else Reason.APP_NOT_INSTALLED)
        VolumeOverview.KIND_APP_HOME -> dirRoot(s, probe.appHome(s.fleet, s.relative), access, needsGrant = false, missing = if (access.installed(s.fleet)) Reason.NOT_READABLE else Reason.APP_NOT_INSTALLED, fleet = s.fleet)
        VolumeOverview.KIND_EXTERNAL -> external(s, probe.externalDrives(), access)
        else -> null
    }

    private fun dirRoot(s: Declarations.ManageSource, dir: File?, access: Access, needsGrant: Boolean, missing: Reason, fleet: String = ""): Root {
        val none = Root(s.id, s.label, s.icon, emptyList(), null, fleet)
        if (dir == null) return none.copy(reason = missing)
        if (needsGrant && !access.hasAllFiles) return none.copy(reason = Reason.NO_STORAGE_GRANT)
        if (!safe { access.canList(dir.path) }) return none.copy(reason = missing)
        return none.copy(places = listOf(Place(s.label, dir.path)))
    }

    private fun external(s: Declarations.ManageSource, drives: List<ExternalDrive>, access: Access): Root {
        val none = Root(s.id, s.label, s.icon, emptyList(), null)
        if (drives.isEmpty()) return none.copy(reason = Reason.NO_DRIVE)
        if (!access.hasAllFiles) return none.copy(reason = Reason.NO_STORAGE_GRANT)
        val listable = drives.filter { d -> d.path.isNotBlank() && safe { access.canList(d.path) } }
        if (listable.isEmpty()) return none.copy(reason = Reason.NOT_READABLE)
        return none.copy(places = listable.map { Place(it.label, it.path) })
    }

    /** The place a root opens at (its first folder), or null when it has none. */
    fun startOf(root: Root, driveIndex: Int = 0): String? = root.places.getOrNull(driveIndex.coerceAtLeast(0))?.path ?: root.places.firstOrNull()?.path

    /** The root a path sits in, or null: the explorer's selector follows the file the Editor already shows. */
    fun rootOfPath(roots: List<Root>, path: String): Root? {
        val p = path.trimEnd('/')
        return roots.firstOrNull { r -> r.places.any { p == it.path.trimEnd('/') || p.startsWith(it.path.trimEnd('/') + "/") } }
    }

    private inline fun safe(f: () -> Boolean): Boolean = try { f() } catch (_: Throwable) { false }
}
