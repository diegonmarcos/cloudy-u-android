package app.sterna.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads `ComposeScreen.kt` as text, same instrument and
 */
class ComposeBarWiringTest {

    // -- 1. the title -----------------------------------------------------------------------------

    @Test fun `the title ellipses on one line instead of being clipped mid-word`() {
        assertEquals(
            "the TopAppBar's title slot must be exactly these lines. `maxLines = 1` and " +
                "`overflow = TextOverflow.Ellipsis` are pinned TOGETHER on purpose: with neither, " +
                "the title wraps and is cut mid-word on a narrow screen at a large font scale " +
                "(the reported \"Dra\"/\"ft\"); with `maxLines` alone the tail is chopped with no " +
                "ellipsis, so nothing on screen says a word was cut. The `when` is pinned with " +
                "them because this is the one place each ComposeTitle answer is turned into a " +
                "string — [ComposeTitleTest] runs the decision, not this mapping.",
            TITLE_SLOT,
            titleSlot(),
        )
    }

    // -- 2. what left the bar ---------------------------------------------------------------------

    @Test fun `the bar no longer carries the save, schedule and delete icons`() {
        val found = actionsBlock().filter { line -> GONE.any { it in line } }
        assertEquals(
            "these three icons went down into the ⋮ overflow and must not come back up: each one " +
                "is another 48 dp slot taken from the title, which is what clipped it mid-word — " +
                "and put back beside its menu entry, the same gesture is offered twice. " +
                "Found back in the bar:\n" + found.joinToString("\n"),
            emptyList<String>(),
            found,
        )
    }

    /**
     * THE SAME RULE FROM THE OTHER END, and the reason it is worth a second test: the one above
     */
    @Test fun `the three icons are not even imported any more`() {
        val back = COMPOSE_SCREEN.readLines().map { it.trim() }.filter { it in GONE_IMPORTS }
        assertEquals(
            "an import is back for an icon that left this bar with #164. Nothing else in " +
                "ComposeScreen.kt draws Save, Delete or Schedule: the import returning means a " +
                "button did too, and the title lost its room again. Found:\n" +
                back.joinToString("\n"),
            emptyList<String>(),
            back,
        )
    }

    // -- 3. what the overflow now offers, and under which conditions -------------------------------

    @Test fun `the overflow offers the four entries in the order it draws them`() {
        assertEquals(
            "the ⋮ menu must carry exactly these four entries, in this order: save, schedule, " +
                "delete, then the read receipt. The three that came down from the bar sit ABOVE " +
                "the receipt — the receipt is a tick box one reads, the other three are actions, " +
                "and an action that lands under a checkbox reads as part of it. A missing entry " +
                "here is a gesture the composer no longer offers at all, the bar having lost its " +
                "icon.",
            ENTRIES,
            overflowMenu().filter { it.startsWith("text = {") },
        )
    }

    @Test fun `each entry kept the condition its icon had`() {
        assertEquals(
            "the ⋮ menu must gate its entries on exactly these two conditions, in this order and " +
                "whole. ⛔ The delete is a SECOND, separate block, outside `draftSaveAllowed`: " +
                "deleting persists nothing, so it has no reason to disappear when the padlock " +
                "closes — folded into the save block it vanishes on an encrypted message, taking " +
                "the only way out of a phone-kept draft with it (#95). And `draftDeleteOffered` " +
                "must keep its four live arguments; [DraftDeleteOfferedTest] runs that decision.",
            CONDITIONS,
            overflowMenu().filter { it.startsWith("if (") },
        )
    }

    @Test fun `each entry kept the enabled rule its icon had`() {
        assertEquals(
            "the three entries that came down from the bar must keep their `enabled` rules, " +
                "whole and in order. Dropping the schedule's `scheduleSendAllowed(...)` offers a " +
                "send later on a signed/encrypted message or one with attachments — the headless " +
                "worker cannot sign and its table carries no attachments, so the message would go " +
                "out unsigned or amputated. Dropping `!sending` acts on a message already in " +
                "flight (INV-6).",
            ENABLED,
            overflowMenu().filter { it.startsWith("enabled = ") },
        )
    }

    // -- 4. the stamp: the one line where a schedule can turn into an immediate send ---------------

