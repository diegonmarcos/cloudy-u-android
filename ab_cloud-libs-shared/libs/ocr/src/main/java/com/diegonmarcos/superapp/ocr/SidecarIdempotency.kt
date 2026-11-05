package com.diegonmarcos.superapp.ocr

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Skip rule of the agent: an image is current when its `.md` and `.json` both exist and the JSON's
 * recorded sha256 matches the image. The cheap path (same size and mtime as recorded) avoids
 * hashing a whole folder on every pass; a changed mtime falls through to the real hash, so a
 * touched but identical file is still skipped and an edited one is read again.
 */
object SidecarIdempotency {
    data class Recorded(val sha256: String, val size: Long, val modifiedMs: Long)

    fun recorded(jsonText: String): Recorded? = runCatching {
        val f = JSONObject(jsonText).getJSONObject("file")
        Recorded(f.getString("sha256"), f.getLong("size"), f.getLong("modified_ms"))
    }.getOrNull()

    fun isCurrent(recorded: Recorded?, mdExists: Boolean, size: Long, modifiedMs: Long, sha256: () -> String): Boolean {
        if (recorded == null || !mdExists) return false
        if (recorded.size != size) return false
        if (recorded.modifiedMs == modifiedMs) return true
        return recorded.sha256 == sha256()
    }

    fun isCurrent(image: File): Boolean {
        val j = SidecarNaming.json(image)
        val rec = if (j.isFile) recorded(j.readText()) else null
        return isCurrent(rec, SidecarNaming.md(image).isFile, image.length(), image.lastModified()) { sha256(image) }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val buf = ByteArray(64 * 1024); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
