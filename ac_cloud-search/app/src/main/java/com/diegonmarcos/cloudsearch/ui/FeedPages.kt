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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.cloudsearch.R
import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.cloudsearch.core.SearchEngine
import com.diegonmarcos.cloudsearch.core.Templates
import com.diegonmarcos.cloudsearch.data.Browser
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object EnginesTags {
    const val QUERY = "engines_query"
    fun engine(id: String) = "engines_engine_$id"
    fun vertical(id: String) = "engines_vertical_$id"
}

/**
 * #1197 Search > Engines: the major web and AI search engines (build.json::search.engines_major), each opened in Cloud Browser with the
 * typed query, and the verticals' own searches (Image, Video, Movies, Books): a chip hands the query to that vertical and opens it.
 */
@Composable
fun EnginesPage(state: SearchState) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    var q by rememberSaveable { mutableStateOf("") }
    val city = state.cfg.city(state.city)
    val searchable = state.shownVerticals.filter { v -> v.subpages.any { state.cfg.subpage(it)?.kind.let { k -> k == "listing" } } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        item { TopicHeader(stringResource(R.string.engines_title), stringResource(R.string.engines_blurb)) }
        item { GlassField(q, { q = it }, stringResource(R.string.engines_placeholder), EnginesTags.QUERY) }
        items(state.cfg.majorEngines, key = { it.id }) { e ->
            GlassCard(Modifier.testTag(EnginesTags.engine(e.id)), onClick = { Browser.open(ctx, Templates.fill(e.url, q.trim(), city)) }) {
                Text(e.label, color = g.text, style = Type.style(Type.body, FontWeight.SemiBold), maxLines = 1)
                Text(stringResource(if (e.kind == "ai") R.string.engines_kind_ai else R.string.engines_kind_web), color = g.text2, style = Type.style(Type.tiny))
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.small)) {
                Text(stringResource(R.string.engines_in), color = g.text2, style = Type.style(Type.label, FontWeight.Bold))
                ChipRow {
                    searchable.forEach { v ->
                        Chip(v.label, EnginesTags.vertical(v.id)) {
                            val text = q.trim()
                            state.listing(v.id).let { it.q = text; it.stale = true }
                            state.open(v.id)
                        }
                    }
                }
            }
        }
    }
}
