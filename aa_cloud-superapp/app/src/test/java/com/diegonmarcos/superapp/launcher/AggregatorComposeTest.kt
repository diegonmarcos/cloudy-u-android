package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the aggregator page in Compose (was ScrollView + SwipeRefreshLayout + hand-built Views), tested where the
 * View version could only be grepped.
 *
 *   G1 a One UI 8 container is folded (newest + "+N" pill) and opens/folds on a header or pill tap
 *   G2 the long-press sheet's Mark read marks the group read and the read set is persisted under the page id
 *   G3 the sheet's Archive files the box away; the Archive card says Restore
 *   G4 a right swipe toggles read (never archives); the row sheet flips read both ways
 *   C1 a container draws the One UI header (name • 2m), the title, the body and the Mark read / Archive / Open / Reply pills
 *   S1 a left swipe (One UI's dismiss) files the row into the Archive
 *   B1 the long-press menu is a bottom sheet (a dialog window), not an anchored dropdown
 *   M1 Mark all read / M2 Archive all as the centred pill row under the list
 *   T1 the top filter row (page tabs + toggle row) is unchanged
 *   F1 the Filters row draws the declared toggles, persists a pick, and a Tools pick returns Services to All
 *   P1 the page draws one card per declared panel, folds on a header tap and on the bottom-nav re-tap
 *   P2 a phone-stream card without Notification Access says so instead of drawing an empty list
 *   P3 an `anchor:` tile scrolls THIS page to the card that claimed the id
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AggregatorComposeTest : KitPageHarness() {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    private val realPoller = AggregatorModel.ntfyPoller

    // Hermetic: the page polls the ntfy relay on its own; a unit test answers "every channel is quiet" instead of
    // opening a socket.
    @Before
    fun quietRelay() {
        AggregatorModel.ntfyPoller = { topics -> topics.associateWith { NtfyResult(true, "", emptyList()) } }
    }

    @After
    fun restoreRelay() {
        AggregatorModel.ntfyPoller = realPoller
    }

    private fun row(id: String, ts: Long, title: String = "title $id") =
        NotifRow(ts = ts, title = title, text = "body $id", id = id)

    private fun view(vararg rows: NotifRow, key: String = "pkg.a") = GroupView(
        NotifGroup(key = key, label = "App A", sub = key, rows = rows.toList()),
        GroupChip("${rows.size} new · ${rows.size}", Tone.OK),
    )

    private fun state(page: String = "test-page") = AggregatorState(ctx, page, emptyList(), emptyList())

    @Test
    fun `G1 a folded group shows its newest item and a +N pill, and opens and folds on tap`() {
        val st = state()
        showKit { GroupBlock(st, view(row("phone:1", 100), row("phone:2", 200)), archived = false) { } }
        compose.onNodeWithTag(AggTags.groupHeader("pkg.a"), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("App A").assertExists()
        // One UI's default: the newest on top, "+N" for the rest, chevron in a circle.
        compose.onNodeWithText("title phone:2").assertExists()
        compose.onNodeWithText("title phone:1").assertDoesNotExist()
        compose.onNodeWithTag(AggTags.groupMore("pkg.a")).assertTextContains("+1")
        compose.onNodeWithTag(AggTags.groupChevron("pkg.a"), useUnmergedTree = true).assertExists()
        // A plain count is the pill's job, so the OK chip is not drawn.
        compose.onNodeWithTag(AggTags.groupChip("pkg.a")).assertDoesNotExist()
        compose.onNodeWithTag(AggTags.groupHeader("pkg.a")).performClick()
        compose.onNodeWithText("title phone:1").assertExists()
        compose.onNodeWithTag(AggTags.groupMore("pkg.a")).assertDoesNotExist()
        assertEquals(false, st.collapsedGroups["pkg.a"])
        compose.onNodeWithTag(AggTags.groupHeader("pkg.a")).performClick()
        compose.onNodeWithText("title phone:1").assertDoesNotExist()
        // The fold lives on the state, so it survives a re-render (a toggle tap rebuilds every body).
        assertEquals(true, st.collapsedGroups["pkg.a"])
        // The "+N" pill opens it too.
        compose.onNodeWithTag(AggTags.groupMore("pkg.a")).performClick()
        compose.onNodeWithText("title phone:1").assertExists()
    }

    @Test
    fun `C1 a container renders the One UI header, the title, the body and the box's action pills`() {
        val st = state("c1-page")
        showKit { GroupBlock(st, view(row("phone:1", System.currentTimeMillis() - 120_000L)), archived = false) { } }
        compose.onNodeWithText("App A").assertExists()
        // " • 2m": One UI's short clock.
        compose.onNodeWithText(" • 2m").assertExists()
        compose.onNodeWithTag(AggTags.row("phone:1")).assertExists()
        compose.onNodeWithText("title phone:1").assertExists()
        compose.onNodeWithText("body phone:1").assertExists()
        compose.onNodeWithTag(AggTags.rowUnread("phone:1"), useUnmergedTree = true).assertExists()
        // A single notification is not a stack: no "+N".
        compose.onNodeWithTag(AggTags.groupMore("pkg.a")).assertDoesNotExist()
        // The box's own pills (#1068) are drawn; the sheet's items only exist once the sheet is open.
        for (a in NotifActions.REQUIRED_PER_BOX) compose.onNodeWithTag(NotifActions.tag(a, "pkg.a")).assertExists()
        compose.onNodeWithTag(AggTags.groupCheck("pkg.a")).assertDoesNotExist()
        compose.onNodeWithTag(AggTags.groupArchive("pkg.a")).assertDoesNotExist()
        compose.onNodeWithTag(AggTags.groupHeader("pkg.a")).performTouchInput { longClick(centerLeft + androidx.compose.ui.geometry.Offset(40f, 0f)) }
        compose.onNodeWithTag(AggTags.groupCheck("pkg.a")).assertTextContains(AggregatorModel.MARK_READ_LABEL)
        compose.onNodeWithTag(AggTags.groupArchive("pkg.a")).assertTextContains(AggregatorModel.ARCHIVE_LABEL)
    }

    @Test
    fun `G2 the long-press sheet marks every row of the group read and persists it`() {
        val st = state("g2-page")
        val v = view(row("phone:1", 100), row("phone:2", 200))
        showKit { GroupBlock(st, v, archived = false) { } }
        assertTrue(st.isUnread(v.group.rows[0]))
        compose.onNodeWithTag(AggTags.groupHeader("pkg.a")).performTouchInput { longClick(centerLeft + androidx.compose.ui.geometry.Offset(40f, 0f)) }
        compose.onNodeWithTag(AggTags.groupCheck("pkg.a")).performClick()
        assertFalse(st.isUnread(v.group.rows[0]))
        assertFalse(st.isUnread(v.group.rows[1]))
        // A new state on the same page (the process died and came back) still knows.
        assertEquals(setOf("phone:1", "phone:2"), StackFilters.readKeys(ctx, "g2-page"))
    }

    @Test
    fun `G3 the sheet's Archive files a box away and the Archive card offers Restore`() {
        val st = state("g3-page")
        val v = view(row("phone:1", 100))
        showKit { GroupBlock(st, v, archived = st.isArchived("pkg.a")) { } }
        compose.onNodeWithTag(AggTags.groupHeader("pkg.a")).performTouchInput { longClick(centerLeft + androidx.compose.ui.geometry.Offset(40f, 0f)) }
        compose.onNodeWithTag(AggTags.groupArchive("pkg.a")).assertTextContains(AggregatorModel.ARCHIVE_LABEL)
            .performClick()
        assertTrue(st.isArchived("pkg.a"))
        // Remembered per page, like every other choice on it.
        assertEquals("yes", StackFilters.selected(ctx, "g3-page", st.yesNo(AggregatorModel.ARCHIVED_PREFIX + "pkg.a")))
        compose.waitForIdle()
        compose.onNodeWithTag(AggTags.groupArchive("pkg.a")).assertTextContains(AggregatorModel.ARCHIVE_RESTORE_LABEL)
            .performClick()
        assertFalse(st.isArchived("pkg.a"))
    }

    @Test
    fun `G4 a right swipe toggles read, and the row sheet flips read both ways`() {
        val st = state("g4-page")
        val r = row("phone:1", 300)  // newest: on top of the stack
        val r2 = row("phone:2", 200)
        showKit { GroupBlock(st, view(r, r2), archived = false) { } }
        assertTrue(st.isUnread(r))
        compose.onNodeWithTag(AggTags.row("phone:1")).performTouchInput { longClick(centerLeft + androidx.compose.ui.geometry.Offset(40f, 0f)) }
        compose.onNodeWithTag(AggTags.rowRead("phone:1")).assertTextContains(AggregatorModel.MARK_READ_LABEL).performClick()
        assertFalse(st.isUnread(r))
        compose.onNodeWithTag(AggTags.row("phone:1")).performTouchInput { longClick(centerLeft + androidx.compose.ui.geometry.Offset(40f, 0f)) }
        compose.onNodeWithTag(AggTags.rowRead("phone:1")).assertTextContains("Mark unread").performClick()
        assertTrue(st.isUnread(r))
        assertEquals(setOf<String>(), StackFilters.readKeys(ctx, "g4-page"))
        compose.onNodeWithTag(AggTags.row("phone:1")).performTouchInput { swipeRight() }
        compose.waitUntil(5_000) { !st.isUnread(r) }
        compose.waitForIdle()
        // Right is read/unread, never a dismiss.
        assertFalse(st.isRowArchived(r))
        assertEquals(setOf("phone:1"), StackFilters.readKeys(ctx, "g4-page"))
        compose.onNodeWithTag(AggTags.row("phone:1")).performTouchInput { swipeRight() }
        compose.waitUntil(5_000) { st.isUnread(r) }
        assertFalse(st.isRowArchived(r))
    }

    @Test
    fun `B1 the long-press menu opens as a bottom sheet`() {
        val st = state("b1-page")
        showKit { GroupBlock(st, view(row("phone:1", 100)), archived = false) { } }
        compose.onNodeWithTag(AggTags.SHEET).assertDoesNotExist()
        compose.onNodeWithTag(AggTags.row("phone:1")).performTouchInput { longClick(centerLeft + androidx.compose.ui.geometry.Offset(40f, 0f)) }
        compose.onNodeWithTag(AggTags.SHEET).assertExists()
        compose.onNodeWithTag(AggTags.rowArchive("phone:1")).performClick()
        compose.waitUntil(5_000) { st.isRowArchived(row("phone:1", 100)) }
    }

    @Test
    fun `G5 a vertical drag on a row does not mark it read`() {
        val st = state("g5-page")
        val r = row("phone:1", 100)
        showKit { GroupBlock(st, view(r), archived = false) { } }
        compose.onNodeWithTag(AggTags.row("phone:1")).performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(centerX, top + 2f), androidx.compose.ui.geometry.Offset(centerX, bottom - 2f))
        }
        compose.waitForIdle()
        assertTrue(st.isUnread(r))
    }

    @Test
    fun `S1 a left swipe archives the row and the Archive swipe restores it`() {
        val st = state("s1-page")
        val r = row("phone:1", 100)
        val v = view(r)
        showKit { androidx.compose.foundation.layout.Column { GroupList(st, listOf(v)) { } } }
        compose.onNodeWithTag(AggTags.row("phone:1")).performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { st.isRowArchived(r) }
        compose.waitForIdle()
        assertEquals(setOf("phone:1"), StackFilters.archivedKeys(ctx, "s1-page"))
        // A box whose every row was swiped away leaves the list.
        compose.onNodeWithTag(AggTags.group("pkg.a")).assertDoesNotExist()
        // Read state is untouched by an archive.
        assertTrue(st.isUnread(r))
        st.archiveRow("phone:1", false)
        compose.waitForIdle()
        compose.onNodeWithTag(AggTags.row("phone:1")).assertExists()
    }

    private fun seededCloudState(page: String): AggregatorState {
        com.diegonmarcos.superapp.core.NotificationStore.clear(ctx)
        for ((src, t) in listOf("Mail" to "Invoice", "Mail" to "Standup", "Chat" to "Ping")) {
            com.diegonmarcos.superapp.core.NotificationStore.push(ctx, src, t, "body of $t")
        }
        val panel = Sections.StackPanel(kind = "notification_center", title = "Cloud", stream = "cloud")
        return AggregatorState(ctx, page, listOf(panel), emptyList())
    }

    @Test
    fun `M1 Mark all read marks every row on the page read`() {
        val st = seededCloudState("m1-page")
        showKit {
            androidx.compose.foundation.layout.Column {
                NotifyListActions(st)
                GroupList(st, st.localFeedViews()) { }
            }
        }
        val rows = st.localFeedViews().flatMap { it.group.rows }
        assertEquals(3, rows.size)
        assertTrue(rows.all { st.isUnread(it) })
        compose.onNodeWithTag(AggTags.MARK_ALL_READ).assertTextContains(AggregatorModel.MARK_ALL_READ_LABEL)
            .performClick()
        assertTrue(rows.none { st.isUnread(it) })
        assertEquals(rows.map { it.id }.toSet(), StackFilters.readKeys(ctx, "m1-page"))
    }

    @Test
    fun `M2 Archive all files every box away and each can be restored`() {
        val st = seededCloudState("m2-page")
        showKit {
            androidx.compose.foundation.layout.Column {
                NotifyListActions(st)
                GroupList(st, st.localFeedViews()) { }
            }
        }
        compose.onNodeWithTag(AggTags.group("Mail")).assertExists()
        compose.onNodeWithTag(AggTags.ARCHIVE_ALL).assertTextContains(AggregatorModel.ARCHIVE_ALL_LABEL).performClick()
        assertTrue(st.isArchived("Mail") && st.isArchived("Chat"))
        compose.onNodeWithTag(AggTags.group("Mail")).assertDoesNotExist()
        compose.onNodeWithTag(AggTags.group("Chat")).assertDoesNotExist()
        st.setArchived("Chat", false)
        compose.waitForIdle()
        compose.onNodeWithTag(AggTags.group("Chat")).assertExists()
    }

    @Test
    fun `T1 the top filter row is unchanged - same tabs, same toggle row, actions sit below it`() {
        val sec = Sections.byId("communication")!!
        // The page tabs across the top of Notify.
        // #1672 (owner 2026-11-12): the Notify sub-pages; the old presets (Inboxes ...) are hidden pages, still routable.
        assertEquals(listOf("All", "Persistent Badges", "Cloudy Apps", "Phone Apps", "Configs"), sec.pages.map { it.label })
        assertTrue(sec.allPages.any { it.id == "rss-inboxes" })
        val filters = filtersOf("communication", "my-rss")
        assumeTrue("the All tab declares no filter row in this build", filters.isNotEmpty())
        val st = AggregatorState(ctx, "t1-filters", emptyList(), filters)
        showKit { FilterRow(st) }
        compose.onNodeWithTag(AggTags.FILTERS_TOGGLE).assertExists()
        // Internal keys ("__collapsed", "__archived/…") are stored choices, not toggles drawn on the row.
        val drawn = filters.filterNot { it.id.startsWith("__") }
        assumeTrue(drawn.isNotEmpty())
        if (compose.onAllNodesWithTag(AggTags.filter(drawn[0].id, drawn[0].options[0].id)).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag(AggTags.FILTERS_TOGGLE).performClick()
        }
        for (f in drawn) for (o in f.options) compose.onNodeWithTag(AggTags.filter(f.id, o.id)).assertExists()
        // The One UI page actions are NOT part of the filter row.
        compose.onNodeWithTag(AggTags.MARK_ALL_READ).assertDoesNotExist()
        compose.onNodeWithTag(AggTags.ARCHIVE_ALL).assertDoesNotExist()
    }

    private fun filtersOf(sectionId: String, page: String): List<Sections.StackFilter> =
        Sections.stackFiltersFor(Sections.byId(sectionId)!!, page)

    @Test
    fun `F1 the Filters row draws the declared toggles and persists a pick`() {
        val filters = filtersOf("communication", "rss-inboxes")
        assumeTrue("the Inboxes tab declares no filter row in this build", filters.isNotEmpty())
        val st = AggregatorState(ctx, "rss-inboxes", emptyList(), filters)
        showKit { FilterRow(st) }
        compose.onNodeWithTag(AggTags.FILTERS_TOGGLE).assertExists()
        val sort = filters.first { it.id == "sort" }
        val pick = sort.options.first { it.id != st.selected(sort) }
        // The row starts folded on the tabs whose filters are chosen for them; open it if so.
        if (compose.onAllNodesWithTag(AggTags.filter("sort", pick.id)).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag(AggTags.FILTERS_TOGGLE).performClick()
        }
        compose.onNodeWithTag(AggTags.filter("sort", pick.id)).performClick()
        assertEquals(pick.id, st.selected(sort))
        assertEquals(pick.id, st.sortMode)
        assertEquals(pick.id, StackFilters.selected(ctx, "rss-inboxes", sort))
    }

    @Test
    fun `F2 narrowing Tools returns Services to All, they are one choice over two rows`() {
        val filters = filtersOf("communication", "rss-inboxes")
        val tools = filters.firstOrNull { it.id == AggregatorModel.FILTER_TOOLS }
        val services = filters.firstOrNull { it.id == AggregatorModel.FILTER_SERVICES }
        assumeTrue("this build declares no Tools/Services rows", tools != null && services != null)
        val st = AggregatorState(ctx, "rss-inboxes-f2", emptyList(), filters)
        val narrowS = services!!.options.first { it.id != "all" }
        val narrowT = tools!!.options.first { it.id != "all" }
        st.select(services, narrowS.id)
        assertEquals(narrowS.id, st.servicesMode)
        st.select(tools, narrowT.id)
        assertEquals(narrowT.id, st.toolsMode)
        assertEquals("all", st.servicesMode)
        // Picking All on one row leaves the other alone.
        st.select(tools, "all")
        assertEquals("all", st.servicesMode)
    }

    private fun page(section: String, mode: String) =
        show(AggregatorStackFragment.newInstance(section, "", mode))

    @Test
    fun `P1 the page draws a card per panel and folds on a header tap and on the nav re-tap`() {
        val sec = Sections.byId("communication")!!
        val panels = Sections.aggregatorStackFor(sec, "rss-inboxes")
        val f = page("communication", "rss-inboxes")
        compose.onNodeWithTag(AggTags.PAGE).assertExists()
        for ((i, p) in panels.withIndex()) if (p.kind != "section_title") compose.onNodeWithTag(AggTags.panelHeader(i)).assertExists()
        val first = panels.indexOfFirst { it.kind != "section_title" }
        val open0 = !panels[first].collapsed
        if (open0) compose.onNodeWithTag(AggTags.panelBody(first)).assertExists()
        compose.onNodeWithTag(AggTags.panelHeader(first)).performClick()
        if (open0) compose.onNodeWithTag(AggTags.panelBody(first)).assertDoesNotExist()
        else compose.onNodeWithTag(AggTags.panelBody(first)).assertExists()
        // The bottom-nav re-tap: collapse every panel if any is open, expand them all if all are closed.
        var handled = false
        compose.runOnUiThread { handled = f.toggleAllCollapsed() }
        assertTrue(handled)
        compose.waitForIdle()
        var again = false
        compose.runOnUiThread { again = f.toggleAllCollapsed() }
        assertTrue(again)
    }

    @Test
    fun `P2 a phone card without notification access says it is unavailable, not empty`() {
        val sec = Sections.byId("communication")!!
        val panels = Sections.aggregatorStackFor(sec, "rss-inboxes")
        assumeTrue(panels.any { it.kind == "notification_center" && it.stream == "phone" })
        page("communication", "rss-inboxes")
        compose.onNodeWithText("unavailable · permission not granted").assertExists()
    }

    @Test
    fun `P3 an anchor tile scrolls this page to the card that claimed the id`() {
        val sec = Sections.byId("c3")!!
        val panels = Sections.aggregatorStackFor(sec, "topology")
        val index = panels.firstOrNull { it.kind == "tile_row" && it.tiles.any { t -> t.target.startsWith(StackAnchors.PREFIX) } }
        assumeTrue("the topology page declares no anchor tile in this build", index != null)
        val owned = panels.flatMap { p -> listOfNotNull(p.anchor.takeIf { it.isNotBlank() }) + p.anchors.map { it.id } }.toSet()
        // The last anchor tile that resolves to a card: far enough down the page to need a scroll.
        val tile = index!!.tiles.last { it.target.startsWith(StackAnchors.PREFIX) && it.target.removePrefix(StackAnchors.PREFIX) in owned }
        page("c3", "topology")
        fun scrolled(): Float = compose.onNodeWithTag(AggTags.PAGE).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(0f, scrolled(), 0f)
        compose.onNodeWithTag("index:" + tile.label).performScrollTo().performClick()
        compose.waitUntil(10_000) { scrolled() > 0f }
    }
}
