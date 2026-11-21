package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. It reads `InboxScreen.kt` as text and proves nothing about
 */
class RowsGuardWiringLintTest {

    @Test
    fun `the rows branch is gated on the guard and the indicator covers it`() {
        assertEquals(
            "⛔ The rows branch of InboxScreen's list `when` must be gated on !staleRows. Ungated, " +
                "LazyPagingItems keeps presenting the PREVIOUS selection's snapshot until the new " +
                "PagingData inserts its first page: itemCount is the old folder's, this branch " +
                "wins, and the screen draws ANOTHER FOLDER'S ROWS under the new folder's header. " +
                "The rows-branch lines found were:",
            listOf("listRows.itemCount > 0 && !staleRows ->"),
            codeLines(INBOX_SCREEN).map { it.trim() }.filter { it.startsWith("listRows.itemCount > 0") },
        )
        assertEquals(
            "⛔ The centred indicator must also cover the armed guard. On a switch that runs no " +
                "refresh of its own, gating the rows branch alone falls through to the offline, " +
                "error and empty scenes below, and the screen claims the folder holds nothing " +
                "while its first page is still loading. (On a FRESH switch nobody refreshes — " +
                "refreshUnlessFresh() returns inside the window — so `ui.refreshing` is false and " +
                "its branch does not catch; this arm catches and draws nothing, leaving the centre " +
                "empty and the tern to speak — #63's decision.) The indicator lines found were:",
            // The arm CATCHES wider than it DRAWS, deliberately. It catches on refreshLoading ||
            // staleRows — that is what this rule is about — and falling through would hand the
            listOf("refreshLoading || staleRows -> if (ringShowing) LoadingRing(Modifier.align(Alignment.Center)) else Unit"),
            codeLines(INBOX_SCREEN).map { it.trim() }.filter { it.startsWith("refreshLoading") && "LoadingRing" in it },
        )
    }

    @Test
    fun `the guard is advanced exactly once, from the same state the pager keys on`() {
        assertEquals(
            "⛔ advanceRowsGuard must be called exactly once in InboxScreen, on the four fields " +
                "that are also PageKey members (PageKey.accountId and PageKey.sel), AND on the " +
                "computed `foreign`. A member that moved WITHOUT rebuilding the pager would arm " +
                "the guard with nothing left to disarm it: a perpetual spinner over a correct " +
                "list. A literal in the `foreign` slot instead hands the sixth argument a constant, " +
                "and the guard goes back to disarming on the OLD selection's own activity: the " +
                "new folder's header over the old folder's rows, on 1 folder switch in 4, 1 in 3 " +
                "offline and 1 account switch in 2 (bench, 2026-08-29). Calls found:",
            listOf(
                "guard.value = advanceRowsGuard(guard.value, listKey, presented, refreshLoading, gaveUp.value, foreign)",
            ),
            codeLines(INBOX_SCREEN).map { it.trim() }.filter { "advanceRowsGuard(" in it },
        )
        assertEquals(
            "⛔ The key must be built from ui.accountId (the ViewModel's currentAccountId, the very " +
                "flow feeding PageKey.accountId) and the three selection fields. Key lines found:",
            listOf("val listKey = ListKey(ui.accountId, ui.selectedMailboxId, ui.unified, ui.unreadView)"),
            codeLines(INBOX_SCREEN).map { it.trim() }.filter { it.startsWith("val listKey =") },
        )
    }

    @Test
    fun `the give-up valve is reset by the selection, which is what makes the guard converge`() {
        // Both lines are keyed on listKey. If either stopped being, `gaveUp` would still be true
        // from the PREVIOUS selection at the instant the next switch arms the guard, and
        // advanceRowsGuard would disarm on the very next recomposition: the guard would be dead
        // for every switch after the first, silently, with every other line here still passing.
        assertEquals(
            "⛔ The give-up flag and its timer must both be keyed on listKey. Lines found:",
            listOf(
                "val gaveUp = remember(listKey) { mutableStateOf(false) }",
                "LaunchedEffect(listKey) { delay(ROWS_GUARD_GIVE_UP_MS); gaveUp.value = true }",
            ),
            codeLines(INBOX_SCREEN).map { it.trim() }.filter { "gaveUp" in it && "advanceRowsGuard(" !in it },
        )
    }

