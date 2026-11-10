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

    enum class Group { OPEN, SHARE, META, GIT }

    enum class Action {
        // OPEN
        OPEN_WITH, EDIT, EDITOR, OPEN_OTHER_PANE, UNZIP, CONVERT, CONVERT_PDF,
        // SHARE
        COPY_PATH, COPY_TO_DOWNLOADS, COPY_TO_CLIPBOARD, SHARE, COMPRESS,
        // META
        RENAME, DELETE, PROPERTIES, BOOKMARK,
        // GIT
        GIT_OPEN,
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
            if (RowMenu.Item.UNZIP in rowItems) add(Action.UNZIP)
            if (RowMenu.Item.CONVERT in rowItems) add(Action.CONVERT)
            if (isPdf) add(Action.CONVERT_PDF)
            if (isDirectory) add(Action.OPEN_OTHER_PANE)
        }
        val share = buildList {
            add(Action.COPY_PATH)
            if (isLocal) add(Action.COPY_TO_DOWNLOADS)
            if (isLocal && !isDirectory) add(Action.COPY_TO_CLIPBOARD)
            if (isLocal && !isDirectory) add(Action.SHARE)
            if (isLocal) add(Action.COMPRESS)
        }
        val meta = buildList {
            add(Action.PROPERTIES)
            if (isLocal) add(Action.RENAME)
            if (isLocal) add(Action.DELETE)
            if (isLocal && isDirectory) add(Action.BOOKMARK)
        }
        val git = buildList { if (isLocal && underGit) add(Action.GIT_OPEN) }
        return buildList {
            if (open.isNotEmpty()) add(Section(Group.OPEN, open))
            if (share.isNotEmpty()) add(Section(Group.SHARE, share))
            if (meta.isNotEmpty()) add(Section(Group.META, meta))
            if (git.isNotEmpty()) add(Section(Group.GIT, git))
        }
    }
}
