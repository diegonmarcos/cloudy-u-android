package com.diegonmarcos.superapp.updater.source

import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.apk.VerifiedApk
import com.diegonmarcos.superapp.updater.cache.ApkCache
import android.content.Context
import java.io.File

/**
 * Where an APK can come from.
 *
 * There were two download paths written as two private functions with an early
 * `return` between them, and because nothing forced them to be the same shape
 * their guarantees drifted apart: the GHCR one verified a digest, the release
 * one verified a length — and guarded that check with `if (total > 0 …)`, so a
 * missing Content-Length turned it off entirely. One contract makes the
 * asymmetry impossible to write: every source returns a [VerifiedApk] or
 * nothing, and "try the next one" is a list rather than a control-flow
 * accident.
 */
internal interface ApkSource {
    /** For logs and the Diagnose report — which channel actually served it. */
    val name: String

    /**
     * Fetch [app]'s APK, verified.
     *
     * Returns null when this source DOES NOT APPLY to this app at all — no
     * release URL configured, say. Throws, with a message naming what went
     * wrong, when it applies and fails.
     *
     * Either way [Fleet.download] moves on to the next source: a release URL
     * that 404s is a reason to fall through to GHCR, not a reason to fail an
     * install the other channel could still complete. The distinction is not
     * about control flow, it is about EVIDENCE. Returning null for a failure
     * threw the reason away, and the pipeline's final message could then only
     * report that everything had failed for no stated reason — which is how a
     * transfer that stalled at 182 MB of 265 MB was reported to the user as
     * nothing at all.
     */
    fun fetch(ctx: Context, app: Fleet.App): VerifiedApk?
}

/**
 * THE RELEASE ASSET FIRST, BECAUSE IT IS THE REPO.
 *
 * A GHCR package is owned by the ACCOUNT, not the repository. GitHub creates
 * every new user-owned package private regardless of the repo it was pushed
 * from, image.source only LINKS it, and there is no API to change it —
 * measured, not assumed: a package created by GITHUB_TOKEN inside this public
 * repo's own workflow came out private, and PATCH /user/packages/... 404s even
 * with write:packages.
 *
 * So GHCR can never follow repo visibility, and a private package on a public
 * repo shows up as 401 — which reads to a user as "the app is gone". A release
 * asset has no visibility of its own: it IS the repo, so a public repo's asset
 * is public, for every app, with nothing to click and nothing to remember.
 */
internal object ReleaseSource : ApkSource {
    override val name = "release"

