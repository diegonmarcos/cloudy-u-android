package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE AND RESOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 */
class RelayAddressRowWiringTest {

    // -- where the block sits ------------------------------------------------------------------

    @Test fun `the relay address block sits inside both guards`() {
        val ancestors = ancestorsOf(RELAY_CALL)
        val missing = REQUIRED_GUARDS.filterNot { it in ancestors }
        assertEquals(
            "the relay address block must be written inside BOTH guards. Without " +
                "'if (notificationsEnabled) {' it offers an address on an account that fetches " +
                "nothing: the relay posts and no mail ever arrives, with the address on screen " +
                "saying otherwise. Without 'if (isImap) {' it is offered to a JMAP account, which " +
                "holds a PushSubscription and needs no relay at all. Missing: " +
                missing.joinToString(", ") + "\nBlocks actually enclosing the call:\n" +
                ancestors.joinToString("\n"),
            emptyList<String>(),
            missing,
        )
    }

    // -- the four states of the block ----------------------------------------------------------

    @Test fun `the block asks for a distributor before it offers anything`() {
        val branches = relaySectionBody().filter { it.startsWith("if (") || it.startsWith("} else") }
        assertEquals(
            "the address block must branch exactly like this, in this order and compared whole. " +
                "The distributor check comes FIRST: with no UnifiedPush app installed there is " +
                "nothing that could ever hand us an address, and a 'Get an address' button that " +
                "does nothing is precisely the screen lie this volet removes. The published-address " +
                "branch comes before the requested one, or an account that already has its address " +
                "reads 'Waiting for an address…' forever.",
            EXPECTED_BRANCHES,
            branches,
        )
    }

    @Test fun `every state of the block offers a way out of itself`() {
        val branches = relayBranches()
        assertEquals(
            "the block must have exactly four states; this rule is about what each of them lets " +
                "the reader DO, and it cannot ask that of a shape it does not recognise.",
            4,
            branches.size,
        )
        assertEquals(
            "each state of the block must offer the action that leaves it, and these are the " +
                "string keys that name them. The one that matters is the third: 'Waiting for an " +
                "address…' can be FINAL. For an account outside the watched set (push-all off and " +
                "not the current account) the only caller of ensureRegistered is this very tap — " +
                "PushController.apply and MailFetchWorker both filter on the watched set — so a " +
                "registration that fails, or a distributor picker the user dismisses, leaves that " +
                "sentence on screen with no button under it and no second attempt, ever. " +
                "'Remove address' is the way out: it puts the account back to where it started " +
                "and lets the tap happen again. No new string for it — the existing key. The " +
                "first state is the only dead end allowed, and only because nothing installed can " +
                "do anything about it from here. Found:\n" +
                branches.joinToString("\n") { (head, _) -> head },
            EXPECTED_BRANCH_ACTIONS,
            relayKeysPerBranch(),
        )
        val buttonless = branches.drop(1).filterNot { (_, body) -> body.any { it.startsWith("TextButton(") } }
        assertEquals(
            "and each of those three states must draw the button itself, not merely name its " +
                "string: a key used in a Text() is a sentence, not a way out. States without a " +
                "TextButton:\n" + buttonless.joinToString("\n") { (head, _) -> head },
            emptyList<String>(),
            buttonless.map { (head, _) -> head },
        )
    }

    @Test fun `the warning about handing the address out stays on screen with the address`() {
        assertEquals(
            "settings_push_relay_explain must be drawn in TWO places, and these are the whole " +
                "lines: next to the address itself, and in the state where none has been asked " +
                "for. It said 'a relay that has this address can make Sterna check for mail, it " +
                "cannot read your mail' — and it disappeared at the exact moment it matters, when " +
                "the address is on screen and about to be pasted into somebody else's app. Lines " +
                "found:\n" + relaySectionBody().filter { "relay_explain" in it }.joinToString("\n"),
            EXPECTED_EXPLAIN_LINES,
            relaySectionBody().filter { "settings_push_relay_explain" in it },
        )
    }

