package app.sterna.ui.inbox

import app.sterna.core.data.account.SyncWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT for the plug, with the value it carries EXECUTED beside it. `InboxViewModel` is an
 */
class SyncWindowIsWhatTheRefreshSendsTest {

    // -- the value that travels down that line ------------------------------------------------------

    @Test fun `the window this path reads is the largest the picker offers`() {
        // The weld between the pinned line below and the constants: the number written out here is
        // the settings label ("10,000 messages"), and if either end moves this fails on the PATH,
        // naming it, as well as in core:data.
        assertEquals(
            "the value `refreshFolder` sends as its refresh limit is no longer the number the " +
                "biggest choice on the settings row promises",
            10_000, SyncWindow.COUNT_10000.limit,
        )
        assertEquals(
            "an account left on \"Everything\" by an older build now caches a number nothing on " +
                "the settings row names",
            10_000, SyncWindow.ALL.limit,
        )
    }

    // -- the plug -----------------------------------------------------------------------------------

    @Test fun `refreshFolder is these six statements, and the window is what it sends`() {
        assertEquals(
            "InboxViewModel.refreshFolder is no longer, line for line, what this test was written " +
                "against. Read the new body before updating it: a number substituted for " +
                "`window.limit`, or a cap inserted above the call, is exactly what this pin " +
                "exists to catch — and no other test in the repo can see it.",
            """
            {
            val credentials = store.load() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
            val window = store.syncWindow(credentials.id)
            val pruneBefore = window.maxAgeDays?.let {
            System.currentTimeMillis() - it.toLong() * MILLIS_PER_DAY
            }
            val inboxWhole = mailboxId == null || mailboxId == store.inboxMailboxId()
            val updated = repo.refresh(credentials, mailboxId, window.limit, if (inboxWhole) null else pruneBefore)
            if (updated.mailboxId == store.inboxMailboxId() || mailboxId == null) {
            store.saveInboxMeta(updated.mailboxId, updated.mailboxName, updated.accountName, updated.unreadCount)
            runCatching { FetchAndNotify.onInboxRefreshed(getApplication(), credentials, updated.mailboxId) }
            }
            selection.value = Sel.Folder(updated.mailboxId)
            meta.value = Meta(updated.accountName, updated.mailboxName, updated.unreadCount)
            }
            """.trimIndent().lines().map { it.trim() }.filter { it.isNotEmpty() },
            bodyLines("refreshFolder"),
        )
    }

    @Test fun `the age cutoff is derived from the same window, not from a second setting`() {
        // The two halves of one setting: `pruneBefore` is the window's age and `window.limit` its
        // count, and `MailRepository.pruneRetention` requires them to come from the SAME window
        // (its floor is read as "keep at least this many whatever their age"). Two different
        // sources here would let the prune delete under a floor the fetch never used.
        val body = bodyLines("refreshFolder")
        assertEquals(
            "refreshFolder reads the sync window more than once — the two halves must come from " +
                "one read, or they can disagree",
            1, body.count { "store.syncWindow(" in it },
        )
        assertTrue("the cutoff is no longer derived from `window`", body.any { it.startsWith("val pruneBefore = window.maxAgeDays") })
    }

    // -- reading the source --------------------------------------------------------------------------

    /** The body of `fun [function]` in `InboxViewModel`, comment and blank lines dropped. */
    private fun bodyLines(function: String): List<String> {
        val source = INBOX_VM.readText()
        val declaration = Regex("""\bfun\s+$function\s*\(""").find(source)
            ?: error("InboxViewModel has no function named '$function' — renamed?")
        var i = source.indexOf('{', declaration.range.last)
        val open = i
        var depth = 0
        do {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        } while (depth > 0)
        return source.substring(open, i).lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }
    }

    private companion object {
        const val INBOX_VM_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VM_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "sources as text and needs a working directory inside the checkout",
                )
        }

        val INBOX_VM: File by lazy { File(root, INBOX_VM_PATH) }
    }
}
