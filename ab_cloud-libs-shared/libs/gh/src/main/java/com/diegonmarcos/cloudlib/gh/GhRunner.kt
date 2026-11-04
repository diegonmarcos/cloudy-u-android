package com.diegonmarcos.cloudlib.gh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.diegonmarcos.cloudlib.sysdns.ResolverProxy
import java.io.File

/**
 * Execs the bundled static gh. The binary lives where the installer put it —
 * applicationInfo.nativeLibraryDir/libgh.so — because that is the one place an
 * app may exec from on API 29+ (see data/gh-binary.json). Blocking; callers run
 * it on IO.
 *
 * SELF-CONTAINED, MEASURED. A real authenticated `gh api /user` round-trip
 * spawned ZERO child processes: no git, no PATH, nothing from `$HOME`. So the
 * environment below is built from nothing rather than inherited — [GH_CONFIG_DIR]
 * points gh's whole config and state tree at the app's own files, and `HOME` is
 * pointed at the same place so a gh that ever consults it cannot reach outside
 * the sandbox.
 *
 * THE TOKEN NEVER APPEARS IN AN ARGUMENT OR IN OUTPUT WE KEEP. It is passed in
 * the environment as `GH_TOKEN`, which keeps it out of argv (and therefore out
 * of /proc/<pid>/cmdline, out of any process listing and out of every log line
 * this class produces). Nothing here calls `gh auth token`, which would print it
 * to stdout; nothing here renders, toasts or logs it, whole or truncated.
 *
 * #689 gh IS THE GITHUB LEG. [login] runs gh's OWN `auth login` (gh's device flow,
 * against the client id GitHub CLI compiles into this binary — the fleet supplies no
 * client id, no secret and no redirect), [status] reads who gh is signed in as,
 * [repoList] lists that account's repositories and [credential] hands the clone the
 * credential gh holds, through gh's own git-credential helper. gh keeps that
 * credential in [configDir], the app's private files.
 *
 * THE SANDBOX IS NOT A LINUX BOX: no /etc/resolv.conf and no /etc/ssl. [start] hands gh the CA
 * directories (SSL_CERT_DIR) and a loopback CONNECT proxy that resolves with Android's resolver
 * (HTTPS_PROXY → libs:sysdns [ResolverProxy]); without them gh died on its first lookup and printed "check your
 * internet connection" (data/gh-binary.json::_doc_sandbox).
 *
 * EVERY gh RUN IS LOGGED under [TAG]: its verb, its exit, and on failure gh's own last words.
 * The device-flow lines are logged as gh prints them (a one-time code is shown on screen anyway).
 * The credential never is: [credential] logs whether gh answered, never what.
 */
class GhRunner(private val context: Context) {

    val binary: File = File(context.applicationInfo.nativeLibraryDir, BuildConfig.GH_JNI_NAME)

    /**
     * gh's config AND state directory. `GH_CONFIG_DIR` overrides both, measured:
     * with it set, gh needs nothing from `$HOME` at all and writes only here.
     */
    val configDir: File = File(context.filesDir, "gh")

    val isAvailable: Boolean get() = binary.isFile

    val version: String get() = BuildConfig.GH_VERSION

    /** The outcome of one gh invocation: never an exit code alone. */
    class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    /**
     * Run gh with [args], authenticating with [token] if one is given. The token
     * goes into the environment, never into [args]. #689 a gh that cannot even be
     * exec'd is an outcome too ([EXEC_FAILED], with the platform's words), never an
     * exception thrown into a caller's coroutine.
     */
    fun run(args: List<String>, token: String? = null): Result = try {
        val process = start(args, token)
        process.outputStream.close()
        val text = process.inputStream.bufferedReader().readText()
        Result(process.waitFor(), text)
    } catch (e: Exception) {
        Result(EXEC_FAILED, "gh could not run: ${e.message ?: e.javaClass.simpleName}")
    }.also { logged(args, it) }

    /** gh's verb and exit, and on a failure its own last lines. Output of a success is not kept. */
    private fun logged(args: List<String>, r: Result) {
        val verb = args.take(2).joinToString(" ")
        if (r.ok) Log.i(TAG, "gh $verb: exit 0")
        else Log.w(TAG, "gh $verb: exit ${r.exitCode}: ${GhOutput.why(r.output)}")
    }

