package com.diegonmarcos.clouddrive.files

/**
 * #1607 the per-file/folder dropdown as DATA, grouped into the four labelled sections the
 * dropdown shows: Open (every way this item opens, plus the registry-driven transforms),
 * Share (copy its path, copy it into Downloads, copy a file's contents to the clipboard,
 * share, and the new Compress), Meta (rename, delete, properties, bookmark a folder) and
 * Git (shown only when the item is under a git working tree). FilesScreen renders exactly
 * [sectionsFor]; EntryMenuTest holds it and proves nothing the old flat menu offered was
 * dropped (field parity, owner rule 16) while the new actions are present.
 *
 * The OPEN_WITH / UNZIP / CONVERT gating still comes from [RowMenu] (the archive types and
 * the converter registry decide those), passed in as [rowItems]; this object only places
 * them in the Open group and decides the rest.
 */
object EntryMenu {

    enum class Group { OPEN, CONVERT, SHARE, META, GIT }

    enum class Action {
        // OPEN
        OPEN_WITH, EDIT, EDITOR, OPEN_OTHER_PANE, UNZIP, CONVERT, CONVERT_PDF, TO_PDF,
        // SHARE
        COPY_PATH, COPY_TO_DOWNLOADS, COPY_TO_CLIPBOARD, SHARE, COMPRESS,
        // META
        RENAME, DELETE, PROPERTIES, BOOKMARK,
        // GIT
        GIT_OPEN, GIT_RENAME, GIT_MOVE,
    }

    data class Section(val group: Group, val actions: List<Action>)

    /**
     * [rowItems] is [RowMenu.itemsFor]'s result (OPEN_WITH / UNZIP / CONVERT). [canEdit] and
     * [canEditor] are the editor hooks the screen already tests; [isPdf] gates Convert PDF;
     * [underGit] is [GitWorkTree.isUnderGit]. A group with no actions is dropped from the list.
     */
    fun sectionsFor(
        isDirectory: Boolean,
        isLocal: Boolean,
        rowItems: List<RowMenu.Item>,
        canEdit: Boolean,
        canEditor: Boolean,
        isPdf: Boolean,
        underGit: Boolean,
    ): List<Section> {
        val open = buildList {
            if (RowMenu.Item.OPEN_WITH in rowItems) add(Action.OPEN_WITH)
            if (canEdit) add(Action.EDIT)
            if (canEditor) add(Action.EDITOR)
            if (isDirectory) add(Action.OPEN_OTHER_PANE)
        }
        // Convert (moved out of Open): archives, the registry converters, PDF from/to, zip.
        val convert = buildList {
            if (RowMenu.Item.UNZIP in rowItems) add(Action.UNZIP)
            if (isLocal) add(Action.COMPRESS)
            if (RowMenu.Item.CONVERT in rowItems) add(Action.CONVERT)
            if (isLocal && !isDirectory) add(Action.TO_PDF)
            if (isPdf) add(Action.CONVERT_PDF)
        }
        val share = buildList {
            add(Action.COPY_PATH)
            if (isLocal) add(Action.COPY_TO_DOWNLOADS)
            if (isLocal && !isDirectory) add(Action.COPY_TO_CLIPBOARD)
            if (isLocal && !isDirectory) add(Action.SHARE)
        }
        val meta = buildList {
            add(Action.PROPERTIES)
            if (isLocal) add(Action.RENAME)
            if (isLocal) add(Action.DELETE)
            if (isLocal && isDirectory) add(Action.BOOKMARK)
        }
        val git = buildList { if (isLocal && underGit) { add(Action.GIT_OPEN); add(Action.GIT_RENAME); add(Action.GIT_MOVE) } }
        return buildList {
            if (open.isNotEmpty()) add(Section(Group.OPEN, open))
            if (convert.isNotEmpty()) add(Section(Group.CONVERT, convert))
            if (share.isNotEmpty()) add(Section(Group.SHARE, share))
            if (meta.isNotEmpty()) add(Section(Group.META, meta))
            if (git.isNotEmpty()) add(Section(Group.GIT, git))
        }
    }
}

/**
 * #1644 the multi-select dropdown as DATA. Actions: Delete All, Move All, Clear, Invert, Zip All
 * (all but the two selection ops need a writable, non-archive pane); Git: Move All via `git mv`,
 * only when every selected item is under one git working tree.
 */
object SelectionMenu {
    enum class Action { DELETE_ALL, MOVE_ALL, CLEAR_SELECTION, INVERT_SELECTION, ZIP_ALL, GIT_MOVE_ALL }

    fun actionsFor(inArchive: Boolean): List<Action> =
        if (inArchive) listOf(Action.INVERT_SELECTION, Action.CLEAR_SELECTION)
        else listOf(Action.DELETE_ALL, Action.MOVE_ALL, Action.CLEAR_SELECTION, Action.INVERT_SELECTION, Action.ZIP_ALL)

    fun gitActionsFor(inArchive: Boolean, allUnderGit: Boolean): List<Action> =
        if (!inArchive && allUnderGit) listOf(Action.GIT_MOVE_ALL) else emptyList()
}
