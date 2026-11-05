package com.diegonmarcos.clouddrive.sync

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.sync.inventory.RepoInventory
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags

/**
 * #1315 THE INVENTORY'S OTHER SECTIONS, each the ONE folder table ([FolderRepoTable]) over one class of the ONE inventory,
 * drawn only when the class has rows; the labels are declared (ui.sync.git.inventory.labels):
 *  - duplicates: a second clone of a repository another folder holds (Archive / Delete; the primary keeps the main row);
 *  (#1491 no bare section: a bare repository is a repo folder in the one list, its bare state an attribute.)
 *  - debris: leftover folders (`*.incoming` and the other declared globs), facts only until the executor's cleanup lands.
 */
@Composable
internal fun InventorySections(
    inventory: RepoInventory.Inventory?,
    decl: Declarations.GitInventoryDecl,
    glances: Map<String, GitSyncCoordinator.Glance>,
    lastCommits: Map<String, Long>,
    run: BulkDepth.Run?,
    coordinator: GitSyncCoordinator,
    onOpenReport: () -> Unit,
) {
    val inv = inventory ?: return
    val dups = inv.of(RepoInventory.RepoClass.DUPLICATE)
    if (dups.isNotEmpty()) FolderRepoTable(
        tableId = "duplicates", title = decl.label("duplicates"), hint = decl.label("duplicates_hint"), empty = "",
        rows = dups.map { FolderActions.Target(it.path, it.origin, it.registryName) },
        glances = glances, lastCommits = lastCommits, run = run,
        kinds = listOf(Declarations.FOLDER_ARCHIVE, Declarations.FOLDER_DELETE),
        // #1452 Archive all: every extra copy to the archive root, after the same confirm dialog as one row's Archive (it lists the folders).
        bulk = listOf(Declarations.FOLDER_ARCHIVE to decl.label("archive_all")),
        coordinator = coordinator,
        subtitle = { t -> decl.label("duplicate_of") + " " + (inv.byPath(t.path)?.primaryPath?.substringAfterLast('/') ?: t.repoName) },
        onOpenReport = onOpenReport,
        modifier = Modifier.padding(vertical = DriveMetrics.pad).testTag(DriveTags.SYNC_GIT_INVENTORY + "_duplicates"),
    )
    val debris = inv.of(RepoInventory.RepoClass.DEBRIS)
    if (debris.isNotEmpty()) FolderRepoTable(
        tableId = "debris", title = decl.label("debris"), hint = decl.label("debris_hint"), empty = "",
        rows = debris.map { FolderActions.Target(it.path, "", "") },
        glances = glances, lastCommits = lastCommits, run = run, kinds = emptyList(), coordinator = coordinator,
        subtitle = { it.path },
        onOpenReport = onOpenReport,
        modifier = Modifier.padding(vertical = DriveMetrics.pad).testTag(DriveTags.SYNC_GIT_INVENTORY + "_debris"),
    )
}
