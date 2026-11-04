package com.diegonmarcos.cloudlib.gitcore

import com.diegonmarcos.cloudlib.gitsync.ConflictSide
import com.diegonmarcos.cloudlib.gitsync.GitAuth
import com.diegonmarcos.cloudlib.gitsync.GitBranchInfo
import com.diegonmarcos.cloudlib.gitsync.GitChange
import com.diegonmarcos.cloudlib.gitsync.GitCommitInfo
import com.diegonmarcos.cloudlib.gitsync.GitFileStatus
import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import com.diegonmarcos.cloudlib.gitsync.GitRemoteInfo
import com.diegonmarcos.cloudlib.gitsync.GitStatusSnapshot
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.File
import org.eclipse.jgit.api.CheckoutCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.RemoteSetUrlCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.TransportHttp
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.util.FS

/**
 * The git manager's engine proper (#1408: in-process inside the host app, behind GitCalls; libs:git-sync's thin GitEngine client instead): every verb the manager screen offers, over JGit,
 * on ONE repository. Blocking — callers run it on an IO dispatcher.
 *
 * Verbs, in the order the brief names them: status, stage/unstage, commit,
 * push/pull (#608 adds the two FORCE variants, each destructive on exactly one
 * side and named so the caller cannot mistake which), branch list (READ-ONLY — the fleet rule is that agents never
 * create branches, and a manager may display them but this engine has no
 * verb that creates, deletes or checks out one), log, diff, remotes, conflicts.
 *
 * Nothing here knows about Android: it is exercised end-to-end by the JVM
 * test suite against real repositories in a temp directory, pushing and
 * pulling over the file:// transport, so the verbs are proven on the same
 * code that runs on the phone.
 */
class JGitEngine(val workTree: File) : AutoCloseable {

    private val git: Git = Git.open(workTree)
    private val repo: Repository get() = git.repository

    override fun close() = git.close()

    // ── status ───────────────────────────────────────────────────────────

    fun status(): GitStatusSnapshot {
        val st = git.status().call()
        val byPath = linkedMapOf<String, GitFileStatus>()
        fun mark(path: String, staged: GitChange? = null, unstaged: GitChange? = null) {
            val prev = byPath[path] ?: GitFileStatus(path)
            byPath[path] = prev.copy(staged = staged ?: prev.staged, unstaged = unstaged ?: prev.unstaged)
        }
        st.added.forEach { mark(it, staged = GitChange.ADDED) }
        st.changed.forEach { mark(it, staged = GitChange.MODIFIED) }
        st.removed.forEach { mark(it, staged = GitChange.DELETED) }
        st.modified.forEach { mark(it, unstaged = GitChange.MODIFIED) }
        st.missing.forEach { mark(it, unstaged = GitChange.DELETED) }
        st.untracked.forEach { mark(it, unstaged = GitChange.UNTRACKED) }
        val stageStates = st.conflictingStageState
        st.conflicting.forEach { path ->
            val prev = byPath[path] ?: GitFileStatus(path)
            byPath[path] = prev.copy(conflicting = true, stageState = stageStates[path]?.name)
        }
        val branch = repo.branch
        val tracking = branch?.let { runCatching { BranchTrackingStatus.of(repo, it) }.getOrNull() }
        return GitStatusSnapshot(
            branch = branch,
            upstream = tracking?.remoteTrackingBranch?.let { Repository.shortenRefName(it) },
            ahead = tracking?.aheadCount ?: 0,
            behind = tracking?.behindCount ?: 0,
            files = byPath.values.sortedBy { it.path },
            repositoryState = repo.repositoryState.name,
        )
    }

    // ── stage / unstage / discard ────────────────────────────────────────

    fun stage(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val (present, gone) = paths.partition { File(workTree, it).exists() }
        if (present.isNotEmpty()) git.add().apply { present.forEach { addFilepattern(it) } }.call()
        // A deleted file is staged with rm --cached: `add` only sees what is on disk.
        if (gone.isNotEmpty()) git.rm().setCached(true).apply { gone.forEach { addFilepattern(it) } }.call()
    }

