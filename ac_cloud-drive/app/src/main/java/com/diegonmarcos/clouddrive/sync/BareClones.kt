package com.diegonmarcos.clouddrive.sync

/**
 * #1282 CLONE BARE's PURE RULES (build.json ui.sync.git.depth_actions kind `clone_bare`, root ui.sync.git.bare_root),
 * JVM-tested in BareClonesTest.
 *
 *  - PATH: a bare mirror lives at <store git>/<bare_root>/<owner>/<repo>.git, the repository's CURRENT (canonical) name.
 *  - ONE BARE PER REPOSITORY: identity is [BulkDepth.identity] of the mirror's origin URL (renamed_from chains folded in),
 *    so a mirror made under an old name still counts. A target already mirrored is UPDATED (git remote update --prune),
 *    never cloned twice; two targets naming the same repository yield one action.
 *  - The working clone is never touched: the bare root is its own tree and only `clone --bare --single-branch` (the remote's default branch, #1731) / `remote update` run there.
 *  - Depth (#1314): bare mirrors are depth 1 (no working tree, only the latest commit per ref); 0 still means full history.
 *  - UPDATE at depth > 0 is `fetch --prune --depth N origin` (the mirror refspec, all refs): `remote update` keeps every
 *    new commit and a shallow mirror would grow back to full history. A mirror that is not shallow, or deeper than N, is SHRUNK in
 *    place by the same script: the depth fetch, `reflog expire --expire=now --all`, `gc --prune=now`; the bytes freed come back
 *    on the script's output ([parseUpdate]).
 */
object BareClones {

    /** A bare mirror found on the device: its folder and its origin URL. */
    data class Mirror(val path: String, val origin: String)
    data class Target(val name: String, val url: String)
    data class Plan(val toClone: List<Target>, /** name -> existing mirror path to update */ val toUpdate: Map<String, String>)

    private val SAFE = Regex("[A-Za-z0-9._-]+")

    /** The owner segment of a clone URL (https, ssh or scp form); "" when it has none. */
    fun ownerOf(url: String): String {
        var u = url.trim().substringBefore('?').substringBefore('#').trimEnd('/')
        if (u.endsWith(".git", ignoreCase = true)) u = u.dropLast(4)
        val parts = u.substringAfter("://", u).replace(':', '/').split('/').filter { it.isNotEmpty() }
        return if (parts.size >= 3 || (parts.size == 2 && "://" !in url)) parts[parts.size - 2].substringAfterLast('@') else ""
    }

    /** The declared bare root must be a plain relative folder. */
    fun validRoot(root: String): Boolean = root.isNotBlank() && !root.startsWith("/") && root.split('/').all { SAFE.matches(it) && it != ".." && it != "." }

    /** <gitRoot>/<root>/<owner>/<canonical repo>.git; null when a segment is unsafe. */
    fun path(gitRoot: String, root: String, url: String, name: String, canonical: (String) -> String): String? {
        val owner = ownerOf(url).ifBlank { "_" }
        val repo = canonical(name.ifBlank { LocalRepos.repoNameOf(url) })
        if (!validRoot(root) || !SAFE.matches(owner) || !SAFE.matches(repo) || owner == ".." || repo == "..") return null
        return gitRoot.trimEnd('/') + "/" + root + "/" + owner + "/" + repo + ".git"
    }

    /** The `[remote "origin"] url` of a bare repository's config text; "" when none. */
    fun originOfConfig(config: String): String {
        var inOrigin = false
        for (raw in config.lineSequence()) {
            val l = raw.trim()
            if (l.startsWith("[")) inOrigin = l.replace(" ", "").equals("[remote\"origin\"]", ignoreCase = true)
            else if (inOrigin && l.substringBefore('=').trim().equals("url", ignoreCase = true)) return l.substringAfter('=').trim()
        }
        return ""
    }

