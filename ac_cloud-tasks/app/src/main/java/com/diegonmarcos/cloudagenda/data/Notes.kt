package com.diegonmarcos.cloudagenda.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

// Inboxes > Notes: card notes in a notebook / section / page hierarchy. Pure Kotlin over org.json (no views, no clock of its own),
// so the whole behaviour (CRUD, persistence, the section / notebook filter, search, sort, the masonry columns) is unit-tested.
// Every name, label, colour, breakpoint and seed is DATA: data/notes/notes.json, read through [NotesDecl].

data class CheckItem(val text: String, val done: Boolean = false)

/** One freeform card. [body] is Markdown (libs:wysiwyg), [color] an id of the declared palette, [label] a short free tag line. */
data class NoteCard(
    val id: String,
    val pageId: String,
    val title: String = "",
    val body: String = "",
    val color: String = "",
    val label: String = "",
    val pinned: Boolean = false,
    val tags: List<String> = emptyList(),
    val checklist: List<CheckItem> = emptyList(),
    val created: Long = 0L,
    val updated: Long = 0L,
)

data class Notebook(val id: String, val name: String)
data class NoteSection(val id: String, val notebookId: String, val name: String, val color: String)
data class NotePage(val id: String, val sectionId: String, val name: String)

/** Which slice of the hierarchy is shown: a notebook, optionally narrowed to one section, optionally to one page ("" = all). */
data class NotesSelection(val notebookId: String = "", val sectionId: String = "", val pageId: String = "")

data class NotesData(
    val notebooks: List<Notebook> = emptyList(),
    val sections: List<NoteSection> = emptyList(),
    val pages: List<NotePage> = emptyList(),
    val cards: List<NoteCard> = emptyList(),
) {
    fun sectionsOf(notebookId: String) = sections.filter { it.notebookId == notebookId }

    /** The pages of [sectionId], or of every section of [notebookId] when no section is picked. */
    fun pagesOf(notebookId: String, sectionId: String): List<NotePage> {
        val ids = if (sectionId.isNotBlank()) setOf(sectionId) else sectionsOf(notebookId).map { it.id }.toSet()
        return pages.filter { it.sectionId in ids }
    }

    /** The page ids a selection covers: the section / notebook filter. A stale id (deleted section) covers nothing. */
    fun pageIds(sel: NotesSelection): Set<String> = when {
        sel.pageId.isNotBlank() -> pages.filter { it.id == sel.pageId }.map { it.id }.toSet()
        else -> pagesOf(sel.notebookId, sel.sectionId).map { it.id }.toSet()
    }

    /** Where a quick-added card lands: the picked page, else the first page of the picked section, else of the notebook. */
    fun target(sel: NotesSelection): String? = sel.pageId.takeIf { p -> pages.any { it.id == p } } ?: pagesOf(sel.notebookId, sel.sectionId).firstOrNull()?.id

    fun withCard(card: NoteCard): NotesData =
        if (cards.any { it.id == card.id }) copy(cards = cards.map { if (it.id == card.id) card else it }) else copy(cards = cards + card)

    fun withoutCard(id: String): NotesData = copy(cards = cards.filterNot { it.id == id })
}

// ── query: filter, search, sort ────────────────────────────────────────────────────────────────────────────────────────

object NoteFilters {
    const val ALL = "all"
    const val PINNED = "pinned"
    const val CHECKLIST = "checklist"
    const val OPEN = "open"
    const val TAGGED = "tagged"
}

object NoteSorts {
    const val UPDATED = "updated"
    const val CREATED = "created"
    const val TITLE = "title"
}

data class NotesQuery(
    val selection: NotesSelection = NotesSelection(),
    val search: String = "",
    val filter: String = NoteFilters.ALL,
    val sort: String = NoteSorts.UPDATED,
)

object NotesQueries {
    private fun haystack(c: NoteCard) =
        (listOf(c.title, c.body, c.label) + c.tags + c.checklist.map { it.text }).joinToString("\n").lowercase()

    /** Every term must match; a `#term` matches a tag by prefix. */
    fun matches(c: NoteCard, search: String): Boolean {
        val terms = search.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return true
        val hay = haystack(c)
        return terms.all { t -> if (t.startsWith("#") && t.length > 1) c.tags.any { it.lowercase().startsWith(t.drop(1)) } else t in hay }
    }

