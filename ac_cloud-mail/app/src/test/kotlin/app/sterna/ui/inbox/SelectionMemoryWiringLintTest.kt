package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — read this before trusting it.
 */
class SelectionMemoryWiringLintTest {

    @Test
    fun `the stored view is read back with the account it belongs to, and the bound the drawer uses`() {
        val calls = mainSources().flatMap { file ->
            callSitesTo("restoreSelection", codeText(file)).map { "${file.name}: $it" }
        }
        assertEquals(
            "the pure rule restoreSelection must be CALLED exactly once in the app module, from " +
                "restoredSelection() in SelectionMemory.kt — a second call is a second copy of " +
                "the same three arguments, and the copy that drifts takes the view the app " +
                "reopens on with it. ⚠ What this counts is calls to the RULE, not reads of the " +
                "store: a caller that decodes the stored string by hand calls nothing and is " +
                "caught by the storedView rule below, not here. Calls found were:" +
                "\n${calls.joinToString("\n")}",
            1,
            calls.size,
        )
        val call = callSitesTo("restoreSelection", codeText(SELECTION_MEMORY)).single()
        assertEquals(
            "restoreSelection must be handed the stored string, the CURRENT account id, and the " +
                "same bound the drawer posts its 'All inboxes' entry under. store.currentId() and " +
                "store.inboxMailboxId() are both String? and swap silently; a bare " +
                "'store.accounts().size > 1' here would be a fourth copy of a bound that already " +
                "moved once. Compared as whole arguments. Arguments found were:" +
                "\n${argumentsOf(call).joinToString("\n")}",
            EXPECTED_RESTORE_ARGUMENTS,
            argumentsOf(call),
        )
    }

    /**
     * The OTHER half of "one reader", and the one the count above cannot see: the store itself.
     */
    @Test
    fun `the stored view is read out of the store in exactly two named places`() {
        val reads = mainSources().flatMap { file ->
            callSitesTo("storedView", codeText(file)).map { "${file.name}: $it" }
        }.sorted()
        assertEquals(
            "the app module may read store.storedView() in exactly TWO places, and this rule names " +
                "both, file included. Any OTHER read is a hand-rolled decoder of the stored " +
                "format: it compiles, it calls no rule, and it answers a different view than the " +
                "list the day either side changes.\n" +
                "  - SelectionMemory.kt — restoredSelection(), the one reader that reopens a view;\n" +
                "  - AccountsViewModel.kt — signOut(), which hands the value to prunedView() so a " +
                "removed account's memory (an IMAP folder PATH, in cleartext) leaves the disk with " +
                "the account, as PRIVACY.md promises. It decodes nothing: the whole line is pinned " +
                "by SignOutStopsTheSyncWiringTest and the rule itself is executed by " +
                "SelectionMemoryTest.\n" +
                "Reads found were:\n${reads.joinToString("\n")}",
            listOf("AccountsViewModel.kt: storedView()", "SelectionMemory.kt: storedView()"),
            reads,
        )
    }

    /**
     * The adapter's own signature, whole. It takes the STORE and nothing else: handed a stored
     */
    @Test
    fun `the one reader of the store takes the store, and answers a selection`() {
        assertEquals(
            "SelectionMemory must expose restoredSelection(store: AccountStore): Sel? — the single " +
                "seam InboxViewModel and RootViewModel both go through. Widen its parameters and " +
                "the decision moves back out to the callers, one of which will get it wrong.",
            listOf("internal fun restoredSelection(store: AccountStore): Sel? = restoreSelection("),
            codeLines(SELECTION_MEMORY).filter { it.startsWith("internal fun restoredSelection") },
        )
    }

    @Test
    fun `the cold start still falls back on the inbox of the current account`() {
        val text = codeText(INBOX_VIEW_MODEL)
        val hits = Regex("""\bMutableStateFlow<Sel>\s*\(""").findAll(text).toList()
        assertEquals(
            "InboxViewModel is expected to hold exactly ONE MutableStateFlow<Sel> — the selection " +
                "this rule is anchored on.",
            1,
            hits.size,
        )
        val declaration = callAt(text, hits.single().range.first)
        assertEquals(
            "The whole initialiser, whitespace collapsed. The elvis fallback is what makes this " +
                "change invisible when there is nothing stored, when the memory is another " +
                "account's, or when it names a view this install does not offer: drop it and a cold " +
                "start opens no folder at all. Found:\n$declaration",
            EXPECTED_SELECTION_DECLARATION,
            declaration,
        )
    }

