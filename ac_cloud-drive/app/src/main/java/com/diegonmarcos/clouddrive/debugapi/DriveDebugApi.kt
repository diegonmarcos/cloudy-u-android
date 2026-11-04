package com.diegonmarcos.clouddrive.debugapi

import com.diegonmarcos.cloudlib.gitrepos.*

import android.content.Context
import com.diegonmarcos.clouddrive.BuildConfig
import com.diegonmarcos.clouddrive.DriveDebugLog
import com.diegonmarcos.clouddrive.GitSyncWorker
import com.diegonmarcos.clouddrive.SharedStore
import com.diegonmarcos.clouddrive.configs.DriveGitChain
import com.diegonmarcos.clouddrive.sync.FleetGit
import com.diegonmarcos.cloudlib.gitrepos.FleetSession
import com.diegonmarcos.clouddrive.sync.GitSyncCoordinator
import com.diegonmarcos.cloudlib.gitsync.GitAuthChain
import com.diegonmarcos.cloudlib.gitsync.RepoRegistry
import com.diegonmarcos.superapp.devtools.AppDebugServer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * #669 THE SYNC ▸ GIT FLOW, EXERCISABLE FROM THE PHONE'S OWN SHELL.
 *
 * cloud-drive already answers on loopback: libs:core pulls libs:devtools in with an
 * `api` dependency, whose [AppDebugServer] binds this package's own 127.0.0.1 port
 * (libs/devtools/debug-ports.json, #792) and gates every route except /api/system/ping behind the fleet's ONE
 * Bearer token ([com.diegonmarcos.superapp.devtools.FleetToken] — SuperApp mints it,
 * siblings adopt it over the signature-guarded provider, and an unauthorized banner
 * proves liveness). This file adds nothing to that transport and NO SECOND SERVER:
 * it registers this app's own route groups on the shared server, the exact
 * extension point [AppDebugServer.route] was built for. Loopback bind, token gate,
 * port-conflict handling and discovery (/api/docs) are therefore the fleet's,
 * not re-implemented here.
 *
 * WHY: the git flow's static suites are green while the flow on the OWNED PHONE can
 * still be broken — a chain that falls through for a live-network reason, a listing
 * the edge redirects, a clone that rides the wrong leg. An agent in termux IS on the
 * phone, so these routes let it run the REAL chain/listing/clone in-process and read
 * the same outcomes the page shows.
 *
 * EVERY HANDLER REUSES THE PAGE'S PATH — [DriveGitChain.resolve], [FleetGit.repos],
 * [GitSyncCoordinator.cloneInto] — and never re-implements a verb. A route that
 * cloned through its own JGit call would be a second clone implementation whose
 * green says nothing about the page.
 *
 * NEVER A SECRET IN A RESPONSE. No token, cookie value or Authorization value is
 * ever echoed or logged: the chain route reads the outcome's narrative (whose
 * toString is redacted by design), the state route reports session PRESENCE as a
 * boolean only, listings carry names and owners but no URLs (a listing URL's
 * userinfo could carry a credential), and clone responses name only the URL's HOST
 * via [GitSyncCoordinator.hostOf]. The session route accepts a cookie and answers
 * with a boolean.
 *
 * EVERY RESPONSE CARRIES AN HONEST STATUS: `ok` plus the same loud words the page
 * would show. A clone that fails returns the engine's own failure sentence, never
 * an empty 200.
 */
object DriveDebugApi {

    private const val TAG = "DriveDebugApi"

    /** A clone over mobile data can be legitimately slow; past this the route
     *  reports timeout WITHOUT killing the clone — it keeps running and the
     *  outcome lands in /api/git/state's lastSync + the on-device debug log. */
    private const val CLONE_WAIT_MS = 120_000L
    private const val POLL_MS = 250L

    private const val TAIL_DEFAULT = 200
    private const val TAIL_MAX = 2_000

    @Volatile
    private var registered = false

    /** The page's own coordinator class over the SAME registry file and store the
     *  screen and the worker use — a repository cloned here and one cloned from
     *  the page are indistinguishable afterwards. */
    @Volatile
    private var coordinator: GitSyncCoordinator? = null

    private fun coordinator(ctx: Context): GitSyncCoordinator =
        coordinator ?: synchronized(this) {
            coordinator ?: GitSyncCoordinator(
                ctx.applicationContext,
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ).also { coordinator = it }
        }