    fun stageAll() = stage(status().files.filter { it.hasUnstaged }.map { it.path })

    fun unstage(paths: Collection<String>) {
        if (paths.isEmpty()) return
        if (repo.resolve(Constants.HEAD) == null) {
            // Unborn branch: there is no HEAD tree to reset the index to, so
            // "unstage" means "forget the index entry" and the file stays on disk.
            git.rm().setCached(true).apply { paths.forEach { addFilepattern(it) } }.call()
        } else {
            git.reset().apply { paths.forEach { addPath(it) } }.call()
        }
    }

    fun unstageAll() = unstage(status().files.filter { it.hasStaged }.map { it.path })

    /** Throw away the worktree change on [path]: an untracked file is deleted, a tracked one restored from the index. */
    fun discard(path: String) {
        val entry = status().files.firstOrNull { it.path == path } ?: return
        if (entry.unstaged == GitChange.UNTRACKED) {
            File(workTree, path).deleteRecursively()
            return
        }
        if (entry.hasStaged) unstage(listOf(path))
        git.checkout().addPath(path).call()
    }

    // ── commit ───────────────────────────────────────────────────────────

    fun commit(message: String, authorName: String, authorEmail: String, amend: Boolean = false): GitCommitInfo {
        require(message.isNotBlank()) { "commit message is empty" }
        val c = git.commit()
            .setMessage(message)
            .setAuthor(authorName, authorEmail)
            .setCommitter(authorName, authorEmail)
            .setAmend(amend)
            .call()
        return GitCommitInfo(
            sha = c.name, summary = c.shortMessage, message = c.fullMessage,
            author = c.authorIdent.name, email = c.authorIdent.emailAddress,
            time = c.authorIdent.whenAsInstant.epochSecond, parents = c.parents.map { it.name },
        )
    }

    // ── push / pull / fetch ──────────────────────────────────────────────

    fun push(remote: String = "origin", auth: GitAuth = GitAuth.None): GitOpResult {
        val branch = repo.branch ?: return GitOpResult(false, "no current branch")
        val cmd = git.push().setRemote(remote).withAuth(auth)
        // JGit follows C git's push.default=simple: with no upstream configured it
        // throws "no upstream" rather than pushing the current branch. The first
        // push of a branch names the refspec explicitly — <branch>:<branch> on the
        // remote — and the upstream is recorded below, so every later push is plain.
        if (repo.config.getString("branch", branch, "merge") == null) {
            cmd.setRefSpecs(RefSpec(Constants.R_HEADS + branch + ":" + Constants.R_HEADS + branch))
        }
        val results = cmd.call()
        val updates = results.flatMap { it.remoteUpdates }
        val bad = updates.filter { it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE }
        if (bad.isNotEmpty()) {
            return GitOpResult(false, "push rejected: " + bad.joinToString { "${it.remoteName} ${it.status}" },
                bad.joinToString("\n") { it.message ?: "" } + results.joinToString("\n") { it.messages })
        }
        // First push of a branch: record the upstream the way `git push -u` does,
        // so ahead/behind and pull have something to track from now on.
        val cfg = repo.config
        if (cfg.getString("branch", branch, "remote") == null) {
            cfg.setString("branch", branch, "remote", remote)
            cfg.setString("branch", branch, "merge", Constants.R_HEADS + branch)
            cfg.save()
        }
        val upToDate = updates.all { it.status == RemoteRefUpdate.Status.UP_TO_DATE }
        return GitOpResult(true, if (upToDate) "already up to date" else "pushed $branch to $remote")
    }

    fun fetch(remote: String = "origin", auth: GitAuth = GitAuth.None): GitOpResult {
        val r = git.fetch().setRemote(remote).withAuth(auth).call()
        val n = r.trackingRefUpdates.size
        return GitOpResult(true, if (n == 0) "fetched $remote: nothing new" else "fetched $remote: $n ref(s) updated", r.messages)
    }

