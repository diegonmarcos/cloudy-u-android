package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. It reads `InboxViewModel.kt`, `InboxScreen.kt`,
 */
class LocalDraftListWiringLintTest {

    @Test
    fun `the browse list's local drafts are inserted downstream of cachedIn, in its own property`() {
        assertEquals(
            "InboxViewModel.pagedListRows must be exactly a combine of the ALREADY-cachedIn " +
                "pagedEmails with the local rows, handed to withLocalDrafts. Compared whole and in " +
                "order: a stage inserted, removed or moved must redden this. Body was:\n" +
                member(INBOX_VIEW_MODEL, "pagedListRows").joinToString("\n"),
            listOf(
                "val pagedListRows: Flow<PagingData<InboxRow>> =",
                "combine(pagedEmails, localDraftRows) { paged, local -> withLocalDrafts(paged, local) }",
            ),
            member(INBOX_VIEW_MODEL, "pagedListRows"),
        )
    }

    @Test
    fun `nothing local reaches the pager the reader shares`() {
        val body = member(INBOX_VIEW_MODEL, "pagedEmails")
        assertTrue(
            "InboxViewModel.pagedEmails must still end on .cachedIn(viewModelScope). Body was:\n" +
                body.joinToString("\n"),
            body.last() == "}.cachedIn(viewModelScope)",
        )
        assertEquals(
            "⛔ InboxViewModel.pagedEmails is what MessageScreen pages: it must not name the local " +
                "drafts anywhere. An insertion here puts a local-draft id in the READER's pager, " +
                "where a sideways swipe opens a page that cannot load and a swipe-delete announces a " +
                "deletion IMAP never performed. Offending lines:",
            emptyList<String>(),
            body.filter { "ocalDraft" in it || "withLocalDrafts" in it || "insertHeaderItem" in it },
        )
    }

    @Test
    fun `the merge hides the replaced server row before it puts the local ones on top`() {
        // Held as text because `PagingData` cannot be read back without the `paging-testing`
        // artifact. What the pure tests DO execute is every decision this body calls
        assertEquals(
            "withLocalDrafts must be exactly these five lines, in this order. Body was:\n" +
                functionBody(LOCAL_DRAFT_ROWS, "internal fun withLocalDrafts(").joinToString("\n"),
            listOf(
                "var data = paged.filter { row -> !hidesServerRow(row, local.replacedServerIds) }",
                "for (row in headerInsertionOrder(local.rows)) {",
                "data = data.insertHeaderItem(terminalSeparatorType = TerminalSeparatorType.SOURCE_COMPLETE, item = row)",
                "}",
                "return data",
            ),
            functionBody(LOCAL_DRAFT_ROWS, "internal fun withLocalDrafts("),
        )
    }

    @Test
    fun `the local rows are scoped by account and by folder role, from the shared flows`() {
        assertEquals(
            "InboxViewModel.localDraftRows must hand localDraftRowsFlow these five inputs and no " +
                "others, each NAMED and each matched with its own parameter — the parameter name on " +
                "its own proves nothing, since two of them are Flow<String?>. The role map is " +
                "folderRoles (a StateFlow, already shared): re-collecting the `mailboxes` flow would " +
                "re-run its onEach side effects, which select a folder. ⛔ And the LAST line is " +
                "pinned whole for its own reason: WhileSubscribed(5_000) with NO " +
                "replayExpirationMillis. Adding `replayExpirationMillis = 0` drops the cached " +
                "value back to an empty LocalDraftRows() while nobody collects, and a selectAll() " +
                "landing in that window subtracts nothing — 'Select all' then 'Delete' trashes a " +
                "message that was never drawn. Body was:\n" +
                member(INBOX_VIEW_MODEL, "localDraftRows").joinToString("\n"),
            listOf(
                "private val localDraftRows: StateFlow<LocalDraftRows> =",
                "localDraftRowsFlow(",
                "currentAccountId = currentAccountId,",
                "selectedMailboxId = selection.map { (it as? Sel.Folder)?.id },",
                "folderRoles = folderRoles,",
                "unreadOnly = unreadOnly,",
                "localDrafts = { accountId -> repo.observeLocalDrafts(accountId) },",
                ").stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalDraftRows())",
            ),
            member(INBOX_VIEW_MODEL, "localDraftRows"),
        )
    }

