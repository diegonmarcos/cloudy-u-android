package com.diegonmarcos.superapp.appstore.ventoy

import java.io.OutputStream

/**
 * #1405 the drive as the Ventoy tab sees it: a tree of paths rooted at the Ventoy data partition. The device side is
 * [SafDriveFs] (the per-volume SAF grant on a removable USB volume); the JVM suite uses a fake. Paths are absolute
 * inside the drive ("/ISO/x.iso").
 */
interface DriveFs {
    fun exists(path: String): Boolean
    /** The names in [dir]; empty when it does not exist. Directories carry a trailing '/'. */
    fun list(dir: String): List<String>
    fun mkdirs(dir: String)
    /** Creates or truncates [path] and lets [body] fill it. */
    fun write(path: String, body: (OutputStream) -> Unit)
    fun readText(path: String): String?
    /** Renames the file at [from] to [toName] in the same directory. */
    fun rename(from: String, toName: String)
    fun delete(path: String): Boolean
}

/** The checksum an official source published did not match the bytes that arrived: nothing is kept. */
class ChecksumMismatch(val expected: String, val actual: String) : Exception("sha256 mismatch: expected $expected, got $actual")

/**
 * The install and remove rules, PURE over [DriveFs]: where an image goes, how it is written (to `.part`, verified,
 * then renamed, so a cut download never looks installed), and what Remove takes with it (its persistence image and its
 * ventoy.json entry, never anything outside /ISO and /persistence).
 */
object VentoyDrive {
    const val ISO_DIR = "/ISO"
    const val PERSIST_DIR = "/persistence"
    /** What Ventoy boots (https://www.ventoy.net/en/doc_start.html). */
    val BOOTABLE = setOf("iso", "img", "wim", "vhd", "vhdx", "efi", "vtoy")

    enum class Mode(val label: String, val note: String) {
        ISO_ONLY("Just the ISO", "Copies the image; Ventoy boots it as published."),
        LIVE_PERSISTENT("Live + persistence", "Adds an ext2 persistence image and its ventoy.json entry: changes survive a reboot."),
        LIVE("Live, no persistence", "Boots the live session; nothing is kept after a reboot."),
        FULL_INSTALL("Full OS install", "The install itself runs from the booted live session onto the target PC's disk; this app only prepares the drive."),
    }

    /** A Ventoy data partition: the volume label Ventoy2Disk gives it, or its /ventoy config folder. */
    fun isVentoy(fs: DriveFs, volumeLabel: String): Boolean = volumeLabel.trim().equals("Ventoy", ignoreCase = true) || fs.exists(VentoyJson.DIR)

    fun isBootable(name: String) = name.substringAfterLast('.', "").lowercase() in BOOTABLE

    /** Every bootable image on the drive (Ventoy scans the whole partition; /ISO and the root are where we look). */
    fun onDrive(fs: DriveFs): List<String> = listOf("/", "$ISO_DIR/").flatMap { dir ->
        fs.list(dir.trimEnd('/').ifEmpty { "/" }).filter { !it.endsWith("/") && isBootable(it) }.map { dir + it }
    }.distinct().sorted()

    /** A file name from a feed or a picker, made safe to place: no separators, no dot names. */
    fun safeName(n: String): String {
        val s = n.substringAfterLast('/').trim()
        require(s.isNotEmpty() && s != "." && s != ".." && '\\' !in s && !s.startsWith(".")) { "bad file name: $n" }
        return s
    }

    fun isoPath(fileName: String) = "$ISO_DIR/${safeName(fileName)}"
    fun backendPath(fileName: String) = "$PERSIST_DIR/${safeName(fileName).substringBeforeLast('.')}.dat"

    /** Which ISOs of [names] are on the drive, by file name. */
    fun installed(fs: DriveFs, names: Collection<String>): Set<String> {
        val here = onDrive(fs).map { it.substringAfterLast('/') }.toSet()
        return names.filter { it in here }.toSet()
    }

    data class Persistence(val bytes: Long, val label: String, val conf: Boolean)

    /**
     * Writes [fileName] into /ISO from [source] (which copies the bytes and returns their sha256), keeps it only if
     * the sha256 matches [expectedSha] (a null expected means "the owner picked a local file": its hash is returned and
     * shown, not checked), then applies [mode].
     */
    fun install(fs: DriveFs, fileName: String, expectedSha: String?, mode: Mode, persistence: Persistence?, source: (OutputStream) -> String): String {
        val iso = isoPath(fileName)
        val sha = placeVerified(fs, ISO_DIR, fileName, expectedSha, source)
        applyMode(fs, iso, mode, persistence)
        return sha
    }

    /** [dir]/[fileName] from [source], written as `.part` and renamed only after the sha256 check (Copy installer too). */
    fun placeVerified(fs: DriveFs, dir: String, fileName: String, expectedSha: String?, source: (OutputStream) -> String): String {
        val path = "$dir/${safeName(fileName)}"
        val part = "$path.part"
        fs.mkdirs(dir)
        var sha = ""
        try {
            fs.write(part) { sha = source(it) }
        } catch (e: Exception) {
            fs.delete(part); throw e
        }
        if (expectedSha != null && !VentoyCatalogue.matches(sha, expectedSha)) {
            fs.delete(part); throw ChecksumMismatch(expectedSha, sha)
        }
        if (fs.exists(path)) fs.delete(path)
        fs.rename(part, path.substringAfterLast('/'))
        return sha
    }

    /** Persistence on: an ext2 image + the entry. Any other mode: no entry for this image (a stale one is removed). */
    fun applyMode(fs: DriveFs, iso: String, mode: Mode, persistence: Persistence?) {
        val cur = fs.readText(VentoyJson.PATH)
        if (mode == Mode.LIVE_PERSISTENT) {
            val p = requireNotNull(persistence) { "persistence needs a size" }
            val backend = backendPath(iso)
            fs.mkdirs(PERSIST_DIR)
            fs.write(backend) { Ext2Image.write(it, p.bytes, p.label, p.conf) }
            fs.mkdirs(VentoyJson.DIR)
            val next = VentoyJson.withPersistence(cur, iso, backend)
            fs.write(VentoyJson.PATH) { it.write(next.toByteArray()) }
        } else if (cur != null && VentoyJson.backendsOf(cur, iso).isNotEmpty()) {
            VentoyJson.backendsOf(cur, iso).filter { it.startsWith("$PERSIST_DIR/") && VentoyJson.isDrivePath(it) }.forEach { fs.delete(it) }
            val next = VentoyJson.withoutImage(cur, iso)
            fs.write(VentoyJson.PATH) { it.write(next.toByteArray()) }
        }
    }

    /** Removes [iso], its persistence image(s) under /persistence and its ventoy.json entry. Nothing else. */
    fun remove(fs: DriveFs, iso: String) {
        require(VentoyJson.isDrivePath(iso) && isBootable(iso)) { "not an image path: $iso" }
        require(iso.startsWith("$ISO_DIR/") || iso.lastIndexOf('/') == 0) { "outside /ISO and the root: $iso" }
        val cur = fs.readText(VentoyJson.PATH)
        val backends = VentoyJson.backendsOf(cur, iso).filter { it.startsWith("$PERSIST_DIR/") && VentoyJson.isDrivePath(it) }
        fs.delete(iso)
        backends.forEach { fs.delete(it) }
        if (cur != null && backends.isNotEmpty()) {
            val next = VentoyJson.withoutImage(cur, iso)
            fs.write(VentoyJson.PATH) { it.write(next.toByteArray()) }
        }
    }
}