    @Test fun `the block re-reads itself while the screen is open`() {
        assertEquals(
            "the address block must re-read its own state on a tick, and these are the whole " +
                "lines that do it. The distributor answers asynchronously: read once and on the " +
                "user's taps only, an address that lands while the screen is open shows up nowhere " +
                "until the screen is re-entered, and the block goes on reading 'Waiting for an " +
                "address…' with the address already in hand. ⛔ What the TICK reads is the state " +
                "record only — SharedPreferences, served from memory after the first load. " +
                "Whether a distributor is installed is read ONCE per visit, above the loop: it " +
                "cannot change while this screen is open, and if it does the next visit sees it.",
            EXPECTED_RELAY_POLL,
            relaySectionBody().take(EXPECTED_RELAY_POLL.size),
        )
    }

    /** The negative half of the rule above — `contains`, deliberately: it screens for absence. */
    @Test fun `the 500 ms loop asks the package manager nothing`() {
        val loop = blockBody(relaySectionBody(), "LaunchedEffect(accountId) {")
        assertEquals(
            "nothing inside the 500 ms loop may read the distributor. " +
                "UnifiedPushManager.distributorInstalled() is UnifiedPush.getDistributors(), i.e. " +
                "packageManager.queryBroadcastReceivers() — a binder round trip. Twice a second, " +
                "for as long as this screen is open. Lines found:\n" + loop.joinToString("\n"),
            emptyList<String>(),
            loop.filter { "distributorInstalled" in it || "Distributor" in it },
        )
        val read = blockBody(codeLinesOf(ACCOUNTS_VIEW_MODEL), RELAY_ADDRESS_READ)
        assertEquals(
            "and AccountsViewModel.relayAddress is what that loop calls, so it may not read the " +
                "distributor either — moving the call one function down is the same binder call " +
                "on the same tick. It reads the state record and nothing else. Lines found:\n" +
                read.joinToString("\n"),
            emptyList<String>(),
            read.filter { "distributorInstalled" in it },
        )
    }

    // -- the status line -----------------------------------------------------------------------

    @Test fun `statusFor asks the relay before it asks UnifiedPush`() {
        assertEquals(
            "the branches of PushController.statusFor are pinned WHOLE and IN ORDER — each one " +
                "closes an incident named in its own comment, and the relay branch has exactly " +
                "one correct place: straight after isLinked, ABOVE both NotWatched and " +
                "up.isActive. Above up.isActive because an armed relay is ACTIVE, so isActive " +
                "answers true for it too and would swallow the case whole, leaving the screen " +
                "reading 'Push: UnifiedPush (…)' for an address nothing has posted to in weeks " +
                "(#177). Above NotWatched because that line would be a lie: a POST on the relay " +
                "address reaches onMessage → PushFetchWorker, and PushFetchWorker gates on " +
                "notificationsEnabled ALONE (PushFetchWorker.kt), never on the watched set — an " +
                "unwatched account with an armed relay really is woken, really does fetch and " +
                "really does notify, while the screen told the reader nothing was watching it " +
                "and offered it an address in the same breath. Nothing moves for a non-relay " +
                "account: NotWatched keeps its place and its meaning for everybody else.",
            EXPECTED_STATUS_BRANCHES,
            statusForBranches(),
        )
    }

    @Test fun `an armed relay is one record and three facts`() {
        val body = managerFunctionBody("fun relayArmed(accountId: String): Boolean {")
        assertEquals(
            "UnifiedPushManager.relayArmed must be exactly these two lines. It is a single " +
                "question over a SINGLE record, which is why it lives here and not in the " +
                "controller as three separate reads that can disagree with each other. The three " +
                "facts are all load-bearing: relayRequested (the user asked), no subscriptionId " +
                "(a JMAP account's ACTIVE is its server's verification, not a delivery), and " +
                "ACTIVE (a wake really arrived — PUBLISHED means an address nobody has posted to).",
            EXPECTED_RELAY_ARMED,
            body,
        )
    }

