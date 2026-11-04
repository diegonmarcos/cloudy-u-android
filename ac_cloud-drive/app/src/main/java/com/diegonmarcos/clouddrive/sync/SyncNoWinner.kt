package com.diegonmarcos.clouddrive.sync

/**
 * SYNC-NO-WINNER's PURE RULES (JVM-tested in SyncNoWinnerTest).
 *
 * "No winner" = neither side is preferred: fetch the upstream and MERGE it into the local branch. It succeeds only when
 * git can combine both histories without a conflict; when it cannot, nothing is changed and the owner picks a side
 * (remote wins / local wins) or resolves by hand. So a failure must say WHY, never a bare "not merged".
 *
 * IDENTITY (same one-clone rule as [BulkDepth]): a clone is the canonical registry repository of its ORIGIN URL, the
 * renamed_from chains folded in; with no origin, of its folder name. A clone under an OLD name is that repository under its
 * current name; when a clone under the current name already exists, the old-named one is a stale duplicate and is not merged.
 */
object SyncNoWinner {

    data class Identity(val current: String, val renamed: Boolean)

    fun identify(cloneName: String, originUrl: String, canonical: (String) -> String): Identity {
        val byUrl = if (originUrl.isNotBlank()) LocalRepos.repoNameOf(originUrl) else ""
        val raw = byUrl.ifBlank { cloneName }
        val current = canonical(raw).ifBlank { raw }
        return Identity(current, !current.equals(cloneName, ignoreCase = true))
    }

    /** The refusal for an old-named clone whose current name already has a clone; null = go ahead. */
    fun duplicateOf(id: Identity, cloneName: String, currentHasClone: Boolean): String? =
        if (id.renamed && currentHasClone) "renamed to ${id.current}: '$cloneName' is a stale clone under the old name and git/${id.current} already holds it; remove '$cloneName' (nothing merged)" else null

    /** The actionable sentence for a failed merge; [ahead]/[behind] from the clone's status after the fetch (-1 = unknown). */
    fun explain(details: String, ahead: Int, behind: Int, id: Identity, cloneName: String): String {
        val d = details.lowercase()
        val counts = if (ahead >= 0 && behind >= 0) "$ahead local, $behind remote commits" else "local and remote both have new commits"
        val why = when {
            // #1408 the in-process merge (libs:git-core) stops WITH the conflict in place: the files carry markers, the solver resolves them.
            IN_PROCESS_CONFLICT.containsMatchIn(d) -> "conflict: ${IN_PROCESS_CONFLICT.find(d)!!.groupValues[1]} file(s) need you, markers kept — open the conflict solver"
            "merge conflict" in d -> "conflict: $counts touch the same lines, merge aborted, nothing changed — open to resolve or pick a side"
            "unrelated histories" in d || "shallow" in d -> "diverged: $counts and the shallow clone has no common base, nothing changed — open to resolve (remote wins / local wins)"
            "would be overwritten" in d || "uncommitted" in d || "commit your changes" in d -> "local changes block the merge, nothing changed — commit or stash them first"
            "no upstream" in d || "no tracking information" in d || "no such branch" in d -> "no upstream branch to merge, nothing changed — set the tracking branch"
            "could not read from remote" in d || "repository not found" in d || "authentication" in d || "403" in d || "401" in d -> "fetch refused by the remote, nothing changed — check the sign-in or the URL"
            ahead > 0 && behind > 0 -> "diverged: $counts, nothing changed — open to resolve"
            else -> "merge failed, nothing changed: " + (details.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: "git gave no reason")
        }
        return if (id.renamed) "$why (renamed: '$cloneName' is now ${id.current})" else why
    }

    private val IN_PROCESS_CONFLICT = Regex("""pull conflicts: (\d+) file""")
    private val LEFT = Regex("""conflict: (\d+) file\(s\) need you""")

    /** #1408 the conflicts a last sync left in the tree, read from its recorded summary; 0 when it left none. */
    fun leftConflicts(lastSummary: String): Int = LEFT.find(lastSummary)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