    fun passes(c: NoteCard, filter: String): Boolean = when (filter) {
        NoteFilters.PINNED -> c.pinned
        NoteFilters.CHECKLIST -> c.checklist.isNotEmpty()
        NoteFilters.OPEN -> c.checklist.any { !it.done }
        NoteFilters.TAGGED -> c.tags.isNotEmpty()
        else -> true
    }

    /** Pinned cards always lead; then the sort key: Updated and Created newest first, Title A to Z. */
    fun sorted(cards: List<NoteCard>, sort: String): List<NoteCard> {
        val key: Comparator<NoteCard> = when (sort) {
            NoteSorts.CREATED -> compareByDescending { it.created }
            NoteSorts.TITLE -> compareBy { it.title.lowercase() }
            else -> compareByDescending { it.updated }
        }
        return cards.sortedWith(compareByDescending<NoteCard> { it.pinned }.then(key))
    }

    fun visible(data: NotesData, q: NotesQuery): List<NoteCard> {
        val pages = data.pageIds(q.selection)
        return sorted(data.cards.filter { it.pageId in pages && passes(it, q.filter) && matches(it, q.search) }, q.sort)
    }
}

// ── masonry ────────────────────────────────────────────────────────────────────────────────────────────────────────────

data class ColumnStep(val minDp: Int, val columns: Int)

object Masonry {
    /** The column count at [widthDp]: the step with the largest min_dp not above it (the first step when none is). */
    fun columnsFor(widthDp: Float, steps: List<ColumnStep>): Int {
        if (steps.isEmpty()) return 2
        val sorted = steps.sortedBy { it.minDp }
        return (sorted.lastOrNull { widthDp >= it.minDp } ?: sorted.first()).columns.coerceAtLeast(1)
    }

    /** A card's height in text lines, to place it before it is measured: title, the preview, the checks shown, a tag line, a time line. */
    fun estimate(c: NoteCard, previewBlocks: Int, previewChecks: Int): Int {
        val bodyLines = c.body.lines().filter { it.isNotBlank() }.take(previewBlocks).sumOf { 1 + it.length / 28 }
        return 2 + bodyLines + c.checklist.take(previewChecks).size + (if (c.tags.isNotEmpty() || c.label.isNotBlank()) 1 else 0)
    }

    /** Places item indices into [columns] columns, each next to the shortest column so far (the lowest on a tie), keeping their order. */
    fun distribute(heights: List<Int>, columns: Int): List<List<Int>> {
        val n = columns.coerceAtLeast(1)
        val out = List(n) { ArrayList<Int>() }
        val used = IntArray(n)
        heights.forEachIndexed { i, h ->
            var col = 0
            for (c in 1 until n) if (used[c] < used[col]) col = c
            out[col].add(i)
            used[col] += h.coerceAtLeast(1)
        }
        return out
    }
}

// ── time ───────────────────────────────────────────────────────────────────────────────────────────────────────────────

object NoteTime {
    /** "HH:mm" for a time today, "d MMM" for another day of this year, "d MMM yyyy" otherwise. */
    fun label(ms: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        if (ms <= 0L) return ""
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone)
        return when {
            t.toLocalDate() == today -> t.format(DateTimeFormatter.ofPattern("HH:mm", locale))
            t.year == today.year -> t.format(DateTimeFormatter.ofPattern("d MMM", locale))
            else -> t.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
        }
    }
}

// ── the declaration ────────────────────────────────────────────────────────────────────────────────────────────────────

data class NoteColor(val id: String, val argb: Int)
data class NoteChoice(val id: String, val label: String)

