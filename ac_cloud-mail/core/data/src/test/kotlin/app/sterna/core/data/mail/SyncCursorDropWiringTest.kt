package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. `MailRepository` needs Room, an Android `Context` and a live
 */
class SyncCursorDropWiringTest {

    private fun bodyLines(function: String): List<String> =
        DaoQuerySource.mailFunctionBody("MailRepository", function).lines().map { it.trim() }

    private fun assertLine(function: String, line: String) {
        val lines = bodyLines(function)
        assertTrue(
            "MailRepository.$function no longer contains the line:\n  $line\nits body is:\n" +
                lines.joinToString("\n"),
            line in lines,
        )
    }

    /**
     * The function's body, WHOLE, line by line — not the presence of the lines it should contain.
     */
    private fun assertBody(function: String, expected: String) {
        val actual = bodyLines(function).filter { it.isNotEmpty() }
        assertEquals(
            "MailRepository.$function is no longer, line for line, what this test was written " +
                "against. Read the new body before updating this: an INSERTED line is exactly what " +
                "this pin exists to catch.",
            expected.trimIndent().lines().map { it.trim() }.filter { it.isNotEmpty() },
            actual,
        )
    }

    // -- the per-account drop ----------------------------------------------------------------------

    @Test fun `the drop is these two lines and nothing else`() {
        // The blank-id guard (a blank prefixes every key), then the drop itself with BOTH its
        // arguments: without the sibling ids the key prefix is read on its own, and swapping
        // dropAccount for clear() would send every folder of every OTHER account into a full
        // re-query for a setting changed on one of them.
        assertBody(
            "dropSyncCursors",
            """
            {
            if (accountId.isBlank()) return
            syncStates.dropAccount(accountId, accountStore.accounts().map { it.id })
            }
            """,
        )
    }

    @Test fun `the global reset still exists for the cache wipe that needs it`() {
        // Clearing an account's cache must still forget every cursor, or incremental sync compares
        // against a state the cache no longer backs and re-fetches nothing.
        assertLine("resetSyncState", "syncStates.clear()")
    }

    @Test fun `the persisted half really enumerates the keys it holds`() {
        // Text, for want of anything better: SyncStateStore is SharedPreferences and this module has
        // no Robolectric, so nothing here can EXECUTE it. It is pinned because a keys() that
        // answered emptySet() would leave the whole disk half of the drop doing nothing, silently,
        // and that is the half that decides what the next cold start resumes (#17).
        val lines = DaoQuerySource.mailSource("SyncStateStore").lines().map { it.trim() }
        assertTrue(
            "SyncStateStore.keys() is no longer 'prefs.all.keys - VERSION_KEY'. It feeds the " +
                "per-account drop; nothing in this module can execute it, so this line is the only " +
                "thing standing between a wrong answer and a setting that looks applied and is not.",
            "override fun keys(): Set<String> = prefs.all.keys - VERSION_KEY" in lines,
        )
    }

    @Test fun `the store's schema marker and version are the ones already on disk`() {
        // One character changed in either and the constructor's `if (… < CURSOR_VERSION) clear()`
        // fires on the next start: EVERY account's cursors fall at once, every folder does a full
        // re-query, and nothing anywhere says why. Bumping it is a legitimate migration — it has
        // been used once, for the uncollapsed folder queries — but it must cost a deliberate edit.
        val lines = DaoQuerySource.mailSource("SyncStateStore").lines().map { it.trim() }
        listOf(
            """private const val VERSION_KEY = "cursor_version"""",
            "private const val CURSOR_VERSION = 2",
            """private val prefs = context.getSharedPreferences("sync_states", Context.MODE_PRIVATE)""",
        ).forEach {
            assertTrue(
                "SyncStateStore no longer carries the line:\n  $it\nchanging the preferences file, " +
                    "the marker or the version drops every cursor of every account on the next " +
                    "start. If that is intended, say so by updating this test.",
                it in lines,
            )
        }
    }

    // -- the branch the drop aims at ----------------------------------------------------------------

    @Test fun `syncMailbox still decides on the presence of a cursor`() {
        assertLine("syncMailboxWindow", "val key = syncKey(localAccountId, mailboxId)")
        assertLine("syncMailboxWindow", "val stored = loadSyncState(key)")
        assertLine("syncMailboxWindow", "if (stored != null) {")
    }

    @Test fun `the full query sizes itself on the account, not on its caller — window AND page`() {
        // The lines that make the drop safe to open: BOTH numbers of the full query are derived
        // from the account's window, and it is those — not the caller's `sizing` — that go on the
        // wire. The decision is executed by FullQueryWindowSourceTest; this is the plug, whole
        // lines.
        //
        // Why the page and not only the target. `requestPageSize(wanted, capacity)` caps what
        // ONE request asks for by what the server admits — so a background pass carrying 50 makes
        //
        // The client call gained an `onPage` hand-off (the streaming volet): the rest of that
        // arrangement — write per page, reconcile once, only on a finished walk — is pinned by
        // FullQueryStreamingWiringTest and executed by FullQueryWriteThroughTest. What this test
        // still owns is the sizing reaching the wire.
        assertLine("syncMailboxWindow", "val full = fullQuerySizing(")
        assertLine("syncMailboxWindow", "accountStore.account(localAccountId)?.syncWindow?.limit,")
        assertLine("syncMailboxWindow", "sizing.windowTarget,")
        assertLine("syncMailboxWindow", "session.getBatchSize(),")
        assertLine(
            "syncMailboxWindow",
            "walk = { onPage -> client.queryEmailsWindow(session, accountId, mailboxId, full.windowTarget, full.pageSize, auth, onPage) },",
        )
    }

    @Test fun `the two background callers still carry the 50 this is written against`() {
        // Not a rule, a tripwire: the push/worker pass and the unified inbox pass a number of their
        // own into a branch that REPLACES the folder. If either default moves, re-read
        // FullQueryWindowSourceTest, which is written against 50.
        val source = DaoQuerySource.mailSource("MailRepository").lines().map { it.trim() }
        listOf(
            "suspend fun refreshAllInboxes(accounts: List<AccountCredentials>, limit: Int = 50): UnifiedRefreshResult {",
            "limit: Int = 50,",
        ).forEach {
            assertTrue(
                "MailRepository no longer carries the line:\n  $it\nthe background callers' size " +
                    "changed; the full query must still size itself on the account either way",
                it in source,
            )
        }
    }

    @Test fun `the delta branch still never mentions the window`() {
        // The delta branch never applies it and never fetches backwards. That asymmetry IS the
        // defect this volet works around — the day it learns to fetch backwards, dropping the
        // cursor on a window change stops being the fix and this test has to be rewritten.
        val delta = deltaBranch()
        assertEquals(
            "the delta branch of syncMailbox now mentions the sync window — if it really applies it, " +
                "dropping the cursor on a window change is no longer the fix:\n" +
                delta.filter { "sizing" in it }.joinToString("\n"),
            emptyList<String>(),
            delta.filter { "sizing" in it },
        )
    }

    /** The lines inside `if (stored != null) { … }` in `syncMailbox`, braces balanced. */
    private fun deltaBranch(): List<String> {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "syncMailboxWindow")
        val start = body.indexOf("if (stored != null) {")
        assertTrue("syncMailbox no longer branches on `if (stored != null) {` — renamed?", start >= 0)
        var depth = 0
        var i = body.indexOf('{', start)
        val open = i
        do {
            when (body[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        } while (depth > 0)
        return body.substring(open, i).lines().map { it.trim() }
    }
}
