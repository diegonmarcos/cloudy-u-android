package com.diegonmarcos.superapp.fleetdata.provider

import com.diegonmarcos.superapp.fleetdata.FleetDataRouting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** A parsed route: `shared/<dataset>`, `app/<pkg>/<dataset>` or `index`. */
sealed interface Route {
    data class Shared(val dataset: String) : Route
    data class App(val pkg: String, val dataset: String) : Route
    data object Index : Route

    companion object {
        fun parse(segments: List<String>): Route? = when {
            segments == listOf("index") -> Index
            segments.size >= 2 && segments[0] == "shared" ->
                segments.drop(1).joinToString("/").takeIf { FleetDataRouting.validDataset(it) }?.let { Shared(it) }
            segments.size >= 3 && segments[0] == "app" && FleetDataRouting.validPackage(segments[1]) ->
                segments.drop(2).joinToString("/").takeIf { FleetDataRouting.validDataset(it) }?.let { App(segments[1], it) }
            else -> null
        }
        fun path(r: Route): String = when (r) { is Shared -> "shared/${r.dataset}"; is App -> "app/${r.pkg}/${r.dataset}"; Index -> "index" }
    }
}

/**
 * The provider's cache in filesDir: `shared/<dataset>.json`, `app/<pkg>/<dataset>.json`, each with `.meta` (etag, schemaVersion, size, times)
 * and `.prev` (the version before the current one: the diff base and the last-known-good). A replace is write-temp-then-rename.
 */
class FleetDataStore(val root: File, private val codec: Codec? = null) {
    /** At-rest encryption of tier-1 datasets (`personal/...`): Keystore-backed on a phone, a fake in a test. */
    interface Codec { fun encode(plain: ByteArray): ByteArray; fun decode(stored: ByteArray): ByteArray }

    /** True when the dataset is kept encrypted: it is then served from memory ([read]), never as a plaintext file. */
    fun encrypted(r: Route): Boolean = codec != null && when (r) {
        is Route.Shared -> r.dataset.startsWith("personal/")
        is Route.App -> r.dataset.startsWith("personal/")
        Route.Index -> false
    }

    data class Meta(val etag: String?, val schemaVersion: Int, val size: Long, val syncedAt: Long, val checkedAt: Long)
    data class Entry(val path: String, val meta: Meta?)

    fun file(r: Route): File? = when (r) {
        is Route.Shared -> File(root, "shared/${r.dataset}.json")
        is Route.App -> File(root, "app/${r.pkg}/${r.dataset}.json")
        Route.Index -> null
    }

    private fun side(f: File, ext: String) = File(f.parentFile, f.name + ext)

    fun meta(r: Route): Meta? {
        val f = file(r) ?: return null
        val m = side(f, ".meta").takeIf { it.isFile }?.let { runCatching { Json.parseToJsonElement(it.readText()) as JsonObject }.getOrNull() } ?: return null
        fun l(k: String) = (m[k] as? JsonPrimitive)?.longOrNull ?: 0L
        return Meta((m["etag"] as? JsonPrimitive)?.contentOrNull, l("schemaVersion").toInt(), l("size"), l("syncedAt"), l("checkedAt"))
    }

    private fun writeMeta(f: File, m: Meta) {
        val o = buildJsonObject {
            m.etag?.let { put("etag", it) }
            put("schemaVersion", m.schemaVersion); put("size", m.size); put("syncedAt", m.syncedAt); put("checkedAt", m.checkedAt)
        }
        side(f, ".meta").writeText(o.toString())
    }

    private fun dec(r: Route, b: ByteArray): ByteArray? = if (encrypted(r)) runCatching { codec!!.decode(b) }.getOrNull() else b
    fun read(r: Route): ByteArray? = file(r)?.takeIf { it.isFile }?.readBytes()?.let { dec(r, it) }
    fun previous(r: Route): ByteArray? = file(r)?.let { side(it, ".prev") }?.takeIf { it.isFile }?.readBytes()?.let { dec(r, it) }

    /** Replaces the dataset with [bytes] (already validated); false when the bytes equal the current copy (nothing to announce). */
    fun replace(r: Route, bytes: ByteArray, etag: String?, schemaVersion: Int, now: Long): Boolean {
        val f = file(r) ?: throw IOException("not a dataset route")
        f.parentFile?.mkdirs()
        if (f.isFile) {
            if (read(r)?.contentEquals(bytes) == true) { touch(r, etag, now); return false }
            f.copyTo(side(f, ".prev"), overwrite = true)
        }
        val tmp = side(f, ".tmp")
        FileOutputStream(tmp).use { it.write(if (encrypted(r)) codec!!.encode(bytes) else bytes); it.fd.sync() }
        if (!tmp.renameTo(f)) { tmp.delete(); throw IOException("rename failed") }
        writeMeta(f, Meta(etag, schemaVersion, bytes.size.toLong(), now, now))
        return true
    }

    /** The server answered "not modified" or the same bytes: the copy is current as of [now]. */
    fun touch(r: Route, etag: String?, now: Long) {
        val f = file(r) ?: return
        val m = meta(r) ?: return
        writeMeta(f, m.copy(etag = etag ?: m.etag, checkedAt = now))
    }

    fun list(): List<Entry> {
        val out = ArrayList<Entry>()
        for (top in listOf("shared", "app")) {
            val base = File(root, top)
            base.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath.removeSuffix(".json")
                val r = Route.parse(rel.split('/')) ?: return@forEach
                out += Entry(rel, meta(r))
            }
        }
        return out.sortedBy { it.path }
    }
}