    @Test
    fun `the list screen pages the merged property and the reader pages the bare one`() {
        assertEquals(
            "InboxScreen must collect the merged property, and it is the only screen that may:",
            listOf("val listRows = viewModel.keptListRows.collectAsLazyPagingItems()"),
            codeLines(INBOX_SCREEN).map { it.trim() }.filter { "collectAsLazyPagingItems()" in it },
        )
        // #1952: the screen pages the merged property THROUGH its cache, and that cache is the merged property and nothing else.
        assertEquals(
            "keptListRows must be exactly pagedListRows cached in the ViewModel's scope:",
            listOf("val keptListRows: Flow<PagingData<InboxRow>> = pagedListRows.cachedIn(viewModelScope)"),
            codeLines(INBOX_VIEW_MODEL).map { it.trim() }.filter { "keptListRows" in it },
        )
        assertEquals(
            "⛔ SternaApp must keep handing MessageScreen the BARE pagedEmails. Routing the reader " +
                "through pagedListRows is the mutation this whole file exists for:",
            listOf("val listSource = if (src == \"list\") inboxViewModel.pagedEmails else null"),
            codeLines(STERNA_APP).map { it.trim() }.filter { "inboxViewModel.paged" in it },
        )
    }

    @Test
    fun `every gesture site asks the one predicate`() {
        assertEquals(
            "A swipe on a local draft must be dropped before performSwipe's when: repo.delete / " +
                "archive / moveToMailbox are silent no-ops on IMAP over an id that carries no UID, " +
                "and none of them removes the local row. First line of performSwipe was:",
            "if (isLocalDraftRow(email.id)) return",
            functionBody(INBOX_SCREEN, "private fun performSwipe(").first(),
        )
        assertEquals(
            "The tap must OPEN a local draft in the composer, and the row's onClick must hand " +
                "isLocalDraftRow(email.id) to rowTapAction as its FIRST argument, on its FIRST " +
                "line: the priority of the local draft over the selection (which InboxViewModel " +
                "refuses for this row, so the tap would do nothing at all) now lives in " +
                "rowTapAction and is EXECUTED by RowTapActionTest — what only this can see is " +
                "that the screen asks that function, with that argument, and acts on all four of " +
                "its answers. Compared whole and in order. Body of the row's onClick was:\n" +
                functionBody(INBOX_SCREEN, "onClick = {", after = "val emailRow:").joinToString("\n"),
            listOf(
                "when (rowTapAction(isLocalDraftRow(email.id), selectionActive, expandable, fromSearch, rowRole)) {",
                "RowTap.EDIT_DRAFT -> onEditDraft(email.id, email.accountId)",
                "RowTap.SELECT_THREAD -> viewModel.toggleSelectThread(email)",
                "RowTap.SELECT_ROW -> viewModel.toggleSelect(email)",
                "RowTap.OPEN -> {",
                "viewModel.onEmailOpened(email.id)",
                "onOpenEmail(email.id, email.accountId, entryIndex, fromSearch)",
                "}",
                "}",
            ),
            functionBody(INBOX_SCREEN, "onClick = {", after = "val emailRow:"),
        )
        for (name in listOf("fun enterSelection(", "fun toggleSelect(")) {
            assertEquals(
                "InboxViewModel.$name must refuse a local draft on its first line: nothing a " +
                    "selection leads to (delete, archive, move, mark read) knows this table, and the " +
                    "row would be acted on in silence. First line was:",
                "if (isLocalDraftRow(email.id)) return",
                functionBody(INBOX_VIEW_MODEL, name).first(),
            )
        }
    }

