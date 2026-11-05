package com.diegonmarcos.superapp.logexport

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.diegonmarcos.superapp.devtools.LogPipe
import java.io.File

/**
 * #1490 every fleet app's log-export door (contract: [LogExportContract]). Exported and gated on the fleet signature
 * permission in the manifest; [call] is the one provider entry the framework does not check, so it re-checks the caller.
 * Reads only the app's OWN logcat (LogPipe, own uid) and its own crash ring (files/crashes); writes nothing.
 */
class LogExportProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val granted = ctx.checkPermission(LogExportContract.PERMISSION, Binder.getCallingPid(), Binder.getCallingUid()) ==
            PackageManager.PERMISSION_GRANTED || Binder.getCallingUid() == Process.myUid()
        LogExportContract.refusal(granted, method)?.let { return reply(false, "", it) }
        val q = LogExportContract.parse(arg)
        val pkg = ctx.packageName
        val info = runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.getOrNull()
        val version = info?.versionName ?: ""
        val app = ctx.applicationInfo.loadLabel(ctx.packageManager).toString()
        val payload = runCatching {
            when (method) {
                LogExportContract.META -> LogExportContract.meta(app, pkg, version)
                LogExportContract.LOGS -> LogExportContract.logsJson(logLines(q))
                LogExportContract.CRASHES -> LogExportContract.crashesJson(crashes(ctx, q.crashes))
                else -> LogExportContract.export(app, pkg, version, System.currentTimeMillis(), logLines(q), crashes(ctx, q.crashes))
            }
        }.getOrElse { return reply(false, "", it.toString()) }
        return reply(true, payload, "")
    }

    private fun logLines(q: LogExportContract.Query): List<String> =
        LogPipe.tail(q.lines, Process.myUid(), q.errorsOnly).trimEnd('\n').split('\n')

    private fun crashes(ctx: android.content.Context, count: Int): List<LogExportContract.Crash> =
        File(ctx.filesDir, LogExportContract.CRASH_DIR).listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            ?.sortedByDescending(File::getName)?.take(count)
            ?.map { LogExportContract.Crash(it.name, runCatching { it.readText() }.getOrDefault("")) } ?: emptyList()

    private fun reply(ok: Boolean, payload: String, error: String) = Bundle().apply {
        putBoolean(LogExportContract.KEY_OK, ok)
        putString(LogExportContract.KEY_MIME, LogExportContract.MIME_JSON)
        putString(LogExportContract.KEY_PAYLOAD, payload)
        putString(LogExportContract.KEY_ERROR, error)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
