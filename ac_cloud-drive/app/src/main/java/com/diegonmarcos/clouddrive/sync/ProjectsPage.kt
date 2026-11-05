package com.diegonmarcos.clouddrive.sync

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.clouddrive.ui.StatusLight

/**
 * #1484 Fsync > Projects: the projects the synced folders belong to, one dense card each. No project source exists
 * yet, so the list is DECLARED SAMPLE DATA ([SAMPLE_PROJECTS], flagged as such on screen); the day a real source
 * lands it replaces the list and nothing else changes. Identifiers say sample, never a banned word (S8 guard).
 */
data class ProjectDecl(val id: String, val name: String, val folder: String, val repos: Int)

val SAMPLE_PROJECTS: List<ProjectDecl> = listOf(
    ProjectDecl("sample-notes", "Notes vault", "/storage/emulated/0/Documents/notes", 1),
    ProjectDecl("sample-photos", "Photo backup", "/storage/emulated/0/DCIM", 0),
    ProjectDecl("sample-code", "Code workspace", "/storage/emulated/0/cloud-drive-storage/code", 3),
)

@Composable
fun ProjectsPage(modifier: Modifier = Modifier, projects: List<ProjectDecl> = SAMPLE_PROJECTS) {
    LazyColumn(modifier.fillMaxSize().testTag(DriveTags.PROJECTS)) {
        item { SectionHeader(stringResource(R.string.projects_sample_header), count = projects.size) }
        items(projects, key = { it.id }) { p ->
            DriveCard(p.name, light = StatusLight.State.UNKNOWN, summary = p.folder, summaryMonospace = true, tag = DriveTags.PROJECT_ROW)
        }
    }
}
