package com.diegonmarcos.superapp.browser

import android.content.Context
import com.diegonmarcos.cloudlib.browsercore.BrowserCore
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
