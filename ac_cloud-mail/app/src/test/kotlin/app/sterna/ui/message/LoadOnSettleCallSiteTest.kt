package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — read this before trusting it.
 */
class LoadOnSettleCallSiteTest {

    /**
     * The load effect, whole and exactly once. `active` must appear TWICE on it: as the third
     */
    @Test fun `a page loads only once it is the page the reader settled on`() {
        assertEquals(
            "MessagePage's load effect must be this line, verbatim and once. ⚠ An empty " +
                "`but was []` means it is no longer in MessageScreen.kt in this form — renamed, " +
                "reordered, or lengthened. Two things break if it is: passing `true` (or " +
                "anything but `active`) as the third argument reloads both warmed neighbours of " +
                "the opened message, and on IMAP each of those loads plants the sender's " +
                "announced key in OpenKeychain with nothing opened; and dropping `active` from " +
                "the effect's KEYS leaves the effect never replayed when the page finally " +
                "settles, so the message the reader tapped stays on a spinner.",
            listOf(LOAD_EFFECT),
            screenLines().filter { it == LOAD_EFFECT },
        )
    }

    /**
     * And where that `active` comes from: the settled MESSAGE, compared by identity, pinned
     */
    @Test fun `active is the settled message compared by identity, inside the call that receives it`() {
        assertEquals(
            "the pager must hand MessagePage `active = pagerKey(entry) == settledKey,` — these " +
                "four code lines, in this order, starting at `MessagePage(`. ⛔ The mutation " +
                "this exists for is the return to a comparison of RANK — " +
                "`pagerState.settledPage == page` or `pagerState.currentPage == page`. Both " +
                "reopen G-décalage: a page number is refreshed only around a scroll, so mail " +
                "prepended under an open reader leaves it naming the neighbour ABOVE, which " +
                "then loads and, on IMAP, files that sender's key in OpenKeychain from a " +
                "message nobody opened. `currentPage == page` additionally counts a page merely " +
                "brushed past, mid-drag.",
            listOf(
                "MessagePage(",
                "emailId = entry.first,",
                "accountId = entry.second,",
                ACTIVE_ARGUMENT,
            ),
            adjacentBlock(screenLines(), "MessagePage(", 4),
        )
    }

    /**
     * The inventory, and it covers EVERY name by which this screen may put a message on a page,
     */
    @Test fun `only these three lines put a message on a page at all`() {
        assertEquals(
            "MessageScreen.kt may name a message-loading method of the page ViewModel on exactly " +
                "three lines, these, in this order: the cache warm-up, the pager's settle-gated " +
                "load, and the Retry button. Fewer means a call site was deleted; more means a " +
                "way onto the page that no rule here gates — and on IMAP a load that nobody " +
                "gated files the sender's announced key in OpenKeychain from a message nobody " +
                "opened.",
            listOf(WARM_EFFECT, LOAD_EFFECT, RETRY_CALL),
            screenLines().filter { line -> PAGE_ENTRY_POINTS.any { it in line } },
        )
    }

    /**
     * The warm-up effect, whole, and ABOVE the load effect with nothing between them.
     */
    @Test fun `the cache warm-up stands directly above the load effect`() {
        assertEquals(
            "MessagePage must open on these two adjacent code lines, in this order: the cache " +
                "warm-up, then the settle-gated load. ⚠ An empty `but was []` means the warm-up " +
                "line is no longer in MessageScreen.kt in this exact form — and then every " +
                "neighbour is back to a blank page and a ~1 s spinner at the end of each swipe, " +
                "with the rest of this file green. A line appearing between them means the " +
                "warm-up became conditional on something no rule here reads.",
            listOf(WARM_EFFECT, LOAD_EFFECT),
            adjacentBlock(screenLines(), WARM_EFFECT, 2),
        )
    }

    /**
     * THE promise this volet is allowed to exist under: the warm-up reads the LOCAL CACHE and
     */
    @Test fun `the warm-up names no fetch of any kind`() {
        assertEquals(
            "MessageViewModel.warmFromCache may not name any of $FETCHING_NAMES. Each of them " +
                "goes to the server for a body, and on IMAP the fetched source deposits the " +
                "sender's announced key in OpenKeychain, irreversibly, for a message the reader " +
                "has not swiped to. The warm-up is allowed to exist ONLY because it reads " +
                "`repo.cachedMessage` and stops when the cache is empty.",
            emptyList<String>(),
            warmBodyLines().filter { line -> FETCHING_NAMES.any { it in line } },
        )
    }

    /**
     * RISK Nº 1 OF THE VOLET, and it loses the message silently. `loadedId` is what
     */
    @Test fun `only load writes the identity that says a message is already loaded`() {
        assertEquals(
            "MessageViewModel.kt may assign `loadedId` on exactly one line: `load`'s own. A " +
                "second assignment — the cache warm-up marking the page loaded — makes " +
                "MessagePaging.needsLoad answer `false` for a message that was never fetched: " +
                "settling on it loads NOTHING and the page stays blank forever. Every other rule " +
                "in this file, and MessagePagingTest, stay green.",
            listOf(LOADED_ID_WRITE),
            bindingsOf(viewModelLines(), "loadedId"),
        )
    }

