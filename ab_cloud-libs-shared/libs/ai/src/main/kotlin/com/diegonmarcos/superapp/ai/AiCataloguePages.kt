package com.diegonmarcos.superapp.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.superapp.modelcatalogue.CatalogueFetch
import com.diegonmarcos.superapp.modelcatalogue.FileCatalogueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The public OpenRouter catalogues, read with no key: the body of a 2xx, else null (the page then draws the bundled snapshot). */
val AiPublicFetch = CatalogueFetch { url ->
    runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        try { if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null } finally { c.disconnect() }
    }.getOrNull()
}

/**
 * The Model Catalogue as a full page (the composer's Model button): the tabs of model-tabs.json in the rows it declares under their header
 * (Frontier, Text, Search / Audio & Speech, Image, Video / Specialist, Tasks, Medium, Small), each filled with the baked snapshot of
 * OpenRouter's metadata (models-snapshot.json), and a live OpenRouter search box over all of it: one GET of the public models list on demand,
 * debounced, kept for the declared TTL, no polling. A tap on a chat model (a tab row or a search result) picks it and returns to the chat;
 * a model another API serves (images, videos, embeddings) is browsable and greyed. One column, ONE scroller below the tabs.
 * [fetch] is the host's network door for the list.
 */
@Composable
fun AiModelCatalogue(chat: AiChatState, theme: AiTheme, fetch: CatalogueFetch = AiPublicFetch, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    // Close, Back and a pick end the page: the chat's own page flag, or the host's own way out when it shows the catalogue outside the chat.
    val done = { chat.page = ""; onClose?.invoke(); Unit }
    val data = remember { runCatching { AiModelTabs.load(ctx) }.getOrNull() }
    val c = theme.colors
    val t = theme.type
    val m = theme.metrics
    val s = theme.strings
    var tab by remember { mutableStateOf(data?.order?.firstOrNull().orEmpty()) }
    var query by remember { mutableStateOf("") }
    BackHandler { done() }
    fun pick(e: AiModelEntry) {
        AiKnownModels.remember(e)
        if (chat.models.none { it.id == e.id }) chat.models = chat.models + e.model()
        chat.pick(e.id)
        done()
    }
    Column(modifier.fillMaxSize().testTag(AiTags.CATALOGUE_PAGE)) {
        Header(theme, s.catalogue, AiTags.CATALOGUE_CLOSE, { done() }) {
            Text(s.webSearch, color = c.text2, style = t.style(t.small))
            Switch(chat.web, { chat.toggleWeb() }, Modifier.padding(horizontal = m.small).testTag(AiTags.WEB),
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent, uncheckedTrackColor = c.field))
        }
        if (data == null) { Text(s.modelsUnavailable, color = c.negative, style = t.style(t.small), modifier = Modifier.padding(m.gutter)); return@Column }
        val shape = RoundedCornerShape(m.cardRadius)
        BasicTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            textStyle = t.style(t.chatInput).copy(color = c.text), cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = m.gutter).clip(shape).background(c.field).border(m.hairline, c.tileBorder, shape).testTag(AiTags.CAT_SEARCH),
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = m.cardPad, vertical = m.gap)) {
                    if (query.isEmpty()) Text(s.searchModels, color = c.text2, style = t.style(t.chatInput), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        data.layout.forEachIndexed { i, block ->
            if (block.header.isNotBlank()) Text(block.header, color = c.text2, style = t.style(t.label, FontWeight.Bold),
                modifier = Modifier.padding(start = m.gutter, top = m.gap).testTag(AiTags.catHeader(i)))
            block.rows.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = m.gutter, vertical = m.tiny), horizontalArrangement = Arrangement.spacedBy(m.gap)) {
                    row.forEach { id ->
                        Text(
                            data.labels.getValue(id), Modifier.weight(1f).clip(shape).background(if (id == tab) c.field else Color.Transparent)
                                .clickable { tab = id; query = "" }.padding(vertical = m.gap).testTag(AiTags.catTab(id)),
                            color = if (id == tab) c.accent else c.text2, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = t.style(t.small, if (id == tab) FontWeight.Bold else FontWeight.Medium),
                        )
                    }
                }
            }
        }
        val q = query.trim()
        if (q.length >= data.search.minChars) SearchResults(chat, theme, data, q, fetch, ::pick, Modifier.weight(1f))
        else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = m.gutter, vertical = m.gap)) {
            val entries = data.entries(tab)
            Text("${data.labels[tab].orEmpty()} · ${entries.size} · ${data.generated}", color = c.text2, style = t.style(t.label), modifier = Modifier.padding(bottom = m.small))
            if (entries.isEmpty()) Text(s.noModelsInTab, color = c.text2, style = t.style(t.small))
            var domain: String? = null
            entries.forEach { e ->
                if (e.domain.isNotEmpty() && e.domain != domain) {
                    domain = e.domain
                    Text(e.domain, color = c.text, style = t.style(t.chatInput, FontWeight.Bold), modifier = Modifier.padding(top = m.gap))
                }
                ModelRow(chat, theme, e, AiTags.catRow(e.id)) { pick(e) }
            }
        }
    }
}

