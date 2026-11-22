package com.diegonmarcos.superapp.repoworkspace;

/**
 * Cloud Drive's git workspace, as one binder: which repositories are on this phone, and where.
 *
 * ROWS, NOT PARCELABLES. Every repository crosses as ONE tab-separated String (RepoInfo.encode /
 * RepoInfo.decode in the module): a Parcelable would pin both sides to one class layout, a row is
 * read by position and tolerates a longer one. Fields: name, path, volume (store|emulated|external),
 * branch, lastSyncMs (0 = never / unknown), state ("" = not read yet, else the status label), summary.
 *
 * NEVER NULL, NEVER AN EXCEPTION. A binder cannot carry one across a process; an unreadable volume
 * is an empty list, an unknown repository an empty row.
 *
 * BLOCKING. listRepos() and findRepo() walk the volumes; the platform runs the call on a binder pool
 * thread, the CALLER's thread waits. Never call on a main thread.
 *
 * METHOD ORDER IS THE WIRE FORMAT. Transaction codes are assigned by declaration order, so a method may
 * only ever be APPENDED. v1 is the first five; v2 (#1855 P4a) appends the last four; contract changes bump version().
 */
interface IRepoWorkspace {

    /** The contract version this server speaks (2). A client newer than the server asks before using an appended method. */
    int version();

    /** Every repository on every volume Drive knows (the store, Emulated, every attached drive), one row each. */
    String[] listRepos();

    /** The rows whose folder name (or registry name) is [name], case-insensitively; empty when the repository is nowhere. */
    String[] findRepo(in String name);

    /**
     * Ask Drive to clone [name]. Answers "" when Drive accepted the request (the name is a plain
     * repository name), else a readable reason. It does NOT start the clone: it validates and
     * returns at once; the caller then opens Drive's clone flow (RepoWorkspaceClient.requestClone),
     * because only a foreground app may start an activity.
     */
    String requestClone(in String name);

    /** The cheap status of the repository folder [path] as one row; empty when Drive does not know that path. */
    String status(in String path);

    // ---- v2 (#1855 P4a): read-only review of the working tree + hand-offs to Drive's own Commit screen. ----
    // NO DESTRUCTIVE VERB CROSSES THIS BINDER: no stage, no discard, no reset, no commit, no push. Staging, commit and push happen in
    // Drive's UI with the user's confirmation; requestCommit / requestPush only VALIDATE and the client then opens that screen.

    /**
     * The changed files of the repository folder [path] (one of listRepos' paths), one row each, tab-separated:
     * relpath, status (A added, M modified, D deleted, R renamed, ? untracked), staged ("1" = the index side, "0" = the worktree side;
     * a file changed on both sides has two rows), added lines, removed lines. Empty when Drive does not know the path.
     */
    String[] changedFiles(in String path);

    /**
     * The unified diff of [relpath] in the repository [path] (staged part first, then the worktree part), cut at [maxBytes] on a line
     * boundary with a trailing "... diff truncated" marker. Empty when the path or file is unknown or has no change. Bounded by the server too.
     */
    String diff(in String path, in String relpath, int maxBytes);

    /** Validate "open Drive's Commit screen for [path]": "" = accepted (the caller then starts RepoWorkspace.ACTION_COMMIT), else the reason. */
    String requestCommit(in String path);

    /** Validate "open Drive's Push screen for [path]": "" = accepted (the caller then starts RepoWorkspace.ACTION_PUSH), else the reason. */
    String requestPush(in String path);
}
