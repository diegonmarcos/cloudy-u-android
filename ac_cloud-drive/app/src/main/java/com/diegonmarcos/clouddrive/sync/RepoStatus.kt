package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitrepos.*

import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.cloudlib.gitsync.GitCommitInfo

/**
 * The repository row's status, as pure rules (JVM-tested): what the leading light means, why it can be "?", the
 * STATUS block at the top of the open repository panel, a probe error as a human sentence, and the unpushed list.
 *
 * THE "?" ROOT CAUSE this replaces: the light was UNKNOWN ("?") for a repository that is not cloned (most listed
 * repositories), for one whose status is not read yet, for one whose probe FAILED (the reason was dropped, nothing
 * on the row said so) and, worst, for a cloned repository whose status read fine but which the scheduler had
 * never synced (`lastSyncEpochSeconds == 0`). The light now follows the real status; every remaining "?" has a
 * named kind, a legend entry and a tooltip.
 */
object RepoStatus {
    /**
     * The row states, declaratively: each kind carries its light (glyph + colour come from [StatusLight]) and the
     * label is the matching `repo_kind_*` string. A read clone is never UNKNOWN: only NOT_CLONED and READING draw "?"
     * (MISSING too: the folder is gone, nothing can be read).
     */
    enum class Kind(val light: StatusLight.State) {
        NOT_CLONED(StatusLight.State.UNKNOWN), READING(StatusLight.State.UNKNOWN),
        /** Cloned, but no check has ever run (empty cache): shown as "not checked yet" until the owner pulls to refresh. Not a read in flight. */
        NOT_CHECKED(StatusLight.State.UNVERIFIABLE), MISSING(StatusLight.State.UNVERIFIABLE),
        /** Size guard: the working tree was not scanned (large repo, tap to check). */
        LARGE(StatusLight.State.UNVERIFIABLE),
        ERROR(StatusLight.State.OFF), CONFLICT(StatusLight.State.OFF), DIRTY(StatusLight.State.OFF),
        DIVERGED(StatusLight.State.OFF), NO_UPSTREAM(StatusLight.State.OFF),
        BEHIND(StatusLight.State.ON), AHEAD(StatusLight.State.ON), CLEAN(StatusLight.State.ON),
    }

    /** The kind of one row: [cloned] says whether a clone is registered, [g] is the status reader's last answer for it. */
    fun kindOf(cloned: Boolean, g: GitSyncCoordinator.Glance?): Kind = when {
        !cloned -> Kind.NOT_CLONED
        g == null -> Kind.NOT_CHECKED
        !g.read -> Kind.READING
        g.gone -> Kind.MISSING
        g.error != null -> Kind.ERROR
        g.large -> Kind.LARGE
        g.conflicts > 0 -> Kind.CONFLICT
        g.changed > 0 -> Kind.DIRTY
        g.upstream == null -> Kind.NO_UPSTREAM
        g.ahead > 0 && g.behind > 0 -> Kind.DIVERGED
        g.behind > 0 -> Kind.BEHIND
        g.ahead > 0 -> Kind.AHEAD
        else -> Kind.CLEAN
    }

    /** The light of a kind (see [Kind.light]). */
    fun light(kind: Kind): StatusLight.State = kind.light

    /** Why a status probe failed, in classes a sentence can be chosen for. */
    /** #1358 [FILES_ACCESS] = the git engine lacks its own All files access (EngineGrant): checked before ENGINE, whose words the engine's sentence also contains. */
    enum class ErrorKind { TIMEOUT, NOT_A_REPOSITORY, FILES_ACCESS, PERMISSION, AUTH, ENGINE, OTHER }

    fun errorKind(reason: String?): ErrorKind {
        val r = reason.orEmpty().lowercase()
        return when {
            r.startsWith("timeout") || "timed out" in r -> ErrorKind.TIMEOUT
            "all files access" in r -> ErrorKind.FILES_ACCESS
            "not a git" in r || "repository not found" in r || "no such repository" in r || "invalid git" in r -> ErrorKind.NOT_A_REPOSITORY
            "permission" in r || "eacces" in r || "read-only" in r -> ErrorKind.PERMISSION
            "401" in r || "403" in r || "not authorized" in r || "auth" in r -> ErrorKind.AUTH
            "no coordinator" in r || "engine" in r || "connect" in r || "refused" in r || "binder" in r || "service" in r -> ErrorKind.ENGINE
            else -> ErrorKind.OTHER
        }
    }

