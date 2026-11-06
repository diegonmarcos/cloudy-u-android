package com.diegonmarcos.cloudagenda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.diegonmarcos.cloudagenda.data.CheckItem
import com.diegonmarcos.cloudagenda.data.Masonry
import com.diegonmarcos.cloudagenda.data.NoteCard
import com.diegonmarcos.cloudagenda.data.NoteTime
import com.diegonmarcos.cloudagenda.data.NotesDecl
import com.diegonmarcos.cloudagenda.data.NotesModel
import com.diegonmarcos.cloudagenda.data.NotesQueries
import com.diegonmarcos.cloudagenda.data.NotesQuery
import com.diegonmarcos.cloudagenda.data.NotesSelection
import com.diegonmarcos.cloudlib.wysiwyg.BlockKind
import com.diegonmarcos.cloudlib.wysiwyg.Codecs
import com.diegonmarcos.cloudlib.wysiwyg.TextFormat
import com.diegonmarcos.cloudlib.wysiwyg.WysiwygColors
import com.diegonmarcos.cloudlib.wysiwyg.WysiwygEditor
import com.diegonmarcos.cloudlib.wysiwyg.annotate
import com.diegonmarcos.superapp.uikit.KitChip
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitSearchBar
import com.diegonmarcos.superapp.uikit.KitStoreQuick
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitTextButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** Test tags of Inboxes > Notes. */
object NotesTags {
    const val PAGE = "notes:page"
    const val GRID = "notes:grid"
    const val QUICK_FIELD = "notes:quick:field"
    const val QUICK_ADD = "notes:quick:add"
    const val EDITOR = "notes:editor"
    const val EDITOR_DONE = "notes:editor:done"
    const val EDITOR_DELETE = "notes:editor:delete"
    const val EDITOR_PIN = "notes:editor:pin"
    const val EMPTY = "notes:empty"
    fun notebook(id: String) = "notes:notebook:$id"
    fun section(id: String) = "notes:section:$id"
    fun page(id: String) = "notes:pagelist:$id"
    fun card(id: String) = "notes:card:$id"
    fun column(i: Int) = "notes:column:$i"
    fun sort(id: String) = "notes:sort:$id"
    fun filter(id: String) = "notes:filter:$id"
}

private enum class PromptKind { NOTEBOOK, SECTION, PAGE }

/**
 * Inboxes > Notes: a OneNote-style surface on the kit's own tokens. Notebooks (a left rail on a tablet, a strip on a phone), coloured
 * section tabs, a page list, and the cards of the picked page in a masonry (the column count comes from the declared width steps).
 * Quick-add, search and a sort / filter chip row sit over the cards; a card opens in an editor on libs:wysiwyg with tags and a checklist.
 * Every name and label is [NotesDecl] data; the cards persist through [NotesModel].
 */
