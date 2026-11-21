package app.sterna.ui.inbox

import app.sterna.core.data.mail.InboxRow
import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EXECUTES the guard's decision, call by call, the way a recomposition sequence would.
 */
class RowsGuardTest {

    private val inbox = ListKey("accA", "mb-inbox", unified = false, unreadView = false)
    private val archive = ListKey("accA", "mb-archive", unified = false, unreadView = false)

    /** The state the wiring starts from: nothing selected yet, guard down. */
    private fun settled(key: ListKey, presented: String) =
        RowsGuard(key, presented, sawLoading = false, stale = false)

    @Test
    fun `a selection change arms the guard on the rows that are still on screen`() {
        val before = settled(inbox, "20|accA|e1")
        val after = advanceRowsGuard(before, archive, "20|accA|e1", refreshLoading = false, gaveUp = false, foreign = false)

        assertTrue(
            "Switching folder must ARM the guard: the 20 rows Paging is still presenting belong " +
                "to the PREVIOUS folder, and drawing them under the new folder's header is the " +
                "defect this guard exists for.",
            after.stale,
        )
        assertEquals("the armed guard must adopt the NEW selection", archive, after.key)
        assertEquals("the baseline is the stale snapshot we must see move", "20|accA|e1", after.baseline)
    }

    @Test
    fun `new rows presented disarm the guard`() {
        var g = advanceRowsGuard(settled(inbox, "20|accA|e1"), archive, "20|accA|e1", refreshLoading = true, gaveUp = false, foreign = false)
        assertTrue("armed by the switch", g.stale)

        g = advanceRowsGuard(g, archive, "12|accA|e77", refreshLoading = false, gaveUp = false, foreign = false)
        assertFalse(
            "Paging presented something other than the snapshot that was on screen at the " +
                "switch, so the new folder's page IS drawn: the guard must come down.",
            g.stale,
        )
    }

    @Test
    fun `a full loading cycle disarms even when both folders present identical rows`() {
        // "All inboxes" then "Inbox" on a single account: same rows, same count, same first row,
        // so `presented` never changes and rule 3 can never fire. Only the loading cycle can.
        val unified = ListKey("accA", null, unified = true, unreadView = false)
        var g = advanceRowsGuard(settled(unified, "20|accA|e1"), inbox, "20|accA|e1", refreshLoading = false, gaveUp = false, foreign = false)
        assertTrue("armed by the switch", g.stale)

        g = advanceRowsGuard(g, inbox, "20|accA|e1", refreshLoading = true, gaveUp = false, foreign = false)
        assertTrue("a refresh is running: still armed", g.stale)
        assertTrue("the guard must remember it saw the refresh start", g.sawLoading)

        g = advanceRowsGuard(g, inbox, "20|accA|e1", refreshLoading = false, gaveUp = false, foreign = false)
        assertFalse(
            "A whole loading cycle ran since the switch with the rows unchanged: they are the new " +
                "selection's rows after all. Staying armed here is a permanent spinner over a " +
                "correct list.",
            g.stale,
        )
    }

    @Test
    fun `the give-up valve never fires while a load is still running`() {
        var g = advanceRowsGuard(settled(inbox, "20|accA|e1"), archive, "20|accA|e1", refreshLoading = true, gaveUp = false, foreign = false)
        assertTrue("armed by the switch", g.stale)

        g = advanceRowsGuard(g, archive, "20|accA|e1", refreshLoading = true, gaveUp = true, foreign = false)
        assertTrue(
            "The delay elapsed but a refresh is STILL running - the slow connection that was " +
                "reported. Handing the screen back now shows the previous folder's mail under " +
                "the new folder's header, which is exactly the defect.",
            g.stale,
        )
    }

    @Test
    fun `the give-up valve fires once nothing is loading`() {
        var g = advanceRowsGuard(settled(inbox, "20|accA|e1"), archive, "20|accA|e1", refreshLoading = false, gaveUp = false, foreign = false)
        assertTrue("armed by the switch", g.stale)

        g = advanceRowsGuard(g, archive, "20|accA|e1", refreshLoading = false, gaveUp = true, foreign = false)
        assertFalse(
            "Nothing is loading and nothing moved for the whole delay: rather than a perpetual " +
                "indicator, the rows get the screen back.",
            g.stale,
        )
    }