    @Test
    fun `the swipe is refused where the gesture is armed, not after the row has flown off`() {
        // THE most expensive line of this branch. performSwipe's refusal is a belt, and it
        // arrives too late to be anything else: commitSwipe animates offsetX to the screen edge and
        assertEquals(
            "The row must arm its swipe through rowGesturesEnabled(email.id, selectionActive), " +
                "whole and once. '!selectionActive' alone is the shipped defect; dropping " +
                "selectionActive is #126. gesturesEnabled arguments found in the row renderer were:",
            listOf("gesturesEnabled = rowGesturesEnabled(email.id, selectionActive),"),
            member(INBOX_SCREEN, "emailRow").filter { it.startsWith("gesturesEnabled") },
        )
        // …and the block has to READ the flag it is keyed on. Passing `gesturesEnabled` is half
        // the guard: a pointerInput key only RESTARTS the block, it never stops it running, so a
        assertEquals(
            "The row's swipe block must refuse on its FIRST line, before the tracker and the " +
                "awaitPointerEventScope loop: a guard placed after the loop is armed is never " +
                "reached for the gesture already in flight. First line of the row's pointerInput " +
                "was:",
            "if (!gesturesEnabled) return@pointerInput",
            functionBody(INBOX_SCREEN, ROW_POINTER_INPUT).first(),
        )
    }

    @Test
    fun `a local draft row is drawn no star to tap, at all three links`() {
        // The star is a tap on EVERY row, TalkBack included, and it is not routed through
        // performSwipe: toggleFlag over a local id touches nothing on IMAP (the star never fills)
        val row = member(INBOX_SCREEN, "emailRow")
        val at = row.indexOfFirst { it.startsWith("onToggleFavourite") }
        assertTrue(
            "InboxScreen's row renderer no longer passes an onToggleFavourite at all. Row was:\n" +
                row.joinToString("\n"),
            at >= 0,
        )
        assertEquals(
            "The star must be handed over only when showsFavouriteStar(email.id) says so, and the " +
                "OTHER arm must stay `null` — 'onToggleFavourite = {' is the shipped defect, and " +
                "an else arm that hands back a lambda is the same defect written longer. The whole " +
                "expression found was:",
            listOf(
                "onToggleFavourite = if (showsFavouriteStar(email.id)) {",
                "{",
                "val favouriting = !email.isFlagged",
                "viewModel.toggleFlag(email)",
                "if (favouriting && !fromSearch && ui.sortOrder == SortOrder.FLAGGED_FIRST) {",
                "scope.launch { listState.animateScrollToItem(0) }",
                "}",
                "}",
                "} else null,",
            ),
            row.subList(at, minOf(at + 9, row.size)),
        )
        // Link 2 of 3 — where the `null` TRAVELS. The rule above pins only where it is born, and
        // that is half a guard: SwipeableEmailRow's own parameter is nullable with a null default,
        // so `onToggleFavourite = onToggleFavourite ?: {},` on the relay compiles, leaves every
        // other rule in this file green, and hands the star back to every local draft.
        assertEquals(
            "SwipeableEmailRow must relay 'onToggleFavourite = onToggleFavourite,' to " +
                "EmailListItem, whole and once — no elvis, no substitute lambda: the row that " +
                "decided there is no star is the caller, and this line is the only thing carrying " +
                "that decision across. Lines found in its body:",
            listOf("onToggleFavourite = onToggleFavourite,"),
            functionBody(INBOX_SCREEN, "private fun SwipeableEmailRow(")
                .filter { it.startsWith("onToggleFavourite") },
        )
        // Link 3 of 3 — where the `null` ACTS. Only this condition decides whether a star exists
        // at all; a live lambda arriving here draws a ☆ that TalkBack announces as a button and
        assertEquals(
            "EmailListItem must declare onToggleFavourite nullable and defaulting to null (the " +
                "search list draws this same row), gate the star on 'if (onToggleFavourite != " +
                "null) {', and wire that very value as the tap's onClick — these three lines, in " +
                "this order, and no others. Lines found:",
            listOf(
                "onToggleFavourite: (() -> Unit)? = null,",
                "onToggleFavourite = onToggleFavourite,",
                "if (onToggleFavourite != null) {",
                ".clickable(onClick = onToggleFavourite)",
            ),
            codeLines(EMAIL_LIST_ITEM).map { it.trim() }.filter { "onToggleFavourite" in it },
        )
        // ...and in Gmail Dark the same value is relayed, whole, to Gmail's row (the second line
        // above), which declares it with NO default and gates its star on the same condition.
        assertEquals(
            "GmailEmailRow must take onToggleFavourite nullable with no default, gate its star on " +
                "'if (onToggleFavourite != null) {' and hand that very value to the star. Lines found:",
            listOf(
                "onToggleFavourite: (() -> Unit)?,",
                "if (onToggleFavourite != null) {",
                "GmailStar(flagged = email.isFlagged, onToggle = onToggleFavourite, lineStyle = bodyStyle)",
            ),
            codeLines(GMAIL_ROW).map { it.trim() }.filter { "onToggleFavourite" in it },
        )
    }