    /** The STATUS block's facts for one repository. Counts are null when they cannot be known (not cloned, no upstream, not read). */
    data class Block(
        val kind: Kind,
        val cloned: Boolean,
        val branch: String?,
        val upstream: String?,
        val modified: Int?,
        val untracked: Int?,
        val conflicts: Int?,
        val ahead: Int?,
        val behind: Int?,
        /** Commits not pushed: the ahead count; null while there is no upstream to compare with. */
        val unpushed: Int?,
        /** When the remote was last fetched (epoch ms); null = never / not known. */
        val lastFetchMs: Long?,
        val error: ErrorKind?,
        val errorDetail: String?,
        /** "light" (refs only: dirty is unknown) / "full" / null = not read. */
        val level: String? = null,
        val readAtMs: Long? = null,
    ) {
        val dirty: Int? get() = if (modified == null || untracked == null) null else modified + untracked + (conflicts ?: 0)
    }

    fun block(cloned: Boolean, g: GitSyncCoordinator.Glance?): Block {
        val kind = kindOf(cloned, g)
        val read = cloned && g != null && g.read && !g.gone && g.error == null
        val counts = read && !g!!.large
        val tree = counts && g.level != "light"
        return Block(
            kind = kind, cloned = cloned,
            branch = if (read) g!!.branch else null,
            upstream = if (read) g!!.upstream else null,
            modified = if (tree) (g!!.changed - g.untracked).coerceAtLeast(0) else null,
            untracked = if (tree) g!!.untracked else null,
            conflicts = if (tree) g!!.conflicts else null,
            ahead = if (counts && g!!.upstream != null) g.ahead else null,
            behind = if (counts && g!!.upstream != null) g.behind else null,
            unpushed = if (counts && g!!.upstream != null) g.ahead else null,
            lastFetchMs = if (counts && g!!.fetchedAtMs > 0) g.fetchedAtMs else null,
            error = if (kind == Kind.ERROR) errorKind(g?.error) else null,
            errorDetail = if (kind == Kind.ERROR) g?.error else null,
            level = if (read) g!!.level.ifBlank { null } else null,
            readAtMs = if (read && g!!.readAtMs > 0) g.readAtMs else null,
        )
    }

    /** The icon's caption: which level the cached result came from and its age, "L 5m" (light, refs only) / "F 2h" (full, worktree scanned); "" = nothing read. */
    fun levelCaption(g: GitSyncCoordinator.Glance?, now: Long): String {
        if (g == null || !g.read || g.level.isBlank() || g.readAtMs <= 0) return ""
        return (if (g.level == "light") "L " else "F ") + ageWord((now - g.readAtMs).coerceAtLeast(0))
    }

    fun ageWord(ms: Long): String = when {
        ms < 60_000 -> "<1m"
        ms < 3_600_000 -> "${ms / 60_000}m"
        ms < 86_400_000 -> "${ms / 3_600_000}h"
        else -> "${ms / 86_400_000}d"
    }

    /** #1238 what the status window's last section offers: Clone for a repository with no clone, a progress word while it clones, nothing for a clone that exists. */
    enum class CloneAction { CLONE, CLONING, NONE }

    fun cloneAction(cloned: Boolean, cloning: Boolean): CloneAction = when {
        cloned -> CloneAction.NONE
        cloning -> CloneAction.CLONING
        else -> CloneAction.CLONE
    }

    /** The commits a push would send: the newest [ahead] entries of the branch's log (the log is newest first). */
    fun unpushed(log: List<GitCommitInfo>, ahead: Int?): List<GitCommitInfo> = if (ahead == null || ahead <= 0) emptyList() else log.take(ahead)

    // ── The THREE icons (local / server / sync): each derived on its own, so one failing part never leaves the others on "?" ──

    /** LOCAL: the working copy. READING is the only "not read yet"; CLONED = the folder is a clone but the light check did not scan the tree (dirty unknown). */
    enum class LocalKind { NOT_CLONED, NOT_CHECKED, READING, MISSING, CLONED, CLEAN, DIRTY, CONFLICT, LARGE, ERROR }
    /** SERVER: the clone against its remote, from refs. NOT_FETCHED = refs equal but never fetched, so "in sync" would be a guess. */
    enum class ServerKind { NOT_CLONED, NOT_CHECKED, READING, UNKNOWN, NOT_FETCHED, IN_SYNC, AHEAD, BEHIND, DIVERGED, NO_UPSTREAM, UNREACHABLE }
    /** SYNC: the last sync / fetch / push / pull job record. */
    enum class SyncKind { NEVER, RUNNING, OK, ERROR }

    fun localKind(cloned: Boolean, g: GitSyncCoordinator.Glance?): LocalKind = when {
        !cloned -> LocalKind.NOT_CLONED
        g == null -> LocalKind.NOT_CHECKED
        !g.read -> LocalKind.READING
        g.gone -> LocalKind.MISSING
        g.error != null -> LocalKind.ERROR
        g.large -> LocalKind.LARGE
        g.level == "light" -> LocalKind.CLONED
        g.conflicts > 0 -> LocalKind.CONFLICT
        g.changed > 0 -> LocalKind.DIRTY
        else -> LocalKind.CLEAN
    }

