package app.sterna.core.data.mail

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A refresh key no correct implementation would ever answer — see `OffsetSource`. */
private const val DELEGATED = -777

/**
 * The list must stay where the reader put it while the mediator writes the pages it just fetched.
 */
class RefreshLandsWhereTheReaderIsTest {

    /** The reader is on this folder row: about six screens down, where the mediator is fetching. */
    private val readerRow = 300

    /**
     * One invalidation: the reader sits at folder row [readerRow] in a window that starts at
     */
    private fun reloadAfterAWrite(windowStart: Int, folderRows: Int = 5_000): Int {
        val config = pagingConfig()
        // Paging's anchor, as the fetcher computes it with placeholders off: the reader's index
        // INSIDE the loaded window, which is all the presenter knows about.
        val anchorInWindow = readerRow - windowStart
        val key = refreshOffsetForAnchor(
            anchorPosition = anchorInWindow,
            firstLoadedItemsBefore = windowStart,
            initialLoadSize = config.initialLoadSize,
            placeholdersEnabled = config.enablePlaceholders,
        )
        // getOffset(Refresh) — room 2.6.1, RoomPagingUtil.kt.
        val k = key ?: 0
        return if (k >= folderRows) maxOf(0, folderRows - config.initialLoadSize) else k
    }

    @Test
    fun `three writes in a row leave the reader on the mail they were reading`() {
        val config = pagingConfig()
        val starts = mutableListOf<Int>()
        var windowStart = 0 // the first generation loads from the top of the folder
        repeat(3) {
            windowStart = reloadAfterAWrite(windowStart)
            starts += windowStart
        }

        // 300 − 150/2 = 225, and it must STAY there: a window that starts at 225 puts the reader
        // at index 75 of it, and 75 − 75 = 0 is exactly the walk back to the top that the report
        // describes ("it jumps back and older mail can never be reached").
        assertEquals(
            "The anchored reloads walk the window away from the reader. Sequence of window " +
                "starts after each write, reader on folder row $readerRow:",
            listOf(225, 225, 225),
            starts,
        )
        starts.forEachIndexed { pass, start ->
            assertTrue(
                "After write ${pass + 1} the reloaded window is rows $start..${start + config.initialLoadSize - 1}, " +
                    "which no longer holds the reader's row $readerRow — the list is thrown back " +
                    "to the newest mail and the reader cannot get past it.",
                readerRow in start until start + config.initialLoadSize,
            )
        }
    }

    @Test
    fun `the reload keeps half a load above the reader, so both directions stay filled`() {
        assertEquals(
            225,
            refreshOffsetForAnchor(
                anchorPosition = 75,
                firstLoadedItemsBefore = 225,
                initialLoadSize = 150,
                placeholdersEnabled = false,
            ),
        )
    }

    @Test
    fun `near the top of the folder the key clips to zero instead of going negative`() {
        assertEquals(
            0,
            refreshOffsetForAnchor(
                anchorPosition = 10,
                firstLoadedItemsBefore = 0,
                initialLoadSize = 150,
                placeholdersEnabled = false,
            ),
        )
    }

    @Test
    fun `nothing accessed yet means no key, and Paging starts from the top itself`() {
        assertNull(
            refreshOffsetForAnchor(
                anchorPosition = null,
                firstLoadedItemsBefore = 225,
                initialLoadSize = 150,
                placeholdersEnabled = false,
            ),
        )
    }

    @Test
    fun `with placeholders on the anchor is already absolute and must not be shifted twice`() {
        // Paging counts placeholdersBefore into anchorPosition as soon as placeholders are on;
        // adding the window offset again would send the reload PAST the reader, by as much as
        // they had scrolled.
        assertEquals(
            225,
            refreshOffsetForAnchor(
                anchorPosition = 300,
                firstLoadedItemsBefore = 225,
                initialLoadSize = 150,
                placeholdersEnabled = true,
            ),
        )
    }

    @Test
    fun `a page that does not know its offset is read as the top of the folder`() {
        // COUNT_UNDEFINED is Int.MIN_VALUE. Room always fills itemsBefore in, but the type says
        // it may not be there, and adding it raw would clamp every key to 0 — the walk back to
        // the newest mail, restored by arithmetic.
        assertEquals(
            225,
            refreshOffsetForAnchor(
                anchorPosition = 300,
                firstLoadedItemsBefore = Int.MIN_VALUE,
                initialLoadSize = 150,
                placeholdersEnabled = false,
            ),
        )
    }