    @Test
    fun `disarming is sticky - a later refresh on the same selection never re-arms`() {
        var g = advanceRowsGuard(settled(inbox, "20|accA|e1"), archive, "20|accA|e1", refreshLoading = true, gaveUp = false, foreign = false)
        g = advanceRowsGuard(g, archive, "12|accA|e77", refreshLoading = false, gaveUp = false, foreign = false)
        assertFalse("disarmed by the new page", g.stale)

        // Pull-to-refresh, or mail arriving, on the SAME selection.
        g = advanceRowsGuard(g, archive, "12|accA|e77", refreshLoading = true, gaveUp = false, foreign = false)
        assertFalse(
            "A refresh on an unchanged selection must NOT re-arm the guard: the list would blank " +
                "out to a spinner on every pull-to-refresh and every delivery (#63, #23).",
            g.stale,
        )
        g = advanceRowsGuard(g, archive, "13|accA|e90", refreshLoading = false, gaveUp = false, foreign = false)
        assertFalse("and it stays down as the refreshed rows land", g.stale)
    }

    @Test
    fun `the sticky rule returns the previous state untouched`() {
        val disarmed = settled(archive, "12|accA|e77")
        val next = advanceRowsGuard(disarmed, archive, "99|accB|zz", refreshLoading = true, gaveUp = true, foreign = false)
        assertSame(
            "Once down for a selection, the guard is not recomputed from anything: same instance.",
            disarmed,
            next,
        )
    }

    @Test
    fun `the guard converges - a repeated recomposition with identical inputs is a fixed point`() {
        // It is written on every recomposition and read in the same pass, so a non-idempotent
        // step would recompose for ever.
        val armed = advanceRowsGuard(settled(inbox, "20|accA|e1"), archive, "20|accA|e1", refreshLoading = true, gaveUp = false, foreign = false)
        val again = advanceRowsGuard(armed, archive, "20|accA|e1", refreshLoading = true, gaveUp = false, foreign = false)
        assertEquals("armed state must be a fixed point", armed, again)
        val third = advanceRowsGuard(again, archive, "20|accA|e1", refreshLoading = true, gaveUp = false, foreign = false)
        assertEquals("and stay one", again, third)

        val idle = advanceRowsGuard(settled(inbox, "20|accA|e1"), archive, "20|accA|e1", refreshLoading = false, gaveUp = false, foreign = false)
        assertEquals(
            "armed-with-nothing-loading must be a fixed point too",
            idle,
            advanceRowsGuard(idle, archive, "20|accA|e1", refreshLoading = false, gaveUp = false, foreign = false),
        )
    }

    @Test
    fun `rowsSignature separates two accounts' first rows that share an id`() {
        // Servers number mailboxes and messages PER ACCOUNT, so id "e1" exists in both accounts.
        // A signature blind to the account would call an account switch "nothing moved".
        val a = rowsSignature(20, InboxRow(Email(id = "e1", accountId = "accA"), threadCount = 1, unread = false))
        val b = rowsSignature(20, InboxRow(Email(id = "e1", accountId = "accB"), threadCount = 1, unread = false))
        assertNotEquals("same count, same email id, different account: must differ", a, b)

        val c = rowsSignature(21, InboxRow(Email(id = "e1", accountId = "accA"), threadCount = 1, unread = false))
        assertNotEquals("a differing count must show", a, c)
        assertEquals("and it must be stable for the same inputs", a, rowsSignature(20, InboxRow(Email(id = "e1", accountId = "accA"), threadCount = 1, unread = false)))
        assertNotEquals("an empty list must not read like a populated one", a, rowsSignature(0, null))
    }

