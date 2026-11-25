/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.feature_node.presentation.timeline.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.mediacenter.R
import com.diegonmarcos.mediacenter.feature_node.domain.model.GroupBy
import com.diegonmarcos.mediacenter.feature_node.presentation.util.AppBottomSheetState
import kotlinx.coroutines.launch

/** The Group by control of the Screenshots, Downloads and Camera pages, built from the filter sheet's own chips. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GroupBySheet(
    sheetState: AppBottomSheetState,
    current: GroupBy,
    onChoose: (GroupBy) -> Unit,
) {
    val scope = rememberCoroutineScope()
    if (!sheetState.isVisible) return
    ModalBottomSheet(
        sheetState = sheetState.sheetState,
        onDismissRequest = { scope.launch { sheetState.hide() } },
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.group_by_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 24.dp),
            ) {
                GroupBy.entries.forEach { option ->
                    FilterChip(
                        label = stringResource(if (option == GroupBy.NONE) R.string.group_by_none else R.string.group_by_kind),
                        selected = current == option,
                        onClick = {
                            onChoose(option)
                            scope.launch { sheetState.hide() }
                        },
                    )
                }
            }
        }
    }
}