    /**
     * The middle link of the warm-up, and the only place the decision becomes a refusal.
     */
    @Test fun `the warm-up asks the decision with the cached body's own values, and obeys it`() {
        assertEquals(
            "MessageViewModel.warmFromCache must consult MessagePaging.warmable on exactly these " +
                "code lines, adjacent and in this order, and RETURN on a refusal. ⚠ An empty " +
                "`but was []` means the call was renamed, moved or reshaped. What the damage is, " +
                "one line at a time. Without `if (!warmable) return@launch` the answer is " +
                "computed and dropped: OpenPGP armour prints in clear on a page nobody swiped " +
                "to, and a body from the JMAP prefetch (`inlineImages = emptyMap()`) is painted " +
                "with its holes and RELOADS under the finger. Without the sender argument — " +
                "lower-cased, against the allowlist as MessageScreen compares it (:1612, :1618) " +
                "— an allowed sender's body is warmed on an unswiped page. ⚠ NOT a pixel: the " +
                "`everActive` latch (MessageScreen.kt:671-674) holds `senderAllowed` false until " +
                "the finger lands, and that latch, not this argument, is what keeps " +
                "PRIVACY.md:288 — the lints below are the ones that guard it. What is lost here " +
                "is comfort: at the settle `blockRemote` flips, the body reloads under the " +
                "finger in the white flash the warm-up exists to remove, and the images strip " +
                "memoised present on the warm body (MessageScreen.kt:1887) stays on screen, " +
                "dead. And `repo.cryptoKindOf` is the single source of PGP detection " +
                "(⛔ never re-derived in the UI layer), while the two counts are exactly what " +
                "`MailRepository.ensureInlineImages` reads to decide it has nothing to download.",
            WARMABLE_CALL,
            adjacentBlock(viewModelLines(), WARMABLE_CALL.first(), WARMABLE_CALL.size),
        )
    }

    /**
     * THE LINK BELOW `repo.cryptoKindOf(` — the rule above pins that the warm-up ASKS the
     */
    @Test fun `cryptoKindOf still hands the body to the executed inline-armour decision`() {
        assertEquals(
            "MailRepository.cryptoKindOf must hold exactly these code lines, adjacent and in " +
                "this order. ⚠ An empty `but was []` means the declaration line changed or is " +
                "no longer unique. The last one is the whole point: it is the ONLY edge between " +
                "the warm-up's crypto question and `inlineArmorKind`, the one function a JVM " +
                "bench can execute (InlineArmorKindTest). Replace it with `return null`, or " +
                "hand it anything other than the message's own decoded text, and a page the " +
                "finger never reached paints PGP armour in clear with no decrypt offered, while " +
                "every executed test in the repository stays green. The two lines above it are " +
                "pinned in the same block because the block is what makes a DELETION visible: " +
                "drop the `application/pgp-signature` test and a signed message becomes " +
                "warmable. ⚠ READ THE TWO HALVES APART: only the LAST line's decision is " +
                "executed anywhere (`inlineArmorKind`, run by InlineArmorKindTest in " +
                "core/imap). The two `parts.any { … }` tests are PINNED AS TEXT and nothing " +
                "runs them — `MailRepository` needs Room and an Android context, and extracting " +
                "them was ruled out for this volet — so their MIME type literals are guarded by " +
                "this string comparison and by nothing else.",
            CRYPTO_KIND_OF,
            adjacentBlock(repositoryLines(), CRYPTO_KIND_OF.first(), CRYPTO_KIND_OF.size),
        )
    }

    /**
     * The same six lines read the other way round — as an INVENTORY of the function, from its
     */
    @Test fun `cryptoKindOf holds these three decisions and nothing else`() {
        assertEquals(
            "MailRepository.cryptoKindOf must contain these code lines and NO OTHERS: the two " +
                "MIME part tests, in that order, and the hand-off to `inlineArmorKind`. ⚠ A " +
                "`but was` naming a sentinel means the function was renamed or removed, and the " +
                "warm-up's `repo.cryptoKindOf(cached.email)` argument pin is what to read next. " +
                "Anything EXTRA here is the danger this rule exists for: a line that answers " +
                "before the three decisions do returns `null` for an armoured body, and " +
                "`MessagePaging.warmable` then warms a crypto message onto a page nobody opened.",
            CRYPTO_KIND_OF,
            cryptoKindLines(),
        )
    }

