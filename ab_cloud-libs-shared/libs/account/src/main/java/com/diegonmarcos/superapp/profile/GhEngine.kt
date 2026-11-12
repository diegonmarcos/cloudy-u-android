package com.diegonmarcos.superapp.profile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import com.diegonmarcos.superapp.account.BuildConfig
import com.diegonmarcos.superapp.core.DataBackendClient
import org.json.JSONObject

/**
 * #713 Configs ▸ Account ▸ Connect ▸ GitHub ▸ WebAuth reaches gh in the gh ENGINE
 * (Cloud-Lib-Gh.apk, libs:gh's GhBackendService, build.json::engines.gh) — the same engine and
 * contract cloud-drive's GitHub card binds (#705). This app compiles nothing of libs:gh: the
 * methods below are strings, and 1_cicd's engine-contract-guard holds every one of them to the
 * engine's own methodNames(), so an engine that drops one goes red before it ships.
 *
 * The sign-in is gh's OWN `auth login` (GitHub CLI's public client inside gh). The fleet declares
 * no GitHub OAuth app; nothing here knows a client of its own.
 *
 * THE HANDSHAKE COMES FIRST AND COSTS NO BIND: [check] reads the engine's declared CONTRACT
 * through PackageManager, so "not installed" and "too old" are two different sentences on the
 * page. Blocking otherwise: call everything but [check] off the main thread. Nothing here logs.
 */
class GhEngine(context: Context) {

    private val ctx = context.applicationContext

    @Volatile private var client: DataBackendClient? = null

    sealed class Check {
        object Ready : Check()
        data class NotInstalled(val pkg: String) : Check()
        /** Installed, but its service declares [found] (0 = no engine service) and this build needs [needed]. */
        data class TooOld(val pkg: String, val found: Int, val needed: Int) : Check()
    }

    /** gh's exit and its words; never an exit code alone. */
    class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    fun check(): Check {
        val pm = ctx.packageManager
        val pkg = BuildConfig.GH_ENGINE_PACKAGE
        val needed = BuildConfig.GH_ENGINE_MIN_CONTRACT
        // #1693 a host that declares no engines.gh (every app but Cloud Account) binds no engine: it reads as not installed.
        if (pkg.isBlank() || BuildConfig.GH_ENGINE_ACTION.isBlank()) return Check.NotInstalled("Cloud Account (this app does not sign in to GitHub)")
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

    /** The token gh holds for [host] (its git-credential answer), or null when it holds none. Used once, never stored. */
    fun token(host: String): String? = ask(CREDENTIAL, host).optString("secret").takeIf { it.isNotBlank() }

    /**
     * gh's own `auth login`, run by the engine; [onPrompt] gets the one-time code and the page as
     * gh prints them. Returns once gh exits, or after the declared ceiling.
     */
    fun login(host: String, onPrompt: (code: String, url: String) -> Unit): Result {
        val started = ask(LOGIN_START, host)
        if (started.has(ERROR)) return Result(EXEC_FAILED, started.optString(ERROR))
        var code = ""
        var url = ""
        val deadline = SystemClock.elapsedRealtime() + BuildConfig.GH_ENGINE_LOGIN_MAX_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(BuildConfig.GH_ENGINE_POLL_MS)
            val p = ask(LOGIN_POLL)
            if (p.has(ERROR)) return Result(EXEC_FAILED, p.optString(ERROR))
            val c = p.optString("code")
            val u = p.optString("url")
            if (c != code || u != url) { code = c; url = u; onPrompt(c, u) }
            if (!p.optBoolean("running")) return Result(p.optInt("exit", EXEC_FAILED), p.optString("output"))
        }
        return Result(EXEC_FAILED, "gh sign-in did not end within ${BuildConfig.GH_ENGINE_LOGIN_MAX_MS / 60_000} minutes")
    }

    private fun ask(method: String, vararg args: String): JSONObject {
        val why = check()
        val c = client
        if (why !is Check.Ready || c == null) return JSONObject().put(ERROR, "the gh engine is not ready: $why")
        val text = c.call(method, *args)
        return runCatching { JSONObject(text) }
            .getOrElse { JSONObject().put(ERROR, "the gh engine answered $method with something that is not JSON") }
    }

    companion object {
        const val EXEC_FAILED = -1

        /** The engine CONTRACT meta-data key (libs/gh's manifest). */
        const val CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"

        // The engine's method names (GhBackendService).
        const val CREDENTIAL = "credential"
        const val LOGIN_START = "loginStart"
        const val LOGIN_POLL = "loginPoll"

        private const val ERROR = "error"

        /** [url] when it is a page on [host], else null — a page gh names is only opened on the declared host. */
        fun pageOnHost(url: String, host: String): String? =
            url.takeIf { it.isNotBlank() && runCatching { java.net.URI(it).host }.getOrNull() == host }
    }
}
