package com.diegonmarcos.superapp.launcher

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment

/**
 * #1545 #1548 A hub page: one titled icon-grid section per declared subgroup of its section (Configs ▸ Agents ▸ Watchdog =
 * Network + Watchtower - Obsv).
 *
 * The sections are [Sections.hubSections] — derived from the section's own page declarations, never listed here — and
 * they are drawn by [GroupTitle] and [TileStrip] / [TileCell], THE heading and tile components of Cloud ▸ Apps (tiles
 * wrapped, as the Configs grid wraps them), so the hub looks and behaves like that page. A tap goes to the activity's [TileGridFragment.TileClickListener], the one
 * dispatcher every tile uses: `page:` opens the in-app page, an `action:` / `section:` / `extapp:` target opens the
 * app or hands off to Cloud Store exactly as the same entry does in the Configs grid.
 */
class SubgroupHubFragment : KitComposeFragment() {

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val sectionId = arguments?.getString(ARG_SECTION_ID).orEmpty()
        val pageId = arguments?.getString(ARG_PAGE_ID).orEmpty()
        val sections = remember(sectionId, pageId) { sectionsFor(sectionId, pageId) }
        val click = { target: String -> (activity as? TileGridFragment.TileClickListener)?.onTileClicked(target); Unit }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(TILE_PAGE_PADDING).testTag(TAG_GRID)) {
            // No tile here is a folder: a hub tile is always one page or one app.
            for (sec in sections) {
                GroupTitle(sec.title)
                TileStrip(sec.tiles, click, openFolder = {}, wrap = true)
            }
        }
    }

    companion object {
        private const val ARG_SECTION_ID = "section_id"
        private const val ARG_PAGE_ID = "page_id"
        const val TAG_GRID = "subgrouphub:grid"

        /** The hub's titled sections, or none when [pageId] is not a hub page of [sectionId]. */
        fun sectionsFor(sectionId: String, pageId: String): List<Sections.HubSection> {
            val section = Sections.byId(sectionId) ?: return emptyList()
            val hub = section.allPages.firstOrNull { it.id == pageId } ?: return emptyList()
            return Sections.hubSections(section, hub)
        }

        fun newInstance(sectionId: String, pageId: String): SubgroupHubFragment = SubgroupHubFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SECTION_ID, sectionId)
                putString(ARG_PAGE_ID, pageId)
            }
        }
    }
}
