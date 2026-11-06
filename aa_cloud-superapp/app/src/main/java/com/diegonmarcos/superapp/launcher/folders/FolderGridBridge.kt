package com.diegonmarcos.superapp.launcher.folders

import android.content.Context
import com.diegonmarcos.superapp.apps.PhoneApp

/**
 * #1553 The bridge that makes the Folders page the source of truth the launcher grid reads.
 *
 * The Phone-tab / Suite / Cloud ▸ Apps grid groups apps into folders by the baked keyword taxonomy
 * (PhoneAppClassifier). This bridge lets the user's own Folders-page assignment WIN over that: an app the user has
 * explicitly placed (a Phone-scope override in [FolderOverrideStore]) moves to that folder id, provided the id is a
 * real folder of the grid. With no overrides set it returns the grouping unchanged — so the default launcher grid is
 * byte-for-byte what it was, and the feature only ever adds the user's deliberate moves.
 */
object FolderGridBridge {

    /** The user's Phone-scope assignment for the launcher grid (the SoT the grid reads). */
    fun launcherOverrides(ctx: Context): Map<String, String> =
        FolderOverrideStore(ctx.applicationContext).all(FolderScope.PHONE)

    /**
     * Apply [overrides] (pkg → folder id) to a [grouped] folder→apps map. An override whose folder id is not in
     * [knownFolderIds] is ignored (the app keeps its classified folder, never lost to a non-existent bucket). Pure:
     * returns a new map and never mutates [grouped] (which may be a shared cache). Empty overrides ⇒ the same grouping.
     */
    fun applyOverrides(
        grouped: Map<String, List<PhoneApp>>,
        overrides: Map<String, String>,
        knownFolderIds: Set<String>,
    ): Map<String, List<PhoneApp>> {
        val moves = overrides.filter { it.value in knownFolderIds }
        if (moves.isEmpty()) return grouped
        val out = LinkedHashMap<String, MutableList<PhoneApp>>()
        for ((fid, apps) in grouped) out[fid] = apps.toMutableList()
        for ((fid, apps) in grouped) {
            val keep = apps.filter { moves[it.packageName].let { t -> t == null || t == fid } }
            if (keep.size != apps.size) out[fid] = keep.toMutableList()
        }
        for ((pkg, target) in moves) {
            val app = grouped.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.packageName == pkg } } ?: continue
            val bucket = out.getOrPut(target) { mutableListOf() }
            if (bucket.none { it.packageName == pkg }) bucket.add(app)
        }
        return out.mapValues { it.value.toList() }
    }
}
