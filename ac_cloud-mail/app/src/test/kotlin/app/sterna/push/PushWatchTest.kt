package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue A8: the per-account new-mail toggle and its status line must be honest about which accounts
 */
class PushWatchTest {

    @Test fun `the current account is watched`() {
        assertTrue(PushController.isWatched("a", currentId = "a", pushAllAccounts = false))
    }

    @Test fun `a non-current account is not watched with push-all off`() {
        assertFalse(PushController.isWatched("b", currentId = "a", pushAllAccounts = false))
    }

    @Test fun `a non-current account is watched once push-all is on`() {
        assertTrue(PushController.isWatched("b", currentId = "a", pushAllAccounts = true))
    }

    @Test fun `the current account stays watched with push-all on`() {
        assertTrue(PushController.isWatched("a", currentId = "a", pushAllAccounts = true))
    }

    @Test fun `no current account and push-all off watches nothing`() {
        assertFalse(PushController.isWatched("a", currentId = null, pushAllAccounts = false))
    }

    // --- the unwatched note promises ONE action, so it may only appear when one is enough ---------
    //
    // Same issue A8, one step further into the screen. The note under the toggle says "turn on Push
    // for all accounts" and nothing else, which is the whole truth only while this account's own
    //
    // Expected values are written out, never recomputed from the same expression as the function.

    /** The one state the note describes exactly: nothing watches this account, but it wants mail. */
    @Test fun `an unwatched account with notifications on is told what to turn on`() {
        assertTrue(
            PushController.shouldShowUnwatchedNote(
                isLinked = false, isWatched = false, notificationsEnabled = true,
            ),
        )
    }

    /** The defect: two actions needed, one promised — the note lies, so it stays away. */
    @Test fun `an unwatched account whose own toggle is off is not promised a one-step fix`() {
        assertFalse(
            PushController.shouldShowUnwatchedNote(
                isLinked = false, isWatched = false, notificationsEnabled = false,
            ),
        )
    }

    /** Witness: a watched account has nothing to turn on, whatever its own flag says. */
    @Test fun `a watched account never shows the unwatched note`() {
        assertFalse(
            PushController.shouldShowUnwatchedNote(
                isLinked = false, isWatched = true, notificationsEnabled = true,
            ),
        )
    }

    /** Witness: a linked sub-account is carried by its login (issue #31), not by push-all. */
    @Test fun `a linked sub-account never shows the unwatched note`() {
        assertTrue(
            "a linked account is watched through its login, so both watched states must stay silent",
            !PushController.shouldShowUnwatchedNote(
                isLinked = true, isWatched = false, notificationsEnabled = true,
            ) &&
                !PushController.shouldShowUnwatchedNote(
                    isLinked = true, isWatched = true, notificationsEnabled = true,
                ),
        )
    }

    /**
     * The parameter ORDER, which the named calls above are blind to: three arguments of the same
     */
    @Test fun `the unwatched note reads its arguments in the declared order`() {
        assertTrue(PushController.shouldShowUnwatchedNote(false, false, true))
        assertFalse(PushController.shouldShowUnwatchedNote(false, true, false))
        assertFalse(PushController.shouldShowUnwatchedNote(true, false, false))
    }

    /**
     * SOURCE RULE, and the weakest test of this section — same reason as the [PushService] ones far
     */
    @Test fun `the notifications section leaves the toggle live and asks the note for all three`() {
        assertEquals(
            listOf(
                "SettingSwitch(",
                "title = stringResource(R.string.settings_account_notifications_title),",
                "subtitle = stringResource(R.string.settings_account_notifications_subtitle),",
                "checked = notificationsEnabled,",
                "onCheckedChange = {",
                "notificationsEnabled = it",
                "viewModel.setNotificationsEnabled(accountId, it)",
                "},",
                ")",
                "if (PushController.shouldShowUnwatchedNote(account.isLinked, " +
                    "viewModel.isWatched(accountId), notificationsEnabled)) {",
                "Text(",
            ),
            notificationsSectionStatements(11),
        )
    }