    /**
     * WHAT THE WARM-UP ACTUALLY PAINTS — the lines that sit BETWEEN two pinned blocks (the
     */
    @Test fun `the warm-up paints the cached body whole, and marks the page warmed`() {
        assertEquals(
            "MessageViewModel.warmFromCache must end on exactly these code lines, adjacent and " +
                "in this order. ⚠ An empty `but was []` means the block was reshaped. `body = " +
                "null` or `inlineImages = emptyMap()` there: the neighbour is painted without a " +
                "WebView, or with the prefetch's holes, and the ~1 s swipe hourglass comes back " +
                "(without even a spinner, since the page is still marked warmed) or the body " +
                "reloads under the finger. `warmedId`/`warmedAccountId` not written: `warm` is " +
                "false at the settle and `load` erases the body it was meant to keep. And the " +
                "`return@launch` gone: the warm body is painted over a fetch already in flight. " +
                "And it must END there, closing braces included: a line AFTER the painted block " +
                "— `runCatching { repo.setRead(credentials, emailId, true) }` compiles fine " +
                "there — marks a neighbour nobody opened as read on the server, which this " +
                "function's KDoc promises never happens.",
            WARM_TAIL,
            tailFrom(warmBodyLines(), WARM_PAINT.first()),
        )
        assertEquals(
            "MessageViewModel.kt may name `warmedId` on exactly these three code lines: the " +
                "field, the warm-up's set, and the `warm` binding that reads it. Fewer means the " +
                "mark is gone and every warmed body is erased the instant the finger lands; more " +
                "is a second writer — `warmedId = null` in a lambda, a reset in load()'s " +
                "prologue — which the adjacency rule above cannot see.",
            WARMED_ID_MENTIONS,
            mentionsOf(viewModelLines(), "warmedId"),
        )
        assertEquals(
            "…and `warmedAccountId` on exactly these three. Dropping this half alone is the " +
                "mutation that ships: it works in a single-account setup and hands the OTHER " +
                "account's warmed body to the same message id in the UNIFIED inbox (#92).",
            WARMED_ACCOUNT_ID_MENTIONS,
            mentionsOf(viewModelLines(), "warmedAccountId"),
        )
    }

    /**
     * THE VOLET ITSELF, and without these two blocks it corrects nothing at all. `load` erased
     */
    @Test fun `load keeps a body the warm-up put there, for this account and this id`() {
        assertEquals(
            "`warm` must be bound on exactly this line. Bound on the id alone, a neighbour from " +
                "another account of the same server keeps the wrong body (#92); bound to `false`, " +
                "the warm-up is erased the moment the finger lands and the swipe spinner returns.",
            listOf(WARM_BINDING),
            bindingsOf(viewModelLines(), "warm"),
        )
        assertEquals(
            "load()'s prologue must guard its two erasing lines with `if (!warm) {`. Unguarded, " +
                "the settle wipes the warmed body and the WebView leaves composition exactly " +
                "when the reader arrives — the whole defect, back, with this file otherwise green.",
            PROLOGUE_ERASURE,
            adjacentBlock(viewModelLines(), PROLOGUE_ERASURE.first(), PROLOGUE_ERASURE.size),
        )
        assertEquals(
            "load()'s cached-row repaint must be guarded the same way: it rewrites `_messages` " +
                "HEADER-ONLY (body = null), which drops the WebView just as surely as the " +
                "prologue does. ⛔ Only these two lines are conditional — the mailbox role, the " +
                "own-message flag, the delivered-to address, the cover subject and the mailbox id " +
                "below them must stay unconditional.",
            REPAINT_ERASURE,
            adjacentBlock(viewModelLines(), REPAINT_ERASURE.first(), REPAINT_ERASURE.size),
        )
    }

    /**
     * T4 — an inventory proves a call EXISTS, never that it is REACHED. Wrapping the load
     */
    @Test fun `the load effect stands unguarded, next to the read-marking effect`() {
        assertEquals(
            "MessagePage's two effects must be these three adjacent code lines, in this order. A " +
                "line appearing between them — an `if (…) {`, a `when`, a `remember` — means the " +
                "load is now conditional on something no rule in this file reads: either the " +
                "message never loads, or it loads on a page the reader never settled on, which " +
                "on IMAP deposits that sender's key in OpenKeychain untouched. ⛔ The third line " +
                "is the read-marking relay, and it must forward `active` ITSELF: with " +
                "`onActiveChanged(true)` a page stays \"active\" after the reader has swiped off " +
                "it — the message she LEFT is marked read at the server, `settleMarking` is " +
                "written, and `maybeOfferReadReceipt()` puts the read-receipt question for a " +
                "message she never stopped on, which SECURITY.md forbids in those words. Loading " +
                "and marking read must stay the SAME flag.",
            listOf(LOAD_EFFECT, "LaunchedEffect(active) {", "viewModel.onActiveChanged(active)"),
            adjacentBlock(screenLines(), LOAD_EFFECT, 3),
        )
    }

