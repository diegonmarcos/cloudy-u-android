package com.diegonmarcos.cloudlib.editorcore

/** The Obsidian-style mode of one tab: reading it (View) or changing it (Edit). A tab opens in View. */
enum class EditorMode {
    View, Edit;

    fun toggled(): EditorMode = if (this == View) Edit else View

    companion object {
        fun of(name: String?): EditorMode = entries.firstOrNull { it.name == name } ?: View
    }
}
