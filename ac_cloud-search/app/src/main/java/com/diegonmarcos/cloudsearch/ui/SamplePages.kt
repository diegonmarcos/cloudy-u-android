package com.diegonmarcos.cloudsearch.ui

import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.cloudsearch.BuildConfig
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import org.json.JSONObject

/** #1472 build.json::sample_pages, baked as SAMPLE_PAGES_B64: the sample pages of Scrappers and 3D Models, page id -> blurb and dense rows. */
object SamplePages {
    data class Row(val title: String, val meta: String, val badge: String)
    data class Page(val id: String, val blurb: String, val rows: List<Row>)

    private val pages: Map<String, Page> by lazy {
        val o = JSONObject(String(Base64.decode(BuildConfig.SAMPLE_PAGES_B64, Base64.DEFAULT), Charsets.UTF_8)).optJSONObject("pages") ?: JSONObject()
        o.keys().asSequence().associateWith { id ->
            val p = o.getJSONObject(id)
            val rows = p.optJSONArray("rows")
            Page(id, p.optString("blurb"), (0 until (rows?.length() ?: 0)).map { i ->
                rows!!.getJSONObject(i).let { Row(it.optString("title"), it.optString("meta"), it.optString("badge")) }
            })
        }
    }

    fun page(id: String): Page? = pages[id]
}

object SampleTags {
    const val STRIP = "sample_strip"
    fun page(id: String) = "sample_page_$id"
    fun row(i: Int) = "sample_row_$i"
}

/** A sample section (Scrappers, 3D Models): its pages on the second row over one dense list of declared sample rows. */
@Composable
fun SampleSection(state: SearchState, section: String) {
    val pages = NAV.section(section)?.pages.orEmpty()
    val selected = state.samplePage[section]?.takeIf { id -> pages.any { it.id == id } } ?: pages.firstOrNull()?.id ?: return
    Column(Modifier.fillMaxSize()) {
        if (pages.size > 1) Box(Modifier.testTag(SampleTags.STRIP)) {
            NavBarTopSecondRow(
                pages = pages,
                selectedId = selected,
                onSelect = { state.samplePage[section] = it.id },
                dark = state.dark,
                underTopChrome = false,
                surface = NAV.stripSurface(section, state.dark),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().testTag(SampleTags.page(selected))) { SampleList(SamplePages.page(selected)) }
    }
}

@Composable
private fun SampleList(page: SamplePages.Page?) {
    val g = LocalGlass.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(Metrics.small)) {
        item { Text(page?.blurb.orEmpty(), color = g.text2, style = Type.style(Type.small), modifier = Modifier.padding(bottom = Metrics.small)) }
        itemsIndexed(page?.rows.orEmpty()) { i, r ->
            Row(Modifier.fillMaxWidth().testTag(SampleTags.row(i)).padding(vertical = Metrics.small), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                Column(Modifier.weight(1f)) {
                    Text(r.title, color = g.text, style = Type.style(Type.body, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.meta, color = g.text2, style = Type.style(Type.tiny), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(r.badge, color = g.accent, style = Type.style(Type.badge, FontWeight.Bold), maxLines = 1)
            }
            Hairline()
        }
    }
}
