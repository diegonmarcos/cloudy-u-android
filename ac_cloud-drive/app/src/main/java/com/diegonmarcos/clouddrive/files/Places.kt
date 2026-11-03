package com.diegonmarcos.clouddrive.files

import android.content.Context
import android.os.Build
import android.os.Environment
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.SharedStore
import java.io.File

/**
 * #579 the Places sheet's rows: the DECLARED places (build.json::ui.files.places, in
 * order, only those that exist on this device), then what only the device can know —
 * removable volumes, the app's slice on each, the persisted SAF grant, the connect
 * affordance — then the bookmarks. The same rules the pre-redesign `places()` had,
 * as a typed list the sheet renders.
 */
object Places {

    enum class Kind { PATH, TREE, CONNECT, BOOKMARK }

    /** [section] is the declared ui.files.sections id this place sits under; blank for a discovered one. */
    data class Place(val id: String, val label: String, val icon: String, val kind: Kind, val location: Location.Local?, val hero: Boolean = false, val hint: String = "", val section: String = "")

    private val TERMINAL_KINDS = setOf("terminal_home", "store_path")

    fun declared(ctx: Context): List<Place> {
        val external = Environment.getExternalStorageDirectory()
        return Declarations.files.places.mapNotNull { p ->
            val dir: File? = when (p.kind) {
                "shared_root" -> SharedStore.root()
                "external_root" -> external
                "public_dir" -> publicDir(external, p.dir)
                "external_path" -> File(external, p.path)
                // The terminal's filesystem, reached directly through the shared uid (libs:shared-store).
                "terminal_home" -> SharedStore.root().parentFile
                "store_path" -> File(SharedStore.root(), p.path)
                else -> null
            }
            // A place that does not exist is not offered: a tap leading to "folder not found"
            // is worse than the entry simply not being there.
            // The terminal roots stay offered when only CLOUD_EXEC reaches them (ExecListing browses them).
            val viaTerminal = p.kind in TERMINAL_KINDS && com.diegonmarcos.cloudlib.sharedstore.StorePaths.viaOwner(com.diegonmarcos.cloudlib.sharedstore.StoreAccess.mode(ctx))
            if (dir == null || (!dir.isDirectory && !viaTerminal)) return@mapNotNull null
            Place(p.id, p.label, p.icon, Kind.PATH, Location.Local(dir.absolutePath), hero = p.hero, hint = if (p.kind == "shared_root") ctx.getString(R.string.files_store_hint) else "", section = p.section)
        }
    }

    /** Environment.DIRECTORY_<name> by the declared constant name; the declaration never spells the folder. */
    private fun publicDir(external: File, constant: String): File? {
        val dirName = when (constant.uppercase()) {
            "DOWNLOADS" -> Environment.DIRECTORY_DOWNLOADS
            "DOCUMENTS" -> Environment.DIRECTORY_DOCUMENTS
            "PICTURES" -> Environment.DIRECTORY_PICTURES
            "MOVIES" -> Environment.DIRECTORY_MOVIES
            "MUSIC" -> Environment.DIRECTORY_MUSIC
            "DCIM" -> Environment.DIRECTORY_DCIM
            else -> return null
        }
        return File(external, dirName)
    }

    /** Removable volumes, the app's slice on each, the SAF grant, and the connect affordance. */
    fun discovered(ctx: Context, prefs: DrivePrefs.Snapshot, hasAllFiles: Boolean): List<Place> {
        val out = mutableListOf<Place>()
        val appDirs = ctx.getExternalFilesDirs(null).filterNotNull()
        appDirs.drop(1).forEach { dir ->
            if (dir.isDirectory) out += Place("app-" + dir.absolutePath, ctx.getString(R.string.files_place_app_files, volumeLabel(ctx, dir)), "storage", Kind.PATH, Location.Local(dir.absolutePath))
        }
        // #1375 the drives Android mounted (USB-C over OTG, SD cards), from StorageManager: their own label and size,
        // USB first. The /storage scan stays as the fallback for a vendor whose StorageManager reports nothing.
        val drives = com.diegonmarcos.superapp.usbdrive.StorageVolumes.drives(ctx)
        if (hasAllFiles) {
            drives.forEach { d ->
                out += Place(d.id, d.label, if (d.kind == com.diegonmarcos.superapp.usbdrive.UsbVolumeModel.Kind.USB) "usb" else "sd_card", Kind.PATH, Location.Local(d.path), hint = d.sizeHint)
            }
            File("/storage").listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" && drives.none { d -> d.path == it.absolutePath } }?.forEach { volume ->
                out += Place("vol-" + volume.absolutePath, volumeLabel(ctx, volume), if (volume.name.lowercase() == "usb") "usb" else "sd_card", Kind.PATH, Location.Local(volume.absolutePath))
            }
        }
        if (prefs.treeGrantUri != null) {
            out += Place("tree", ctx.getString(R.string.files_place_granted, prefs.treeGrantName ?: ctx.getString(R.string.files_place_sd_card)), "sd_card", Kind.TREE, null)
        }
        val hasRemovablePath = out.any { it.kind == Kind.PATH }
        // Without all-files access a mounted drive is offered by name: the tap asks for exactly that volume (SAF).
        val unreached = drives.firstOrNull().takeIf { !hasAllFiles }
        if (!hasRemovablePath) out += if (unreached != null) Place("connect", ctx.getString(R.string.usb_place_grant, unreached.label), "usb", Kind.CONNECT, null, hint = unreached.sizeHint)
            else Place("connect", ctx.getString(R.string.files_place_connect), "usb", Kind.CONNECT, null)
        return out
    }

    fun bookmarks(prefs: DrivePrefs.Snapshot): List<Place> = prefs.bookmarks.map { path ->
        Place("bm-$path", path.trimEnd('/').substringAfterLast('/').ifEmpty { path }, "bookmark", Kind.BOOKMARK, Location.Local(path), hint = path)
    }

    /** A readable name for the volume a path sits on: the id between /storage/ and the next slash. */
    fun volumeLabel(ctx: Context, dir: File): String {
        val volume = dir.absolutePath.substringAfter("/storage/", "").substringBefore("/", "")
        return when {
            volume.isEmpty() || volume == "emulated" -> ctx.getString(R.string.files_place_removable)
            volume.lowercase() == "usb" -> ctx.getString(R.string.files_place_usb)
            volume.contains("-") -> ctx.getString(R.string.files_place_sd_card)
            else -> volume
        }
    }

    /** The label a volume-root crumb prints instead of its path: the declared place, else the volume name. */
    fun rootLabel(ctx: Context, path: String): String? {
        declared(ctx).firstOrNull { it.location?.path == path }?.let { return it.label }
        if (path.startsWith("/storage/") && path.count { it == '/' } == 2) return volumeLabel(ctx, File(path))
        return null
    }

    fun hasAllFilesAccess(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        // #1106 a vendor storage service that throws is "no grant", never a startup crash.
        false
    }

    /** The two panes' first locations: the store, and shared storage. */
    fun initialLocations(): Pair<Location.Local, Location.Local> =
        Location.Local(SharedStore.root().absolutePath) to Location.Local(Environment.getExternalStorageDirectory().absolutePath)
}