    /**
     * The first [count] statements of the account screen's Notifications section, trimmed, with
     */
    private fun notificationsSectionStatements(count: Int): List<String> {
        val marker = "SettingsSection(stringResource(R.string.settings_account_notifications_section)) {"
        val lines = File(
            repoRoot,
            "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt",
        ).readText().lines()
        val hits = lines.withIndex().filter { it.value.trim() == marker }.map { it.index }
        assertEquals("expected exactly one notifications section in SettingsScreen, found $hits", 1, hits.size)
        return lines.drop(hits.single() + 1)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") }
            .take(count)
    }

    // --- issue #61: resolving an account to the connection that carries it -----------------------
    //
    // The service holds one connection per LOGIN and groups the accounts under it (issue #31), so
    // the open-connection map is keyed by login id while the status line asks with an account id.

    /** "sub" is a shared mailbox reached through login "login"; the login's socket is open. */
    private val grouped = mapOf("login" to "login", "sub" to "login")

    @Test fun `a standalone account resolves to its own connection`() {
        assertTrue(isCarriedByOpenConnection("a", carriedBy = emptyMap(), openConnections = setOf("a")))
    }

    @Test fun `a standalone account with no connection open is not carried`() {
        assertFalse(isCarriedByOpenConnection("a", carriedBy = emptyMap(), openConnections = setOf("b")))
    }

    @Test fun `the login of a group resolves to its connection`() {
        assertTrue(isCarriedByOpenConnection("login", grouped, openConnections = setOf("login")))
    }

    @Test fun `a grouped sub-account resolves to its login's connection`() {
        assertTrue(isCarriedByOpenConnection("sub", grouped, openConnections = setOf("login")))
    }

    @Test fun `a grouped sub-account is not carried while its login's connection is down`() {
        assertFalse(isCarriedByOpenConnection("sub", grouped, openConnections = emptySet()))
    }

    /** The sub-account's own id must never be what is matched: it is not a connection key. */
    @Test fun `a grouped sub-account is not carried by a connection under its own id`() {
        assertFalse(isCarriedByOpenConnection("sub", grouped, openConnections = setOf("sub")))
    }

    @Test fun `an account the service does not watch is not carried`() {
        assertFalse(isCarriedByOpenConnection("other", grouped, openConnections = setOf("login")))
    }

    // --- the fallback poll asks per account, not "is the service up" -----------------------------
    //
    // Same account-vs-connection confusion as above, on the other side of the app: the 30-minute
    // safety poll (issue #11) used to read the service's process-wide isRunning flag. The service
    //
    // What these tests pin is the RULE, not the wiring. The rule did not exist before the fix, so
    // saying they "fail on the old tree" would be meaningless; what they do is fix the decision so
    // that reverting it to the old one — poll only when no service is up — turns the first test red.
    // The worker's call site is not covered here (see the class KDoc).

    @Test fun `a service with no open connection for this account still polls its inbox`() {
        assertTrue(shouldPollInbox(pushConnected = false, linked = false))
    }

    /** The witness: with the connection genuinely open, the poll must stay out of the inbox. */
    @Test fun `an open connection for this account keeps the poll out of its inbox`() {
        assertFalse(shouldPollInbox(pushConnected = true, linked = false))
    }

    /** Issue #31: the server never puts a shared sub-account's changes on the login's socket. */
    @Test fun `a linked sub-account is polled even under an open connection`() {
        assertTrue(shouldPollInbox(pushConnected = true, linked = true))
    }

    @Test fun `IMAP watched extras are polled while IDLE holds the inbox`() {
        assertTrue(hasExtrasToPoll(isImap = true, watchedFolders = setOf("f1")))
    }

    @Test fun `IMAP with no watched extra has nothing left to poll`() {
        assertFalse(hasExtrasToPoll(isImap = true, watchedFolders = emptySet()))
    }

    /** A JMAP EventSource covers the watched extras itself — polling them again is waste. */
    @Test fun `JMAP watched extras are left to the EventSource`() {
        assertFalse(hasExtrasToPoll(isImap = false, watchedFolders = setOf("f1")))
    }

