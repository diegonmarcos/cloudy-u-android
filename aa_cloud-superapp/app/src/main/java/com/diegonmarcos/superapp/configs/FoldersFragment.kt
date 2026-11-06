package com.diegonmarcos.superapp.configs

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.launcher.GroupTitle
import com.diegonmarcos.superapp.launcher.TILE_PAGE_PADDING
import com.diegonmarcos.superapp.launcher.folders.FolderRepository
import com.diegonmarcos.superapp.launcher.folders.FolderScope
import com.diegonmarcos.superapp.launcher.folders.ResolvedSection
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #1553 Configs ▸ Setup ▸ Launcher ▸ Folders — the declarative app-into-folder organiser.
 *
 * Two scopes (Cloud | Phone) chosen with a toggle, the same Cloud/Phone split other pages use. In each scope the
 * folders and the app→folder assignment are DATA served by the fleet-data client ([FolderRepository]); the page
 * renders the resolved sections with the launcher's own [GroupTitle] heading, and a Refresh action re-enumerates on
 * demand so a newly installed/added app surfaces in Others. Colours come from the kit palette and text sizes from
 * MaterialTheme typography, so the page reads correctly in dark and light; every control carries a contentDescription.
 */
class FoldersFragment : KitComposeFragment() {

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val repo = remember { FolderRepository(ctx.applicationContext) }
        var scope by remember { mutableStateOf(FolderScope.PHONE) }
        var refreshTick by remember { mutableIntStateOf(0) }
        val sections by produceState<List<ResolvedSection>?>(null, scope, refreshTick) {
            value = withContext(Dispatchers.Default) {
                if (refreshTick == 0) repo.snapshot(scope) else repo.refresh(scope)
            }
        }
        Column(Modifier.fillMaxSize().padding(TILE_PAGE_PADDING)) {
            ScopeToggle(scope) { scope = it }
            RefreshBar { refreshTick++ }
            FolderSections(sections)
        }
    }

    @Composable
    private fun ScopeToggle(scope: FolderScope, onPick: (FolderScope) -> Unit) {
        val p = LocalKitPalette.current
        Row(Modifier.fillMaxWidth().padding(bottom = GAP), horizontalArrangement = Arrangement.spacedBy(GAP)) {
            for (s in FolderScope.values()) {
                val selected = s == scope
                Text(
                    if (s == FolderScope.CLOUD) "Cloud" else "Phone",
                    color = if (selected) p.textPrimary else p.textSecondary,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(CORNER))
                        .border(HAIRLINE, if (selected) p.accent else p.hairline, RoundedCornerShape(CORNER))
                        .clickable { onPick(s) }
                        .padding(horizontal = PAD_H, vertical = PAD_V)
                        .semantics { contentDescription = if (s == FolderScope.CLOUD) "Cloud scope" else "Phone scope" }
                        .testTag(scopeTag(s)),
                )
            }
        }
    }

    @Composable
    private fun RefreshBar(onRefresh: () -> Unit) {
        val p = LocalKitPalette.current
        Text(
            "Refresh",
            color = p.accent,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .padding(bottom = GAP)
                .clip(RoundedCornerShape(CORNER))
                .border(HAIRLINE, p.hairline, RoundedCornerShape(CORNER))
                .clickable { onRefresh() }
                .padding(horizontal = PAD_H, vertical = PAD_V)
                .semantics { contentDescription = "Refresh: re-scan apps; unassigned apps go to Others" }
                .testTag(TAG_REFRESH),
        )
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun FolderSections(sections: List<ResolvedSection>?) {
        val p = LocalKitPalette.current
        if (sections == null) return
        if (sections.isEmpty()) {
            Text("No folders yet — tap Refresh to scan apps into Others.",
                color = p.textSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(TAG_EMPTY))
            return
        }
        LazyColumn(Modifier.fillMaxSize().testTag(TAG_LIST)) {
            for (section in sections) {
                item(key = "s:${section.title}") { GroupTitle(section.title) }
                for (folder in section.folders) {
                    item(key = "f:${folder.def.id}") {
                        Column(Modifier.fillMaxWidth().padding(bottom = GAP).testTag(folderTag(folder.def.id))) {
                            Text(folder.def.label, color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                                for (app in folder.apps) {
                                    Text(app.label.ifBlank { app.pkg },
                                        color = p.textSecondary, style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.testTag(appTag(app.pkg)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        // Spacing/shape tokens kept in one place rather than scattered literals; colours are palette, sizes typography.
        private val GAP = 8.dp
        private val CORNER = 10.dp
        private val HAIRLINE = 1.dp
        private val PAD_H = 12.dp
        private val PAD_V = 6.dp

        const val TAG_LIST = "folders:list"
        const val TAG_REFRESH = "folders:refresh"
        const val TAG_EMPTY = "folders:empty"
        fun scopeTag(s: FolderScope) = "folders:scope:${s.key}"
        fun folderTag(id: String) = "folders:folder:$id"
        fun appTag(pkg: String) = "folders:app:$pkg"

        fun newInstance() = FoldersFragment()
    }
}
