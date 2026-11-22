package com.diegonmarcos.superapp.browser

import android.content.Context
import com.diegonmarcos.cloudlib.browsercore.BrowserCore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * #1841 the one-time, in-place migration of a list store (history, bookmarks, the visit log, Quickmarks) to the browser core's canonical
 * format. The stored TEXT keeps its prefs file, key and schema (so an older app, the fleet-config sync of the declared prefs and the scraper
 * still read it); the core only normalises it (the old reader's quirks: unsorted rows, rows without a url, a missing title or time) and from
 * then on every read and write of the list goes through the core.
 *
 * Order of work, so that nothing is lost: (1) the old text is written to a backup FILE (files/browser-core/backup/<prefs>.<key>.<time>.json,
 * plus a copy of the prefs file's xml when it can be read) and, when that failed, nothing else happens (the next start tries again); (2) the
 * core reads the old text; text that is not a JSON array is left exactly as it is; (3) the canonical text is written together with the
 * `<key>_pre_core` copy of the old one and the marker, in ONE commit. The old text is never deleted.
 */
object BrowserListMigration {
    private val done = HashSet<String>()
    private const val MARK = "core_migrated_"
    private const val PRE = "_pre_core"

    /** Migrate once per process and store; cheap afterwards. Never throws: a store that cannot be migrated stays as it is. */
    fun ensure(context: Context, kind: String, prefs: String, key: String) {
        val id = "$prefs/$key"
        synchronized(done) { if (!done.add(id)) return }
        runCatching { migrate(context.applicationContext, kind, prefs, key) }
    }

    /** What happened, for the logs and the tests: "none" (nothing stored), "done" (already), "migrated", "kept" (not a list), "no-backup". */
    fun migrate(ctx: Context, kind: String, prefs: String, key: String): String {
        val sp = ctx.getSharedPreferences(prefs, Context.MODE_PRIVATE)
        if (sp.getBoolean(MARK + key, false)) return "done"
        val raw = sp.getString(key, null)
        if (raw == null) { sp.edit().putBoolean(MARK + key, true).apply(); return "none" }
        if (!backup(ctx, prefs, key, raw)) return "no-backup"
        val a = BrowserCore.lists("migrate", JSONObject().put("kind", kind).put("raw", raw))
        if (!a.optBoolean("valid")) { sp.edit().putBoolean(MARK + key, true).apply(); return "kept" }
        val e = sp.edit().putBoolean(MARK + key, true)
        if (a.optBoolean("changed")) e.putString(key + PRE, raw).putString(key, a.getString("raw"))
        e.commit()
        return "migrated"
    }

    private const val META = "browser_core_meta"

    /**
     * The same for a store of many typed prefs keys (settings: `set_migrate`, site permissions: `perm_migrate`): the keys stay where they
     * are, only a value stored under the wrong type is coerced or dropped (it threw when read) and an upper-case host key (a rule no lookup
     * ever matched) moves to its canonical key. The whole prefs map goes to a backup file first. The marker lives in its own prefs file
     * ([META]), so the declared prefs gain no key.
     */
    fun ensureMap(context: Context, prefs: String, op: String, catalogue: JSONArray?) {
        val id = "map/$prefs"
        synchronized(done) { if (!done.add(id)) return }
        runCatching { migrateMap(context.applicationContext, prefs, op, catalogue) }
    }

    fun migrateMap(ctx: Context, prefs: String, op: String, catalogue: JSONArray?): String {
        val meta = ctx.getSharedPreferences(META, Context.MODE_PRIVATE)
        val mark = "map_$prefs"
        if (meta.getBoolean(mark, false)) return "done"
        val sp = ctx.getSharedPreferences(prefs, Context.MODE_PRIVATE)
        val all = sp.all
        if (all.isEmpty()) { meta.edit().putBoolean(mark, true).apply(); return "none" }
        val stored = JSONObject()
        for ((k, v) in all) {
            val tag = when (v) { is Boolean -> "Boolean"; is Int -> "Integer"; is Long -> "Long"; is Float -> "Float"; is String -> "String"; is Set<*> -> "Set"; else -> "Other" }
            val json: Any = if (v is Set<*>) JSONArray(v.map { it.toString() }) else v ?: JSONObject.NULL
            stored.put(k, JSONObject().put("t", tag).put("v", json))
        }
        if (!backup(ctx, prefs, "all", stored.toString())) return "no-backup"
        val ed = sp.edit()
        if (op == "set_migrate") {
            val a = BrowserCore.lists(op, JSONObject().put("catalogue", catalogue ?: JSONArray()).put("stored", stored))
            val fixes = a.optJSONObject("fixes") ?: JSONObject()
            for (k in fixes.keys()) {
                val f = fixes.getJSONObject(k)
                when (f.optString("t")) {
                    "bool" -> ed.putBoolean(k, f.getBoolean("v"))
                    "int" -> ed.putInt(k, f.getInt("v"))
                    "set" -> ed.putStringSet(k, f.getJSONArray("v").let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() })
                    else -> ed.putString(k, f.getString("v"))
                }
            }
            a.optJSONArray("drops")?.let { d -> for (i in 0 until d.length()) ed.remove(d.getString(i)) }
        } else {
            val rules = JSONObject()
            for ((k, v) in all) if (v is String) rules.put(k, v)
            val a = BrowserCore.lists(op, JSONObject().put("rules", rules))
            a.optJSONArray("moves")?.let { m -> for (i in 0 until m.length()) m.getJSONObject(i).let { ed.putString(it.getString("to"), it.getString("value")).remove(it.getString("from")) } }
            a.optJSONArray("drops")?.let { d -> for (i in 0 until d.length()) ed.remove(d.getString(i)) }
        }
        ed.commit()
        meta.edit().putBoolean(mark, true).apply()
        return "migrated"
    }

    private fun backup(ctx: Context, prefs: String, key: String, raw: String): Boolean {
        val dir = File(ctx.filesDir, "browser-core/backup")
        val stamp = System.currentTimeMillis()
        return try {
            dir.mkdirs()
            val f = File(dir, "$prefs.$key.$stamp.json")
            f.writeText(raw)
            val ok = f.isFile && f.length() == raw.toByteArray().size.toLong()
            runCatching {
                val xml = File(ctx.applicationInfo.dataDir, "shared_prefs/$prefs.xml")
                if (xml.isFile) xml.copyTo(File(dir, "$prefs.$stamp.xml"), overwrite = true)
            }
            ok
        } catch (_: Throwable) { false }
    }
}
