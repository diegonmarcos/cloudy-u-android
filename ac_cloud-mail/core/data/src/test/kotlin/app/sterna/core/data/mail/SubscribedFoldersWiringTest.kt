package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * WHICH boolean reaches `ImapSession.listFolders`, and at which call sites (#174).
 *
 * The decision itself is executed elsewhere — `withSubscriptions`/`parseLsubPaths` in
 * `:core:imap` (`SubscribedFoldersTest`) and `showOnlySubscribedFoldersOf` in
 * `ShowOnlySubscribedFoldersTest`. What no executable test on this bench can reach is the WIRING:
 * `MailRepository` opens an `AccountStore` (shared preferences) in its constructor and
 * `ImapMailService` needs a live session, and there is no Robolectric here — same reason as
 * [DecryptedSubjectCallSiteTest], whose source-reading harness this reuses.
 *
 * Both halves matter and neither implies the other:
 * - passing a hard-coded `false` from the repository compiles, keeps every other test green, and
 *   leaves the setting INERT on IMAP — nothing is ever hidden, the switch does nothing;
 * - passing `onlySubscribed` at a call site whose rows are DISCARDED costs an LSUB round trip for
 *   nothing. Three such sites exist and all three must stay bare: the rename-delimiter lookup, the
 *   connection test, and the push-wake refresh. `ImapMailService.listMailboxes` is a fourth — its
 *   one caller reads a role off the rows and writes none of them, so a flag there would buy an
 *   extra full listing on every archive and every delete, on exactly the big-folder-list account
 *   this feature was asked for.
 *
 * Assertions are on WHOLE trimmed lines, by equality, and they screen EVERY code line that calls
 * these functions — never `contains`, which is blind to any mutation that lengthens a line.
 */
class SubscribedFoldersWiringTest {

    @Test fun `the repository passes the account's own setting, at every call site`() {
        assertEquals(
            "every imap.loadFolder / imap.listMailboxes call must pass the ACCOUNT'S setting. " +
                "A literal false leaves the switch inert on IMAP. Lines found:",
            listOf(
                "walk = { onPage -> imap.loadFolder(credentials, mailboxId, window, " +
                    "onlySubscribed = accountStore.showOnlySubscribedFolders(credentials.id), onPage = onPage, " +
                    "cachedPreviewsFor = listPreviewCache(credentials.id)) },",
                // Bare, and the only bare one in this list: these rows are never cached, so a
                // subscription fetched for them is an LSUB paid on every archive and every delete.
                "val folders = imap.listMailboxes(credentials)",
                // The value STATED by the caller, not re-read from the store here: the toggle
                // path hands it down (`refreshFolderList`), so no ordering between the store write
                // and this listing can turn the feature off.
                "val load = imap.loadFolder(credentials, requestedMailboxId = null, limit = 1, " +
                    "onlySubscribed = onlySubscribed, onPage = {}, cachedPreviewsFor = null)",
            ),
            codeLines("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
                .filter { it.contains("imap.loadFolder(") || it.contains("imap.listMailboxes(") },
        )
    }

    @Test fun `only the call site whose rows are CACHED asks about subscription`() {
        assertEquals(
            "loadFolder builds the MailboxEntity rows the cache keeps, so it alone carries the " +
                "flag; the rename-delimiter lookup, the connection test, the role-folder listing " +
                "and the watched-folder refresh all discard their rows and must keep their bare " +
                "LIST. Lines found:",
            listOf(
                "withSession(credentials) { it.listFolders() }", // listImapFolders — a delimiter, no more
                "withSession(credentials) { it.listFolders() }", // testConnection
                "session.listFolders().mapIndexed { index, folder ->", // listMailboxes — rows discarded
                "val folders = session.listFolders(onlySubscribed)", // loadFolder — the cached rows
                "val folders = session.listFolders()", // the push-wake refresh: no LSUB on every wake-up
            ),
            codeLines("core/data/src/main/kotlin/app/sterna/core/data/mail/ImapMailService.kt")
                .filter { it.contains("listFolders(") },
        )
    }

    /**
     * WHERE the boolean comes from, at each of the two doors into the folder-list refresh — the
     * order of these seven lines IS the fix (#174).
     *
     * The toggle path enters by `refreshFolderList(accountId, onlySubscribed)` and hands its own
     * argument down; the create/rename/delete paths enter by the one-argument overload, which has
     * no opinion of its own and reads the account's stored setting. Collapse the two into a single
     * function that re-reads the store and the tick becomes order-dependent: sync before write and
     * the listing goes out under the OLD value, the LSUB is never asked for, and the switch is
     * inert again — with every other test in this branch still green.
     */
    @Test fun `the toggle's own value reaches the listing, and only the other door reads the store`() {
        val lines = codeLines("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        val at = lines.indexOfFirst { it.startsWith("suspend fun refreshFolderList(") }
        assertEquals(
            "the two doors into the folder-list refresh changed shape. Lines found:",
            listOf(
                "suspend fun refreshFolderList(accountId: String, onlySubscribed: Boolean) {",
                "val credentials = accountStore.credentials(accountId) ?: return",
                // `onlySubscribed`, the caller's — NOT accountStore.showOnlySubscribedFolders(...).
                "refreshMailboxes(credentials, onlySubscribed)",
                "}",
                "private suspend fun refreshMailboxes(credentials: AccountCredentials) =",
                "refreshMailboxes(credentials, accountStore.showOnlySubscribedFolders(credentials.id))",
                "private suspend fun refreshMailboxes(credentials: AccountCredentials, onlySubscribed: Boolean) {",
            ),
            if (at < 0) listOf("<no refreshFolderList in MailRepository at all>") else lines.subList(at, minOf(at + 7, lines.size)),
        )
    }

    /**
     * **What is WRITTEN to the folder cache — every write, whole line** (#174).
     *
     * The pin this branch was missing, and the mutation it exists for:
     *
     * ```
     * -    mailboxDao.replaceAll(credentials.id, load.mailboxes)
     * +    mailboxDao.replaceAll(credentials.id, load.mailboxes.filter { it.isSubscribed })
     * ```
     *
     * That one passed the whole suite. The seven-line pin above stops at the private overload's
     * SIGNATURE, the call-site pin above only reads lines naming `imap.loadFolder(`, the executable
     * test next door never builds a `MailRepository` at all, and the "filter at the screen" guard
     * lives in `:app` and scans `app/src/main` only — `:core:data` is not swept by it.
     *
     * And the damage is DATA, not display: ticking the box would DELETE the unsubscribed
     * folders' rows from the cache. A folder delete would no longer find its hidden children
     * (`InboxViewModel.subfolderIdsOf`) — mail nobody can reach any more — the reader's parent path
     * would lose a segment (#109), and unticking the box would leave a drawer with holes in it.
     * The cache keeps everything the server listed; hiding is the SCREEN's job, and only the
     * screen's.
     *
     * Whole lines, by equality, screening EVERY write — never `contains`, which is blind to any
     * mutation that lengthens a line, and never a subset, which is blind to a new write appearing.
     */
    @Test fun `every folder-cache write keeps what the server listed, unfiltered`() {
        assertEquals(
            "a folder-cache write changed shape. Whatever the account's subscription setting says, " +
                "the rows written here are the ones the server LISTED — filtering, mapping or " +
                "dropping any of them at this layer deletes cached folders the drawer merely had " +
                "to stop drawing. Lines found:",
            listOf(
                "mailboxDao.replaceAll(credentials.id, load.mailboxes)",
                "mailboxDao.replaceAll(credentials.id, resolved.mailboxes.map { it.toEntity(credentials.id) })",
                // #1952 the first sync's early write of the same unfiltered list, before its messages
                "mailboxDao.replaceAll(credentials.id, mailboxes.map { it.toEntity(credentials.id) })",
                "mailboxDao.replaceAll(credentials.id, mailboxes.map { it.toEntity(credentials.id) })",
                "mailboxDao.replaceAll(credentials.id, load.mailboxes)",
                // THE one the switch drives: `refreshFolderList` → `refreshMailboxes(credentials, onlySubscribed)`.
                "mailboxDao.replaceAll(credentials.id, load.mailboxes)",
                "mailboxDao.replaceAll(credentials.id, client.getMailboxes(ctx.session, ctx.accountId, ctx.auth).map { it.toEntity(credentials.id) })",
                "mailboxDao.replaceAll(credentials.id, resolved.mailboxes.map { it.toEntity(credentials.id) })",
            ),
            codeLines("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
                .filter { it.contains("mailboxDao.replaceAll(") },
        )
    }

    /** [relative]'s code lines, trimmed, comments and blanks dropped — as [DecryptedSubjectCallSiteTest] does. */
    private fun codeLines(relative: String): List<String> = locate(relative).readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }

    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("core/data/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
