package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. It reads `InboxScreen.kt` as text and proves nothing about what
 */
class RowFolderRoleWiringLintTest {

    @Test
    fun `every per-row affordance is decided on that row's own folder`() {
        assertEquals(
            "Each of these lines must decide on the folder of the row it is drawn on. The two the " +
                "mutations aim at: the child swipe handed `rowRole` (the collapsed row's " +
                "representative — a child living in Sent then unarchives out of Sent), and the " +
                "top-level row handed `folderTrusted = true` (a search hit's folder is frozen at " +
                "crawl time, and believing it moves a message out of the folder it was moved to). " +
                "The selection bar and the folder menu are NOT here on purpose: they act on many " +
                "rows or on none, so they keep the view's role. Lines found were:" +
                "\n${gestureLines().joinToString("\n")}",
            EXPECTED_LINES.sorted(),
            gestureLines().sorted(),
        )
    }

    /**
     * Every code line of `InboxScreen.kt` that decides a per-row affordance from a role, comments
     * stripped and whitespace collapsed. Declarations are left out — this is about call sites.
     */
    private fun gestureLines(): List<String> =
        codeLines(INBOX_SCREEN)
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            .filter { line -> MARKERS.any { it in line } }
            .filterNot { it.startsWith("private fun ") || it.startsWith("internal fun ") }

    /**
     * [file]'s non-blank lines with every comment taken out. The block-comment state is carried
     */
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
            if (code.isNotBlank()) out += code.toString().trim()
        }
        return out
    }

    companion object {
        /** What makes a line a per-row role decision. `visibleFolderRole` is in the list so that a
         *  site quietly going back to the view's role has to come here and say so. */
        private val MARKERS = listOf(
            "rowFolderRole(",
            "isUnarchiveContext(",
            "isTrashContext(",
            "rowTapAction(",
            "childTapAction(",
            "performSwipe(",
            "performThreadSwipe(",
            "visibleFolderRole(",
        )

        /**
         * The lines, whole. Most are the row's own decision; three keep the VIEW's role and are
         */
        private val EXPECTED_LINES = listOf(
            // The selection bar: many rows at once, so the view's role — see above.
            "val trash = isTrashContext(visibleFolderRole(ui))",
            // Not this volet's: which folder decides "To: …" is #115's rule, unchanged.
            "role = visibleFolderRole(ui),",
            // rowFolderRole itself: the search refusal FIRST, then the lookup with its fallback.
            // Both whole, because the refusal is one deleted line away from a lost message.
            "if (!folderTrusted) return visibleFolderRole(ui)",
            "return messageFolderRole(email.accountId, email.mailboxId, roles) ?: visibleFolderRole(ui)",
            // The browse/search row, decided ONCE and reused by the four sites under it. The trust
            // flag is the search guard and is not optional.
            "val rowRole = rowFolderRole(email, ui, folderRoles, folderTrusted = !fromSearch)",
            "unarchiveContext = isUnarchiveContext(rowRole),",
            "trashContext = isTrashContext(rowRole),",
            "if (expandable) performThreadSwipe(action, email, viewModel, ui, rowRole)",
            "else performSwipe(action, email, viewModel, ui, rowRole)",
            // The tap hands rowTapAction the row's role, LAST; rowRole's fallback is the search guard.
            "when (rowTapAction(isLocalDraftRow(email.id), selectionActive, expandable, fromSearch, rowRole)) {",
            // The unfolded children: each one judged by ITS OWN folder, never the representative's.
            // Trusted because a child comes from the cache — search results never unfold.
            "unarchiveContextFor = { child -> isUnarchiveContext(rowFolderRole(child, ui, folderRoles, folderTrusted = true)) },",
            "trashContextFor = { child -> isTrashContext(rowFolderRole(child, ui, folderRoles, folderTrusted = true)) },",
            // #1802: the delivery ticker is drawn on a row filed in Sent, by THAT child's own folder.
            "sentDeliveryFor = { child -> isSentRole(rowFolderRole(child, ui, folderRoles, folderTrusted = true)) },",
            "performSwipe(action, child, viewModel, ui, rowFolderRole(child, ui, folderRoles, folderTrusted = true))",
            // The child tap asks the child's OWN folder, like its swipe; `rowRole` here is the defect.
            "when (childTapAction(rowFolderRole(child, ui, folderRoles, folderTrusted = true))) {",
            // The two dispatchers read the role they were handed; the branch itself is behaviour.
            "if (isUnarchiveContext(rowRole)) {",
            "if (isUnarchiveContext(rowRole)) {",
        )

        private const val INBOX_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
    }
}
