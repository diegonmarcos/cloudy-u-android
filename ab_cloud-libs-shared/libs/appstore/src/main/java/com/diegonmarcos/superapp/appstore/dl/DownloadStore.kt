package com.diegonmarcos.superapp.appstore.dl

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1517 The persistent queue: one JSON file in the app's files dir, written atomically (temp file + rename) so a kill mid-write
 * leaves the previous queue, never half of one. The engine writes it on every state change; a restart reads it back.
 */
class DownloadStore(private val file: File) {
    fun load(): List<DlJob> = runCatching {
        if (!file.isFile) return emptyList()
        val a = JSONArray(file.readText())
        List(a.length()) { fromJson(a.getJSONObject(it)) }
    }.getOrElse { emptyList() }

    @Synchronized fun save(jobs: Collection<DlJob>) {
        val a = JSONArray()
        jobs.forEach { a.put(toJson(it)) }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        fun toJson(j: DlJob): JSONObject = JSONObject()
            .put("id", j.id).put("pkg", j.pkg).put("versionCode", j.versionCode).put("label", j.label)
            .put("namePrefix", j.namePrefix).put("priority", j.priority).put("userStarted", j.userStarted)
            .put("addedAt", j.addedAt).put("state", j.state.name).put("bytes", j.bytes).put("total", j.total)
            .put("etag", j.etag).put("lastModified", j.lastModified).put("mirror", j.mirror).put("mirrorName", j.mirrorName)
            .put("failures", j.failures).put("nextTryAt", j.nextTryAt).put("sha256", j.sha256)
            .put("error", j.error).put("trail", j.trail).put("file", j.file)

        fun fromJson(o: JSONObject): DlJob = DlJob(
            id = o.getString("id"), pkg = o.optString("pkg"), versionCode = o.optLong("versionCode", -1L),
            label = o.optString("label", o.getString("id")), namePrefix = o.optString("namePrefix", "dl-${o.getString("id")}-"),
            priority = o.optInt("priority"), userStarted = o.optBoolean("userStarted", true), addedAt = o.optLong("addedAt"),
            state = runCatching { DlState.valueOf(o.optString("state")) }.getOrDefault(DlState.QUEUED),
            bytes = o.optLong("bytes"), total = o.optLong("total", -1L), etag = o.optString("etag"),
            lastModified = o.optString("lastModified"), mirror = o.optInt("mirror"), mirrorName = o.optString("mirrorName"),
            failures = o.optInt("failures"), nextTryAt = o.optLong("nextTryAt"), sha256 = o.optString("sha256"),
            error = o.optString("error"), trail = o.optString("trail"), file = o.optString("file"),
        )
    }
}
