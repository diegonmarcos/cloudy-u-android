package com.diegonmarcos.cloudsearch.ui

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.cloudsearch.R
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.cloudsearch.core.Listing
import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.cloudsearch.core.SearchEngine
import com.diegonmarcos.cloudsearch.data.Browser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** One vertical's listing: the query, the answer, whether it is running, and whether the city changed under it. */
class ListingModel(initial: String) {
    var q by mutableStateOf(initial)
    var result by mutableStateOf<SearchEngine.QueryResult?>(null)
    var loading by mutableStateOf(false)
    var stale by mutableStateOf(true)

    fun run(state: SearchState, scope: CoroutineScope, vertical: String) {
        val query = q
        loading = true
        stale = false
        state.busy++
        scope.launch {
            try {
                result = withContext(Dispatchers.IO) { runCatching { state.services.engine.query(vertical, query, state.city) }.getOrNull() }
                state.services.prefs.setLastQuery(vertical, query)
            } catch (e: CancellationException) {
                stale = true // the page left before the answer came: ask again when it is back
                throw e
            } finally {
                loading = false
                state.busy--
            }
        }
    }
}

fun money(v: Double, currency: String?): String {
    val n = String.format(Locale.ROOT, "%,.2f", v)
    return when (currency) { null -> n; "EUR" -> "€$n"; else -> "$n $currency" }
}

fun day(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.UK).format(Date(ms))

/** The page's own padding under the top bar and above the nav (the mockup's .content-area). */
val pagePadding = PaddingValues(start = Metrics.gutter, end = Metrics.gutter, top = Metrics.contentTop, bottom = Metrics.contentBottom)

/**
 * Every vertical page starts with its title, its description and, when it has more than one, its sub-nav.
 */
fun LazyListScope.pageHeader(state: SearchState, v: SearchConfig.Vertical) {
    item(key = "header") {
        Column {
            TopicHeader(v.title, v.blurb)
            // #868 the fleet's page-tab strip, from build.json::ui.sections[].pages.
            if (v.subpages.size > 1) NavBarTopSecondRow(
                pages = NAV.section(state.sectionOf(v.id))?.page(v.id)?.pages.orEmpty(),
                selectedId = state.navId(v, state.subpageOf(v)),
                onSelect = { state.showSubpage(v, it.id) },
                dark = state.dark,
                underTopChrome = false,
                // The vertical's sub-strip: its page's background, else its section's ("theme" in build.json).
                surface = NAV.stripSurface(state.sectionOf(v.id), state.dark, v.id),
            )
        }
    }
}

@Composable
fun ListingPage(v: SearchConfig.Vertical) {
    val state = LocalState.current
    val g = LocalGlass.current
    val m = state.listing(v.id)
    val scope = rememberCoroutineScope()
    LaunchedEffect(v.id, state.city) { if (m.stale) m.run(state, scope, v.id) }
    val f = state.filtersOf(v.id)
    val shown = m.result?.let { f.apply(it.listings, v, state.cfg.recentDays, System.currentTimeMillis()) }.orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        pageHeader(state, v)
        item { GlassField(m.q, { m.q = it }, v.placeholder, Tags.SEARCH_BOX, onSearch = { m.run(state, scope, v.id) }) }
        item {
            ChipRow {
                Chip(stringResource(R.string.more_filters), Tags.MORE_FILTERS, accent = true, icon = R.drawable.ph_faders) { state.menu = Menu.FILTERS }
                Chip(stringResource(R.string.recent), Tags.chip("recent"), selected = f.recent) { state.filters[v.id] = f.copy(recent = !f.recent) }
                Chip(stringResource(R.string.verified), Tags.chip("verified"), selected = f.verified) { state.filters[v.id] = f.copy(verified = !f.verified) }
                v.chips.forEach { c ->
                    Chip(c.label, Tags.chip(c.id), selected = c.id in f.chips) {
                        state.filters[v.id] = f.copy(chips = if (c.id in f.chips) f.chips - c.id else f.chips + c.id)
                    }
                }
            }
        }
        if (m.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = g.accent, trackColor = g.glass) }
        m.result?.let { r -> item { SourceStrip(r.statuses) } }
        if (!m.loading && m.result != null && shown.isEmpty() && m.result!!.statuses.any { it.state.fetchedSomething() }) item {
            Text(stringResource(R.string.no_results), color = g.text2, style = Type.style(Type.small), modifier = Modifier.padding(vertical = Metrics.gutter))
        }
        items(shown, key = { it.key }) { ListingCard(it) }
    }
}

private fun SearchEngine.State.fetchedSomething() = this == SearchEngine.State.OK || this == SearchEngine.State.CACHED || this == SearchEngine.State.STALE

/**
 * Where every result came from, or why a source gave none: read, cached, stale (offline, last
 * answer), failed, waiting for a term; links open the site's own search; disabled ones say why.
 */