    /** One gh process: argv as given, stderr folded into stdout, environment built from nothing. */
    private fun start(args: List<String>, token: String?): Process {
        check(isAvailable) { "gh is not installed: ${binary.absolutePath} is missing" }
        configDir.mkdirs()
        val builder = ProcessBuilder(listOf(binary.absolutePath) + args).redirectErrorStream(true)
        builder.environment().apply {
            // Built from nothing: gh needs no inherited environment, and an
            // inherited one is how a stray variable changes behaviour on one
            // phone and not another.
            clear()
            put("GH_CONFIG_DIR", configDir.absolutePath)
            put("HOME", configDir.absolutePath)
            put("GH_NO_UPDATE_NOTIFIER", "1")
            put("GH_PROMPT_DISABLED", "1")
            // The two things the app sandbox lacks (the pin's _doc_sandbox): Android's CA
            // directories, and a way to resolve a name.
            put("SSL_CERT_DIR", ResolverProxy.CA_DIRS)
            net?.let { put("HTTPS_PROXY", it.url) }
            if (!token.isNullOrBlank()) put("GH_TOKEN", token)
        }
        return builder.start()
    }

    /**
     * A REST call through gh's own API client — this is the leg #641/#642 left
     * open. Listing a user's repositories is `/user/repos`, which is REST and
     * not the git protocol, so it needs a token no matter which rung of the
     * chain supplied it. Measured to work with zero child execs.
     */
    fun api(path: String, token: String): Result = run(listOf("api", path), token)

    /**
     * Does [token] actually authenticate? Asks GitHub, and returns the answer as
     * a sentence rather than a code. The token is NOT included in the message on
     * either branch.
     */
    fun checkToken(token: String): Pair<Boolean, String> {
        val r = api("/user", token)
        return if (r.ok && r.output.contains("\"login\"")) {
            true to "GitHub accepted the credential"
        } else {
            false to "GitHub refused the credential (gh exited ${r.exitCode})"
        }
    }

    /**
     * #689 who gh ITSELF is signed in as on [host]: `gh auth status --json hosts`. MEASURED on the
     * pinned 2.101.0: the JSON carries state, login, token source, scopes and git protocol and
     * never the token; signed OUT it still exits 0, printing a sentence and then `{"hosts":{}}`,
     * so the caller reads the JSON line and never trusts the exit code alone.
     */
    fun status(host: String): Result = run(listOf("auth", "status", "--hostname", host, "--json", "hosts"))

    /**
     * #689 gh's OWN sign-in, `gh auth login`. With no TTY (how this runs) gh goes straight to its
     * device flow: it prints a one-time code and the page to enter it at, then polls until the code
     * is approved or expires. [onLine] gets every line AS gh prints it, because the code has to
     * reach the screen while gh is still waiting; this returns when gh exits. [drain] is that
     * reading, and [GhLogin] is the job that runs it in the background.
     *
     * `--insecure-storage`: Android has no keyring for gh to use, so its credential is written to
     * [configDir], the app's private files. `--clipboard=false`: gh cannot reach Android's
     * clipboard (measured: it warns and names desktop tools); the page copies the code itself.
     * No GH_TOKEN is ever passed here — gh refuses to log in while one is set.
     */
    fun login(host: String, onLine: (String) -> Unit): Result = try {
        val process = start(
            listOf("auth", "login", "--hostname", host, "--git-protocol", "https", "--web",
                "--insecure-storage", "--skip-ssh-key", "--clipboard=false"),
            token = null,
        )
        drain(process, onLine)
    } catch (e: Exception) {
        Result(EXEC_FAILED, "gh could not run: ${e.message ?: e.javaClass.simpleName}")
    }.let { if (it.ok) it else Result(it.exitCode, it.output.trimEnd() + "\n" + networkCheck()) }
        .also { logged(listOf("auth", "login"), it) }

