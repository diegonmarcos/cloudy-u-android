package com.diegonmarcos.superapp.launcher.pins

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * #1840 the shortcuts pinned on the SuperApp's home (the Cloud page and the Home sheet's Cloud tab): what a pin-shortcut request
 * (ACTION_CONFIRM_PIN_SHORTCUT / LauncherApps.PinItemRequest) hands this launcher, and what Cloud Browser's declared add-to-home IPC adds
 * when the launcher refused. One JSON file under filesDir/launcher (written atomically), one PNG per icon next to it; a removal deletes both.
 *
 *  - [Kind.SYSTEM]: accepted from a PinItemRequest. Launched through LauncherApps.startShortcut(package, id), the only way a launcher may
 *    start a shortcut it does not own the intent of.
 *  - [Kind.IPC]: added by an app of the fleet over the signature-guarded receiver ([AddHomeShortcutReceiver]); launched as its https url in
 *    the package that sent it.
 */
object PinnedShortcutStore {
    enum class Kind { SYSTEM, IPC }

    data class Pin(val id: String, val pkg: String, val label: String, val kind: Kind, val url: String = "", val iconFile: String = "", val addedAt: Long = 0L) {
        val key: String get() = keyOf(pkg, id)
    }

    /** Most pins kept: the home is a strip, not a database. */
    const val MAX = 200

    /** A bumped counter the home page reads, so a pin accepted while it is showing appears at once. */
    val changes = mutableIntStateOf(0)

    fun keyOf(pkg: String, id: String) = "$pkg/$id"

    // ── pure: the JSON, so a JVM test holds the format ───────────────────────────────────────────────────────────────

    fun serialize(pins: List<Pin>): String = JSONArray(pins.map {
        JSONObject().put("id", it.id).put("pkg", it.pkg).put("label", it.label).put("kind", it.kind.name).put("url", it.url)
            .put("icon", it.iconFile).put("at", it.addedAt)
    }).toString()

    fun parse(json: String?): List<Pin> {
        val a = runCatching { JSONArray(json ?: "[]") }.getOrDefault(JSONArray())
        val out = LinkedHashMap<String, Pin>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optString("id"); val pkg = o.optString("pkg")
            if (id.isBlank() || pkg.isBlank()) continue
            val kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.SYSTEM)
            val p = Pin(id, pkg, o.optString("label").ifBlank { id }, kind, o.optString("url"), o.optString("icon"), o.optLong("at"))
            out[p.key] = p
        }
        return out.values.toList()
    }

    /** [pins] with [p] added (replacing the same key, newest last), capped at [MAX] (the oldest go). */
    fun withPin(pins: List<Pin>, p: Pin): List<Pin> = (pins.filterNot { it.key == p.key } + p).takeLast(MAX)

    fun without(pins: List<Pin>, key: String): List<Pin> = pins.filterNot { it.key == key }

    /** A label for the tile: trimmed, one line, at most 40 characters. */
    fun cleanLabel(raw: String?, fallback: String): String =
        (raw ?: "").replace('\n', ' ').trim().ifEmpty { fallback }.take(40)

    /** Only a web page may be pinned over the IPC: http(s), no credentials in the authority. */
    fun acceptableUrl(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        val low = u.lowercase()
        if (!(low.startsWith("https://") || low.startsWith("http://"))) return false
        val authority = u.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        return authority.isNotEmpty() && !authority.contains('@')
    }

    // ── files ────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun dir(ctx: Context) = File(ctx.filesDir, "launcher").apply { mkdirs() }
    private fun file(ctx: Context) = File(dir(ctx), "pinned-shortcuts.json")

    @Synchronized fun list(ctx: Context): List<Pin> = runCatching { parse(file(ctx).takeIf { it.isFile }?.readText()) }.getOrDefault(emptyList())

    @Synchronized private fun write(ctx: Context, pins: List<Pin>): Boolean = runCatching {
        val f = file(ctx)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(serialize(pins))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        changes.intValue++
        true
    }.getOrDefault(false)

    @Synchronized fun add(ctx: Context, p: Pin): Boolean = write(ctx, withPin(list(ctx), p))

    @Synchronized fun remove(ctx: Context, key: String): Boolean {
        val gone = list(ctx).firstOrNull { it.key == key } ?: return false
        if (gone.iconFile.isNotBlank()) runCatching { File(gone.iconFile).delete() }
        return write(ctx, without(list(ctx), key))
    }

    /** Write [png] as [key]'s icon; the file's path ("" when it could not be written). */
    fun saveIcon(ctx: Context, key: String, png: ByteArray): String = runCatching {
        val d = File(dir(ctx), "pin-icons").apply { mkdirs() }
        val f = File(d, key.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }.take(80) + ".png")
        f.writeBytes(png)
        f.path
    }.getOrDefault("")
}
