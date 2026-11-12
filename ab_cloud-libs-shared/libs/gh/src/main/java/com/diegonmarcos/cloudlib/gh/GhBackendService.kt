package com.diegonmarcos.cloudlib.gh

import android.content.Context
import android.util.Log
import com.diegonmarcos.superapp.core.DataBackendService
import org.json.JSONObject

/**
 * #705 libs:gh AS AN ENGINE. gh runs HERE — inside Cloud-Lib-Gh.apk, in that APK's own
 * process and uid — behind core's IDataBackend, and an app that wants GitHub binds this
 * service instead of compiling gh in. Cloud Drive carried the 39 MB binary itself until
 * #705, so every gh bump republished Cloud Drive; now it republishes this one APK.
 *
 * WHERE gh's STATE LIVES: [GhRunner.configDir] is this APK's own files, so the sign-in gh
 * keeps belongs to the engine, and every fleet app that binds it shares that one GitHub
 * sign-in. The uid is also why gh can be an engine at all: it reads and writes no shared
 * storage. rclone and git-sync do, which is why they are not engines (a0_docs/eng-specs/
 * engine-apk-split.md).
 *
 * THE CONTRACT is [methodNames] plus the JSON each answers, versioned by the CONTRACT
 * meta-data on this service in the manifest. It only GROWS: a method is never removed or
 * re-shaped while a published client may call it — a change that would break an answer
 * ships under a new method name and raises the contract — so an engine update cannot
 * break an older app. A client reads the contract through PackageManager before it binds,
 * so an engine too old to serve it is named as such instead of reading like a missing one.
 *
 * THE CREDENTIAL CROSSES THE BINDER, deliberately: [CREDENTIAL] hands a clone the
 * credential gh holds, as GhRunner.credential did in-process. The service is guarded by
 * CONSTELLATION_DATA (signature-level), so only APKs signed with the fleet key can ask.
 * Nothing here logs an answer.
 *
 * SIGN-IN IS POLLED, NOT STREAMED: `gh auth login` blocks until GitHub approves the code,
 * and a binder call must not. [LOGIN_START] starts it on a thread and returns at once;
 * [LOGIN_POLL] reports the one-time code and the page as gh prints them — read HERE, with
 * the pin's own patterns (GhOutput), so a client never parses gh's output — and gh's exit
 * once it ends. A running gh is never an error: only gh's own exit is a verdict.
 *
 * EVERY CALL IS LOGGED to logcat under GhRunner.TAG ("GhEngine"), so a sign-in that fails on a
 * phone can be read there: the call, gh's verb and exit, gh's own words on a failure, and each
 * tunnel the engine opened for gh. The phone once showed a failure while this process logged
 * nothing at all.
 *
 * Dispatch is an explicit `when`, not reflection, for the reason DataBackendService gives.
 */
class GhBackendService : DataBackendService() {

    private val runner: GhRunner by lazy { GhRunner(applicationContext) }

    override fun methodNames(): Array<String> = arrayOf(STATUS, REPO_LIST, CREDENTIAL, LOGIN_START, LOGIN_POLL)

    // Logged both ways: core turns a throw into an error answer, which would otherwise leave no trace here.
    override fun dispatch(method: String, args: Array<String>): String = runCatching { answer(method, args) }
        .onSuccess { Log.i(GhRunner.TAG, "engine call $method answered") }
        .onFailure { Log.w(GhRunner.TAG, "engine call $method failed: ${it.message}") }
        .getOrThrow()

    private fun answer(method: String, args: Array<String>): String = when (method) {
        STATUS -> result(runner.status(arg(args, 0)))
        REPO_LIST -> result(runner.repoList(arg(args, 0).toInt(), arg(args, 1)))
        CREDENTIAL -> runner.credential(arg(args, 0))
            ?.let { JSONObject().put("username", it.username).put("secret", it.secret).toString() }
            ?: "{}"
        LOGIN_START -> Login.start(runner, arg(args, 0), applicationContext, caller())
        LOGIN_POLL -> Login.poll()
        else -> throw IllegalArgumentException("the gh engine does not answer '$method'")
    }

    /** #1692 the app that asked, for the sign-in notification. Null when Android will not say. */
    private fun caller(): String? = runCatching {
        val uid = android.os.Binder.getCallingUid()
        val pkg = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: return@runCatching null
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString().ifBlank { pkg }
    }.getOrNull()

    private fun result(r: GhRunner.Result): String =
        JSONObject().put("exit", r.exitCode).put("output", r.output).toString()

    private fun arg(args: Array<String>, i: Int): String =
        requireNotNull(args.getOrNull(i)?.takeIf { it.isNotBlank() }) { "argument ${i + 1} is missing" }

    /**
     * One sign-in at a time per engine process: gh keeps ONE config, and two logins would race it.
     * The sign-in is a [GhLogin] job: [start] answers at once, and [poll] carries the code and the
     * page the moment gh prints them, while gh is still waiting on GitHub.
     */
    private object Login {
        private var job: GhLogin? = null

        @Synchronized
        fun start(runner: GhRunner, host: String, ctx: Context, who: String? = null): String {
            val running = job?.running == true
            // #1692 gh's process is kept so expiry and Cancel end gh itself, not just the notification.
            val proc = java.util.concurrent.atomic.AtomicReference<Process?>()
            if (!running) job = GhLogin(host, hold = { GhLoginKeeper.hold(ctx, it, who) }, kill = { proc.get()?.destroyForcibly() }) { onLine ->
                runner.login(host, onLine) { proc.set(it) }
            }.start()
            else Log.i(GhRunner.TAG, "gh auth login: already running; the client follows that one")
            return JSONObject().put("started", !running).toString()
        }

        @Synchronized
        fun poll(): String {
            val j = job
            val done = j?.ended
            val o = JSONObject().put("code", j?.code.orEmpty()).put("url", j?.url.orEmpty())
            return when {
                done != null -> o.put("running", false).put("exit", done.exitCode).put("output", done.output)
                j?.running == true -> o.put("running", true)
                else -> o.put("running", false).put("exit", GhRunner.EXEC_FAILED).put("output", "no gh sign-in was started")
            }.toString()
        }
    }

    companion object {
        const val STATUS = "status"
        const val REPO_LIST = "repoList"
        const val CREDENTIAL = "credential"
        const val LOGIN_START = "loginStart"
        const val LOGIN_POLL = "loginPoll"
    }
}