    @Test fun `tapping Schedule send stamps the presets, and stamps them at that tap`() {
        val block = overflowMenu()
        val at = block.indexOf(SCHEDULE_TEXT)
        assertTrue(
            "the ⋮ menu has no 'Schedule send' entry at all — expected '$SCHEDULE_TEXT'. See the " +
                "entry test above.",
            at >= 0,
        )
        assertEquals(
            "the 'Schedule send' entry's tap must be exactly:\n    $SCHEDULE_TAP\n⛔ " +
                "`menuOpenedAt` is stamped HERE, when the PRESETS open — never when the ⋮ opens. " +
                "Stamped at the ⋮, the whole time spent reading the overflow is added to the " +
                "presets' expiry: an entry drawn for 6 PM and tapped at 6:05 reaches " +
                "`ScheduledSends.enqueue` as a past instant, which coerces the delay to zero — " +
                "the message goes out AT ONCE and irreversibly, while the banner announces a " +
                "schedule. `moreMenu = false` before it is the other half: one menu closes, the " +
                "other opens, and there is no nested submenu.",
            SCHEDULE_TAP,
            block.getOrNull(at + 1),
        )
    }

    // -- 5. the delete still only ASKS -------------------------------------------------------------

    @Test fun `tapping Delete draft only raises the confirmation`() {
        val block = overflowMenu()
        val at = block.indexOf(DELETE_TEXT)
        assertTrue(
            "the ⋮ menu has no 'Delete draft' entry at all — expected '$DELETE_TEXT'. See the " +
                "entry test above.",
            at >= 0,
        )
        assertEquals(
            "the 'Delete draft' entry's tap must be exactly:\n    $DELETE_TAP\nIt closes the menu " +
                "and RAISES the confirmation, nothing else. Everything typed since the composer " +
                "opened is unsaved, and on a server draft the delete takes the SERVER copy — the " +
                "Undo behind it restores that copy, never the text on screen (#127), and on a " +
                "phone-kept draft there is no Undo at all (#95). Deleting straight from the entry " +
                "drops all of it with no question, on the one screen that already asks when the X " +
                "is tapped.",
            DELETE_TAP,
            block.getOrNull(at + 1),
        )
    }

    @Test fun `tapping Save draft saves what is on screen`() {
        val block = overflowMenu()
        val at = block.indexOf(SAVE_TEXT)
        assertTrue(
            "the ⋮ menu has no 'Save draft' entry at all — expected '$SAVE_TEXT'. See the entry " +
                "test above.",
            at >= 0,
        )
        assertEquals(
            "the 'Save draft' entry's tap must be exactly:\n    $SAVE_TAP\nThe six fields are the " +
                "message: one dropped here writes a server copy that describes something other " +
                "than what the screen shows (ReadReceiptWiringTest pins the same call from the " +
                "argument side).",
            SAVE_TAP,
            block.getOrNull(at + 1),
        )
    }

    // -- locating the pieces -----------------------------------------------------------------------

    /**
     * The code lines strictly inside the `TopAppBar`'s `title = {` slot, trimmed, in order.
     */
    private fun titleSlot(): List<String> {
        val code = code(COMPOSE_SCREEN)
        val bar = code.indexOf(TOP_APP_BAR)
        assertTrue("ComposeScreen.kt no longer contains '$TOP_APP_BAR' — did the top bar move?", bar >= 0)
        val at = code.indexOf("title = {", bar)
        assertTrue("the TopAppBar has no 'title = {' slot", at >= 0)
        return blockAt(code, at)
    }

    /**
     * The code lines strictly inside the `TopAppBar`'s `actions = {` slot, trimmed, in order.
     */
    private fun actionsBlock(): List<String> {
        val code = code(COMPOSE_SCREEN)
        val at = code.indexOf("actions = {")
        assertTrue("ComposeScreen.kt no longer contains 'actions = {' — did the top bar move?", at >= 0)
        return blockAt(code, at)
    }

    /**
     * The code lines strictly inside the ⋮ overflow's `DropdownMenu { … }`, trimmed, in order.
     */
    private fun overflowMenu(): List<String> {
        val code = code(COMPOSE_SCREEN)
        val anchor = code.indexOf(EXPANDED_MORE)
        assertTrue(
            "ComposeScreen.kt no longer holds a DropdownMenu whose '$EXPANDED_MORE' — the ⋮ " +
                "overflow is the whole point of this bar: without it the three entries below have " +
                "nowhere to be.",
            anchor >= 0,
        )
        val call = code.lastIndexOf("DropdownMenu(", anchor)
        assertTrue("'$EXPANDED_MORE' is not an argument of a DropdownMenu(", call >= 0)
        val whole = blockAt(code, matching(code, code.indexOf('(', call)))
        // #1800 sectioned the overflow (Actions, Text, Signature, Templates, Compose). These checks
        // are about the ACTIONS: everything up to the Text section's header, which must exist —
        // the other sections are pinned by ComposeOptionsParityTest.
        val textSection = whole.indexOfFirst { it.startsWith("ComposeMenuSection(R.string.compose_menu_section_text") }
        assertTrue("the overflow's Text section header is missing (#1800)", textSection > 0)
        return whole.subList(0, textSection)
    }