    // --- the silent reseed is per account, not per arm --------------------------------------------
    //
    // A user-initiated arm (app open, account switch, a settings toggle, ticking one watched folder)
    // may swallow an inbox's backlog into the baseline instead of announcing it, because the user is
    //
    // The witnesses matter as much as the case: without them these tests would also pass with the
    // reseed deleted outright, which is the opposite defect (a fresh install or a baseline version
    // bump would empty weeks of mail into the notification shade).

    @Test fun `an account the user is not looking at is not reseeded`() {
        assertFalse(
            shouldResetBaseline("b", userInitiated = true, currentAccountId = "a", unifiedInbox = false),
        )
    }

    /** Witness: the account on screen is exactly what the silent reseed is for. */
    @Test fun `the account on screen is reseeded`() {
        assertTrue(
            shouldResetBaseline("a", userInitiated = true, currentAccountId = "a", unifiedInbox = false),
        )
    }

    /** Witness: in the unified inbox every account's mail IS on screen, so every one is reseeded. */
    @Test fun `the unified inbox reseeds every account`() {
        assertTrue(
            shouldResetBaseline("b", userInitiated = true, currentAccountId = "a", unifiedInbox = true),
        )
    }

    /** Witness: a background arm never reseeds — not the current account, not in the unified view. */
    @Test fun `a background arm never reseeds the account on screen`() {
        assertFalse(
            shouldResetBaseline("a", userInitiated = false, currentAccountId = "a", unifiedInbox = false),
        )
    }

    @Test fun `a background arm never reseeds in the unified inbox either`() {
        assertFalse(
            shouldResetBaseline("a", userInitiated = false, currentAccountId = "a", unifiedInbox = true),
        )
    }

    /** With no account current (first run, every account signed out) there is no inbox on screen. */
    @Test fun `no current account reseeds nothing`() {
        assertFalse(
            shouldResetBaseline("a", userInitiated = true, currentAccountId = null, unifiedInbox = false),
        )
    }

    /**
     * The witness that keeps the narrowing from turning into a deletion: a folder with no baseline
     */
    @Test fun `a folder with no baseline seeds silently anyway`() {
        assertTrue(seedsSilently(resetBaselines = false, isInbox = true, hasBaseline = false))
        assertTrue(seedsSilently(resetBaselines = false, isInbox = false, hasBaseline = false))
    }

    @Test fun `an inbox with a baseline and no reseed diffs`() {
        assertFalse(seedsSilently(resetBaselines = false, isInbox = true, hasBaseline = true))
    }

    /** Watched extras always diff: a Sieve folder is not on screen at app-open (issue #16). */
    @Test fun `a watched extra is never swallowed by a reseed`() {
        assertFalse(seedsSilently(resetBaselines = true, isInbox = false, hasBaseline = true))
    }

    @Test fun `a reseeded inbox with a baseline seeds silently`() {
        assertTrue(seedsSilently(resetBaselines = true, isInbox = true, hasBaseline = true))
    }

    // --- the arm's log line says what it GOT, not only what it asked for -------------------------
    //
    // The line the service writes at the end of an arm is read as evidence when a "push is dead"
    // report comes in. It used to count the login GROUPS it intended to connect, so it announced

    @Test fun `the arm line carries accounts, requested logins and held connections in their places`() {
        assertEquals(
            "Push armed for 1 account(s): 1 login connection(s) requested, 1 held",
            PushController.armSummary(accounts = 1, logins = 1, held = 1),
        )
    }

    /**
     * The case the whole change is for: one login was requested and the arm came away with nothing.
     * The sentence must be unreadable as "1 connection".
     */
    @Test fun `an arm that came away with nothing says so and cannot be read as one connection`() {
        val line = PushController.armSummary(accounts = 1, logins = 1, held = 0)
        assertEquals(
            "Push armed for 1 account(s): 1 login connection(s) requested, 0 held",
            line,
        )
        assertFalse(line, line.contains("1 held"))
    }

    /** Two accounts riding one login: the account count and the connection count are distinct. */
    @Test fun `accounts and logins are not the same number`() {
        assertEquals(
            "Push armed for 2 account(s): 1 login connection(s) requested, 1 held",
            PushController.armSummary(accounts = 2, logins = 1, held = 1),
        )
    }

