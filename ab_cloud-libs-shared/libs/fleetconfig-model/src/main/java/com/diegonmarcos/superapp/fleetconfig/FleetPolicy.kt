package com.diegonmarcos.superapp.fleetconfig

import android.content.Context
import com.diegonmarcos.superapp.fleetdata.FleetData
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * #783/#825 THE fleet configuration policy: the manifest (fleet-config.json) parsed into stores,
 * classes and apps, and what an export carries and an import writes over it.
 *
 * It used to be libs:core's FleetConfig, compiled into every fleet app. Since #825 an app's
 * `<package>.fleetconfig` provider (libs:core) only reads and writes its own SharedPreferences;
 * the decisions - which stores, which keys, which types, what is refused - are made here, in the
 * Cloud-Lib-Fleetconfig engine the provider asks over IPC ([FleetPolicyEngine]), and in the
 * SuperApp, which plans and diffs the whole fleet with the same classes. A policy or schema edit
 * therefore rebuilds the engine and the SuperApp, not the 32 apps that serve the contract.
 *
 * WIRE FORMAT (one app), unchanged since #783:
 *     {"contract": 2, "app": "calc", "schema_version": 1,
 *      "stores": {"<file>": {"<key>": <value>, …, "_types": {"<key>": "i|l|f"}}}}
 *
 * #855 the manifest itself (assets/fleet-config.json) lives in this module, so it is merged into the
 * two APKs that apply it: the SuperApp and Cloud-Lib-Fleetconfig.
 *
 * test: aa_cloud-superapp FleetConfigTest (export → clean profile → import, field by field).
 */
object FleetPolicy {

    private const val TAG = "FleetPolicy"

    /** The app-to-app wire contract (libs:core FleetConfig.CONTRACT says the same number). */
    const val CONTRACT = 2
    /** #1566 the manifest is a fleet-private served dataset (Cloud Account's private route), never an APK asset. */
    const val DATASET = "fleet/fleet-config"
    const val KEY_ERROR = "error"
    const val TYPES = "_types"

    data class Store(
        val name: String, val kind: String, val cls: String, val doc: String,
        val keys: Map<String, String>, val classByApp: Map<String, String>, val files: List<String>,
        val usedBy: List<String> = emptyList(),
    ) {
        /** The on-disk file names in [pkg] (`{pkg}` expanded). */
        fun filesFor(pkg: String): List<String> = files.map { it.replace("{pkg}", pkg) }
    }

    data class Coverage(val app: String, val covered: List<String>, val gaps: List<String>) {
        val total: Int get() = covered.size + gaps.size
        /** Percent of declared migrating items the contract moves; an app with none is fully covered. */
        val percent: Int get() = if (total == 0) 100 else covered.size * 100 / total
        fun json(): JSONObject = JSONObject().put("covered", JSONArray(covered)).put("gaps", JSONArray(gaps))
            .put("total", total).put("percent", percent)
    }

    data class App(val id: String, val pkg: String, val module: String, val schema: Int, val libs: List<String>, val items: List<JSONObject>)

    class Manifest(val json: JSONObject) {
        val migrate: Set<String> = json.optJSONArray("migrate").strings().toSet()
        /** Kinds this contract carries: prefs and encrypted by default; `provider` and `datastore` through the
         *  handler the app registers ([SetupStores.register]) — a DataStore must be read through the app's own
         *  instance, and provider data (the contacts) lives outside the app. */
        val portable = setOf("prefs", "encrypted", "file", "provider", "datastore")

        val stores: Map<String, Store> = json.optJSONObject("stores").let { o ->
            o?.keys()?.asSequence()?.associateWith { n ->
                val s = o.getJSONObject(n)
                Store(n, s.optString("kind"), s.optString("class"), s.optString("doc"),
                    s.optJSONObject("keys").stringMap(), s.optJSONObject("class_by_app").stringMap(),
                    s.optJSONArray("files")?.strings() ?: listOf(if (n == "<default>") "{pkg}_preferences" else n),
                    s.optJSONArray("used_by").strings())
            }.orEmpty()
        }

        val apps: Map<String, App> = json.optJSONObject("apps").let { o ->
            o?.keys()?.asSequence()?.associateWith { id ->
                val a = o.getJSONObject(id)
                App(id, a.optString("package"), a.optString("module"), a.optInt("schema_version", 1),
                    a.optJSONArray("libs").strings(),
                    a.optJSONArray("items")?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }.orEmpty())
            }.orEmpty()
        }

