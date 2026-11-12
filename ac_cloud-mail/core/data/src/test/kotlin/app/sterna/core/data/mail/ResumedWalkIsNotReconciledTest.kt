package app.sterna.core.data.mail

import app.sterna.core.jmap.WindowWalk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A full re-query whose walk had to RESTART ON A POSITION must delete nothing.
 */
class ResumedWalkIsNotReconciledTest {

    /** One folder as this path leaves it, reconciled by the shipped complement rule. */
    private class Folder(cached: List<String>) {
        val cache = LinkedHashSet(cached)
        var reconcileRan = false
        var evicted: List<String> = emptyList()

        suspend fun inventory(): Set<String> = cache.toSet()

        suspend fun write(page: List<String>) {
            cache += page
        }

        suspend fun reconcile(keepIds: Set<String>, spareIds: List<String>, evictableIds: Set<String>?) {
            reconcileRan = true
            val gone = reconcileEvictions(
                cachedIds = evictableCachedIds(cache.toList(), evictableIds),
                keepIds = keepIds,
                spareIds = spareIds.toHashSet(),
            ).flatten()
            evicted = gone
            cache -= gone.toSet()
        }
    }

    /**
     * A walk that hands [pages] over one at a time and answers with the ids it handed over —
     */
    private fun walkOf(
        pages: List<List<String>>,
        resumedByPosition: Boolean,
    ): suspend (suspend (List<String>) -> Unit) -> WindowWalk = { onPage ->
        val handed = mutableListOf<String>()
        pages.forEach { page ->
            onPage(page)
            handed += page
        }
        WindowWalk(
            ids = handed,
            queryState = "q1",
            emailState = "e1",
            queryCount = handed.size,
            resumedByPosition = resumedByPosition,
        )
    }

    private fun writeThrough(
        folder: Folder,
        walk: suspend (suspend (List<String>) -> Unit) -> WindowWalk,
    ): WindowWalk = runBlocking {
        fullQueryWriteThrough(
            folderIds = { folder.inventory() },
            walk = walk,
            writePage = folder::write,
            keepIds = { reconcilableWindowIds(it) },
            spareIds = { emptyList() },
            reconcile = { _, keep, spares, evictable -> folder.reconcile(keep, spares, evictable) },
        )
    }

    // The walk's pages: it listed e499/e500, lost its anchor, restarted too far down and came back
    // with e503 onwards. e501 and e502 are in NEITHER page and are still on the server.
    private val pagesOfAResumedWalk = listOf(listOf("e499", "e500"), listOf("e503", "e504"))

    // The cache as the previous sync left it: the two messages the walk is about to step over, plus
    // one row the server really has destroyed since.
    private fun folderBeforeTheWalk() = Folder(cached = listOf("e501", "e502", "destroyed-on-server"))

    // -- the volet ------------------------------------------------------------------------------

    @Test fun `a walk that resumed on a position deletes nothing at all`() {
        // THE test of this volet. e501 and e502 were cached before the walk and are named by no
        // page of it, so the reconcile is entitled to delete them — the #165 bound protects rows
        // that arrived DURING the walk, and these did not. They are on the server. Deleting them
        // here is permanent: `putSyncState` runs on the next lines.
        val folder = folderBeforeTheWalk()

        val walked = writeThrough(folder, walkOf(pagesOfAResumedWalk, resumedByPosition = true))

        assertNull(
            "a walk that restarted on an absolute position is still reconcilable: it may have " +
                "stepped over messages, and the keep set is what stands between that and a delete",
            reconcilableWindowIds(walked),
        )
        assertFalse("the reconcile ran on a walk that may have skipped messages", folder.reconcileRan)
        assertEquals(
            "the reconcile deleted mail the walk skipped and the server still holds. Evicted: " +
                "${folder.evicted}",
            emptyList<String>(),
            folder.evicted,
        )
        assertTrue("e501 was deleted although the server still holds it", "e501" in folder.cache)
        assertTrue("e502 was deleted although the server still holds it", "e502" in folder.cache)
        // The cost, stated: this pass also keeps a row the server destroyed. The next full query
        // re-lists the folder and removes it; nothing loses a message in the meantime.
        assertTrue(
            "the refusal is expected to spare the stale row too — that is what it costs",
            "destroyed-on-server" in folder.cache,
        )
        assertEquals("reconcile=skip/resumed", "reconcile=${fullQueryReconcileReason(walked)}")
    }