    /**
     * #726 THE ENGINE'S OWN READING OF ITS NETWORK, appended to a failed sign-in. gh's
     * "error connecting to github.com / check your internet connection" is its line for ANY failed
     * lookup, so it cannot tell a dead tunnel from a firewall from a refused port. This line can:
     * whether gh-net is up, whether THIS uid resolves and reaches each declared host (DNS / TCP,
     * Android's words), and whether a VPN carries this uid's traffic, the firewall's included.
     */
    private fun networkCheck(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val vpn = runCatching {
            cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }.getOrNull() == true
        // #729 a process Android has pushed out of a valid lifecycle loses its network, and every
        // lookup then fails like a dead resolver. Say which it is: BLOCKED is Android's own verdict
        // for this uid, and the importance is how foreground this process was when gh gave up.
        @Suppress("DEPRECATION")
        val blocked = runCatching { cm?.activeNetworkInfo?.detailedState == android.net.NetworkInfo.DetailedState.BLOCKED }.getOrNull() == true
        val importance = android.app.ActivityManager.RunningAppProcessInfo().also { android.app.ActivityManager.getMyMemoryState(it) }.importance
        return (listOf(if (net != null) "gh-net tunnel up" else "gh-net tunnel DID NOT START ($netFailure), so gh had to resolve names itself") +
            listOf(if (blocked) "Android is BLOCKING this engine's network (process importance $importance: it fell out of the foreground)"
                else "Android is not blocking this engine's network (process importance $importance)") +
            BuildConfig.GH_PROXY_HOSTS.split(',').map { ResolverProxy.probe(it) } +
            // #729 where gh's connection died, when the network killed it under gh: gh itself
            // only reads "unexpected EOF" or a reset, which names no layer and no address.
            listOfNotNull(net?.lastDrop?.let { "last gh-net drop: $it" }) +
            listOfNotNull(if (vpn) "a VPN carries ${context.packageName}'s traffic: if its firewall does not allow this app, that is the block" else null))
            .joinToString(" · ", prefix = "engine check: ")
            .also { Log.i(TAG, it) }
    }

    /** #689 the signed-in account's own repositories, public and private, as gh's JSON [fields]. */
    fun repoList(limit: Int, fields: String): Result =
        run(listOf("repo", "list", "--limit", limit.toString(), "--json", fields))

    /**
     * #689 THE CREDENTIAL gh HOLDS for [host], asked the way git asks it: gh's own git-credential
     * helper, the very protocol `gh repo clone` feeds to git. MEASURED: signed in, gh answers
     * `username=` (the token user) and `password=...`; signed out it answers nothing and exits 1.
     * The answer is read straight into a [Credential] and never into a [Result], so it cannot reach
     * a caller's error line, a log or a screen. Null when gh holds none.
     */
    fun credential(host: String): Credential? {
        if (!isAvailable) return null
        return try {
            val process = start(listOf("auth", "git-credential", "get"), token = null)
            process.outputStream.bufferedWriter().use { it.write("protocol=https\nhost=$host\n\n") }
            val answer = process.inputStream.bufferedReader().readText()
            if (process.waitFor() == 0) GhOutput.credential(answer) else null
        } catch (e: Exception) {
            null
        }.also { Log.i(TAG, "gh auth git-credential for $host: " + if (it == null) "none" else "answered") }
    }

    /** A git credential gh answered with. Its toString never prints the secret. */
    class Credential(val username: String, val secret: String) {
        override fun toString(): String = "Credential(username=$username, secret=<redacted>)"
    }

    companion object {
        /** #689 the exit code of a gh that could not be started at all; no real exit code is negative. */
        const val EXEC_FAILED = -1

        const val TAG = "GhEngine"

        /**
         * The one proxy per engine process, or null if loopback would not open — then gh runs
         * without it, fails on its lookup, and says so in its own words.
         */
        private val net: ResolverProxy? by lazy {
            runCatching { ResolverProxy("gh", BuildConfig.GH_PROXY_HOSTS.split(',').toSet(), 443, idleMs = BuildConfig.GH_PROXY_IDLE_MS) { Log.i(TAG, it) } }
                .onFailure { netFailure = it.message ?: it.javaClass.simpleName }
                .onFailure { Log.e(TAG, "gh-net: loopback proxy would not start; gh cannot resolve names", it) }
                .getOrNull()
        }

        /** Why [net] is null, for the card: a failed sign-in must not read like the phone being offline. */
        @Volatile private var netFailure: String? = null

        /**
         * Read gh's output LINE BY LINE AS IT ARRIVES, handing each to [onLine] at once, until gh
         * exits. This is what puts the one-time code on screen while gh is still polling GitHub:
         * reading to the end first would hold the code back until gh gave up (GhLoginTest).
         */
        fun drain(process: Process, onLine: (String) -> Unit): Result {
            process.outputStream.close()
            val out = StringBuilder()
            process.inputStream.bufferedReader().forEachLine { out.appendLine(it); onLine(it) }
            return Result(process.waitFor(), out.toString())
        }
    }
}