        fun appByPackage(pkg: String): App? = apps.values.firstOrNull { it.pkg == pkg }

        fun classOf(s: Store, appId: String): String = s.classByApp[appId] ?: s.cls

        /** A key's class: the store's, unless an override (exact, or a `*` glob) narrows it. */
        fun keyClass(s: Store, key: String, appId: String): String {
            val own = classOf(s, appId)
            s.keys[key]?.let { return it }
            s.keys.entries.firstOrNull { (pat, _) -> '*' in pat && glob(pat).matches(key) }?.let { return it.value }
            return own
        }

        fun migrates(s: Store, appId: String) = s.kind in portable && classOf(s, appId) in migrate
        fun migratesKey(s: Store, key: String, appId: String) = migrates(s, appId) && keyClass(s, key, appId) in migrate
        fun secret(s: Store, key: String, appId: String) = keyClass(s, key, appId) == "secret"

        /** Library items (files a lib keeps), by lib module id. */
        val libItems: Map<String, List<JSONObject>> = json.optJSONObject("libs").let { o ->
            o?.keys()?.asSequence()?.associateWith { l ->
                o.getJSONObject(l).optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
            }.orEmpty()
        }

        /**
         * How much of [app]'s migrating configuration this contract moves on its own: every
         * config/secret store its code or its libs open, plus every declared config/secret file.
         * A prefs/encrypted store is covered; a DataStore, a Room table or a file is a named gap.
         */
        fun coverage(app: App): Coverage {
            val mods = app.libs.toSet() + app.module
            val mine = stores.values.filter { s -> s.usedBy.any { it in mods } && classOf(s, app.id) in migrate }.sortedBy { it.name }
            // An item is moved when a portable store holds it, when installing the same build
            // reproduces it (`via: install`), or when Connect re-fetches it (`via: connect`).
            val (moved, notMoved) = (app.items + app.libs.flatMap { libItems[it].orEmpty() })
                .filter { it.optString("class") in migrate }
                .partition { it.optString("via") in setOf("install", "connect") || stores[it.optString("store")]?.kind in portable }
            fun label(i: JSONObject) = "file:" + i.optString("path").ifBlank { i.optString("id") }
            return Coverage(app.id,
                covered = mine.filter { it.kind in portable }.map { it.name } + moved.map(::label),
                gaps = mine.filter { it.kind !in portable }.map { it.name } + notMoved.map(::label))
        }