@Composable
fun NotesPage(model: NotesModel, modifier: Modifier = Modifier, now: Long = System.currentTimeMillis()) {
    val decl = model.decl
    val p = LocalKitPalette.current
    var data by remember { mutableStateOf(model.data) }
    var nbId by rememberSaveable { mutableStateOf("") }
    var sectionPick by rememberSaveable { mutableStateOf("") }
    var pagePick by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(decl.defaultFilter) }
    var sort by rememberSaveable { mutableStateOf(decl.defaultSort) }
    var editing by rememberSaveable { mutableStateOf("") }
    var prompt by remember { mutableStateOf<PromptKind?>(null) }
    var notice by remember { mutableStateOf("") }
    fun refresh() { data = model.data }

    val notebook = data.notebooks.firstOrNull { it.id == nbId } ?: data.notebooks.firstOrNull()
    val sections = notebook?.let { data.sectionsOf(it.id) }.orEmpty()
    val sectionId = sectionPick.takeIf { s -> sections.any { it.id == s } }.orEmpty()
    val pages = data.pagesOf(notebook?.id.orEmpty(), sectionId)
    val pageId = pagePick.takeIf { s -> pages.any { it.id == s } }.orEmpty()
    val sel = NotesSelection(notebook?.id.orEmpty(), sectionId, pageId)
    val cards = NotesQueries.visible(data, NotesQuery(sel, search, filter, sort))

    val notebookStrip: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KitDensity.small, vertical = KitDensity.small / 2), horizontalArrangement = Arrangement.spacedBy(KitDensity.small), verticalAlignment = Alignment.CenterVertically) {
            for (n in data.notebooks) NotesChip(n.name, n.id == notebook?.id, Modifier.testTag(NotesTags.notebook(n.id))) { nbId = n.id; sectionPick = ""; pagePick = "" }
            PlusChip(decl.t("add_notebook")) { prompt = PromptKind.NOTEBOOK }
        }
    }
    val sectionTabs: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KitDensity.small), horizontalArrangement = Arrangement.spacedBy(KitDensity.small / 2), verticalAlignment = Alignment.Bottom) {
            SectionTab(decl.t("all_sections"), null, sectionId.isEmpty(), Modifier.testTag(NotesTags.section(""))) { sectionPick = ""; pagePick = "" }
            for (s in sections) SectionTab(s.name, decl.color(s.color)?.argb?.let { Color(it) }, s.id == sectionId, Modifier.testTag(NotesTags.section(s.id))) { sectionPick = s.id; pagePick = "" }
            if (notebook != null) PlusChip(decl.t("add_section")) { prompt = PromptKind.SECTION }
        }
    }
    val pageStrip: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KitDensity.small, vertical = KitDensity.small / 2), horizontalArrangement = Arrangement.spacedBy(KitDensity.small), verticalAlignment = Alignment.CenterVertically) {
            NotesChip(decl.t("all_pages"), pageId.isEmpty(), Modifier.testTag(NotesTags.page(""))) { pagePick = "" }
            for (pg in pages) NotesChip(pg.name, pg.id == pageId, Modifier.testTag(NotesTags.page(pg.id))) { pagePick = pg.id }
            if (sectionId.isNotEmpty()) PlusChip(decl.t("add_page")) { prompt = PromptKind.PAGE }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().testTag(NotesTags.PAGE)) {
        val widthDp = maxWidth.value
        val wide = widthDp >= decl.railMinDp
        val columns = Masonry.columnsFor(widthDp, decl.columns)
        Row(Modifier.fillMaxSize()) {
            if (wide) Column(Modifier.width(KitDensity.avatar * 3).fillMaxHeight().verticalScroll(rememberScrollState()).padding(KitDensity.small), verticalArrangement = Arrangement.spacedBy(KitDensity.small / 2)) {
                for (n in data.notebooks) RailItem(n.name, n.id == notebook?.id, Modifier.testTag(NotesTags.notebook(n.id))) { nbId = n.id; sectionPick = ""; pagePick = "" }
                PlusChip(decl.t("add_notebook")) { prompt = PromptKind.NOTEBOOK }
                Box(Modifier.fillMaxWidth().height(KitDensity.rule).background(p.hairline))
                RailItem(decl.t("all_pages"), pageId.isEmpty(), Modifier.testTag(NotesTags.page(""))) { pagePick = "" }
                for (pg in pages) RailItem(pg.name, pg.id == pageId, Modifier.testTag(NotesTags.page(pg.id))) { pagePick = pg.id }
                if (sectionId.isNotEmpty()) PlusChip(decl.t("add_page")) { prompt = PromptKind.PAGE }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (!wide) notebookStrip()
                sectionTabs()
                Box(Modifier.fillMaxWidth().height(KitDensity.rule).background(p.hairline))
                if (!wide) pageStrip()
                Toolbar(decl, search, { search = it }, filter, { filter = it }, sort, { sort = it })
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = KitDensity.small, vertical = KitDensity.small / 2), verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                    QuickAdd(decl) { text ->
                        val target = data.target(sel)
                        if (target == null) { notice = decl.t("no_page"); false }
                        else { notice = ""; model.addCard(target, text); refresh(); true }
                    }
                    if (notice.isNotEmpty()) Text(notice, color = p.warn, fontSize = KitDensity.caption)
                    if (cards.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = KitDensity.large).testTag(NotesTags.EMPTY), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(decl.t("empty_title"), color = p.textPrimary, fontSize = KitDensity.title, fontWeight = FontWeight.Bold)
                            Text(decl.t("empty_caption"), color = p.textSecondary, fontSize = KitDensity.caption)
                        }
                    } else {
                        val placed = Masonry.distribute(cards.map { Masonry.estimate(it, decl.previewBlocks, decl.previewChecks) }, columns)
                        Row(Modifier.fillMaxWidth().testTag(NotesTags.GRID), horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                            placed.forEachIndexed { ci, idxs ->
                                Column(Modifier.weight(1f).testTag(NotesTags.column(ci)), verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                                    for (i in idxs) {
                                        val c = cards[i]
                                        NoteCardView(c, decl, now, onOpen = { editing = c.id }, onPin = { model.togglePin(c.id); refresh() }, onCheck = { k -> model.toggleCheck(c.id, k); refresh() })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val open = data.cards.firstOrNull { it.id == editing }
    if (open != null) NoteEditor(
        card = open, decl = decl, now = now,
        onSave = { model.saveCard(it); refresh(); editing = "" },
        onDelete = { model.deleteCard(open.id); refresh(); editing = "" },
    )
    prompt?.let { kind ->
        NamePrompt(decl, decl.t(when (kind) { PromptKind.NOTEBOOK -> "add_notebook"; PromptKind.SECTION -> "add_section"; PromptKind.PAGE -> "add_page" }),
            onCancel = { prompt = null },
            onCreate = { name ->
                when (kind) {
                    PromptKind.NOTEBOOK -> model.addNotebook(name)?.let { nbId = it.id; sectionPick = ""; pagePick = "" }
                    PromptKind.SECTION -> notebook?.let { nb -> model.addSection(nb.id, name)?.let { sectionPick = it.id; pagePick = "" } }
                    PromptKind.PAGE -> model.addPage(sectionId, name)?.let { pagePick = it.id }
                }
                refresh(); prompt = null
            })
    }
}

// ── navigation parts ─────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun NotesChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    Text(
        label, color = if (selected) p.tileInk else p.textPrimary, fontSize = KitDensity.caption, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(KitDensity.corner)).background(if (selected) p.accent else p.surfaceSelected)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = KitDensity.small, vertical = KitDensity.small / 2),
    )
}