    /** Idempotent; called by [DriveDebugApiProvider] before Application.onCreate. */
    fun register(ctx: Context) {
        if (registered) return
        registered = true
        val app = ctx.applicationContext

        AppDebugServer.route(
            "health",
            listOf(AppDebugServer.Op("", "", "app alive: applicationId, version_code, store root path")),
        ) { _, _ -> healthJson(app) }

        AppDebugServer.route(
            "git",
            listOf(
                AppDebugServer.Op("state", "", "registered repos (name, path, remote HOST only, authKind, lastSync) + whether a fleet session is present (boolean only)"),
                AppDebugServer.Op("chain", "", "run DriveGitChain.resolve with the in-process session; outcome narrative + answeredBy, never a token"),
                AppDebugServer.Op("list", "rung=<declared rung id, optional>", "run that rung's listing (DriveGitChain.repos: gh repo list for the github rung, FleetGit.repos for a fleet one) with the in-process session; count + names/owners, no URLs"),
                AppDebugServer.Op("status", "force=1 (optional: re-read even fresh entries)", "#850 the ONE status reader's per-repo result (clean / ahead N / behind N / dirty / not cloned / error(reason)) with read_at and took_ms; bounded by the declared timeout"),
                AppDebugServer.Op("pull", "force=1 (force pull = fetch + reset) & name=<repo, optional — blank = every clone>", "#850 the page's own pullAll on the one credential path; waits for every per-repo outcome (summaries only, never a credential)"),
                AppDebugServer.Op("clone", "name=<repo>&url=<clone url, optional — blank resolves it from the fleet listing the way the page does>", "clone through the page's own path (GitSyncCoordinator.cloneInto) and wait for the real outcome"),
                AppDebugServer.Op("bare", "url=<public clone url, comma-separated>&depth=<default 1>", "#1358 the Mirror all (bare) item path (StoreGit.bareClone in cld.termux at BareClones.path), one report line per repository with git's real error"),
            ),
        ) { op, query -> gitRoute(app, op, query) }

        AppDebugServer.route(
            "session",
            listOf(AppDebugServer.Op("", "cookie=<fleet session cookie>", "inject a fleet web session into FleetSession for test purposes; the value is held in-process only and NEVER echoed — the answer is a boolean")),
        ) { _, query -> sessionJson(query) }

        AppDebugServer.route(
            "log",
            listOf(AppDebugServer.Op("tail", "lines=N (default $TAIL_DEFAULT, max $TAIL_MAX)", "tail of the on-device debug log (Download/${BuildConfig.DEBUG_LOG_DIR}/)")),
        ) { op, query -> if (op == "tail") logTailJson(app, query) else null }

        // #813 /api/disk/<op> — the Disk Management engine the Home ▸ Disk page renders.
        com.diegonmarcos.clouddrive.disk.DiskDebugApi.register(app)
    }

    // ── /api/health ─────────────────────────────────────────────────────────

    private fun healthJson(ctx: Context): String {
        val root = runCatching { SharedStore.root().absolutePath }.getOrElse { "unavailable: ${it.message}" }
        // cloud-shared-store: direct (same uid as cld.termux) | exec (git runs in cld.termux) | none.
        val mode = runCatching { com.diegonmarcos.cloudlib.sharedstore.StoreAccess.mode(ctx).name.lowercase() }.getOrElse { "unknown" }
        // The git and rclone engine APKs are other uids: they reach the store only through cld.termux.
        val route = runCatching { if (com.diegonmarcos.cloudlib.sharedstore.StorePaths.viaOwner(com.diegonmarcos.cloudlib.sharedstore.StoreAccess.mode(ctx))) "cld.termux" else "unreachable" }.getOrElse { "unknown" }
        return """{"ok":true,"app":"${esc(ctx.packageName)}","version_code":${BuildConfig.VERSION_CODE},"version_name":"${esc(BuildConfig.VERSION_NAME)}","store_root":"${esc(root)}","store_mode":"${esc(mode)}","engine_route":"${esc(route)}","legacy_root":"${esc(SharedStore.legacyRoot().absolutePath)}"}"""
    }

    // ── /api/git/* ──────────────────────────────────────────────────────────

    private fun gitRoute(ctx: Context, op: String, query: Map<String, String>): String? = when (op) {
        "state" -> stateJson(ctx)
        "chain" -> chainJson(ctx)
        "list" -> listJson(ctx, query["rung"].orEmpty())
        "clone" -> cloneJson(ctx, query)
        "status" -> statusJson(ctx, query)
        "pull" -> pullJson(ctx, query)
        "bare" -> bareJson(ctx, query)
        else -> null
    }

