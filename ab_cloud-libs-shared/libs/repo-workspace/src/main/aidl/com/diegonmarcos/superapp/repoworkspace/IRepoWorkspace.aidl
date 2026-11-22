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
 * only ever be APPENDED. v1 is these five; contract changes bump version().
 */
interface IRepoWorkspace {

    /** The contract version this server speaks (1). A client newer than the server asks before using an appended method. */
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
}
