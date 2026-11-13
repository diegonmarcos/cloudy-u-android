package com.diegonmarcos.clouddrive.manage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manage > Local Apps keeps its last list on disk (the entry ui.manage.local_apps.cache of build.json names the file) so the page
 * shows it instantly on open, with "updated <age>", while a background read replaces it. A package add / remove / replace
 * broadcast invalidates it (the page refreshes at once). Pure JVM apart from org.json; nothing here is a secret (labels, versions, sizes).
 */
class LocalAppsCache(private val file: File) {
    fun read(): AppSnapshot.Snapshot? = runCatching { if (file.isFile) decode(file.readText()) else null }.getOrNull()
    fun write(s: AppSnapshot.Snapshot) { runCatching { file.parentFile?.mkdirs(); val tmp = File(file.path + ".tmp"); tmp.writeText(encode(s)); tmp.renameTo(file) } }
    /** Package set changed: the cached list is stale; keep the rows but zero the capture time so "updated" never claims fresh data. */
    fun invalidate() { read()?.let { write(it.copy(capturedAt = 0L)) } }

    companion object {
        const val FILE_NAME = "local-apps.json"
        const val VERSION = 1

        fun encode(s: AppSnapshot.Snapshot): String = JSONObject().apply {
            put("v", VERSION); put("capturedAt", s.capturedAt); put("usageAccess", s.usageAccess); put("shell", s.shell ?: JSONObject.NULL)
            put("rows", JSONArray().apply { s.rows.forEach { r -> put(JSONObject().apply {
                put("pkg", r.pkg); put("label", r.label); put("system", r.system)
                put("app", r.appBytes ?: JSONObject.NULL); put("data", r.dataBytes ?: JSONObject.NULL); put("cache", r.cacheBytes ?: JSONObject.NULL)
                put("running", r.running ?: JSONObject.NULL); put("ram", r.ramKb ?: JSONObject.NULL)
                put("version", r.versionName ?: JSONObject.NULL); put("fleetIcon", r.fleetIcon ?: JSONObject.NULL); put("fleetUrl", r.fleetUrl ?: JSONObject.NULL)
            }) } })
        }.toString()

        fun decode(text: String): AppSnapshot.Snapshot? {
            val o = JSONObject(text)
            if (o.optInt("v") != VERSION) return null
            fun JSONObject.l(k: String): Long? = if (isNull(k) || !has(k)) null else getLong(k)
            fun JSONObject.s(k: String): String? = if (isNull(k) || !has(k)) null else getString(k)
            val arr = o.getJSONArray("rows")
            val rows = (0 until arr.length()).map { i -> val r = arr.getJSONObject(i)
                AppRow(r.getString("pkg"), r.getString("label"), r.optBoolean("system"), r.l("app"), r.l("data"), r.l("cache"),
                    if (r.isNull("running")) null else r.getBoolean("running"), r.l("ram"), r.s("version"), r.s("fleetIcon"), r.s("fleetUrl")) }
            return AppSnapshot.Snapshot(rows, o.optBoolean("usageAccess"), o.s("shell"), o.optLong("capturedAt"))
        }

        /** "updated just now / 5 min ago / 3 h ago / 2 d ago"; capturedAt 0 = invalidated ("updating"). */
        fun ageLabel(now: Long, capturedAt: Long): String {
            if (capturedAt <= 0L) return "updated: out of date"
            val s = ((now - capturedAt) / 1000).coerceAtLeast(0)
            return "updated " + when {
                s < 60 -> "just now"
                s < 3600 -> "${s / 60} min ago"
                s < 86400 -> "${s / 3600} h ago"
                else -> "${s / 86400} d ago"
            }
        }

        /**
         * The load order of the page: the cached list FIRST (cached=true, instantly), then the fresh read (cached=false) which
         * replaces it and is written back. A failing read keeps what is shown. [emit] runs on the caller's thread.
         */
        fun load(cache: LocalAppsCache?, collect: () -> AppSnapshot.Snapshot?, emit: (AppSnapshot.Snapshot, Boolean) -> Unit) {
            cache?.read()?.let { emit(it, true) }
            val fresh = runCatching(collect).getOrNull() ?: return
            cache?.write(fresh)
            emit(fresh, false)
        }
    }
}
