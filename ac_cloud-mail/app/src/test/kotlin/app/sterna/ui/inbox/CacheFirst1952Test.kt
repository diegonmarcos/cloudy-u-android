package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1952: the list is the on-device store; the network only reconciles and a failure only adds a banner. Mutations: workflow
 *  mail-cache-1952-mutation. The emulator walk test-mail-tab-switch-1952-emulator proves the same on a device. */
class CacheFirst1952Test {
    private fun code(path: String): List<String> =
        repoFile(path).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }

    @Test
    fun `the list paints from the store with the network failing`() {
        val repo = code("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        assertTrue(
            "the folder pager must never wait on the server before its first page: initialize() = SKIP_INITIAL_REFRESH",
            "override suspend fun initialize() = InitializeAction.SKIP_INITIAL_REFRESH" in repo,
        )
        val paged = repo.dropWhile { !it.startsWith("fun pagedFolder(") }.takeWhile { !it.startsWith("private fun <V : Any> folderMediator(") }
        val sources = paged.filter { it.startsWith("pagingSourceFactory") }
        assertEquals("both shapes of the folder list page a Room query", 2, sources.size)
        assertTrue("the rows come from the DAO (the on-device store), nothing else: $sources", sources.all { "emailDao." in it })
        assertTrue("no session or network call builds the pager", paged.none { "connect(" in it || "client." in it })
    }

    @Test
    fun `a session failure never empties the list`() {
        val screen = code("app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt")
        val rows = screen.indexOf("listRows.itemCount > 0 && !staleRows ->")
        assertTrue("the rows arm must read the cached rows and the rows guard only (no refresh state, no error, no offline flag)", rows >= 0)
        for (arm in listOf("ui.refreshing -> Unit", "notice == RefreshNotice.OFFLINE -> PullableCenter {", "notice == RefreshNotice.ERROR -> PullableCenter {")) {
            val at = screen.indexOf(arm)
            assertTrue("arm missing: $arm", at >= 0)
            assertTrue("the rows arm must come BEFORE '$arm': cached rows win over every network state", rows < at)
        }
    }

    @Test
    fun `a failed session keeps the cached rows under a banner with the reason and the last sync`() {
        val at = 1_700_000_000_000L
        assertEquals(CacheBanner(RefreshNotice.ERROR, "HTTP 502", at), cacheBanner(rowCount = 400, offline = false, error = "HTTP 502", lastSyncAt = at))
        assertEquals(CacheBanner(RefreshNotice.OFFLINE, null, at), cacheBanner(rowCount = 1, offline = true, error = null, lastSyncAt = at))
        assertEquals(CacheBanner(RefreshNotice.ERROR, "timeout", null), cacheBanner(rowCount = 3, offline = false, error = "timeout", lastSyncAt = null))
        assertNull("a refresh that landed says nothing", cacheBanner(rowCount = 400, offline = false, error = null, lastSyncAt = at))
        assertNull("no rows: the centred scene speaks, with its own Retry", cacheBanner(rowCount = 0, offline = false, error = "HTTP 502", lastSyncAt = at))
        val screen = code("app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt")
        assertTrue("the screen draws the banner from cacheBanner", "cacheBanner(listRows.itemCount, ui.offline, ui.error, ui.lastSyncAt)?.let { banner ->" in screen)
        assertTrue("both banners offer Retry", screen.count { it == "TextButton(onClick = onRetry) { Text(stringResource(R.string.inbox_retry)) }" } == 2)
    }

    @Test
    fun `only a refresh that landed moves the last sync time`() {
        val vm = code("app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt")
        assertEquals("one stamp", 1, vm.count { "store.setLastSyncAt(" in it })
        assertTrue("the one stamp is guarded by refreshLanded", vm.single { "store.setLastSyncAt(" in it }.startsWith("if (refreshLanded(wasRefreshing, now.refreshing, now.error))"))
        assertTrue("a refresh that ran and left no error landed", refreshLanded(wasRefreshing = true, refreshing = false, error = null))
        assertTrue("a failed session (HTTP 502) is not a sync", !refreshLanded(wasRefreshing = true, refreshing = false, error = "HTTP 502"))
        assertTrue("a refresh still running is not a sync", !refreshLanded(wasRefreshing = true, refreshing = true, error = null))
        assertTrue("an idle status is not a sync", !refreshLanded(wasRefreshing = false, refreshing = false, error = null))
    }

    private fun repoFile(path: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, path) }.firstOrNull { it.isFile }
            ?: error("cannot find $path from ${File("").absolutePath}")
}
