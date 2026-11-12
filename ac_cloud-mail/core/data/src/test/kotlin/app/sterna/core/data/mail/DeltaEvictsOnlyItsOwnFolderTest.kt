package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bench, 2026-08-17, JMAP: a message unarchived (Archive → Inbox) landed in the Inbox, survived a
 */
class DeltaEvictsOnlyItsOwnFolderTest {

    // ---- the decision, executed ------------------------------------------------------------

    @Test fun `a candidate whose cached row sits in another folder is not deleted`() {
        // Archive's delta names M1; the cache holds M1 under Inbox, so Archive's own rows do not
        // contain it. Deleting it here is the reported loss.
        assertEquals(
            emptyList<String>(),
            mailboxScopedEvictions(candidates = listOf("M1"), cachedInMailbox = setOf("A1", "A2")),
        )
    }

    @Test fun `a candidate whose cached row is still in this folder is deleted`() {
        // The ordinary case the gate must not break: the message really left the folder we are
        // syncing, and its row still says so. It goes.
        assertEquals(
            listOf("M1"),
            mailboxScopedEvictions(candidates = listOf("M1"), cachedInMailbox = setOf("M1", "A1")),
        )
    }

    @Test fun `the folder's own candidates go and the others stay, in the order the delta named them`() {
        assertEquals(
            listOf("M1", "M3"),
            mailboxScopedEvictions(listOf("M1", "M2", "M3"), cachedInMailbox = setOf("M3", "M1", "A9")),
        )
    }

    @Test fun `a candidate the cache does not hold at all is nothing to delete`() {
        assertEquals(emptyList<String>(), mailboxScopedEvictions(listOf("M1"), cachedInMailbox = emptySet()))
    }

    @Test fun `the unarchived message survives the refresh of the folder it left`() {
        // THE bench sequence, replayed end to end and with the two decisions composed exactly as
        // the shipped delta composes them.
        //
        // State: M7 was in Archive, has been moved to the Inbox, and the Inbox delta has already
        // written its row. Now the unread view refreshes ARCHIVE.
        val cache = mutableMapOf("M7" to "inbox", "A1" to "archive", "A2" to "archive")

        // Archive's Email/queryChanges: M7 is no longer in this folder's query. Nothing was added,
        // nothing was destroyed, and the app itself did not mutate M7 (the move came from the
        // Inbox pass a moment ago, past the protection window, and from another client it never
        // would have been in it at all).
        val candidates = deltaEvictions(
            removed = listOf("M7"),
            added = emptySet(),
            destroyed = emptyList(),
            isProtected = { false },
        )
        assertEquals("the server DID say M7 left Archive — that part was never wrong", listOf("M7"), candidates)

        val archiveRows = cache.filterValues { it == "archive" }.keys
        val toDelete = mailboxScopedEvictions(candidates, archiveRows)

        // What the shipped DELETE does with that list: `WHERE accountId = ? AND id IN (?)`, no
        // folder — the statement pinned below. Run it on the cache and read the Inbox.
        toDelete.forEach { cache.remove(it) }

        assertEquals("the Archive delta may delete nothing here", emptyList<String>(), toDelete)
        assertEquals("the unarchived message must still be in the Inbox", "inbox", cache["M7"])
        assertEquals(setOf("M7", "A1", "A2"), cache.keys)
    }

    @Test fun `a message another client filed into Archive is not lost by the Inbox pass behind it`() {
        // The other direction, and it was losing mail too — but by the sequence the app really
        // runs, which is NOT one multi-folder pass: `refreshAccountFolders` always puts the inbox
        val cache = mutableMapOf("M8" to "archive")
        val inboxRows = cache.filterValues { it == "inbox" }.keys

        val toDelete = mailboxScopedEvictions(
            deltaEvictions(removed = listOf("M8"), added = emptySet(), destroyed = emptyList()) { false },
            inboxRows,
        )
        toDelete.forEach { cache.remove(it) }

        assertEquals(emptyList<String>(), toDelete)
        assertEquals("archive", cache["M8"])
    }

    // ---- the premise the gate rests on, read from the shipped DAO --------------------------

    @Test fun `the delete this gate bounds still names no folder`() {
        // If this ever stops holding, the gate above is not the fix any more — re-read it rather
        // than deleting it. It is the whole reason a folder's delta can reach another folder's row.
        val sql = DaoQuerySource.emailDaoQuery("deleteRowsByIds")
        assertEquals(
            "the bulk row delete is keyed on (accountId, id) with no mailbox in its WHERE",
            "DELETE FROM emails WHERE accountId = :accountId AND id IN (:ids)",
            sql.trim(),
        )
    }

    // ---- the wiring, read from the shipped source ------------------------------------------

