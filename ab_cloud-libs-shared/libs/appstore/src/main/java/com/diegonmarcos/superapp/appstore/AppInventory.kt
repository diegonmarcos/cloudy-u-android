package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.PackageInfoCompat
import com.diegonmarcos.superapp.updater.Fleet
import org.json.JSONArray
import org.json.JSONObject

/**
 * Store ▸ Phone Apps export / import (#565): the installed-apps inventory as a
 * versioned JSON file, and the plan an imported file turns into on this phone.
 *
 * Every field is read, never re-derived: version from PackageInfo, origin
 * store and `ours` through [PhoneAppActions] (the real installer and the one
 * install-source map #564 declared), category from the host's CENTRAL
 * classification (#170) — the shelf both Store tabs draw. The file is sorted
 * by package and carries no timestamp, so the same phone exports the same bytes.
 */
object AppInventory {

    const val KIND = "cloud-sa.app-inventory"
    const val SCHEMA = 1

    class Entry(
        val pkg: String,
        val versionName: String,
        val versionCode: Long,
        /** Installer package of record; null = none recorded (sideload, adb, preinstall). */
        val origin: String?,
        val ours: Boolean,
        val category: String?,
        /** `"play"` when this phone found the app refuses to run unless Play installed it
         *  ([IntegrityMarks]); null otherwise. Travels with the inventory. */
        val integrity: String? = null,
    )

    /** Every launchable package, fleet or not: package → label. */
    fun launchable(ctx: Context): Map<String, String> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }
    }

    /** Every package id the fleet manifest names, alt ids included. */
    fun fleetPackages(): Set<String> =
        StoreMembership.fleetPackages(Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64))

    /** One [Entry] per installed package in [labels], sorted by package id. */
    fun entriesFor(ctx: Context, labels: Map<String, String>): List<Entry> {
        val pm = ctx.packageManager
        val fleet = fleetPackages()
        val shelves = AppStoreHost.classify(ctx, labels)
        return labels.keys.sorted().mapNotNull { pkg ->
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            val origin = PhoneAppActions.installerOf(ctx, pkg)
            Entry(pkg, info.versionName.orEmpty(), PackageInfoCompat.getLongVersionCode(info),
                origin, PhoneAppActions.isOurs(ctx, pkg in fleet, origin), shelves[pkg]?.heading)
        }
    }

    fun toJson(entries: List<Entry>, integrityPlay: Set<String> = emptySet()): String {
        val apps = JSONArray()
        for (e in entries.sortedBy { it.pkg }) apps.put(JSONObject().apply {
            put("package", e.pkg)
            put("version_name", e.versionName)
            put("version_code", e.versionCode)
            put("origin_store", e.origin ?: JSONObject.NULL)
            put("ours", e.ours)
            put("category", e.category ?: JSONObject.NULL)
            if (e.integrity == "play" || e.pkg in integrityPlay) put("integrity", "play")
        })
        return JSONObject().put("kind", KIND).put("schema", SCHEMA).put("apps", apps).toString(2) + "\n"
    }

    /** Inverse of [toJson]. Refuses a file that is not an inventory, or one
     *  written by a newer schema this build cannot read. */
    fun parse(text: String): List<Entry> {
        val o = JSONObject(text)
        require(o.optString("kind") == KIND) { "not an app inventory" }
        val schema = o.optInt("schema", 0)
        require(schema in 1..SCHEMA) { "inventory schema $schema is newer than this app ($SCHEMA)" }
        val apps = o.getJSONArray("apps")
        fun JSONObject.text(k: String) = if (isNull(k)) null else optString(k).ifEmpty { null }
        return (0 until apps.length()).map { i ->
            val a = apps.getJSONObject(i)
            Entry(a.getString("package"), a.optString("version_name"), a.optLong("version_code"),
                a.text("origin_store"), a.optBoolean("ours"), a.text("category"), a.text("integrity"))
        }
    }

    /** A foreign app and the store page that installs it: label + intent aimed at that store. */
    class StoreLink(val entry: Entry, val label: String, val intent: Intent)

    /** What an imported inventory means on THIS phone, before anything acts. */
    class Plan(
        val installed: List<Entry>,
        /** Fleet members this phone lacks: installable by the Constellation path. */
        val ours: List<Entry>,
        /** #571 Foreign apps this store can install itself, by their declared ladder (vendor / F-Droid). */
        val direct: List<Entry>,
        /** Foreign apps whose installer is a declared store: the user is sent there. */
        val store: List<StoreLink>,
        /** Everything else — sideloads, unknown installers. Listed, never acted on. */
        val manual: List<Entry>,
        /** The part of [direct] whose app needs Play Integrity: installable by us, may refuse to run. */
        val needIntegrity: List<Entry> = emptyList(),
    ) {
        /** [direct] minus [needIntegrity]: what our Store fully manages. */
        val managed: List<Entry> get() = direct.filter { it !in needIntegrity }
    }

    /**
     * `ours` is decided by THIS build's fleet, not by the file's flag: a file
     * cannot make a foreign package installable by claiming to be ours. The
     * store link comes from the one map ([PhoneAppActions.storePage]).
     */
    fun plan(wanted: List<Entry>, installed: Set<String>, fleet: Set<String>, sources: JSONObject): Plan {
        val have = ArrayList<Entry>(); val ours = ArrayList<Entry>(); val direct = ArrayList<Entry>()
        val store = ArrayList<StoreLink>(); val manual = ArrayList<Entry>()
        val cfg = PhoneAppActions.resolver(sources)
        for (e in wanted.distinctBy { it.pkg }.sortedBy { it.pkg }) {
            val page = PhoneAppActions.storePage(sources, e.origin, e.pkg)
            when {
                e.pkg in installed -> have += e
                e.pkg in fleet -> ours += e
                // #571: a declared direct rung beats the store that installed it
                // last time — that store is exactly what a fresh phone lacks.
                !SourceResolver.resolve(cfg, e.pkg).needsPlay -> direct += e
                page != null -> store += StoreLink(e, page.first, page.second)
                else -> manual += e
            }
        }
        val needIntegrity = direct.filter { e ->
            e.integrity == "play" || SourceResolver.resolve(cfg, e.pkg).integrity == SourceResolver.Integrity.PLAY
        }
        return Plan(have, ours, direct, store, manual, needIntegrity)
    }
}
