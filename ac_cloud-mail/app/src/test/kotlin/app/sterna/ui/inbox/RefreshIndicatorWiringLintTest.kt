package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. It reads `InboxViewModel.kt` and `InboxScreen.kt` as text and
 * proves nothing about what the app does. [RefreshIndicatorGraceTest] is the real test of #178 —
 * read it first: it EXECUTES the grace, the floor and the visibility flow on a virtual clock. This
 * file only holds the wiring, which no JVM test can reach: `InboxViewModel` is an
 * `AndroidViewModel` and is not instantiable here, and the screen is Compose.
 *
 * Each rule below is a one-word mutation that compiles and leaves the rest of the suite green:
 *  - `refreshRequested()` setting its flag AFTER calling `refresh()` — `viewModelScope` is
 *    `Main.immediate`, so the launch body has already started and the pull gets the grace it exists
 *    to skip;
 *  - the seed of the state flow carrying `showRefreshIndicator = true`, which puts the tern back on
 *    screen for the whole of every cold start — #178's third case, shipped by its own fix;
 *  - the seed losing `refreshing = true`, which is NOT this fix's field: it empties the centre so
 *    "this folder is empty" cannot lie during the first fetch (#63);
 *  - `PullToRefreshBox`/`TernRefreshIndicator` reading `ui.refreshing` again — the defect itself,
 *    restored, with every test still green;
 *  - the `ui.refreshing -> Unit` arm switched to the new field, which would let the empty-folder
 *    scene draw itself during a fetch shorter than the grace (#63 undone).
 *
 * Every rule compares a WHOLE unit — a whole declaration body, a whole named argument. Never a
 * `contains` on a fragment: a fragment is blind to any mutation that LENGTHENS the line
 * (`ui.showRefreshIndicator` contains neither more nor less than `ui.refreshing || debug`, and
 * `refreshing = true` is a prefix of `refreshing = true && x`). The price is that a reformat
 * reddens this test; that direction of error is loud and one line to fix.
 */
class RefreshIndicatorWiringLintTest {

    // -- the ViewModel ----------------------------------------------------------------------------

    @Test
    fun `refreshRequested raises the gesture flag BEFORE it calls refresh`() {
        val body = declarationBody(INBOX_VIEW_MODEL, "fun refreshRequested()")
        assertEquals(
            "The only door to an indicator with no grace is refreshRequested(), and the order of " +
                "its two lines is the whole point: viewModelScope is Main.immediate, so the body of " +
                "refresh()'s launch runs BEFORE refresh() returns — a flag raised after the call is " +
                "read too late and the reader's own pull waits out the 250 ms grace. Compared whole, " +
                "because a rule looking for both lines would pass on either order. Body was:\n$body",
            "fun refreshRequested() { refreshAnswersAGesture = true refresh() }",
            body,
        )
    }

    /**
     * The one line no other rule here could see, and the survivor a counter-expertise found: the
     * DEFINITION of the clock. Every other pin in this file compares a call site spelling `clock()`,
     * which is character-for-character identical whatever the clock actually is.
     */
    @Test
    fun `the clock is Android's duration clock, named whole`() {
        val lines = codeLines(INBOX_VIEW_MODEL).map { it.trim() }.filter { it.startsWith("private val clock") }
        assertEquals(
            "InboxViewModel must read the DURATION clock, and the whole declaration is compared " +
                "because each wrong answer is one word away and invisible to every other test here: " +
                "System::currentTimeMillis is a WALL clock, which NTP moves while the monotonic " +
                "delay scheduling the wake-ups does not — the grace is then skipped or overserved, " +
                "and the persisted freshness register believes a stamp from before a correction; " +
                "System::nanoTime compiles, is monotonic, and is in NANOSECONDS, so the 250 ms " +
                "grace becomes 250 ns, i.e. none at all, and #178 ships again with this suite " +
                "green. elapsedRealtime counts through sleep and survives process death, which is " +
                "what the freshness register needs across a cold start. Declarations found were:",
            listOf("private val clock: () -> Long = SystemClock::elapsedRealtime"),
            lines,
        )
    }

    @Test
    fun `the ring is timed on the same duration clock as the tern`() {
        val reads = codeLines(INBOX_SCREEN).map { it.trim() }
            .filter { "elapsedRealtime" in it || "currentTimeMillis" in it }
        assertEquals(
            "rememberGracedIndicator times the centred ring, and it must read the SAME duration " +
                "clock as the ViewModel: it sleeps on a monotonic delay between its two reads, so a " +
                "wall clock corrected in between would blank the centre past the grace or skip it. " +
                "Both reads are pinned, whole. Clock reads found in InboxScreen.kt were:",
            listOf("val now = SystemClock.elapsedRealtime()", "val instant = SystemClock.elapsedRealtime()"),
            reads,
        )
    }

    @Test
    fun `refresh opens a stretch with the gesture flag it consumes, then leaves its body alone`() {
        val body = declarationBody(INBOX_VIEW_MODEL, "fun refresh()")
        assertTrue(
            "refresh() must open (or extend) the indicator's stretch before it touches the status, " +
                "read the gesture flag into a local and CLEAR it — a flag left standing makes every " +
                "later background reconcile instant too, and a missing startRefreshRun call means " +
                "the indicator never appears at all, which no JVM test can see. The three lines are " +
                "compared as the whole opening of the body, not searched for. Body was:\n$body",
            body.startsWith(EXPECTED_REFRESH_OPENING),
        )
    }

    @Test
    fun `the stretch ends off the truth, in one collector, and not in the three arms`() {
        val text = codeText(INBOX_VIEW_MODEL)
        val at = text.indexOf(END_OF_STRETCH_ANCHOR)
        check(at >= 0) {
            "InboxViewModel.kt no longer collects the refreshing flag to close the indicator's " +
                "stretch. Without it the stretch never ends and the tern stays up for ever — #130's " +
                "symptom, by another road. Expected a launch starting with:\n$END_OF_STRETCH_ANCHOR"
        }
        assertEquals(
            "The end of a stretch is DERIVED from the truth (status.refreshing going false) in one " +
                "place, so the three arms of refresh()'s when — which BoundedRefreshWiringLintTest " +
                "pins word for word — never have to carry it. Compared whole. Block was:",
            "viewModelScope.launch { status.map { it.refreshing }.distinctUntilChanged().collect { busy -> " +
                "if (!busy) refreshRun.value = refreshRun.value?.copy(endedAt = clock()) } }",
            balancedFrom(text, at),
        )
    }

    @Test
    fun `the state carries the display from the fold, and never re-reads the truth`() {
        val text = codeText(INBOX_VIEW_MODEL)
        assertEquals(
            "MailUi.showRefreshIndicator must be carried from the fold that computed it. " +
                "'showRefreshIndicator = base.refreshing' compiles, type-checks, reads perfectly " +
                "well — and hands the screen the truth again, i.e. #178 whole, with the grace still " +
                "computed and thrown away and every behaviour test green. Whole line compared; " +
                "nothing else in this repo pins it.",
            1,
            Regex(Regex.escape("showRefreshIndicator = base.showRefreshIndicator,")).findAll(text).count(),
        )
    }

    @Test
    fun `the seed of the state flow keeps the truth and spells the display false`() {
        val text = codeText(INBOX_VIEW_MODEL)
        val at = text.indexOf(SEED_ANCHOR)
        check(at >= 0) { "InboxViewModel.kt no longer seeds its state flow with '$SEED_ANCHOR'." }
        val args = argsAt(text, at)

        assertTrue(
            "The seed must keep 'refreshing = true'. It is NOT this fix's field: it is the truth, " +
                "and it empties the centre of the screen so the empty-folder scene cannot claim the " +
                "folder holds nothing while the first fetch is still out (#63). Compared as a whole " +
                "argument. Seed carried:\n$args",
            args.contains("refreshing = true"),
        )
        assertTrue(
            "The seed must spell 'showRefreshIndicator = false' EXPLICITLY, not lean on the field's " +
                "default: this placeholder is a second site of every rule the combine states, and it " +
                "is what the reader sees for the whole of a cold start — exactly the moment #178 is " +
                "about. Compared as a whole argument. Seed carried:\n$args",
            args.contains("showRefreshIndicator = false"),
        )
    }

    // -- the screen -------------------------------------------------------------------------------

    @Test
    fun `the tern and the pull box are driven by the display, never by the truth`() {
        val text = codeText(INBOX_SCREEN)

        // #1869: the box and the tern read ternShowing, derived from the DISPLAY (never the truth) and narrowed to
        // the pull gesture or an empty list, so no spinner is drawn over cached rows.
        assertEquals(
            "ternShowing must be derived from ui.showRefreshIndicator, the gesture flag and the row count, whole. " +
                "Fed ui.refreshing it lights the instant refresh() is called (#178); without the gesture or the row " +
                "count it puts the spinner back over cached rows (#1869).",
            listOf(
                "val ternShowing = ternDrawn(ui.showRefreshIndicator, refreshGesture, listRows.itemCount)",
                "val syncLineShowing = syncLineDrawn(ui.showRefreshIndicator, refreshGesture, listRows.itemCount)",
            ),
            codeLines(INBOX_SCREEN).map { it.trim() }.filter { it.startsWith("val ternShowing") || it.startsWith("val syncLineShowing") },
        )
        assertEquals(
            "the gesture flag is the ViewModel's, read from the stretch on screen",
            listOf("val refreshGesture by viewModel.refreshByGesture.collectAsStateWithLifecycle()"),
            codeLines(INBOX_SCREEN).map { it.trim() }.filter { it.startsWith("val refreshGesture") },
        )
        assertTrue(
            "the gesture flag is RefreshRun.instant, nothing else",
            codeText(INBOX_VIEW_MODEL).contains("val refreshByGesture: StateFlow<Boolean> = refreshRun.map { it?.instant == true }"),
        )
        val pull = argsAt(text, findOnce(text, "PullToRefreshBox"))
        assertTrue(
            "PullToRefreshBox must be driven by ternShowing (the display, narrowed by #1869): fed the truth it " +
                "lights the instant refresh() is called, which IS #178. Compared as a whole named " +
                "argument. Its arguments were:\n$pull",
            pull.contains("isRefreshing = ternShowing"),
        )
        assertTrue(
            "The pull gesture must call refreshRequested(), the only door to an indicator with no " +
                "grace: through refresh() the reader's own pull draws nothing for 250 ms and reads " +
                "as a pull that did nothing. Its arguments were:\n$pull",
            pull.contains("onRefresh = { viewModel.refreshRequested() listRows.retry() }"),
        )

        val tern = argsAt(text, findOnce(text, "TernRefreshIndicator"))
        assertTrue(
            "TernRefreshIndicator must read the same display field as the box around it. Its " +
                "arguments were:\n$tern",
            tern.contains("isRefreshing = ternShowing"),
        )
    }

    @Test
    fun `the ring answers no gesture, so its stretch is never an instant one`() {
        val text = codeText(INBOX_SCREEN)
        assertEquals(
            "The centred ring must open its stretch with instant = false, whole call compared. " +
                "'instant = true' there compiles, leaves every behaviour test green (the pure rule " +
                "is right, it is simply asked the wrong question) and puts the ring back on screen " +
                "for every load shorter than the grace — #178 in the other indicator.",
            1,
            Regex(Regex.escape("startRefreshRun(run.value, now, instant = false)")).findAll(text).count(),
        )
        assertEquals(
            "…and it must be the ONLY startRefreshRun on the screen: a second one is a second " +
                "stretch nobody ends.",
            1, Regex("""\bstartRefreshRun\s*\(""").findAll(text).count(),
        )
    }

    @Test
    fun `the screen reads the truth exactly once, in the arm that keeps the centre empty`() {
        val text = codeText(INBOX_SCREEN)
        val reads = Regex("""\bui\.refreshing\b""").findAll(text).count()
        assertEquals(
            "InboxScreen.kt must read ui.refreshing — the truth — exactly ONCE: in the arm that " +
                "keeps the centre of the screen empty during a fetch (#63). More than one read means " +
                "a drawing decision went back to the truth and #178 is back; zero means #63's arm " +
                "went with it and the empty-folder scene can claim an empty folder mid-fetch.",
            1, reads,
        )
        assertEquals(
            "The one read must be the bare arm 'ui.refreshing -> Unit', unchanged: it is #63's " +
                "decision and this branch does not touch it.",
            1, Regex("""\bui\.refreshing -> Unit\b""").findAll(text).count(),
        )
    }

    @Test
    fun `no refresh entry point on the screen bypasses refreshRequested`() {
        val text = codeText(INBOX_SCREEN)
        // (?![A-Za-z]) and not a `contains`: 'viewModel::refresh' is a PREFIX of
        // 'viewModel::refreshRequested', so a looser rule reddens on the fix itself.
        val direct = Regex("""viewModel::refresh(?![A-Za-z])|viewModel\.refresh\(\)""")
            .findAll(text).map { it.value }.toList()
        assertEquals(
            "Every refresh the reader asks for in so many words — the pull and the two Retry " +
                "buttons — goes through refreshRequested(). A call to refresh() from the screen is " +
                "a gesture whose answer is held back by the grace. Found:\n$direct",
            emptyList<String>(), direct,
        )
    }

    @Test
    fun `the centred ring is graced by the same rule, not a second one`() {
        val text = codeText(INBOX_SCREEN)
        val hits = Regex(Regex.escape(RING_ARM_LABEL)).findAll(text).toList()
        assertEquals("InboxScreen.kt is expected to open that arm exactly once.", 1, hits.size)
        // The label plus what follows it, cut to the expected arm's length — so a mutation that
        // LENGTHENS the arm ('if (true || ringShowing)') lands inside the window and mismatches,
        // which a `contains` on the label would wave through. Same cut as the opener rule in
        // BoundedRefreshWiringLintTest.
        val at = hits.single().range.first
        val arm = text.substring(at, minOf(at + EXPECTED_RING_ARM.length, text.length))
        assertEquals(
            "The centred ring must be gated by rememberGracedIndicator. The arm CATCHES wider " +
                "than it DRAWS, deliberately: it has to keep catching (otherwise the screen falls " +
                "through to the empty/offline scenes and lies mid-fetch) while drawing nothing " +
                "during the grace, and nothing at all on a change of view — the grace's own " +
                "predicate is pinned below. Compared as the whole arm. Arm was:\n$arm",
            EXPECTED_RING_ARM,
            arm,
        )
        // Whole line, not a `contains` nor a Regex without an end anchor: `...staleRows) || true`
        // is a mutation that LENGTHENS the line, and both would wave it through.
        assertEquals(
            "The arm keeps CATCHING on refreshLoading || staleRows — letting it fall through hands " +
                "the centre to the offline/error/empty scenes while a page is still loading, and " +
                "the centre would lie. But it only DRAWS when a page really loads in the view " +
                "already on screen: refreshLoading && !staleRows. A change of view has nothing to " +
                "say to the centre — on a fresh view nothing is fetched at all, and announcing " +
                "server work that isn't happening would be untrue (#63).",
            listOf("val ringShowing = rememberGracedIndicator(refreshLoading && !staleRows)"),
            codeLines(INBOX_SCREEN).map { it.trim() }.filter { it.startsWith("val ringShowing") },
        )
        // The rule above holds the ARGUMENT; this one holds what the callee does with it.
        // Inverting `if (active)` is ONE character, it puts the centred ring back on every folder
        // switch (#63), and it leaves every other rule in this file green: the call line, the arm,
        // the two clock reads and the single startRefreshRun are all untouched by it. Swapping the
        // two branches does the same, which is why the whole decision is compared and not one line.
        val grace = declarationBody(INBOX_SCREEN, "private fun rememberGracedIndicator(active: Boolean): Boolean")
        val opens = grace.indexOf("run.value = if (")
        assertTrue("rememberGracedIndicator no longer opens its stretch with `run.value = if (`.", opens >= 0)
        // Cut to the expected length, like the arm rule above: a mutation that LENGTHENS the
        // decision lands inside the window and mismatches instead of being waved through.
        val decision = grace.substring(opens, minOf(opens + EXPECTED_GRACE_DECISION.length, grace.length))
        assertEquals(
            "rememberGracedIndicator must OPEN a stretch while its predicate holds and END it when " +
                "the predicate drops — never the other way round, and never with the branches " +
                "swapped. Inverted, the ring draws exactly when NO page is loading in the view on " +
                "screen: the centred ring on every folder switch, with no tern beside it. Compared " +
                "whole. The decision found was:\n$decision",
            EXPECTED_GRACE_DECISION,
            decision,
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    /** The body of the declaration whose header is [header], from the header to its balanced `}`. */
    private fun declarationBody(file: File, header: String): String {
        val text = codeText(file)
        val start = text.indexOf(header)
        check(start >= 0) { "${file.name} declares no '$header' — did it get renamed?" }
        return balancedFrom(text, start)
    }

    /** The single offset in [text] where [call] is applied; fails loudly on none or several. */
    private fun findOnce(text: String, call: String): Int {
        val hits = Regex("""\b${Regex.escape(call)}\s*\(""").findAll(text).toList()
        assertEquals("$call is expected to be called exactly once. Found ${hits.size}.", 1, hits.size)
        return hits.single().range.first
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
     * The WHOLE named arguments of the call whose token starts at [from]: the parenthesised list
     * split on its own top-level commas. Whole arguments, never fragments — see the class comment.
     */
    private fun argsAt(text: String, from: Int): List<String> {
        val open = text.indexOf('(', from)
        check(open >= 0) { "no argument list opens after offset $from" }
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var i = open
        while (i < text.length) {
            val c = text[i]
            when (c) {
                '(', '{', '[' -> {
                    depth++
                    if (depth > 1) current.append(c)
                }
                ')', '}', ']' -> {
                    depth--
                    if (depth >= 1) {
                        current.append(c)
                    } else {
                        args.add(current.toString().trim())
                        break
                    }
                }
                ',' -> if (depth == 1) {
                    args.add(current.toString().trim())
                    current.clear()
                } else {
                    current.append(c)
                }
                else -> if (depth >= 1) current.append(c)
            }
            i++
        }
        return args.filter { it.isNotBlank() }
    }

    /**
     * [file] as ONE line of code: comments out, runs of whitespace collapsed — so a call the
     * formatter spreads over eight lines reads the same as one written on a line. Same scanner as
     * `BoundedRefreshWiringLintTest`, including the `//` inside a string literal it must not take
     * for a comment.
     */
    /**
     * [file]'s non-blank lines, trimmed, with every comment taken out — block comments tracked
     * across lines, a `//` honoured only outside a double-quoted string. The same scanner as
     * [codeText], kept per LINE: a rule about a whole declaration needs the line's end, which the
     * collapsed form no longer has, and a `contains` on a prefix is blind to any mutation that
     * lengthens it (`elapsedRealtime` is a prefix of `elapsedRealtimeNanos`).
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
            if (code.isNotBlank()) out += code.toString()
        }
        return out
    }

    private fun codeText(file: File): String {
        val code = StringBuilder()
        var inBlockComment = false
        for (raw in file.readLines()) {
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
            code.append('\n')
        }
        return code.toString().replace(Regex("""\s+"""), " ").trim()
    }

    companion object {
        /** The opening of refresh(): the stretch is opened before anything else is written. */
        private const val EXPECTED_REFRESH_OPENING =
            "fun refresh() { refreshJob?.cancel() " +
                "val instant = refreshAnswersAGesture " +
                "refreshAnswersAGesture = false " +
                "refreshRun.value = startRefreshRun(refreshRun.value, clock(), instant) " +
                "status.value = Status(refreshing = true, error = null)"

        /** The whole of rememberGracedIndicator's open/close decision, comments out, one line. */
        private const val EXPECTED_GRACE_DECISION =
            "run.value = if (active) { " +
                "startRefreshRun(run.value, now, instant = false) " +
                "} else { " +
                "run.value?.copy(endedAt = now) " +
                "}"

        private const val RING_ARM_LABEL = "refreshLoading || staleRows ->"

        /** The only body that arm may have: it catches every time, and draws under the grace. */
        private const val EXPECTED_RING_ARM =
            "refreshLoading || staleRows -> if (ringShowing) " +
                "LoadingRing(Modifier.align(Alignment.Center)) else Unit"

        private const val END_OF_STRETCH_ANCHOR = "viewModelScope.launch { status.map"

        private const val SEED_ANCHOR = "initialValue = MailUi"

        private const val INBOX_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"
        private const val INBOX_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"

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
        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
    }
}