    override fun fetch(ctx: Context, app: Fleet.App): VerifiedApk? {
        if (app.releaseUrl.isBlank()) return null
        // abiReleaseUrl, not releaseUrl: the release asset name is per-ABI
        // (see Fleet.App.assets), and this source is tried FIRST, so a flat
        // arm64 name here is what put an arm64 APK on an x86_64 device.
        val url = app.abiReleaseUrl
        val target = ApkCache.file(ctx, "fleet-${app.id}-release.apk")
        // The declared size, so Download can tell a resumable prefix of THIS
        // artifact from a leftover part of a previous release under the same
        // per-app filename, and so progress has a denominator from the first
        // byte instead of after the first response.
        val declared = Fleet.releaseSize(app)
        return try {
            UpdateProgress.update(UpdateProgress.State.Downloading(0, 0L, declared))
            Download.toFile(
                url = url,
                target = target,
                expectedBytes = declared,
                shouldCancel = { UpdateProgress.cancelRequested },
            ) { written, total ->
                val t = if (total > 0) total else declared
                val pct = if (t > 0) ((written * 100) / t).toInt().coerceIn(0, 100) else 0
                UpdateProgress.update(UpdateProgress.State.Downloading(pct, written, t))
            }
            // A truncated download is the failure this catches: an APK that is
            // short is not an APK, and the installer's error for one is far
            // less useful than saying so here.
            //
            // The size check USED to be guarded by `total > 0`, which made it
            // no check at all whenever the CDN omitted Content-Length: total
            // came back -1, the comparison was skipped, and an unverified file
            // went to the installer. That is exactly how a 383 kB
            // fleet-mail-release.apk — 1.3% of a 29 MB APK — reached
            // PackageInstaller and came back
            // "INSTALL_PARSE_FAILED_NOT_APK: Failed to load asset path".
            // One construction, three guarantees: a declared length must
            // exist, must match, and the bytes must actually be a zip. Failing
            // here falls through to GHCR, which does carry a digest — an
            // unverifiable download must never be RETURNED, because the caller
            // cannot tell the difference once it is just a File.
            // VERIFY THE BYTES, NOT MERELY HOW MANY OF THEM ARRIVED.
            //
            // The ship engine publishes a "<asset>.sha256" sidecar beside every
            // release asset, and CI now hard-verifies the published pin against
            // it — so the exact bytes that were meant to ship ARE knowable from
            // the phone. This path checked only the LENGTH, which cannot tell a
            // 267 MB APK from a DIFFERENT 267 MB APK. That is not hypothetical
            // here: on 2026-08-30 several distinct builds all landed on exactly
            // 32,012,393 bytes and the store was wrong about every one of them.
            // A length check spends a whole install attempt — and, when the
            // installer's answer goes unread, spends it silently — to discover
            // what the digest answers before the attempt is made.
            //
            // Size remains the fallback for apps not yet re-shipped with a
            // sidecar. "No digest published" must degrade to the older, weaker
            // check; it must never degrade to no check.
            val sha = Fleet.releaseSha256(app)
            val verified = if (sha != null) VerifiedApk.byDigest(target, sha)
                           else VerifiedApk.bySize(target, declared)
            verified ?: run {
                // Known-bad bytes: drop the partial too, or every later attempt
                // resumes on top of them forever.
                ApkCache.drop(target)
                error("release asset failed verification (${target.length()} B against a " +
                      "declared $declared" +
                      (if (sha != null) ", sha256 $sha" else ", no sha256 sidecar published") +
                      ") — deferring to GHCR")
            }
        } catch (c: java.util.concurrent.CancellationException) {
            // A cancel is the user's decision, not a reason to go and try the
            // other source with the same 265 MB.
            throw c
        } catch (t: Throwable) {
            // THIS CATCH USED TO BE `runCatching { ... }.getOrNull()`.
            //
            // That swallowed every Throwable — socket timeout, connection
            // reset, OutOfMemoryError, the explicit verification error — with
            // no log line and, far worse, no UpdateProgress transition. The
            // last state the user could see stayed `Downloading(43%)` while
            // control quietly fell through to GHCR, which republished
            // `Downloading(0, …)` and began the same 265 MB again from byte
            // zero. A progress bar that resets and a progress bar that is
            // frozen are the same picture to someone watching: "it sticks on
            // downloading and never progresses".
            //
            // At 25 MB this path essentially never ran, because the transfer
            // finished inside one TCP session. Nothing about it was safe; it
            // was untested.
            //
            // Falling through to GHCR is still right — GHCR carries a digest
            // and may well succeed where the release CDN did not — but it must
            // be a decision that leaves evidence, and the partial file stays on
            // disk so a retry resumes rather than restarts.
            // #831 a classified failure (DNS / not published) is reported as
            // what it is; "kept 0 B for resume" only buries it.
            if (DownloadFailure.isFinal(t)) throw t
            val kept = File(target.parentFile, target.name + ".part").length()
            throw java.io.IOException(
                "${t.message ?: t.javaClass.simpleName} (kept $kept B on disk for resume)", t)
        }
    }
}

/**
 * The OCI blob, kept as the fallback because it carries a digest and a
 * manifest — a stronger integrity story than a declared length. Access first,
 * integrity second: an app nobody can download is not made safer by its
 * digest.
 */
internal object GhcrSource : ApkSource {
    override val name = "ghcr"

    override fun fetch(ctx: Context, app: Fleet.App): VerifiedApk? {
        val client = GhcrClient(app.registry, app.namespace, app.image)
        val token = client.token()
        val layer = Fleet.remoteLayerFor(app, client, token)
        val target = ApkCache.file(ctx, "fleet-${app.id}-${layer.digest.substringAfter(':').take(12)}.apk")
        UpdateProgress.update(UpdateProgress.State.Downloading(0, 0L, layer.size))
        // Raw fleet threads aren't WorkManager — the Cancel button reaches them
        // only through UpdateProgress.cancelRequested.
        client.blob(layer.digest, token, target, layer.size, { UpdateProgress.cancelRequested }) { bytes, total ->
            val t = if (total > 0) total else layer.size
            val pct = if (t > 0) ((bytes * 100) / t).toInt().coerceIn(0, 100) else 0
            UpdateProgress.update(UpdateProgress.State.Downloading(pct, bytes, t))
        }
        val verified = VerifiedApk.byDigest(target, layer.digest)
        if (verified == null) {
            // Both the file AND the partial: bytes that failed a digest are
            // known-bad, and a resume on top of them can only ever fail again.
            ApkCache.drop(target)
            // Throw rather than publish Failed and return null. Fleet.download
            // owns the terminal state now, and it needs this reason to put in
            // it; publishing here as well raced its own caller and reported a
            // digest mismatch as the outcome of a pass that had another source
            // still to try.
            error("digest mismatch against ${layer.digest}")
        }
        // Keep the verified APK, drop this app's superseded ones. Keeping it
        // means a retry after a failed install reuses the download instead of
        // pulling the blob again.
        client.pruneCache("fleet-${app.id}-", target)
        return verified
    }
}