    /** The registry the page and the worker share, plus session PRESENCE — a boolean
     *  and nothing else. The remote is reported as its HOST only: a full URL's
     *  userinfo could carry a credential. */
    private fun stateJson(ctx: Context): String {
        val repos = runCatching {
            RepoRegistry(File(ctx.filesDir, GitSyncWorker.REGISTRY_FILE)).load()
        }.getOrElse { return errJson("registry read failed: ${it.message ?: it}") }
        return buildString {
            append("""{"ok":true,"fleet_session_present":${FleetSession.present},"count":${repos.size},"repos":[""")
            repos.forEachIndexed { i, r ->
                if (i > 0) append(',')
                append("""{"name":"${esc(r.name)}",""")
                append(""""path":"${esc(r.path)}",""")
                append(""""remote_host":"${esc(GitSyncCoordinator.hostOf(r.remoteUrl))}",""")
                append(""""auth_kind":"${esc(r.authKind)}",""")
                append(""""last_sync_epoch":${r.lastSyncEpochSeconds},""")
                append(""""last_sync_summary":"${esc(r.lastSyncSummary)}"}""")
            }
            append("]}")
        }
    }

    /** The REAL chain, with the in-process session. The narrative is the page's own
     *  account of every rung; the outcome's token is deliberately never read here. */
    private fun chainJson(ctx: Context): String {
        val outcome = runCatching { DriveGitChain.resolve(ctx, session = FleetSession.cookie) }
            .getOrElse { return errJson("chain threw: ${it.message ?: it}") }
        return """{"ok":${outcome.ok},"answered_by":${jsonStr(outcome.answeredBy)},"narrative":"${esc(outcome.narrative())}"}"""
    }

    /** The REAL listing of the rung, dispatched on its declared kind (#735: the github
     *  rung lists through gh in the engine, never through the fleet lister). Names and
     *  owners only — the listing's clone URLs stay in-process (they feed /api/git/clone),
     *  because a listing URL can carry a credential in its userinfo. */
    private fun listJson(ctx: Context, rungId: String): String =
        when (val out = runCatching { DriveGitChain.repos(ctx, rungId, FleetSession.cookie) }
            .getOrElse { return errJson("listing threw: ${it.message ?: it}") }) {
            is FleetGit.Outcome.Listed -> buildString {
                append("""{"ok":true,"status":"listed","count":${out.repos.size},"repos":[""")
                out.repos.forEachIndexed { i, r ->
                    if (i > 0) append(',')
                    append("""{"name":"${esc(r.name)}","owner":"${esc(r.owner)}","private":${r.private}}""")
                }
                append("]}")
            }
            is FleetGit.Outcome.Refused ->
                """{"ok":false,"status":"refused","http":${out.code},"why":"${esc(out.why)}"}"""
            is FleetGit.Outcome.Blocked ->
                """{"ok":false,"status":"blocked","http":${out.code},"why":"the edge redirected without reaching the service"}"""
            is FleetGit.Outcome.Unreachable ->
                """{"ok":false,"status":"unreachable","why":"${esc(out.why)}"}"""
        }

