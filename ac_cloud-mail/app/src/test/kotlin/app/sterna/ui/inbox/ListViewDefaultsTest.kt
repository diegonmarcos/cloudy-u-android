package app.sterna.ui.inbox

import android.app.Application
import app.sterna.core.data.mail.AlertCounts
import app.sterna.core.data.mail.AlertScope
import app.sterna.core.data.settings.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * What EVERY folder opens with (Group by Domain, Newest first, filter All) is declared in
 * build.json::mail_list_defaults, "All" heads the filter menu and is the default, overrides persist
 * per folder, and Reset puts a folder back on the defaults.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ListViewDefaultsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val buildJson get() = File("../build.json").readText()

    private fun declared(key: String): String =
        Regex(""""mail_list_defaults"\s*:\s*\{[^}]*"$key"\s*:\s*"([A-Z_]+)"""").find(buildJson)?.groupValues?.get(1)
            ?: error("build.json::mail_list_defaults.$key is not declared")

    @Test fun `the defaults are declared in build json and are Domain, Newest, All`() {
        assertEquals("DOMAIN", declared("group"))
        assertEquals("NEWEST", declared("rank"))
        assertEquals("ALL", declared("filter"))
        assertEquals(GroupMode.DOMAIN, ListViewDefaults.group)
        assertEquals(RankMode.NEWEST, ListViewDefaults.rank)
        assertEquals(ListFilter.ALL, ListViewDefaults.filter)
        assertTrue(ListViewDefaults.filters.isEmpty())
    }

    @Test fun `the build bakes the declared defaults and no default lives in code`() {
        val gradle = File("build.gradle.kts").readText()
        listOf("LIST_DEFAULT_GROUP", "LIST_DEFAULT_RANK", "LIST_DEFAULT_FILTER", "INBOX_SPLIT_B64").forEach {
            assertTrue("build.gradle.kts no longer bakes $it", gradle.contains("\"$it\""))
        }
        val prefs = File("src/main/kotlin/app/sterna/ui/inbox/ListViewPrefs.kt").readText()
        assertTrue(prefs.contains("val group: GroupMode? = ListViewDefaults.group"))
        assertTrue(prefs.contains("val rank: RankMode? = ListViewDefaults.rank"))
        assertTrue(prefs.contains("val filters: Set<ListFilter> = ListViewDefaults.filters"))
    }

    @Test fun `every folder opens on the defaults`() {
        listOf("inbox", "sent", "archive", "Fc    Finance", "unified", "unread").forEach { folder ->
            val v = ListViewPrefs.load(context, ListViewPrefs.keyFor("acct", folder))
            assertEquals(GroupMode.DOMAIN, v.effectiveGroup(globalConversation = true))
            assertEquals(GroupMode.DOMAIN, v.effectiveGroup(globalConversation = false))
            assertEquals(SortOrder.DATE_DESC, v.effectiveSort(SortOrder.SUBJECT))
            assertTrue(v.filters.isEmpty())
            assertFalse(v.filterActive())
            assertFalse(v.rankActive(SortOrder.SUBJECT))
        }
    }

    @Test fun `All is the first filter option and the default one`() {
        val filter = ListViewFunction.of(ListViewGroup.FILTER)
        assertEquals(ListViewFunction.FILTER_ALL, filter.first())
        val ui = ListViewUi.of(ListView(), globalConversation = true, globalSort = SortOrder.DATE_DESC)
        assertTrue("All is checked on a fresh folder", ListViewFunction.FILTER_ALL.isActive(ui))
        assertEquals(listOf("FILTER_ALL"), filter.filter { it.isActive(ui) }.map { it.name })
        val starred = ListViewUi.of(ListView().toggled(ListFilter.STARRED), true, SortOrder.DATE_DESC)
        assertFalse(ListViewFunction.FILTER_ALL.isActive(starred))
        // choosing All lifts every other filter
        val lifted = ListView(filters = setOf(ListFilter.STARRED, ListFilter.UNREAD, ListFilter.AUTH_ANY)).toggled(ListFilter.ALL)
        assertTrue(lifted.filters.isEmpty())
        assertFalse("ALL is never stored", ListFilter.ALL in ListView.decode("DOMAIN;NEWEST;ALL,STARRED").filters)
        var picked: ListFilter? = null
        ListViewFunction.FILTER_ALL.run(ListViewActions({}, {}, { picked = it }))
        assertEquals(ListFilter.ALL, picked)
    }

    @Test fun `an override persists per folder and Reset returns the folder to the defaults`() {
        val a = ListViewPrefs.keyFor("acct", "inbox")
        val b = ListViewPrefs.keyFor("acct", "sent")
        val mine = ListView(GroupMode.NONE, RankMode.SENDER, setOf(ListFilter.STARRED))
        ListViewPrefs.save(context, a, mine)
        assertEquals(mine, ListViewPrefs.load(context, a))
        assertEquals("the sibling folder is untouched", ListView(), ListViewPrefs.load(context, b))
        ListViewPrefs.reset(context, a)
        assertEquals(ListView(), ListViewPrefs.load(context, a))
        assertNotNull(ListViewFunction.entries.firstOrNull { it == ListViewFunction.RESET_DEFAULTS })
        assertTrue(File("src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt").readText().contains("ListViewPrefs.reset("))
    }

    @Test fun `the alert views are offered only with counts, and the counts add up`() {
        val none = ListViewUi.of(ListView(), true, SortOrder.DATE_DESC)
        assertFalse(ListViewFunction.FILTER_ALERTS.visible(none))
        assertFalse(ListViewFunction.FILTER_NO_ALERTS.visible(none))
        val counts = AlertCounts(all = 10, alerts = 4, noAlerts = 6)
        val ui = ListViewUi.of(ListView(), true, SortOrder.DATE_DESC, counts)
        assertTrue(ListViewFunction.FILTER_ALERTS.visible(ui) && ListViewFunction.FILTER_NO_ALERTS.visible(ui))
        assertTrue(counts.partitions)
        assertFalse(AlertCounts(10, 4, 5).partitions)
        // the two are exclusive, and reach the query as one AlertScope
        val v = ListView().toggled(ListFilter.ALERTS).toggled(ListFilter.NO_ALERTS)
        assertEquals(setOf(ListFilter.NO_ALERTS), v.filters)
        assertEquals(AlertScope.NO_ALERTS, v.shape().alerts)
        assertNotNull(v.shape().alertRules)
        assertEquals(AlertScope.ALERTS, ListView().toggled(ListFilter.ALERTS).shape().alerts)
        assertEquals(AlertScope.ALL, ListView().shape().alerts)
    }
}