        /** The declared store a file in [pkg] belongs to (a store may own several files). */
        fun storeOfFile(pkg: String, file: String): Store? = stores.values.firstOrNull { file in it.filesFor(pkg) }
    }

    // ── the engine (pure over SharedPreferences: the JVM suite runs it on two profiles) ──

    /** Opens one store file; null = the file does not exist (export) — import passes create=true. */
    fun interface Opener { fun open(store: Store, file: String, create: Boolean): SharedPreferences? }

    /** Every migrating value [appId] holds, in the wire format. Device/content keys never leave. */
    fun exportApp(m: Manifest, app: App, opener: Opener): JSONObject {
        val stores = JSONObject()
        for (s in m.stores.values.sortedBy { it.name }) {
            if (!m.migrates(s, app.id)) continue
            for (file in s.filesFor(app.pkg)) {
                val prefs = runCatching { opener.open(s, file, false) }
                    .onFailure { Log.w(TAG, "export ${app.id}/$file unreadable: ${it.javaClass.simpleName}") }
                    .getOrNull() ?: continue
                val out = JSONObject(); val types = JSONObject()
                for ((k, v) in prefs.all.toSortedMap()) {
                    if (v == null || !m.migratesKey(s, k, app.id)) continue
                    when (v) {
                        is Int -> { out.put(k, v); types.put(k, "i") }
                        is Long -> { out.put(k, v); types.put(k, "l") }
                        is Float -> { out.put(k, v.toDouble()); types.put(k, "f") }
                        is Set<*> -> out.put(k, JSONArray(v.map { it.toString() }.sorted()))
                        else -> out.put(k, v)   // String, Boolean
                    }
                }
                if (out.length() == 0) continue
                if (types.length() > 0) out.put(TYPES, types)
                stores.put(file, out)
            }
        }
        return JSONObject().put("contract", CONTRACT).put("app", app.id).put("schema_version", app.schema).put("stores", stores)
    }

    /**
     * Writes [body] (the wire format) into [app]'s stores and says, per file, what it wrote and
     * what it refused. Refuses — never guesses — a newer schema, an undeclared file, a store that
     * does not migrate, and any key the manifest keeps on the device. Keys the phone holds and
     * [body] does not are left alone. Idempotent: the same body twice writes the same values.
     */
    fun importApp(m: Manifest, app: App, body: JSONObject, opener: Opener): JSONObject {
        val result = JSONObject().put("app", app.id)
        val incoming = body.optInt("schema_version", app.schema)
        if (incoming > app.schema)
            return result.put(KEY_ERROR, "the declared copy is schema $incoming, this app understands ${app.schema} — update ${app.id} first")
        val files = JSONObject()
        var written = 0
        body.optJSONObject("stores")?.let { stores ->
            for (file in stores.keys().asSequence().sorted()) {
                val r = JSONObject(); files.put(file, r)
                val s = m.storeOfFile(app.pkg, file) ?: run { r.put(KEY_ERROR, "not a declared store"); null } ?: continue
                if (!m.migrates(s, app.id)) { r.put(KEY_ERROR, "${m.classOf(s, app.id)} store: never migrates"); continue }
                val values = stores.getJSONObject(file)
                val types = values.optJSONObject(TYPES) ?: JSONObject()
                // An encrypted store in an app that ships no cipher throws here (NoClassDefFoundError).
                val prefs = runCatching { opener.open(s, file, true) }.getOrNull() ?: run { r.put(KEY_ERROR, "cannot open"); null } ?: continue
                val existing = prefs.all
                val ed = prefs.edit()
                val wrote = JSONArray(); val refused = JSONArray()
                for (k in values.keys().asSequence().filter { it != TYPES }.sorted()) {
                    if (!m.migratesKey(s, k, app.id)) { refused.put(k); continue }
                    if (!put(ed, k, values.get(k), types.optString(k), existing[k])) { refused.put(k); continue }
                    wrote.put(k)
                }
                if (!ed.commit()) { r.put(KEY_ERROR, "commit failed"); continue }
                written += wrote.length()
                r.put("written", wrote).put("refused", refused)
            }
        }
        return result.put("files", files).put("written", written)
    }

    /** One value with its declared type, else the type the key already has, else its JSON type. */
    private fun put(ed: SharedPreferences.Editor, k: String, v: Any?, type: String, existing: Any?): Boolean {
        val t = type.ifEmpty {
            when (existing) { is Int -> "i"; is Long -> "l"; is Float -> "f"; else -> "" }
        }
        when {
            v == null || v == JSONObject.NULL -> ed.remove(k)
            v is JSONArray -> ed.putStringSet(k, (0 until v.length()).map { v.optString(it) }.toSet())
            v is Boolean -> ed.putBoolean(k, v)
            v is Number -> when (t) {
                "i" -> ed.putInt(k, v.toInt())
                "f" -> ed.putFloat(k, v.toFloat())
                "l" -> ed.putLong(k, v.toLong())
                else -> if (v.toDouble() % 1.0 != 0.0) ed.putFloat(k, v.toFloat())
                        else if (v.toLong() in Int.MIN_VALUE..Int.MAX_VALUE) ed.putInt(k, v.toInt()) else ed.putLong(k, v.toLong())
            }
            v is String -> ed.putString(k, v)
            else -> return false
        }
        return true
    }

    @Volatile private var cached: Manifest? = null

    /** UNIT TESTS ONLY: the CI-materialised manifest text, put where the reader looks first; production never sets it. */
    @Volatile var testManifest: String? = null

    /** The manifest answered while nothing is served: no store, no app, nothing migrates (so nothing is exported or described). */
    val EMPTY: Manifest by lazy { Manifest(JSONObject()) }

    /** The served manifest (last-known-good while the provider is away; held for the process), or null while nothing is served. A copy that
     *  is present but malformed still throws: that is a broken dataset, not an absent declaration. */
    fun manifestOrNull(ctx: Context): Manifest? = testManifest?.let { Manifest(JSONObject(it)) } ?: cached ?: synchronized(this) {
        cached ?: FleetData.get(ctx.applicationContext, DATASET) { Manifest(JSONObject(it)) }?.also { cached = it }
    }

    /** The manifest, or [EMPTY] while it is not served (never a crash: nothing migrates until the dataset arrives). */
    fun manifest(ctx: Context): Manifest = manifestOrNull(ctx) ?: EMPTY

    // ── helpers ──────────────────────────────────────────────────────────

    private fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
    private fun JSONObject?.stringMap(): Map<String, String> = this?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } }.orEmpty()
    fun glob(p: String) = Regex(p.split('*').joinToString(".*") { Regex.escape(it) })
}