    /**
     * The guard against a silent inversion in the wording: swapping "requested" and "held" must
     */
    @Test fun `requested and held are not interchangeable`() {
        val oneRequestedNoneHeld = PushController.armSummary(accounts = 1, logins = 1, held = 0)
        val noneRequestedOneHeld = PushController.armSummary(accounts = 1, logins = 0, held = 1)
        assertEquals(
            "Push armed for 1 account(s): 1 login connection(s) requested, 0 held",
            oneRequestedNoneHeld,
        )
        assertEquals(
            "Push armed for 1 account(s): 0 login connection(s) requested, 1 held",
            noneRequestedOneHeld,
        )
        assertNotEquals(oneRequestedNoneHeld, noneRequestedOneHeld)
    }

    /**
     * The parameter ORDER, which every test above is blind to: they all pass named arguments, so
     */
    @Test fun `the arm line reads its arguments in the declared order`() {
        assertEquals(
            "Push armed for 3 account(s): 2 login connection(s) requested, 1 held",
            PushController.armSummary(3, 2, 1),
        )
    }

    /**
     * SOURCE RULE, and the weakest test here: the three arguments the service hands to
     */
    @Test fun `the service logs the arm summary with accounts, groups and open connections`() {
        val source = File(
            repoRoot,
            "app/src/main/kotlin/app/sterna/push/PushService.kt",
        ).readText()
        val call = source.lines().map { it.trim() }.filter { it.contains("armSummary") }
        assertEquals("expected exactly one armSummary call site in PushService: $call", 1, call.size)
        assertEquals(
            "Log.i(TAG, PushController.armSummary(accounts.size, groups.size, connections.size))",
            call.single(),
        )
    }

    // --- an arm that gives up says so, instead of dying without a word --------------------------
    //
    // Three places in the service could drop an armament on the floor in silence: the generation
    // guard at the top of watch(), the same guard inside the delayed reconnect, and an arm that
    //
    // Not claimed to be exhaustive, and one nearby path is deliberately left silent: the generation
    // check inside onClosed, which fires on every arm (reconnectAll closes every socket first) and
    // would print a line per healthy arm for nothing.
    //
    // The expected sentences are written out literally: rebuilding them with the same expression as
    // the functions under test would make this file a copy of the code. Both generations are
    // DIFFERENT numbers on purpose — with gen == current, dropping either one of them from the
    // sentence would still print the right text and nothing here would notice.

    @Test fun `the abandoned arm line names the login and both generations`() {
        assertEquals(
            "Stale push arm abandoned for login acc-1 (generation 3, service is at 5)",
            PushController.abandonedArmLine("acc-1", gen = 3, current = 5),
        )
    }

    @Test fun `the abandoned reconnect line names the login and both generations`() {
        assertEquals(
            "Stale push reconnect abandoned for login acc-1 (generation 3, service is at 5)",
            PushController.abandonedReconnectLine("acc-1", gen = 3, current = 5),
        )
    }

    /**
     * The two renouncements are different events — one never opened anything, the other gave up a
     */
    @Test fun `the two abandonments do not print the same sentence`() {
        assertNotEquals(
            PushController.abandonedArmLine("acc-1", gen = 3, current = 5),
            PushController.abandonedReconnectLine("acc-1", gen = 3, current = 5),
        )
    }

    /**
     * SOURCE RULE, same weakness and same reason as the armSummary one above: [PushService] cannot
     */
    @Test fun `the service logs the arm it abandons at the generation guard`() {
        assertEquals(
            "expected exactly one abandonedArmLine occurrence in PushService",
            1,
            pushServiceOccurrencesOf("abandonedArmLine"),
        )
        assertEquals(
            listOf(
                "val current = generation",
                "if (gen != current) { Log.w(TAG, PushController.abandonedArmLine(loginId, gen, current)); return }",
            ),
            pushServiceStatementsOf(
                "private suspend fun watch(loginId: String, group: List<AccountCredentials>, gen: Int, userInitiated: Boolean) {",
            ).take(2),
        )
    }