    @Test fun `the delete receives the filtered list, never the delta's raw candidates`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "syncMailboxWindow")
        assertEquals(
            "the incremental branch must delete what the folder still holds (toDelete), not what " +
                "the delta named (toRemove) — the latter reaches rows that have moved on",
            listOf("if (toDelete.isNotEmpty()) emailDao.deleteByIds(localAccountId, toDelete)"),
            codeLinesNaming(body, "emailDao.deleteByIds("),
        )
        assertEquals(
            "and the list must come from the pure decision, applied to this folder's own rows",
            listOf("val toDelete = mailboxScopedEvictions(toRemove, inThisMailbox)"),
            codeLinesNaming(body, "mailboxScopedEvictions("),
        )
    }

    @Test fun `the set compared against is this mailbox's own rows, read before the delete`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "syncMailboxWindow")
        assertEquals(
            "the comparison set must be read FOR THIS MAILBOX and this account (#31/#92: an email " +
                "id is unique only within its account, and two accounts share mailbox ids)",
            listOf(
                "if (toRemove.isEmpty()) emptySet() else emailDao.idsForMailbox(localAccountId, mailboxId).toSet()",
                "val cachedIds = emailDao.idsForMailbox(localAccountId, mailboxId).toSet()",
                // The full-query branch's own read (#165): the folder as it stood BEFORE the walk,
                // subtracted from a second read at the reconcile so that mail which landed during
                // the walk is not deleted. Same two arguments, for the same reason as the two above.
                "folderIds = { emailDao.idsForMailbox(localAccountId, mailboxId).toSet() },",
            ),
            codeLinesNaming(body, "idsForMailbox("),
        )

        // And EXACTLY one call to the reconcile, inside the write-through's `reconcile` lambda.
        // The hole this closes was found by counter-expertise and no other test sees it: an
        assertEquals(
            "syncMailbox no longer reconciles exactly once, from inside the write-through, with " +
                "the bound the write-through computed (evictableIds = evictable). A second call, " +
                "or one that names its own keep set or drops the bound, deletes the mail that " +
                "arrived while the walk ran and the cursor makes it permanent (#165)",
            listOf(
                "reconcile = { _, keepIds, spare, evictable -> if (inboxServerIds != null) emailDao.reconcileMailbox(localAccountId, mailboxId, keepIds + inboxServerIds, spare, evictableIds = evictable) },",
            ),
            codeLinesNaming(body, "emailDao.reconcileMailbox("),
        )

        val lines = body.lines().map { it.trim() }
        val gateRead = lines.indexOfFirst { it.startsWith("if (toRemove.isEmpty()) emptySet() else") }
        val delete = lines.indexOfFirst { it == "if (toDelete.isNotEmpty()) emailDao.deleteByIds(localAccountId, toDelete)" }
        val fetchRead = lines.indexOfFirst { it == "val cachedIds = emailDao.idsForMailbox(localAccountId, mailboxId).toSet()" }
        assertTrue("the gate's cache read is gone", gateRead >= 0)
        assertTrue("the bounded delete is gone", delete >= 0)
        assertTrue("deltaFetches' cache read is gone", fetchRead >= 0)
        assertTrue(
            "the gate must read the folder BEFORE deleting from it: read it after and every " +
                "candidate is already gone from the comparison set, so nothing is ever spared",
            gateRead < delete,
        )
        assertTrue(
            "and deltaFetches' read must stay AFTER the delete, where its meaning was decided",
            delete < fetchRead,
        )
    }

    @Test fun `the comparison set is the folder's rows and nothing added to them`() {
        // THE LINE NO OTHER ASSERTION READS. The declaration is split in two, and every pinned
        // keyword (`idsForMailbox(`, `mailboxScopedEvictions(`, `deleteByIds(`) lives on one of
        assertEquals(
            "nothing may be added to the set the gate compares against: it is this folder's " +
                "cached rows, exactly, or the gate spares nobody",
            listOf(
                "val inThisMailbox =",
                "val toDelete = mailboxScopedEvictions(toRemove, inThisMailbox)",
            ),
            codeLinesNaming(DaoQuerySource.mailFunctionBody("MailRepository", "syncMailboxWindow"), "inThisMailbox"),
        )
    }

    @Test fun `what the folder reports as departed is unchanged, and stays the server's word`() {
        // Deliberately NOT toDelete. The server did say those ids left this mailbox, which is
        // exactly what a notification cancellation claims (#134) and what `confirmDepartures` then
        // puts to the server. Only the local DELETE is bounded by the folder.
        assertEquals(
            listOf("return MailboxSync(fetchedIds = toFetch, departedIds = toRemove + swept)"),
            codeLinesNaming(
                DaoQuerySource.mailFunctionBody("MailRepository", "syncMailboxWindow"),
                "departedIds = toRemove",
            ),
        )
    }

    /** The code lines of [body] naming [needle], comments dropped. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }
}