    @Test
    fun `the shipped window is these four numbers, and the re-basing needs the last of them`() {
        val config = pagingConfig()
        assertEquals("the reader's steps between server pages", 50, config.pageSize)
        assertEquals("half of this is what stays above the reader", 150, config.initialLoadSize)
        assertEquals("how early the next page is asked for", 50, config.prefetchDistance)
        assertFalse(
            "The list now pages WITH placeholders. That changes what `anchorPosition` means " +
                "(it becomes folder-wide), and it makes null rows reach InboxScreen's scrollbar, " +
                "its `itemCount > 0` guard, the entry cascade and the new-mail-at-the-top watch. " +
                "Read RefreshAnchor.kt before pinning this differently.",
            config.enablePlaceholders,
        )
    }

    // -- the seam: what the Pagers actually hand Room ---------------------------------------------

    /**
     * Stands in for `LimitOffsetPagingSource`: same LIMIT/OFFSET loading and the same `itemsBefore`
     */
    private class OffsetSource(private val folderRows: Int) : PagingSource<Int, String>() {
        val loadedOffsets = mutableListOf<Int>()

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> {
            val offset = params.key ?: 0
            loadedOffsets += offset
            val end = minOf(offset + params.loadSize, folderRows)
            val data = (offset until end).map { "row $it" }
            return LoadResult.Page(
                data = data,
                prevKey = if (offset <= 0) null else offset,
                nextKey = if (end >= folderRows) null else end,
                itemsBefore = offset,
                itemsAfter = folderRows - end,
            )
        }

        override fun getRefreshKey(state: PagingState<Int, String>): Int = DELEGATED
    }

    /**
     * The state Paging hands `getRefreshKey`: the loaded window as the list of pages it really is
     */
    private fun stateOver(
        window: List<Pair<Int, Int>>,
        anchorPosition: Int?,
        config: PagingConfig = pagingConfig(),
    ): PagingState<Int, String> = PagingState(
        pages = window.map { (start, rows) ->
            PagingSource.LoadResult.Page(
                data = (start until start + rows).map { "row $it" },
                prevKey = null,
                nextKey = null,
                itemsBefore = start,
                itemsAfter = 0,
            )
        },
        anchorPosition = anchorPosition,
        config = config,
        leadingPlaceholderCount = if (config.enablePlaceholders) window.first().first else 0,
    )

    @Test
    fun `the source the list pages answers a folder-wide refresh key, not the delegate's`() {
        val source = AnchoredRefreshPagingSource(OffsetSource(folderRows = 5_000))
        assertEquals(225, source.getRefreshKey(stateOver(listOf(225 to 150), anchorPosition = 75)))
    }

    @Test
    fun `the key is measured from where the WINDOW starts, not from its last page`() {
        // What the reader's window looks like after three scroll steps: the reloaded 150 rows,
        // then the appended pages. `anchorPosition` counts from the FIRST of them, so the offset
        // it must be re-based on is the first page's.
        //
        // Reading the last page's offset instead sends the reload 175 rows BELOW the reader: the
        // message being read leaves the window downwards, the landing point sits at the window's
        // edge so the mediator immediately APPENDs again — a burst of server pages, on mobile
        // data, in the middle of the gesture. Worse than the defect this fixes, and silent.
        val source = AnchoredRefreshPagingSource(OffsetSource(folderRows = 5_000))
        val window = listOf(225 to 150, 375 to 50, 425 to 50, 475 to 50)
        assertEquals(
            "The reader is on folder row 300 (index 75 of a window that starts at 225).",
            225,
            source.getRefreshKey(stateOver(window, anchorPosition = 75)),
        )
    }