    fun plan(targets: List<Target>, mirrors: List<Mirror>, canonical: (String) -> String): Plan {
        val have = LinkedHashMap<String, String>()
        mirrors.forEach { m ->
            val id = BulkDepth.identity(m.origin.ifBlank { m.path.substringAfterLast('/').removeSuffix(".git") }, canonical)
            if (id.isNotEmpty()) have.putIfAbsent(id, m.path)
        }
        val seen = HashSet<String>()
        val clone = ArrayList<Target>(); val update = LinkedHashMap<String, String>()
        targets.forEach { t ->
            val id = BulkDepth.identity(t.url.ifBlank { t.name }, canonical).ifEmpty { BulkDepth.identity(t.name, canonical) }
            if (id.isEmpty() || !seen.add(id)) return@forEach
            val at = have[id]
            if (at != null) update[t.name] = at else clone += t
        }
        return Plan(clone, update)
    }

    /** The shell for one bare clone (run in cld.termux): the mirror at [guestDir], [depth] 0 = full history. */
    fun cloneArgs(url: String, guestDir: String, depth: Int, q: (String) -> String, branch: String? = null): String =
        "clone --bare --single-branch " + (if (!branch.isNullOrBlank()) "--branch " + q(branch) + " " else "") + (if (depth > 0) "--depth $depth " else "") + q(url) + " " + q(guestDir)

    /** The git arguments of a plain update: depth 0 = `remote update --prune`, depth > 0 = `fetch --prune --depth N origin`. */
    fun updateArgs(guestDir: String, q: (String) -> String, depth: Int = 0): String =
        "-C " + q(guestDir) + (if (depth > 0) " fetch --prune --depth $depth origin" else " remote update --prune")

    /**
     * #1314 the whole update script of one mirror (run in cld.termux; [authed] wraps `git <args>` with the token on stdin).
     * Depth 0: the plain `remote update --prune`. Depth > 0: the depth fetch, then, when the mirror was not shallow or holds more
     * than [depth] commits per ref tip (total commits > distinct tips * depth), `reflog expire --expire=now --all` and
     * `gc --prune=now` to free the old packs. Prints `shrunk=0|1` and `freed_kb=<n>` (du before - after).
     */
    fun updateScript(guestDir: String, depth: Int, q: (String) -> String, authed: (String) -> String): String {
        if (depth <= 0) return "set -e; " + authed(updateArgs(guestDir, q))
        val d = q(guestDir)
        return "set -e; d=$d; b=\$(du -sk \"\$d\" | cut -f1); need=0; " +
            "if [ ! -f \"\$d/shallow\" ]; then need=1; else " +
            "tips=\$(git -C \"\$d\" for-each-ref --format='%(objectname)' | sort -u | wc -l); " +
            "total=\$(git -C \"\$d\" rev-list --all --count 2>/dev/null || echo 0); " +
            "[ \"\$total\" -gt \$((tips * $depth)) ] && need=1 || true; fi; " +
            authed("-C \"\$d\" fetch --prune --depth $depth origin") + "; " +
            "if [ \"\$need\" = 1 ]; then git -C \"\$d\" reflog expire --expire=now --all; git -C \"\$d\" gc --prune=now; fi; " +
            "a=\$(du -sk \"\$d\" | cut -f1); echo \"shrunk=\$need\"; echo \"freed_kb=\$((b - a))\""
    }

    /** What [updateScript] printed: whether the mirror was shrunk and the bytes freed (0 when it did not shrink or grew). */
    data class UpdateOutcome(val shrunk: Boolean = false, val freedBytes: Long = 0)

    fun parseUpdate(out: String): UpdateOutcome {
        val kv = out.lineSequence().mapNotNull { l -> l.trim().takeIf { '=' in it }?.let { it.substringBefore('=') to it.substringAfter('=') } }.toMap()
        val shrunk = kv["shrunk"] == "1"
        return UpdateOutcome(shrunk, if (shrunk) ((kv["freed_kb"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)) * 1024 else 0)
    }

    /** Identity -> mirror path, for the page's "bare ✓" indicator. */
    fun index(mirrors: List<Mirror>, canonical: (String) -> String): Map<String, String> =
        mirrors.mapNotNull { m -> BulkDepth.identity(m.origin.ifBlank { m.path.substringAfterLast('/').removeSuffix(".git") }, canonical).takeIf { it.isNotEmpty() }?.let { it to m.path } }.toMap()
}