    /** The code lines strictly inside the first `{` at or after [at] in [code], trimmed, in order. */
    private fun blockAt(code: String, at: Int): List<String> {
        val open = code.indexOf('{', at)
        return code.substring(open + 1, matching(code, open))
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Index of the bracket closing the one at [open] in [code], string literals skipped. */
    private fun matching(code: String, open: Int): Int {
        var depth = 0
        var i = open
        var inString = false
        while (i < code.length) {
            val c = code[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                inString -> Unit
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        error("unbalanced block in ComposeScreen.kt at offset $open")
    }

    // -- reading the sources ------------------------------------------------------------------------

    /** [file]'s code as one string, comments cut — same reader as [ScheduleMenuWiringTest]. */
    private fun code(file: File): String = file.readLines().mapNotNull { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }.joinToString("\n")

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    companion object {
        /** The whole title slot, whole lines: the two ellipsis attributes and what they apply to. */
        private val TITLE_SLOT = listOf(
            "Text(",
            "stringResource(",
            "when (composeTitle(draftId, mode, replyTo, restore, editingOutbox)) {",
            "ComposeTitle.DRAFT -> R.string.draft_label",
            "ComposeTitle.FORWARD -> R.string.message_forward",
            "ComposeTitle.REPLY -> R.string.compose_title_reply",
            "ComposeTitle.OUTBOX_EDIT -> R.string.outbox_edit",
            "ComposeTitle.NEW -> R.string.compose_title_new",
            "},",
            "),",
            "maxLines = 1,",
            "overflow = TextOverflow.Ellipsis,",
            ")",
        )

        /**
         * The three icons that went down into the overflow, as the SYMBOL and not as a call site.
         */
        private val GONE = listOf(
            "Icons.Filled.Save",
            "Icons.Filled.Delete",
            "Icons.Filled.Schedule",
        )

        /** The imports the three icons needed, which nothing in this file may need again. */
        private val GONE_IMPORTS = GONE.map { "import androidx.compose.material.icons.filled.${it.substringAfterLast('.')}" }

        private const val SAVE_TEXT = "text = { Text(stringResource(R.string.compose_save_draft)) },"
        private const val SCHEDULE_TEXT =
            "text = { Text(stringResource(R.string.compose_schedule_send)) },"
        private const val DELETE_TEXT =
            "text = { Text(stringResource(R.string.compose_delete_draft)) },"

        private val ENTRIES = listOf(
            SAVE_TEXT,
            SCHEDULE_TEXT,
            DELETE_TEXT,
            "text = { Text(stringResource(R.string.compose_request_receipt)) },",
        )

        private val CONDITIONS = listOf(
            "if (draftSaveAllowed(pgpMode)) {",
            "if (draftDeleteOffered(restore, draftId, editingDraft != null, editingLocalDraftId != null)) {",
        )

        private val ENABLED = listOf(
            "enabled = !sending && canSaveDraft,",
            "enabled = !sending && canSend && scheduleSendAllowed(pgpMode, attachments.isNotEmpty()),",
            "enabled = !sending,",
        )

        private const val SAVE_TAP =
            "onClick = { moreMenu = false; scope.launch { viewModel.saveDraft(to, cc, bcc, subject.text, freshRich(), requestReceipt) } },"
        private const val SCHEDULE_TAP =
            "onClick = { moreMenu = false; menuOpenedAt = System.currentTimeMillis(); scheduleMenu = true },"
        private const val DELETE_TAP = "onClick = { moreMenu = false; viewModel.askDraftDelete() },"

        private const val EXPANDED_MORE = "expanded = moreMenu,"
        private const val TOP_APP_BAR = "TopAppBar("

        private const val COMPOSE_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val COMPOSE_SCREEN: File by lazy { File(root, COMPOSE_SCREEN_PATH) }
    }
}
