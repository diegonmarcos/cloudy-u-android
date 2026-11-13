package com.diegonmarcos.superapp.appstore

import android.content.Context
import org.json.JSONObject

/**
 * #1707 RETIRED APPS. An app removed from the fleet (its APK is no longer published) that is still installed on a phone is shown in the
 * Store as retired, with an Uninstall action and a pointer to where its function lives now, instead of silently vanishing.
 * The declaration is assets/appstore-retired-apps.json; the parser is pure so it is unit-tested on the JVM.
 */
object RetiredApps {
    const val ASSET = "appstore-retired-apps.json"

    class Entry(
        val pkg: String, val label: String, val retired: String,
        val replacementLabel: String, val replacementPackage: String, val reason: String,
    )

    /** Every well-formed entry of [json]; an unreadable document or a malformed entry yields nothing for it, never a crash. */
    fun parse(json: String?): List<Entry> {
        val arr = runCatching { JSONObject(json ?: return emptyList()).optJSONArray("apps") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val pkg = o.optString("package").trim()
            if (pkg.isEmpty()) return@mapNotNull null
            Entry(
                pkg, o.optString("label").ifBlank { pkg }, o.optString("retired"),
                o.optString("replacement_label"), o.optString("replacement_package"), o.optString("reason"),
            )
        }
    }

    fun load(ctx: Context): List<Entry> =
        runCatching { ctx.assets.open(ASSET).bufferedReader().use { parse(it.readText()) } }.getOrDefault(emptyList())

    /** The retired apps that are installed right now (the only ones the Store lists). */
    fun installed(ctx: Context, all: List<Entry> = load(ctx)): List<Entry> =
        all.filter { e -> runCatching { ctx.packageManager.getPackageInfo(e.pkg, 0); true }.getOrDefault(false) }

    /** The one meta line of a retired row. */
    fun meta(e: Entry): String = "retired" + if (e.retired.isNotBlank()) " ${e.retired}" else ""

    /** The explanation under it: where the function lives now, then the reason. */
    fun explain(e: Entry): String =
        listOf(
            if (e.replacementLabel.isNotBlank()) "Now in ${e.replacementLabel}." else "",
            e.reason,
        ).filter { it.isNotBlank() }.joinToString(" ")
}