    @Test
    fun `the give-up valve is long enough to outlast the pager being built`() {
        // Nothing else in the suite reads this constant's VALUE — the wiring lint only pins its
        // name — so shortening it is a silent way to undo the whole guard.
        assertTrue(
            "⛔ ROWS_GUARD_GIVE_UP_MS must stay at or above ROWS_GUARD_GIVE_UP_FLOOR_MS. The valve " +
                "only ever fires while NOTHING is loading, and a new pager takes a moment to even " +
                "exist (a folder awaits a DataStore credential read before its Pager is built): " +
                "fired inside that moment it hands the screen straight back to the PREVIOUS " +
                "folder's rows, under the new folder's header, which is the defect. Value was " +
                "$ROWS_GUARD_GIVE_UP_MS",
            ROWS_GUARD_GIVE_UP_MS >= ROWS_GUARD_GIVE_UP_FLOOR_MS,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Belonging, not movement. The bench pass of 2026-08-29 (emulator, debug) still saw the

    /** A presented row, account-and-folder qualified the way the pager's SQL guarantees. */
    private fun row(accountId: String, mailboxId: String, id: String = "e1") =
        InboxRow(Email(id = id, accountId = accountId, mailboxId = mailboxId), threadCount = 1, unread = false)

    /**
     * One recomposition, wired like `InboxScreen`: the SAME presented snapshot feeds the signature
     */
    private fun step(
        prev: RowsGuard,
        key: ListKey,
        count: Int,
        rows: List<InboxRow>,
        refreshLoading: Boolean,
        gaveUp: Boolean,
    ) = advanceRowsGuard(
        prev,
        key,
        rowsSignature(count, rows.firstOrNull()),
        refreshLoading,
        gaveUp,
        rowsForeign(key, rows.firstOrNull(), rows.lastOrNull()),
    )

    @Test
    fun `the previous folder's rows coming back re-arm a guard an empty snapshot had disarmed`() {
        // The bench sequence, replayed: blank for 0.6-2.3 s, THEN the new folder's header over
        // the OLD folder's rows, THEN the right rows. The blank is the guard doing its job; the
        // second frame is the defect, and it is reached through the guard's own disarming.
        val rowsOfA = row("accA", "mb-inbox")
        var g = step(settled(inbox, rowsSignature(20, rowsOfA)), archive, 20, listOf(rowsOfA), refreshLoading = true, gaveUp = false)
        assertTrue("armed by the switch", g.stale)

        // Paging invalidates: for a beat the OLD generation presents nothing. That is "something
        // other than the baseline", so rule 4 disarms — correctly, on the evidence it has.
        g = step(g, archive, 0, emptyList(), refreshLoading = false, gaveUp = false)
        assertFalse("the empty snapshot disarms: nothing presented proves nothing", g.stale)

        // …and the old folder's rows come BACK, because the new PagingData has still not inserted
        // its first page. THIS is the frame the user photographs.
        g = step(g, archive, 20, listOf(rowsOfA), refreshLoading = false, gaveUp = false)
        assertTrue(
            "⛔ The rows on screen belong to accA/mb-inbox while the header says accA/mb-archive: " +
                "the screen is showing one folder's mail under another folder's name. Disarming " +
                "must not be sticky against PROVEN foreign rows - the guard has to come back up.",
            g.stale,
        )
    }

    @Test
    fun `a finished loading cycle does not disarm while the rows are still the old folder's`() {
        val rowsOfA = row("accA", "mb-inbox")
        var g = step(settled(inbox, rowsSignature(20, rowsOfA)), archive, 20, listOf(rowsOfA), refreshLoading = true, gaveUp = false)
        assertTrue("armed by the switch, with a refresh already running", g.stale)

        // The refresh that ends here is the OLD generation's: `refreshLoading` is the load state of
        // the PagingData being PRESENTED, so a whole cycle can run without the new one existing.
        g = step(g, archive, 20, listOf(rowsOfA), refreshLoading = false, gaveUp = false)
        assertTrue(
            "⛔ A loading cycle that belongs to the PREVIOUS selection must not hand the screen " +
                "to the previous selection's rows: accA/mb-inbox rows under the mb-archive header.",
            g.stale,
        )
    }

    @Test
    fun `the give-up valve does not disarm while the rows are still the old folder's`() {
        val rowsOfA = row("accA", "mb-inbox")
        var g = step(settled(inbox, rowsSignature(20, rowsOfA)), archive, 20, listOf(rowsOfA), refreshLoading = false, gaveUp = false)
        assertTrue("armed by the switch", g.stale)

        g = step(g, archive, 20, listOf(rowsOfA), refreshLoading = false, gaveUp = true)
        assertTrue(
            "⛔ The valve exists for the case we cannot decide - two selections whose rows read " +
                "alike. Here we CAN decide: these rows are accA/mb-inbox's. Spending the valve on " +
                "them draws the old folder's mail under the new folder's header, on purpose.",
            g.stale,
        )
    }

    @Test
    fun `pull-to-refresh on an unchanged selection still never re-arms`() {
        // The belonging rule sits in FRONT of the sticky rule, so this is the thing it could break:
        // #63 and bench gesture G4, both green. The rows belong to the selection, so it must not
        // even be consulted.
        val rowsOfA = row("accA", "mb-inbox")
        val rowsOfB = row("accA", "mb-archive", "e77")
        var g = step(settled(inbox, rowsSignature(20, rowsOfA)), archive, 20, listOf(rowsOfA), refreshLoading = true, gaveUp = false)
        g = step(g, archive, 12, listOf(rowsOfB), refreshLoading = false, gaveUp = false)
        assertFalse("disarmed by the new folder's page landing", g.stale)

        g = step(g, archive, 12, listOf(rowsOfB), refreshLoading = true, gaveUp = false)
        assertFalse(
            "⛔ A pull-to-refresh on the folder already shown must not blank the list to a spinner " +
                "(#63, #23): its rows belong to the selection, so nothing is foreign.",
            g.stale,
        )
        g = step(g, archive, 13, listOf(row("accA", "mb-archive", "e90")), refreshLoading = false, gaveUp = false)
        assertFalse("and it stays down as the refreshed rows land", g.stale)
    }

    @Test
    fun `rowsForeign decides ownership only where a folder owns its rows`() {
        val mine = row("accA", "mb-inbox")
        val other = row("accB", "mb-inbox")
        val elsewhere = row("accA", "mb-archive")
        assertTrue(
            "another account's row under this folder's header is the account-switch defect " +
                "(1 try in 2 at the bench)",
            rowsForeign(inbox, other, other),
        )
        assertTrue(
            "another folder's row of the same account is the folder-switch defect",
            rowsForeign(inbox, elsewhere, elsewhere),
        )
        assertFalse("the folder's own rows are not foreign", rowsForeign(inbox, mine, mine))

        // The three undecidable shapes: answering `true` for any of them would hold a loading ring
        // over a perfectly correct list until the valve fired, on every recomposition.
        assertFalse(
            "\"All inboxes\" spans several accounts: no single owner, nothing to decide",
            rowsForeign(ListKey("accA", null, unified = true, unreadView = false), other, elsewhere),
        )
        assertFalse(
            "the unread view spans the account's folders: no single owner either",
            rowsForeign(ListKey("accA", null, unified = false, unreadView = true), elsewhere, other),
        )
        assertFalse("nothing presented decides nothing", rowsForeign(inbox, null, null))
        assertFalse(
            "an unresolved account decides nothing",
            rowsForeign(ListKey(null, "mb-inbox", unified = false, unreadView = false), mine, elsewhere),
        )
        assertFalse(
            "an unresolved folder id decides nothing",
            rowsForeign(ListKey("accA", null, unified = false, unreadView = false), mine, elsewhere),
        )
    }

    @Test
    fun `a folder that also lists messages filed elsewhere is not called foreign by their mailbox (1837 membership)`() {
        val label = ListKey("accA", "mb-receipts", unified = false, unreadView = false)
        val inInbox = row("accA", "mb-inbox", "e1") // a message of the Inbox that the label also holds
        val own = row("accA", "mb-receipts", "e2")
        assertTrue("without the membership fact the mailbox says foreign", rowsForeign(label, inInbox, own))
        assertFalse("with it, the mailbox proves nothing", rowsForeign(label, inInbox, own, memberFolder = true))
        assertTrue(
            "another account's row is foreign whatever the folder lists",
            rowsForeign(label, row("accB", "mb-receipts", "e3"), own, memberFolder = true),
        )
    }

    @Test
    fun `rowsForeign asks both ends, because either end alone can be honest`() {
        val drafts = ListKey("accA", "mb-drafts", unified = false, unreadView = false)
        assertTrue(
            "⛔ A local draft prefixed onto the PREVIOUS folder's page is exactly what the Drafts " +
                "screen presents for a beat: the FIRST row honestly belongs to Drafts while the " +
                "inbox mail behind it does not. Reading the first row alone lets that draft vouch " +
                "for twenty foreign messages.",
            rowsForeign(drafts, row("accA", "mb-drafts", "local-1"), row("accA", "mb-inbox", "e20")),
        )
        assertTrue(
            "the ordinary switch, where the whole old page is still up: the first row already " +
                "proves it",
            rowsForeign(drafts, row("accA", "mb-inbox", "e1"), row("accA", "mb-drafts", "d9")),
        )
        assertFalse(
            "⛔ A correct list has BOTH ends inside the scope, and answering true here would hold " +
                "a loading ring over the folder the user is reading.",
            rowsForeign(drafts, row("accA", "mb-drafts", "d1"), row("accA", "mb-drafts", "d9")),
        )
        assertFalse(
            "a single presented row is its own first and last",
            rowsForeign(drafts, row("accA", "mb-drafts", "d1"), row("accA", "mb-drafts", "d1")),
        )
    }

    @Test
    fun `a local draft on top does not vouch for the inbox rows behind it`() {
        // THE DRAFTS WITNESS. pagedListRows is combine(pagedEmails, localDraftRows) and
        // withLocalDrafts PREFIXES rows built with the SELECTED folder's id. On Inbox → Drafts the
        // Room query behind localDraftRows lands before pagedEmails' DataStore read, so the
        // combine emits the new local rows over the OLD PagingData.
        val drafts = ListKey("accA", "mb-drafts", unified = false, unreadView = false)
        val inboxRows = List(20) { row("accA", "mb-inbox", "e$it") }

        var g = step(settled(inbox, rowsSignature(20, inboxRows.first())), drafts, 20, inboxRows, refreshLoading = false, gaveUp = false)
        assertTrue("armed by the switch to Drafts", g.stale)

        val draftOverInbox = listOf(row("accA", "mb-drafts", "local-1")) + inboxRows
        g = step(g, drafts, 21, draftOverInbox, refreshLoading = false, gaveUp = false)
        assertTrue(
            "⛔ What the user sees when this disarms: the \"Drafts\" header, her own unsent draft " +
                "on the first line, and UNDER IT the twenty messages of her inbox. The snapshot " +
                "did move (rule 4) and the first row does belong to Drafts, so the only thing that " +
                "can keep the guard up is the rest of the presented list still being the inbox's.",
            g.stale,
        )

        // …and when the real Drafts page finally lands, nothing is foreign any more.
        val realDrafts = listOf(row("accA", "mb-drafts", "local-1"), row("accA", "mb-drafts", "d2"))
        g = step(g, drafts, 2, realDrafts, refreshLoading = false, gaveUp = false)
        assertFalse("the folder's own page is on screen: the guard must come down", g.stale)
    }

    @Test
    fun `re-arming on foreign rows converges too`() {
        // Same requirement as the rest: written and read in one composition pass, so a step that
        // kept returning a different object would recompose for ever - with foreign rows on screen
        // that would be a permanent recomposition loop, the worst version of it.
        val disarmed = settled(archive, "12|accA|e77")
        val rearmed = advanceRowsGuard(disarmed, archive, "20|accA|e1", refreshLoading = false, gaveUp = true, foreign = true)
        assertTrue("foreign rows must re-arm even a disarmed, given-up guard", rearmed.stale)

        val again = advanceRowsGuard(rearmed, archive, "20|accA|e1", refreshLoading = false, gaveUp = true, foreign = true)
        assertEquals("re-armed state must be a fixed point", rearmed, again)
        val third = advanceRowsGuard(again, archive, "20|accA|e1", refreshLoading = false, gaveUp = true, foreign = true)
        assertEquals("and stay one", again, third)
    }
}