    /**
     * Pull the current branch from [remote]. A branch that has never been pushed
     * has no upstream in config; JGit refuses that, so the remote branch of the
     * same name is named explicitly. A conflict is NOT an exception: it returns
     * ok=false and [status] then lists the conflicting paths for the UI.
     */
    fun pull(remote: String = "origin", rebase: Boolean = true, auth: GitAuth = GitAuth.None): GitOpResult {
        val branch = repo.branch ?: return GitOpResult(false, "no current branch")
        val cmd = git.pull().setRemote(remote).setRebase(rebase).withAuth(auth)
        if (repo.config.getString("branch", branch, "merge") == null) {
            // Never pushed: JGit would throw "did not advertise Ref" against a
            // remote that has no such branch yet (the first sync into a fresh
            // bare repository). Look first, and say so instead of failing.
            fetch(remote, auth)
            if (repo.findRef(Constants.R_REMOTES + remote + "/" + branch) == null) {
                return GitOpResult(true, "remote $remote has no $branch yet — nothing to pull")
            }
            cmd.setRemoteBranchName(branch)
        }
        val r = cmd.call()
        if (r.isSuccessful) {
            val merge = r.mergeResult?.mergeStatus?.name ?: r.rebaseResult?.status?.name ?: "ok"
            return GitOpResult(true, "pulled $remote/$branch: ${merge.lowercase().replace('_', ' ')}")
        }
        val conflicts = r.mergeResult?.conflicts?.keys?.sorted()
            ?: status().conflicts.map { it.path }
        val why = r.mergeResult?.mergeStatus?.let { if (it == MergeResult.MergeStatus.CONFLICTING) "conflicts" else it.name.lowercase() }
            ?: r.rebaseResult?.status?.name?.lowercase() ?: "failed"
        return GitOpResult(false, "pull $why: ${conflicts.size} file(s)", conflicts.joinToString("\n"))
    }

    /**
     * #608 FORCE PUSH: the local branch overwrites the remote one. The refspec is
     * named explicitly and `setForce` takes the fast-forward check off, so a
     * rewritten history (an amend, a rebase) lands instead of being rejected as
     * non-fast-forward. DESTRUCTIVE ON THE REMOTE — the caller confirms first; the
     * engine does not guess.
     */
    fun forcePush(remote: String = "origin", auth: GitAuth = GitAuth.None): GitOpResult {
        val branch = repo.branch ?: return GitOpResult(false, "no current branch")
        val results = git.push().setRemote(remote).setForce(true)
            .setRefSpecs(RefSpec(Constants.R_HEADS + branch + ":" + Constants.R_HEADS + branch))
            .withAuth(auth).call()
        val updates = results.flatMap { it.remoteUpdates }
        val bad = updates.filter { it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE }
        if (bad.isNotEmpty()) {
            return GitOpResult(false, "force push rejected: " + bad.joinToString { "${it.remoteName} ${it.status}" },
                bad.joinToString("\n") { it.message ?: "" } + results.joinToString("\n") { it.messages })
        }
        val cfg = repo.config
        if (cfg.getString("branch", branch, "remote") == null) {
            cfg.setString("branch", branch, "remote", remote)
            cfg.setString("branch", branch, "merge", Constants.R_HEADS + branch)
            cfg.save()
        }
        return GitOpResult(true, "force-pushed $branch to $remote")
    }