@Composable
private fun PlusChip(label: String, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    Text(
        "+", color = p.accent, fontSize = KitDensity.title, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(KitDensity.corner)).clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = KitDensity.small, vertical = KitDensity.small / 4),
    )
}

@Composable
private fun RailItem(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    Text(
        label, color = if (selected) p.accent else p.textPrimary, fontSize = KitDensity.body, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(KitDensity.small)).background(if (selected) p.surfaceSelected else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = KitDensity.small, vertical = KitDensity.small / 2),
    )
}

/** A coloured section tab: a rounded top, the section's colour (full when picked, tinted otherwise). [color] null = the neutral "all" tab. */
@Composable
private fun SectionTab(label: String, color: Color?, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    val base = color ?: p.accent
    val shape = RoundedCornerShape(topStart = KitDensity.small, topEnd = KitDensity.small)
    Text(
        label, color = if (selected) Color.Black else p.textPrimary, fontSize = KitDensity.body, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
        modifier = modifier.clip(shape).background(if (selected) base else base.copy(alpha = TAB_TINT)).clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = KitDensity.medium, vertical = KitDensity.small / 2 + KitDensity.small / 4),
    )
}

private const val TAB_TINT = 0.25f
private const val CARD_TINT = 0.16f

@Composable
private fun Toolbar(
    decl: NotesDecl, search: String, onSearch: (String) -> Unit, filter: String, onFilter: (String) -> Unit, sort: String, onSort: (String) -> Unit,
) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = KitDensity.small, vertical = KitDensity.small / 2), verticalArrangement = Arrangement.spacedBy(KitDensity.small / 2)) {
        KitSearchBar(search, onSearch, onSubmit = {}, placeholder = decl.t("search"), clearLabel = decl.t("search_clear"))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(KitDensity.small / 2), verticalAlignment = Alignment.CenterVertically) {
            for (f in decl.filters) NotesChip(f.label, f.id == filter, Modifier.testTag(NotesTags.filter(f.id))) { onFilter(f.id) }
            Text(decl.t("sort"), color = p.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.padding(start = KitDensity.small))
            for (s in decl.sorts) NotesChip(s.label, s.id == sort, Modifier.testTag(NotesTags.sort(s.id))) { onSort(s.id) }
        }
    }
}

@Composable
private fun QuickAdd(decl: NotesDecl, onAdd: (String) -> Boolean) {
    val p = LocalKitPalette.current
    var text by rememberSaveable { mutableStateOf("") }
    fun submit() { if (text.isNotBlank() && onAdd(text)) text = "" }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(KitDensity.corner)).background(p.surfaceSelected).padding(start = KitDensity.medium, end = KitDensity.small / 2, top = KitDensity.small / 2, bottom = KitDensity.small / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlainField(text, { text = it }, decl.t("quick_add"), Modifier.weight(1f).testTag(NotesTags.QUICK_FIELD), onDone = ::submit)
        KitStoreQuick(decl.t("quick_add_button"), KitStoreRow.BLUE_FILL, Modifier.clip(RoundedCornerShape(KitDensity.small)).testTag(NotesTags.QUICK_ADD)) { submit() }
    }
}

