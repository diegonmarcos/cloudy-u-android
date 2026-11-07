package com.diegonmarcos.superapp.fleetdata

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * A tier-4 downloadable pack (#1547): a big reference blob (OUI table, maps, models, stickers) that is NEVER inside an .aar or APK.
 * Its small descriptor is an ordinary served dataset `packs/<name>` (`{schemaVersion, url, sha256, bytes}`, repo file data/fleet/packs/<name>.json,
 * listed in data/fleet/manifest.json), so it rides the same provider, the same sync and the same last-known-good as every other dataset; the
 * blob itself lives at `url` (data/fleet/packs/<file> on main) and is fetched ON DEMAND the first time it is asked for, verified (size + SHA-256)
 * and cached in the app's filesDir. A cached pack answers offline and whenever a refresh fails; nothing here throws.
 */
data class PackDescriptor(val url: String, val sha256: String, val bytes: Long) {
    companion object {
        fun parse(text: String): PackDescriptor {
            val o = Json.parseToJsonElement(text) as JsonObject
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull ?: error("pack descriptor has no $k")
            val d = PackDescriptor(s("url"), s("sha256").lowercase(), (o["bytes"] as? JsonPrimitive)?.longOrNull ?: -1L)
            require(d.url.startsWith("https://") && Regex("[0-9a-f]{64}").matches(d.sha256)) { "bad pack descriptor" }
            return d
        }
    }
}

/** Pure half (JVM-tested): resolves a descriptor to a verified cached file. [fetch] opens the blob stream, null when offline. */
class PackCache(private val dir: File, private val fetch: (String) -> InputStream?) {
    /** The pack file for [d] (downloaded and verified when absent); with no descriptor or a failed download, the last cached copy, else null. */
    fun resolve(name: String, d: PackDescriptor?): File? {
        if (!FleetDataRouting.validDataset("packs/$name") || '/' in name) return null
        val current = File(dir, "$name.current")
        if (d != null) {
            val target = File(dir, "$name-${d.sha256}.pack")
            if (target.isFile) { remember(current, target); return target }
            if (download(d, target)) { remember(current, target); prune(name, target); return target }
        }
        return runCatching { File(dir, current.readText().trim()).takeIf { it.isFile } }.getOrNull()
    }

    private fun remember(current: File, target: File) { runCatching { current.writeText(target.name) } }

    private fun prune(name: String, keep: File) {
        dir.listFiles { f -> f.name.startsWith("$name-") && f.name.endsWith(".pack") && f != keep }?.forEach { it.delete() }
    }

    private fun download(d: PackDescriptor, target: File): Boolean {
        val tmp = File(dir, target.name + ".part")
        return try {
            dir.mkdirs()
            val md = MessageDigest.getInstance("SHA-256")
            var n = 0L
            (fetch(d.url) ?: return false).use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val r = input.read(buf); if (r < 0) break; md.update(buf, 0, r); out.write(buf, 0, r); n += r }
                }
            }
            val ok = (d.bytes < 0 || n == d.bytes) && md.digest().joinToString("") { "%02x".format(it) } == d.sha256
            ok && tmp.renameTo(target)
        } catch (e: Exception) { false } finally { if (tmp.exists()) tmp.delete() }
    }
}

object FleetPack {
    /** Blocking (call off the main thread): the verified pack file, or null when it was never fetched and cannot be now. */
    fun file(ctx: Context, name: String): File? = runCatching {
        val app = ctx.applicationContext
        val d = FleetData.get(app, "packs/$name") { PackDescriptor.parse(it) }
        PackCache(File(app.filesDir, "fleetpacks")) { url -> open(url) }.resolve(name, d)
    }.getOrNull()

    fun text(ctx: Context, name: String): String? = file(ctx, name)?.let { runCatching { it.readText() }.getOrNull() }

    private fun open(url: String): InputStream? = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        if (c.responseCode == 200) c.inputStream else null
    }.getOrNull()
}
