package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. The order this pins — write each page, reconcile once, only
 */
class FullQueryStreamingWiringTest {

    private fun lines(file: String, function: String): List<String> =
        DaoQuerySource.mailFunctionBody(file, function).lines().map { it.trim() }

    private fun assertLine(file: String, function: String, line: String) {
        val body = lines(file, function)
        assertTrue(
            "$file.$function no longer contains the line:\n  $line\nits body is:\n" +
                body.joinToString("\n"),
            line in body,
        )
    }

    // -- the decision function, pinned whole -------------------------------------------------------

    @Test fun `fullQueryWriteThrough is these six statements, in this order, with no guard`() {
        // The red line of the volet, as source. It is short precisely so it can be pinned
        // entire: the mutations this exists to catch are INSERTIONS (a try/catch around the walk,
        // a finally around the reconcile), and no presence check can see an inserted line.
        //
        // The `?: return walked` is the IMAP half — a walk whose folder was renumbered under it
        // answers null and nothing is deleted. It sits ABOVE `spareIds()` on purpose: not
        // reconciling must not consume the protection window's read either.
        //
        // `val before = folderIds()` is the #165 half, and its POSITION is the fix: read ONCE,
        // before the walk, and carried into `reconcile` as the fourth argument — the set of rows
        // that DELETE is allowed to touch.
        assertEquals(
            "fullQueryWriteThrough is no longer, line for line, what this was written against. " +
                "Read the new body before touching this test. A `try`, a `catch` or a `finally` " +
                "here means the reconcile can run on a walk that did not finish, and the " +
                "reconcile DELETES every cached row outside the ids it is given. And moving " +
                "`val before = folderIds()` BELOW the walk, or dropping it from the `reconcile` " +
                "call, makes the whole guard inert without changing a single test that only " +
                "looks at the folder afterwards: mail that lands while the walk runs is named by " +
                "no page, the reconcile deletes it, the cursor is stored, and #165 is back.",
            """
            {
            val before = folderIds()
            val walked = walk(writePage)
            val keep = keepIds(walked) ?: return walked
            val spare = spareIds()
            reconcile(walked, keep, spare, before)
            return walked
            }
            """.trimIndent().lines().map { it.trim() }.filter { it.isNotEmpty() },
            lines("FullQuerySync", "fullQueryWriteThrough").filter { it.isNotEmpty() && !it.startsWith("//") },
        )
    }

    // -- the plug: syncMailbox's full-query branch -------------------------------------------------

    @Test fun `the full query routes through the write-through, with these arguments`() {
        // Each argument carries one of the volet's properties, and each is pinned
        // whole because each has a plausible-looking wrong version:
        assertLine("MailRepository", "syncMailboxWindow", "val full = fullQuerySizing(")
        assertLine("MailRepository", "syncMailboxWindow", "val walked = fullQueryWriteThrough<List<Email>, WindowWalk>(")
        // #165: the folder as it stood BEFORE the walk, which travels to the reconcile as the
        // bound on what may be DELETED. `localAccountId` and `mailboxId` are the SAME two the
        // reconcile is given below — a bound read off another folder would evict nothing at all
        // and switch the reconcile off. On this path it is never null: the folder is known.
        assertLine(
            "MailRepository", "syncMailboxWindow",
            "folderIds = { emailDao.idsForMailbox(localAccountId, mailboxId).toSet() },",
        )
        assertLine(
            "MailRepository", "syncMailboxWindow",
            "walk = { onPage -> client.queryEmailsWindow(session, accountId, mailboxId, full.windowTarget, full.pageSize, auth, onPage) },",
        )
        // writePage became a block when the sign-out guard moved into it: the page is refused
        // when the account is no longer configured. The guard line and its position inside the
        // block are pinned by SignedOutAccountWiringTest; what this test still owns is that the
        // write itself is an `upsertAll` of the whole page, tagged with the walk's account.
        assertLine("MailRepository", "syncMailboxWindow", "writePage = { fresh ->")
        assertLine(
            "MailRepository", "syncMailboxWindow",
            "emailDao.upsertAll(fresh.map { it.toEntity(localAccountId, mailboxId) })",
        )
        assertLine("MailRepository", "syncMailboxWindow", "keepIds = { reconcilableWindowIds(it) },")
        assertLine("MailRepository", "syncMailboxWindow", "spareIds = { recentlyMutatedIds(localAccountId) },")
        assertLine(
            "MailRepository", "syncMailboxWindow",
            "reconcile = { _, keepIds, spare, evictable -> if (inboxServerIds != null) emailDao.reconcileMailbox(localAccountId, mailboxId, keepIds + inboxServerIds, spare, evictableIds = evictable) },",
        )
    }

    @Test fun `what the full query returns is the walk's ids, whole`() {
        // Codeberg #110 on requests 2..n: this list is the retention prune's freshIds. The last
        // page's ids would make the prune delete, in the same refresh, what the server just sent.
        assertLine("MailRepository", "syncMailboxWindow", "return MailboxSync(fetchedIds = walked.ids, departedIds = emptyList())")
    }

