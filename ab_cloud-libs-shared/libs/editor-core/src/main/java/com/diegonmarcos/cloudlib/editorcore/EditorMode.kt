package com.diegonmarcos.cloudlib.editorcore

/**
 * The mode of one tab. A tab opens in View. What each means depends on the file (editor-types.json lists the modes a type has):
 * Markdown and HTML: View = the rendered WYSIWYG view (editable), Edit = the raw source, Served (HTML) = the page from a loopback server.
 * Other types: View = reading, Edit = changing.
 */
enum class EditorMode {
    View, Edit, Served;

    /** The Obsidian-style flip between View and Edit; Served goes back to View. */
    fun toggled(): EditorMode = if (this == View) Edit else View

    companion object {
        fun of(name: String?): EditorMode = entries.firstOrNull { it.name == name } ?: View
    }
}