/** data/notes/notes.json, parsed. Nothing here is named in Kotlin. */
class NotesDecl(
    val prefsKey: String,
    val columns: List<ColumnStep>,
    val railMinDp: Int,
    val previewBlocks: Int,
    val previewChecks: Int,
    val defaultSort: String,
    val defaultFilter: String,
    val colors: List<NoteColor>,
    val sorts: List<NoteChoice>,
    val filters: List<NoteChoice>,
    private val labels: Map<String, String>,
    private val seed: NotesData,
) {
    fun t(key: String): String = labels[key].orEmpty()
    fun color(id: String): NoteColor? = colors.firstOrNull { it.id == id }
    fun seed(): NotesData = seed

    companion object {
        const val ASSET = "notes.json"
        val EMPTY = NotesDecl("notes_v1", emptyList(), 600, 4, 3, NoteSorts.UPDATED, NoteFilters.ALL, emptyList(), emptyList(), emptyList(), emptyMap(), NotesData())

        private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
        private fun argb(hex: String): Int = (0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()

        fun parse(text: String): NotesDecl {
            val j = JSONObject(text)
            val store = j.optJSONObject("store") ?: JSONObject()
            val labels = j.optJSONObject("labels")?.let { l -> l.keys().asSequence().associateWith { l.optString(it) } }.orEmpty()
            val nbs = ArrayList<Notebook>(); val secs = ArrayList<NoteSection>(); val pgs = ArrayList<NotePage>()
            for (nb in j.optJSONObject("seed")?.optJSONArray("notebooks").objects()) {
                nbs += Notebook(nb.getString("id"), nb.optString("name"))
                for (s in nb.optJSONArray("sections").objects()) {
                    secs += NoteSection(s.getString("id"), nb.getString("id"), s.optString("name"), s.optString("color"))
                    for (p in s.optJSONArray("pages").objects()) pgs += NotePage(p.getString("id"), s.getString("id"), p.optString("name"))
                }
            }
            return NotesDecl(
                prefsKey = store.optString("key", EMPTY.prefsKey),
                columns = j.optJSONArray("columns").objects().map { ColumnStep(it.getInt("min_dp"), it.getInt("columns")) },
                railMinDp = j.optInt("rail_min_dp", EMPTY.railMinDp), previewBlocks = j.optInt("preview_blocks", EMPTY.previewBlocks),
                previewChecks = j.optInt("preview_checks", EMPTY.previewChecks),
                defaultSort = j.optString("default_sort", EMPTY.defaultSort), defaultFilter = j.optString("default_filter", EMPTY.defaultFilter),
                colors = j.optJSONArray("colors").objects().map { NoteColor(it.getString("id"), argb(it.getString("argb"))) },
                sorts = j.optJSONArray("sorts").objects().map { NoteChoice(it.getString("id"), it.optString("label")) },
                filters = j.optJSONArray("filters").objects().map { NoteChoice(it.getString("id"), it.optString("label")) },
                labels = labels, seed = NotesData(nbs, secs, pgs),
            )
        }

        fun load(context: Context): NotesDecl = runCatching { parse(context.assets.open(ASSET).bufferedReader().use { it.readText() }) }.getOrDefault(EMPTY)
    }
}

// ── persistence ────────────────────────────────────────────────────────────────────────────────────────────────────────

object NotesJson {
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    fun encode(d: NotesData): String = JSONObject()
        .put("notebooks", JSONArray(d.notebooks.map { JSONObject().put("id", it.id).put("name", it.name) }))
        .put("sections", JSONArray(d.sections.map { JSONObject().put("id", it.id).put("notebook", it.notebookId).put("name", it.name).put("color", it.color) }))
        .put("pages", JSONArray(d.pages.map { JSONObject().put("id", it.id).put("section", it.sectionId).put("name", it.name) }))
        .put("cards", JSONArray(d.cards.map { c ->
            JSONObject().put("id", c.id).put("page", c.pageId).put("title", c.title).put("body", c.body).put("color", c.color).put("label", c.label)
                .put("pinned", c.pinned).put("created", c.created).put("updated", c.updated)
                .put("tags", JSONArray(c.tags))
                .put("checklist", JSONArray(c.checklist.map { JSONObject().put("text", it.text).put("done", it.done) }))
        }))
        .toString()

    fun decode(text: String): NotesData {
        val j = JSONObject(text)
        return NotesData(
            notebooks = j.optJSONArray("notebooks").objects().map { Notebook(it.getString("id"), it.optString("name")) },
            sections = j.optJSONArray("sections").objects().map { NoteSection(it.getString("id"), it.optString("notebook"), it.optString("name"), it.optString("color")) },
            pages = j.optJSONArray("pages").objects().map { NotePage(it.getString("id"), it.optString("section"), it.optString("name")) },
            cards = j.optJSONArray("cards").objects().map { c ->
                NoteCard(
                    id = c.getString("id"), pageId = c.optString("page"), title = c.optString("title"), body = c.optString("body"),
                    color = c.optString("color"), label = c.optString("label"), pinned = c.optBoolean("pinned"),
                    tags = c.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                    checklist = c.optJSONArray("checklist").objects().map { CheckItem(it.optString("text"), it.optBoolean("done")) },
                    created = c.optLong("created"), updated = c.optLong("updated"),
                )
            },
        )
    }
}

/** Where the notes JSON lives. The app uses the prefs file declared in fleet-config.json (stores.tasks_notes); tests a memory one. */
interface NotesStore {
    fun read(): String?
    fun write(text: String)
}

class MemoryNotesStore(var text: String? = null) : NotesStore {
    override fun read() = text
    override fun write(text: String) { this.text = text }
}

class PrefsNotesStore(private val sp: SharedPreferences, private val key: String) : NotesStore {
    constructor(context: Context, decl: NotesDecl) : this(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE), decl.prefsKey)
    override fun read(): String? = sp.getString(key, null)
    override fun write(text: String) { sp.edit().putString(key, text).apply() }

    companion object {
        /** The prefs file, declared in fleet-config.json (stores.tasks_notes). */
        const val FILE = "tasks_notes"
    }
}

