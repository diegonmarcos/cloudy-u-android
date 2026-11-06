package com.diegonmarcos.clouddrive.manage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.SharedStore
import com.diegonmarcos.superapp.usbdrive.StorageVolumes
import java.io.File

/** #1537 the phone's answers for [VolumeProbe]: shared storage, the terminal's home, a fleet app's data dir and StorageManager's volumes. */
class AndroidVolumeProbe(private val ctx: Context) : VolumeProbe {
    override fun emulated(): File? = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
    override fun terminalHome(): File? = runCatching { SharedStore.root().parentFile }.getOrNull()
    override fun appHome(fleet: String, relative: String): File? {
        val pkg = Declarations.constellation.firstOrNull { it.fleet == fleet }?.packageName?.takeIf { it.isNotBlank() } ?: return null
        val data = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).dataDir }.getOrNull() ?: return null
        return File(data, relative)
    }
    override fun externalDrives(): List<ExternalDrive> = VolumeOverview.externalFrom(StorageVolumes.read(ctx))
    override fun stat(dir: File): Pair<Long, Long>? = runCatching { StatFs(dir.absolutePath).let { it.availableBytes to it.totalBytes } }.getOrNull()
}
