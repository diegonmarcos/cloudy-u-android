package com.diegonmarcos.clouddrive.manage

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import com.diegonmarcos.clouddrive.files.Places
import com.diegonmarcos.superapp.shellclient.ShellClient
import com.diegonmarcos.superapp.shellclient.ShellResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.PriorityQueue

/**
 * X-Ray Memory's reader: every source is asked for real and answers data or a reason ([XrayMemoryReport.Src]). Blocking; off the main thread.
 * Sources: StorageManager + StatFs (volumes), the apps snapshot (StorageStatsManager sizes, PSS), a bounded walk of shared storage
 * (needs all-files access; sizes and names only), ActivityManager.MemoryInfo, and the ADB owner channel through [ShellClient]
 * (dumpsys meminfo, df, du: this app's allowlist entry in libs:shell-broker).
 */
object XrayMemoryCollector {
    const val MAX_ENTRIES = 200_000
    const val BUDGET_MS = 25_000L
    private const val TOP_FOLDERS = 25
    private const val TOP_FILES = 25

    fun collect(ctx: Context, snap: AppSnapshot.Snapshot?, appVersion: String, now: Long = System.currentTimeMillis(),
                exec: (String) -> ShellResult? = { cmd -> runCatching { ShellClient.over(ctx).exec(cmd, timeoutMs = 30_000L) }.getOrElse { ShellResult.Failed(it.message ?: it.javaClass.simpleName) } }): XrayMemoryReport.Inputs {
        val R = XrayMemoryReport
        val device = R.Device(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(), Build.VERSION.RELEASE.orEmpty(), Build.VERSION.SDK_INT,
            appVersion, SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(now)))
        val walk = walkShared(ctx)
        return R.Inputs(
            device = device,
            volumes = volumes(ctx),
            apps = appsSource(snap),
            folders = walk.folders, files = walk.files, media = walk.media, scanNote = walk.note,
            mem = memory(ctx),
            procs = procs(snap),
            adbMeminfo = shell(exec("dumpsys meminfo"), "dumpsys meminfo") { R.trimMeminfo(it) },
            adbDf = shell(exec("df -h"), "df -h") { R.scrubLines(it) },
            adbDu = shell(exec("du -k -d 1 /sdcard"), "du") { R.scrubLines(it) },
        )
    }

    /** One shell answer as a source: output, or the reason in the channel's own words. */
    fun shell(r: ShellResult?, what: String, shape: (String) -> String): XrayMemoryReport.Src<String> = when (r) {
        null -> XrayMemoryReport.Src.NA("no answer from the ADB owner channel for $what")
        is ShellResult.Ok -> if (r.success && r.stdout.isNotBlank()) XrayMemoryReport.Src.Ok(shape(r.stdout)) else XrayMemoryReport.Src.NA("$what exited ${r.exitCode}${r.stderr.takeIf { it.isNotBlank() }?.let { ": " + it.trim().take(160) } ?: ""}")
        is ShellResult.NeedsArm -> XrayMemoryReport.Src.NA("ADB owner channel not armed (arm it in SuperApp)")
        is ShellResult.Denied -> XrayMemoryReport.Src.NA("ADB owner channel denied $what: ${r.reason}")
        is ShellResult.Failed -> XrayMemoryReport.Src.NA("ADB owner channel failed for $what: ${r.reason}")
    }

    fun appsSource(snap: AppSnapshot.Snapshot?): XrayMemoryReport.Src<List<AppRow>> = when {
        snap == null -> XrayMemoryReport.Src.NA("the installed-app list could not be read")
        snap.rows.isEmpty() -> XrayMemoryReport.Src.NA("no installed apps visible (package visibility)")
        snap.rows.none { it.totalBytes != null } -> XrayMemoryReport.Src.NA("usage access not granted, so StorageStatsManager answers for no app but this one")
        else -> XrayMemoryReport.Src.Ok(snap.rows)
    }

    fun procs(snap: AppSnapshot.Snapshot?): XrayMemoryReport.Src<List<XrayMemoryReport.Proc>> {
        val p = snap?.rows.orEmpty().mapNotNull { r -> r.ramKb?.let { XrayMemoryReport.Proc(r.pkg, it) } }
        return if (p.isEmpty()) XrayMemoryReport.Src.NA("ActivityManager shows an app only its own processes since Android 5.1; no other PSS without the ADB owner channel")
        else XrayMemoryReport.Src.Ok(p)
    }

    private fun memory(ctx: Context): XrayMemoryReport.Src<XrayMemoryReport.Mem> = runCatching {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        XrayMemoryReport.Src.Ok(XrayMemoryReport.Mem(mi.totalMem, mi.availMem, mi.threshold, mi.lowMemory)) as XrayMemoryReport.Src<XrayMemoryReport.Mem>
    }.getOrElse { XrayMemoryReport.Src.NA(it.message ?: "ActivityManager refused") }

    private fun volumes(ctx: Context): XrayMemoryReport.Src<List<XrayMemoryReport.Volume>> = runCatching {
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        fun stat(f: File?): Pair<Long?, Long?> = runCatching { StatFs(f!!.absolutePath).let { it.totalBytes to it.availableBytes } }.getOrDefault(null to null)
        val out = ArrayList<XrayMemoryReport.Volume>()
        sm.storageVolumes.forEach { v ->
            val dir = if (Build.VERSION.SDK_INT >= 30) v.directory else if (v.isPrimary) Environment.getExternalStorageDirectory() else null
            val (t, f) = stat(dir)
            out += XrayMemoryReport.Volume(v.getDescription(ctx).orEmpty().ifBlank { "volume" }, dir?.absolutePath ?: "n/a", t, f, v.state.orEmpty())
        }
        val (dt, df) = stat(Environment.getDataDirectory())
        out += XrayMemoryReport.Volume("Internal data partition", Environment.getDataDirectory().absolutePath, dt, df, "mounted")
        XrayMemoryReport.Src.Ok(out) as XrayMemoryReport.Src<List<XrayMemoryReport.Volume>>
    }.getOrElse { XrayMemoryReport.Src.NA(it.message ?: "StorageManager refused") }

    private class Walk(val folders: XrayMemoryReport.Src<List<XrayMemoryReport.Sized>>, val files: XrayMemoryReport.Src<List<XrayMemoryReport.Sized>>,
                       val media: XrayMemoryReport.Src<List<XrayMemoryReport.MediaCat>>, val note: String?)

    /** A bounded depth-first walk of shared storage: per top-level folder totals, the largest files, and bytes per file category. Names and sizes only; symlinks are not followed. */
    private fun walkShared(ctx: Context): Walk {
        if (!Places.hasAllFilesAccess(ctx)) {
            val why = "all-files access not granted (Settings > Special app access > All files access)"
            return Walk(XrayMemoryReport.Src.NA(why), XrayMemoryReport.Src.NA(why), XrayMemoryReport.Src.NA(why), "skipped, $why")
        }
        val root = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        if (root == null || !root.isDirectory) {
            val why = "shared storage is not mounted"
            return Walk(XrayMemoryReport.Src.NA(why), XrayMemoryReport.Src.NA(why), XrayMemoryReport.Src.NA(why), "skipped, $why")
        }
        val t0 = System.currentTimeMillis()
        var entries = 0; var partial: String? = null
        val folderBytes = LinkedHashMap<String, Long>()
        val top = PriorityQueue<XrayMemoryReport.Sized>(compareBy<XrayMemoryReport.Sized> { it.bytes })
        val cats = HashMap<DataKind, LongArray>()
        fun visit(dir: File, topName: String) {
            val kids = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (k in kids) {
                if (partial != null) return
                if (++entries > MAX_ENTRIES) { partial = "partial, stopped after $MAX_ENTRIES entries"; return }
                if ((entries and 1023) == 0 && System.currentTimeMillis() - t0 > BUDGET_MS) { partial = "partial, stopped after ${BUDGET_MS / 1000} s"; return }
                if (runCatching { java.nio.file.Files.isSymbolicLink(k.toPath()) }.getOrDefault(false)) continue
                if (k.isDirectory) visit(k, topName)
                else {
                    val len = k.length()
                    folderBytes[topName] = (folderBytes[topName] ?: 0L) + len
                    cats.getOrPut(FileClassifier.classifyOrOthers(k.name)) { LongArray(2) }.also { it[0]++; it[1] += len }
                    top += XrayMemoryReport.Sized(k.absolutePath, len); if (top.size > TOP_FILES) top.poll()
                }
            }
        }
        root.listFiles().orEmpty().forEach { k ->
            if (partial != null) return@forEach
            if (k.isDirectory) visit(k, k.absolutePath)
            else { val len = k.length(); folderBytes["${root.absolutePath} (loose files)"] = (folderBytes["${root.absolutePath} (loose files)"] ?: 0L) + len
                cats.getOrPut(FileClassifier.classifyOrOthers(k.name)) { LongArray(2) }.also { it[0]++; it[1] += len }
                top += XrayMemoryReport.Sized(k.absolutePath, len); if (top.size > TOP_FILES) top.poll() }
        }
        return Walk(
            XrayMemoryReport.Src.Ok(folderBytes.map { XrayMemoryReport.Sized(it.key, it.value) }.sortedByDescending { it.bytes }.take(TOP_FOLDERS)),
            XrayMemoryReport.Src.Ok(top.sortedByDescending { it.bytes }),
            XrayMemoryReport.Src.Ok(cats.map { XrayMemoryReport.MediaCat(it.key.name.lowercase(), it.value[0], it.value[1]) }.sortedByDescending { it.bytes }),
            partial,
        )
    }
}
