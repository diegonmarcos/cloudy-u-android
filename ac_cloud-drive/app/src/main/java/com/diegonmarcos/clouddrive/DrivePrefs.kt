package com.diegonmarcos.clouddrive

import com.diegonmarcos.cloudlib.gitrepos.*

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * #579 the app's own settings and small persisted facts: Files defaults, bookmarks,
 * the persisted SAF tree grant, the last result of each mirror job. Plain
 * SharedPreferences — nothing here is a secret (secrets live in the engines'
 * keystore-backed stores). Observed as a StateFlow so a Configs toggle re-renders
 * the Files pane it governs without a restart.
 */
class DrivePrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("cloud-drive-prefs", Context.MODE_PRIVATE)
    private val grants: SharedPreferences = context.getSharedPreferences("cloud-drive-storage-grants", Context.MODE_PRIVATE)

    data class Snapshot(
        val defaultSort: String,
        val showHidden: Boolean,
        val dualPane: Boolean,
        val thumbnails: Boolean,
        val bookmarks: List<String>,
        val treeGrantUri: String?,
        val treeGrantName: String?,
        /** #1195 the table pages' auto-behaviour settings (AutoChecks): the raw `auto.*` and Git pull keys; absent = OFF. */
        val auto: Map<String, Any?> = emptyMap(),
    )

    private val _snapshot = MutableStateFlow(read())
    val snapshot: StateFlow<Snapshot> get() = _snapshot

    /**
     * #1106 an older build may have stored a key under another TYPE (a Boolean where this one reads a
     * String): SharedPreferences then throws ClassCastException on the read. That is "no value", not a crash.
     */
    private inline fun <T> safe(default: T, block: () -> T): T = try { block() } catch (_: ClassCastException) { default }

    private fun read(): Snapshot = Snapshot(
        defaultSort = safe(null) { prefs.getString(KEY_SORT, null) } ?: Declarations.files.defaultSort,
        showHidden = safe(false) { prefs.getBoolean(KEY_HIDDEN, false) },
        dualPane = safe(Declarations.files.defaultDualPane) { prefs.getBoolean(KEY_DUAL, Declarations.files.defaultDualPane) },
        thumbnails = safe(true) { prefs.getBoolean(KEY_THUMBS, true) },
        bookmarks = (safe("") { prefs.getString(KEY_BOOKMARKS, "") } ?: "").split('\n').filter { it.isNotBlank() },
        treeGrantUri = safe(null) { grants.getString(KEY_TREE_GRANT_URI, null) },
        treeGrantName = safe(null) { grants.getString(KEY_TREE_GRANT_NAME, null) },
        auto = safe<Map<String, Any?>>(emptyMap()) { prefs.all.filterKeys { it.startsWith("auto.") || it == com.diegonmarcos.cloudlib.gitrepos.AutoChecks.GIT_PULL_KEY } },
    )

    private fun edit(block: SharedPreferences.Editor.() -> Unit) { prefs.edit().apply(block).apply(); _snapshot.value = read() }

    fun setDefaultSort(key: String) = edit { putString(KEY_SORT, key) }
    fun setShowHidden(on: Boolean) = edit { putBoolean(KEY_HIDDEN, on) }
    fun setDualPane(on: Boolean) = edit { putBoolean(KEY_DUAL, on) }
    fun setThumbnails(on: Boolean) = edit { putBoolean(KEY_THUMBS, on) }

    fun isBookmarked(path: String): Boolean = path in _snapshot.value.bookmarks
    fun toggleBookmark(path: String) {
        val next = if (isBookmarked(path)) _snapshot.value.bookmarks - path else _snapshot.value.bookmarks + path
        edit { putString(KEY_BOOKMARKS, next.joinToString("\n")) }
    }
    /** #1149 a folder was renamed: every bookmark at or under it follows. [rule] maps an old path to its new one or null (unchanged). Returns how many moved. */
    fun repointBookmarks(rule: (String) -> String?): Int {
        val current = _snapshot.value.bookmarks
        val next = current.map { rule(it) ?: it }
        val moved = next.indices.count { next[it] != current[it] }
        if (moved > 0) edit { putString(KEY_BOOKMARKS, next.distinct().joinToString("\n")) }
        return moved
    }
    fun removeBookmark(path: String) = edit { putString(KEY_BOOKMARKS, (_snapshot.value.bookmarks - path).joinToString("\n")) }

    /** The remembered SAF grant (the OS keeps the permission itself; this is the URI and its display name). */
    fun rememberTreeGrant(uri: Uri, name: String) { grants.edit().putString(KEY_TREE_GRANT_URI, uri.toString()).putString(KEY_TREE_GRANT_NAME, name).apply(); _snapshot.value = read() }
    fun forgetTreeGrant() { grants.edit().clear().apply(); _snapshot.value = read() }

    /** The last mirror result per declared job name: "<epoch seconds>|<summary>|<ok>". */
    fun mirrorResult(job: String): Triple<Long, String, Boolean>? {
        val raw = prefs.getString(KEY_MIRROR_PREFIX + job, null) ?: return null
        val parts = raw.split('|', limit = 3)
        if (parts.size < 3) return null
        return Triple(parts[0].toLongOrNull() ?: 0L, parts[1], parts[2] == "1")
    }
    fun setMirrorResult(job: String, epochSeconds: Long, summary: String, ok: Boolean) =
        edit { putString(KEY_MIRROR_PREFIX + job, "$epochSeconds|${summary.replace('|', '/')}|${if (ok) 1 else 0}") }

    /** The last remote / mount test per id: true reachable, false failed, null untested. */
    fun lastTest(id: String): Boolean? = if (!prefs.contains(KEY_TEST_PREFIX + id)) null else prefs.getBoolean(KEY_TEST_PREFIX + id, false)
    fun setLastTest(id: String, ok: Boolean) = edit { putBoolean(KEY_TEST_PREFIX + id, ok) }

    /** #604 whether a declared folder↔folder sync rule is on; [declared] is the declaration's own word until the user overrides it. */
    fun ruleEnabled(id: String, declared: Boolean): Boolean = prefs.getBoolean(KEY_RULE_PREFIX + id, declared)
    fun setRuleEnabled(id: String, on: Boolean) = edit { putBoolean(KEY_RULE_PREFIX + id, on) }

    /** Manage ▸ Data's filter (DataFilter.encode); absent = the default, Media only. */
    fun dataFilter(): String? = prefs.getString(KEY_DATA_FILTER, null)
    fun setDataFilter(encoded: String) = edit { putString(KEY_DATA_FILTER, encoded) }
    /** Manage ▸ Data: list files (false) or folders (true). */
    fun dataFolders(): Boolean = prefs.getBoolean(KEY_DATA_FOLDERS, false)
    fun setDataFolders(on: Boolean) = edit { putBoolean(KEY_DATA_FOLDERS, on) }
    /** Manage ▸ Apps: the sorted column (AppColumn name) and its direction. */
    fun appsSort(): Pair<String?, Boolean> = prefs.getString(KEY_APPS_SORT, null) to prefs.getBoolean(KEY_APPS_DESC, true)
    fun setAppsSort(column: String, descending: Boolean) = edit { putString(KEY_APPS_SORT, column); putBoolean(KEY_APPS_DESC, descending) }
    /** Agents ▸ Screenshots: whether the agent runs on its own schedule. On by default. */
    fun screenshotAgentEnabled(): Boolean = prefs.getBoolean(KEY_AGENT_SHOTS, true)
    fun setScreenshotAgentEnabled(on: Boolean) = edit { putBoolean(KEY_AGENT_SHOTS, on) }

    /** #1195 one page's auto-behaviour settings (all OFF until set). */
    fun autoChecks(page: com.diegonmarcos.cloudlib.gitrepos.AutoChecks.Page): com.diegonmarcos.cloudlib.gitrepos.AutoChecks.Settings =
        com.diegonmarcos.cloudlib.gitrepos.AutoChecks.Settings(page, _snapshot.value.auto)
    fun setAutoCheck(page: com.diegonmarcos.cloudlib.gitrepos.AutoChecks.Page, flag: com.diegonmarcos.cloudlib.gitrepos.AutoChecks.Flag, on: Boolean) =
        edit { putBoolean(com.diegonmarcos.cloudlib.gitrepos.AutoChecks.key(page, flag), on) }
    fun setAutoInterval(page: com.diegonmarcos.cloudlib.gitrepos.AutoChecks.Page, minutes: Int) =
        edit { putInt(com.diegonmarcos.cloudlib.gitrepos.AutoChecks.intervalKey(page), minutes) }

    private companion object {
        const val KEY_DATA_FILTER = "manage.data.filter"
        const val KEY_DATA_FOLDERS = "manage.data.folders"
        const val KEY_APPS_SORT = "manage.apps.sort"
        const val KEY_APPS_DESC = "manage.apps.desc"
        const val KEY_AGENT_SHOTS = "agents.screenshots.enabled"
        const val KEY_SORT = "files.sort"
        const val KEY_HIDDEN = "files.hidden"
        const val KEY_DUAL = "files.dual"
        const val KEY_THUMBS = "files.thumbnails"
        const val KEY_BOOKMARKS = "files.bookmarks"
        const val KEY_MIRROR_PREFIX = "mirror.result."
        const val KEY_TEST_PREFIX = "sync.test."
        const val KEY_RULE_PREFIX = "sync.rule.enabled."
        // The same keys the pre-#579 bridge wrote, so an existing grant survives the redesign.
        const val KEY_TREE_GRANT_URI = "saf_tree_grant_uri"
        const val KEY_TREE_GRANT_NAME = "saf_tree_grant_name"
    }
}
