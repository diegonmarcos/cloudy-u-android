package com.diegonmarcos.superapp.usbdrive

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * #1375 the device side of [UsbVolumeModel]: StorageManager's volumes flattened to [UsbVolumeModel.Volume], and the
 * live drive state the media broadcasts (UsbVolumeReceiver) and the raw USB session publish. Never throws: a vendor
 * storage service that fails reads as "no drive" (the #1106 rule).
 */
object StorageVolumes {

    /** Bumped on every media / USB broadcast so Explorer's places and the Volumes card re-read. */
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick

    /** Per volume path (or "usb:<device>" for a raw drive): the last state a broadcast left it in. */
    private val _states = MutableStateFlow<Map<String, UsbVolumeModel.DriveState>>(emptyMap())
    val states: StateFlow<Map<String, UsbVolumeModel.DriveState>> = _states

    fun onBroadcast(key: String, action: String, sessionClosed: Boolean = false) {
        val cur = _states.value[key] ?: UsbVolumeModel.DriveState.MOUNTED
        _states.value = _states.value + (key to UsbVolumeModel.next(cur, action, sessionClosed))
        _tick.value = _tick.value + 1
    }

    fun read(ctx: Context): List<UsbVolumeModel.Volume> = runCatching {
        val sm = ctx.getSystemService(StorageManager::class.java) ?: return emptyList()
        sm.storageVolumes.map { v ->
            val path = pathOf(v)
            val (total, free) = path?.let { sizeOf(it) } ?: (0L to 0L)
            UsbVolumeModel.Volume(
                uuid = v.uuid,
                description = runCatching { v.getDescription(ctx) }.getOrNull().orEmpty(),
                path = path,
                state = v.state.orEmpty(),
                removable = v.isRemovable,
                primary = v.isPrimary,
                totalBytes = total,
                freeBytes = free,
            )
        }
    }.getOrDefault(emptyList())

    fun drives(ctx: Context): List<UsbVolumeModel.Drive> = UsbVolumeModel.drives(read(ctx))

    /** The volume's root: StorageVolume.directory on API 30+, the hidden getPath() below it. */
    private fun pathOf(v: StorageVolume): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) v.directory?.absolutePath
        else StorageVolume::class.java.getMethod("getPath").invoke(v) as? String
    }.getOrNull()

    private fun sizeOf(path: String): Pair<Long, Long> = runCatching {
        val s = StatFs(path)
        s.totalBytes to s.availableBytes
    }.getOrDefault(0L to 0L)

    /** Only the system unmounts a volume: Eject opens its Storage page (the media broadcasts then say "Safe to remove"). */
    fun openEjectSettings(ctx: Context) {
        runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
    }

    /**
     * Without all-files access a drive is still reachable through SAF: the system's own "allow access to <drive>"
     * dialog for exactly this volume (API 29+). Null below 29 or when the path is not a known volume.
     */
    fun grantIntent(ctx: Context, path: String): Intent? = runCatching {
        if (Build.VERSION.SDK_INT < 29) return null
        val sm = ctx.getSystemService(StorageManager::class.java) ?: return null
        sm.getStorageVolume(File(path))?.createOpenDocumentTreeIntent()
    }.getOrNull()
}
