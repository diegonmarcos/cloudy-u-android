package com.diegonmarcos.superapp.repoworkspace

/**
 * Where Drive's git workspace lives, and what a caller may assume about it (#1855 P1).
 *
 * [SERVICE_PACKAGES] is a constellation ADDRESS, like TextTools.SERVICE_PACKAGES: not read from a
 * build.json (a lib shared by reference reads the CONSUMING app's file).
 */
object RepoWorkspace {
    val SERVICE_PACKAGES = listOf("com.diegonmarcos.clouddrive")

    /** The intent action [IRepoWorkspace] is published under. */
    const val ACTION = "com.diegonmarcos.superapp.repoworkspace.IRepoWorkspace"

    /** The activity intent Drive handles: Sync > Git with the clone dialog prefilled with [EXTRA_NAME]. */
    const val ACTION_CLONE = "com.diegonmarcos.superapp.repoworkspace.CLONE"
    const val EXTRA_NAME = "name"

    /** Signature-level: the platform refuses any caller not signed with the Cloud key. */
    const val PERMISSION = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"

    /** v2 (#1855 P4a): the activity intents Drive handles to open its own Commit screen / Push screen for the repository folder [EXTRA_PATH]. */
    const val ACTION_COMMIT = "com.diegonmarcos.superapp.repoworkspace.COMMIT"
    const val ACTION_PUSH = "com.diegonmarcos.superapp.repoworkspace.PUSH"
    const val EXTRA_PATH = "path"

    /** The first contract version that has changedFiles / diff / requestCommit / requestPush. */
    const val VERSION_REVIEW = 2
    const val VERSION = 2

    /** The diff size a caller asks for by default, and the most any server returns whatever is asked. */
    const val DIFF_DEFAULT_BYTES = 64 * 1024
    const val DIFF_MAX_BYTES = 256 * 1024
    const val DIFF_TRUNCATED = "... diff truncated"

    const val TOO_OLD = "Cloud Drive is too old for this: update Cloud Drive"

    const val NOT_INSTALLED = "Repositories are held by the Cloud Drive app: install Cloud Drive (Sync, Git)"

    /** The volume kinds, the ids of Drive's GitStorage.Kind. */
    const val VOLUME_STORE = "store"
    const val VOLUME_EMULATED = "emulated"
    const val VOLUME_EXTERNAL = "external"
    val VOLUMES = listOf(VOLUME_STORE, VOLUME_EMULATED, VOLUME_EXTERNAL)

    /** A plain repository name: no path separator, not "." / "..", no control character, bounded. */
    fun validName(name: String?): Boolean {
        val n = name?.trim().orEmpty()
        return n.isNotEmpty() && n.length <= 100 && n != "." && n != ".." &&
            n.none { it == '/' || it == '\\' || it.code < 0x20 }
    }

    /** One repository on one volume, the wire row of [IRepoWorkspace]. */
    data class RepoInfo(
        val name: String,
        val path: String,
        val volume: String,
        val branch: String = "",
        val lastSyncMs: Long = 0,
        val state: String = "",
        val summary: String = "",
    ) {
        fun encode(): String = listOf(name, path, volume, branch, lastSyncMs.toString(), state, summary)
            .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') }

        companion object {
            /** Reads a row without trusting its shape (it crossed a process); null when it has no name or path. */
            fun decode(row: String?): RepoInfo? {
                val f = row?.split('\t') ?: return null
                val name = f.getOrNull(0).orEmpty()
                val path = f.getOrNull(1).orEmpty()
                if (name.isEmpty() || path.isEmpty()) return null
                return RepoInfo(name, path, f.getOrNull(2).orEmpty(), f.getOrNull(3).orEmpty(),
                    f.getOrNull(4)?.toLongOrNull() ?: 0L, f.getOrNull(5).orEmpty(), f.getOrNull(6).orEmpty())
            }

            fun decodeAll(rows: Array<String>?): List<RepoInfo> = rows?.mapNotNull { decode(it) }.orEmpty()
        }
    }

    /** The status letters of [ChangedFile.status]. */
    const val ST_ADDED = "A"
    const val ST_MODIFIED = "M"
    const val ST_DELETED = "D"
    const val ST_RENAMED = "R"
    const val ST_UNTRACKED = "?"

    /** One changed file of one repository, the wire row of [IRepoWorkspace.changedFiles] (v2). */
    data class ChangedFile(
        val relpath: String,
        val status: String,
        val staged: Boolean,
        val added: Int = 0,
        val removed: Int = 0,
    ) {
        fun encode(): String = listOf(relpath, status, if (staged) "1" else "0", added.toString(), removed.toString())
            .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') }

        companion object {
            /** Reads a row without trusting its shape; null when it has no path or an unknown status letter. */
            fun decode(row: String?): ChangedFile? {
                val f = row?.split('\t') ?: return null
                val rel = f.getOrNull(0).orEmpty()
                val st = f.getOrNull(1).orEmpty()
                if (rel.isEmpty() || st !in listOf(ST_ADDED, ST_MODIFIED, ST_DELETED, ST_RENAMED, ST_UNTRACKED)) return null
                return ChangedFile(rel, st, f.getOrNull(2) == "1", f.getOrNull(3)?.toIntOrNull() ?: 0, f.getOrNull(4)?.toIntOrNull() ?: 0)
            }

            fun decodeAll(rows: Array<String>?): List<ChangedFile> = rows?.mapNotNull { decode(it) }.orEmpty()
        }
    }

    /** A repository-relative path a caller may name: not empty, not absolute, no ".." segment, no NUL / control character, bounded. */
    fun validRelPath(rel: String?): Boolean {
        val r = rel.orEmpty()
        return r.isNotEmpty() && r.length <= 1024 && !r.startsWith("/") && !r.contains('\\') &&
            r.none { it.code < 0x20 } && r.split('/').none { it == ".." || it.isEmpty() }
    }
}