/**
 * The notes of the app: loads from [store] (the declared seed structure, with no cards, on a first run or an unreadable file),
 * and writes the whole document after every change. The UI reads [data] and calls the verbs; it keeps no copy of its own.
 */
class NotesModel(
    private val store: NotesStore,
    val decl: NotesDecl,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    var data: NotesData = store.read()?.let { runCatching { NotesJson.decode(it) }.getOrNull() } ?: decl.seed()
        private set

    private fun commit(next: NotesData): NotesData { data = next; store.write(NotesJson.encode(next)); return next }

    /** A new card on [pageId] (null when the page does not exist). The first line of [text] is the title, the rest the body. */
    fun addCard(pageId: String, text: String): NoteCard? {
        if (data.pages.none { it.id == pageId } || text.isBlank()) return null
        val now = clock()
        val lines = text.trim().lines()
        val card = NoteCard(newId(), pageId, title = lines.first().trim(), body = lines.drop(1).joinToString("\n").trim(), created = now, updated = now)
        commit(data.withCard(card))
        return card
    }

    /** Saves an edited card (its `updated` becomes now; a card not yet stored is created). */
    fun saveCard(card: NoteCard): NoteCard {
        val now = clock()
        val old = data.cards.firstOrNull { it.id == card.id }
        val next = card.copy(created = old?.created ?: card.created.takeIf { it > 0L } ?: now, updated = now)
        commit(data.withCard(next))
        return next
    }

    fun deleteCard(id: String) { commit(data.withoutCard(id)) }

    fun togglePin(id: String) {
        val c = data.cards.firstOrNull { it.id == id } ?: return
        saveCard(c.copy(pinned = !c.pinned))
    }

    fun toggleCheck(id: String, index: Int) {
        val c = data.cards.firstOrNull { it.id == id } ?: return
        if (index !in c.checklist.indices) return
        saveCard(c.copy(checklist = c.checklist.mapIndexed { i, it -> if (i == index) it.copy(done = !it.done) else it }))
    }

    fun addNotebook(name: String): Notebook? = name.trim().takeIf { it.isNotEmpty() }?.let { n -> Notebook(newId(), n).also { commit(data.copy(notebooks = data.notebooks + it)) } }

    fun addSection(notebookId: String, name: String): NoteSection? {
        if (data.notebooks.none { it.id == notebookId } || name.isBlank()) return null
        val color = decl.colors.getOrNull(data.sectionsOf(notebookId).size % decl.colors.size.coerceAtLeast(1))?.id.orEmpty()
        return NoteSection(newId(), notebookId, name.trim(), color).also { commit(data.copy(sections = data.sections + it)) }
    }

    fun addPage(sectionId: String, name: String): NotePage? {
        if (data.sections.none { it.id == sectionId } || name.isBlank()) return null
        return NotePage(newId(), sectionId, name.trim()).also { commit(data.copy(pages = data.pages + it)) }
    }
}