    /**
     * THE PAGE'S CLONE, DRIVEN AND WAITED ON. Same decision order as
     * GitReposScreen.clone: a caller-supplied URL is the listing's truth; a blank one
     * is resolved the way the page resolves it — the chain names the rung that
     * answered, that rung's listing supplies the URL — and a repository the listing
     * does not carry is a loud error, never a re-templated guess (#669's wrong-leg
     * defect). The clone itself is ONLY [GitSyncCoordinator.cloneInto]; this route
     * then waits on the coordinator's own opResults for the engine's real outcome.
     *
     * #735 THE CREDENTIAL IS THE PAGE'S: a fleet-listed URL (the declared clone URL
     * for the listing item, FleetGit.cloneUrl, as the page's cloneViaFleet) rides the
     * fleet session; every other URL presents what the github rung answers — gh's own
     * credential from the engine first, then the one filed under the declared id
     * ([DriveGitChain.cloneAuth]). It never reached gh before, so a private repo gh
     * had just listed cloned with no credential at all.
     */
    private fun cloneJson(ctx: Context, query: Map<String, String>): String {
        val name = query["name"].orEmpty().trim()
        if (name.isBlank()) return errJson("clone needs ?name=<repo>")
        val c = coordinator(ctx)
        if (c.cloning.value.contains(name)) return errJson("'$name' is already cloning")

        var url = query["url"].orEmpty().trim()
        var leg = "caller-supplied url"
        var viaFleet = false
        if (url.isBlank()) {
            val chain = runCatching { DriveGitChain.resolve(ctx, session = FleetSession.cookie) }
                .getOrElse { return errJson("chain threw: ${it.message ?: it}") }
            if (!chain.ok) return errJson("no rung answered, so no listing can name the URL: ${chain.narrative()}")
            val rung = chain.answeredBy.orEmpty()
            when (val out = runCatching { DriveGitChain.repos(ctx, rung, FleetSession.cookie) }
                .getOrElse { return errJson("listing threw: ${it.message ?: it}") }) {
                is FleetGit.Outcome.Listed -> {
                    val hit = out.repos.firstOrNull { it.name == name }
                        ?: return errJson("'$name' is not in the listing (rung '$rung', ${out.repos.size} repos)")
                    viaFleet = DriveGitChain.rung(rung)?.kind == DriveGitChain.RUNG_FLEET
                    url = if (viaFleet) FleetGit.cloneUrl(rung, hit.owner, hit.name, hit.cloneUrl) else hit.cloneUrl
                    if (url.isBlank()) return errJson("the listing carries no clone URL for '$name'")
                    leg = "listing (rung '$rung')"
                }
                is FleetGit.Outcome.Refused -> return errJson("the fleet refused this identity (HTTP ${out.code})")
                is FleetGit.Outcome.Blocked -> return errJson("the edge redirected (HTTP ${out.code}) without reaching the service")
                is FleetGit.Outcome.Unreachable -> return errJson("the fleet is unreachable: ${out.why}")
            }
        }

        // The DESTINATION IS THE COORDINATOR'S TO PICK, not this route's: clones are
        // parallel per source (the same name from gitea and from GitHub are two
        // different clones), so the outcome is read back by DIFFING the
        // coordinator's own opResults rather than precomputing a store path.
        val before = c.opResults.value
        val (authKind, secret) = DriveGitChain.cloneAuth(viaFleet) {
            runCatching { DriveGitChain.githubCredential(ctx) }.getOrElse { GitAuthChain.Answer.Unreachable(it.message ?: "$it") }
        }
        c.cloneInto(
            name = name,
            url = url,
            authKind = authKind,
            username = GitSyncCoordinator.DEFAULT_AUTHOR,
            token = secret,
        )
        val host = GitSyncCoordinator.hostOf(url)
        fun outcome(): String? {
            val r = c.opResults.value.entries.firstOrNull { (k, v) -> before[k] !== v }?.value ?: return null
            return """{"ok":${r.ok},"status":"${if (r.ok) "cloned" else "failed"}","host":"${esc(host)}","leg":"${esc(leg)}","summary":"${esc(r.summary)}","details":"${esc(r.details)}"}"""
        }
        if (!c.cloning.value.contains(name)) {
            // Either it already finished (a very fast failure records its outcome
            // first) or cloneInto's own guard refused to start — tell which.
            return outcome()
                ?: errJson("the coordinator refused to start the clone (blank input or a duplicate in flight)")
        }

        val deadline = System.currentTimeMillis() + CLONE_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!c.cloning.value.contains(name)) {
                return outcome()
                    ?: errJson("the clone finished but recorded no outcome — read /api/git/state and /api/log/tail")
            }
            Thread.sleep(POLL_MS)
        }
        return """{"ok":false,"status":"timeout","host":"${esc(host)}","leg":"${esc(leg)}","why":"no outcome within ${CLONE_WAIT_MS / 1000}s — the clone keeps running; read /api/git/state and /api/log/tail for its result"}"""
    }

    /** #850 the reader the page reads — the process-wide one — asked and waited on, bounded. */
    private fun statusJson(ctx: Context, query: Map<String, String>): String {
        val force = query["force"] == "1" || query["force"] == "true"
        val c = coordinator(ctx)
        val wait = com.diegonmarcos.clouddrive.Declarations.sync.git.status.timeoutSeconds * 1000L * 4 + 5_000L
        val finished = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(wait) { c.refresh(force).join(); true } ?: false
        }
        val repos = c.repos.value
        val st = GitSyncCoordinator.statusReader.statuses.value
        return buildString {
            append("""{"ok":true,"finished":$finished,"count":${repos.size},"repos":[""")
            repos.forEachIndexed { i, r ->
                val s = st[GitSyncCoordinator.statusKey(r)]
                if (i > 0) append(',')
                append("""{"name":"${esc(r.name)}","state":"${esc(s?.state?.name?.lowercase() ?: "unrequested")}",""")
                append(""""label":"${esc(s?.label() ?: "unrequested")}","read_at_ms":${s?.readAtMs ?: 0},"took_ms":${s?.tookMs ?: 0}}""")
            }
            append("]}")
        }
    }

    /** #850 THE PAGE'S pullAll, driven and waited on — never a second pull implementation. */
    private fun pullJson(ctx: Context, query: Map<String, String>): String {
        val force = query["force"] == "1" || query["force"] == "true"
        val name = query["name"].orEmpty()
        val c = coordinator(ctx)
        val job = c.pullAll(force = force, trigger = "debug-api", only = if (name.isBlank()) null else setOf(name))
            ?: return errJson("a pull-all is already running — read /api/git/status afterwards")
        val finished = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(CLONE_WAIT_MS * 3) { job.join(); true } ?: false }
        val b = c.bulk.value
        return buildString {
            append("""{"ok":${finished && (b?.failed ?: 0) == 0},"finished":$finished,"force":$force,"total":${b?.total ?: 0},"failed":${b?.failed ?: 0},"results":[""")
            b?.results.orEmpty().entries.sortedBy { it.key }.forEachIndexed { i, e ->
                if (i > 0) append(',')
                append("""{"name":"${esc(e.key)}","ok":${e.value.ok},"summary":"${esc(e.value.summary)}"}""")
            }
            append("]}")
        }
    }

    // ── /api/session ────────────────────────────────────────────────────────

    /** GUARDED as the transport guards everything: loopback-only + fleet Bearer.
     *  The cookie is written into [FleetSession] — process memory, no store, same
     *  slot the sign-in host writes — and the answer is a BOOLEAN. There is no read
     *  route for it and this route never echoes it. */
    private fun sessionJson(query: Map<String, String>): String {
        val cookie = query["cookie"].orEmpty()
        if (cookie.isBlank()) {
            return errJson("need ?cookie=<fleet session> — held in process memory only, never echoed or persisted")
        }
        FleetSession.remember(cookie)
        return """{"ok":true,"session_present":${FleetSession.present}}"""
    }

    // ── /api/log/tail ───────────────────────────────────────────────────────

    private fun logTailJson(ctx: Context, query: Map<String, String>): String {
        val n = (query["lines"]?.toIntOrNull() ?: TAIL_DEFAULT).coerceIn(1, TAIL_MAX)
        val text = DriveDebugLog.tail(ctx, n)
        return """{"ok":true,"lines":$n,"text":"${esc(text)}"}"""
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** #1358 the bare clone of clone_all_bare, item by item, with no token (public URLs): the exit code and git's own stderr. */
    private fun bareJson(ctx: Context, query: Map<String, String>): String {
        val urls = query["url"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (urls.isEmpty()) return errJson("bare needs ?url=<clone url>[,<clone url>…]")
        val depth = query["depth"]?.toIntOrNull() ?: 1
        val route = com.diegonmarcos.clouddrive.sync.store.StoreRoute.of(ctx)
        if (!route.viaTermux) return errJson("the store is not reached through cld.termux (mode ${route.mode})")
        val gitGuest = route.guest(SharedStore.gitRoot().absolutePath) ?: return errJson("the store git folder has no guest path")
        val canonical: (String) -> String = { com.diegonmarcos.clouddrive.Declarations.repoSot.canonical(it) }
        val lines = urls.map { url ->
            val name = url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
            val dest = com.diegonmarcos.clouddrive.sync.BareClones.path(gitGuest, com.diegonmarcos.clouddrive.Declarations.sync.git.bareRoot, url, name, canonical)
                ?: return@map Triple(false, name, "no safe bare path")
            val r = com.diegonmarcos.clouddrive.sync.StoreGit.bareClone(ctx, url, dest, "", depth)
            Triple(r.ok, name, if (r.ok) r.summary else com.diegonmarcos.clouddrive.sync.BulkDepth.withTail(r.summary, r.details))
        }
        return buildString {
            append("""{"ok":${lines.all { it.first }},"results":[""")
            lines.forEachIndexed { i, (ok, name, msg) -> if (i > 0) append(','); append("""{"name":"${esc(name)}","ok":$ok,"report":"${esc(msg)}"}""") }
            append("]}")
        }
    }

    private fun errJson(why: String): String = """{"ok":false,"why":"${esc(why)}"}"""

    private fun jsonStr(s: String?): String = if (s == null) "null" else "\"${esc(s)}\""

    /** Same escaping the shared server uses, plus control chars a log tail can carry. */
    internal fun esc(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
}