/** The live search: debounced, one fetch of OpenRouter's public list per TTL (never polled), filtered by name, id, provider and modality. */
@Composable
private fun SearchResults(chat: AiChatState, theme: AiTheme, data: AiModelTabs, query: String, fetch: CatalogueFetch, onPick: (AiModelEntry) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val c = theme.colors
    val t = theme.type
    val m = theme.metrics
    val s = theme.strings
    var results by remember { mutableStateOf<List<AiModelEntry>>(emptyList()) }
    var state by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        state = s.searchingModels
        delay(data.search.debounceMs)
        val live = withContext(Dispatchers.IO) { liveList(ctx, data, fetch).get() }
        val pool = live ?: data.all.values.toList()
        results = AiModelSearch.filter(pool, query, data.search.maxResults)
        state = if (live == null) s.searchOffline else if (results.isEmpty()) s.noModelMatches else ""
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = m.gutter, vertical = m.gap)) {
        Text(s.searchResults + " · " + results.size, color = c.text2, style = t.style(t.label), modifier = Modifier.padding(bottom = m.small))
        if (state.isNotEmpty()) Text(state, color = c.text2, style = t.style(t.small), modifier = Modifier.testTag(AiTags.CAT_SEARCH_STATE))
        results.forEach { e -> ModelRow(chat, theme, e, AiTags.catResult(e.id)) { onPick(e) } }
    }
}

private object LiveList {
    @Volatile var held: Triple<CatalogueFetch, String, AiModelListCache>? = null
}

/** The process's one live list per door and URL, so the TTL holds across visits to the page. */
private fun liveList(ctx: android.content.Context, data: AiModelTabs, fetch: CatalogueFetch): AiModelListCache {
    LiveList.held?.let { (f, u, cache) -> if (f === fetch && u == data.search.url) return cache }
    val cache = AiModelListCache(data.search.url, data.search.ttlMs, fetch, FileCatalogueStore(File(ctx.filesDir, "model-catalogue")), data.providerLabels)
    LiveList.held = Triple(fetch, data.search.url, cache)
    return cache
}