/**
 * #837 THE MESH MIRROR — the leg that needs no public DNS.
 *
 * #831: the phone could not resolve github.com or ghcr.io, so both legs above
 * failed and the Store had nothing left to try. The fleet's git-proxy-api now
 * streams the very same release asset (and its sidecars) from GitHub
 * server-side, reachable on the mesh-private name the fleet declares for it
 * (cloud-u-containers infra-api_git-proxy-api build.json: dns
 * git-proxy-api.mesh.diegonmarcos.com, ports.app 8123). A direct wg0 dial needs no credential
 * there — which is the point: the Store's downloader has none.
 *
 * #1093 Tried FIRST (mesh first, like every fleet endpoint); the public legs follow when it is down. The sha256 sidecar is REQUIRED here, never the
 * size fallback ReleaseSource keeps for old ships — a second channel must not
 * be a weaker one, and the digest comes from the mirror itself so it is
 * readable exactly when this leg is the only one that works.
 */
// The mechanism is public (like Download, #571) so the SuperApp's tests can
// drive it; the ApkSource entry below stays internal like its siblings.
object MeshMirror {

    /** The git-proxy-api mesh origin, from the one fleet declaration (ac_cloud-account build.json fleet_endpoints, baked as BuildConfig.MESH_MIRROR_BASE). */
    val BASE: String get() = com.diegonmarcos.superapp.updater.BuildConfig.MESH_MIRROR_BASE

    /**
     * The same service by its wg0 address. MEASURED 2026-10-03 from oci-apps:
     * git-proxy-api.mesh.diegonmarcos.com resolves to the hub (fd0c:1d00::1), which has no
     * listener on 8123 and no internal .app route for this service — only
     * 10.0.0.4:8123 (oci-analytics wg0, data/mesh.json oci-E2-f_1, the address
     * the service binds per its compose.nix) answers. Tried second, so the
     * declared name wins as soon as the hub routes it; and an IP needs no DNS,
     * which is the whole point of this leg (#831).
     */
    const val BASE_WG = "http://10.0.0.4:8123"

    /** The mirror origins, in order; a local stub in tests. */
    @Volatile var bases: List<String> = listOf(BASE, BASE_WG).filter { it.isNotBlank() }

    /** Whether the wg0 tunnel is up, from the host that owns it (SuperApp:
     *  FleetDns.meshUp). null = not known here, and the leg is tried as before. */
    @Volatile var meshUp: (Context) -> Boolean? = { null }

    private val RELEASE_DOWNLOAD =
        Regex("^https://github\\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/([^/?#]+)$")

    /**
     * The mirror URL for a GitHub release-download URL, or null when the URL is
     * not one (then this leg does not apply). Pure, so the mapping is pinned by
     * a test rather than discovered on a phone.
     */
    fun urlFor(releaseUrl: String, base: String = BASE): String? {
        val m = RELEASE_DOWNLOAD.matchEntire(releaseUrl.trim()) ?: return null
        val (owner, repo, tag, asset) = m.destructured
        return "${base.trimEnd('/')}/releases/$owner/$repo/$tag/assets/$asset"
    }