    @Test
    fun `init collects the selection twice, for two different subjects`() {
        val block = initBlock()
        val collectors = Regex("""\bselection\.collect\b""").findAll(block)
            .map { balancedFrom(block, it.range.first) }
            .toList()
        assertEquals(
            "init must carry TWO separate collectors of the selection, whole and unmerged: the push " +
                "side's (which inbox is on screen) and the memory's (what to reopen on). Folded " +
                "together they share a KDoc that speaks only of push, and store.currentId() must be " +
                "read INSIDE the lambda — hoisted out, it freezes the account of the moment the " +
                "ViewModel was built and onAccountChanged() then writes the arriving selection " +
                "under the departing account's id. Compared as whole calls, in source order. " +
                "Collectors found were:\n${collectors.joinToString("\n")}",
            EXPECTED_SELECTION_COLLECTORS,
            collectors,
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    /** The body of the ViewModel's single `init { … }`, comments out and whitespace collapsed. */
    private fun initBlock(): String {
        val text = codeText(INBOX_VIEW_MODEL)
        val hits = Regex("""\binit\s*\{""").findAll(text).toList()
        check(hits.size == 1) {
            "InboxViewModel is expected to have exactly one init block — this rule reads it. " +
                "Found ${hits.size}."
        }
        return balancedFrom(text, hits.single().range.first)
    }

    /**
     * Every Kotlin source of the app module's THREE production source sets. `restoreSelection` and
     */
    private fun mainSources(): List<File> = MODULE_SOURCE_DIRS
        .map { File(root, it) }
        .onEach {
            check(it.isDirectory) {
                "source directory missing: $it — this lint would then scan less than the module"
            }
        }
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    /** [file]'s code lines, comments and blanks dropped, each trimmed. */
    private fun codeLines(file: File): List<String> =
        strippedLines(file).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Every CALL of [name] in [text], whole, arguments included, in source order — the function's
     * own `fun` declaration excluded, since it is the thing being called, not a caller of it.
     */
    private fun callSitesTo(name: String, text: String): List<String> =
        Regex("""\b${Regex.escape(name)}\s*\(""").findAll(text)
            .filterNot { text.substring(0, it.range.first).trimEnd().endsWith("fun") }
            .map { callAt(text, it.range.first) }
            .toList()

    /** The arguments of the call [text], split at the commas of its own depth, blanks dropped. */
    private fun argumentsOf(text: String): List<String> {
        val open = text.indexOf('(')
        check(open >= 0) { "not a call: $text" }
        val args = mutableListOf<String>()
        var depth = 0
        var start = open + 1
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(', '{', '[' -> depth++
                ')', ']', '}' -> depth--
            }
            if (depth == 0) {
                args += text.substring(start, i)
                break
            }
            if (depth == 1 && text[i] == ',') {
                args += text.substring(start, i)
                start = i + 1
            }
            i++
        }
        return args.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The whole call starting at [from], up to the `)` that balances its first `(`. */
    private fun callAt(text: String, from: Int): String {
        val open = text.indexOf('(', from)
        check(open >= 0) { "no argument list opens after offset $from" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
            if (depth == 0) break
        }
        return text.substring(from, i).trim()
    }

    /** [text] from [from] up to the `}` that balances the first `{` at or after it. */
    private fun balancedFrom(text: String, from: Int): String {
        val open = text.indexOf('{', from)
        check(open >= 0) { "no block opens after offset $from" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
            if (depth == 0) break
        }
        return text.substring(from, i).trim()
    }

    /**
     * [file] as ONE line of code: comments taken out, runs of whitespace collapsed. Same scanner as
     */
    private fun codeText(file: File): String = strippedLines(file).joinToString(" ")
        .replace(Regex("""\s+"""), " ").trim()

    private fun strippedLines(file: File): List<String> {
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
            out += code.toString()
        }
        return out
    }

    companion object {
        /** The module's production source sets, all three — see [mainSources]. */
        private val MODULE_SOURCE_DIRS = listOf(
            "app/src/main/kotlin",
            "app/src/benchShared/kotlin",
            "app/src/testApp/kotlin",
        )

        /** What the cold-start read is given, argument by argument. */
        private val EXPECTED_RESTORE_ARGUMENTS = listOf(
            "store.storedView()",
            "store.currentId()",
            "AllInboxesView.existsFor(store.accounts())",
        )

        /** The whole selection declaration, whitespace collapsed — memory first, inbox as fallback. */
        private const val EXPECTED_SELECTION_DECLARATION =
            "MutableStateFlow<Sel>( restoredSelection(store) ?: Sel.Folder(store.inboxMailboxId() ?: inboxIdFromTheStore()), )"

        /** The two collectors of the selection, whole, in source order. */
        private val EXPECTED_SELECTION_COLLECTORS = listOf(
            "selection.collect { PushController.unifiedInboxVisible = it is Sel.Unified }",
            "selection.collect { store.setStoredView(encodeSelection(store.currentId(), it)) }",
        )

        private const val INBOX_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }

        private val SELECTION_MEMORY: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/inbox/SelectionMemory.kt")
        }
    }
}