/**
 * ONE `gh auth login` AS A LONG-LIVED JOB. [start] returns at once; gh runs on its own thread,
 * and [code] and [url] fill in the moment gh prints them, while gh keeps polling GitHub for the
 * approval. [running] stays true until gh exits; [ended] is then gh's exit and its words. A
 * non-zero exit is gh's verdict and its output says why — the job never rewrites it into one.
 *
 * [run] is the login itself (GhRunner.login in the engine, a fake gh in GhLoginTest), so the
 * job, the line reading and the pin's patterns are exercised as one, the way the phone runs them.
 */
class GhLogin(
    private val host: String,
    /**
     * #729 keeps the engine's network for as long as gh runs (GhLoginKeeper on the phone) and
     * answers its own release. Taken in [start], on the caller's thread, while the app that asked
     * is still on screen; released only once gh has exited, whatever its verdict.
     */
    private val hold: () -> (() -> Unit) = { {} },
    private val run: (onLine: (String) -> Unit) -> GhRunner.Result,
) {
    @Volatile var code = ""; private set
    @Volatile var url = ""; private set
    @Volatile var ended: GhRunner.Result? = null; private set
    @Volatile private var release: () -> Unit = {}

    private val thread = Thread({
        val r = try {
            run { line ->
                if (line.isNotBlank()) Log.i(GhRunner.TAG, "gh auth login: $line")
                if (code.isEmpty()) GhOutput.deviceCode(line)?.let { code = it }
                if (url.isEmpty()) GhOutput.verificationUrl(line, host)?.let { url = it }
            }
        } finally {
            release()
        }
        ended = r
    }, "gh-login").apply { isDaemon = true }

    val running: Boolean get() = ended == null && thread.isAlive

    fun start(): GhLogin = apply {
        Log.i(GhRunner.TAG, "gh auth login: starting for $host")
        release = hold()
        thread.start()
    }
}

/**
 * #689 how the PINNED gh speaks, read with the patterns data/gh-binary.json::login_output
 * declares (measured on that version; baked by build.gradle), and the git-credential answer.
 * Pure, so the pin's patterns are the only thing that decides what the page shows.
 */
object GhOutput {
    private val DEVICE_CODE = Regex(BuildConfig.GH_LOGIN_CODE_PATTERN)
    private val VERIFICATION_URL = Regex(BuildConfig.GH_LOGIN_URL_PATTERN)

    /** The one-time code in a line of `gh auth login` output, or null. */
    fun deviceCode(line: String): String? = DEVICE_CODE.find(line)?.value

    /** The page gh says to enter the code at — only ever on [host], so a stray URL is never opened. */
    fun verificationUrl(line: String, host: String): String? =
        VERIFICATION_URL.find(line)?.value?.takeIf { runCatching { java.net.URI(it).host }.getOrNull() == host }

    /**
     * WHY gh STOPPED, in its own words: every line it printed except the device-flow prompt
     * (the code, the page, the clipboard notice), joined. "error connecting to github.com"
     * and the line after it both survive — the last line alone read like a network outage.
     */
    fun why(output: String): String =
        output.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && deviceCode(it) == null && !it.contains("clipboard", ignoreCase = true) && !it.startsWith("Open this URL") }
            .joinToString(" · ")
            .ifEmpty { "gh printed nothing" }

    /** `username=`/`password=` from a git-credential answer; null unless both are there. */
    fun credential(answer: String): GhRunner.Credential? {
        val fields = answer.lineSequence().filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val username = fields["username"].orEmpty()
        val secret = fields["password"].orEmpty()
        return if (username.isBlank() || secret.isBlank()) null else GhRunner.Credential(username, secret)
    }
}