    /**
     * Same rule for the delayed retry, and here the WHOLE body is pinned: the retry's guard has no
     */
    @Test fun `the service logs the reconnect it abandons`() {
        assertEquals(
            "expected exactly one abandonedReconnectLine occurrence in PushService",
            1,
            pushServiceOccurrencesOf("abandonedReconnectLine"),
        )
        assertEquals(
            listOf(
                "scope.launch {",
                "delay(ReconnectBackoff.delayMs(failures[loginId] ?: 0))",
                "val current = generation",
                "if (gen == current) {",
                "Log.i(TAG, \"Reconnecting push for login \$loginId\")",
                "runCatching { connections.remove(loginId)?.close() }",
                "watch(loginId, group, gen, userInitiated = false)",
                "} else {",
                "Log.w(TAG, PushController.abandonedReconnectLine(loginId, gen, current))",
                "}",
                "}",
            ),
            pushServiceStatementsOf(
                "private fun scheduleReconnect(loginId: String, group: List<AccountCredentials>, gen: Int) {",
            ),
        )
    }

    /**
     * The third way an arm ends with nothing — no connection, nothing rescheduled, and the service
     * gone — which used not to print even the summary line. Pinned the same way, body and all.
     */
    @Test fun `the service says when an arm finds nothing to watch`() {
        assertEquals(
            "expected exactly one nothingToWatchLine occurrence in PushService",
            1,
            pushServiceOccurrencesOf("nothingToWatchLine"),
        )
        val arm = pushServiceStatementsOf(
            "private suspend fun reconnectAll(gen: Int, userInitiated: Boolean) {",
        )
        assertEquals(
            listOf(
                "if (accounts.isEmpty()) {",
                "Log.i(TAG, PushController.nothingToWatchLine(watched.size))",
                "stopSelf()",
                "return",
                "}",
            ),
            arm.dropWhile { !it.startsWith("if (accounts.isEmpty())") }.take(5),
        )
    }

    @Test fun `the empty arm line separates an opted-out set from credentials that never came`() {
        assertEquals(
            "Push arm found nothing to watch: 0 of 2 account(s) need a direct connection, stopping",
            PushController.nothingToWatchLine(candidates = 2),
        )
        assertEquals(
            "Push arm found nothing to watch: 0 of 0 account(s) need a direct connection, stopping",
            PushController.nothingToWatchLine(candidates = 0),
        )
    }

    // --- issue #130: a stale arm must not overwrite the live handle, and a stale close must
    // --- still retract its own entry ---------------------------------------------------------
    //
    // The arm's generation guard is read ONCE, at the top of watch(), and what follows it is a full
    // baseline pass over the group — tens of seconds on a real mailbox. A concurrent onStartCommand
    //
    // The retraction is the part with a trap in it, which is why it is a pure function executed here
    // rather than read. A close callback is ASYNCHRONOUS: close() returns at once and the callback

    /** Stands in for a connection handle. A data class on purpose: see the identity test below. */
    private data class Handle(val name: String)

    /** The trap, executed: a late close callback must not expel the generation that replaced it. */
    @Test fun `another arm's handle under the same login survives a stale retraction`() {
        val live = Handle("live")
        val connections = mutableMapOf("login" to live)
        val retracted = retractOwnConnection(connections, "login", mine = Handle("stale"))
        assertFalse("a stale handle must retract nothing, it reported: $retracted", retracted)
        assertEquals(mapOf("login" to live), connections)
    }

    @Test fun `a handle retracts its own entry`() {
        val mine = Handle("mine")
        val connections = mutableMapOf("login" to mine)
        val retracted = retractOwnConnection(connections, "login", mine)
        assertTrue("the owning handle must retract its entry, it reported: $retracted", retracted)
        assertEquals(emptyMap<String, Handle>(), connections)
    }

    /** The arm gave up before storing anything: there is no entry of its own to take away. */
    @Test fun `a handle that was never stored retracts nothing`() {
        val live = Handle("live")
        val connections = mutableMapOf("login" to live)
        val retracted = retractOwnConnection(connections, "login", mine = null)
        assertFalse("an unstored handle must retract nothing, it reported: $retracted", retracted)
        assertEquals(mapOf("login" to live), connections)
    }

