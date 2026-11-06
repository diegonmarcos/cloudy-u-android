package com.diegonmarcos.clouddrive.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.Hairline
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine

@Composable
private fun label(c: SearchColumn): String = stringResource(when (c) {
    SearchColumn.NAME -> R.string.files_search_col_name
    SearchColumn.SIZE -> R.string.files_search_col_size
    SearchColumn.FOLDER -> R.string.files_search_col_folder
    SearchColumn.MODIFIED -> R.string.files_search_col_modified
    SearchColumn.CREATED -> R.string.files_search_col_created
})

private fun widthOf(c: SearchColumn): Dp = when (c) {
    SearchColumn.NAME, SearchColumn.FOLDER -> DriveMetrics.tableName
    SearchColumn.SIZE -> DriveMetrics.tableCell
    SearchColumn.MODIFIED, SearchColumn.CREATED -> DriveMetrics.tableDate
}

/**
 * #1537 THE search-results table: Name | Size | Folder | Modified | Created. A header tap sorts that column (a second tap flips it),
 * the sorted column carries an arrow; rows are dense KitStoreRow lines (ui-kit type ramp), a tap opens the row's entry (a file in the
 * Editor, a folder in the pane). One horizontal scroll carries header and rows together (the table is wider than a phone).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchResultsTable(rows: List<SearchRow>, sort: SearchSort, onSort: (SearchColumn) -> Unit, onOpen: (SearchRow) -> Unit, modifier: Modifier = Modifier) {
    val hs = rememberScrollState()
    val total = SearchColumn.entries.fold(DriveMetrics.none) { acc, c -> acc + widthOf(c) }
    Box(modifier.fillMaxSize().horizontalScroll(hs)) {
        LazyColumn(Modifier.width(total).testTag(DriveTags.SEARCH_TABLE)) {
            stickyHeader {
                Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = KitStoreRow.padH, vertical = DriveMetrics.gap), verticalAlignment = Alignment.CenterVertically) {
                    SearchColumn.entries.forEach { c ->
                        val arrow = if (c == sort.column) (if (sort.descending) " ▼" else " ▲") else ""
                        KitStoreName(
                            label(c) + arrow,
                            Modifier.width(widthOf(c)).clickable { onSort(c) }.padding(horizontal = DriveMetrics.gap).testTag(DriveTags.searchHeader(c.name)),
                        )
                    }
                }
            }
            items(rows, key = { it.key }) { r ->
                KitStoreRowLine(Modifier.testTag(DriveTags.SEARCH_ROW), onClick = { onOpen(r) }) {
                    SearchColumn.entries.forEach { c ->
                        val cell = Modifier.width(widthOf(c)).padding(horizontal = DriveMetrics.gap)
                        when (c) {
                            SearchColumn.NAME -> KitStoreName(if (r.isDirectory) r.name + "/" else r.name, cell)
                            SearchColumn.SIZE -> KitStoreCaption(SearchTable.sizeText(r), cell, mono = true, align = TextAlign.End)
                            SearchColumn.FOLDER -> KitStoreCaption(r.folder, cell, mono = true)
                            SearchColumn.MODIFIED -> KitStoreCaption(SearchTable.date(r.modified), cell, mono = true)
                            SearchColumn.CREATED -> KitStoreCaption(SearchTable.date(r.created), cell, mono = true)
                        }
                    }
                }
                Hairline()
            }
        }
    }
}
