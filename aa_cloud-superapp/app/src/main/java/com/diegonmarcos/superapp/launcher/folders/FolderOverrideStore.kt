package com.diegonmarcos.superapp.launcher.folders

import android.content.Context

/**
 * #1553 The user's own app→folder edits, persisted per [FolderScope] on top of the served Table 2 defaults.
 *
 * The app uses SharedPreferences for its small per-feature prefs (see FloatingNavPrefs, SearchScopePrefs), so this
 * override store follows that convention rather than adding a DataStore dependency for one map. It is still exactly a
 * "persisted override": [FolderTables.resolve] reads [all] and lets any entry here win over the served assignment.
 */
class FolderOverrideStore(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("launcher_folder_overrides", Context.MODE_PRIVATE)

    private fun key(scope: FolderScope, pkg: String) = "${scope.key}:$pkg"
    private val prefix = { scope: FolderScope -> "${scope.key}:" }

    /** Every override in [scope] as pkg → folderId. */
    fun all(scope: FolderScope): Map<String, String> {
        val p = prefix(scope)
        return sp.all.entries
            .filter { it.key.startsWith(p) && it.value is String }
            .associate { it.key.removePrefix(p) to it.value as String }
    }

    /** Assign [pkg] to [folderId] in [scope] (user edit). Blank/`others` clears the override so it falls back to served/Others. */
    fun set(scope: FolderScope, pkg: String, folderId: String) {
        if (folderId.isBlank() || folderId == OTHERS_FOLDER_ID) sp.edit().remove(key(scope, pkg)).apply()
        else sp.edit().putString(key(scope, pkg), folderId).apply()
    }

    fun clear(scope: FolderScope, pkg: String) = sp.edit().remove(key(scope, pkg)).apply()
}
