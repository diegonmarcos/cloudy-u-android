package com.diegonmarcos.superapp.usbdrive

import android.content.Context
import android.hardware.usb.UsbDevice
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.UsbMassStorageDevice.Companion.getMassStorageDevices
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileStreamFactory
import java.io.Closeable
import java.io.File

/**
 * #1375 a USB drive Android did NOT mount (no kernel driver for its file system, or a vendor that skips OTG mounts),
 * opened directly over USB with libaums (me.jahnen.libaums:core, Apache-2.0): SCSI over the bulk endpoints, the
 * partition table, and its FAT32 driver. exFAT and NTFS have no libaums driver: those drives are reachable only when
 * Android mounts them itself (StorageVolumes), and [open] says so instead of pretending.
 *
 * One session = one drive. Every stream it opens is closed before [close] returns, and [close] runs on detach
 * (UsbDriveActivity), so a pulled drive never leaves a half-written FAT behind our back.
 */
class RawUsbDrive private constructor(private val device: UsbMassStorageDevice, val fs: FileSystem) : Closeable {

    data class Entry(val name: String, val path: String, val isDir: Boolean, val size: Long, val modified: Long)

    @Volatile var closed = false
        private set
    private val open = mutableSetOf<Closeable>()

    val label: String get() = fs.volumeLabel.trim().ifEmpty { "USB drive" }
    val sizeHint: String get() = UsbVolumeModel.sizeHint(fs.capacity, fs.freeSpace)
    val usbDevice: UsbDevice get() = device.usbDevice

    private fun find(path: String): UsbFile =
        if (path == "/" || path.isEmpty()) fs.rootDirectory else fs.rootDirectory.search(path.trimStart('/')) ?: error("not found: $path")

    fun list(path: String): List<Entry> {
        check(!closed) { "drive closed" }
        return find(path).listFiles().map { f ->
            Entry(f.name, UsbVolumeModel.rawChild(path, f.name), f.isDirectory, if (f.isDirectory) 0 else f.length, runCatching { f.lastModified() }.getOrDefault(0))
        }.sortedWith(compareBy<Entry>({ !it.isDir }, { it.name.lowercase() }))
    }

    /** Copies a file from the drive into [destDir] on the phone, never overwriting; returns the file written. */
    fun copyToPhone(path: String, destDir: File): File {
        check(!closed) { "drive closed" }
        val src = find(path)
        require(!src.isDirectory) { "folders are copied file by file" }
        destDir.mkdirs()
        val out = File(destDir, UsbVolumeModel.freeName(src.name) { File(destDir, it).exists() })
        val input = UsbFileStreamFactory.createBufferedInputStream(src, fs)
        track(input) { input.use { i -> out.outputStream().use { o -> i.copyTo(o, fs.chunkSize.coerceAtLeast(8192)) } } }
        return out
    }

    /** Copies a phone file onto the drive into [dirPath], never overwriting. */
    fun copyFromPhone(file: File, dirPath: String): String {
        check(!closed) { "drive closed" }
        val dir = find(dirPath)
        val existing = dir.list().toSet()
        val name = UsbVolumeModel.freeName(file.name) { it in existing }
        val dst = dir.createFile(name)
        val output = UsbFileStreamFactory.createBufferedOutputStream(dst, fs)
        track(output) { file.inputStream().use { i -> output.use { o -> i.copyTo(o, fs.chunkSize.coerceAtLeast(8192)) } } }
        return UsbVolumeModel.rawChild(dirPath, name)
    }

    fun mkdir(dirPath: String, name: String): String { check(!closed); find(dirPath).createDirectory(name); return UsbVolumeModel.rawChild(dirPath, name) }

    fun delete(path: String) { check(!closed); require(path != "/" && path.isNotEmpty()) { "the drive root is not deleted" }; find(path).delete() }

    private fun <T> track(c: Closeable, block: () -> T): T {
        synchronized(open) { open += c }
        try { return block() } finally { synchronized(open) { open -= c } }
    }

    /** Closes every open stream, then the device (flushes the FAT). Safe to call twice and from the detach receiver. */
    override fun close() {
        if (closed) return
        closed = true
        synchronized(open) { open.toList().also { open.clear() } }.forEach { runCatching { it.close() } }
        runCatching { device.close() }
    }

    sealed class Opened {
        data class Ok(val drive: RawUsbDrive) : Opened()
        /** The device is a drive but no partition holds a file system libaums reads (exFAT, NTFS, ext4…). */
        data class Unsupported(val why: String) : Opened()
        data class Failed(val why: String) : Opened()
    }

    companion object {
        /** The libaums view of [device]; empty when it is not mass storage. */
        fun massStorage(ctx: Context, device: UsbDevice): List<UsbMassStorageDevice> = runCatching { JavaUsbCommunication.install(); device.getMassStorageDevices(ctx) }.getOrDefault(emptyList())

        /** Opens [device] (USB permission must already be granted: PermsUsb.request). Blocking: call off the main thread. */
        fun open(ctx: Context, device: UsbDevice): Opened {
            val ms = massStorage(ctx, device).firstOrNull() ?: return Opened.Failed("not a USB mass-storage device")
            return try {
                ms.init()
                val part = ms.partitions.firstOrNull()
                if (part == null) {
                    runCatching { ms.close() }
                    Opened.Unsupported("no FAT32 partition: exFAT and NTFS drives open only when Android mounts them")
                } else Opened.Ok(RawUsbDrive(ms, part.fileSystem))
            } catch (t: Throwable) {
                runCatching { ms.close() }
                Opened.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