    /**
     * T3 — and a pin on a NAME is worth nothing without a pin on what that name is BOUND to.
     */
    @Test fun `the whole screen binds the name active exactly twice, and to these`() {
        assertEquals(
            "MessageScreen.kt may bind `active` on exactly these two lines: the pager's argument " +
                "(the settled-page test) and the chrome's `val active = activeMessage`. A THIRD " +
                "binding is how the settle gate is defeated without touching a single pinned " +
                "line — `val active = true` above MessagePage's effects shadows the parameter, " +
                "Kotlin merely warns, and both neighbours of the opened message load and import " +
                "their sender's key again.",
            listOf(ACTIVE_ARGUMENT, "val active = activeMessage"),
            bindingsOf(screenLines(), "active"),
        )
    }

    /**
     * T1 and T2 — the MIDDLE LINK, which nothing watched: the screen passes `active`, the pure
     */
    @Test fun `the ViewModel forwards the page's own settled and failed to the decision`() {
        assertEquals(
            "MessageViewModel.load must open on exactly these four code lines. ⛔ Read `but was` " +
                "closely: `needsLoad(true, …)` re-arms the unopened key import on both warmed " +
                "neighbours, and `val failed = false` makes the Retry button do nothing at all — " +
                "both leave MessagePagingTest and every source rule in this file green, because " +
                "neither the pure function nor MessageScreen.kt changes.",
            listOf(
                LOAD_DECLARATION,
                FAILED_BINDING,
                LOAD_GUARD,
                "return",
            ),
            adjacentBlock(viewModelLines(), LOAD_DECLARATION, 4),
        )
    }

    /** And the decision is consulted THERE and nowhere else in the ViewModel: a second call, on
     *  another path, is a load this file's reasoning does not cover. */
    @Test fun `the ViewModel asks the decision on exactly one line`() {
        assertEquals(
            "MessageViewModel.kt may name `MessagePaging.needsLoad(` on exactly one line, the " +
                "guard of `load`. A second is another load path, gated by arguments nobody read.",
            listOf(LOAD_GUARD),
            viewModelLines().filter { "MessagePaging.needsLoad(" in it },
        )
    }

    /**
     * G-décalage, 2026-09-04 — the stale index must not be named ANYWHERE in the screen.
     */
    @Test fun `the screen never names the stale settled page index`() {
        assertEquals(
            "MessageScreen.kt may not name `pagerState.settledPage` on any line. It is an INDEX, " +
                "refreshed only around a scroll: when new mail is prepended under an open " +
                "reader the pager re-anchors onto the same message (its pages are keyed by " +
                "identity) while that index keeps its old number, which now names the neighbour " +
                "ABOVE. That neighbour's page believes itself settled and LOADS — on IMAP a " +
                "load files the sender's announced key in OpenKeychain, from a message nobody " +
                "opened. Hold the settled page as the MESSAGE, via MessagePaging.settledEntry.",
            emptyList<String>(),
            screenLines().filter { "pagerState.settledPage" in it },
        )
    }

    /**
     * And the replacement, `currentPage`, is a live index too: it moves as soon as a drag
     */
    @Test fun `currentPage is read on exactly these lines, and nowhere else`() {
        assertEquals(
            "MessageScreen.kt may read `pagerState.currentPage` on exactly these lines, in this " +
                "order: the settled entry's initial value (the message the reader opened on, " +
                "which must load), the snapshot pair that carries it with " +
                "`isScrollInProgress`, the page number handed to the reading pane's anchor " +
                "(#103), and the position line's counter. A read MORE, outside the " +
                "`isScrollInProgress` guard, makes a page merely brushed past count as settled: " +
                "it loads, marks itself read, and on IMAP imports its sender's key. A read " +
                "FEWER means the settled page is no longer tracked at all.",
            CURRENT_PAGE_READS,
            screenLines().filter { "pagerState.currentPage" in it },
        )
    }

    /**
     * The middle link on THIS side: what is handed to the pure function. `settledEntry` is
     * green against every argument, so the rule that matters is which entry travels into it.
     */
    @Test fun `the settled entry is held from the page at rest, by identity`() {
        assertEquals(
            "these four code lines, adjacent and in this order, hold the settled page. ⛔ It is " +
                "`entryAt(page)` — the entry at the page the pager came to REST on — that " +
                "carries the identity into the decision. Re-pointing it at " +
                "`entryAt(pagerState.settledPage)` reopens G-décalage with " +
                "MessagePaging.settledEntry still green, because the pure function only ever " +
                "sees the entry it is given. Dropping `scrolling` (or passing `false`) makes a " +
                "page brushed past mid-gesture settle, which is what `settledPage` used to buy.",
            SETTLED_HOLD,
            adjacentBlock(screenLines(), SETTLED_HOLD.first(), SETTLED_HOLD.size),
        )
    }

    /**
     * And the key the pages compare themselves against is bound ONCE, to the settled entry.
     */
    @Test fun `settledKey is bound exactly once, to the settled entry`() {
        assertEquals(
            "MessageScreen.kt may bind `settledKey` exactly once, to `settledEntry?.let" +
                "(::pagerKey)`. Bound to anything else — or a second time, shadowing it inside " +
                "the page lambda — every page compares equal to itself, becomes active, loads, " +
                "and on IMAP deposits its sender's key in OpenKeychain: exactly the state this " +
                "file exists to prevent, with every other rule in it still green.",
            listOf(SETTLED_KEY),
            bindingsOf(screenLines(), "settledKey"),
        )
    }