    /**
     * The same "nothing stored" case with an EMPTY slot, which is the common one in production: the
     */
    @Test fun `nothing stored and nothing under the key reports nothing removed`() {
        val connections = mutableMapOf<String, Handle>()
        val retracted = retractOwnConnection(connections, "login", mine = null)
        assertFalse("an empty slot cannot have been retracted, it reported: $retracted", retracted)
        assertEquals(emptyMap<String, Handle>(), connections)
    }

    /**
     * The pairing is by IDENTITY, not by equals: two handles that compare equal are still two
     */
    @Test fun `an equal but different handle does not retract the entry`() {
        val live = Handle("same")
        val connections = mutableMapOf("login" to live)
        val twin = Handle("same")
        assertEquals("the fixture is pointless unless the two handles compare equal", live, twin)
        val retracted = retractOwnConnection(connections, "login", mine = twin)
        assertFalse("an equal-but-different handle must retract nothing, it reported: $retracted", retracted)
        assertEquals(mapOf("login" to live), connections)
    }

    /** Connections are keyed by LOGIN (issues #31, #61) and a retraction touches exactly one key. */
    @Test fun `retracting one login leaves the other logins connected`() {
        val mine = Handle("mine")
        val other = Handle("other")
        val connections = mutableMapOf("login" to mine, "other-login" to other)
        assertTrue(retractOwnConnection(connections, "login", mine))
        assertEquals(mapOf("other-login" to other), connections)
    }

    @Test fun `retracting a login with no entry at all reports nothing removed`() {
        val connections = mutableMapOf("other-login" to Handle("other"))
        val retracted = retractOwnConnection(connections, "login", mine = Handle("mine"))
        assertFalse("nothing was there to retract, it reported: $retracted", retracted)
        assertEquals(1, connections.size)
    }

    // The ONE new line of this fix, pinned literally like the two abandonments above. Both
    // generations are DIFFERENT numbers on purpose: with gen == current, dropping either from the
    // sentence would still print the right text and nothing here would notice.
    //
    // There is deliberately no line in onClosed. That path fires on EVERY arm (reconnectAll closes
    // every socket before it re-arms), so a line there would print once per healthy arm while being
    // unable to tell the race apart from a routine re-arm — the silence the section above this file's
    // reconnect tests already argued for, and it stands.

    @Test fun `the dropped handle line names the login, both generations and the missing retry`() {
        assertEquals(
            "Stale push connection dropped for login acc-1 (generation 3, service is at 5), nothing rescheduled",
            PushController.retiredHandleDroppedLine("acc-1", gen = 3, current = 5),
        )
    }

    /**
     * Three renouncements now, and a logcat reader has only the sentence to tell them apart: nothing
     */
    @Test fun `none of the three abandonments print the same sentence`() {
        val lines = listOf(
            PushController.abandonedArmLine("acc-1", gen = 3, current = 5),
            PushController.abandonedReconnectLine("acc-1", gen = 3, current = 5),
            PushController.retiredHandleDroppedLine("acc-1", gen = 3, current = 5),
        )
        assertEquals("two of the abandonment lines print the same sentence: $lines", 3, lines.toSet().size)
    }

    /**
     * SOURCE RULE, same weakness and same reason as the ones above: [PushService] is an Android
     */
    @Test fun `the arm checks the generation after storing, and takes its handle back out`() {
        assertEquals(
            "expected exactly one retiredHandleDroppedLine occurrence in PushService",
            1,
            pushServiceOccurrencesOf("retiredHandleDroppedLine"),
        )
        assertEquals(
            "expected exactly one post-pass generation re-read in PushService",
            1,
            pushServiceOccurrencesOf("val after = generation"),
        )
        val watch = pushServiceStatementsOf(WATCH_SIGNATURE)
        assertEquals(
            listOf(
                "mine.set(handle)",
                "connections[loginId] = handle",
                "openedAt[loginId] = android.os.SystemClock.elapsedRealtime()",
                "val after = generation",
                "if (gen != after) {",
                "retractOwnConnection(connections, loginId, handle)",
                "runCatching { handle.close() }",
                "Log.w(TAG, PushController.retiredHandleDroppedLine(loginId, gen, after))",
                "}",
            ),
            watch.dropWhile { it != "mine.set(handle)" }.take(9),
        )
    }