    /**
     * #608 FORCE PULL: the remote branch overwrites the local one. Fetch, then
     * `reset --hard` onto `<remote>/<branch>` — so a divergence that [pull] would
     * report as a conflict is resolved by throwing the local side away. DESTRUCTIVE
     * LOCALLY (local commits AND worktree edits are discarded); the caller confirms.
     * A remote that has no such branch yet is said so, not reset to nothing.
     */
    fun forcePull(remote: String = "origin", auth: GitAuth = GitAuth.None): GitOpResult {
        val branch = repo.branch ?: return GitOpResult(false, "no current branch")
        val fetched = fetch(remote, auth)
        if (!fetched.ok) return GitOpResult(false, "force pull: ${fetched.summary}", fetched.details)
        val ref = repo.findRef(Constants.R_REMOTES + remote + "/" + branch)
            ?: return GitOpResult(false, "remote $remote has no $branch — nothing to reset onto")
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(ref.name).call()
        return GitOpResult(true, "force-pulled $remote/$branch: reset to " + (ref.objectId?.name?.take(8) ?: "?"))
    }

    /** GitSync's one-tap sync: stage everything, commit if anything changed, pull, push. */
    fun sync(message: String, authorName: String, authorEmail: String, remote: String = "origin",
             rebase: Boolean = true, auth: GitAuth = GitAuth.None): GitOpResult {
        val steps = mutableListOf<String>()
        stageAll()
        if (status().staged.isNotEmpty()) {
            val c = commit(message, authorName, authorEmail)
            steps += "committed ${c.shortSha}"
        } else steps += "nothing to commit"
        if (remotes().none { it.name == remote }) return GitOpResult(true, steps.joinToString(", ") + ", no remote '$remote'")
        val pulled = pull(remote, rebase, auth)
        steps += pulled.summary
        if (!pulled.ok) return GitOpResult(false, steps.joinToString(", "), pulled.details)
        val pushed = push(remote, auth)
        steps += pushed.summary
        return GitOpResult(pushed.ok, steps.joinToString(", "), pushed.details)
    }

    // ── branches (read-only) ─────────────────────────────────────────────

    // ── compound ops Cloud Drive's sheet runs (#1408: in-process, the same names the Termux wire answers) ──

    /** Sync: no winner. Fetch, then a real merge of the upstream (no rebase, no force); conflicts stop it and are named. */
    fun mergeUpstream(remote: String = "origin", auth: GitAuth = GitAuth.None): GitOpResult = pull(remote, rebase = false, auth = auth)

    /** Sync: local wins. Force push guarded by a lease: refused when the remote moved past what was last fetched. */
    fun forcePushLease(remote: String = "origin", auth: GitAuth = GitAuth.None): GitOpResult {
        val branch = repo.branch ?: return GitOpResult(false, "no current branch")
        val tracking = Constants.R_REMOTES + remote + "/" + branch
        val cmd = git.push().setRemote(remote).setForce(true)
            .setRefSpecs(RefSpec(Constants.R_HEADS + branch + ":" + Constants.R_HEADS + branch))
            .withAuth(auth)
        if (repo.findRef(tracking) != null) cmd.setRefLeaseSpecs(org.eclipse.jgit.transport.RefLeaseSpec(Constants.R_HEADS + branch, tracking))
        val results = cmd.call()
        val bad = results.flatMap { it.remoteUpdates }.filter { it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE }
        if (bad.isNotEmpty()) return GitOpResult(false, "push with lease rejected: " + bad.joinToString { "${it.remoteName} ${it.status}" })
        return GitOpResult(true, "pushed $branch to $remote with lease")
    }

    /** The default branch: main, else master. */
    fun checkoutMain(): GitOpResult {
        for (b in listOf("main", "master")) {
            if (repo.findRef(Constants.R_HEADS + b) != null) { git.checkout().setName(b).call(); return GitOpResult(true, "on $b") }
        }
        return GitOpResult(false, "no main or master branch")
    }

