package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitrepos.*

import android.content.Context
import com.diegonmarcos.clouddrive.sync.store.StoreRoute
import com.diegonmarcos.clouddrive.sync.store.StoreShell
import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import java.io.File

/**
 * cloud-shared-store's EXEC fallback for git: because the git engine APK (its own uid) cannot open
 * the store in either access mode, so the clone / sync runs
 * INSIDE cld.termux's glibc root on the store (Debian git, /root/cloud-shared-store/...),
 * through the signature-level CLOUD_EXEC service. The token goes over stdin only.
 * [useExec] is the one switch every caller asks. #1315 every script runs on the ONE [StoreShell];
 * every guest path comes from the ONE [StoreRoute].
 */
object StoreGit {

    /** The git engine APK is another uid and cannot open the store even when this app shares cld.termux's uid, so git runs in cld.termux whenever it answers. */
    fun useExec(ctx: Context): Boolean = StoreRoute.of(ctx).viaTermux

    private fun shell(ctx: Context): StoreShell = StoreShell.of(ctx)

    private fun guest(ctx: Context, dir: File): String = StoreRoute.of(ctx).guestOrThrow(dir.absolutePath)

    private fun q(s: String): String = StoreShell.q(s)

    /** #1358 a failed script's summary carries the REAL reason (git's fatal / error line, or "git printed nothing"), not the exit code alone. */
    private fun failed(what: String, r: com.diegonmarcos.cloudlib.sharedstore.StoreExec.Result): String =
        BulkDepth.withTail("$what failed (exit ${r.exit})${hint(r.out + "\n" + r.err)}", r.out + "\n" + r.err)

    /** #1436 what to do, in the Logs line itself, for the failures cld.termux's two logins can produce. */
    internal fun hint(text: String): String = when {
        "cloud-rootfs:" in text && ("no unpacked rootfs" in text || "rootfs.sha256 is missing" in text) ->
            " -- cld.termux's rootfs is being repaired automatically (re-extracted from cloud-lib-rootfs-termux); retry in a few minutes, or open cld.termux once. If it repeats, reinstall cloud-lib-rootfs-termux from the Store"
        "git: inaccessible or not found" in text || "git: not found" in text || "git is still missing" in text ->
            " -- git is missing in cld.termux: open cld.termux and run 'pkg install git', or let it repair its rootfs (open the app once)"
        "cannot create directory '/root'" in text ->
            " -- an old Cloud Drive ran a rootfs path in plain Termux: update Cloud Drive and cld.termux from the Store"
        else -> ""
    }

    /** `git <args>` with the token (if any) as an http.extraHeader, read from stdin. */
    internal fun authed(args: String): String =
        "T=\"\$(cat)\"; if [ -n \"\$T\" ]; then " +
            "H=\"Authorization: Basic \$(printf '${com.diegonmarcos.cloudlib.gitui.GitConnectRules.USER}:%s' \"\$T\" | base64 | tr -d '\\n')\"; " +
            "git -c http.extraHeader=\"\$H\" $args; else git $args; fi"

    fun cloneScript(url: String, guestDir: String, depth: Int?): String =
        "set -e; mkdir -p \"\$(dirname ${q(guestDir)})\"; " +
            authed("clone ${if (depth != null && depth > 0) "--depth $depth " else ""}${q(url)} ${q(guestDir)}")

    fun syncScript(guestDir: String, message: String, name: String, email: String, rebase: Boolean): String =
        "set -e; cd ${q(guestDir)}; git add -A; " +
            "git diff --cached --quiet || git -c user.name=${q(name)} -c user.email=${q(email)} commit -q -m ${q(message)}; " +
            authed(if (rebase) "pull --rebase" else "pull --no-rebase") + "; " + authed("push")

    fun clone(ctx: Context, url: String, dir: File, token: String, depth: Int? = null): GitOpResult {
        val r = shell(ctx).job(cloneScript(url, guest(ctx, dir), depth), stdin = token)
        return GitOpResult(r.ok, if (r.ok) "cloned into ${dir.name} (in cld.termux)" else failed("clone in cld.termux", r), (r.out + "\n" + r.err).trim().takeLast(4000))
    }

