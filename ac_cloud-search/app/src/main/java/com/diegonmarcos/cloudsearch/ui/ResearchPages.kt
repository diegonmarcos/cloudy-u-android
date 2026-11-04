package com.diegonmarcos.cloudsearch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.cloudsearch.R
import com.diegonmarcos.cloudsearch.data.Browser
import com.diegonmarcos.cloudsearch.data.FleetCalls
import com.diegonmarcos.superapp.ai.AiModelCatalogue
import com.diegonmarcos.superapp.modelcatalogue.CatalogueFetch
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ResearchTags {
    const val STRIP = "research_strip"
}

/**
 * #1197 Research: Reports (what agents published), Models (the model catalogue) and the Agents pages (its third page). The second row is libs:ui-nav-bars's; each page below it is one bounded list, so the
 * shared scroll-collapse sees a single scroller and nothing scrolls inside another scroller of the same axis.
 */
@Composable
fun ResearchSection(state: SearchState) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.testTag(ResearchTags.STRIP)) {
            NavBarTopSecondRow(
                pages = NAV.section("research")?.pages.orEmpty(),
                selectedId = state.researchPage,
                onSelect = { state.researchPage = it.id },
                dark = state.dark,
                underTopChrome = false,
                surface = NAV.stripSurface("research", state.dark),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().testTag(Tags.page("research_" + state.researchPage))) {
            when (state.researchPage) {
                "models" -> AiModelCatalogue(
                    state.chat, aiTheme(),
                    CatalogueFetch { url -> state.services.http.get(url, emptyMap(), state.services.cfg.timeoutMs).takeIf { it.code in 200..299 }?.body },
                    Modifier.padding(top = Metrics.contentTop, bottom = Metrics.contentBottom),
                    onClose = { state.researchPage = "reports" }, // here the catalogue is a Research page, not the chat's: Close goes back to Reports
                )
                "agenthub" -> AgentsSection(state)
                else -> ReportsSection(state)
            }
        }
    }
}
