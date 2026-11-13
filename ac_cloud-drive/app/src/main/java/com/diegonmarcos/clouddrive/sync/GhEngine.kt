package com.diegonmarcos.clouddrive.sync

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import com.diegonmarcos.clouddrive.BuildConfig
import com.diegonmarcos.clouddrive.DriveDebugLog
import com.diegonmarcos.superapp.core.DataBackendClient
import org.json.JSONObject

/**
 * #705 gh, REACHED, NOT CARRIED. The GitHub card used to exec gh out of this app's own
 * nativeLibraryDir, which made a 39 MB binary part of Cloud Drive and every gh bump a Cloud
 * Drive release. gh now runs in Cloud-Lib-Gh.apk (libs:gh's GhBackendService, installed by
 * the Store) and this is the thin client the page talks to instead: the same calls GhRunner
 * offered, each one a request over core's IDataBackend.
 *
 * THE HANDSHAKE COMES FIRST AND COSTS NO BIND: [check] asks PackageManager for the service
 * that answers the declared action in the declared package (build.json::engines.gh, both
 * resolved at build time from the fleet manifest) and reads the CONTRACT it declares. No
 * package → [Check.NotInstalled]; a package with no such service, or one declaring a contract
 * below what this build calls → [Check.TooOld]. Each is a different sentence on the card,
 * because "install it" and "update it" are different next steps. Only a ready engine is bound.
 *
 * WHAT CROSSES: the page's questions and gh's answers as JSON. The engine parses gh's sign-in
 * output with its own pinned patterns and hands back the code and the page, so nothing here
 * knows how a given gh version prints. The credential crosses too — it always reached the
 * clone — and it lands in a [Credential] whose toString never prints it.
 *
 * EVERY STEP IS LOGGED, to logcat and to the on-device log (DriveDebugLog), under [TAG]: each
 * call and how it ended (an engine that is missing or too old included), and the sign-in's progress (code shown, gh
 * still waiting, gh's verdict in its own words). A sign-in once failed on a phone with no line
 * about it anywhere. What is never logged: the credential (only whether one came back) and the
 * one-time code (only that one is on screen).
 *
 * BLOCKING, like GhRunner was: call everything but [check] off the main thread.
 */
class GhEngine(context: Context) {

    private val ctx = context.applicationContext

    @Volatile private var client: DataBackendClient? = null

    /** What stands between this phone and the gh engine. */
    sealed class Check {
        object Ready : Check()
        /** The engine APK is not on the phone. */
        data class NotInstalled(val pkg: String) : Check()
        /** Installed, but its service declares [found] (0 = no engine service at all) and this build needs [needed]. */
        data class TooOld(val pkg: String, val found: Int, val needed: Int) : Check()
    }

    /** The outcome of one gh invocation in the engine: never an exit code alone. */
    class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    /** A git credential gh answered with. Its toString never prints the secret. */
    class Credential(val username: String, val secret: String) {
        override fun toString(): String = "Credential(username=$username, secret=<redacted>)"
    }

    fun check(): Check {
        val pm = ctx.packageManager
        val pkg = BuildConfig.GH_ENGINE_PACKAGE
        val needed = BuildConfig.GH_ENGINE_MIN_CONTRACT
        val service = pm.resolveService(Intent(BuildConfig.GH_ENGINE_ACTION).setPackage(pkg), PackageManager.GET_META_DATA)
            ?.serviceInfo
        if (service == null) {
            val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
            return if (installed) Check.TooOld(pkg, 0, needed) else Check.NotInstalled(pkg)
        }
        val found = service.metaData?.getInt(CONTRACT_KEY, 0) ?: 0
        if (found < needed) return Check.TooOld(pkg, found, needed)
        if (client == null) synchronized(this) {
            if (client == null) client = DataBackendClient(ctx, service.packageName, service.name)
        }
        return Check.Ready
    }

    /** `gh auth status` for [host], as gh printed it. */
    fun status(host: String): Result = result(ask(STATUS, host))

    /** `gh repo list` with [fields], as gh printed it. */
    fun repoList(limit: Int, fields: String): Result = result(ask(REPO_LIST, limit.toString(), fields))

    /** The credential gh holds for [host], or null when it holds none or cannot answer. */
    fun credential(host: String): Credential? {
        val o = ask(CREDENTIAL, host)
        val username = o.optString("username")
        val secret = o.optString("secret")
        val held = if (username.isBlank() || secret.isBlank()) null else Credential(username, secret)
        if (!o.has(ERROR)) log("credential for $host: " + if (held == null) "gh holds none" else "answered")
        return held
    }

    private fun ask(method: String, vararg args: String): JSONObject {
        val why = check()
        val c = client
        if (why !is Check.Ready || c == null) return JSONObject().put(ERROR, "the gh engine is not ready: $why")
        val text = c.call(method, *args)
        return runCatching { JSONObject(text) }
            .getOrElse { JSONObject().put(ERROR, "the gh engine answered $method with something that is not JSON") }
            .also { o -> callLine(method, o)?.let(::log) }
    }

    /**
     * The log line for one call: the engine's error, or gh's exit and (on a failure) its words.
     * Only those two keys are read, so a credential answer is never described here;
     */
    private fun callLine(method: String, o: JSONObject): String? = when {
        o.has(ERROR) -> "$method: ${o.optString(ERROR)}"
        o.has("exit") -> "$method: exit ${o.optInt("exit")}" + if (o.optInt("exit") == 0) "" else ": ${why(o.optString("output"))}"
        else -> null
    }

    private fun result(o: JSONObject): Result =
        if (o.has(ERROR)) Result(EXEC_FAILED, o.optString(ERROR))
        else Result(o.optInt("exit", EXEC_FAILED), o.optString("output"))

    private fun log(msg: String) = DriveDebugLog.i(ctx, TAG, msg)

    companion object {
        /** Same exit code GhRunner used for a gh that could not run at all: no real one is negative. */
        const val EXEC_FAILED = -1

        /** The engine CONTRACT meta-data key every engine service declares (libs/gh's manifest). */
        const val CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"

        // The engine's method names (GhBackendService). Strings, not an import: this app does
        // not compile libs:gh, and that is the point.
        const val STATUS = "status"
        const val REPO_LIST = "repoList"
        const val CREDENTIAL = "credential"

        private const val ERROR = "error"

        const val TAG = "DriveGh"

        /**
         * WHY gh STOPPED, in its own words: every line it printed but the device-flow prompt (any
         * line about the one-time code, the clipboard notice included, and the page), joined. The last line alone turned gh's DNS
         * failure into "check your internet connection", which read as the phone's fault.
         */
        fun why(output: String): String =
            output.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !CODE_LINE.containsMatchIn(it) && !it.startsWith("Open this URL") }
                .joinToString(" · ")
                .ifEmpty { "gh printed nothing" }

        private val CODE_LINE = Regex("one-time code", RegexOption.IGNORE_CASE)
    }
}
