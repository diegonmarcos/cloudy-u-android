package com.diegonmarcos.cloudlib.gitsync

import kotlinx.serialization.Serializable

// The models are @Serializable because they cross the binder to and from the git sync engine (GitWire):
// the same classes on both sides, one codec. (#871 move 11)

/** How a path differs, on one side of the index (staged) or the other (worktree). */
@Serializable
enum class GitChange { ADDED, MODIFIED, DELETED, UNTRACKED }

/**
 * One path in `git status`. Both sides can be set at once (a file edited after
 * it was staged), which is exactly the state a stage/unstage UI has to show.
 */
@Serializable
data class GitFileStatus(
    val path: String,
    val staged: GitChange? = null,
    val unstaged: GitChange? = null,
    /** In a merge/rebase with unresolved markers; `stageState` is JGit's name for the shape. */
    val conflicting: Boolean = false,
    val stageState: String? = null,
) {
    val hasStaged: Boolean get() = staged != null
    val hasUnstaged: Boolean get() = unstaged != null
}

@Serializable
data class GitStatusSnapshot(
    val branch: String?,
    val upstream: String?,
    val ahead: Int,
    val behind: Int,
    val files: List<GitFileStatus>,
    /** Repository state as JGit names it: SAFE, MERGING, REBASING, … */
    val repositoryState: String,
) {
    val staged: List<GitFileStatus> get() = files.filter { it.hasStaged && !it.conflicting }
    val unstaged: List<GitFileStatus> get() = files.filter { it.hasUnstaged && !it.conflicting }
    val conflicts: List<GitFileStatus> get() = files.filter { it.conflicting }
    val isClean: Boolean get() = files.isEmpty()
}

@Serializable
data class GitCommitInfo(
    val sha: String,
    val summary: String,
    val message: String,
    val author: String,
    val email: String,
    /** Seconds since the epoch, the author time. */
    val time: Long,
    val parents: List<String>,
) {
    val shortSha: String get() = sha.take(8)
}

@Serializable
data class GitBranchInfo(
    /** Short name: `main`, or `origin/main` for a remote-tracking branch. */
    val name: String,
    val fullName: String,
    val isRemote: Boolean,
    val isCurrent: Boolean,
    val sha: String,
)

@Serializable
data class GitRemoteInfo(
    val name: String,
    val fetchUrl: String,
    val pushUrl: String,
)

/** Which side of a conflict wins when the user resolves a path wholesale. */
@Serializable
enum class ConflictSide { OURS, THEIRS }

/**
 * Transport credentials. HTTPS carries a username plus a token/password (what
 * GitHub, Gitea and GitLab all take). SSH carries a private key file; the
 * known-hosts file lives in the host app's private storage so a first contact
 * is recorded there and a changed key is refused afterwards (accept-new).
 */
sealed class GitAuth {
    object None : GitAuth()
    data class Https(val username: String, val secret: String) : GitAuth()
    data class Ssh(val privateKeyPath: String, val passphrase: String?, val knownHostsPath: String) : GitAuth()

    /**
     * #669 an HTTP SESSION riding a named header — an Authelia session cookie on a
     * gate-fronted git host. NOT a username/password pair: the gate takes the raw
     * header (e.g. `Cookie: authelia_session=...`), and sending the same value as
     * an Authorization bearer is MEASURED to collide with gitea's own token auth
     * (401), so the header NAME is data the declaration provides, never a literal.
     * The value lives in process memory only; [toString] is redacted so holding
     * this in state or a log line cannot leak it.
     */
    data class Session(val header: String, val value: String) : GitAuth() {
        override fun toString(): String = "Session(header=$header, value=<redacted>)"
    }
}

/** The outcome of a transport operation, in words the UI can show as-is. */
@Serializable
data class GitOpResult(val ok: Boolean, val summary: String, val details: String = "")

/**
 * One repository the manager knows about. Persisted by [RepoRegistry]; the
 * secret half of its auth lives in [GitCredentialStore], keyed by [id], and is
 * never serialised here.
 */
@Serializable
data class ManagedRepo(
    val id: String,
    val name: String,
    val path: String,
    val remoteUrl: String = "",
    /** "none" | "https" | "ssh" — which [GitAuth] shape the credential store holds for [id]. */
    val authKind: String = "none",
    val authUsername: String = "",
    val sshKeyPath: String = "",
    val authorName: String = "",
    val authorEmail: String = "",
    /** Pull with rebase instead of merge, GitSync's default. */
    val pullRebase: Boolean = true,
    /** Message used when Sync commits everything in one go. */
    val syncMessage: String = "sync from cloud-drive",
    /** #575 opted in to the host's scheduled background sync (GitSync's scheduler). */
    val autoSync: Boolean = false,
    /**
     * #579 this repository's own period, in minutes; 0 = the host's base period. The host's
     * scheduler ticks at its base period and skips a repository whose own period has not
     * elapsed since [lastSyncEpochSeconds] — so a repository may sync less often than the
     * base, never more often (WorkManager's floor is the base).
     */
    val syncIntervalMinutes: Long = 0,
    /** #579 this repository's network rule: only on an unmetered network (Wi-Fi), or on any. */
    val syncRequireUnmetered: Boolean = true,
    val lastSyncEpochSeconds: Long = 0,
    val lastSyncSummary: String = "",
)
