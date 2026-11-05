package com.diegonmarcos.clouddrive.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.IconCatalog

/** #1484 the Editor tab (app-side glue only; the tabs, viewers and editors live in the shared libs). */
@Composable
fun EditorScreen(actions: DriveActions, openInExplorer: (String) -> Unit, modifier: Modifier = Modifier) {
    EmptyState(IconCatalog.vectorOrDefault("edit_note"), stringResource(R.string.editor_empty_title), stringResource(R.string.editor_empty_hint), modifier.fillMaxSize().testTag(DriveTags.EDITOR))
}