    /** Either a bare digest or the sha256sum(1) form "<hex>  <filename>". */
    fun parseSha256(body: String?): String? =
        body?.trim()?.substringBefore(' ')?.trim()?.lowercase()
            ?.takeIf { s -> s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' } }

    /** The sidecar from the mirror. Throws, classified, when it cannot be read. */
    fun sha256At(assetUrl: String): String {
        val sidecar = "$assetUrl.sha256"
        val c = try {
            (java.net.URL(sidecar).openConnection() as java.net.HttpURLConnection).apply {
                // Short: this leg is tried FIRST now, and a dead mesh must cost seconds, not the public legs' patience.
                connectTimeout = 3_000
                readTimeout = 10_000
            }
        } catch (t: Throwable) { throw java.io.IOException("mesh sidecar: ${t.message}", t) }
        try {
            val code = try { c.responseCode } catch (u: java.net.UnknownHostException) {
                throw DownloadFailure.Unresolvable(java.net.URL(sidecar).host, u)
            }
            if (code !in 200..299) {
                // The body is only detail for the message; a read failure here must
                // not replace the status it is describing.
                val err = try { c.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: java.io.IOException) { null }
                throw DownloadFailure.HttpStatus(code, sidecar, err)
            }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            return parseSha256(body) ?: error("mesh sidecar $sidecar is not a sha256 digest")
        } finally {
            c.disconnect()
        }
    }

    /**
     * #1517 Which mesh URL serves [app] and the digest its sidecar declares, or null when this leg does not apply. Throws, classified,
     * when the leg applies and cannot answer. Shared by [fetch] and the download engine, so there is one reading of the mesh leg.
     */
    fun plan(ctx: Context, app: Fleet.App): Pair<String, String>? {
        if (app.releaseUrl.isBlank()) return null
        // Both origins live on wg0 and the declared name resolves only through
        // the fleet resolver the tunnel carries, so with the mesh down this leg
        // cannot answer and the row must say THAT — not "cannot resolve
        // git-proxy-api.mesh.diegonmarcos.com (active resolver: unknown)".
        // A throwing hook means "not known" (null), never a swallowed failure:
        // test-download-resume T4 bans the getOrNull() shape in this file.
        val up: Boolean? = try { meshUp(ctx) } catch (_: Exception) { null }
        if (up == false)
            throw java.io.IOException("mesh down: ${bases.joinToString(", ")} need the wg0 tunnel")
        // Digest FIRST, and it also picks the origin: the first base whose
        // sidecar answers is the one the bytes come from. Without a digest the
        // bytes could not be trusted, so they are not worth fetching.
        var url: String? = null
        var sha: String? = null
        val failures = mutableListOf<Throwable>()
        for (b in bases) {
            val u = urlFor(app.abiReleaseUrl, b) ?: return null
            try { sha = sha256At(u); url = u; break } catch (t: Throwable) { failures += t }
        }
        if (url == null || sha == null) {
            // A 404 from a mirror that answered says more than a dead origin:
            // report that one; otherwise the first (declared-name) failure.
            throw failures.firstOrNull { DownloadFailure.kind(it) == DownloadFailure.Kind.NOT_PUBLISHED }
                ?: failures.firstOrNull() ?: IllegalStateException("no mesh mirror origin configured")
        }
        return url to sha
    }

    /** The leg itself; [MeshMirrorSource] is only its place in Fleet's list. */
    fun fetch(ctx: Context, app: Fleet.App): VerifiedApk? {
        val (url, sha) = plan(ctx, app) ?: return null
        val target = ApkCache.file(ctx, "fleet-${app.id}-mesh.apk")
        UpdateProgress.update(UpdateProgress.State.Downloading(0, 0L, -1L))
        try {
            Download.toFile(
                url = url,
                target = target,
                shouldCancel = { UpdateProgress.cancelRequested },
            ) { written, total ->
                val pct = if (total > 0) ((written * 100) / total).toInt().coerceIn(0, 100) else 0
                UpdateProgress.update(UpdateProgress.State.Downloading(pct, written, total))
            }
        } catch (c: java.util.concurrent.CancellationException) {
            throw c
        } catch (t: Throwable) {
            if (DownloadFailure.isFinal(t)) throw t
            val kept = File(target.parentFile, target.name + ".part").length()
            throw java.io.IOException(
                "${t.message ?: t.javaClass.simpleName} (kept $kept B on disk for resume)", t)
        }
        return VerifiedApk.byDigest(target, sha) ?: run {
            ApkCache.drop(target)
            error("mesh mirror asset failed verification (${target.length()} B, sha256 $sha)")
        }
    }
}

internal object MeshMirrorSource : ApkSource {
    override val name = "mesh"
    override fun fetch(ctx: Context, app: Fleet.App): VerifiedApk? = MeshMirror.fetch(ctx, app)
}
