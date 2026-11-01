package com.diegonmarcos.clouddrive.sync

/**
 * #1138 THE ACTIONS of a repository's detail. A declared operation (build.json ui.sync.git.ops) keeps
 * its declared id and its dispatch; the ids added here are the compound sync verbs, the history trim,
 * the extra readers, the clone settings and (#1289) the Workflow and AutoSync items. #1289 the GROUPING,
 * order and labels are data (ui.sync.git.repo_menu); [route] is the confirmation gate, pure so the JVM
 * suite pins it.
 */
object RepoActions {
    const val SYNC_NO_WINNER = "sync_no_winner"
    const val SYNC_REMOTE_WINS = "sync_remote_wins"
    const val SYNC_LOCAL_WINS = "sync_local_wins"
    const val META_BRANCH = "meta_branch"
    const val META_SYNC = "meta_sync"
    const val OPEN_FOLDER = "open_folder"
    const val CHECKOUT_MAIN = "checkout_main"
    const val BRANCHES = "branches"
    const val PRS = "prs"
    const val UNPUSHED = "unpushed"
    const val STASH_PUSH = "stash_push"
    /** #1356 Manual Sync: stash the working tree, pull with rebase, pop the newest stash. */
    const val STASH = "stash"
    const val PULL_REBASE = "pull_rebase"
    const val STASH_POP = "stash_pop"
    const val RUNS = "runs"
    const val CLEAN_1 = "clean_1"
    const val CLEAN_5 = "clean_5"
    const val CLEAN_10 = "clean_10"
    const val SYNC_NOW = "sync_now"
    const val SETTINGS = "settings"
    const val CLONE_FULL = "clone_full"
    const val CLONE_DEPTH1 = "clone_depth1"
    const val CLONE_SSH = "clone_ssh"
    const val CLONE_OAUTH = "clone_oauth"

    const val LIST_DIRTY = "list_dirty"
    const val LIST_CONFLICTS = "list_conflicts"
    const val SOLVE_CONFLICTS = "solve_conflicts"
    const val AUTOSYNC_TOGGLE = "autosync_toggle"
    const val AUTOSYNC_EVERY = "autosync_every"

    /** #1289 the menu's group ids, in the order build.json declares them (RepoMenuTest pins both). */
    const val G_GOTO = "goto"
    const val G_CLONE = "clone"
    const val G_SYNC = "sync"
    const val G_MANUAL = "manual_sync"
    const val G_WORKFLOW = "workflow"
    const val G_CLEAN = "repo_clean"
    const val G_OTHERS = "others"
    const val G_AUTH = "configs_auth"
    const val G_AUTOSYNC = "configs_autosync"
    val GROUP_ORDER = listOf(G_GOTO, G_CLONE, G_SYNC, G_MANUAL, G_WORKFLOW, G_CLEAN, G_OTHERS, G_AUTH, G_AUTOSYNC)
    /** Groups whose chips stay usable while an operation runs (settings and readers; the verbs wait). */
    val ALWAYS_ON_GROUPS = setOf(G_GOTO, G_CLONE, G_AUTH, G_AUTOSYNC)

    /** The ids every action handler of the page knows besides the declared ops and depth/folder actions. */
    val PAGE_IDS: Set<String> = setOf(
        SYNC_NO_WINNER, SYNC_REMOTE_WINS, SYNC_LOCAL_WINS, META_BRANCH, META_SYNC, OPEN_FOLDER, CHECKOUT_MAIN, BRANCHES, PRS, UNPUSHED,
        RUNS, CLEAN_1, CLEAN_5, CLEAN_10, SYNC_NOW, SETTINGS, CLONE_FULL, CLONE_DEPTH1, CLONE_SSH, CLONE_OAUTH,
        LIST_DIRTY, LIST_CONFLICTS, SOLVE_CONFLICTS, AUTOSYNC_TOGGLE, AUTOSYNC_EVERY, STASH, PULL_REBASE, STASH_POP,
    )

    val DANGEROUS: List<String> = listOf("force_pull", "force_push")
    val REPO_CLEAN: List<String> = listOf(CLEAN_1, CLEAN_5, CLEAN_10)

    /** The depth a Repo clean id keeps, or 0 when [id] is not one. */
    fun cleanDepth(id: String): Int = when (id) { CLEAN_1 -> 1; CLEAN_5 -> 5; CLEAN_10 -> 10; else -> 0 }

    enum class Route { RUN, CONFIRM }

    /** Dangerous and Repo clean never run on the tap: they ask first. Everything else runs. */
    fun route(id: String): Route = if (id in DANGEROUS || id in REPO_CLEAN) Route.CONFIRM else Route.RUN

    /** The compound sync verbs and the history trim run through [GitSyncCoordinator.runCompound]. */
    fun isCompound(id: String): Boolean = id == STASH_PUSH || id == STASH || id == PULL_REBASE || id == STASH_POP || id == SYNC_NO_WINNER || id == SYNC_REMOTE_WINS || id == SYNC_LOCAL_WINS || cleanDepth(id) > 0


    /**
     * #1195 what a repository that is NOT cloned can still do: open it on the web, read its pull requests and CI runs
     * (remote reads), and set how a clone will be made. Every other action needs the local clone and is drawn disabled
     * with the reason ([needsClone]); Clone and Copy URL are the panel's own two buttons.
     */
    val NO_CLONE_NEEDED: Set<String> = setOf("web", PRS, RUNS, CLONE_FULL, CLONE_DEPTH1, CLONE_OAUTH, CLONE_SSH)
    fun needsClone(id: String): Boolean = id !in NO_CLONE_NEEDED
}

/**
 * #1356 THE SYNC DROPDOWN of the Git top bar: Sync All (Server Wins | Local Wins | No Winner) and Manual Sync (Stash All,
 * Pull (Rebase), Stash Pop All, Conflicts All). Every verb is a compound id run by [GitSyncCoordinator.runCompoundAll] over
 * the listed clones (one job, a result per repository in Logs); pure, so SyncAllTest pins the mapping.
 */
object SyncAll {
    enum class Mode(val op: String) {
        SERVER_WINS(RepoActions.SYNC_REMOTE_WINS), LOCAL_WINS(RepoActions.SYNC_LOCAL_WINS), NO_WINNER(RepoActions.SYNC_NO_WINNER),
        STASH(RepoActions.STASH), PULL_REBASE(RepoActions.PULL_REBASE), STASH_POP(RepoActions.STASH_POP),
    }

    /** The engine strategy a declared op runs, or null when it is not one of the dropdown's verbs. */
    fun modeOf(op: String): Mode? = Mode.values().firstOrNull { it.op == op }

    /** A result line that reports a conflict (the engine's merge, rebase or pop stopped on one). */
    fun isConflict(summary: String, details: String = ""): Boolean = Regex("(?i)conflict").containsMatchIn(summary + "\n" + details)

    /**
     * No Winner STOPS at conflicts: it never resolves one itself, so when its job reports any, the page opens the conflict
     * solver. Server Wins and Local Wins pick a side and never open it; Pull (Rebase) and Stash Pop open it too, they stop the same way.
     */
    fun opensSolver(op: String, results: List<Pair<String, String>>): Boolean =
        (op == RepoActions.SYNC_NO_WINNER || op == RepoActions.PULL_REBASE || op == RepoActions.STASH_POP) && results.any { (s, d) -> isConflict(s, d) }
}