/** One dense model row: name, "provider · id", then context, price and modalities; its link opens through the host. */
@Composable
private fun ModelRow(chat: AiChatState, theme: AiTheme, e: AiModelEntry, tag: String, onPick: () -> Unit) {
    val c = theme.colors
    val t = theme.type
    val m = theme.metrics
    val on = e.chatCapable
    Column(Modifier.fillMaxWidth().alpha(if (on) 1f else DIM).clickable(enabled = on, onClick = onPick).padding(vertical = m.small).testTag(tag)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.name, Modifier.weight(1f), color = if (e.id == chat.model) c.accent else c.text, style = t.style(t.small, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("↗", color = c.accent, style = t.style(t.chatInput), modifier = Modifier.clickable { chat.host().openUrl(e.link) }.padding(horizontal = m.gap))
        }
        Text(e.provider + " · " + e.id, color = c.text2, style = t.style(t.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(AiModelText.spec(e), color = c.text2, style = t.style(t.label))
        val note = listOf(e.note, if (on) "" else theme.strings.browseOnly).filter { it.isNotBlank() }.joinToString(" · ")
        if (note.isNotEmpty()) Text(note, color = c.text2, style = t.style(t.label))
    }
}

/** The greyed alpha of a browse-only row: the theme's own disabled look. */
private const val DIM = 0.45f

/**
 * The Task Catalogue as a full page (the composer's Task button): the shared groups (Big LLMs; Medium & Small LLMs) with their
 * categories, then the app's own tasks the catalogue does not list. A task the app does not offer is greyed; selecting one
 * greys Agent and Model and sets them from its mapping, then returns to the chat. One column, one scroller.
 */
@Composable
fun AiTaskCatalogue(chat: AiChatState, theme: AiTheme, decl: AiComposerDecl, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val cat = remember { runCatching { AiCatalogue.load(ctx) }.getOrNull() }
    val c = theme.colors
    val t = theme.type
    val m = theme.metrics
    val s = theme.strings
    BackHandler { chat.page = "" }
    Column(modifier.fillMaxSize().testTag(AiTags.TASK_PAGE)) {
        Header(theme, s.taskCatalogue, AiTags.TASK_CLOSE, { chat.page = "" }) {
            if (!decl.taskRequired) Text(AiDesign.load(ctx).noTask, color = if (chat.task.isEmpty()) c.accent else c.text, style = t.style(t.chatInput, FontWeight.SemiBold),
                modifier = Modifier.clickable { chat.chooseTask(""); chat.page = "" }.padding(horizontal = m.gap).testTag(AiTags.TASK_NONE))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = m.gutter, vertical = m.gap),
            verticalArrangement = Arrangement.spacedBy(m.small)) {
            val offered = chat.tasks.map { it.id }.toSet()
            cat?.groups?.forEach { g ->
                Text(g.label, color = c.text, style = t.style(t.chat, FontWeight.Bold), modifier = Modifier.padding(top = m.gap).testTag(AiTags.taskGroup(g.id)))
                g.categories.forEach { k ->
                    Text(k.label, color = c.text2, style = t.style(t.chatInput, FontWeight.SemiBold), modifier = Modifier.padding(top = m.small))
                    k.items.forEach { item -> TaskRow(chat, theme, item.id, item.label, item.task, item.id in offered, item.note) }
                }
            }
            val known = cat?.tasks?.map { it.id }.orEmpty().toSet()
            val own = chat.tasks.filter { it.id !in known }
            if (own.isNotEmpty()) {
                Text(s.appTasks, color = c.text, style = t.style(t.chat, FontWeight.Bold), modifier = Modifier.padding(top = m.gap).testTag(AiTags.taskGroup("app")))
                own.forEach { TaskRow(chat, theme, it.id, it.label, it, true, it.note) }
            }
        }
    }
}

@Composable
private fun TaskRow(chat: AiChatState, theme: AiTheme, id: String, label: String, task: AiTask?, offered: Boolean, note: String?) {
    val c = theme.colors
    val t = theme.type
    val m = theme.metrics
    val on = offered && task != null
    Column(
        Modifier.fillMaxWidth().alpha(if (on) 1f else 0.45f).clickable(enabled = on) { chat.chooseTask(id); chat.page = "" }
            .padding(vertical = m.small).testTag(AiTags.task(id)),
    ) {
        Text(label, color = if (id == chat.task) c.accent else c.text, style = t.style(t.small, FontWeight.SemiBold))
        val sub = if (task != null) (if (offered) "" else theme.strings.taskNotOffered + " · ") + task.agent + " · " + task.model.substringAfterLast('/') else theme.strings.taskNotOffered
        Text(listOfNotNull(sub, note).filter { it.isNotBlank() }.joinToString(" · "), color = c.text2, style = t.style(t.label))
    }
}

@Composable
private fun Header(theme: AiTheme, title: String, closeTag: String, onClose: () -> Unit, extra: @Composable () -> Unit = {}) {
    val c = theme.colors
    val t = theme.type
    val m = theme.metrics
    Row(Modifier.fillMaxWidth().padding(horizontal = m.gutter, vertical = m.gap), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = c.text, style = t.style(t.chat, FontWeight.Bold))
        extra()
        Text(theme.strings.close, color = c.accent, style = t.style(t.chatInput, FontWeight.SemiBold),
            modifier = Modifier.clickable(onClick = onClose).padding(horizontal = m.gap).testTag(closeTag))
    }
}
