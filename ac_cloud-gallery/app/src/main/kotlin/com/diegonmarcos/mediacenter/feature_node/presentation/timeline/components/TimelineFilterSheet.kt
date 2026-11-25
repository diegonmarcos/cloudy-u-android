/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.feature_node.presentation.timeline.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import com.diegonmarcos.mediacenter.core.presentation.components.SetupButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.mediacenter.R
import com.diegonmarcos.mediacenter.feature_node.domain.model.Album
import com.diegonmarcos.mediacenter.feature_node.domain.model.FolderSelection
import com.diegonmarcos.mediacenter.feature_node.domain.model.LocationFilter
import com.diegonmarcos.mediacenter.feature_node.domain.model.SourceFilter
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaTypeFilter
import com.diegonmarcos.mediacenter.feature_node.domain.model.TimelineDates
import com.diegonmarcos.mediacenter.feature_node.domain.model.TimelineFilter
import com.diegonmarcos.mediacenter.feature_node.presentation.util.AppBottomSheetState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TimelineFilterSheet(
    sheetState: AppBottomSheetState,
    currentFilter: TimelineFilter,
    availableYears: List<Int>,
    availableAlbums: List<Album>,
    availableBuckets: List<String>,
    onApply: (TimelineFilter) -> Unit,
) {
    val scope = rememberCoroutineScope()

    if (sheetState.isVisible) {
        var filter by remember(currentFilter) { mutableStateOf(currentFilter) }
        var showDatePicker by remember { mutableStateOf(false) }

        if (showDatePicker) {
            val pickerState = rememberDateRangePickerState(
                initialSelectedStartDateMillis = TimelineDates.pickerMillis(filter.dateFromSec),
                initialSelectedEndDateMillis = TimelineDates.pickerMillis(filter.dateToSec),
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        val (from, to) = TimelineDates.rangeFromPicker(
                            pickerState.selectedStartDateMillis,
                            pickerState.selectedEndDateMillis
                        )
                        filter = filter.copy(dateFromSec = from, dateToSec = to)
                        showDatePicker = false
                    }) { Text(stringResource(R.string.filter_apply)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            ) {
                DateRangePicker(state = pickerState, modifier = Modifier.height(480.dp))
            }
        }

        ModalBottomSheet(
            sheetState = sheetState.sheetState,
            onDismissRequest = {
                scope.launch { sheetState.hide() }
            },
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Scrollable filter content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                ) {
                    // Title
                    Text(
                        text = stringResource(R.string.filter_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Type section
                    FilterSectionHeader(stringResource(R.string.filter_type))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        FilterChip(
                            label = stringResource(R.string.filter_all),
                            selected = filter.mediaType == MediaTypeFilter.ALL,
                            onClick = { filter = filter.copy(mediaType = MediaTypeFilter.ALL) }
                        )
                        FilterChip(
                            label = stringResource(R.string.photos),
                            selected = filter.mediaType == MediaTypeFilter.PHOTOS,
                            onClick = { filter = filter.copy(mediaType = MediaTypeFilter.PHOTOS) }
                        )
                        FilterChip(
                            label = stringResource(R.string.videos),
                            selected = filter.mediaType == MediaTypeFilter.VIDEOS,
                            onClick = { filter = filter.copy(mediaType = MediaTypeFilter.VIDEOS) }
                        )
                    }

                    // Status section
                    FilterSectionHeader(stringResource(R.string.filter_status))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        FilterChip(
                            label = stringResource(R.string.favorites),
                            selected = filter.favoritesOnly,
                            onClick = { filter = filter.copy(favoritesOnly = !filter.favoritesOnly) }
                        )
                    }

                    // Storage source section: All | Emulated | External | App
                    FilterSectionHeader(stringResource(R.string.filter_source))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        SourceFilter.entries.forEach { option ->
                            FilterChip(
                                label = stringResource(
                                    when (option) {
                                        SourceFilter.ALL -> R.string.filter_all
                                        SourceFilter.EMULATED -> R.string.source_emulated
                                        SourceFilter.EXTERNAL -> R.string.source_external
                                        SourceFilter.APP -> R.string.source_app
                                    }
                                ),
                                selected = filter.source == option,
                                onClick = { filter = filter.copy(source = option) }
                            )
                        }
                    }

                    // Folder (bucket) section
                    if (availableBuckets.isNotEmpty()) {
                        FilterSectionHeader(stringResource(R.string.filter_folder))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            FilterChip(
                                label = stringResource(R.string.filter_select_all),
                                selected = FolderSelection.isAllSelected(filter.folders, availableBuckets),
                                onClick = { filter = filter.copy(folders = FolderSelection.selectAll(availableBuckets)) }
                            )
                            FilterChip(
                                label = stringResource(R.string.filter_unselect_all),
                                selected = filter.folders.isEmpty(),
                                onClick = { filter = filter.copy(folders = FolderSelection.unselectAll()) }
                            )
                            availableBuckets.forEach { bucket ->
                                FilterChip(
                                    label = bucket,
                                    selected = filter.folders.any { it.equals(bucket, ignoreCase = true) },
                                    onClick = {
                                        val folders = filter.folders.toMutableSet()
                                        if (!folders.removeAll { it.equals(bucket, ignoreCase = true) }) folders.add(bucket)
                                        filter = filter.copy(folders = folders)
                                    }
                                )
                            }
                        }
                    }

                    // Location section
                    FilterSectionHeader(stringResource(R.string.filter_location))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        FilterChip(
                            label = stringResource(R.string.filter_all),
                            selected = filter.location == LocationFilter.ANY,
                            onClick = { filter = filter.copy(location = LocationFilter.ANY) }
                        )
                        FilterChip(
                            label = stringResource(R.string.filter_location_with),
                            selected = filter.location == LocationFilter.WITH,
                            onClick = { filter = filter.copy(location = LocationFilter.WITH) }
                        )
                        FilterChip(
                            label = stringResource(R.string.filter_location_without),
                            selected = filter.location == LocationFilter.WITHOUT,
                            onClick = { filter = filter.copy(location = LocationFilter.WITHOUT) }
                        )
                    }

                    // Date range section
                    FilterSectionHeader(stringResource(R.string.filter_date_range))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        FilterChip(
                            label = TimelineDates.label(filter.dateFromSec, filter.dateToSec)
                                ?: stringResource(R.string.filter_date_any),
                            selected = filter.dateFromSec != null || filter.dateToSec != null,
                            onClick = { showDatePicker = true }
                        )
                        if (filter.dateFromSec != null || filter.dateToSec != null) {
                            FilterChip(
                                label = stringResource(R.string.filter_date_clear),
                                selected = false,
                                onClick = { filter = filter.copy(dateFromSec = null, dateToSec = null) }
                            )
                        }
                    }

                    // Time section
                    if (availableYears.isNotEmpty()) {
                        FilterSectionHeader(stringResource(R.string.filter_time))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            availableYears.forEach { year ->
                                FilterChip(
                                    label = year.toString(),
                                    selected = year in filter.selectedYears,
                                    onClick = {
                                        val newYears = filter.selectedYears.toMutableSet()
                                        if (year in newYears) newYears.remove(year) else newYears.add(year)
                                        filter = filter.copy(selectedYears = newYears)
                                    }
                                )
                            }
                        }
                    }

                    // Source (Albums) section
                    if (availableAlbums.isNotEmpty()) {
                        FilterSectionHeader(stringResource(R.string.filter_albums))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            availableAlbums.forEach { album ->
                                FilterChip(
                                    label = album.label,
                                    selected = album.id in filter.selectedAlbumIds,
                                    onClick = {
                                        val newIds = filter.selectedAlbumIds.toMutableSet()
                                        if (album.id in newIds) newIds.remove(album.id) else newIds.add(album.id)
                                        filter = filter.copy(selectedAlbumIds = newIds)
                                    }
                                )
                            }
                        }
                    }
                }

                // Pinned bottom buttons
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SetupButton(
                        modifier = Modifier.weight(1f),
                        applyHorizontalPadding = false,
                        applyBottomPadding = false,
                        applyInsets = false,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        text = stringResource(R.string.filter_reset),
                        onClick = { filter = TimelineFilter() }
                    )
                    SetupButton(
                        modifier = Modifier.weight(1f),
                        applyHorizontalPadding = false,
                        applyBottomPadding = false,
                        applyInsets = false,
                        text = stringResource(R.string.filter_apply),
                        onClick = {
                            onApply(filter)
                            scope.launch { sheetState.hide() }
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun FilterSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
internal fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        label = "chipBackground"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "chipContent"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
        label = "chipBorder"
    )

    val shape = RoundedCornerShape(100)
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = contentColor,
        modifier = Modifier
            .clip(shape)
            .background(backgroundColor, shape)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
