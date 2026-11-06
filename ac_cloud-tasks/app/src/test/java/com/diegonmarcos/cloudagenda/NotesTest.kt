package com.diegonmarcos.cloudagenda

import com.diegonmarcos.cloudagenda.data.CheckItem
import com.diegonmarcos.cloudagenda.data.Masonry
import com.diegonmarcos.cloudagenda.data.MemoryNotesStore
import com.diegonmarcos.cloudagenda.data.NoteCard
import com.diegonmarcos.cloudagenda.data.NoteFilters
import com.diegonmarcos.cloudagenda.data.NoteSorts
import com.diegonmarcos.cloudagenda.data.NotesDecl
import com.diegonmarcos.cloudagenda.data.NotesJson
import com.diegonmarcos.cloudagenda.data.NotesModel
import com.diegonmarcos.cloudagenda.data.NotesQueries
import com.diegonmarcos.cloudagenda.data.NotesQuery
import com.diegonmarcos.cloudagenda.data.NotesSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Inboxes > Notes: card CRUD and persistence, the notebook / section / page filter, search, sort and the masonry column count per
 * width, over the REAL declaration (data/notes/notes.json) and a memory store; the page itself (ui/NotesPage.kt) holds no name.
 */
@RunWith(RobolectricTestRunner::class)
class NotesTest {
    private val declText = File("../data/notes/notes.json").readText()
    private val decl = NotesDecl.parse(declText)
    private var clock = 1_000L
    private var seq = 0
    private fun model(store: MemoryNotesStore = MemoryNotesStore()) = NotesModel(store, decl, { clock }, { "id${++seq}" })

    private val d get() = decl.seed()
    private val ideasInbox = "pg-ideas-inbox"