    @Test fun `a walk that did NOT resume still caps the cache — the witness`() {
        // The witness for the test above. The same fixture, the same pages, the same rows named
        // by nothing — with the resume withheld, the reconcile runs and clears them. Without this,
        // "nothing was deleted" could be got by switching the reconcile off altogether, and the
        // full query would stop being the only thing that ever caps this folder.
        val folder = folderBeforeTheWalk()

        val walked = writeThrough(folder, walkOf(pagesOfAResumedWalk, resumedByPosition = false))

        assertEquals(
            setOf("e499", "e500", "e503", "e504"),
            reconcilableWindowIds(walked),
        )
        assertTrue("the reconcile no longer runs on an ordinary walk", folder.reconcileRan)
        assertEquals(
            "an ordinary full query no longer removes the rows no page named",
            listOf("e501", "e502", "destroyed-on-server"),
            folder.evicted,
        )
        assertEquals(setOf("e499", "e500", "e503", "e504"), folder.cache)
        assertEquals("reconcile=reconciled", "reconcile=${fullQueryReconcileReason(walked)}")
    }

    // -- the log token and the keep rule cannot drift apart ----------------------------------------

    @Test fun `every input answers one token, and skip means exactly no reconcile`() {
        // [fullQueryReconcileReason] exists to say on the bench WHY a folder was left alone, so it
        // has to be the same decision as [reconcilableWindowIds] and not a second copy of it. The
        // whole grid, both functions, one pass: a `skip` token must be a null keep set and a null
        // keep set must be a `skip` token, whatever else the walk says.
        val grid = listOf(false, true).flatMap { resumed ->
            listOf(emptyList(), listOf("m1")).flatMap { ids ->
                listOf(0, 500).map { queryCount ->
                    WindowWalk(
                        ids = ids,
                        queryState = "q1",
                        emailState = "e1",
                        queryCount = queryCount,
                        resumedByPosition = resumed,
                    )
                }
            }
        }

        grid.forEach { walk ->
            val token = fullQueryReconcileReason(walk)
            assertEquals(
                "the log token and the keep rule disagree about " +
                    "resumedByPosition=${walk.resumedByPosition} ids=${walk.ids} " +
                    "queryCount=${walk.queryCount}: token '$token', " +
                    "keep set ${reconcilableWindowIds(walk)}",
                reconcilableWindowIds(walk) == null,
                token.startsWith("skip"),
            )
        }

        // …and the tokens themselves, in grid order, so a rename or a re-ordered `when` shows up
        // as a failure here instead of as a silent change in what the bench reads back. The
        // last four say that the resume is tested FIRST: a walk that resumed answers `skip/resumed`
        // even when it listed ids, which is the only case where the loss can happen at all.
        assertEquals(
            listOf(
                // resumed = false: no ids + nothing matched, no ids + 500 matched, then ids
                "emptied", "skip/muted", "reconciled", "reconciled",
                // resumed = true: the same four inputs, all refused
                "skip/resumed", "skip/resumed", "skip/resumed", "skip/resumed",
            ),
            grid.map { fullQueryReconcileReason(it) },
        )
    }

    // -- the plug, which no JVM test can execute ---------------------------------------------------

    @Test fun `the full query prints the token it decided with`() {
        // SOURCE LINT, and a whole line: `MailRepository` needs Room, a Context and a live
        // session, so the one thing a JVM test cannot do is watch this line run. The decision above
        // is executed; what is read here is that the log line calls THIS function with THE walk —
        // a token computed from anything else would be a second decision.
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "syncMailboxWindow")
            .lines().map { it.trim() }

        assertTrue(
            "the full query's log line no longer prints the reconcile token, or no longer " +
                "computes it from the walk it just ran. Its body is:\n" + body.joinToString("\n"),
            "\"reconcile=\${fullQueryReconcileReason(walked)}\"," in body,
        )
    }
}
