package com.diegonmarcos.cloudlib.editorcore

import android.content.Context

/** Keeps the tab strip across restarts in one SharedPreferences string ([EditorTabs.encode]). */
class PrefsTabsStore(context: Context, name: String = "editor-core-tabs") {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun load(): EditorTabs = EditorTabs.decode(prefs.getString(KEY, "").orEmpty())

    fun save(tabs: EditorTabs) {
        prefs.edit().putString(KEY, tabs.encode()).apply()
    }

    private companion object {
        const val KEY = "tabs"
    }
}
