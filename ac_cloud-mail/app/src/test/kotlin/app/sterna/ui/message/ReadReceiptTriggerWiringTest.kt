package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the same instrument, and the same disclaimer, as
 */
class ReadReceiptTriggerWiringTest {

    // -- 1. the setting: stored, shown, switched, and carried in a backup ---------------------------

    /**
     * The unloaded state is a THIRD value, not `false`, and the flow is started EAGERLY.
     */
    @Test fun `the setting is read as three values, and starts eagerly`() {
        assertBlock(
            MESSAGE_VIEW_MODEL,
            listOf(
                "val readReceiptSetting: StateFlow<ReadReceiptSetting> = settings.askReadReceipt",
                ".map { if (it) ReadReceiptSetting.ON else ReadReceiptSetting.OFF }",
                ".stateIn(",
                "scope = viewModelScope,",
                "started = SharingStarted.Eagerly,",
                "initialValue = ReadReceiptSetting.NOT_LOADED,",
                ")",
            ),
            "the read-receipt setting's stateIn",
        )
    }

    /**
     * The stored side of the same setting: the flow hands the WHOLE `Preferences` to the pure
     */
    @Test fun `the stored setting is read through the lookup that holds its default`() {
        assertBlock(
            SETTINGS_REPOSITORY,
            listOf("val askReadReceipt: Flow<Boolean> = dataStore.data.map(::askReadReceiptFrom)"),
            "the read-receipt flow",
        )
    }

    /**
     * THE SWITCH IS WIRED TO ITS OWN SETTING.
     */
    @Test fun `the switch shows and writes the read-receipt setting`() {
        assertSoleLine(
            SETTINGS_SCREEN,
            "val askReadReceipt by viewModel.askReadReceipt.collectAsStateWithLifecycle()",
            "the privacy screen's collection of the read-receipt setting",
            "askReadReceipt",
        )
        assertBlock(
            SETTINGS_SCREEN,
            listOf(
                "SettingsSection(stringResource(R.string.settings_read_receipt_section)) {",
                "SettingSwitch(",
                "title = stringResource(R.string.settings_read_receipt_title),",
                "subtitle = stringResource(R.string.settings_read_receipt_subtitle),",
                "checked = askReadReceipt,",
                "onCheckedChange = viewModel::setAskReadReceipt,",
                ")",
                "}",
            ),
            "the read-receipt switch",
        )
        assertBlock(
            SETTINGS_VIEW_MODEL,
            listOf(
                "fun setAskReadReceipt(value: Boolean) {",
                "viewModelScope.launch { settings.setAskReadReceipt(value) }",
                "}",
            ),
            "the switch's write-through",
        )
    }

    /**
     * AND IT TRAVELS. `AndroidManifest.xml` + `res/xml/backup_rules.xml` exclude `datastore/`
     */
    @Test fun `the setting is exported and restored like every other preference`() {
        val repository = codeLines(SETTINGS_REPOSITORY)
        assertEquals(
            "snapshotBackup must carry 'askReadReceipt = askReadReceipt.first(),' — without it " +
                "the switch is absent from every export, and an import silently puts it back to " +
                "off for someone who had turned it on.",
            listOf("askReadReceipt = askReadReceipt.first(),"),
            repository.filter { it.startsWith("askReadReceipt = askReadReceipt") },
        )
        assertEquals(
            "restoreBackup must apply it: 'backup.askReadReceipt?.let { setAskReadReceipt(it) }'. " +
                "Exported and never read back is the same defect one step later. The '?.let' is " +
                "the part that matters: an absent field means 'leave as is', not 'off'.",
            listOf("backup.askReadReceipt?.let { setAskReadReceipt(it) }"),
            repository.filter { it.startsWith("backup.askReadReceipt") },
        )
    }

    // -- 2. the single trigger --------------------------------------------------------------------

    /**
     * WHAT "SETTLED" MEANS, pinned in the one line that defines it — and it is an IDENTITY,
     */
    @Test fun `active means the message the reader stopped on, not a page number`() {
        assertSoleLine(
            MESSAGE_SCREEN,
            "active = pagerKey(entry) == settledKey,",
            "the reader's definition of an active page",
            "active = ",
        )
    }