    /**
     * The identity itself, body included. Before this volet `active` compared two `Int`s and
     */
    @Test fun `the pager's identity is the account and the id, not the id alone`() {
        assertEquals(
            "`pagerKey` must be these two code lines, adjacent and in this order. ⛔ Its body is " +
                "now what the entire load decision rests on. Drop the account from it — " +
                "`entry.second` → `null`, or a bare `entry.first` — and two messages held by two " +
                "accounts of one server under the same id collapse into ONE identity again " +
                "(#92): the neighbour compares equal to the settled message, becomes active, " +
                "loads, and on IMAP files its sender's announced key in OpenKeychain under an " +
                "address nobody opened a message from.",
            listOf(PAGER_KEY_DECLARATION, PAGER_KEY_BODY),
            adjacentBlock(screenLines(), PAGER_KEY_DECLARATION, 2),
        )
    }

    /**
     * The `scrolling` guard can be undone from OUTSIDE the pure function, and every test of
     */
    @Test fun `the settled entry is written on exactly one line`() {
        assertEquals(
            "MessageScreen.kt may assign `settledEntry` on exactly one line: the decision inside " +
                "the collect. A SECOND write in that block — `settledEntry = entryAt(page)` " +
                "after it — defeats the `scrolling` guard from outside the pure function, which " +
                "stays green because it is still called and still answers correctly; its answer " +
                "is simply thrown away. A page flicked past then settles, loads, marks itself " +
                "read, and on IMAP deposits its sender's key in OpenKeychain.",
            listOf(SETTLED_WRITE),
            bindingsOf(screenLines(), "settledEntry"),
        )
    }

    /**
     * The SAME flag, spent on the other thing a composed-but-never-opened page can do: fetch
     */
    @Test fun `the neighbour's allowlist answer is latched to a page the reader has settled on`() {
        assertEquals(
            "MessageScreen.kt may bind `senderAllowed` on exactly these three lines, in this " +
                "order: the fixed chrome's (MessageActions only ever holds the SETTLED page's " +
                "ViewModel), the page's own — WHICH MUST OPEN ON THE `everActive &&` LATCH — and " +
                "the argument handed down to ConversationBody. Drop `everActive &&` and a " +
                "\"Always show images from this sender\" tapped on the open message makes the " +
                "WARMED neighbour, never opened, let its remote pictures out: its own " +
                "`imageAllowlist` collection recomposes, `showRemote` flips, the body is re-keyed " +
                "and reloaded, and `shouldInterceptRequest` stops blocking — the tracking pixel " +
                "of a message nobody opened is fetched, which PRIVACY.md:288 promises will never " +
                "happen. ⛔ Gated on `blockRemote` instead of on this line, the printer keeps its " +
                "own ungated copy. A FOURTH binding is the same defeat by shadowing that " +
                "`active` is pinned against above.",
            listOf(SENDER_ALLOWED_CHROME, SENDER_ALLOWED_LATCHED, SENDER_ALLOWED_WIRING),
            bindingsOf(screenLines(), "senderAllowed"),
        )
    }

    /**
     * And the latch itself, which the rule above only names. An inventory of one line proves a
     */
    @Test fun `the latch is one-way, and set on exactly one line`() {
        assertEquals(
            "MessagePage must carry the latch as these three adjacent code lines, in this order: " +
                "the remembered `false` seed, the `if (active) {` guard, and the set. ⚠ An empty " +
                "`but was []` means the declaration is no longer in MessageScreen.kt in this " +
                "exact form. Seeded `true`, or with the guard removed, the latch is decorative: " +
                "the warmed neighbour is allowed from the first composition and lets the " +
                "sender's pixel out for a message nobody opened. ⛔ And it must be a LATCH, not " +
                "`!active`: `settledEntry` is null at rest, so a bare `active` reading would " +
                "block the images of the page being READ, re-key its document and reload it " +
                "under the reader, scroll position lost.",
            LATCH_BLOCK,
            adjacentBlock(screenLines(), LATCH_DECLARATION, 3),
        )
        assertEquals(
            "…and `everActive` may be assigned on exactly these two lines: the set inside the " +
                "guard, and the argument handed to MessageContent. A THIRD assignment — " +
                "`val everActive = true` above the effects, shadowing the latch exactly as " +
                "`val active = true` shadows the settle flag — leaves the adjacency rule above " +
                "and every text pin in this file green while every warmed neighbour is allowed " +
                "again. Kotlin only warns about the shadowing, and nothing in this build turns " +
                "that warning into a failure.",
            LATCH_BINDINGS,
            bindingsOf(screenLines(), "everActive"),
        )
    }