    /** The negative screen — `contains`, deliberately: it looks for what must be absent. */
    @Test fun `the status line states a dated fact, never a verdict of health`() {
        val files = stringFiles()
        assertEquals(
            "every shipped language must have its own list of health words, or the rule below " +
                "reads a language it has no vocabulary for and passes it whatever it says.",
            files.map { it.parentFile.name }.toSet(),
            HEALTH_WORDS.keys,
        )
        val offenders = files.associate { file ->
            val value = stringValue(file, "settings_push_status_relay").lowercase()
            file.parentFile.name to HEALTH_WORDS.getValue(file.parentFile.name).filter { it in value }
        }.filterValues { it.isNotEmpty() }
        assertEquals(
            "settings_push_status_relay must state WHEN a wake last arrived, in every language, " +
                "and never a verdict on the relay's health. ⛔ The app cannot know whether a relay " +
                "is alive — it only knows when something last came through. It was promised to " +
                "the reporter of #177, in as many words, that Sterna would stop showing push as " +
                "working while nothing comes in, and that promise is not about the English " +
                "string: it is about what is on screen, in whatever language the phone is set " +
                "to. Reading values/ alone left 'Push : relais actif %1\$s' green in eight " +
                "languages. Found:",
            emptyMap<String, List<String>>(),
            offenders,
        )
        val english = stringValue(File(res, "values/strings.xml"), "settings_push_status_relay")
        assertTrue(
            "settings_push_status_relay must carry its %1\$s — it is the date itself, and a line " +
                "that lost it says 'Push: last wake' and means nothing. Found: \"$english\"",
            "%1\$s" in english,
        )
    }

    // -- the strings ---------------------------------------------------------------------------

    /**
     * What `TranslationParityTest` already covers, and which is deliberately NOT repeated here:
     */
    @Test fun `the nine languages all carry the eight relay strings`() {
        val files = stringFiles()
        assertEquals(
            "the app ships nine languages; a directory that vanishes takes its own rule with it",
            listOf(
                "values", "values-de", "values-es", "values-fr", "values-it",
                "values-nl", "values-pl", "values-pt", "values-ru",
            ),
            files.map { it.parentFile.name },
        )
        val missing = files.associate { file ->
            file.parentFile.name to RELAY_KEYS.filterNot { keysOf(file).contains(it) }
        }.filterValues { it.isNotEmpty() }
        assertEquals(
            "the relay address block is untranslated in these languages. Android says nothing " +
                "about a half-translated string, it silently falls back to English: a missing key " +
                "here means a stray English sentence about handing an address to a third party, " +
                "in the middle of a translated Notifications section.",
            emptyMap<String, List<String>>(),
            missing,
        )
    }

    @Test fun `the status line keeps its date argument in all nine languages`() {
        val broken = stringFiles().mapNotNull { file ->
            val value = stringValue(file, "settings_push_status_relay")
            if ("%1\$s" in value) null else "${file.parentFile.name}: \"$value\""
        }
        assertEquals(
            "every language's settings_push_status_relay must carry %1\$s. TranslationParityTest " +
                "compares each translation's arguments with the DEFAULT one's, so dropping the " +
                "argument from values/ and from a translation at once is green there; this rule " +
                "asks for the argument itself.",
            emptyList<String>(),
            broken,
        )
    }

    // -- the field that is not a field ----------------------------------------------------------

    @Test fun `the address is not typed into a masked field`() {
        assertEquals(
            "'isPassword' appears nowhere in SettingsScreen.kt any more (#1693): the API-token / password field moved to Cloud Account " +
                "(CredentialRevealWiringTest there owns the reveal rule). A relay address is not a secret: it " +
                "is meant to be read, selected and pasted into someone else's app, so borrowing " +
                "a masked field for it would hide the one thing the block exists to show.",
            emptyList<String>(),
            screenLines().filter { "isPassword" in it },
        )
    }

    // -- reading the sources ---------------------------------------------------------------------

    /**
     * The lines that open the blocks the call to [RELAY_CALL] is written inside, outermost first —
     */
    private fun ancestorsOf(call: String): List<String> {
        val stack = ArrayDeque<String>()
        for (line in screenLines()) {
            if (line == call) return stack.toList()
            repeat(line.count { it == '}' }) { stack.removeLastOrNull() }
            repeat(line.count { it == '{' }) { stack.addLast(line) }
        }
        error(
            "no '$call' in SettingsScreen.kt — the relay address block was renamed or moved, and " +
                "this lint must be taught where it went rather than left green over a call it " +
                "never found",
        )
    }