@Composable
fun SourceStrip(statuses: List<SearchEngine.SourceStatus>) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.small)) {
        statuses.filter { it.state != SearchEngine.State.LINK && it.state != SearchEngine.State.DISABLED }.forEach { s ->
            val line = when (s.state) {
                SearchEngine.State.OK -> stringResource(R.string.src_ok, s.label, s.count)
                SearchEngine.State.CACHED -> stringResource(R.string.src_cached, s.label, s.count, s.at?.let { day(it) } ?: "")
                SearchEngine.State.STALE -> stringResource(R.string.src_stale, s.label, s.count, s.at?.let { day(it) } ?: "", s.detail)
                SearchEngine.State.ERROR -> stringResource(R.string.src_error, s.label, s.detail)
                else -> stringResource(R.string.src_skipped, s.label, s.detail)
            }
            val bad = s.state == SearchEngine.State.ERROR || s.state == SearchEngine.State.STALE
            Text(line, color = if (bad) g.negative else g.text2, style = Type.style(Type.label), modifier = Modifier.testTag(Tags.source(s.id)))
        }
        val links = statuses.filter { it.state == SearchEngine.State.LINK }
        if (links.isNotEmpty()) {
            FieldLabel(stringResource(R.string.open_on), Modifier.padding(top = Metrics.small))
            ChipRow {
                links.forEach { s -> Chip(s.label, Tags.source(s.id), icon = R.drawable.ph_arrow_square_out) { s.link?.let { Browser.open(ctx, it) } } }
            }
        }
        statuses.filter { it.state == SearchEngine.State.DISABLED }.forEach { s ->
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.small), modifier = Modifier.testTag(Tags.source(s.id))) {
                Ph(R.drawable.ph_warning, Metrics.iconXs, g.text2)
                Text(stringResource(R.string.src_disabled, s.label, s.detail), color = g.text2, style = Type.style(Type.label))
            }
        }
    }
}

/** .card for a result: the image area (the vertical's icon: no image loader), title, subtitle, badge, source line, save. */
@Composable
fun ListingCard(l: Listing) {
    val state = LocalState.current
    val g = LocalGlass.current
    val ctx = LocalContext.current
    var saved by remember(l.key) { mutableStateOf(state.services.saved.isSaved(l.key)) }
    GlassCard(Modifier.testTag(Tags.card(l.key)), onClick = l.url?.let { u -> { Browser.open(ctx, u) } }) {
        val v = state.cfg.verticals.firstOrNull { l.source in it.sources }
        Box(
            Modifier.fillMaxWidth().height(Metrics.placeholderHeight).clip(RoundedCornerShape(Metrics.placeholderRadius)).background(g.field),
            contentAlignment = Alignment.Center,
        ) { DeclaredIcon(v?.icon ?: "", Metrics.iconLg, g.text2) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Column(Modifier.weight(1f)) {
                Text(l.title, color = g.text, style = Type.style(Type.cardTitle, FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (l.subtitle.isNotBlank()) Text(l.subtitle, color = g.text2, style = Type.style(Type.cardSubtitle), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val price = l.price?.let { p ->
                val unit = when (l.unit) { null -> ""; "h" -> stringResource(R.string.per_hour); "yr" -> stringResource(R.string.per_year); else -> "/" + l.unit }
                money(p, l.currency) + unit
            }
            if (price != null) Badge(price, g.priceBadge) else l.tags.firstOrNull()?.let { Badge(it, g.accent) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.small)) {
            if (l.verified) Ph(R.drawable.ph_seal_check, Metrics.iconSm, g.accent, stringResource(R.string.verified))
            val meta = listOfNotNull(state.cfg.sources[l.source]?.label, l.date?.let { day(it) }).joinToString(" · ")
            Text(meta, color = g.text2, style = Type.style(Type.label), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(Modifier.clip(CircleShape).clickable { saved = state.services.saved.toggle(l) }.padding(Metrics.small)) {
                Ph(if (saved) R.drawable.ph_star_fill else R.drawable.ph_star, Metrics.icon, if (saved) g.accent else g.text2,
                    stringResource(if (saved) R.string.unsave else R.string.save))
            }
        }
    }
}

/** A default as the user would type it: 30, not 30.0. */
fun plain(d: Double): String = if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

/** Saved Items: every starred result, newest first, kept whole so it opens offline. */
@Composable
fun SavedPage() {
    val state = LocalState.current
    val g = LocalGlass.current
    val saved = remember { state.services.saved.all() }
    LazyColumn(Modifier.fillMaxSize().testTag(Tags.SAVED), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        item { TopicHeader(stringResource(R.string.saved_items), stringResource(R.string.saved_desc)) }
        if (saved.isEmpty()) item { Text(stringResource(R.string.saved_empty), color = g.text2, style = Type.style(Type.small)) }
        items(saved, key = { it.key }) { ListingCard(it) }
    }
}
