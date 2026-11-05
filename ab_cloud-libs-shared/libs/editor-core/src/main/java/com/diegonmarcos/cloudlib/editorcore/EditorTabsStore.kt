package com.diegonmarcos.cloudlib.editorcore

import android.content.Context
import java.io.File

/**
 * Keeps the tab strip across restarts in one private text file ([EditorTabs.encode]) under the app's files dir. A file, not a
 * preferences store: the strip is session state of the Editor, not a configuration setting of the fleet.
 */
class FileTabsStore(context: Context, name: String = "editor-core-tabs.txt") {
    private val file = File(context.applicationContext.filesDir, name)

    fun load(): EditorTabs = runCatching { EditorTabs.decode(file.readText()) }.getOrDefault(EditorTabs())

    fun save(tabs: EditorTabs) {
        runCatching { file.writeText(tabs.encode()) }
    }
}
