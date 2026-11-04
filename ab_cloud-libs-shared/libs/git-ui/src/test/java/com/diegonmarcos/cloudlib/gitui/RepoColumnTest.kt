package com.diegonmarcos.cloudlib.gitui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** #1408 the table header's pure rules: every column is labelled by its own string; only the sorted column shows an arrow. */
class RepoColumnTest {
    @Test fun everyColumnHasItsOwnLabel() {
        val ids = RepoColumn.entries.map { columnLabelRes(it) }
        assertEquals(RepoColumn.entries.size, ids.toSet().size)
        ids.forEach { assertNotEquals(0, it) }
        assertEquals(R.string.repo_col_status, columnLabelRes(RepoColumn.STATUS))
        assertEquals(R.string.repo_col_last_commit, columnLabelRes(RepoColumn.LAST_COMMIT))
    }

    @Test fun onlyTheSortedColumnShowsItsDirection() {
        assertEquals(" ▲", sortArrow(RepoColumn.NAME, RepoColumn.NAME, ascending = true))
        assertEquals(" ▼", sortArrow(RepoColumn.NAME, RepoColumn.NAME, ascending = false))
        assertEquals("", sortArrow(RepoColumn.LFS, RepoColumn.NAME, ascending = true))
    }

    @Test fun theDefaultKitIsDrivesLook() {
        val k = GitUiKit()
        assertEquals(160f, k.tableName.value); assertEquals(36f, k.tap.value); assertEquals(76f, k.tableCell.value)
        assertEquals("sync_git_table_header", k.tagTableHeader); assertEquals("sync_git_meta_table", k.tagMetaTable)
    }
}