    @Test
    fun `the guard's own state outlives the selection change, and the signature keeps its rows`() {
        assertEquals(
            "⛔ The guard's state must be remembered UNKEYED. `remember(listKey) { … }` is the " +
                "tidy-up anyone would write — the two lines above it are keyed on listKey — and it " +
                "is fatal: the state is rebuilt with stale = false at every selection change, rule " +
                "1 of advanceRowsGuard is never reached, staleRows is false for ever and the " +
                "original defect is back, whole, with every other line of this file still passing. " +
                "State lines found:",
            listOf("val guard = remember { mutableStateOf(RowsGuard(listKey, presented, refreshLoading, false)) }"),
            codeLines(INBOX_SCREEN).map { it.trim() }.filter { it.startsWith("val guard =") },
        )
        assertEquals(
            "⛔ The signature must be taken from the count AND the first row Paging is presenting. " +
                "Passing `null` for the row leaves rule 4 comparing counts alone: two folders whose " +
                "cached page holds as many rows read as 'nothing moved', and the previous folder's " +
                "mail stays under the new folder's header until the valve. RowsGuardTest runs " +
                "rowsSignature in isolation and can never see this — the arguments are pinned HERE. " +
                "Signature lines found:",
            listOf("val presented = rowsSignature(listRows.itemCount, firstRow)"),
            codeLines(INBOX_SCREEN).map { it.trim() }.filter { it.startsWith("val presented =") },
        )
    }

    @Test
    fun `the belonging test is fed the very rows the screen is about to draw`() {
        assertEquals(
            "⛔ The presented snapshot must be hoisted into ONE local, because the signature and " +
                "the belonging test have to describe the SAME rows. Read twice from " +
                "itemSnapshotList, a page inserted between the two reads makes the guard compare " +
                "one folder's row against another folder's ownership, and the screen it protects " +
                "is the one that draws the old folder's mail under the new folder's name. " +
                "Snapshot lines found:",
            listOf("val presentedRows = listRows.itemSnapshotList.items"),
            codeLines(INBOX_SCREEN).map { it.trim() }.filter { it.startsWith("val presentedRows =") },
        )
        assertEquals(
            "⛔ The first presented row must come from that one snapshot, not from a second read " +
                "of itemSnapshotList. First-row lines found:",
            listOf("val firstRow = presentedRows.firstOrNull()"),
            codeLines(INBOX_SCREEN).map { it.trim() }.filter { it.startsWith("val firstRow =") },
        )
        assertEquals(
            "⛔ Belonging must be computed by rowsForeign, from the list key and BOTH ENDS of that " +
                "same snapshot. Passing `null`, a literal, or the first row twice makes the guard " +
                "blind to WHOSE rows are on screen: it falls back on 'something moved / a load " +
                "ran / time passed', which the PREVIOUS selection satisfies all by itself, and the " +
                "three bench gestures of 2026-08-29 come back — the new folder's header over the " +
                "old folder's rows on 1 folder switch in 4, 1 in 3 offline, 1 account switch in 2. " +
                "Dropping the LAST row loses Drafts on its own: the phone's local draft is " +
                "prefixed onto the OLD PagingData, so the first row honestly belongs to Drafts " +
                "and vouches for the twenty inbox messages drawn under it. RowsGuardTest runs " +
                "rowsForeign in isolation and can never see this — the arguments are pinned HERE. " +
                "Belonging lines found:",
            listOf("val foreign = rowsForeign(listKey, firstRow, presentedRows.lastOrNull(), memberFolder)"),
            codeLines(INBOX_SCREEN).map { it.trim() }.filter { it.startsWith("val foreign =") },
        )
    }