// ── the card ─────────────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun NoteCardView(card: NoteCard, decl: NotesDecl, now: Long, onOpen: () -> Unit, onPin: () -> Unit, onCheck: (Int) -> Unit) {
    val p = LocalKitPalette.current
    val tint = decl.color(card.color)?.argb?.let { Color(it) }
    val shape = RoundedCornerShape(KitDensity.corner)
    val colors = WysiwygColors(p.textPrimary, p.accent, p.surface, p.hairline, p.textSecondary)
    val doc = remember(card.body) { Codecs.decode(TextFormat.Markdown, card.body) }
    Column(
        Modifier.fillMaxWidth().testTag(NotesTags.card(card.id)).clip(shape).background(if (tint != null) tint.copy(alpha = CARD_TINT).compositeOver(p.surface) else p.surfaceSelected)
            .border(KitDensity.rule, p.hairline, shape).clickable(role = Role.Button, onClick = onOpen),
    ) {
        if (tint != null) Box(Modifier.fillMaxWidth().height(KitDensity.small / 2).background(tint))
        Column(Modifier.padding(KitDensity.small + KitDensity.small / 2), verticalArrangement = Arrangement.spacedBy(KitDensity.small / 2)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(card.title.ifBlank { decl.t("untitled") }, color = p.textPrimary, fontSize = KitDensity.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (card.pinned) Text("◆", color = p.accent, fontSize = KitDensity.body, modifier = Modifier.clickable(role = Role.Button, onClickLabel = decl.t("editor_unpin"), onClick = onPin).padding(start = KitDensity.small / 2))
            }
            for ((i, b) in doc.blocks.take(decl.previewBlocks).withIndex()) {
                if (b.kind == BlockKind.Rule || b.text.isBlank()) continue
                Row {
                    if (b.kind == BlockKind.Bullet) Text("•", color = p.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.padding(end = KitDensity.small / 2))
                    if (b.kind == BlockKind.Numbered) Text("${i + 1}.", color = p.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.padding(end = KitDensity.small / 2))
                    Text(
                        annotate(b, colors), color = p.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        fontSize = if (b.kind == BlockKind.Heading) KitDensity.body else KitDensity.caption,
                        fontWeight = if (b.kind == BlockKind.Heading) FontWeight.Bold else null,
                    )
                }
            }
            for ((i, item) in card.checklist.withIndex().take(decl.previewChecks)) {
                Row(Modifier.clickable(role = Role.Checkbox) { onCheck(i) }, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (item.done) "☑" else "☐", color = if (item.done) p.ok else p.textSecondary, fontSize = KitDensity.body, modifier = Modifier.padding(end = KitDensity.small / 2))
                    Text(item.text, color = if (item.done) p.textSecondary else p.textPrimary, fontSize = KitDensity.caption, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null)
                }
            }
            if (card.checklist.size > decl.previewChecks) Text("+${card.checklist.size - decl.previewChecks}", color = p.textSecondary, fontSize = KitDensity.caption)
            val meta = (listOf(card.label).filter { it.isNotBlank() } + card.tags.map { "#$it" }).joinToString("  ")
            if (meta.isNotEmpty()) Text(meta, color = p.accent, fontSize = KitDensity.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                NoteTime.label(card.updated, now).let { u -> if (card.created == card.updated || card.created <= 0L) u else NoteTime.label(card.created, now) + " · " + u },
                color = p.textSecondary, fontSize = KitDensity.caption, maxLines = 1,
            )
        }
    }
}

// ── the editor ───────────────────────────────────────────────────────────────────────────────────────────────────────────