    @Test fun `the full query branch catches nothing and has no finally`() {
        // The half of the red line that lives at the call site rather than in the function: an
        // exception must climb out of syncMailbox. A `runCatching` around the write-through here
        // would let the caller carry on as if the folder had synced — and store a cursor on it.
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "syncMailboxWindow").lines().map { it.trim() }
        val start = body.indexOfFirst { it.startsWith("val full = fullQuerySizing(") }
        assertTrue("syncMailbox no longer starts its full-query branch with `val full = fullQuerySizing(`", start >= 0)
        val tail = body.drop(start)
        listOf("try {", "catch (", "finally", "runCatching").forEach { guard ->
            assertEquals(
                "the full-query branch of syncMailbox now carries a `$guard`. If a failure mid-walk " +
                    "no longer climbs out, the reconcile can run against half a folder and delete " +
                    "the other half — or a cursor gets stored on a folder that never synced:\n" +
                    tail.filter { guard in it }.joinToString("\n"),
                emptyList<String>(),
                tail.filter { guard in it },
            )
        }
    }

    // -- the DAO half -------------------------------------------------------------------------------

    @Test fun `replaceMailbox still upserts and then reconciles, for its remaining caller`() {
        // The extraction must be invisible to the caller that still hands over a whole snapshot at
        // once — emptying a deleted folder's cache, `MailRepository.deleteMailbox`: same two
        // things, same order, one transaction. Pinned as the whole body — the failure this
        // catches is a line moving or appearing, and no presence check sees either.
        assertEquals(
            "EmailDao.replaceMailbox is no longer, line for line, what this was written against. " +
                "Its caller hands it a whole snapshot and relies on it writing THEN deleting.",
            listOf(
                "{",
                "upsertAll(emails)",
                "reconcileMailboxRows(accountId, mailboxId, emails.mapTo(HashSet()) { it.id }, spareIds, evictableIds = null)",
                "}",
            ),
            DaoQuerySource.daoFunctionBody("EmailDao", "replaceMailbox")
                .lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    @Test fun `exactly one transaction, whichever way into the reconcile`() {
        // The two entry points are transactional and the shared body is NOT. If the body were,
        // `replaceMailbox` would open a transaction inside a transaction — supported by Room and
        assertTrue("replaceMailbox is no longer one transaction", DaoQuerySource.isTransactional("EmailDao", "replaceMailbox"))
        assertTrue("reconcileMailbox is no longer one transaction", DaoQuerySource.isTransactional("EmailDao", "reconcileMailbox"))
        assertEquals(
            "EmailDao.reconcileMailboxRows is now @Transaction. Both of its callers already are, so " +
                "this puts a transaction inside a transaction on the delete path.",
            false, DaoQuerySource.isTransactional("EmailDao", "reconcileMailboxRows"),
        )
        assertEquals(
            "EmailDao.reconcileMailbox is no longer a bare call to the shared body: it has grown a " +
                "decision of its own, which is then only reachable through one of the two doors.",
            listOf("{", "reconcileMailboxRows(accountId, mailboxId, keepIds, spareIds, evictableIds)", "}"),
            DaoQuerySource.daoFunctionBody("EmailDao", "reconcileMailbox")
                .lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
        assertEquals(
            "replaceMailbox grew a fallback path — a reconcile that can be replayed outside its " +
                "transaction is a delete nobody is watching",
            emptyList<String>(),
            DaoQuerySource.emailDaoPath("replaceMailbox").fallback.map { it.function },
        )
    }

    @Test fun `the reconcile is these four lines, and evicts WITHOUT un-indexing`() {
        // Pinned whole, because the mutations that matter here are ARGUMENT substitutions:
        // `keepIds` and `spareIds` are both id collections, so handing reconcileEvictions the wrong
        assertEquals(
            "EmailDao.reconcileMailboxRows is no longer, line for line, what this was written against.",
            listOf(
                "{",
                "reconcileEvictions(",
                "cachedIds = evictableCachedIds(idsForMailbox(accountId, mailboxId), evictableIds),",
                "keepIds = keepIds,",
                "spareIds = spareIds.toHashSet(),",
                ").forEach { batch -> evictFromCacheKeepingIndex(accountId, batch) }",
                "}",
            ),
            DaoQuerySource.daoFunctionBody("EmailDao", "reconcileMailboxRows")
                .lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
        // `deleteByIds` instead of `evictFromCacheKeepingIndex` is an IRREVERSIBLE loss on IMAP:
        // these messages are still in their folder on the server, and nothing re-indexes a row the
        // cache no longer holds (the crawl is JMAP only).
        val statements = DaoQuerySource.emailDaoStatements("reconcileMailbox")
        assertEquals(
            "reconcileMailbox no longer issues exactly `idsForMailbox` then `deleteRowsByIds`",
            listOf("idsForMailbox", "deleteRowsByIds"),
            statements.map { it.function },
        )
        val evict = statements.single { it.function == "deleteRowsByIds" }.sql
        assertTrue(
            "the reconcile's eviction now touches something other than the emails table — if it " +
                "reaches the FTS index, offline search is capped at the sync window on every " +
                "refresh and permanently on IMAP:\n$evict",
            evict.startsWith("DELETE FROM emails WHERE"),
        )
        assertTrue("the eviction is no longer scoped to one account (#31/#121):\n$evict", "accountId = :accountId" in evict)
    }
}