    @Test
    fun `the branches spend the guard's own answer`() {
        assertEquals(
            "⛔ staleRows must be READ OFF THE GUARD. Any other right-hand side — a literal, a " +
                "leftover flag — hands both branches below a constant: `false` puts the rows " +
                "branch back in front, and the new folder's header comes up over the OLD folder's " +
                "mail (1 folder switch in 4, 1 in 3 offline, 1 account switch in 2 at the bench, " +
                "2026-08-29); `true` holds a loading ring over a correct list until the user " +
                "leaves the screen. Nothing else in this suite reads this line: the two branches " +
                "that spend it are the ones no JVM test here can drive. Answer lines found:",
            listOf("val staleRows = guard.value.stale"),
            codeLines(INBOX_SCREEN).map { it.trim() }.filter { it.startsWith("val staleRows =") },
        )
    }

    @Test
    fun `the guard's answer is read after the step that produces it, not before`() {
        val lines = codeLines(INBOX_SCREEN).map { it.trim() }
        val advance = lines.indexOfFirst { "advanceRowsGuard(" in it && it.startsWith("guard.value =") }
        val answer = lines.indexOfFirst { it.startsWith("val staleRows =") }
        assertTrue("both lines must still be there: advance=$advance answer=$answer", advance >= 0 && answer >= 0)
        assertTrue(
            "⛔ staleRows must be read AFTER guard.value is advanced. Hoisted above the step — the " +
                "tidy-up anyone would write, since both lines only mention `guard` — it is the " +
                "PREVIOUS frame's answer: at the very frame the switch arms the guard staleRows is " +
                "still false, the rows branch wins once more, and the old folder's mail is drawn " +
                "for one frame under the new folder's name. The two lines look identical either " +
                "way and every other check in this file still passes. Order was " +
                "advance=$advance answer=$answer",
            answer > advance,
        )
    }

    @Test
    fun `the indicator branch stays above the scenes that would claim the folder is empty`() {
        val lines = codeLines(INBOX_SCREEN).map { it.trim() }
        val indicator = lines.indexOf("refreshLoading || staleRows -> if (ringShowing) LoadingRing(Modifier.align(Alignment.Center)) else Unit")
        val offline = lines.indexOf("notice == RefreshNotice.OFFLINE -> PullableCenter {")
        val error = lines.indexOf("notice == RefreshNotice.ERROR -> PullableCenter {")
        assertTrue("the three branches must all still be there: $indicator, $offline, $error", indicator >= 0 && offline >= 0 && error >= 0)
        assertTrue(
            "⛔ The centred indicator must come BEFORE the offline and error scenes in the list " +
                "`when`. Moved below them, a folder switch made while the phone is offline draws " +
                "\"you are offline, I will sync when you are back\" under the new folder's header " +
                "while that folder's first page is loading from the cache — the lying screen the " +
                "guard exists to prevent, in the other direction. Order was " +
                "indicator=$indicator offline=$offline error=$error",
            indicator < offline && indicator < error,
        )
    }

    /** [file]'s non-blank lines, indentation kept, with every comment taken out — block comments
     *  tracked across lines, and a `//` only honoured outside a double-quoted string. */
    private fun codeLines(file: File): List<String> {
        val out = mutableListOf<String>()
        var inBlockComment = false
        for (raw in file.readLines()) {
            val code = StringBuilder()
            var inString = false
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    inBlockComment -> if (c == '*' && raw.getOrNull(i + 1) == '/') {
                        inBlockComment = false
                        i++
                    }
                    inString -> {
                        code.append(c)
                        when {
                            c == '\\' -> raw.getOrNull(i + 1)?.let { code.append(it); i++ }
                            c == '"' -> inString = false
                        }
                    }
                    c == '"' -> {
                        code.append(c)
                        inString = true
                    }
                    c == '/' && raw.getOrNull(i + 1) == '*' -> {
                        inBlockComment = true
                        i++
                    }
                    c == '/' && raw.getOrNull(i + 1) == '/' -> i = raw.length
                    else -> code.append(c)
                }
                i++
            }
            if (code.isNotBlank()) out += code.toString().trimEnd()
        }
        return out
    }

    private companion object {
        val INBOX_SCREEN: File by lazy { repoFile("app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt") }

        private fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, path) }
                .firstOrNull { it.isFile }
                ?: error("cannot find $path from ${File("").absolutePath}")
    }
}
