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

    const val VERSION = 1

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
}