    /**
     * And the same latch again, counted this time by every line that so much as NAMES it,
     */
    @Test fun `the latch is named on exactly six lines, wherever the name sits`() {
        assertEquals(
            "MessageScreen.kt may NAME `everActive` on exactly these six code lines, in this " +
                "order: the remembered `false` seed, the set under the `if (active)` guard, the " +
                "argument handed to MessageContent, the parameter it lands on, and the TWO " +
                "answers it gates — `manualShow` and `senderAllowed`. A SEVENTH mention is a " +
                "second door onto the latch " +
                "— `LaunchedEffect(emailId) { everActive = true }`, `.also { everActive = true }`, " +
                "a one-line `if (…) everActive = true` — and every one of them arms the latch " +
                "without the reader's finger ever landing on the page: the WARMED neighbour, a " +
                "message she never opened, answers `senderAllowed = true`, re-keys and reloads " +
                "its body with remote content allowed, and `shouldInterceptRequest` hands the " +
                "sender's tracking pixel the fact that it was opened. PRIVACY.md:288 promises " +
                "the opposite. ⚠ A SHORTER list than six means the latch was renamed or torn " +
                "out, which is the same fetch by another route.",
            LATCH_MENTIONS,
            mentionsOf(screenLines(), "everActive"),
        )
    }

    /**
     * THE SECOND PRODUCER of the very same answer, latched on the same flag. `showRemoteImages`
     */
    @Test fun `the neighbour's manual show-images answer is latched too`() {
        assertEquals(
            "MessageContent must read the per-message override into `manualShowRaw` and hand the " +
                "body `everActive && manualShowRaw`, on exactly these two code lines in this " +
                "order. ⚠ An empty or short `but was` means one of them is gone from " +
                "MessageScreen.kt in this exact form. Without the latch, a \"Show images\" " +
                "raised on a page the reader never settled on stays raised — `load()` holds the " +
                "only reset and never runs there — `showRemote` flips on the `manualShow` half " +
                "alone, the body is re-keyed and reloaded with remote content allowed, and " +
                "`shouldInterceptRequest` lets the sender's tracking pixel out for a message " +
                "NOBODY OPENED. PRIVACY.md:288 promises the opposite. ⛔ And the RAW line is " +
                "pinned beside it because the origin of a value is pinned by nothing else here: " +
                "`val manualShowRaw = true` in place of the flow read leaves every other rule in " +
                "this file green. ⛔ The latch belongs on these `val`s, never on the " +
                "`showRemoteImages(imageMode, manualShow, senderAllowed)` call — ReaderBodyTest " +
                "counts that line whole and demands exactly two of it.",
            MANUAL_SHOW_LATCH,
            mentionsOf(screenLines(), "manualShowRaw"),
        )
    }

    // --- reading the source ----------------------------------------------------------------------

    private fun screenLines(): List<String> = codeLines(screen().readText())

    private fun viewModelLines(): List<String> = codeLines(viewModel().readText())

    /**
     * The code lines of `fun warmFromCache(…)` only, from its declaration to the next member
     */
    private fun warmBodyLines(): List<String> {
        val text = viewModel().readText()
        val start = text.indexOf("fun warmFromCache(")
        // Named after a forbidden call on purpose: a vanished warm-up must redden the rule
        // that reads this, not satisfy it against an empty list.
        if (start < 0) return listOf("warmFromCache is gone from MessageViewModel.kt: openMessage(")
        val end = NEXT_MEMBER.find(text, start + 1)?.range?.first ?: text.length
        return codeLines(text.substring(start, end))
    }

    /**
     * Every code line of [lines] that BINDS the name [name] — a `val`/`var` declaration, a bare
     */
    private fun bindingsOf(lines: List<String>, name: String): List<String> =
        lines.filter { Regex("""^(val\s+|var\s+)?$name\s*=(?!=)""").containsMatchIn(it) }

    /**
     * Every code line of [lines] that MENTIONS [name] as a whole word, wherever it sits on the
     */
    private fun mentionsOf(lines: List<String>, name: String): List<String> =
        lines.filter { Regex("""\b""" + Regex.escape(name) + """\b""").containsMatchIn(it) }

    /**
     * [text]'s lines as CODE: every COMPLETE block comment removed from wherever it sits on the
     */
    private fun codeLines(text: String): List<String> = text.lines()
        .map { WHITESPACE.replace(BLOCK_COMMENT.replace(it, " "), " ").trim() }
        .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /**
     * [count] lines starting at the single line equal to [needle]. Empty when [needle] is not
     */
    private fun adjacentBlock(lines: List<String>, needle: String, count: Int): List<String> {
        val at = lines.indices.filter { lines[it] == needle }.singleOrNull() ?: return emptyList()
        return if (at + count > lines.size) emptyList() else lines.subList(at, at + count)
    }

    /**
     * Every code line of [lines] from the single line equal to [needle] to the LAST one — the
     */
    private fun tailFrom(lines: List<String>, needle: String): List<String> {
        val at = lines.indices.filter { lines[it] == needle }.singleOrNull() ?: return emptyList()
        return lines.subList(at, lines.size)
    }