    /**
     * The settle, whole: the `active` guard, the once-only guard, the transition read off the
     */
    @Test fun `the marking is recorded by the reader's settle and nowhere else`() {
        assertBlock(
            MESSAGE_VIEW_MODEL,
            listOf(
                "private fun maybeMarkRead() {",
                "if (!active || anchorMarked) return",
                "val current = (_state.value as? MessageState.Loaded)?.email ?: return",
                "anchorMarked = true",
                "settleMarking = if (current.isSeen) {",
                "ReadMarking.READER_SETTLE_ALREADY_READ",
                "} else {",
                "ReadMarking.READER_SETTLE_UNREAD",
                "}",
                "maybeOfferReadReceipt()",
                "if (current.isSeen) return",
            ),
            "the reader's settle",
        )
    }

    /** Two assignments in the whole file: the reset for the next message, and the settle's own. */
    @Test fun `nothing else in the reader writes a marking`() {
        val assignments = codeLines(MESSAGE_VIEW_MODEL).filter { it.startsWith("settleMarking =") }
        assertEquals(
            "`settleMarking` is what the read-receipt decision hangs off. It may be written twice " +
                "and only twice: cleared for the next message in load()'s prologue, and set by the " +
                "settle. A third assignment is a second way into the decision, and the eight " +
                "markings that are not a person reading are exactly what it must not become. Found:",
            listOf("settleMarking = null", "settleMarking = if (current.isSeen) {"),
            assignments,
        )
    }

    /** The decision's call site for PUTTING the question, whole — arguments included. */
    @Test fun `the question is put by running the decision on the state as it stands`() {
        assertBlock(
            MESSAGE_VIEW_MODEL,
            listOf(
                "private fun maybeOfferReadReceipt() {",
                "val marking = settleMarking ?: return",
                "val current = (_state.value as? MessageState.Loaded)?.email ?: return",
                "_readReceiptOffer.value = offeredReadReceipt(",
                "setting = readReceiptSetting.value,",
                "marking = marking,",
                "request = readReceiptRequest(current, _deliveredTo.value, coverSubject),",
                "answered = readReceiptAnswer.answered,",
                ")",
                "}",
            ),
            "the read-receipt offer",
        )
        assertEquals(
            "`offeredReadReceipt(` is called exactly twice: once to put the question, once to act " +
                "on it. A third call is a third policy, and the one that gets forgotten is never " +
                "the one under test",
            2,
            codeLines(MESSAGE_VIEW_MODEL).count { "offeredReadReceipt(" in it },
        )
    }

    /**
     * The question is put again whenever something that feeds the decision changes — and the two
     */
    @Test fun `the question is put again when the body arrives, when the switch moves, and after a failure`() {
        assertBlock(
            MESSAGE_VIEW_MODEL,
            listOf(
                "loadCalendarFor(_messages.value.first())",
                // #1763 put the inline-image load and the neighbour prefetch between the calendar and
                // the settle; neither marks read nor offers a receipt (see the prefetch test below).
                "if (opened.crypto == null && opened.inlineImages.isEmpty() && anchor.inlineImageParts().isNotEmpty()) {",
                "loadInlineImagesLater(credentials, anchor.id)",
                "}",
                "repo.prefetchNeighbours(credentials, anchor.id)",
                "maybeMarkRead()",
                "maybeOfferReadReceipt()",
            ),
            "the offer put when the body lands",
        )
        assertBlock(
            MESSAGE_VIEW_MODEL,
            listOf(
                "init {",
                "viewModelScope.launch {",
                "readReceiptSetting.collect { maybeOfferReadReceipt() }",
                "}",
                "}",
            ),
            "the setting watcher",
        )
        // WIDENED BY ONE NAMED PLACE when the strip was written: a send that FAILED puts the
        // question back, because nothing left the device and the reader's yes bought her nothing —
        assertBlock(
            MESSAGE_VIEW_MODEL,
            listOf(
                "_readReceiptState.value = outcome",
                "if (outcome == ReadReceiptState.Failed) {",
                "readReceiptAnswer = ReadReceiptAnswer.PENDING",
                "maybeOfferReadReceipt()",
                "}",
                "}",
            ),
            "the retry after a failed send",
        )
        assertEquals(
            "the offer is put from exactly four places — the settle, the body landing, the " +
                "setting changing, and a send that failed — and from nowhere else. Calls found:",
            listOf(
                "readReceiptSetting.collect { maybeOfferReadReceipt() }",
                "maybeOfferReadReceipt()",
                "maybeOfferReadReceipt()",
                "maybeOfferReadReceipt()",
            ),
            codeLines(MESSAGE_VIEW_MODEL)
                .filter { "maybeOfferReadReceipt()" in it && !it.startsWith("private fun") },
        )
    }