    /** Continue a rebase once every conflict is staged; nothing to continue is a plain ok. */
    fun rebaseContinue(): GitOpResult {
        if (!repo.repositoryState.isRebasing) return GitOpResult(true, "no rebase in progress")
        val r = git.rebase().setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.CONTINUE).call()
        return GitOpResult(r.status.isSuccessful, "rebase " + r.status.name.lowercase().replace('_', ' '))
    }

    // ── stash and upstream (#1408) ───────────────────────────────────────

    /** Stashes the working tree and index (untracked files too); ok with "nothing to stash" when clean. */
    fun stashPush(message: String = "", includeUntracked: Boolean = true): GitOpResult {
        val cmd = git.stashCreate().setIncludeUntracked(includeUntracked)
        if (message.isNotBlank()) cmd.setWorkingDirectoryMessage(message)
        val made = cmd.call() ?: return GitOpResult(true, "nothing to stash")
        return GitOpResult(true, "stashed " + made.name.take(8))
    }

    /** Applies the newest stash and drops it; a conflicting apply keeps the stash and names the files. */
    fun stashPop(): GitOpResult {
        if (stashList().isEmpty()) return GitOpResult(true, "no stash to pop")
        try {
            git.stashApply().setStashRef("stash@{0}").setRestoreUntracked(true).call()
        } catch (e: org.eclipse.jgit.api.errors.StashApplyFailureException) {
            val conflicts = status().conflicts.map { it.path }
            return GitOpResult(false, "stash pop conflicts: ${conflicts.size} file(s)", conflicts.joinToString("\n"))
        }
        val conflicts = status().conflicts.map { it.path }
        if (conflicts.isNotEmpty()) return GitOpResult(false, "stash pop conflicts: ${conflicts.size} file(s)", conflicts.joinToString("\n"))
        git.stashDrop().setStashRef(0).call()
        return GitOpResult(true, "popped stash")
    }

    /** The stash entries, newest first, as their messages. */
    fun stashList(): List<String> = git.stashList().call().map { it.fullMessage }

    /** Sets the current branch's upstream to [remote]/[branch] (default: the same name). */
    fun setUpstream(remote: String = "origin", branch: String? = null): GitOpResult {
        val local = repo.branch ?: return GitOpResult(false, "no current branch")
        val target = branch?.ifBlank { null } ?: local
        if (remotes().none { it.name == remote }) return GitOpResult(false, "no remote '$remote'")
        val cfg = repo.config
        cfg.setString("branch", local, "remote", remote)
        cfg.setString("branch", local, "merge", Constants.R_HEADS + target)
        cfg.save()
        return GitOpResult(true, "upstream of $local is $remote/$target")
    }

    /** The current branch's upstream as "remote/branch", or null when none is set. */
    fun upstream(): String? {
        val local = repo.branch ?: return null
        val r = repo.config.getString("branch", local, "remote") ?: return null
        val m = repo.config.getString("branch", local, "merge") ?: return null
        return r + "/" + Repository.shortenRefName(m)
    }

    fun branches(): List<GitBranchInfo> {
        val current = repo.fullBranch
        return git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call()
            .filter { it.name != Constants.HEAD }
            .map { ref ->
                val remote = ref.name.startsWith(Constants.R_REMOTES)
                GitBranchInfo(
                    name = Repository.shortenRefName(ref.name), fullName = ref.name, isRemote = remote,
                    isCurrent = ref.name == current, sha = ref.objectId?.name ?: "",
                )
            }
            .sortedWith(compareBy({ it.isRemote }, { it.name }))
    }

    // ── log ──────────────────────────────────────────────────────────────

    fun log(max: Int = 100, path: String? = null): List<GitCommitInfo> {
        val cmd = git.log().setMaxCount(max)
        if (path != null) cmd.addPath(path)
        val commits = try { cmd.call() } catch (e: NoHeadException) { return emptyList() }
        return commits.map { c ->
            GitCommitInfo(
                sha = c.name, summary = c.shortMessage, message = c.fullMessage,
                author = c.authorIdent.name, email = c.authorIdent.emailAddress,
                time = c.authorIdent.whenAsInstant.epochSecond, parents = c.parents.map { it.name },
            )
        }
    }

    // ── diff ─────────────────────────────────────────────────────────────

    /** Unified diff of the worktree (or the index, when [staged]) — one path or everything. */
    fun diff(path: String? = null, staged: Boolean = false): String {
        val out = ByteArrayOutputStream()
        val cmd = git.diff().setCached(staged).setOutputStream(out)
        if (path != null) cmd.setPathFilter(PathFilter.create(path))
        cmd.call()
        val text = out.toString(Charsets.UTF_8.name())
        if (text.isNotEmpty() || path == null || staged) return text
        // An untracked file has no index side to diff against; show it whole so
        // the tap on it in the Changes list is never an empty screen.
        val f = File(workTree, path)
        if (!f.isFile) return ""
        val entry = status().files.firstOrNull { it.path == path }
        if (entry?.unstaged != GitChange.UNTRACKED) return ""
        return buildString {
            append("--- /dev/null\n+++ b/").append(path).append('\n')
            f.readLines().forEach { append('+').append(it).append('\n') }
        }
    }

    /** Unified diff of one commit against its first parent (or the empty tree for a root commit). */
    fun commitDiff(sha: String): String {
        val out = ByteArrayOutputStream()
        RevWalk(repo).use { walk ->
            val id = repo.resolve(sha) ?: return ""
            val commit = walk.parseCommit(id)
            repo.newObjectReader().use { reader ->
                val newTree = CanonicalTreeParser(null, reader, commit.tree)
                val oldTree = if (commit.parentCount > 0) {
                    CanonicalTreeParser(null, reader, walk.parseCommit(commit.getParent(0)).tree)
                } else EmptyTreeIterator()
                DiffFormatter(out).use { fmt ->
                    fmt.setRepository(repo)
                    fmt.setContext(3)
                    fmt.format(fmt.scan(oldTree, newTree))
                }
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    // ── remotes ──────────────────────────────────────────────────────────

    fun remotes(): List<GitRemoteInfo> = git.remoteList().call().map { rc ->
        val fetch = rc.getURIs().firstOrNull()?.toString() ?: ""
        GitRemoteInfo(rc.name, fetch, rc.getPushURIs().firstOrNull()?.toString() ?: fetch)
    }

    fun addRemote(name: String, url: String) {
        git.remoteAdd().setName(name).setUri(URIish(url)).call()
    }

    fun removeRemote(name: String) {
        git.remoteRemove().setRemoteName(name).call()
    }

    fun setRemoteUrl(name: String, url: String, push: Boolean = false) {
        git.remoteSetUrl().setRemoteName(name).setRemoteUri(URIish(url))
            .setUriType(if (push) RemoteSetUrlCommand.UriType.PUSH else RemoteSetUrlCommand.UriType.FETCH).call()
    }

    // ── conflicts ────────────────────────────────────────────────────────
    // The conflict SURFACE is status().conflicts — one index diff, one snapshot,
    // no second verb to keep in step with it. What follows is how it is resolved.

    /** Take one whole side of a conflicted path and stage it as resolved. */
    fun resolve(path: String, side: ConflictSide) {
        val stage = if (side == ConflictSide.OURS) CheckoutCommand.Stage.OURS else CheckoutCommand.Stage.THEIRS
        git.checkout().setStage(stage).addPath(path).call()
        git.add().addFilepattern(path).call()
    }

    /** The user edited the markers away by hand: stage the path as resolved. */
    fun markResolved(path: String) {
        git.add().addFilepattern(path).call()
    }

    /** Abort an in-progress merge: back to HEAD, worktree included. */
    fun abortMerge() {
        git.reset().setMode(ResetCommand.ResetType.HARD).call()
    }

    // ── transport plumbing ───────────────────────────────────────────────

    private fun <C : org.eclipse.jgit.api.TransportCommand<C, *>> C.withAuth(auth: GitAuth): C {
        when (auth) {
            is GitAuth.None -> Unit
            is GitAuth.Https -> setCredentialsProvider(UsernamePasswordCredentialsProvider(auth.username, auth.secret))
            is GitAuth.Ssh -> setTransportConfigCallback(sshCallback(auth))
            is GitAuth.Session -> setTransportConfigCallback(sessionCallback(auth))
        }
        return this
    }

    companion object {
        fun isRepository(dir: File): Boolean = File(dir, ".git").exists()

        /** #1452 a bare repository: the folder itself holds HEAD and objects (what a bare clone leaves). */
        fun isBare(dir: File): Boolean = File(dir, "HEAD").isFile && File(dir, "objects").isDirectory && !File(dir, ".git").exists()

        fun init(dir: File): JGitEngine {
            Git.init().setDirectory(dir).call().close()
            return JGitEngine(dir)
        }

        /**
         * @param depth above 0 clones SHALLOW to that many commits (JGit's CloneCommand.setDepth).
         *   #603 the host seeds its store with depth 1: a working tree is what the user wants and
         *   the full history is megabytes it never asked for. 0 keeps the complete clone.
         */
        fun clone(url: String, dir: File, auth: GitAuth = GitAuth.None, depth: Int = 0, bare: Boolean = false): JGitEngine {
            val cmd = Git.cloneRepository().setURI(url).setDirectory(dir)
            // #1452 [bare]: only the repository itself (no working tree, no .git folder: [dir] IS the git dir); with [depth] 1 it is the latest commit of each ref only.
            if (bare) cmd.setBare(true)
            if (depth > 0) cmd.setDepth(depth)
            when (auth) {
                is GitAuth.None -> Unit
                is GitAuth.Https -> cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider(auth.username, auth.secret))
                is GitAuth.Ssh -> cmd.setTransportConfigCallback(sshCallback(auth))
                is GitAuth.Session -> cmd.setTransportConfigCallback(sessionCallback(auth))
            }
            cmd.call().close()
            return JGitEngine(dir)
        }

        /**
         * SSH through jsch (the maintained mwiede fork, same package as the
         * original): the given key is the ONLY identity, and host keys are
         * recorded on first contact into the app's own known_hosts and refused
         * when they change — `accept-new`, not `no`.
         */
        internal fun sshCallback(auth: GitAuth.Ssh): TransportConfigCallback {
            val factory = object : JschConfigSessionFactory() {
                override fun configure(hc: OpenSshConfig.Host, session: Session) {
                    session.setConfig("StrictHostKeyChecking", "accept-new")
                }
                override fun createDefaultJSch(fs: FS): JSch {
                    val jsch = super.createDefaultJSch(fs)
                    jsch.removeAllIdentity()
                    File(auth.knownHostsPath).parentFile?.mkdirs()
                    jsch.setKnownHosts(auth.knownHostsPath)
                    if (auth.passphrase.isNullOrEmpty()) jsch.addIdentity(auth.privateKeyPath)
                    else jsch.addIdentity(auth.privateKeyPath, auth.passphrase)
                    return jsch
                }
            }
            return TransportConfigCallback { transport ->
                if (transport is SshTransport) transport.setSshSessionFactory(factory)
            }
        }

        /**
         * #669 the SESSION rides the HTTP transport as the one declared header —
         * JGit's [TransportHttp.setAdditionalHeaders] — and touches nothing else:
         * no credentials provider (nothing to answer a 401 challenge with, because
         * the gate this serves never issues one the session could satisfy) and no
         * effect on a non-HTTP transport, so a Session against an ssh:// remote is
         * simply anonymous rather than a crash. The header NAME comes with the
         * auth (declared upstream, e.g. Cookie); this engine never spells the
         * Authorization header, which the fleet's gitea is measured to reject.
         */
        internal fun sessionCallback(auth: GitAuth.Session): TransportConfigCallback =
            TransportConfigCallback { transport ->
                if (transport is TransportHttp) transport.setAdditionalHeaders(mapOf(auth.header to auth.value))
            }

        /** Exposed for the host: a credentials provider for HTTPS remotes when it needs one outside the engine. */
        fun httpsCredentials(username: String, secret: String): CredentialsProvider =
            UsernamePasswordCredentialsProvider(username, secret)
    }
}