    /**
     * The branch condition lines of `statusFor`'s `when`, in source order.
     */
    private fun statusForBranches(): List<String> {
        val lines = codeLinesOf(PUSH_CONTROLLER)
        val fn = lines.indexOfFirst { it == STATUS_FOR }
        check(fn >= 0) {
            "no '$STATUS_FOR' in PushController.kt — the status decision was renamed or reshaped, " +
                "and this lint must be taught where it went"
        }
        val at = fn + lines.drop(fn).indexOfFirst { it == "return when {" }
        check(at > fn) {
            "no 'return when {' after '$STATUS_FOR' — statusFor no longer decides in one when, " +
                "and the order this rule is about has moved somewhere it cannot read"
        }
        var depth = 1
        val out = mutableListOf<String>()
        for (line in lines.drop(at + 1)) {
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0) return out
            out += line
        }
        error("'return when {' is never closed in PushController.kt")
    }

    /** The body of the private composable that draws the block, closed by counting braces. */
    private fun relaySectionBody(): List<String> = blockBody(codeLinesOf(SETTINGS_SCREEN), RELAY_FUN)

    /**
     * The block's branches: each condition line with the lines drawn under it, in source order.
     */
    private fun relayBranches(): List<Pair<String, List<String>>> {
        val out = mutableListOf<Pair<String, MutableList<String>>>()
        for (line in relaySectionBody()) {
            if (line.startsWith("if (") || line.startsWith("} else")) out += line to mutableListOf()
            else out.lastOrNull()?.second?.add(line)
        }
        return out.map { (head, body) -> head to body.toList() }
    }

    /** The relay string keys each branch names, in source order. */
    private fun relayKeysPerBranch(): List<List<String>> = relayBranches().map { (_, body) ->
        body.flatMap { line ->
            Regex("R\\.string\\.(settings_push_relay_[a-z_]+)").findAll(line)
                .map { it.groupValues[1] }.toList()
        }
    }

    private fun managerFunctionBody(signature: String): List<String> =
        blockBody(codeLinesOf(UNIFIED_PUSH_MANAGER), signature)

    private fun blockBody(lines: List<String>, opener: String): List<String> {
        val at = lines.indexOfFirst { it == opener }
        check(at >= 0) {
            "no '$opener' — it was renamed or reshaped, and this lint must be taught the new " +
                "shape rather than left green over a function it never read"
        }
        var depth = lines[at].count { it == '{' } - lines[at].count { it == '}' }
        val out = mutableListOf<String>()
        for (line in lines.drop(at + 1)) {
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0) return out
            out += line
        }
        error("'$opener' is never closed")
    }

    private fun screenLines(): List<String> = codeLinesOf(SETTINGS_SCREEN)

    /** Trimmed lines, comment-only ones dropped so no rule is satisfied by prose. */
    private fun codeLinesOf(file: File): List<String> =
        file.readLines().map { it.trim() }.filterNot {
            it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
        }

    private fun stringFiles(): List<File> = (res.listFiles() ?: emptyArray())
        .filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
        .map { File(it, "strings.xml") }
        .filter { it.isFile }
        .sortedBy { it.parentFile.name }

    private fun keysOf(file: File): Set<String> =
        Regex("<string name=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()

    private fun stringValue(file: File, key: String): String =
        Regex("<string name=\"$key\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())?.groupValues?.get(1)
            ?: error("no $key in ${file.parentFile.name}/strings.xml")

    private companion object {
        const val STATUS_FOR = "fun statusFor(context: Context, accountId: String): PushStatus {"
        const val RELAY_CALL = "RelayAddressSection(accountId = accountId, viewModel = viewModel)"
        const val RELAY_FUN =
            "private fun RelayAddressSection(accountId: String, viewModel: AccountsViewModel) {"
        const val RELAY_ADDRESS_READ = "fun relayAddress(id: String): RelayAddress {"
        val REQUIRED_GUARDS = listOf("if (notificationsEnabled) {", "if (isImap) {")

        val EXPECTED_EXPLAIN_LINES = listOf(
            "stringResource(R.string.settings_push_relay_explain),",
            "RelayAddressNote(title, stringResource(R.string.settings_push_relay_explain))",
        )

        val EXPECTED_RELAY_POLL = listOf(
            "val distributorInstalled = remember(accountId) { viewModel.distributorInstalled() }",
            "var relay by remember(accountId) { mutableStateOf(viewModel.relayAddress(accountId)) }",
            "LaunchedEffect(accountId) {",
            "while (true) {",
            "delay(500)",
            "relay = viewModel.relayAddress(accountId)",
            "}",
            "}",
        )

        val EXPECTED_BRANCHES = listOf(
            "if (!distributorInstalled) {",
            "} else if (endpoint != null) {",
            "} else if (relay.requested) {",
            "} else {",
        )

        val EXPECTED_STATUS_BRANCHES = listOf(
            "store.account(accountId)?.isLinked == true -> PushStatus.Periodic",
            "up.relayArmed(accountId) -> PushStatus.ViaRelay(up.relayLastDeliveryMillis(accountId))",
            "!isWatched(accountId, store.currentId(), store.pushAllAccounts()) -> PushStatus.NotWatched",
            "up.isActive(accountId) -> PushStatus.ViaUnifiedPush(up.distributorLabel())",
            "isBatterySaver(context) -> PushStatus.Periodic",
            "PushService.isConnected(accountId) -> PushStatus.Direct",
            "up.isPending(accountId) -> PushStatus.Connecting",
            "PushService.isRunning -> PushStatus.Connecting",
            "else -> PushStatus.Periodic",
        )

        val EXPECTED_RELAY_ARMED = listOf(
            "val state = store.load(accountId) ?: return false",
            "return state.relayRequested && state.subscriptionId == null && state.status == UpStatus.ACTIVE",
        )

        val RELAY_KEYS = listOf(
            "settings_push_relay_title",
            "settings_push_relay_explain",
            "settings_push_relay_get",
            "settings_push_relay_waiting",
            "settings_push_relay_remove",
            "settings_push_relay_copied",
            "settings_push_relay_no_distributor",
            "settings_push_status_relay",
        )

        /**
         * The words that would turn the dated fact into a verdict, per shipped language. Compared
         */
        val HEALTH_WORDS = mapOf(
            "values" to listOf("active", "alive", "healthy", "connected"),
            "values-de" to listOf("aktiv", "verbunden"),
            "values-es" to listOf("activo", "conectado"),
            "values-fr" to listOf("actif", "vivant", "connecté"),
            "values-it" to listOf("attivo", "connesso"),
            "values-nl" to listOf("actief", "verbonden"),
            "values-pl" to listOf("aktywny", "połączony"),
            "values-pt" to listOf("ativo", "ligado", "conectado"),
            "values-ru" to listOf("активен", "активный", "подключ"),
        )

        val EXPECTED_BRANCH_ACTIONS = listOf(
            listOf("settings_push_relay_no_distributor"),
            listOf(
                "settings_push_relay_copied",
                "settings_push_relay_explain",
                "settings_push_relay_remove",
            ),
            listOf("settings_push_relay_waiting", "settings_push_relay_remove"),
            listOf("settings_push_relay_explain", "settings_push_relay_get"),
        )

        const val SETTINGS_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"
        const val PUSH_CONTROLLER_PATH = "app/src/main/kotlin/app/sterna/push/PushController.kt"
        const val UNIFIED_PUSH_MANAGER_PATH = "app/src/main/kotlin/app/sterna/push/UnifiedPushManager.kt"
        const val ACCOUNTS_VIEW_MODEL_PATH = "app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt"
        const val RES_PATH = "app/src/main/res"

        /** Repo root, walked up from the module's working directory. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this lint reads " +
                        "sources and resources as text and needs a working directory inside the checkout",
                )
        }

        val SETTINGS_SCREEN: File by lazy { File(root, SETTINGS_SCREEN_PATH) }
        val PUSH_CONTROLLER: File by lazy { File(root, PUSH_CONTROLLER_PATH) }
        val UNIFIED_PUSH_MANAGER: File by lazy { File(root, UNIFIED_PUSH_MANAGER_PATH) }
        val ACCOUNTS_VIEW_MODEL: File by lazy { File(root, ACCOUNTS_VIEW_MODEL_PATH) }
        val res: File by lazy { File(root, RES_PATH) }
    }
}