    @Test
    fun `the upstream role map is shared without an expiring replay either`() {
        // The ban on `replayExpirationMillis = 0` is not the downstream line's alone. The same
        // setting on the UPSTREAM does the same damage through it: on the way back to the screen
        assertEquals(
            "InboxViewModel.folderRoles must stay scoped off accountsFlow and shared with " +
                "WhileSubscribed(5_000) and NO replayExpirationMillis. Compared whole and in " +
                "order. Body was:\n" + member(INBOX_VIEW_MODEL, "folderRoles").joinToString("\n"),
            listOf(
                "val folderRoles: StateFlow<Map<Pair<String, String>, String>> =",
                "folderRolesFlow(store.accountsFlow) { accountIds -> repo.observeFolderRoles(accountIds) }",
                ".stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())",
            ),
            member(INBOX_VIEW_MODEL, "folderRoles"),
        )
    }

    @Test
    fun `Select all cannot reach the server rows the list is hiding`() {
        // Our own hiding re-opens the destructive half of #126: selectableIds pages the `emails`
        // table, where a replaced server draft is still filed, so the counter says N+1 and "Delete"
        // sends to the Trash a message that was never on screen. Compared whole and in order.
        assertEquals(
            "InboxViewModel.selectAll must subtract the hidden ids from the folder keys it hands " +
                "selectAllKeys, and read them from localDraftRows at the tap. Body was:\n" +
                functionBody(INBOX_VIEW_MODEL, "fun selectAll(").joinToString("\n"),
            listOf(
                "_selectionActive.value = true",
                "val search = searchState.value",
                // The list's own unread filter, not the raw funnel: a scope that forces the filter
                // on (Sel.Unread) and a selection that reads the toggle is #126 rebuilt. One
                // source, executed in ListUnreadOnlyTest.
                "val filtered = listUnreadOnly(selection.value, unreadOnly.value)",
                "val hidden = localDraftRows.value.replacedServerIds",
                "viewModelScope.launch {",
                "_selectedKeys.value = selectAllKeys(",
                "searching = search.active,",
                "query = search.query,",
                "results = search.results?.map { it.emailKey() },",
                "loading = search.loading,",
                "complete = search.complete,",
                "folderKeys = selectableKeysMinusHidden(repo.selectableIds(currentScopes(), filtered, listView.value.shape()), hidden),",
                ")",
                "}",
            ),
            functionBody(INBOX_VIEW_MODEL, "fun selectAll("),
        )
    }

    @Test
    fun `the not-uploaded pill is pinned at all three links, arguments included`() {
        // The mutation this exists for is not a deleted call, it is a changed ARGUMENT:
        // `showNotUploadedBadge = false` at any one of the three links compiles, leaves every other
        assertEquals(
            "The row renderer must ask the SAME predicate the gesture sites ask, on the row's own " +
                "id: a row cannot be drawn as uploaded and treated as inert, or the other way " +
                "round. The argument found was:",
            listOf("showNotUploadedBadge = isLocalDraftRow(email.id),"),
            member(INBOX_SCREEN, "emailRow").filter { it.startsWith("showNotUploadedBadge") },
        )
        assertEquals(
            "SwipeableEmailRow must relay 'showNotUploadedBadge = showNotUploadedBadge,' to " +
                "EmailListItem, whole and once: the parameter DEFAULTS to false, so dropping or " +
                "constant-folding this line compiles and takes the pill off every row at once.",
            listOf("showNotUploadedBadge = showNotUploadedBadge,"),
            functionBody(INBOX_SCREEN, "private fun SwipeableEmailRow(")
                .filter { it.startsWith("showNotUploadedBadge") },
        )
        assertEquals(
            "EmailListItem must draw the pill on 'if (showNotUploadedBadge) {' — its parameter, " +
                "not a constant and not a second predicate of its own. Lines found:",
            listOf("if (showNotUploadedBadge) {"),
            codeLines(EMAIL_LIST_ITEM).map { it.trim() }.filter { "showNotUploadedBadge" in it && it.startsWith("if (") },
        )
        assertEquals(
            "EmailListItem's parameter must keep defaulting to false: no other list can answer " +
                "this question, and the search results render this same row.",
            listOf("showNotUploadedBadge: Boolean = false,"),
            codeLines(EMAIL_LIST_ITEM).map { it.trim() }.filter { it.startsWith("showNotUploadedBadge:") },
        )
    }

