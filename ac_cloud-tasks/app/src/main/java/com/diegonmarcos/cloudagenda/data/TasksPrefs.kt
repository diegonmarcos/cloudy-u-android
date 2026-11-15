package com.diegonmarcos.cloudagenda.data

import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs
import android.content.Context
import android.content.SharedPreferences

/** The Settings ▸ Appearance choice. */
enum class ThemeMode(val id: String, val label: String) {
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    SYSTEM("system", "System");

    /** Whether the app draws dark, given what the system is in. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> systemDark
    }

    companion object {
        fun of(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/** Small per-install choices that are not task data: theme, the persisted filter, where the user was. */
class TasksPrefs(private val sp: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    var themeMode: ThemeMode
        get() = ThemeMode.of(sp.getString(THEME, null))
        set(v) { sp.edit().putString(THEME, v.id).apply() }

    /** The top filter bar's selection; it survives a restart. */
    var filter: TaskFilter
        get() = TaskFilter.decode(sp.getString(FILTER, null))
        set(v) { sp.edit().putString(FILTER, v.encode()).apply() }

    /** The list the Tasks page was showing. */
    var selectedListId: String
        get() = sp.getString(LIST, "") ?: ""
        set(v) { sp.edit().putString(LIST, v).apply() }

    /** The Home page's project selection (which projects feed Inbox, Backlog, Tasks and Library); it survives a restart. */
    var projectSelection: ProjectSelection
        get() = ProjectSelection.decode(sp.getString(PROJECTS, null))
        set(v) { sp.edit().putString(PROJECTS, v.encode()).commit() }

    companion object {
        const val FILE = "tasks_prefs"
        private const val PROJECTS = "home_projects"
        private const val THEME = "theme_mode"
        private const val FILTER = "filter"
        private const val LIST = "selected_list"
    }
}

/**
 * The mail agent's secret (a JMAP Bearer token or an Authelia session value), encrypted at rest with the
 * Android Keystore. If the keystore is unavailable the secret is simply not kept: it is never written in the clear.
 */
class AgentSecrets(context: Context) {
    private val sp: SharedPreferences? = runCatching {
        SafeEncryptedPrefs.prefs(context.applicationContext, FILE)
    }.getOrNull()

    val available: Boolean get() = sp != null

    var secret: String
        get() = sp?.getString(KEY, "") ?: ""
        set(v) { sp?.edit()?.putString(KEY, v)?.apply() }

    companion object {
        private const val FILE = "tasks_agent_secrets"
        private const val KEY = "jmap_secret"
    }
}