/** The card editor: title, colour, label, tags, checklist above, the libs:wysiwyg body below. Leaving it (Done, Back) saves; Delete removes. */
@Composable
private fun NoteEditor(card: NoteCard, decl: NotesDecl, now: Long, onSave: (NoteCard) -> Unit, onDelete: () -> Unit) {
    val p = LocalKitPalette.current
    var draft by remember(card.id) { mutableStateOf(card) }
    var newTag by remember(card.id) { mutableStateOf("") }
    var newCheck by remember(card.id) { mutableStateOf("") }
    fun addTag() { val t = newTag.trim().removePrefix("#"); if (t.isNotEmpty() && t !in draft.tags) draft = draft.copy(tags = draft.tags + t); newTag = "" }
    fun addCheck() { val t = newCheck.trim(); if (t.isNotEmpty()) draft = draft.copy(checklist = draft.checklist + CheckItem(t)); newCheck = "" }
    Dialog(onDismissRequest = { onSave(draft) }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().testTag(NotesTags.EDITOR), color = p.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = KitDensity.small, vertical = KitDensity.small / 2), verticalAlignment = Alignment.CenterVertically) {
                    KitTextButton(onDelete, Modifier.testTag(NotesTags.EDITOR_DELETE)) { Text(decl.t("editor_delete"), color = p.bad) }
                    Box(Modifier.weight(1f))
                    KitTextButton({ draft = draft.copy(pinned = !draft.pinned) }, Modifier.testTag(NotesTags.EDITOR_PIN)) { Text(decl.t(if (draft.pinned) "editor_unpin" else "editor_pin"), color = p.accent) }
                    KitStoreQuick(decl.t("editor_done"), KitStoreRow.BLUE_FILL, Modifier.clip(RoundedCornerShape(KitDensity.small)).testTag(NotesTags.EDITOR_DONE)) { onSave(draft) }
                }
                Column(
                    Modifier.weight(0.45f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = KitDensity.medium),
                    verticalArrangement = Arrangement.spacedBy(KitDensity.small),
                ) {
                    PlainField(draft.title, { draft = draft.copy(title = it) }, decl.t("editor_title"), Modifier.fillMaxWidth(), size = KitDensity.head, bold = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(KitDensity.small), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        for (c in decl.colors) {
                            val on = c.id == draft.color
                            Box(Modifier.size(KitDensity.glyph - KitDensity.small).clip(CircleShape).background(Color(c.argb))
                                .border(if (on) KitDensity.small / 4 else KitDensity.rule, if (on) p.textPrimary else p.hairline, CircleShape)
                                .clickable(role = Role.RadioButton) { draft = draft.copy(color = if (on) "" else c.id) })
                        }
                    }
                    PlainField(draft.label, { draft = draft.copy(label = it) }, decl.t("editor_label"), Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(KitDensity.small / 2), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        for (t in draft.tags) KitChip("#$t", { draft = draft.copy(tags = draft.tags - t) }, tag = "notes:tag:$t")
                        PlainField(newTag, { newTag = it }, decl.t("editor_tag"), Modifier.width(KitDensity.avatar * 2), onDone = ::addTag)
                    }
                    for ((i, item) in draft.checklist.withIndex()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(item.done, { v -> draft = draft.copy(checklist = draft.checklist.mapIndexed { k, it -> if (k == i) it.copy(done = v) else it }) })
                            PlainField(item.text, { v -> draft = draft.copy(checklist = draft.checklist.mapIndexed { k, it -> if (k == i) it.copy(text = v) else it }) }, "", Modifier.weight(1f).padding(start = KitDensity.small))
                            Text("×", color = p.textSecondary, fontSize = KitDensity.title, modifier = Modifier.clickable(role = Role.Button) { draft = draft.copy(checklist = draft.checklist.filterIndexed { k, _ -> k != i }) }.padding(horizontal = KitDensity.small))
                        }
                    }
                    PlainField(newCheck, { newCheck = it }, decl.t("editor_check"), Modifier.fillMaxWidth(), onDone = ::addCheck)
                    Text(
                        decl.t("editor_created") + " " + NoteTime.label(card.created, now) + " · " + decl.t("editor_updated") + " " + NoteTime.label(card.updated, now),
                        color = p.textSecondary, fontSize = KitDensity.caption,
                    )
                }
                Box(Modifier.fillMaxWidth().height(KitDensity.rule).background(p.hairline))
                WysiwygEditor(card.body, TextFormat.Markdown, { draft = draft.copy(body = it) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NamePrompt(decl: NotesDecl, title: String, onCancel: () -> Unit, onCreate: (String) -> Unit) {
    val p = LocalKitPalette.current
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title, color = p.textPrimary) },
        text = { PlainField(name, { name = it }, decl.t("name_hint"), Modifier.fillMaxWidth(), onDone = { onCreate(name) }) },
        confirmButton = { KitTextButton({ onCreate(name) }) { Text(decl.t("create"), color = p.accent) } },
        dismissButton = { KitTextButton(onCancel) { Text(decl.t("cancel"), color = p.textSecondary) } },
    )
}

/** A bare text field on the kit's ramp, with a hint while empty. */
@Composable
private fun PlainField(
    value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier,
    size: TextUnit = KitDensity.body, bold: Boolean = false, onDone: (() -> Unit)? = null,
) {
    val p = LocalKitPalette.current
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty() && hint.isNotEmpty()) Text(hint, color = p.textSecondary, fontSize = size)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = p.textPrimary, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
            cursorBrush = SolidColor(p.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