    // ── CRUD ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a first run holds the declared notebooks, sections and pages and no cards`() {
        val m = model()
        assertEquals(listOf("nb-personal", "nb-work"), m.data.notebooks.map { it.id })
        assertTrue(m.data.sections.isNotEmpty() && m.data.pages.isNotEmpty())
        assertTrue(m.data.cards.isEmpty())
    }

    @Test fun `quick add makes a card on the page, the first line its title and the rest its body`() {
        val m = model()
        val c = m.addCard(ideasInbox, "Buy a lamp\nwarm light\nunder 40")!!
        assertEquals("Buy a lamp", c.title); assertEquals("warm light\nunder 40", c.body)
        assertEquals(ideasInbox, c.pageId); assertEquals(1_000L, c.created); assertEquals(1_000L, c.updated)
        assertEquals(listOf(c), m.data.cards)
        assertNull(m.addCard("no-such-page", "x")); assertNull(m.addCard(ideasInbox, "   "))
    }

    @Test fun `saving a card bumps updated and keeps created, pin and check toggle, delete removes`() {
        val m = model()
        val c = m.addCard(ideasInbox, "Plan")!!
        clock = 5_000L
        val saved = m.saveCard(c.copy(title = "Plan B", color = "amber", label = "soon", tags = listOf("trip"), checklist = listOf(CheckItem("a"), CheckItem("b"))))
        assertEquals(1_000L, saved.created); assertEquals(5_000L, saved.updated)
        assertEquals("Plan B", m.data.cards.single().title)
        m.togglePin(c.id); assertTrue(m.data.cards.single().pinned)
        m.togglePin(c.id); assertTrue(!m.data.cards.single().pinned)
        m.toggleCheck(c.id, 1); assertEquals(listOf(false, true), m.data.cards.single().checklist.map { it.done })
        m.toggleCheck(c.id, 9); assertEquals(listOf(false, true), m.data.cards.single().checklist.map { it.done })
        m.deleteCard(c.id); assertTrue(m.data.cards.isEmpty())
    }

    @Test fun `notebooks, sections and pages can be added`() {
        val m = model()
        val nb = m.addNotebook(" Travel ")!!
        val sec = m.addSection(nb.id, "Spain")!!
        val pg = m.addPage(sec.id, "Madrid")!!
        assertEquals("Travel", nb.name); assertEquals(nb.id, sec.notebookId); assertEquals(sec.id, pg.sectionId)
        assertTrue(decl.color(sec.color) != null)
        assertNull(m.addSection("nope", "x")); assertNull(m.addPage("nope", "x")); assertNull(m.addNotebook("  "))
    }

    // ── persistence ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `every change is persisted and a new model over the same store reads it back`() {
        val store = MemoryNotesStore()
        val m = model(store)
        val c = m.addCard(ideasInbox, "Keep me\nbody")!!
        m.saveCard(c.copy(pinned = true, color = "teal", label = "L", tags = listOf("x", "y"), checklist = listOf(CheckItem("one", true))))
        val back = model(store)
        assertEquals(m.data, back.data)
        val card = back.data.cards.single()
        assertEquals(listOf("x", "y"), card.tags); assertTrue(card.pinned); assertEquals(listOf(CheckItem("one", true)), card.checklist)
        back.deleteCard(card.id)
        assertTrue(model(store).data.cards.isEmpty())
    }

    @Test fun `the json codec round trips and an unreadable store falls back to the seed`() {
        val data = d.withCard(NoteCard("c1", ideasInbox, "T", "B", "red", "L", true, listOf("t"), listOf(CheckItem("i", true)), 1L, 2L))
        assertEquals(data, NotesJson.decode(NotesJson.encode(data)))
        assertEquals(d, model(MemoryNotesStore("not json")).data)
    }

    // ── the section / notebook filter ───────────────────────────────────────────────────────────────────────────────

    private fun filled(): NotesModel {
        val m = model()
        for ((page, title) in listOf("pg-ideas-inbox" to "A", "pg-ideas-later" to "B", "pg-reading-queue" to "C", "pg-meetings-notes" to "D")) { clock += 10; m.addCard(page, title) }
        return m
    }
    private fun titles(m: NotesModel, sel: NotesSelection) = NotesQueries.visible(m.data, NotesQuery(sel, sort = NoteSorts.TITLE)).map { it.title }

    @Test fun `a notebook shows the cards of all its sections, a section only its own, a page only its own`() {
        val m = filled()
        assertEquals(listOf("A", "B", "C"), titles(m, NotesSelection("nb-personal")))
        assertEquals(listOf("D"), titles(m, NotesSelection("nb-work")))
        assertEquals(listOf("A", "B"), titles(m, NotesSelection("nb-personal", "sec-ideas")))
        assertEquals(listOf("C"), titles(m, NotesSelection("nb-personal", "sec-reading")))
        assertEquals(listOf("B"), titles(m, NotesSelection("nb-personal", "sec-ideas", "pg-ideas-later")))
        assertEquals(emptyList<String>(), titles(m, NotesSelection("nb-personal", "sec-projects")))
    }

    @Test fun `the page list follows the section, and a quick add lands on the picked page or the first of the slice`() {
        val m = filled()
        assertEquals(listOf("pg-ideas-inbox", "pg-ideas-later"), m.data.pagesOf("nb-personal", "sec-ideas").map { it.id })
        assertEquals(4, m.data.pagesOf("nb-personal", "").size)
        assertEquals("pg-ideas-later", m.data.target(NotesSelection("nb-personal", "sec-ideas", "pg-ideas-later")))
        assertEquals("pg-reading-queue", m.data.target(NotesSelection("nb-personal", "sec-reading")))
        assertEquals("pg-ideas-inbox", m.data.target(NotesSelection("nb-personal")))
        assertNull(m.data.target(NotesSelection("nb-gone")))
    }

    // ── search, filter chips, sort ──────────────────────────────────────────────────────────────────────────────────

    @Test fun `search matches title, body, label, tags and checklist, a hash term matches a tag`() {
        val m = model()
        val a = m.addCard(ideasInbox, "Lamp\nwarm light")!!
        val b = m.addCard(ideasInbox, "Taxes")!!
        m.saveCard(b.copy(tags = listOf("money"), label = "urgent", checklist = listOf(CheckItem("file form"))))
        val sel = NotesSelection("nb-personal")
        fun hits(s: String) = NotesQueries.visible(m.data, NotesQuery(sel, search = s)).map { it.title }.toSet()
        assertEquals(setOf("Lamp"), hits("warm")); assertEquals(setOf("Taxes"), hits("urgent")); assertEquals(setOf("Taxes"), hits("FORM"))
        assertEquals(setOf("Taxes"), hits("#mon")); assertEquals(emptySet<String>(), hits("#warm")); assertEquals(setOf("Lamp", "Taxes"), hits("  "))
        assertEquals(setOf("Taxes"), hits("urgent form")); assertNotNull(a)
    }

    @Test fun `filter chips narrow to pinned, checklists, open items and tagged`() {
        val m = model()
        val a = m.addCard(ideasInbox, "P")!!; val b = m.addCard(ideasInbox, "C")!!; val c = m.addCard(ideasInbox, "T")!!
        m.saveCard(a.copy(pinned = true)); m.saveCard(b.copy(checklist = listOf(CheckItem("x", true)))); m.saveCard(c.copy(tags = listOf("t"), checklist = listOf(CheckItem("y"))))
        val sel = NotesSelection("nb-personal")
        fun by(f: String) = NotesQueries.visible(m.data, NotesQuery(sel, filter = f, sort = NoteSorts.TITLE)).map { it.title }
        assertEquals(listOf("P"), by(NoteFilters.PINNED)); assertEquals(listOf("C", "T"), by(NoteFilters.CHECKLIST))
        assertEquals(listOf("T"), by(NoteFilters.OPEN)); assertEquals(listOf("T"), by(NoteFilters.TAGGED)); assertEquals(listOf("C", "P", "T"), by(NoteFilters.ALL))
    }

    @Test fun `pinned cards lead, then updated and created newest first, or title A to Z`() {
        val m = model()
        clock = 100; val x = m.addCard(ideasInbox, "b")!!
        clock = 200; val y = m.addCard(ideasInbox, "a")!!
        clock = 300; val z = m.addCard(ideasInbox, "c")!!
        clock = 400; m.saveCard(x.copy(title = "b"))
        val sel = NotesSelection("nb-personal")
        fun order(s: String) = NotesQueries.visible(m.data, NotesQuery(sel, sort = s)).map { it.title }
        assertEquals(listOf("b", "c", "a"), order(NoteSorts.UPDATED)); assertEquals(listOf("c", "a", "b"), order(NoteSorts.CREATED)); assertEquals(listOf("a", "b", "c"), order(NoteSorts.TITLE))
        m.togglePin(y.id)
        assertEquals("a", order(NoteSorts.UPDATED).first()); assertEquals("a", order(NoteSorts.TITLE).first()); assertNotNull(z)
    }

    // ── masonry ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the column count follows the declared width steps, two on a phone and more on a tablet`() {
        val cols = decl.columns
        assertEquals(listOf(0, 600, 840), cols.map { it.minDp })
        for ((w, n) in listOf(0f to 2, 320f to 2, 411f to 2, 599f to 2, 600f to 3, 720f to 3, 839f to 3, 840f to 4, 1280f to 4)) assertEquals("at $w dp", n, Masonry.columnsFor(w, cols))
        assertEquals(2, Masonry.columnsFor(500f, emptyList()))
    }

    @Test fun `cards are placed in the shortest column, in order, and none is lost`() {
        assertEquals(listOf(listOf(0, 3), listOf(1), listOf(2)), Masonry.distribute(listOf(1, 1, 1, 1), 3).let { listOf(it[0], it[1], it[2]) })
        assertEquals(listOf(listOf(0), listOf(1, 2, 3)), Masonry.distribute(listOf(10, 2, 2, 2), 2))
        val placed = Masonry.distribute(List(11) { (it % 4) + 1 }, 4)
        assertEquals((0 until 11).toList(), placed.flatten().sorted())
        assertEquals(1, Masonry.distribute(listOf(3, 3), 0).size)
    }

    // ── declaration and the page's source ───────────────────────────────────────────────────────────────────────────

    @Test fun `the declaration carries the chips, palette and labels the page uses`() {
        assertEquals(listOf("updated", "created", "title"), decl.sorts.map { it.id })
        assertEquals(listOf("all", "pinned", "checklist", "open", "tagged"), decl.filters.map { it.id })
        assertTrue(decl.colors.size >= 6)
        assertEquals(decl.defaultSort, "updated"); assertEquals(decl.defaultFilter, "all")
        val ui = File("src/main/java/com/diegonmarcos/cloudagenda/ui/NotesPage.kt").readText()
        val keys = Regex("""\bt\("(\w+)"\)""").findAll(ui).map { it.groupValues[1] }.toSet() + setOf("add_notebook", "add_section", "add_page", "editor_pin", "editor_unpin")
        for (k in keys) assertTrue("label $k is not in notes.json", decl.t(k).isNotEmpty())
    }

    @Test fun `no notebook, section, page or label name is typed in the Kotlin`() {
        val names = decl.seed().let { s -> s.notebooks.map { it.name } + s.sections.map { it.name } + s.pages.map { it.name } }
        val kt = listOf("ui/NotesPage.kt", "data/Notes.kt").joinToString("\n") { File("src/main/java/com/diegonmarcos/cloudagenda/$it").readText() }
        for (n in names) assertTrue("\"$n\" is typed in Kotlin", !kt.contains("\"$n\""))
        for (l in decl.sorts + decl.filters) assertTrue("\"${l.label}\" is typed in Kotlin", !kt.contains("\"${l.label}\""))
    }
}