    /**
     * The code lines of `fun cryptoKindOf(…)` only, declaration included, from its declaration to
     * the next member declared at class indentation.
     */
    private fun cryptoKindLines(): List<String> {
        val text = repository().readText()
        val start = text.indexOf("fun cryptoKindOf(")
        // Sentinel rather than an empty list, for the reason warmBodyLines() states: a vanished
        // function must redden the rule that reads it, not satisfy it against nothing.
        if (start < 0) return listOf("cryptoKindOf is gone from MailRepository.kt")
        val end = NEXT_MEMBER.find(text, start + 1)?.range?.first ?: text.length
        return codeLines(text.substring(start, end))
    }

    private fun repositoryLines(): List<String> = codeLines(repository().readText())

    private fun screen(): File = locate("app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")

    /**
     * Another module's source, read by path on purpose. The rule below it lives HERE rather
     */
    private fun repository(): File =
        locate("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")

    private fun viewModel(): File =
        locate("app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")

    /** [relative] resolved from the test's working directory, walking up. */
    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("app/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val LOAD_EFFECT =
            "LaunchedEffect(emailId, accountId, active) { viewModel.load(emailId, accountId, active) }"

        const val WARM_EFFECT =
            "LaunchedEffect(emailId, accountId) { viewModel.warmFromCache(emailId, accountId) }"

        /** Every name by which MessageScreen.kt may put a message on a page's ViewModel. */
        val PAGE_ENTRY_POINTS = listOf("viewModel.load(", "viewModel.warmFromCache(")

        /**
         * A DENYLIST OF KNOWN NAMES — repository entry points that reach the SERVER for a body,
         */
        val FETCHING_NAMES = listOf(
            "openMessage(",
            "openEmail(",
            "openEmailImap(",
            "fetchEmail(",
            "ensureInlineImages(",
            "rawSource(",
            "rawHeaders(",
            "decryptMessage(",
            "downloadAttachment(",
            "refresh(",
            "refreshAllInboxes(",
            "notificationPreview(",
        )

        const val LOADED_ID_WRITE = "loadedId = emailId"

        const val WARM_BINDING = "val warm = warmedId == emailId && warmedAccountId == accountId"

        /**
         * The tail of `warmFromCache`: the re-check, the state, the thread row with its BODY and
         * its inline images, and the two fields that say "there is a body on this page already".
         */
        val WARM_PAINT = listOf(
            "if (loadedId != null) return@launch",
            "_state.value = MessageState.Loaded(cached.email)",
            "_messages.value = listOf(",
            "ThreadMessage(",
            "id = cached.email.id,",
            "header = cached.email,",
            "body = cached.email,",
            "inlineImages = cached.inlineImages,",
            "),",
            ")",
            "warmedId = emailId",
            "warmedAccountId = accountId",
        )

        /**
         * …and the two braces that close the coroutine and the function, which is what makes the
         */
        val WARM_TAIL = WARM_PAINT + listOf("}", "}")

        /** Every line of the ViewModel that may so much as NAME the warm mark: field, set, read. */
        val WARMED_ID_MENTIONS = listOf(
            "private var warmedId: String? = null",
            "warmedId = emailId",
            WARM_BINDING,
        )

        val WARMED_ACCOUNT_ID_MENTIONS = listOf(
            "private var warmedAccountId: String? = null",
            "warmedAccountId = accountId",
            WARM_BINDING,
        )

        /**
         * The consultation of the pure warm/no-warm decision, argument by argument — AND the line
         */
        val WARMABLE_CALL = listOf(
            "val warmable = MessagePaging.warmable(",
            "cryptoKind = repo.cryptoKindOf(cached.email),",
            "inlineParts = cached.email.inlineImageParts().size,",
            "cachedInlineImages = cached.inlineImages.size,",
            "senderAllowedRemoteImages =",
            "cached.email.from.firstOrNull()?.email?.lowercase()?.let { it in settings.imageAllowlist.first() } == true,",
            ")",
            "if (!warmable) return@launch",
        )

        /** load()'s prologue erasure, anchored on the unique line above it. */
        val PROLOGUE_ERASURE = listOf(
            "settleMarking = null",
            "if (!warm) {",
            "_state.value = MessageState.Loading",
            "_messages.value = emptyList()",
        )

        /** load()'s header-only repaint, anchored on the unique line that opens it. */
        val REPAINT_ERASURE = listOf(
            "listEmail?.let { cached ->",
            // #1869: nor when the local snapshot holds the body, which paints the page once, below.
            "if (!warm && localBody == null) {",
            "_messages.value = listOf(ThreadMessage(id = cached.id, header = cached))",
            "_state.value = MessageState.Loaded(cached)",
        )

        /** The next member declared at class indentation, which is where a function ends. */
        val NEXT_MEMBER = Regex("""\n {4}(private )?(suspend )?fun \w""")

        /**
         * `MailRepository.cryptoKindOf`, whole. Arguments included: the value handed to
         */
        val CRYPTO_KIND_OF = listOf(
            "fun cryptoKindOf(email: Email): CryptoKind? {",
            "val parts = email.attachments",
            "if (parts.any { it.type == \"application/pgp-encrypted\" }) return CryptoKind.PGP_ENCRYPTED",
            "if (parts.any { it.type == \"application/pgp-signature\" }) return CryptoKind.PGP_SIGNED",
            "return inlineArmorKind(email.textContent().orEmpty())",
            "}",
        )

        /**
         * The page's own allowlist answer, latched. Whole line, arguments included: `in`
         * would be blind to `everActive || …`, which is the mutation that reads as a fix.
         */
        const val SENDER_ALLOWED_LATCHED =
            "val senderAllowed = everActive && senderEmail?.lowercase()?.let " +
                "{ it in imageAllowlist } == true"

        /** The fixed chrome's copy, ungated on purpose: it is handed the settled page only. */
        const val SENDER_ALLOWED_CHROME =
            "val senderAllowed = senderEmail?.lowercase()?.let { it in imageAllowlist } == true"

        /** …and the same answer handed down to the body that renders and prints it. */
        const val SENDER_ALLOWED_WIRING = "senderAllowed = senderAllowed,"

        const val LATCH_DECLARATION = "var everActive by remember { mutableStateOf(false) }"

        /** Declared `false`, set only under the settle flag: the three lines, adjacent. */
        val LATCH_BLOCK = listOf(LATCH_DECLARATION, "if (active) {", "everActive = true")

        /** Every line that may ASSIGN the latch — the set, and the argument it is handed on. */
        val LATCH_BINDINGS = listOf("everActive = true", "everActive = everActive,")

        /**
         * Every line that may so much as NAME the latch, in source order: seed, set, argument,
         */
        val LATCH_MENTIONS = listOf(
            LATCH_DECLARATION,
            "everActive = true",
            "everActive = everActive,",
            "everActive: Boolean,",
            MANUAL_SHOW_LATCHED,
            SENDER_ALLOWED_LATCHED,
            // #1837: the third producer of the remote-image answer, the "Load remote images" setting, latched like the other two.
            AUTO_LOAD_LATCHED,
        )

        const val AUTO_LOAD_LATCHED = "val autoLoad = everActive && autoLoadRaw"

        /**
         * The page's SECOND producer of the remote-image answer, at its origin and latched.
         */
        const val MANUAL_SHOW_RAW =
            "val manualShowRaw by viewModel.manualShowImages.collectAsStateWithLifecycle()"

        const val MANUAL_SHOW_LATCHED = "val manualShow = everActive && manualShowRaw"

        val MANUAL_SHOW_LATCH = listOf(MANUAL_SHOW_RAW, MANUAL_SHOW_LATCHED)

        const val ACTIVE_ARGUMENT = "active = pagerKey(entry) == settledKey,"

        const val SETTLED_KEY = "val settledKey = settledEntry?.let(::pagerKey)"

        const val SETTLED_WRITE =
            "settledEntry = MessagePaging.settledEntry(settledEntry, entryAt(page), scrolling)"

        const val PAGER_KEY_DECLARATION =
            "private fun pagerKey(entry: Pair<String, String?>): String ="

        const val PAGER_KEY_BODY = "MessagePaging.entryKey(entry.first, entry.second)"

        /** The four code lines that hold the settled page, from the effect that opens them. */
        val SETTLED_HOLD = listOf(
            "LaunchedEffect(pagerState) {",
            "snapshotFlow { pagerState.isScrollInProgress to pagerState.currentPage }",
            ".collect { (scrolling, page) ->",
            SETTLED_WRITE,
        )

        /** Every read of `pagerState.currentPage` in the file, in source order. */
        val CURRENT_PAGE_READS = listOf(
            "var settledEntry by remember { mutableStateOf(entryAt(pagerState.currentPage)) }",
            "snapshotFlow { pagerState.isScrollInProgress to pagerState.currentPage }",
            "else onPageSettled?.invoke(settled.first, settled.second, pagerState.currentPage)",
            "page = pagerState.currentPage,",
        )

        const val RETRY_CALL = "Button(onClick = { viewModel.load(emailId, accountId, settled = true) }) {"

        const val LOAD_DECLARATION = "fun load(emailId: String, accountId: String?, settled: Boolean) {"

        const val FAILED_BINDING = "val failed = _state.value is MessageState.Error"

        const val LOAD_GUARD =
            "if (!MessagePaging.needsLoad(settled, loadedId, this.accountId, emailId, accountId, failed)) {"

        /** A block comment that OPENS AND CLOSES on the same line — the only kind that can sit
         *  beside code, and therefore the only kind [codeLines] may remove rather than obey. */
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""")

        val WHITESPACE = Regex("""\s+""")
    }
}
