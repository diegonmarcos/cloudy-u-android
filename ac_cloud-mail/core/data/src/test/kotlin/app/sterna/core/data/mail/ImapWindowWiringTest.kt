package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. Everything it pins is EXECUTED elsewhere — the order of the
 */
class ImapWindowWiringTest {

    private fun lines(file: String, function: String): List<String> =
        DaoQuerySource.mailFunctionBody(file, function).lines().map { it.trim() }

    private fun assertLine(file: String, function: String, line: String) {
        val body = lines(file, function)
        assertTrue(
            "$file.$function no longer contains the line:\n  $line\nits body is:\n" + body.joinToString("\n"),
            line in body,
        )
    }

    // -- the plug: the IMAP full re-query ------------------------------------------------------------

    @Test fun `the IMAP refresh routes through the shared write-through, with these arguments`() {
        // Pinned as the WHOLE body: the mutations that matter here are insertions and removals —
        // a `try` around the walk, a `return` above the reconcile, a fifth argument gone missing —
        // and no presence check sees any of them.
        //
        // The FIRST line is the one the unified inbox turns on. This function ends in a
        // reconcile, which DELETES every cached row it is not given, and `refreshAllInboxes`
        //
        // Each argument carries one property:
        //  - folderIds: the folder as it stood BEFORE the walk, which bounds what the reconcile
        //
        // The two `checkAccountStillConfigured` lines are the sign-out guard: this walk runs for
        // minutes on a deep folder, and nothing cancels it when the account is signed out from the
        // settings screen. Their position is pinned by SignedOutAccountWiringTest and the decision
        // executed by SignedOutAccountStopsWritingTest.
        assertEquals(
            "MailRepository.imapWriteThrough is no longer, line for line, what this was written " +
                "against. Read the new body before touching this test: every line below stands " +
                "between a paginated walk and a DELETE of every cached row it did not name.",
            """
            {
            checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
            val window = fullQueryWindowTarget(accountStore.account(credentials.id)?.syncWindow?.limit, limit)
            return fullQueryWriteThrough<List<EmailEntity>, ImapFolderLoad>(
            folderIds = { mailboxId?.let { emailDao.idsForMailbox(credentials.id, it).toSet() } },
            walk = { onPage -> imap.loadFolder(credentials, mailboxId, window, onlySubscribed = accountStore.showOnlySubscribedFolders(credentials.id), onPage = onPage, cachedPreviewsFor = listPreviewCache(credentials.id)) },
            writePage = { page ->
            checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
            emailDao.upsertAll(page)
            },
            keepIds = { load -> reconcilableIds(load, credentials.id) },
            spareIds = { recentlyMutatedIds(credentials.id) },
            reconcile = { load, keepIds, spare, evictable ->
            val kept = syncImapBacklog(credentials, load)
            if (kept != null) emailDao.reconcileMailbox(
            credentials.id, load.targetMailboxId, keepIds + kept, spare,
            evictableIds = evictable.takeIf { mailboxId != null && mailboxId == load.targetMailboxId },
            )
            },
            )
            }
            """.trimIndent().lines().map { it.trim() }.filter { it.isNotEmpty() },
            lines("MailRepository", "imapWriteThrough").filter { it.isNotEmpty() && !it.startsWith("//") },
        )
    }

    @Test fun `the IMAP write-through catches nothing and has no finally`() {
        // The half of the red line that lives at the call site. A `runCatching` here would let the
        // refresh carry on as if the folder had synced — and, worse, would turn a failure into a
        // walk that "finished".
        val body = lines("MailRepository", "imapWriteThrough")
        listOf("try {", "catch (", "finally", "runCatching").forEach { guard ->
            assertEquals(
                "imapWriteThrough now carries a `$guard`: a failure mid-walk no longer climbs out, " +
                    "so the reconcile can run against half a folder and delete the other half:\n" +
                    body.filter { guard in it }.joinToString("\n"),
                emptyList<String>(),
                body.filter { guard in it },
            )
        }
    }

