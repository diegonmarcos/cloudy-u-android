package app.sterna.ui.inbox

import android.app.Application
import app.sterna.core.data.mail.AuthFilter
import app.sterna.core.data.mail.ListShape
import app.sterna.core.data.settings.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The list's top bar: icon groups in the owner's order, every function named in the overflow, persistence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ListViewControlsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun src(path: String) = File("src/main/kotlin/app/sterna/ui/inbox/$path").readText()

    // -- the order: View mode, Rank, Filter, then Search LAST before the overflow ---------------

    @Test fun `the groups are View mode, Rank, Filter, in that order`() {
        assertEquals(
            listOf(ListViewGroup.VIEW_MODE, ListViewGroup.RANK, ListViewGroup.FILTER),
            ListViewGroup.entries.toList(),
        )
        assertEquals(listOf(false, false, true), ListViewGroup.entries.map { it.multi })
    }

    @Test fun `the top bar draws the groups, then Search as the last icon, then the overflow`() {
        val screen = src("InboxScreen.kt")
        val groups = screen.indexOf("ListViewIconGroups(")
        val search = screen.indexOf("IconButton(onClick = { viewModel.setSearchActive(true) })", groups)
        val overflow = screen.indexOf("Icons.Filled.MoreVert", search)
        assertTrue("groups, then search, then overflow: $groups $search $overflow", groups >= 0 && search > groups && overflow > search)
        // nothing but the Search icon sits between the groups and the overflow icon: two IconButtons,
        // Search's and the overflow's own
        val between = screen.substring(groups, overflow)
        assertEquals(2, Regex("""\bIconButton\(""").findAll(between).count())
    }

    // -- the overflow lists every function by name, checked when active -------------------------

    @Test fun `every function is declared once and the overflow walks the whole list`() {
        assertEquals(
            listOf(
                "GROUP_SUBJECT", "GROUP_SENDER", "GROUP_DOMAIN", "GROUP_NONE",
                "RANK_NEWEST", "RANK_SENDER", "RANK_SUBJECT",
                "FILTER_ALL", "FILTER_STARRED", "FILTER_UNREAD", "FILTER_ATTACHMENTS",
                "FILTER_AUTH_CODES", "FILTER_AUTH_LINKS", "FILTER_AUTH_ANY",
                "FILTER_ALERTS", "FILTER_NO_ALERTS", "RESET_DEFAULTS",
            ),
            ListViewFunction.entries.map { it.name },
        )
        val controls = src("ListViewControls.kt")
        assertTrue(controls.contains("ListViewFunction.entries.forEach { fn ->"))
        assertTrue(controls.contains("ListViewFunction.of(group).forEach { fn ->"))
        assertTrue(src("InboxScreen.kt").contains("ListViewOverflowItems(shownViewUi, listViewActions, ui.unreadView)"))
        // Search is in the overflow too
        val overflow = src("InboxScreen.kt").substringAfter("ListViewOverflowItems(")
        assertTrue(overflow.substringBefore("inbox_select_all").contains("R.string.inbox_search"))
    }

    @Test fun `each function is checked exactly when its choice is active, and runs its own action`() {
        val ui = ListViewUi.of(
            ListView(GroupMode.SENDER, RankMode.SUBJECT, setOf(ListFilter.STARRED, ListFilter.AUTH_LINKS)),
            globalConversation = true, globalSort = SortOrder.DATE_DESC,
        )
        val active = ListViewFunction.entries.filter { it.isActive(ui) }.map { it.name }
        assertEquals(listOf("GROUP_SENDER", "RANK_SUBJECT", "FILTER_STARRED", "FILTER_AUTH_LINKS"), active)
        val ran = mutableListOf<String>()
        val actions = ListViewActions({ ran += "g:$it" }, { ran += "r:$it" }, { ran += "f:$it" })
        ListViewFunction.entries.forEach { it.run(actions) }
        // Reset is not a filter: with no onReset wired it adds nothing here.
        assertEquals(16, ran.size)
        assertEquals("g:SUBJECT", ran.first())
        assertEquals("f:NO_ALERTS", ran.last())
        var reset = 0
        ListViewFunction.RESET_DEFAULTS.run(ListViewActions({}, {}, {}, onReset = { reset++ }))
        assertEquals(1, reset)
    }

    // -- an icon is active when any non-default choice is set -----------------------------------

    @Test fun `groups read as active only off their defaults`() {
        val none = ListViewUi.of(ListView(), globalConversation = true, globalSort = SortOrder.DATE_DESC)
        assertEquals(listOf(false, false, false), ListViewGroup.entries.map { none.groupActive(it) })
        val sender = ListViewUi.of(ListView(group = GroupMode.NONE), true, SortOrder.DATE_DESC)
        assertTrue(sender.groupActive(ListViewGroup.VIEW_MODE)); assertFalse(sender.groupActive(ListViewGroup.RANK))
        val rank = ListViewUi.of(ListView(rank = RankMode.SENDER), true, SortOrder.DATE_DESC)
        assertTrue(rank.groupActive(ListViewGroup.RANK))
        val filter = ListViewUi.of(ListView(filters = setOf(ListFilter.UNREAD)), true, SortOrder.DATE_DESC)
        assertTrue(filter.groupActive(ListViewGroup.FILTER))
        // By sender is the default whatever the global conversation setting; subject grouping is not
        assertFalse(ListViewUi.of(ListView(), false, SortOrder.DATE_DESC).groupActive(ListViewGroup.VIEW_MODE))
        assertTrue(ListViewUi.of(ListView(group = GroupMode.SUBJECT), false, SortOrder.DATE_DESC).groupActive(ListViewGroup.VIEW_MODE))
    }

    // -- grouping, ranking and the filters reach the query --------------------------------------

    @Test fun `grouping and ranking resolve against the global settings`() {
        assertEquals(GroupMode.SUBJECT, ListView(group = GroupMode.SUBJECT).effectiveGroup(false))
        assertEquals(GroupMode.NONE, ListView(group = GroupMode.NONE).effectiveGroup(true))
        assertEquals(GroupMode.SENDER, ListView(group = GroupMode.SENDER).effectiveGroup(false))
        assertEquals("a folder opens Newest first whatever the global sort", SortOrder.DATE_DESC, ListView().effectiveSort(SortOrder.FLAGGED_FIRST))
        assertEquals(SortOrder.FLAGGED_FIRST, ListView(rank = null).effectiveSort(SortOrder.FLAGGED_FIRST))
        assertEquals(SortOrder.SENDER, ListView(rank = RankMode.SENDER).effectiveSort(SortOrder.DATE_DESC))
        assertEquals(SortOrder.SUBJECT, RankMode.SUBJECT.order)
        assertEquals(SortOrder.DATE_DESC, RankMode.NEWEST.order)
    }

    @Test fun `each filter maps to its part of the shape, and the auth classes are exclusive`() {
        assertEquals(ListShape(starred = true), ListView(GroupMode.NONE, filters = setOf(ListFilter.STARRED)).shape())
        assertEquals(ListShape(attachments = true), ListView(GroupMode.NONE, filters = setOf(ListFilter.ATTACHMENTS)).shape())
        assertEquals(ListShape(), ListView(GroupMode.NONE, filters = setOf(ListFilter.UNREAD)).shape()) // unread has its own flag
        assertEquals(ListShape(bySender = true), ListView(group = GroupMode.SENDER).shape())
        assertEquals(ListShape(byDomain = true), ListView(group = GroupMode.DOMAIN).shape())
        var v = ListView(GroupMode.NONE).toggled(ListFilter.AUTH_CODES)
        assertEquals(AuthFilter.CODES, v.shape().auth)
        v = v.toggled(ListFilter.AUTH_ANY)
        assertEquals(setOf(ListFilter.AUTH_ANY), v.filters)
        assertEquals(AuthFilter.ANY, v.shape().auth)
        v = v.toggled(ListFilter.STARRED).toggled(ListFilter.AUTH_LINKS)
        assertEquals(setOf(ListFilter.STARRED, ListFilter.AUTH_LINKS), v.filters)
        assertEquals(ListShape(starred = true, auth = AuthFilter.LINKS), v.shape())
    }

    // -- persistence per account and folder ------------------------------------------------------

    @Test fun `choices are stored per account and folder and read back`() {
        val a = ListViewPrefs.keyFor("acc1", "inbox")
        val b = ListViewPrefs.keyFor("acc2", "inbox")
        val view = ListView(GroupMode.SENDER, RankMode.SUBJECT, setOf(ListFilter.STARRED, ListFilter.AUTH_CODES))
        ListViewPrefs.save(context, a, view)
        assertEquals(view, ListViewPrefs.load(context, a))
        assertEquals("another account's folder of the same id is untouched", ListView(), ListViewPrefs.load(context, b))
        ListViewPrefs.save(context, a, ListView())
        assertEquals("the default is stored as nothing", ListView(), ListViewPrefs.load(context, a))
    }

    @Test fun `Group by Domain sits right after Group by Sender, is checked alone, and persists`() {
        val viewMode = ListViewFunction.of(ListViewGroup.VIEW_MODE).map { it.name }
        assertEquals(listOf("GROUP_SUBJECT", "GROUP_SENDER", "GROUP_DOMAIN", "GROUP_NONE"), viewMode)
        val ui = ListViewUi.of(ListView(group = GroupMode.DOMAIN), globalConversation = true, globalSort = SortOrder.DATE_DESC)
        assertEquals(listOf("GROUP_DOMAIN", "RANK_NEWEST", "FILTER_ALL"), ListViewFunction.entries.filter { it.isActive(ui) }.map { it.name })
        assertFalse("Domain is the default, so the View mode icon reads inactive", ui.groupActive(ListViewGroup.VIEW_MODE))
        assertTrue(ListViewUi.of(ListView(group = GroupMode.SENDER), true, SortOrder.DATE_DESC).groupActive(ListViewGroup.VIEW_MODE))
        val ran = mutableListOf<GroupMode>()
        ListViewFunction.GROUP_DOMAIN.run(ListViewActions({ ran += it }, {}, {}))
        assertEquals(listOf(GroupMode.DOMAIN), ran)
        val key = ListViewPrefs.keyFor("acc1", "inbox")
        val view = ListView(GroupMode.DOMAIN, RankMode.SENDER, setOf(ListFilter.UNREAD))
        ListViewPrefs.save(context, key, view)
        assertEquals(view, ListViewPrefs.load(context, key))
        assertEquals("DOMAIN;SENDER;UNREAD", view.encode())
        // a value stored before the choice existed still reads back as itself
        assertEquals(ListView(group = GroupMode.SENDER), ListView.decode("SENDER;;"))
        assertTrue(src("InboxScreen.kt").contains("if (domainGrouped && !fromSearch) DomainGroupHeader(DomainGroupHeading.of(row))"))
    }

    @Test fun `Domain is the default list grouping, and a saved choice still wins`() {
        // fresh install, reset, or no saved choice: nothing stored for this account and folder
        val key = ListViewPrefs.keyFor("fresh", "inbox")
        assertEquals(GroupMode.DOMAIN, ListViewPrefs.load(context, key).effectiveGroup(true))
        assertEquals(GroupMode.DOMAIN, ListViewPrefs.load(context, key).effectiveGroup(false))
        assertEquals(GroupMode.DOMAIN, ListView.decode(null).group)
        assertEquals(GroupMode.DOMAIN, ListView.decode(";NEWEST;STARRED").group)
        assertTrue(ListView().shape().byDomain)
        assertEquals(GroupMode.DOMAIN, ListViewUi.of(ListView(), true, SortOrder.DATE_DESC).group)
        assertEquals(GroupMode.DOMAIN, ListViewUi().group)
        // a saved choice wins over the default
        for (mode in listOf(GroupMode.SUBJECT, GroupMode.SENDER, GroupMode.NONE)) {
            ListViewPrefs.save(context, key, ListView(group = mode))
            assertEquals(mode, ListViewPrefs.load(context, key).effectiveGroup(true))
        }
    }

    @Test fun `a damaged stored value reads as the default`() {
        assertEquals(ListView(), ListView.decode("nonsense;;"))
        assertEquals(ListView(rank = RankMode.SENDER), ListView.decode("BOGUS;SENDER;NOPE"))
    }
}
