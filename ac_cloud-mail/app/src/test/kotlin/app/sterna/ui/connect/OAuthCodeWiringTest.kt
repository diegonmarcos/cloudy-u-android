package app.sterna.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and disclaimer as [OAuthWiringTest].
 */
class OAuthCodeWiringTest {

    /**
     * #1703 THE GRANT IS CLOUD ACCOUNT'S: this driver hands the redirect's code + state over and takes the tokens back. It names no verifier, no state, no
     * authorization URL and no token endpoint call (PKCE is decoration the moment a second place can draw the verifier; the provenance rule that
     * `tokenExchangeFor` pinned here moved with the exchange, and is executed in Cloud Account's MailOAuthCodeSessionsTest).
     */
    @Test fun `the exchange is handed to Cloud Account and nothing of the grant is drawn here`() {
        val body = functionBody(SIGN_IN, "exchange")
        val lines = body.lines().map { it.trim() }
        assertTrue(
            "OAuthCodeSignIn.exchange must hand the redirect to Cloud Account, as this whole line:\n  $HANDS_TO_ACCOUNT\nBody was:\n$body",
            lines.contains(HANDS_TO_ACCOUNT),
        )
        val offenders = (SIGN_IN.readText() + REDIRECT.readText()).lines().map { it.trim() }
            .filter { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/**") }
            .filter { GRANT_IN_THIS_APP.containsMatchIn(it) }
        assertEquals(
            "the authorization-code grant runs in Cloud Account: no verifier, state, URL or code exchange here. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    /**
     * A new sign-in REPLACES the one in flight. An abandoned request (browser opened, Back pressed)
     */
    @Test fun `a new sign-in replaces the request in flight`() {
        val body = functionBody(SIGN_IN, "start")
        val offenders = body.lines().map { it.trim() }.filter { it.contains("slot.isArmed()") }
        assertEquals(
            "start() must not refuse because a request is already armed. Found:\n" +
                offenders.joinToString("\n") + "\nBody was:\n$body",
            emptyList<String>(), offenders,
        )
        assertTrue(
            "start() must arm the new request. Body was:\n$body",
            body.contains("slot.arm("),
        )
    }

    /**
     * A failure is said out loud, like a success. This driver is app-scoped precisely because the
     */
    @Test fun `a failure nobody is watching is still said`() {
        val body = functionBody(SIGN_IN, "fail")
        assertTrue(
            "fail() must toast the message as well as emitting it, as this whole line:\n  " +
                "$TOASTS\nBody was:\n$body",
            body.lines().map { it.trim() }.contains(TOASTS),
        )
    }

    /** Nothing drawn or received here is loggable: verifier, state, code, tokens. */
    @Test fun `the code flow logs nothing`() {
        val offenders = (SIGN_IN.readText() + REDIRECT.readText()).lines()
            .filter { LOGGING.containsMatchIn(it) }
            .map { it.trim() }
        assertEquals(
            "the authorization-code files must not log: every value they hold is a secret or " +
                "identifies one. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    /**
     * The device flow stays the default, and the search is the shared one. A server offering
     * both grants must not be sent through a browser: that is the production path today.
     */
    @Test fun `the screen hands over only on the authorization code grant`() {
        val body = functionBody(VIEW_MODEL, "connectOAuth")
        val lines = body.lines().map { it.trim() }
        listOf(PROBES, HANDS_OVER, "host = host,", "metadata = found,", "email = emailTrim,", "accountName = accountName,")
            .forEach { required ->
                assertTrue(
                    "connectOAuth must carry exactly this whole line:\n  $required\n⚠ The anchors " +
                        "are the point: `!= OAuthGrant.NONE` compiles and sends every device-flow " +
                        "server to a browser; `start(host, found, accountName, emailTrim)` " +
                        "compiles and stores the account label as its address. Body was:\n$body",
                    lines.contains(required),
                )
            }
    }

    /**
     * A redirect is a one-shot payload, and it must be handed over from BOTH doors: `onNewIntent`
     */
    @Test fun `both doors hand the redirect over, and it is dropped once taken`() {
        listOf("onCreate", "onNewIntent").forEach { door ->
            val body = functionBody(MAIN_ACTIVITY, door)
            assertTrue(
                "MainActivity.$door must hand the intent to the code driver, as this whole line:" +
                    "\n  $HANDS_REDIRECT\nBody was:\n$body",
                body.lines().map { it.trim() }.contains(HANDS_REDIRECT),
            )
        }
        val body = functionBody(MAIN_ACTIVITY, "consumeOAuthRedirect")
        assertTrue(
            "MainActivity.consumeOAuthRedirect must clear the payload it consumed, as this whole " +
                "line:\n  $STRIPS\nBody was:\n$body",
            body.lines().map { it.trim() }.contains(STRIPS),
        )
    }

    /**
     * WHAT THESE TWO SAY WHEN THE ADD IS OVER. Both are app-scoped drivers whose toast is the only
     */
    @Test fun `both app-scoped sign-ins let the shared choice pick the sentence`() {
        listOf(
            Triple(SIGN_IN, "exchange", CODE_SAYS_IT),
            Triple(OUTLOOK, "start", OUTLOOK_SAYS_IT),
        ).forEach { (file, function, required) ->
            val lines = functionBody(file, function).lines().map { it.trim() }
            val at = lines.indexOf(required.first())
            assertTrue(
                "${file.name}.$function must say what happened through the shared choice, as " +
                    "these whole lines:\n  " + required.joinToString("\n  ") +
                    "\nWithout them a sign-in that only refreshed an account already installed " +
                    "still reads \"added\", and the reader goes looking for a second account that " +
                    "was never created. Lines were:\n" + lines.joinToString("\n"),
                at >= 0 && lines.subList(at, minOf(at + required.size, lines.size)) == required,
            )
            val offenders = lines.filter { REFRESHED_SENTENCE.containsMatchIn(it) }
            assertEquals(
                "and ${file.name}.$function must not name R.string.connect_account_refreshed " +
                    "itself: it is one of the two answers accountAddedToast picks between, and " +
                    "naming it here takes the decision back into a class no test can build. " +
                    "Found:\n" + offenders.joinToString("\n"),
                emptyList<String>(), offenders,
            )
        }
    }

    // -- reading the files -------------------------------------------------------------------------

    /** The body of a named function, as text. Braces counted raw, as the other source lints do. */
    private fun functionBody(file: File, name: String): String {
        val lines = file.readText().lines()
        val start = lines.indexOfFirst { Regex("""\bfun $name\b""").containsMatchIn(it) }
        check(start >= 0) {
            "$name() is gone from ${file.name}: this lint reads nothing, so rename it here too " +
                "rather than let the rules pass over an empty string."
        }
        val out = StringBuilder()
        var depth = 0
        var opened = false
        for (i in start until lines.size) {
            val line = lines[i]
            out.appendLine(line)
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (line.contains('{')) opened = true
            if (opened && depth <= 0) break
        }
        return out.toString()
    }

    private companion object {
        val SIGN_IN = repoFile("app/src/main/kotlin/app/sterna/ui/connect/OAuthCodeSignIn.kt")
        val REDIRECT = repoFile("app/src/main/kotlin/app/sterna/ui/connect/OAuthRedirect.kt")
        val VIEW_MODEL = repoFile("app/src/main/kotlin/app/sterna/ui/connect/ConnectViewModel.kt")
        val MAIN_ACTIVITY = repoFile("app/src/main/kotlin/app/sterna/MainActivity.kt")
        val OUTLOOK = repoFile("app/src/main/kotlin/app/sterna/ui/connect/OutlookSignIn.kt")

        /** The end of the code-grant add: its own sentence on a create, the shared choice on both. */
        val CODE_SAYS_IT = listOf(
            "accountAddedToast(created, R.string.connect_account_added)?.let { toast(string(it)) }",
        )

        /** The same, on the Outlook driver, whose call is wrapped over two lines. */
        val OUTLOOK_SAYS_IT = listOf(
            "accountAddedToast(created, R.string.connect_outlook_added)",
            "?.let { toast(appContext.getString(it)) }",
        )

        const val HANDS_TO_ACCOUNT = "val answer = repo.finishAuthorizationCode(request.handle, code = code, state = state, error = error)"
        val GRANT_IN_THIS_APP = Regex("""\bPkce\b|codeVerifier|code_verifier|newCodeVerifier|buildAuthorizationUrl|exchangeCode|ACTION_VIEW""")

        val REFRESHED_SENTENCE = Regex("""\bR\.string\.connect_account_refreshed\b""")

        const val HANDS_OVER = "if (chooseOAuthGrant(found) == OAuthGrant.AUTHORIZATION_CODE) {"
        /**
         * The search is now the FALLBACK arm: the address step already asked, under its budget,
         */
        const val PROBES =
            "?: collectOAuthHosts(candidates) { container.mailRepository.discoverOAuth(it, addressDomain) }"
        const val HANDS_REDIRECT = "consumeOAuthRedirect()"
        const val STRIPS = "current.data = null"
        const val TOASTS = "toast(message)"

        val LOGGING = Regex("""\bLog\.[a-z]|\bprintln\(""")

        fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, path).isFile }
                ?.let { File(it, path) }
                ?: error(
                    "cannot locate $path from ${File("").absolutePath} — this test reads source " +
                        "files as text and needs a working directory inside the checkout",
                )
    }
}