    @Test fun `both IMAP refresh paths go through it, and neither writes the folder itself`() {
        assertLine("MailRepository", "refreshImap", "val load = imapWriteThrough(credentials, mailboxId, limit)")
        assertLine(
            "MailRepository", "refreshAllInboxes",
            "val load = imapWriteThrough(credentials, mailboxId = null, limit = limit)",
        )
        // `replaceMailbox` writes a whole snapshot and deletes everything else in one call. Its
        // return to either path would mean the window is held on one heap again — and, page by
        // page, would delete all but the last page.
        listOf("refreshImap", "refreshAllInboxes", "imapWriteThrough").forEach { function ->
            assertEquals(
                "$function calls replaceMailbox again",
                emptyList<String>(),
                lines("MailRepository", function).filter { "replaceMailbox(" in it },
            )
        }
    }

    @Test fun `the retention prune is told the same thing the reconcile was`() {
        // Not `load.walk.uids`, and not a set built here: the prune deletes on the strength of this
        // answer, so it must be the same refusal. `reconcilableIds` answering null makes
        // `retentionEvictions` prune nothing (executed in ImapFullQueryWriteThroughTest).
        assertLine("MailRepository", "refreshImap", "reconcilableIds(load, credentials.id),")
    }

    // -- the plug: the account's window reaching the network -------------------------------------------

    @Test fun `the window the caller asked for is what the folder walk is given`() {
        // The line the cartography found untested: `SyncWindow.ALL.limit` travels
        // InboxViewModel → refresh → refreshImap → imapWriteThrough → loadFolder → walkFolder, and
        assertLine("ImapMailService", "loadFolder", "session.walkFolder(status, limit, IMAP_FOLDER_PAGE) { page ->")
        // And `status.uidValidity` travels with the page: the rows are stamped with the numbering
        // of THE SELECT THAT ENUMERATED THEM (#99). Dropped here, a cached row says nothing about
        assertLine("ImapMailService", "loadFolder", "val preview = previews[emailId(credentials.id, target.path, it.uid)]")
        assertLine(
            "ImapMailService",
            "loadFolder",
            "it.toEntity(credentials.id, target.path, status.uidValidity, preview)",
        )
    }

    @Test fun `the folder's size is what the SELECT said, never a verdict written here`() {
        // THE MUTATION THIS EXISTS FOR, which the whole suite survived until it was written:
        //
        //     - val status = session.select(target.path)
        //     + val status = session.select(target.path).copy(existsObserved = true)
        //
        // `exists` stays 0 (the server said nothing), `existsObserved` becomes true, the walk then
        // STATES the folder empty, `reconcilableIds` answers the empty set, and the reconcile
        //
        // Nothing executable can see it: no JVM test runs `loadFolder` (Room, an Android `Context`,
        // a live socket), `core:imap`'s wire tests drive the session directly, and the decision
        // tests build their walk by hand. The `walkFolder` line above is unchanged to the character
        // under the mutation, so pinning it proves nothing here.
        //
        // Two facts, and the second is the load-bearing one: the SELECT's answer is taken WHOLE, and
        // this function does not name `existsObserved` or `copy(` at all. The verdict TRAVELS
        // through `loadFolder`; it is never written in it.
        assertLine("ImapMailService", "loadFolder", "val status = session.select(target.path)")
        val body = lines("ImapMailService", "loadFolder").filterNot { it.startsWith("//") }
        listOf("existsObserved", "copy(").forEach { forged ->
            assertEquals(
                "ImapMailService.loadFolder now names `$forged`. The one thing this function may " +
                    "not do is decide whether the server stated the folder's size: an `existsObserved` " +
                    "asserted here licenses the reconcile to DELETE a folder the app knows nothing " +
                    "about:\n" + body.filter { forged in it }.joinToString("\n"),
                emptyList<String>(),
                body.filter { forged in it },
            )
        }
    }