    /**
     * A background body prefetch (#1763) is not an open: it must never mark mail read ($seen) and
     * never reach the receipt decision. Pins the prefetch path of MailRepository.
     */
    @Test fun `prefetching a body never sends a read receipt or sets seen`() {
        val repo = File(root, "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        val text = repo.readText()
        fun body(signature: String, until: String): List<String> {
            val start = text.indexOf(signature)
            check(start > 0) { "$signature is gone — teach this test the new shape" }
            val end = text.indexOf(until, start)
            check(end > start) { "$until no longer follows $signature" }
            return text.substring(start, end).lines().map { it.trim() }
                .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        }
        val paths = listOf(
            body("private suspend fun fetchAndStoreBodies(", "fun evictBodies("),
            body("private fun requestBodyPrefetch(", "private fun bodyPrefetchPort("),
        )
        val forbidden = listOf(
            "setRead(", "setReadAll(", "setSeen", "setFlag(", "\\Seen", "\$seen",
            "markRead", "receipt", "Receipt", "Disposition", "mdn", "Mdn", "MDN",
        )
        paths.forEach { lines ->
            assertTrue("the prefetch path is empty — this test lost its subject", lines.size > 10)
            val hits = lines.filter { l -> forbidden.any { it in l } }
            assertEquals("the body prefetch must never mark read or send a receipt. Lines:", emptyList<String>(), hits)
        }
        // The ViewModel's only prefetch call is a bare fire-and-forget; nothing is read from it.
        assertEquals(
            listOf("repo.prefetchNeighbours(credentials, anchor.id)"),
            codeLines(MESSAGE_VIEW_MODEL).filter { "prefetchNeighbours(" in it },
        )
    }

    /**
     * THE COUNTER-WITNESS. Every file in the app that marks mail read, and the fact that not one
     */
    @Test fun `no other file that marks mail read can reach the decision`() {
        val markers = mainSources().filter { file ->
            codeLines(file).any { "setRead(" in it || "setReadAll(" in it }
        }
        assertTrue(
            "no source calls setRead / setReadAll any more — this rule has lost its subject and " +
                "must be taught the new shape rather than left green over nothing",
            markers.size >= 3,
        )
        val reaching = markers.filter { file ->
            codeLines(file).any { line -> TRIGGER_SYMBOLS.any { it in line } }
        }.map { it.name }.sorted()
        assertEquals(
            "these files mark mail read AND name part of the read-receipt trigger. Only the " +
                "reader's own page may: it is the single place where a message is in front of a " +
                "person. Everything else here is a swipe, a multi-select, a 'mark all read', a " +
                "notification action, an archive, or a \$seen that arrived from another device — " +
                "mail nobody read, and a receipt sent for one of those tells a stranger something " +
                "that never happened. Files that mark mail read: " + markers.map { it.name }.sorted(),
            listOf("MessageViewModel.kt"),
            reaching,
        )
    }

    /**
     * And the trigger's names exist in exactly three files, so nothing can reach them sideways.
     */
    @Test fun `the trigger is named in three files only`() {
        val naming = mainSources().filter { file ->
            codeLines(file).any { line -> TRIGGER_SYMBOLS.any { it in line } }
        }.map { it.name }.sorted()
        assertEquals(
            "the read-receipt trigger is written in the decision's own file, in the reader, and " +
                "in the screen that draws the strip — and a FOURTH file naming it is a fourth way " +
                "in. Adding one is a deliberate edit here, which is the point.",
            listOf("MessageScreen.kt", "MessageViewModel.kt", "ReadReceiptOffer.kt"),
            naming,
        )
    }

    /**
     * AND THE SEND ITSELF. The question can be guarded perfectly and the door still stand open
     */
    @Test fun `only the reader asks the repository to queue a receipt`() {
        val naming = mainSources().filter { file ->
            codeLines(file).any { "sendReadReceipt" in it }
        }.map { it.name }.sorted()
        assertEquals(
            "`sendReadReceipt` is the irreversible end of this work: what it queues leaves the " +
                "device and tells a stranger a person read their mail. It is declared in " +
                "MailRepository, called from the reader, and named by the screen that wires the " +
                "strip's button — and nowhere else. A background receiver, a worker or a sync " +
                "path naming it at all is the short-circuit this rule exists for.",
            listOf("MailRepository.kt", "MessageScreen.kt", "MessageViewModel.kt"),
            naming,
        )
    }

    // -- 3. the hand-over to the repository --------------------------------------------------------

    /**
     * The tap re-runs the DECISION rather than trusting that a banner is on screen, and the send
     */
    @Test fun `the tap re-reads the setting, and the send is handed the captured request`() {
        assertBlock(
            MESSAGE_VIEW_MODEL,
            listOf(
                "fun sendReadReceipt() {",
                "val pending = offeredReadReceipt(",
                "setting = readReceiptSetting.value,",
                "marking = ReadMarking.READER_SETTLE_UNREAD,",
                "request = _readReceiptOffer.value,",
                "answered = readReceiptAnswer.answered,",
                ") ?: return",
                "readReceiptAnswer = ReadReceiptAnswer.ACCEPTED",
                "_readReceiptOffer.value = null",
            ),
            "the acceptance",
        )
        assertBlock(
            MESSAGE_VIEW_MODEL,
            listOf(
                "readReceiptSendOutcome(",
                "repo.sendReadReceipt(",
                "credentials = credentials,",
                "receiptTo = pending.receiptTo,",
                "originalSubject = pending.originalSubject,",
                "originalMessageId = pending.originalMessageId,",
                "deliveredTo = pending.deliveredTo,",
                "),",
                ")",
            ),
            "the read-receipt send",
        )
    }

    /**
     * Saying no sends nothing, and it is REMEMBERED for this page — otherwise the decision, which
     */
    @Test fun `saying no sends nothing, and is not asked again`() {
        assertBlock(
            MESSAGE_VIEW_MODEL,
            listOf(
                "fun declineReadReceipt() {",
                "readReceiptAnswer = ReadReceiptAnswer.DECLINED",
                "_readReceiptOffer.value = null",
                "}",
            ),
            "the refusal",
        )
    }

    /**
     * The per-message state is CLEARED when the pager repoints this page at another message —
     */
    @Test fun `a new message in the pager clears the question, the state and the answer`() {
        val prologue = loadPrologue()
        listOf(
            "_readReceiptOffer.value = null",
            "_readReceiptState.value = ReadReceiptState.Idle",
            "readReceiptAnswer = ReadReceiptAnswer.PENDING",
        ).forEach { line ->
            assertTrue(
                "load()'s prologue — everything that runs before it suspends — must contain " +
                    "exactly this line:\n  $line\nAnything else leaves the previous message's " +
                    "question, or the previous reader's answer, standing over the new one. " +
                    "Prologue was:\n" + prologue.joinToString("\n"),
                line in prologue,
            )
        }
    }

    /**
     * The preview is relayed and nothing else is: the state is built from what came back, so the
     */
    @Test fun `the reader is told what was queued, not what this file would rebuild`() {
        val lines = codeLines(MESSAGE_VIEW_MODEL)
        assertEquals(
            "the outcome must be built from the repository's return value, in one expression",
            1,
            lines.count { it == "readReceiptSendOutcome(" },
        )
        val rebuilt = lines.filter { "readReceiptPreview(" in it || "ReadReceiptPreview(" in it }
        assertEquals(
            "the reader must not build a receipt's wording of its own — the strings it shows are " +
                "the ones `sendReadReceipt` put in the outbox row, handed back. Found:",
            emptyList<String>(),
            rebuilt,
        )
    }

    // -- reading the sources -----------------------------------------------------------------------

    /** The trimmed lines of `load()`'s prologue: everything that runs before the first suspension. */
    private fun loadPrologue(): List<String> {
        val source = MESSAGE_VIEW_MODEL.readText()
        val start = source.indexOf("fun load(")
        check(start > 0) { "MessageViewModel has no load() any more" }
        val end = source.indexOf("viewModelScope.launch", start)
        check(end > start) { "load() no longer launches a coroutine — this rule's shape is wrong" }
        return source.substring(start, end).lines().map { it.trim() }
    }

    /**
     * Pins one WHOLE line that must appear in [file] EXACTLY ONCE. [near] names the neighbourhood
     * printed on failure, so the message says which of "absent" and "duplicated" happened.
     */
    private fun assertSoleLine(file: File, expected: String, what: String, near: String) {
        val lines = codeLines(file)
        val nearby = lines.filter { near in it }
        assertEquals(
            "$what must appear in ${file.name} exactly once and be written EXACTLY as:\n" +
                "  $expected\n" +
                "Lines mentioning '$near':\n" + nearby.joinToString("\n").ifEmpty { "(none at all)" },
            1,
            lines.count { it == expected },
        )
    }

    /**
     * Locates [expected]'s first line in [file] and compares the block that follows it, whole.
     */
    private fun assertBlock(file: File, expected: List<String>, what: String) {
        val lines = codeLines(file)
        val occurrences = lines.count { it == expected.first() }
        check(occurrences == 1) {
            "'${expected.first()}' appears $occurrences times in ${file.name} — $what must have " +
                "exactly one anchor. Zero: it is gone or was reshaped, and this lint must be " +
                "taught the new shape. Two or more: this rule would check whichever comes first " +
                "and say nothing about the other."
        }
        val at = lines.indexOfFirst { it == expected.first() }
        val found = lines.subList(at, minOf(at + expected.size, lines.size))
        val mismatches = expected.indices.mapNotNull { i ->
            val actual = found.getOrNull(i)
            if (actual == expected[i]) null
            else "line ${i + 1} of $what: expected '${expected[i]}' but found '$actual'"
        }
        assertEquals(
            "$what is pinned WHOLE, line by line and in order. Nothing in this module can run it. " +
                "Mismatches:\n" + mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches,
        )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule can be satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    /** Every Kotlin source the app ships: the app module and the three library modules. */
    private fun mainSources(): List<File> = SOURCE_ROOTS
        .map { File(root, it) }
        .onEach { check(it.isDirectory) { "no such source root: $it" } }
        .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }

    private companion object {
        /**
         * The names that make up the trigger — the question and its decision. Every one of them is
         */
        val TRIGGER_SYMBOLS = listOf(
            "offeredReadReceipt",
            "settleMarking",
            "ReadMarking.",
            "_readReceiptOffer",
            "readReceiptSetting",
            "readReceiptAnswer",
            "ReadReceiptAnswer.",
            "declineReadReceipt",
        )

        val SOURCE_ROOTS = listOf(
            "app/src/main/kotlin",
            "core/data/src/main/kotlin",
            "core/jmap/src/main/kotlin",
            "core/imap/src/main/kotlin",
        )

        private const val MESSAGE_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, MESSAGE_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this lint reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        val MESSAGE_VIEW_MODEL: File by lazy { File(root, MESSAGE_VIEW_MODEL_PATH) }

        val MESSAGE_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")
        }

        val SETTINGS_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt")
        }

        val SETTINGS_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsViewModel.kt")
        }

        val SETTINGS_REPOSITORY: File by lazy {
            File(root, "core/data/src/main/kotlin/app/sterna/core/data/settings/SettingsRepository.kt")
        }
    }
}
