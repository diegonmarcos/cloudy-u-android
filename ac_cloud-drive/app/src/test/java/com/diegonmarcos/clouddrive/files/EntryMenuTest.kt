package com.diegonmarcos.clouddrive.files

import com.diegonmarcos.clouddrive.files.convert.ConverterRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1607 the grouped per-item dropdown is DATA. These tests pin the four groups AND prove (owner
 * rule 16, field parity) that the reorg drops nothing the old flat menu offered — the old gating is
 * reproduced here and every action it produced must still appear in the new grouped sections.
 */
class EntryMenuTest {

    private val reg = ConverterRegistry.default

    private fun sections(name: String, dir: Boolean, local: Boolean, isPdf: Boolean = false, underGit: Boolean = false): List<EntryMenu.Section> {
        val rowItems = RowMenu.itemsFor(name, dir, local, reg)
        val canEdit = local && !dir && name.endsWith(".txt")
        val canEditor = local && !dir
        return EntryMenu.sectionsFor(dir, local, rowItems, canEdit, canEditor, isPdf, underGit)
    }

    private fun flat(sections: List<EntryMenu.Section>): List<EntryMenu.Action> = sections.flatMap { it.actions }

    /** The exact action set the pre-#1607 flat menu rendered, for parity checking. */
    private fun oldFlat(name: String, dir: Boolean, local: Boolean, isPdf: Boolean): Set<EntryMenu.Action> {
        val rowItems = RowMenu.itemsFor(name, dir, local, reg)
        return buildSet {
            if (RowMenu.Item.OPEN_WITH in rowItems) add(EntryMenu.Action.OPEN_WITH)
            if (local && !dir && name.endsWith(".txt")) add(EntryMenu.Action.EDIT)
            if (local && !dir) add(EntryMenu.Action.EDITOR)
            if (RowMenu.Item.UNZIP in rowItems) add(EntryMenu.Action.UNZIP)
            if (RowMenu.Item.CONVERT in rowItems) add(EntryMenu.Action.CONVERT)
            if (isPdf) add(EntryMenu.Action.CONVERT_PDF)
            if (dir) add(EntryMenu.Action.OPEN_OTHER_PANE)
            if (local && dir) add(EntryMenu.Action.BOOKMARK)
            add(EntryMenu.Action.PROPERTIES)
            add(EntryMenu.Action.COPY_PATH)
            if (local && !dir) add(EntryMenu.Action.SHARE)
            if (local) add(EntryMenu.Action.RENAME)
            if (local) add(EntryMenu.Action.DELETE)
        }
    }

    @Test fun noOldActionIsDropped() {
        val cases = listOf(
            Triple("a.txt", false, false) to false,
            Triple("a.txt", false, true) to false,
            Triple("a.png", false, true) to false,
            Triple("a.zip", false, true) to false,
            Triple("a.pdf", false, true) to true,
            Triple("folder", true, true) to false,
            Triple("a.bin", false, true) to false,
        )
        for ((t, pdf) in cases) {
            val (name, dir, local) = t
            val got = flat(sections(name, dir, local, isPdf = pdf)).toSet()
            val old = oldFlat(name, dir, local, pdf)
            assertTrue("$name dir=$dir local=$local lost ${old - got}", got.containsAll(old))
        }
    }

    @Test fun theFiveGroupsAreOrderedAndLabelledSections() {
        val s = sections("a.txt", dir = false, local = true, underGit = true)
        assertEquals(listOf(EntryMenu.Group.OPEN, EntryMenu.Group.CONVERT, EntryMenu.Group.SHARE, EntryMenu.Group.META, EntryMenu.Group.GIT), s.map { it.group })
    }

    @Test fun shareGroupCarriesTheNewActions() {
        val share = sections("a.txt", dir = false, local = true).first { it.group == EntryMenu.Group.SHARE }.actions
        assertTrue(EntryMenu.Action.COPY_PATH in share)
        assertTrue(EntryMenu.Action.COPY_TO_DOWNLOADS in share)
        assertTrue(EntryMenu.Action.COPY_TO_CLIPBOARD in share)
        assertTrue(EntryMenu.Action.SHARE in share)
        // Compress moved to the Convert group (zip); still reachable.
        assertTrue(EntryMenu.Action.COMPRESS in sections("a.txt", dir = false, local = true).first { it.group == EntryMenu.Group.CONVERT }.actions)
    }

    @Test fun openKeepsOnlyOpeningActionsAndConvertHoldsTheRest() {
        val s = sections("a.zip", dir = false, local = true, isPdf = false)
        val open = s.first { it.group == EntryMenu.Group.OPEN }.actions
        val conv = s.first { it.group == EntryMenu.Group.CONVERT }.actions
        listOf(EntryMenu.Action.UNZIP, EntryMenu.Action.CONVERT, EntryMenu.Action.CONVERT_PDF, EntryMenu.Action.TO_PDF, EntryMenu.Action.COMPRESS).forEach { assertFalse("$it still in Open", it in open) }
        assertTrue(EntryMenu.Action.UNZIP in conv && EntryMenu.Action.COMPRESS in conv && EntryMenu.Action.TO_PDF in conv)
        val pdf = sections("a.pdf", dir = false, local = true, isPdf = true).first { it.group == EntryMenu.Group.CONVERT }.actions
        assertTrue(EntryMenu.Action.CONVERT_PDF in pdf)
    }

    @Test fun compressShowsForFoldersToo_butClipboardAndShareDoNot() {
        val share = sections("folder", dir = true, local = true).first { it.group == EntryMenu.Group.SHARE }.actions
        assertTrue(EntryMenu.Action.COMPRESS in sections("folder", dir = true, local = true).first { it.group == EntryMenu.Group.CONVERT }.actions)
        assertTrue(EntryMenu.Action.COPY_TO_DOWNLOADS in share)
        assertFalse(EntryMenu.Action.COPY_TO_CLIPBOARD in share)
        assertFalse(EntryMenu.Action.SHARE in share)
    }

    @Test fun gitGroupOnlyWhenUnderAWorkTreeAndLocal() {
        assertTrue(sections("a.txt", false, true, underGit = true).any { it.group == EntryMenu.Group.GIT })
        assertFalse(sections("a.txt", false, true, underGit = false).any { it.group == EntryMenu.Group.GIT })
        assertFalse(sections("a.txt", false, false, underGit = true).any { it.group == EntryMenu.Group.GIT })
    }

    @Test fun nonLocalRowKeepsOnlyCopyPathInShareAndPropertiesInMeta() {
        val s = sections("a.txt", dir = false, local = false)
        val share = s.first { it.group == EntryMenu.Group.SHARE }.actions
        assertEquals(listOf(EntryMenu.Action.COPY_PATH), share)
        assertTrue(s.none { it.group == EntryMenu.Group.CONVERT })
        val meta = s.first { it.group == EntryMenu.Group.META }.actions
        assertEquals(listOf(EntryMenu.Action.PROPERTIES), meta)
    }
}