    @Test fun `the numbering is settled before the walk, through the function that says so`() {
        // Codeberg #99, re-opened by writing pages as they land: a row of the new numbering must
        // not be visible while a body cached under the old one is still readable. The ORDER is
        assertLine("ImapMailService", "loadFolder", "val walk = withNumberingSettled(")
        assertLine(
            "ImapMailService", "loadFolder",
            "settle = { reconcileNumbering(credentials.id, target.path, status.uidValidity) },",
        )
        assertLine("ImapMailService", "loadFolder", "walk = {")
        // And nothing reconciles the numbering a second time, after the walk, which is where it
        // used to be: a straggler there would look like belt-and-braces and be the old window.
        assertEquals(
            "loadFolder reconciles the numbering somewhere other than inside withNumberingSettled",
            1,
            lines("ImapMailService", "loadFolder").count { "reconcileNumbering(" in it },
        )
    }

    @Test fun `settling the numbering is two statements in this order, and nothing else`() {
        assertEquals(
            "withNumberingSettled is no longer, line for line, what this was written against. Its " +
                "whole content is an ORDER: the caches keyed by the old UIDs are dropped BEFORE a " +
                "row of the new numbering can be seen.",
            listOf("{", "settle()", "return walk()", "}"),
            lines("ImapMailService", "withNumberingSettled").filter { it.isNotEmpty() && !it.startsWith("//") },
        )
    }

    @Test fun `the folder load guards neither the walk nor the page write`() {
        // The third place the red line can be broken, and the least visible: a `runCatching` around
        // `onPage(...)` here would let a page fail to be WRITTEN while the walk carried on and
        val body = lines("ImapMailService", "loadFolder")
        listOf("try {", "catch (", "finally", "runCatching").forEach { guard ->
            assertEquals(
                "ImapMailService.loadFolder now carries a `$guard`: a page that fails to fetch or " +
                    "to write no longer stops the walk, and the reconcile that follows deletes " +
                    "what that page did not re-write:\n" + body.filter { guard in it }.joinToString("\n"),
                emptyList<String>(),
                body.filter { guard in it },
            )
        }
    }

    @Test fun `the folder walk keeps no messages of its own`() {
        // The shape that bounds the memory: `loadFolder` hands each page over and answers with a
        // walk. A `val messages = ...` collected here and returned would put the window back on one
        // heap, whatever the wire looked like.
        assertEquals(
            "ImapMailService.loadFolder collects messages again",
            emptyList<String>(),
            lines("ImapMailService", "loadFolder").filter { it.startsWith("val messages") },
        )
        assertLine("ImapMailService", "loadFolder", "walk = walk,")
    }

    @Test fun `the two refusals are read from the walk and nowhere else`() {
        // The decision, whole. It is four lines so it can be pinned entire: the mutations to fear
        // are `if (load.walk.moved) return null` losing its `!`-equivalent, the empty-walk refusal
        // below it going missing, or either refusal turning into an empty set — which would delete
        // the folder instead of sparing it.
        //
        // The second line is the one added for a walk that read NOTHING: a SELECT that never
        // stated a count, or a page whose every FETCH was unreadable, both end with no UID and
        assertEquals(
            "reconcilableIds is no longer, line for line, what this was written against. Null is a " +
                "REFUSAL to reconcile; an empty set is an instruction to delete the folder.",
            """
            {
            if (load.walk.moved) return null
            if (load.walk.uids.isEmpty() && !load.walk.folderStatedEmpty) return null
            return load.walk.uids.mapTo(HashSet()) { ImapMailService.emailId(accountId, load.targetMailboxId, it) }
            }
            """.trimIndent().lines().map { it.trim() }.filter { it.isNotEmpty() },
            lines("ImapMailService", "reconcilableIds").filter { it.isNotEmpty() && !it.startsWith("//") },
        )
    }
}
