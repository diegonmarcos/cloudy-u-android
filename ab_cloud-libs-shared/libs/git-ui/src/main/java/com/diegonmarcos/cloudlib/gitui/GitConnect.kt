package com.diegonmarcos.cloudlib.gitui

import android.content.Context
import com.diegonmarcos.cloudlib.gitcore.GitErrorKind
import com.diegonmarcos.cloudlib.gitcore.GitException
import com.diegonmarcos.cloudlib.gitrepos.AutoCommit
import com.diegonmarcos.cloudlib.gitsync.GitAuth
import com.diegonmarcos.cloudlib.gitsync.GitCredentialStore
import com.diegonmarcos.cloudlib.gitsync.GitEngine
import com.diegonmarcos.cloudlib.gitsync.clone
import java.io.File

/**
 * #1445 THE ONE GitHub connect of the fleet's apps (Cloud Drive, Cloud Me, Cloud Account): a private repo is cloned
 * through libs:git-sync's in-process GitEngine, pulled on open, and a save is staged, committed with libs:git-repos'
 * Auto Commit message and pushed. The token is never held here: it comes from the host (the gh engine) or from
 * libs:git-sync's GitCredentialStore (SafeEncryptedPrefs), and is never logged. No app re-implements this
 * (ac_cloud-me/test/test-git-connect-one.sh fails on a second GitHub connect).
 */
data class GitConnectSpec(val id: String, val label: String, val owner: String, val repo: String, val ref: String, val paths: List<String>)

/** What a connect operation ended in; [kind] is set when it failed. */
class GitConnectResult(val ok: Boolean, val summary: String, val kind: GitErrorKind? = null) {
    val conflict: Boolean get() = kind == GitErrorKind.CONFLICT || kind == GitErrorKind.REJECTED
}

/** The pure rules, pinned by GitConnectRulesTest. */
object GitConnectRules {
    const val USER = "x-access-token"
    const val AUTHOR = "Cloud Account"
    const val AUTHOR_EMAIL = "noreply@users.noreply.github.com"

    fun url(s: GitConnectSpec) = "https://github.com/${s.owner}/${s.repo}.git"
    fun credentialId(s: GitConnectSpec) = "gitconnect:${s.owner}/${s.repo}"
    fun auth(token: String?): GitAuth = if (token.isNullOrBlank()) GitAuth.None else GitAuth.Https(USER, token)

    /** A declared path stays inside the clone: relative, no `..`, not blank. */
    fun safePath(p: String) = p.isNotBlank() && !p.startsWith("/") && p.split('/').none { it == ".." || it.isEmpty() }

    /** The commit message of a save: libs:git-repos' Auto Commit rule over the staged diff; a plain line when the diff is empty. */
    fun message(path: String, stagedDiff: String, sizeOf: (String) -> Long): String {
        val files = AutoCommit.stats(stagedDiff, sizeOf)
        return if (files.isEmpty()) "profile: update $path" else AutoCommit.message(files)
    }

    /** Read or write state for the page: no token = read only (public repos only), else read + write. */
    fun access(hasToken: Boolean) = if (hasToken) "read + write" else "read only (no token)"
}

class GitConnect(
    private val ctx: Context,
    val spec: GitConnectSpec,
    private val hostToken: (Context) -> String? = { null },
) {
    private val store by lazy { GitCredentialStore(ctx.applicationContext) }
    val dir: File = File(ctx.filesDir, "git-connect/${spec.owner}/${spec.repo}")

    /** The host's token (the gh engine) first, else the one stored encrypted by [saveToken]. */
    private fun token(): String? = hostToken(ctx)?.takeIf { it.isNotBlank() } ?: runCatching { store.secret(GitConnectRules.credentialId(spec)) }.getOrNull()
    fun hasToken(): Boolean = !token().isNullOrBlank()
    fun saveToken(t: String) = store.setSecret(GitConnectRules.credentialId(spec), t.trim())
    fun forgetToken() = store.clear(GitConnectRules.credentialId(spec))
    fun isCloned(): Boolean = GitEngine.isRepository(dir)

    private fun engine() = GitEngine(ctx, dir)
    private fun fail(t: Throwable) = GitConnectResult(false, t.message.orEmpty().lines().firstOrNull().orEmpty().take(200),
        (t as? GitException)?.kind ?: GitErrorKind.classify(t.message))

    /** Pull on open: clone shallow the first time, then pull --rebase. BLOCKS: call on IO. */
    fun open(): GitConnectResult = runCatching {
        val auth = GitConnectRules.auth(token())
        if (!isCloned()) {
            dir.parentFile?.mkdirs()
            GitEngine.clone(ctx, GitConnectRules.url(spec), dir, auth, depth = 1)
            GitConnectResult(true, "cloned ${spec.repo}")
        } else {
            val r = engine().pull(auth = auth)
            if (r.ok) GitConnectResult(true, r.summary) else GitConnectResult(false, r.summary, GitErrorKind.classify(r.summary + " " + r.details))
        }
    }.getOrElse(::fail)

    fun read(path: String): String? = if (!GitConnectRules.safePath(path)) null else File(dir, path).takeIf { it.isFile }?.readText()

    /** Save = write, stage, Auto Commit message, commit, pull --rebase, push. BLOCKS: call on IO. */
    fun save(path: String, text: String): GitConnectResult = runCatching {
        require(GitConnectRules.safePath(path)) { "path outside the repository" }
        val auth = GitConnectRules.auth(token())
        val f = File(dir, path)
        f.parentFile?.mkdirs()
        f.writeText(text)
        val e = engine()
        e.stage(listOf(path))
        val diff = e.diff(path, staged = true)
        if (diff.isBlank()) return@runCatching GitConnectResult(true, "nothing changed")
        e.commit(GitConnectRules.message(path, diff) { File(dir, it).length() }, GitConnectRules.AUTHOR, GitConnectRules.AUTHOR_EMAIL)
        val pull = e.pull(auth = auth)
        if (!pull.ok) return@runCatching GitConnectResult(false, pull.summary, GitErrorKind.classify(pull.summary + " " + pull.details).let { if (it == GitErrorKind.UNKNOWN) GitErrorKind.CONFLICT else it })
        val push = e.push(auth = auth)
        if (push.ok) GitConnectResult(true, "committed and pushed $path") else GitConnectResult(false, push.summary, GitErrorKind.classify(push.summary + " " + push.details))
    }.getOrElse(::fail)

    /** The unified diff of the local copy of [path] against its last commit (shown on a conflict). */
    fun diff(path: String): String = runCatching { engine().diff(path) }.getOrDefault("")

    /** Conflict: keep mine (force push) or take theirs (force pull). */
    fun keepMine(): GitConnectResult = runCatching { engine().forcePush(auth = GitConnectRules.auth(token())).let { GitConnectResult(it.ok, it.summary, if (it.ok) null else GitErrorKind.classify(it.summary)) } }.getOrElse(::fail)
    fun takeTheirs(): GitConnectResult = runCatching { engine().forcePull(auth = GitConnectRules.auth(token())).let { GitConnectResult(it.ok, it.summary, if (it.ok) null else GitErrorKind.classify(it.summary)) } }.getOrElse(::fail)
}