    fun sync(ctx: Context, dir: File, message: String, name: String, email: String, rebase: Boolean, token: String): GitOpResult {
        val r = shell(ctx).job(syncScript(guest(ctx, dir), message, name, email, rebase), stdin = token)
        return GitOpResult(r.ok, if (r.ok) "synced (in cld.termux)" else failed("sync in cld.termux", r), (r.out + "\n" + r.err).trim().takeLast(4000))
    }

    /** #1282 a bare mirror at [guestDir] (BareClones.path in guest form); [depth] 0 = full history. Never touches a working clone. */
    fun bareClone(ctx: Context, url: String, guestDir: String, token: String, depth: Int): GitOpResult {
        val r = shell(ctx).job("set -e; mkdir -p \"\$(dirname ${q(guestDir)})\"; " + authed(BareClones.cloneArgs(url, guestDir, depth) { q(it) }), stdin = token)
        return GitOpResult(r.ok, if (r.ok) "bare mirror at $guestDir" else failed("bare clone in cld.termux", r), (r.out + "\n" + r.err).trim().takeLast(4000))
    }

    /** #1282 / #1314 update of an existing mirror (the one-bare rule: no second clone): at [depth] > 0 a depth fetch that also shrinks a full or deeper mirror (BareClones.updateScript), else `remote update --prune`. The summary names the bytes freed. */
    fun bareUpdate(ctx: Context, guestDir: String, token: String, depth: Int = 0): GitOpResult {
        val r = shell(ctx).job(BareClones.updateScript(guestDir, depth, { q(it) }, ::authed), stdin = token)
        val o = BareClones.parseUpdate(r.out)
        val note = if (o.shrunk) ", shrunk to depth $depth, freed ${StalePackCleaner.humanBytes(o.freedBytes)}" else ""
        return GitOpResult(r.ok, if (r.ok) "bare mirror updated at $guestDir$note" else failed("bare update in cld.termux", r), (r.out + "\n" + r.err).trim().takeLast(4000))
    }

    /** #1313 the stale temp pack pass inside cld.termux (StalePackCleaner.script); null when the guest could not run it. */
    fun sweepStalePacks(ctx: Context, gitRoot: File, bareRoot: String, patterns: List<String>, minAgeHours: Int): StalePackCleaner.Result? = runCatching {
        val r = shell(ctx).run(StalePackCleaner.script(guest(ctx, gitRoot), bareRoot, patterns, minAgeHours) { q(it) }, timeoutMs = 120_000)
        if (r.ok) StalePackCleaner.parse(r.out) else null
    }.getOrNull()

    /** #1282 every mirror under the bare root (two levels: <owner>/<repo>.git) with its origin, read in the guest. */
    fun listBare(ctx: Context, bareRoot: File): List<BareClones.Mirror> = runCatching {
        val g = guest(ctx, bareRoot)
        val r = shell(ctx).run("for d in ${q(g)}/*/*.git; do [ -d \"\$d\" ] || continue; printf '%s\\t%s\\n' \"\$d\" \"\$(git --git-dir=\"\$d\" config remote.origin.url 2>/dev/null)\"; done", timeoutMs = 30_000)
        if (!r.ok) emptyList() else r.out.lineSequence().filter { it.isNotBlank() }.map { BareClones.Mirror(it.substringBefore('\t'), it.substringAfter('\t', "")) }.toList()
    }.getOrDefault(emptyList())

    /** `git rev-parse` in the guest: is [dir] a repository (EXEC mode's GitEngine.isRepository). */
    fun isRepository(ctx: Context, dir: File): Boolean =
        runCatching { shell(ctx).run("git -C ${q(guest(ctx, dir))} rev-parse --git-dir >/dev/null 2>&1", timeoutMs = 30_000).ok }.getOrDefault(false)
}
