package app.sterna.core.data.mail

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1952 round 3: returning to Mail shows the kept rows at once and starts no store query; the pager starts on the first key;
 * every pass of the Rust core waits for the painted rows; a slow list runs one query at a time.
 * Mutations: workflow mail-cache-1952b-mutation (return-requeries, placeholder-view, core-ungated, query-per-write).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListReturn1952cTest {
    // -- warm return ----------------------------------------------------------------------------------------------

    @Test fun `returning to the list shows the kept rows first and runs no store query for it`() = runTest {
        var queries = 0
        // the live pages: a generation that is still loading when the screen comes back (the store query takes 2 s)
        val live = flow { queries++; delay(2_000); emit(listOf("row 1 (fresh)", "row 2")) }
        val shown = listOf("row 1", "row 2")
        val returning = keptFirst({ shown }, live)
        assertEquals("the first frame of the return holds the rows shown before, not an empty list", shown, returning.first())
        assertEquals("and no store query ran to produce it", 0, queries)
        val all = mutableListOf<List<String>>()
        val job = launch { returning.toList(all) }
        runCurrent()
        assertEquals("the kept rows are on screen while the live generation loads", listOf(shown), all)
        advanceTimeBy(2_001); runCurrent()
        assertEquals("then the live rows replace them, a diff in the background", listOf(shown, listOf("row 1 (fresh)", "row 2")), all)
        job.join()
        assertEquals("one live subscription, nothing started twice", 1, queries)
    }

    @Test fun `nothing kept means the live pages only, and the screen and the view model are wired to the kept rows`() = runTest {
        assertEquals(listOf(listOf("a")), keptFirst<List<String>>({ null }, flow { emit(listOf("a")) }).toList())
        val vm = code("app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt")
        assertTrue("app.sterna.core.data.mail.keptFirst({ shownRows.takeIf { it.isNotEmpty() }?.let { PagingData.from(it) } }, keptListRows)" in vm)
        assertTrue("another view never shows these rows", "viewModelScope.launch { selection.drop(1).collect { shownRows = emptyList() } }" in vm)
        val screen = code("app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt")
        assertTrue("val listRows = viewModel.returningListRows.collectAsLazyPagingItems()" in screen)
        assertTrue("LaunchedEffect(listRows) { snapshotFlow { listRows.itemSnapshotList.items }.collect { if (it.isNotEmpty()) viewModel.rememberShownRows(it) } }" in screen)
    }

    // -- cold start: the first rows survive the process -----------------------------------------------------------------

    @Test fun `the top of the list is kept for the next cold start, for its own view only`() {
        val rows = (1..40).map { InboxRow(app.sterna.core.jmap.model.Email(id = "m$it", subject = "s$it"), threadCount = it, unread = it % 2 == 0, threadExpandable = true, unreadCount = it % 3) }
        val text = KeptRows.encode("acc\nf\ninbox\ndomain", rows)
        val back = KeptRows.decode(text, "acc\nf\ninbox\ndomain")
        assertEquals("one screen and a half, the top of the list, every field of a row", rows.take(KEPT_ROWS_MAX), back)
        assertEquals("another folder never shows these rows", null, KeptRows.decode(text, "acc\nf\narchive\ndomain"))
        assertEquals("another account neither", null, KeptRows.decode(text, "other\nf\ninbox\ndomain"))
        assertEquals("a broken file is no rows, never a crash", null, KeptRows.decode("{not json", "acc\nf\ninbox\ndomain"))
        val vm = code("app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt")
        assertTrue("@Volatile private var shownRows: List<InboxRow> = restoreFirstRows()" in vm)
        assertTrue("runCatching { firstRowsFile().writeText(app.sterna.core.data.mail.KeptRows.encode(view, rows)) }" in vm)
        assertTrue("an unknown Inbox id is read from the store", "resolveInboxFromTheStore()" in vm)
    }

    @Test fun `the background fill pauses between batches only while the app is on screen`() {
        assertEquals(UI_RESUMED_BATCH_PAUSE_MS, UiLoad.pauseMs(resumed = true))
        assertEquals("a worker with no screen runs flat out", 0L, UiLoad.pauseMs(resumed = false))
        assertTrue(code("core/data/src/main/kotlin/app/sterna/core/data/mail/FullSync.kt").let { it.indexOf("UiLoad.yieldToUi()") in 0 until it.indexOf("port.fetchBodies(folder, ids)") })
        assertTrue(code("core/data/src/main/kotlin/app/sterna/core/data/mail/BodyPrefetch.kt").let { it.indexOf("UiLoad.yieldToUi()") in 0 until it.indexOf("port.fetchAndStore(batch)") })
        val activity = code("app/src/main/kotlin/app/sterna/MainActivity.kt")
        assertTrue(activity.any { it.startsWith("app.sterna.core.data.mail.UiLoad.uiResumed = true") } && "app.sterna.core.data.mail.UiLoad.uiResumed = false" in activity)
    }

    // -- cold start: one key ----------------------------------------------------------------------------------------

    @Test fun `the pager's first key carries the stored view, never a placeholder`() {
        val vm = code("app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt")
        assertTrue("the list view starts as the stored view of the restored selection", ".stateIn(viewModelScope, SharingStarted.Eagerly, initialListView())" in vm)
        assertTrue("return ListViewPrefs.load(getApplication<Application>(), key)" in vm)
        assertTrue(".stateIn(viewModelScope, SharingStarted.Eagerly, ListFilter.UNREAD in listView.value.filters)" in vm)
        val keys = code("app/src/main/kotlin/app/sterna/ui/inbox/PageSource.kt")
        assertTrue("a folder's key never carries the unread view's late folder list", ".map(::pageKeyForItsView).distinctUntilChanged()" in vm)
        assertTrue("is Sel.Folder -> key.copy(unifiedScopes = emptyList(), unreadScopes = emptyList())" in keys)
        assertFalse("no placeholder view is ever a key", vm.any { ".stateIn(viewModelScope, SharingStarted.Eagerly, ListView())" in it })
    }

    // -- paint before network: the core and a process that starts before its activity -----------------------------

    @Test fun `every pass of the Rust core and the push connection wait for the painted rows`() {
        val core = code("core/data/src/main/kotlin/app/sterna/core/data/mail/RustMailCore.kt")
        val at = core.indexOfFirst { it.startsWith("ColdStart.awaitRows()") }
        assertTrue("RustMailEngine.sync is the one door to MailCore.sync and it is gated", at >= 0 && at < core.indexOfFirst { "MailCore.sync(dbPath" in it })
        assertEquals("one door only", 1, code("core/data/src/main/kotlin/app/sterna/core/data/mail/RustMailCore.kt").count { "MailCore.sync(" in it })
        assertTrue(code("app/src/main/kotlin/app/sterna/SternaApplication.kt").any { it.startsWith("app.sterna.core.data.mail.ColdStart.armProcess()") })
    }

    @Test fun `a sync started before the activity waits for its rows, and a worker's process is not held`() = runTest {
        val gate = ColdStartGate()
        gate.armProcess()
        val order = mutableListOf<String>()
        launch { gate.awaitRows(); order += "network" }
        runCurrent()
        advanceTimeBy(300); gate.arm() // the activity follows the process start
        advanceTimeBy(COLD_START_ACTIVITY_WAIT_MS + 500); runCurrent()
        assertEquals("the activity came: the sync waits for the rows", emptyList<String>(), order)
        order += "rows"; gate.rowsPainted(); runCurrent()
        assertEquals(listOf("rows", "network"), order)

        val worker = ColdStartGate()
        worker.armProcess()
        var ran = false
        launch { worker.awaitRows(); ran = true }
        advanceTimeBy(COLD_START_ACTIVITY_WAIT_MS - 1); runCurrent()
        assertFalse(ran)
        advanceTimeBy(2); runCurrent()
        assertTrue("no activity: a worker's process goes on after the short wait", ran)

        val empty = ColdStartGate()
        empty.arm()
        var synced = false
        launch { empty.awaitRows(); synced = true }
        runCurrent(); empty.nothingToPaint(); runCurrent()
        assertTrue("an empty store releases the first sync at once", synced)
    }

    // -- one query at a time ------------------------------------------------------------------------------------------

    @Test fun `a slow list coalesces invalidations into one query and a fast list follows every write`() {
        assertEquals("an ordinary mailbox: at once", 0L, listInvalidateDelayMs(0))
        assertEquals(0L, listInvalidateDelayMs(LIST_INVALIDATE_FAST_QUERY_MS - 1))
        assertEquals("a 555 ms query: the writes of the next 555 ms share one query", 555L, listInvalidateDelayMs(555))
        assertEquals("never longer than the bound", LIST_INVALIDATE_MAX_DELAY_MS, listInvalidateDelayMs(8_408))
        val src = code("core/data/src/main/kotlin/app/sterna/core/data/mail/RefreshAnchor.kt")
        assertTrue("val wait = listInvalidateDelayMs(lastLoadMs)" in src)
        assertTrue("if (wait == 0L) invalidate() else invalidateScope.launch { delay(wait); invalidate() }" in src)
        assertFalse("the bare forward is gone", "delegate.registerInvalidatedCallback(::invalidate)" in src)
    }

    private fun code(path: String): List<String> =
        generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, path) }.first { it.isFile }
            .readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
}