    /** [fetchFailed] = the newest fetch / pull job of this repository failed (the remote did not answer). */
    fun serverKind(cloned: Boolean, g: GitSyncCoordinator.Glance?, fetchFailed: Boolean = false): ServerKind = when {
        !cloned -> ServerKind.NOT_CLONED
        g == null -> ServerKind.NOT_CHECKED
        !g.read -> ServerKind.READING
        g.gone || g.error != null || g.large && g.upstream == null -> ServerKind.UNKNOWN
        g.upstream == null -> ServerKind.NO_UPSTREAM
        fetchFailed -> ServerKind.UNREACHABLE
        g.ahead > 0 && g.behind > 0 -> ServerKind.DIVERGED
        g.behind > 0 -> ServerKind.BEHIND
        g.ahead > 0 -> ServerKind.AHEAD
        g.fetchedAtMs <= 0 -> ServerKind.NOT_FETCHED
        else -> ServerKind.IN_SYNC
    }

    fun syncKind(running: Boolean, last: SyncEvent?): SyncKind = when {
        running -> SyncKind.RUNNING
        last == null -> SyncKind.NEVER
        last.ok -> SyncKind.OK
        else -> SyncKind.ERROR
    }

    /** The three kinds of one row, with the sync event they came from (its age / message are shown). */
    data class Icons(val local: LocalKind, val server: ServerKind, val sync: SyncKind, val lastSync: SyncEvent? = null)

    fun icons(cloned: Boolean, g: GitSyncCoordinator.Glance?, running: Boolean, events: List<SyncEvent>, repoId: String): Icons {
        val mine = events.filter { it.repoId == repoId }.maxByOrNull { it.epochSeconds }
        val fetchOp = mine?.takeIf { !it.ok && (it.summary.startsWith("fetch") || it.summary.startsWith("pull")) }
        return Icons(localKind(cloned, g), serverKind(cloned, g, fetchOp != null), syncKind(running, mine), mine)
    }

    /** The baked default of build.json::...git.status.icons (the declaration wins when it names a kind). Light names: on / off / unknown / unverifiable. */
    val DEFAULT_ICONS: Map<String, Map<String, String>> = mapOf(
        "local" to mapOf("not_cloned" to "unknown", "not_checked" to "unverifiable", "reading" to "unknown", "missing" to "unverifiable", "cloned" to "unverifiable", "clean" to "on", "dirty" to "off", "conflict" to "off", "large" to "unverifiable", "error" to "off"),
        "server" to mapOf("not_cloned" to "unknown", "not_checked" to "unverifiable", "reading" to "unknown", "unknown" to "unknown", "not_fetched" to "unverifiable", "in_sync" to "on", "ahead" to "on", "behind" to "on", "diverged" to "off", "no_upstream" to "off", "unreachable" to "off"),
        "sync" to mapOf("never" to "unverifiable", "running" to "unverifiable", "ok" to "on", "error" to "off"),
    )

    /** The light of one icon kind through the declared mapping [decl] (falls back to [DEFAULT_ICONS], then UNKNOWN). */
    fun lightOf(icon: String, kind: Enum<*>, decl: Map<String, Map<String, String>> = emptyMap()): StatusLight.State {
        val k = kind.name.lowercase()
        return when ((decl[icon]?.get(k) ?: DEFAULT_ICONS[icon]?.get(k))?.lowercase()) {
            "on" -> StatusLight.State.ON
            "off" -> StatusLight.State.OFF
            "unverifiable" -> StatusLight.State.UNVERIFIABLE
            else -> StatusLight.State.UNKNOWN
        }
    }

    /** "last check: <time> / n of m done": [keys] are the row status keys of the cloned repositories, [glances] the reader's answers. */
    data class Progress(val done: Int, val total: Int, val lastCheckMs: Long)
    fun progress(keys: Collection<String>, glances: Map<String, GitSyncCoordinator.Glance>): Progress {
        val read = keys.mapNotNull { glances[it]?.takeIf { g -> g.read } }
        return Progress(read.size, keys.size, read.maxOfOrNull { it.readAtMs } ?: 0L)
    }

    /** The status window's debug line: the exact path read and the reader's outcome. */
    fun debugLine(path: String, g: GitSyncCoordinator.Glance?): String =
        "path=" + path.ifBlank { "-" } + " read=" + (g?.path?.takeIf { it.isNotBlank() } ?: "-") +
            " via=" + (g?.via?.ifBlank { "-" } ?: "-") + " outcome=" + (if (g == null) "not requested" else if (!g.read) "reading" else g.state.ifBlank { "?" }) +
            (g?.error?.let { " error=$it" } ?: "") + (if (g != null && g.read) " took=${g.tookMs}ms" else "")
}
