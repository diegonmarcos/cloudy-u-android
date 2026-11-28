package app.sterna.core.data.mail

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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

    @Test fun `returning to the list shows the kept rows first and runs no store query for it`() {
        val holder = ReturnHolder<String>()
        fun firstFrame() = holder.flow.replayCache.firstOrNull() // what the paging list reads in its first composition
        holder.shown()
        holder.live("generation 1 (loaded)")
        assertEquals("on screen the live pages go straight through", "generation 1 (loaded)", firstFrame())
        // the screen leaves for another page; nothing changes in the store
        holder.left("kept rows")
        assertEquals("the return draws the loaded generation itself in its first frame: no query, no empty list", "generation 1 (loaded)", firstFrame())
        // a write invalidates the list while away: generation 2 exists but its store query is still running
        holder.live("generation 2 (loading: no rows yet)")
        assertEquals("the return never draws a generation that is still loading: the rows shown before are kept", "kept rows", firstFrame())
        holder.shown()
        assertEquals("back on screen, the newest generation is handed over (a diff in the background)", "generation 2 (loading: no rows yet)", firstFrame())
        holder.live("generation 3")
        assertEquals("generation 3", firstFrame())
    }

    @Test fun `a cold start draws the rows kept from the last run, and the screen and the view model are wired to the holder`() {
        val cold = ReturnHolder<String>()
        cold.seed("top of the list, from the last run")
        cold.live("generation 1 (loading)")
        assertEquals("before the store has answered", "top of the list, from the last run", cold.flow.replayCache.firstOrNull())
        cold.shown()
        assertEquals("generation 1 (loading)", cold.flow.replayCache.firstOrNull())
        val none = ReturnHolder<String>()
        none.live("generation 1"); none.left(null); none.live("generation 2")
        assertEquals("nothing kept: the live pages only", "generation 2", none.flow.replayCache.firstOrNull())
        val vm = code("app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt")
        assertTrue("val returningListRows: kotlinx.coroutines.flow.SharedFlow<PagingData<InboxRow>> = returning.flow" in vm)
        assertTrue("viewModelScope.launch { keptListRows.collect { returning.live(it) } }" in vm)
        assertTrue("fun onListLeft() = returning.left(shownRows.takeIf { it.isNotEmpty() }?.let { PagingData.from(it) })" in vm)
        assertTrue("shownRows.takeIf { it.isNotEmpty() }?.let { returning.seed(PagingData.from(it)) }" in vm)
        assertTrue("another view never shows these rows", "viewModelScope.launch { selection.drop(1).collect { shownRows = emptyList() } }" in vm)
        val screen = code("app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt")
        assertTrue("val listRows = viewModel.returningListRows.collectAsLazyPagingItems()" in screen)
        assertTrue("DisposableEffect(viewModel) { viewModel.onListShown(); onDispose { viewModel.onListLeft() } }" in screen)
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

    // -- headers are never evicted --------------------------------------------------------------------------------------

    @Test fun `the Inbox is never pruned by age, whoever asks`() {
        assertTrue(isInboxRole("inbox")); assertTrue(isInboxRole(" Inbox "))
        assertFalse(isInboxRole("archive")); assertFalse(isInboxRole(null))
        val repo = code("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        val prune = repo.dropWhile { !it.startsWith("private suspend fun pruneRetention(") }.take(10)
        assertTrue("the prune itself refuses the Inbox, before it reads a row: $prune", "if (isInboxRole(mailboxDao.roleForId(accountId, mailboxId))) return" in prune)
        assertTrue(prune.indexOf("if (isInboxRole(mailboxDao.roleForId(accountId, mailboxId))) return") < prune.indexOfFirst { it.startsWith("val gone = retentionEvictions(") })
    }

    @Test fun `a first sync stores the folder list before its messages`() {
        assertTrue("nothing stored yet: the folders go in first", firstSyncNeedsFolders(storedRoleOfTarget = null, storedFolders = 0))
        assertFalse("a known account keeps the counters-after-rows order", firstSyncNeedsFolders("inbox", 7))
        assertFalse(firstSyncNeedsFolders(null, 7))
        val repo = code("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        val early = repo.indexOfFirst { it.startsWith("if (accountStore.account(credentials.id) != null && firstSyncNeedsFolders(mailboxDao.roleForId(credentials.id, target.id)") } // a signed-out account is never given a folder list (#121)
        val sync = repo.indexOfFirst { it.startsWith("val sync = runCatching { syncMailbox(session, accountId, auth, target.id, sizing, credentials.id) }") }
        assertTrue("before the message sync starts", early in 0 until sync)
        val remembered = repo.indexOfFirst { it.startsWith("if (target.role == \"inbox\") accountStore.saveInboxMetaFor(credentials.id, target.id, target.name") }
        assertTrue("the Inbox id is remembered with the early folder write, so a restart mid-sync knows its folder", remembered in early + 1 until sync)
    }

    @Test fun `a start that does not know its Inbox id reads it from the store before the list key`() {
        val vm = code("app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt")
        assertTrue("the selection starts on the stored Inbox id", "restoredSelection(store) ?: Sel.Folder(store.inboxMailboxId() ?: inboxIdFromTheStore())," in vm)
        assertTrue("the read is the store's folder list by role, bounded", "kotlinx.coroutines.runBlocking(Dispatchers.IO) { kotlinx.coroutines.withTimeoutOrNull(1_500L) { repo.storedInboxId(account) } }" in vm)
        val repo = code("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        assertTrue("suspend fun storedInboxId(account: String): String? = mailboxDao.idForRole(account, \"inbox\")" in repo)
    }

    @Test fun `the first page does not wait for the folders that list extra messages`() {
        val repo = code("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        val at = repo.indexOf("emailDao.observeExtraScopes().map { rows -> rows.map { it.accountId to it.mailboxId }.toSet() }")
        assertTrue("extraScopes() reads the store's set", at >= 0)
        assertEquals("the set starts from the answer asked at application start", ".onStart { warmExtraScopes.await()?.let { emit(it) } }", repo[at + 1])
        assertEquals("an equal answer rebuilds nothing", ".distinctUntilChanged()", repo[at + 2])
    }

    @Test fun `the settings file, the account store and the database are opened in parallel when the application starts`() {
        val app = code("app/src/main/kotlin/app/sterna/SternaApplication.kt")
        assertTrue("container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { container.settingsRepository.sortOrder.first() } }" in app)
        assertTrue("container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { container.accountStore.currentId() } }" in app)
        assertTrue("container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { container.mailRepository.storedInboxId(\"\") } }" in app)
        assertTrue("the extra-scopes query is asked at application start", "container.mailRepository.warmUpExtraScopes()" in app)
    }

    @Test fun `the key and the pager are built off the main thread`() {
        val vm = code("app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt")
        assertTrue("}.flowOn(Dispatchers.Default).cachedIn(pagingScope)" in vm && "private val pagingScope = kotlinx.coroutines.CoroutineScope(viewModelScope.coroutineContext + Dispatchers.Default)" in vm)
    }

    @Test fun `the drawer's content is composed after the first rows are painted`() {
        val screen = code("app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt")
        assertEquals("both drawer envelopes wait for the first rows", 2, screen.count { it == "if (drawerContentReady) {" })
        assertTrue("the readiness is the painted rows, or an opening drawer", "return ready || drawerState.targetValue != DrawerValue.Closed" in screen)
        assertTrue("var ready by remember { mutableStateOf(app.sterna.core.data.mail.ColdStart.rowsWerePainted) }" in screen)
    }

    @Test fun `the first page load starts before the screen composes`() {
        val vm = code("app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt")
        assertTrue("private val firstPageLoad: Unit = startFirstPageLoad()" in vm)
        val at = vm.indexOf("private fun startFirstPageLoad() {")
        assertTrue("the starter exists", at >= 0)
        val body = vm.drop(at).take(30)
        assertTrue("it takes the pages the screen shows (keptListRows), through a presenter", body.any { "keptListRows.collectLatest { differ.submitData(it) }" in it })
        assertTrue("off the main thread from the start", "viewModelScope.launch(Dispatchers.Default) {" in body && body.any { "launch(Dispatchers.Default) { keptListRows.collectLatest" in it })
        assertTrue("and only for the first generation", body.any { "differ.onPagesUpdatedFlow.first()" in it } && body.any { it == "feed.cancel()" })
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

    // -- the drawer's aggregates ------------------------------------------------------------------------------------

    @Test fun `a sync's writes reach the drawer aggregates in steps and the last write is never lost`() = runTest {
        val seen = mutableListOf<Int>()
        val src = kotlinx.coroutines.flow.flow { for (i in 1..50) { emit(i); delay(20) } } // 50 writes over one second
        val job = launch { src.stepped().collect { seen.add(it) } }
        advanceTimeBy(5_000); runCurrent()
        job.cancel()
        assertEquals("the first result at once", 1, seen.first())
        assertEquals("the newest write lands", 50, seen.last())
        assertTrue("a second of writes makes a few results, not fifty: $seen", seen.size <= 3)
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