    @Test
    fun `the seam asks the config whether the anchor is already folder-wide`() {
        // Placeholders ON: Paging counts placeholdersBefore into anchorPosition, so the anchor is
        // 300 (the folder row) and the key is 300 − 75. The wrapper must read that from the state's
        // config; a hard-wired `false` here would re-base an already-absolute anchor and answer
        // 450 — a reload 150 rows past the reader.
        val withPlaceholders = PagingConfig(
            pageSize = 50,
            initialLoadSize = 150,
            prefetchDistance = 50,
            enablePlaceholders = true,
        )
        val source = AnchoredRefreshPagingSource(OffsetSource(folderRows = 5_000))
        assertEquals(
            225,
            source.getRefreshKey(stateOver(listOf(225 to 150), anchorPosition = 300, config = withPlaceholders)),
        )
    }

    @Test
    fun `it loads exactly what the delegate loads, at the offset it is asked for`() = runBlocking {
        val delegate = OffsetSource(folderRows = 5_000)
        val source = AnchoredRefreshPagingSource(delegate)
        val page = source.load(PagingSource.LoadParams.Refresh(key = 225, loadSize = 150, placeholdersEnabled = false))
        page as PagingSource.LoadResult.Page
        assertEquals(listOf(225), delegate.loadedOffsets)
        assertEquals("row 225", page.data.first())
        assertEquals(225, page.itemsBefore)
    }

    @Test
    fun `a write that invalidates Room's source invalidates the list's source`() {
        val delegate = OffsetSource(folderRows = 5_000)
        val source = AnchoredRefreshPagingSource(delegate)
        assertFalse(source.invalid)
        delegate.invalidate() // what the Room invalidation observer does when the mediator writes
        assertTrue(
            "The list would keep showing the generation the write invalidated: no new page ever " +
                "appears, and scrolling stops at the cached rows.",
            source.invalid,
        )
    }

    @Test
    fun `a generation Paging retires tells Room's source to stop`() {
        val delegate = OffsetSource(folderRows = 5_000)
        val source = AnchoredRefreshPagingSource(delegate)
        source.invalidate() // what PageFetcher does to the source it is replacing
        assertTrue(
            "The retired Room source would go on serving loads for a generation nobody presents: " +
                "its `invalid` flag is what makes it answer LoadResult.Invalid and stop.",
            delegate.invalid,
        )
    }

    // -- the plug, as source ----------------------------------------------------------------------

    /**
     * SOURCE LINT. `MailRepository` needs Room, an Android `Context` and a live JMAP session, so
     */
    @Test
    fun `all four paged lists page through the anchored source`() {
        val expected = listOf(
            "pagingSourceFactory = { AnchoredRefreshPagingSource(emailDao.conversationPagingSource(conversationQuery(scopes, sort, unreadOnly, accountId = null, sentMailboxes = sentMailboxes, shape = shape, memberScopes = extra))) },",
            "pagingSourceFactory = { AnchoredRefreshPagingSource(emailDao.pagingSource(pagingQuery(scopes, sort, unreadOnly, shape, extra))) },",
            "pagingSourceFactory = { AnchoredRefreshPagingSource(emailDao.conversationPagingSource(conversationQuery(scopes, sort, unreadOnly, credentials.id, sentMailboxes, shape, extra))) },",
            "pagingSourceFactory = { AnchoredRefreshPagingSource(emailDao.pagingSource(pagingQuery(scopes, sort, unreadOnly, shape, extra))) },",
        )
        val actual = (
            DaoQuerySource.mailFunctionBody("MailRepository", "pagedMailbox") +
                DaoQuerySource.mailFunctionBody("MailRepository", "pagedFolder")
            )
            .lines().map { it.trim() }.filter { it.startsWith("pagingSourceFactory") }
        assertEquals(
            "The unified inbox and the single folder must both page through the anchored source, " +
                "in both view modes: a list left on Room's own refresh key walks back to the top " +
                "on every write the sync or the mediator does under the reader.",
            expected,
            actual,
        )
    }

    /** The four numbers themselves are pinned above; this only says all four Pagers take THAT
     *  config, rather than one of them growing a window of its own. */
    @Test
    fun `all four Pagers page through pagingConfig`() {
        val configs = (
            DaoQuerySource.mailFunctionBody("MailRepository", "pagedMailbox") +
                DaoQuerySource.mailFunctionBody("MailRepository", "pagedFolder")
            )
            .lines().map { it.trim() }.filter { it.startsWith("config =") }
        assertEquals(List(4) { "config = pagingConfig()," }, configs)
    }
}