    /**
     * The other half, same rule: onClosed retracts ITS OWN entry — the call is read whole, so a slip
     */
    @Test fun `a connection that dies retracts its own entry, whatever its generation`() {
        assertEquals(
            "expected three retractOwnConnection occurrences in PushService (declaration, close callback, arm)",
            3,
            pushServiceOccurrencesOf("retractOwnConnection"),
        )
        val watch = pushServiceStatementsOf(WATCH_SIGNATURE)
        assertEquals(
            listOf(
                "val mine = AtomicReference<Closeable?>(null)",
                "runCatching {",
            ),
            watch.dropWhile { !it.startsWith("val mine =") }.take(2),
        )
        assertEquals(
            listOf(
                "onClosed = {",
                "retractOwnConnection(connections, loginId, mine.get())",
                "val lived = openedAt.remove(loginId)?.let { android.os.SystemClock.elapsedRealtime() - it }",
                "failures[loginId] = ReconnectBackoff.next(failures[loginId] ?: 0, lived)",
                "if (gen == generation) {",
                "scheduleReconnect(loginId, group, gen)",
                "}",
                "},",
            ),
            watch.dropWhile { it != "onClosed = {" }.take(8),
        )
    }

    /**
     * SOURCE RULE — same weakness and same reason as the ones above, and it closes the WINDOW they
     */
    @Test fun `the arm reads the unified flag itself, and hands it to the baseline decision`() {
        val watch = pushServiceStatementsOf(WATCH_SIGNATURE)
        assertEquals(
            "watch() must read the current account and the unified view from their live sources, " +
                "as whole statements. Frozen to `val unified = false`, a cold start restored into " +
                "'All inboxes' (#179) reseeds only the current account and every other watched " +
                "account re-announces mail the reader is already looking at; frozen to `true`, a " +
                "user-initiated arm swallows every account's backlog. Both compile, and both leave " +
                "the executed shouldResetBaseline tests above green.",
            listOf(
                "val currentId = application.container.accountStore.currentId()",
                "val unified = PushController.unifiedInboxVisible",
            ),
            watch.dropWhile { !it.startsWith("val currentId =") }.take(2),
        )
        assertEquals(
            "the value read above must be what shouldResetBaseline is GIVEN, whole and per " +
                "account: `unified` and not a literal, `credentials.id` and not the group's owner. " +
                "Statements starting with `val reset =` were:\n" +
                watch.filter { it.startsWith("val reset =") }.joinToString("\n"),
            listOf("val reset = shouldResetBaseline(credentials.id, userInitiated, currentId, unified)"),
            watch.filter { it.startsWith("val reset =") },
        )
        assertEquals(
            "expected exactly one mention of unifiedInboxVisible in PushService — the arm's own " +
                "read. The flag mirrors the list's selection and it MOVES, so two reads are two " +
                "answers, taken at two moments.",
            1,
            pushServiceOccurrencesOf("unifiedInboxVisible"),
        )
    }

    /**
     * The statements of one function of [PushService], in order, trimmed, with comments and blank
     */
    private fun pushServiceStatementsOf(signature: String): List<String> {
        val lines = pushServiceSource().lines()
        val start = lines.indexOfFirst { it.trim() == signature }
        assertTrue("signature not found in PushService: $signature", start >= 0)
        val body = lines.drop(start + 1).takeWhile { it != "    }" }
        assertTrue("function $signature is not closed at member indentation", body.size < lines.size - start - 1)
        return body.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") }
    }

    private fun pushServiceOccurrencesOf(token: String): Int =
        pushServiceSource().split(token).size - 1

    private fun pushServiceSource(): String =
        File(repoRoot, "app/src/main/kotlin/app/sterna/push/PushService.kt").readText()

    private companion object {
        const val WATCH_SIGNATURE =
            "private suspend fun watch(loginId: String, group: List<AccountCredentials>, gen: Int, userInitiated: Boolean) {"

        val repoRoot: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error("cannot locate the checkout from ${File("").absolutePath}")
        }
    }
}