    // -- reading the sources --------------------------------------------------------------------

    /**
     * The whole body of the member `val` [name] in [file], one trimmed code line per entry, IN
     */
    private fun member(file: File, name: String): List<String> {
        val lines = codeLines(file)
        val start = lines.indexOfFirst { Regex("""\bval\s+$name\b""").containsMatchIn(it) }
        check(start >= 0) { "${file.name} declares no 'val $name' — did it get renamed, or not written yet?" }
        val indent = lines[start].indexOfFirst { !it.isWhitespace() }
        val out = mutableListOf(lines[start].trim())
        var i = start + 1
        while (i < lines.size) {
            val line = lines[i]
            val here = line.indexOfFirst { !it.isWhitespace() }
            if (here <= indent && !CONTINUATION.containsMatchIn(line)) break
            out += line.trim()
            i++
        }
        return out
    }

    /**
     * The body of the declaration whose line contains [opener] in [file] — the lines between the
     * brace it opens and the one that closes it, trimmed, in order, braces excluded.
     */
    private fun functionBody(file: File, opener: String, after: String? = null): List<String> {
        val lines = codeLines(file)
        val from = after?.let { anchor ->
            lines.indexOfFirst { anchor in it }.also {
                check(it >= 0) { "${file.name} has no line containing '$anchor'" }
            }
        } ?: 0
        val start = lines.drop(from).indexOfFirst { opener in it }.let { if (it < 0) -1 else it + from }
        check(start >= 0) { "${file.name} has no line containing '$opener' — renamed, or not written yet?" }
        var depth = 0
        var seen = false
        val out = mutableListOf<String>()
        for (i in start until lines.size) {
            val line = lines[i]
            val next = depth + line.count { it == '{' } - line.count { it == '}' }
            if (seen) {
                // The line that brings the brace count back to zero is the closing one: it ends the
                // body and is not part of it. A body line that opens AND closes its own braces (a
                // one-line lambda) leaves the count where it was and is kept.
                if (next <= 0) return out
                line.trim().takeIf { it.isNotEmpty() }?.let { out += it }
            }
            depth = next
            if (depth > 0) seen = true
        }
        return out
    }

    /** [file]'s non-blank lines, indentation kept, with every comment taken out — block comments
     *  tracked across lines, and a `//` only honoured outside a double-quoted string, so a
     *  continuation written operator-first is never mistaken for a comment. */
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
        /** A line that carries on the previous one because it opens with an operator. */
        val CONTINUATION = Regex("""^\s*(\.|\?:|\?\.|\+|&&|\|\||,|\))""")

        /** The row's swipe block, named by its WHOLE opening line. `.pointerInput(` on its own
         *  would find the account-chip drag in the drawer first, which [functionBody] would then
         *  read instead — it takes the first match. */
        const val ROW_POINTER_INPUT =
            ".pointerInput(gesturesEnabled, rightAction, leftAction, drawerBandPx, drawerCanOpen) {"

        val INBOX_VIEW_MODEL: File by lazy { repoFile("app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt") }
        val INBOX_SCREEN: File by lazy { repoFile("app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt") }
        val STERNA_APP: File by lazy { repoFile("app/src/main/kotlin/app/sterna/ui/SternaApp.kt") }
        val LOCAL_DRAFT_ROWS: File by lazy { repoFile("app/src/main/kotlin/app/sterna/ui/inbox/LocalDraftRows.kt") }
        val EMAIL_LIST_ITEM: File by lazy { repoFile("app/src/main/kotlin/app/sterna/ui/components/EmailListItem.kt") }
        val GMAIL_ROW: File by lazy { repoFile("app/src/main/kotlin/app/sterna/ui/components/GmailEmailRow.kt") }

        private fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, path) }
                .firstOrNull { it.isFile }
                ?: error("cannot find $path from ${File("").absolutePath}")
    }
}
