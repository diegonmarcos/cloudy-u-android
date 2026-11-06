package com.diegonmarcos.superapp.updater

import com.diegonmarcos.superapp.updater.apk.ApkIntegrity
import com.diegonmarcos.superapp.updater.apk.VerifiedApk
import com.diegonmarcos.superapp.updater.cache.ApkCache
import com.diegonmarcos.superapp.updater.install.InstallChannel
import com.diegonmarcos.superapp.updater.install.InstallGate
import com.diegonmarcos.superapp.updater.install.SessionInstall
import com.diegonmarcos.superapp.updater.install.ShellInstall
import com.diegonmarcos.superapp.updater.install.UpdateInstaller
import com.diegonmarcos.superapp.updater.source.ApkSource
import com.diegonmarcos.superapp.updater.source.GhcrClient
import com.diegonmarcos.superapp.updater.source.GhcrSource
import com.diegonmarcos.superapp.updater.source.MeshMirrorSource
import com.diegonmarcos.superapp.updater.source.ReleaseSource
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Constellation AppStore engine — checks / installs / updates / uninstalls
 * EVERY constellation APK, not just self. Reuses [GhcrClient] (OCI digest pull)
 * and [UpdateInstaller] (installs foreign packages). No R references — the app
 * module owns the manifest source (BuildConfig.CONSTELLATION_FLEET_B64) and the
 * UI; this engine is pure mechanism.
 *
 * Flat model (data-driven): the fleet JSON is auto-scanned from each app's own
 * build.json by data/regen.sh → data/constellation-fleet.json → BuildConfig.
 */
object Fleet {
    private const val TAG = "Fleet"

    data class App(
        val id: String,
        val label: String,
        val pkg: String,
        // Real installed package of a resigned STOCK upstream APK that isn't
        // repackaged to `pkg` yet (chat=com.mattermost.rnbeta,
        // matrix=io.element.android.x). Detection accepts either; null = the
        // APK already declares `pkg` (patched forks + self).
        val altId: String?,
        val registry: String,
        val namespace: String,
        val image: String,
        val tag: String,
        val asset: String,
        // Per-ABI release asset names, keyed by ABI, from each app's
        // build.json::release.variants[] via data/regen.sh. Empty for an app
        // that publishes a single universal APK — those keep [asset] alone.
        //
        // THE ABI DIMENSION USED TO STOP HALF WAY. [remoteLayer] already picked
        // the right GHCR tag for the device (`latest` vs `latest-x86_64`), but
        // the GitHub Release path had exactly one flat [asset] — always the
        // arm64 one, because that is the first variant in build.json. Release
        // wins over registry in [status], so an x86_64 device took the arm64
        // APK from the release and either 404ed or hit
        // INSTALL_FAILED_NO_MATCHING_ABIS. One path was ABI-aware and the other
        // silently served the wrong architecture.
        val assets: Map<String, String>,
        val releaseUrl: String,
        val repoUrl: String,
        val ghcrPage: String,
        val blocked: Boolean,
        // "app" (default) or "lib" — a companion APK that ships engines behind
        // AIDL bound services instead of UI. Data-driven from each app's
        // build.json::release.kind; splits the Constellation page's Apps/Libs tabs.
        val kind: String,
        // OUR declared version, from this app's own
        // build.json::android.version_name / version_code via data/regen.sh
        // (#631). The version a row shows must be the fleet's own, and the only
        // version the device can otherwise see is the INSTALLED APK's — which
        // for a fork is upstream's string and for our apps already has the sha
        // folded into it. Null/0 where nothing declares one (the comms forks
        // keep upstream's versioning); [FleetIdentity] says so instead of
        // substituting a number from somewhere else.
        val declaredVersionName: String? = null,
        val declaredVersionCode: Long = 0L,
        // #678 the libs this app LINKS (fleet ids, lib-<module>), from its own
        // build.json::modules.app.depends_on via data/regen.sh — compiled in by
        // definition, so never presented as something to install for this app.
        val libs: List<String> = emptyList(),
        // The engines it BINDS at runtime (fleet ids), from build.json::engines
        // (#728) — the libs that must be installed separately.
        val engineLibs: List<String> = emptyList(),
    ) {
        /**
         * [releaseUrl] with its asset filename swapped for THIS device's ABI
         * variant — the URL every release download, HEAD probe and sidecar
         * fetch must use.
         *
         * Selection is [AbiUpdateTag]'s rule, not a second one: walk
         * Build.SUPPORTED_ABIS in order and take the first ABI present in
         * [assets]. Falls back to [releaseUrl] verbatim whenever there is no
         * map, no match, or nothing to swap — an arm64 device that works today
         * must keep working byte for byte.
         */
        val abiReleaseUrl: String get() {
            if (releaseUrl.isBlank() || asset.isBlank() || assets.isEmpty()) return releaseUrl
            if (!releaseUrl.endsWith("/$asset")) return releaseUrl
            val picked = AbiUpdateTag.currentFrom(assets, asset)
            return if (picked == asset) releaseUrl
                   else releaseUrl.removeSuffix(asset) + picked
        }
    }

    /** Live state of one app on this device vs. its GHCR image. */
    sealed class State(
        /** APK size in bytes: the DOWNLOAD size when the manifest was reached,
         *  otherwise the installed APK's own size, and 0 when neither is known
         *  (blocked, or the check failed before either was available). Carried
         *  on the base class because every variant has a size to show and the
         *  UI should not have to branch to find it. */
        val bytes: Long,
    ) {
        class Installed(val versionName: String, val versionCode: Long, val sha12: String, bytes: Long = 0L) : State(bytes)
        // [source] is WHICH channel answered — "release" or "ghcr" — set by
        // [status]/[releaseStatus], the only two places that know it. A detail
        // screen that wants to say "served by GH release" must read it from
        // here, not re-derive it from app.releaseUrl.isNotEmpty(): that would
        // guess the same branch [status] already took and could disagree with
        // it the day the branching logic changes in one place and not the other.
        class UpdateAvailable(val versionName: String?, val remoteDigest12: String, bytes: Long = 0L, val source: String) : State(bytes)
        class Missing(bytes: Long = 0L) : State(bytes)
        class Blocked : State(0L)
        class Error(val message: String) : State(0L)
    }

    /** Decode BuildConfig.CONSTELLATION_FLEET_B64 (base64 JSON) into apps. */
    fun parse(fleetB64: String): List<App> {
        return try {
            val json = String(Base64.decode(fleetB64, Base64.DEFAULT))
            val arr = JSONObject(json).optJSONArray("apps") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                App(
                    id = o.getString("id"),
                    label = o.optString("label", o.getString("id")),
                    pkg = o.getString("package"),
                    altId = o.optString("alt_id").takeIf { it.isNotEmpty() },
                    registry = o.getString("registry"),
                    namespace = o.getString("namespace"),
                    image = o.getString("image"),
                    tag = o.optString("tag", "latest"),
                    asset = o.optString("asset", ""),
                    assets = o.optJSONObject("assets")?.let { m ->
                        m.keys().asSequence()
                            .mapNotNull { k -> m.optString(k).takeIf { it.isNotEmpty() }?.let { k to it } }
                            .toMap()
                    } ?: emptyMap(),
                    releaseUrl = o.optString("release_url", ""),
                    repoUrl = o.optString("repo_url", ""),
                    ghcrPage = o.optString("ghcr_page", ""),
                    blocked = o.optBoolean("blocked", false),
                    kind = o.optString("kind", "app").takeIf { it.isNotEmpty() } ?: "app",
                    declaredVersionName = o.optString("version_name").takeIf { it.isNotEmpty() },
                    declaredVersionCode = o.optLong("version_code", 0L),
                    libs = o.optJSONArray("libs")?.let { a ->
                        (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
                    } ?: emptyList(),
                    engineLibs = o.optJSONArray("engines")?.let { a ->
                        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("fleet") }
                            .filter { it.isNotEmpty() }.distinct()
                    } ?: emptyList(),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "parse failed: ${t.message}")
            emptyList()
        }
    }

    /** ABI-aware tag. Across the constellation, x86_64 is the ONLY ABI that
     *  publishes a suffixed variant tag (`<tag>-x86_64`); arm64 is the default
     *  publish under the universal `<tag>`. The old code composed
     *  `<tag>-arm64-v8a`, which never exists — a guaranteed 404 + fallback
     *  round-trip on every arm64 device. Only reach for the suffix on x86_64. */
    private fun remoteLayer(app: App, client: GhcrClient, token: String): GhcrClient.ManifestLayer {
        if (Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") {
            try {
                return client.manifest("${app.tag}-x86_64", token)
            } catch (_: Throwable) { /* variant not published yet — universal tag */ }
        }
        return client.manifest(app.tag, token)
    }

    /** Compute install/update status for one app. Network per call. */
    fun status(ctx: Context, app: App): State {
        if (app.blocked) return State.Blocked()
        val installed = installedInfo(ctx, app)
        // THE RELEASE ASSET DECIDES, WHEN THERE IS ONE.
        //
        // GHCR answers 401/403 for a package that is private OR absent, and an
        // app that ships only on its GH Release now has no package at all — so
        // probing GHCR reports 403 for something that is downloadable right
        // now. That is the failure this branch exists to end, and the comment
        // below already recorded it happening to cloud-watchdog.
        //
        // The release asset has no visibility of its own: it IS the repo. If
        // it answers, it is the truth about whether this app can be installed.
        //
        // EXCEPT FOR OUR OWN PACKAGE, AND THE GUARD BELOW IS WHY.
        //
        // The code-identity short-circuit further down exists because our
        // builds are not byte-reproducible: a rebuild of the SAME COMMIT
        // produces a different APK sha, so a byte comparison reports an update
        // that installing can never satisfy — the next pass compares against
        // yet another sha and asks again, forever. That guard compares the
        // remote manifest's git revision instead, which is the only identity
        // a rebuild preserves.
        //
        // It was unreachable for the one entry it was written for. This app
        // publishes a release asset, so releaseStatus answered first and
        // returned, and the guard below it never ran. The result was a fleet
        // row for THIS package stuck on UpdateAvailable while
        // UpdateChecker.available() — the self-updater, which applies the
        // revision rule — reported the same package up to date. One package,
        // two identity keys, two verdicts: the phantom "second cloud-sa"
        // demanding updates that were already installed.
        //
        // Self is answered by code identity, so self skips the byte path. The
        // rule now matches UpdateChecker's by construction rather than by
        // two copies of it staying in step.
        if (app.pkg != ctx.packageName) releaseStatus(app, installed)?.let { return it }
        return try {
            val client = GhcrClient(app.registry, app.namespace, app.image)
            val layer = remoteLayer(app, client, client.token())
            val remote12 = layer.digest.substringAfter(':').take(12)
            // Valid manifest ⇒ remote APK exists. Not installed ⇒ offer install.
            if (installed == null) return State.Missing(layer.size)
            // Code-identity short-circuit for the SELF entry: builds are not
            // byte-reproducible, so a same-commit rebuild has a different APK
            // sha and would show a phantom "update available". When this is our
            // own package and the manifest revision matches our built-in git
            // sha, we are up to date regardless of bytes. (Foreign apps don't
            // expose their revision, so sha comparison remains their signal.)
            if (app.pkg == ctx.packageName && layer.revision != null &&
                layer.revision == BuildConfig.GIT_SHORT_SHA) {
                return State.Installed(installed.versionName, installed.versionCode, installed.sha.take(12), layer.size)
            }
            val currentSha = "sha256:" + installed.sha
            if (currentSha == layer.digest)
                State.Installed(installed.versionName, installed.versionCode, installed.sha.take(12), layer.size)
            else
                State.UpdateAvailable(installed.versionName, remote12, layer.size, source = "ghcr")
        } catch (e: GhcrClient.HttpException) {
            // NOT INSTALLED IS A LOCAL FACT. Whether the registry answers has no
            // bearing on it, so a failed probe must not turn "missing" into
            // "unknown" — that hid exactly the apps the ◯ Missing filter exists
            // to find. 404 already did the right thing; 401 and 403 did not, and
            // GHCR answers 401/403 for a package that is private OR absent, which
            // is the normal state of anything not yet published. cloud-watchdog
            // (401) and cloud-infra-desktop-termux-boot (403) both vanished.
            // Installed + unreachable registry stays an honest error: there we
            // genuinely cannot tell whether an update is waiting.
            installed?.let {
                if (e.code == 404) State.Installed(it.versionName, it.versionCode, it.sha.take(12), it.bytes)
                else State.Error(registryFailure(e.code))
            } ?: State.Missing()
        } catch (t: Throwable) {
            // An installed app whose remote check THREW must not masquerade as
            // "Installed" — that silently hides real updates behind transient
            // network failures. On an IPv6-only carrier (no route to 1.1.1.1)
            // every GHCR token fetch throws UnknownHost, and the store showed
            // "Installed" while an update sat on the release (2026-08-30).
            // Missing stays a local fact; a failed check on an installed app
            // is an honest Error.
            installed?.let { State.Error("check failed: " + com.diegonmarcos.superapp.updater.source.DownloadFailure.describe(t)) }
                ?: State.Missing()
        }
    }

    /**
     * What a failed registry probe MEANS, in words the owner can act on.
     *
     * This used to render as the bare string "HTTP 403", which is the least
     * useful thing it could have said. On 2026-09-10 Cloud Terminal (Nix)
     * showed exactly that, and a bare 403 gives no way to tell apart the three
     * situations that produce it — so diagnosing it took a walk back through
     * the release assets, the workflow list and 431 GHCR packages to establish
     * that cloud-nixdroid had simply never been pushed. That work should have
     * been one line on the phone.
     *
     * GHCR deliberately refuses to distinguish "private" from "does not
     * exist" to an anonymous caller: both answer 403 DENIED at the /token
     * endpoint, because confirming absence would leak the existence of private
     * packages. So this cannot claim which one it is — but it CAN say that the
     * two are indistinguishable and name both remedies, which is the actual
     * next step either way. Reaching this branch at all already means the
     * release asset did not answer, so neither channel is serving this app.
     */
    private fun registryFailure(code: Int): String = when (code) {
        401, 403 ->
            "not published (HTTP $code): no release asset, and its registry " +
            "package is either private or was never pushed"
        in 500..599 -> "registry is down (HTTP $code) — try again later"
        else -> "registry refused the check (HTTP $code)"
    }

    /**
     * Byte size of [app]'s release asset for THIS device's ABI, or -1 when the
     * asset is absent, unreachable, or served without a Content-Length.
     *
     * Extracted because it has two callers with the same question and they must
     * not answer it differently: [releaseStatus] uses it to decide whether an
     * update exists, and ReleaseSource needs it as the download's expected
     * length — both the denominator for progress and the signal that lets
     * Download tell a resumable prefix of this artifact from a leftover of a
     * previous one. A second HEAD probe written slightly differently is how the
     * store came to report a size the downloader disagreed with.
     */
    internal fun releaseSize(app: App): Long =
        releaseAsset(app)?.takeIf { it.ok }?.bytes ?: -1L

    /**
     * What the ONE HEAD probe learned about the published asset.
     *
     * [releaseSize] used to be the whole of it and answered a single question
     * with a single number, which meant a caller that also wanted the publish
     * time or the status code had to open a second connection - and "a second
     * HEAD probe written slightly differently" is, verbatim, how the store came
     * to report a size the downloader disagreed with. One probe, every fact it
     * saw, and callers pick.
     *
     * [status] is carried rather than collapsed into a boolean on purpose. #257
     * ("Cloud Terminal (Nix) returns HTTP 403 on install/update") was a generic
     * "failed" on screen for a specific, nameable refusal; a UI holding this can
     * say 403.
     */
    data class ReleaseAsset(
        /** The ABI-resolved URL that was probed - the one a link should open. */
        val url: String,
        /** Content-Length, or -1 when the server declined to declare one. */
        val bytes: Long,
        /** `Last-Modified` as epoch millis, or 0 when the server did not say. */
        val publishedAtMillis: Long,
        /** The HTTP status the probe ended on, after redirects. */
        val status: Int,
    ) {
        val ok: Boolean get() = status in 200..299
    }

    /**
     * HEAD the release asset for THIS device's ABI. Null only when the probe
     * could not complete at all (no network, DNS, TLS) - a reachable server
     * that said 403 or 404 comes back as a [ReleaseAsset] carrying that status,
     * because "the server answered, and this is what it said" is information
     * and must not be flattened into the same silence as "there was no server".
     *
     * NO Authorization HEADER, DELIBERATELY. These are public release assets,
     * and `releases/download/...` answers with a 302 to a pre-signed object
     * store URL that already carries its own credentials in the query string.
     * Carrying a bearer token across that redirect is one of the documented
     * ways to turn a working download into a 403 (#257). Nothing here needs to
     * authenticate, so nothing here does - which also means there is no token
     * on this path to leak into a log.
     */
    fun releaseAsset(app: App): ReleaseAsset? = runCatching {
        val url = app.abiReleaseUrl
        val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection)
        c.requestMethod = "HEAD"
        c.instanceFollowRedirects = true
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        val asset = ReleaseAsset(
            url = url,
            bytes = c.contentLengthLong,
            publishedAtMillis = c.lastModified,
            status = c.responseCode,
        )
        c.disconnect()
        asset
    }.getOrNull()

    /**
     * State from the release asset, or null when this app declares none or the
     * probe fails — in which case the caller falls through to GHCR.
     *
     * IDENTITY FIRST, SIZE ONLY AS FALLBACK. The ship engine publishes a
     * "<asset>.sha256" sidecar next to every APK, so the exact bytes on the
     * release can be compared against the exact bytes installed.
     *
     * This used to compare SIZE alone, and that silently broke the store. On
     * 2026-08-30 several different builds all landed on exactly 32,012,393
     * bytes; the installed APK and the release asset were different content
     * (sha dbf86559… vs c8ff19ec…) at identical size, so this returned
     * Installed and — because release wins over registry — gated the GHCR
     * digest check behind it. The store reported "no update" permanently.
     * A size compare cannot answer "are these the same bytes"; only a digest
     * can, which is why the sidecar exists. Size remains the fallback purely
     * for apps that have not been re-shipped with a sidecar yet.
     */
    private fun releaseStatus(app: App, installed: Installed?): State? {
        if (app.releaseUrl.isBlank()) return null
        val size = releaseSize(app)
        if (size < 0) return null
        val i = installed ?: return State.Missing(size)
        releaseSha256(app)?.let { remote ->
            return if (i.sha.equals(remote, ignoreCase = true)) {
                State.Installed(i.versionName, i.versionCode, i.sha.take(12), size)
            } else {
                State.UpdateAvailable(i.versionName, remote.take(12), size, source = "release")
            }
        }
        return if (i.bytes == size) {
            State.Installed(i.versionName, i.versionCode, i.sha.take(12), size)
        } else {
            State.UpdateAvailable(i.versionName, "release", size, source = "release")
        }
    }

    /**
     * The hex sha256 the ship engine published beside the release asset, or
     * null when this app has not been re-shipped with a sidecar yet (in which
     * case the caller falls back to the size compare). Never throws: a missing
     * or malformed sidecar must degrade to the old behaviour, not to an error.
     */
    fun releaseSha256(app: App): String? = runCatching {
        val c = (java.net.URL(app.abiReleaseUrl + ".sha256").openConnection()
                as java.net.HttpURLConnection)
        c.instanceFollowRedirects = true
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        val body = if (c.responseCode in 200..299) {
            c.inputStream.bufferedReader().use { it.readText() }
        } else null
        c.disconnect()
        // Either a bare digest or the sha256sum(1) form "<hex>  <filename>".
        body?.trim()?.substringBefore(' ')?.trim()?.lowercase()
            ?.takeIf { s -> s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' } }
    }.getOrNull()

    /**
     * Download the blob, verify it, install/update [app] (foreign pkg).
     *
     * THE FAILURE MUST BE PUBLISHED HERE, not left to the caller.
     *
     * This threw and said nothing to [UpdateProgress]. Every caller runs it on
     * its own thread and reports the throwable its own way — the Constellation
     * page raises a four-second Toast — while the progress row, whose only
     * input is UpdateProgress, kept rendering the last [State.Downloading]
     * frame it was given. Forever. A download that had already died therefore
     * displayed as one still running, which is exactly the "sticks on
     * downloading and never progresses" the owner reported: the Toast is gone
     * in four seconds and the frozen bar is what remains.
     *
     * A terminal outcome is part of running the pass, so it belongs to the pass.
     */
    fun install(ctx: Context, app: App): Boolean {
        try {
            return observesOutcome(commit(ctx, app, download(ctx, app)))
        } catch (c: java.util.concurrent.CancellationException) {
            UpdateProgress.update(UpdateProgress.State.Cancelled)
            throw c
        } catch (t: Throwable) {
            UpdateProgress.update(UpdateProgress.State.Failed(
                message = t.message ?: t.javaClass.simpleName,
                appId = app.id,
                pkg = app.pkg,
            ))
            throw t
        }
    }

    /**
     * Fetch and verify [app]'s APK, WITHOUT installing it. Returns the file.
     *
     * Separate from [commit] because a batch downloads everything first and
     * only then starts installing: an install prompt blocks on the user, so
     * interleaving the two made every tap wait on the next app's network
     * fetch. Downloading first means the prompts run back to back.
     */
    /**
     * Fetch [app]'s APK from the first source that can serve it, verified.
     *
     * A LIST, not a chain of early returns: the two paths used to be private
     * functions with `releaseDownload(...)?.let { return it }` between them,
     * and nothing held them to the same contract — so one verified a digest
     * and the other verified a length it could skip. Adding a third source is
     * now one entry here, and it cannot be added without returning evidence.
     *
     * Separate from [commit] because a batch downloads everything first and
     * installs afterwards: an install prompt blocks on the user, so
     * interleaving made every tap wait on the next app's network fetch.
     */
    private val sources: List<ApkSource> = listOf(MeshMirrorSource, ReleaseSource, GhcrSource)

    /** #831 the legs in the order they are tried, by name. #1093 the mesh mirror (git-proxy-api's release-asset
     *  route on the wg0-private name) is FIRST, like every fleet endpoint: mesh first, the public legs (release,
     *  GHCR) when it is down or does not carry the asset. */
    val sourceOrder: List<String> get() = sources.map { it.name }

    /**
     * Does a clean return from [channelName] mean INSTALLED, or only HANDED OVER?
     *
     * [ShellInstall] runs `pm install` and reads its answer, so when it says
     * nothing went wrong it has actually watched the install finish. A
     * PackageInstaller commit has not: it hands a session to the system and
     * returns, and the real outcome arrives later at [PackageInstallerReceiver].
     *
     * The distinction has to be visible to callers because treating a commit as
     * a result is how "installed successfully" gets recorded for an install
     * that went on to fail. [install] returns this rather than Unit so a caller
     * cannot silently assume the stronger of the two.
     */
    fun observesOutcome(channelName: String): Boolean = channelName == ShellInstall.name

    fun download(ctx: Context, app: App): VerifiedApk {
        UpdateProgress.update(UpdateProgress.State.CheckingManifest)
        // #774 THE CACHE IS ASKED BEFORE THE NETWORK. A 400 MB lib finished
        // downloading, the user cancelled Android's install sheet, and the next
        // Install fetched all 400 MB again. The bytes were still on disk — the
        // receiver keeps them on every non-success — but ReleaseSource never
        // looked: Download.toFile short-circuits only on a complete `.part`, and
        // a finished download is no longer a `.part`. GhcrClient.blob had its
        // own content-addressed hit; the release path, tried FIRST, had none.
        // #785 hashing a cached build can take seconds; the bar says so.
        UpdateProgress.stage(UpdateProgress.STAGE_VERIFYING)
        cachedRelease(ctx, app)?.let { hit ->
            Log.i(TAG, "download ${app.kind} ${app.id}: cache HIT ${hit.file.name} → ${hit.evidence}")
            UpdateProgress.update(UpdateProgress.State.Downloading(100, hit.length, hit.length))
            if (ApkCache.record(hit.file) == null) ApkCache.keep(ctx, hit.file)
            ApkCache.clearNote(ctx, app.pkg)
            return hit
        }
        // NAME WHAT DECLINED, AND WHY — the same rule [commit] already applies
        // to install channels. A source that could not serve it used to
        // disappear into a bare `continue`, so the final message could only say
        // that two unnamed things had failed for unnamed reasons. That is what
        // turned "the transfer stalled at 182 MB of 265 MB with no new data for
        // two minutes" into "no source could provide a verified APK".
        val declined = mutableListOf<String>()
        UpdateProgress.stage(UpdateProgress.STAGE_DOWNLOADING)
        for (source in sources) {
            val apk = try {
                source.fetch(ctx, app)
                    ?: run { declined += "${source.name} → not configured for this app"; null }
            } catch (c: java.util.concurrent.CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Falling through to the next source is still right — GHCR
                // carries a digest and may well succeed where the release CDN
                // did not — but the reason has to survive the fall.
                Log.w(TAG, "source '${source.name}' could not serve ${app.id}: ${t.message}", t)
                // #831 classified: "DNS: cannot resolve github.com (active
                // resolver: …)" / "not published on the release yet", else the raw reason.
                declined += "${source.name} → ${com.diegonmarcos.superapp.updater.source.DownloadFailure.describe(t)}"
                null
            } ?: continue
            // WHICH SOURCE SERVED IT is the first question to ask of a stale
            // artifact, and it used to be unanswerable from the logs.
            Log.i(TAG, "download ${app.kind} ${app.id}: source=${source.name} " +
                       "→ ${apk.evidence}, ${apk.length} bytes")
            // #625 RECORD WHAT WAS DOWNLOADED, HERE, ONCE. Every fleet source
            // funnels through this return, so the sha256 + identity that
            // retention is later gated on cannot be missing for some sources and
            // present for others — and the cache is brought back under its
            // declared bound at the same moment, before the next app's download
            // pushes it further over.
            ApkCache.keep(ctx, apk.file)
            ApkCache.clearNote(ctx, app.pkg)
            return apk
        }
        val why = "could not download ${app.id}: " + declined.joinToString(" | ")
        ApkCache.note(ctx, app.pkg, ApkCache.STAGE_DOWNLOAD, why)
        // Publish before throwing: [install] catches this too, but a BATCH
        // catches it per-app and moves on, which would leave the row frozen on
        // this app's last progress frame while the next app downloads.
        UpdateProgress.update(UpdateProgress.State.Failed(why, appId = app.id, pkg = app.pkg))
        error(why)
    }

    /**
     * A cached artifact for [app] that is byte-for-byte the release asset
     * published NOW, or null. Content-addressed: the sha256 sidecar names the
     * bytes, so any `fleet-<id>-*` file that hashes to it is the download,
     * whichever source fetched it. No sidecar → no hit (a length cannot tell
     * this build from the last one under the same filename).
     *
     * ponytail: hashes each candidate in full (seconds for 400 MB) — still far
     * cheaper than the download it replaces; a record whose sha already
     * disagrees is skipped without reading the file.
     */
    fun cachedRelease(ctx: Context, app: App): VerifiedApk? {
        if (app.releaseUrl.isBlank()) return null
        val sha = releaseSha256(app) ?: return null
        return ApkCache.entries(ctx)
            .filter { !it.partial && it.file.name.startsWith("fleet-${app.id}-") }
            .filter { it.record == null || it.record.sha256.equals(sha, ignoreCase = true) }
            .firstNotNullOfOrNull { VerifiedApk.byDigest(it.file, sha) }
    }

    /** [GhcrSource] needs the manifest layer; the resolution logic (ABI tag
     *  first, then the universal one) stays here with the rest of the fleet
     *  model rather than being duplicated into the source. */
    internal fun remoteLayerFor(app: App, client: GhcrClient, token: String) =
        remoteLayer(app, client, token)

    /** Hand a verified APK to the installer: the first channel that accepts it
     *  wins. Shell first (installs with NO dialog at all), PackageInstaller
     *  second (prompts, but always available). Cheap — the work was the
     *  download.
     *
     *  Returns the name of the channel that ACTUALLY performed the install —
     *  callers report it, and reporting the attempted channel instead is how
     *  a completely dead `pm install` path spent a day looking like a working
     *  one. */
    /**
     * Whether a candidate that would move [app] BACKWARDS may install anyway.
     *
     * Called synchronously from [commit], and ONLY when [VersionOrder.isDowngrade]
     * has already said OLDER — never for SAME, NEWER, or UNKNOWN (see
     * [VersionOrder]'s own contract on why UNKNOWN must never be treated as a
     * downgrade). Both codes are real by the time this is asked: a call only
     * happens once each was actually read.
     *
     * Runs on whatever thread [commit] runs on — every caller today is a
     * caller-owned background thread, never the main thread. An
     * implementation that wants to ask a human must post to the main thread
     * and BLOCK this one until the answer lands; this interface says nothing
     * about how, on purpose. THAT is exactly the part this module must never
     * learn: [Fleet] is a headless engine in an ab_* shared lib, and ab_* may
     * not import from an app or gain a View. The app that hosts Constellation
     * supplies the how; this only supplies the question.
     */
    fun interface DowngradePolicy {
        /** Return true to proceed with the downgrade anyway. */
        fun allowDowngrade(app: App, candidateCode: Long, installedCode: Long): Boolean
    }

    /**
     * The behaviour every caller of [commit] had before this seam existed:
     * refuse, unconditionally. An app that never assigns this var keeps that
     * exact behaviour with no code change and no risk — #17's guard is not
     * weakened by this var merely existing. Only a host that explicitly opts
     * in, by assigning a UI-backed policy from its own app module, can ever
     * turn a downgrade into a confirmed question instead of a hard refusal.
     */
    @Volatile
    var downgradePolicy: DowngradePolicy = DowngradePolicy { _, _, _ -> false }

    fun commit(ctx: Context, app: App, apk: VerifiedApk): String {
        // ASK THE CANDIDATE WHAT IT IS, BEFORE COMMITTING IT.
        //
        // "Verified" answers "are these the bytes the server meant to send" —
        // it says nothing about WHICH BUILD they are. Both sources can hand
        // back a perfectly verified stale artifact: ReleaseSource verifies a
        // declared length, and a same-length older build passes; GhcrSource
        // verifies the digest of whatever the `latest` tag currently points
        // at, which is only as fresh as the last successful ORAS push.
        //
        // Without this check the first thing to notice was PackageInstaller,
        // failing with "INSTALL_FAILED_VERSION_DOWNGRADE: Update version code
        // 190 is older than current 3355358" — a hard, repeating error for
        // what is really a no-op, and one that told the user their app was
        // broken while the live release asset was in fact newer than what they
        // had installed. A downgrade is not an install failure. It is nothing
        // to do, and it should say so and drop the stale file so the next pass
        // re-fetches instead of re-offering the same bytes forever.
        val identity = ApkIntegrity.identify(ctx, apk.file)
        if (identity == null) {
            Log.w(TAG, "cannot read the manifest of the candidate for ${app.id} " +
                       "(${apk.file.name}) — installing anyway, PackageInstaller decides")
        } else {
            val installedCode = installedInfo(ctx, app)?.versionCode
            Log.i(TAG, "candidate ${app.kind} ${app.id}: $identity " +
                       "(installed: ${installedCode ?: "none"}, ${apk.evidence})")
            if (VersionOrder.isDowngrade(identity.versionCode, installedCode)) {
                // installedCode cannot be null here: isDowngrade only returns
                // true for Order.OLDER, and VersionOrder.compare only reaches
                // OLDER when both codes are non-null — see its own contract.
                val allowed = downgradePolicy.allowDowngrade(app, identity.versionCode, installedCode!!)
                if (!allowed) {
                    ApkCache.drop(apk.file)   // stale: never re-offer these exact bytes
                    error("stale candidate for ${app.id}: versionCode ${identity.versionCode} is " +
                          "OLDER than the installed $installedCode — this is a downgrade, not an " +
                          "update. Discarded the cached artifact; the source served a build older " +
                          "than the device has. Nothing installed")
                }
                Log.w(TAG, "downgrade of ${app.id} CONFIRMED by an explicit override: candidate " +
                           "${identity.versionCode} is older than installed $installedCode — " +
                           "proceeding because the downgrade policy allowed it")
            }
        }
        // NAME WHAT DECLINED, AND WHY. "no install channel accepted <pkg>" was
        // the whole message, which is a statement of the outcome and not of the
        // cause: it read identically whether the phone had never been paired,
        // had merely not reconnected since the last app start, or had run `pm
        // install` and been refused. Each channel now hands back a sentence.
        val declined = mutableListOf<String>()
        for (channel in channels(ctx)) {
            val why = channel.install(ctx, app, apk)
            if (why == null) {
                Log.i(TAG, "install committed via ${channel.name}: ${app.label} (${app.pkg})")
                return channel.name
            }
            Log.w(TAG, "install channel '${channel.name}' declined ${app.pkg}: $why")
            declined += "${channel.name} → $why"
        }
        ApkCache.note(ctx, app.pkg, ApkCache.STAGE_INSTALL, "no install channel accepted it: " +
            declined.joinToString(" | "))
        error("no install channel accepted ${app.pkg}. " +
            declined.joinToString(" | ") +
            if (AutoUpdatePrefs.requireSilent(ctx))
                ". This device has opted in to \"never prompt\" (AutoUpdatePrefs.requireSilent), " +
                "so the universal PackageInstaller fallback was not tried. Turn that setting off " +
                "to install with a confirmation dialog instead"
            else "")
    }

    /**
     * A candidate APK's real pkg/versionCode, read straight out of its own
     * manifest WITHOUT installing it — the exact fact [commit] reads before
     * every downgrade decision, now public so a caller can show it BEFORE
     * committing to anything. [ApkIntegrity] itself stays internal (see its
     * own header: it is the one place this module hashes or sanity-checks an
     * APK); this is the narrow public door onto one of its answers.
     */
    data class CandidateIdentity(val pkg: String, val versionCode: Long)

    /** [CandidateIdentity] for an already-downloaded, already-verified [file]
     *  (see [download]), or null when its manifest will not parse. */
    fun candidateIdentity(ctx: Context, file: File): CandidateIdentity? =
        ApkIntegrity.identify(ctx, file)?.let { CandidateIdentity(it.pkg, it.versionCode) }

    /**
     * THE LADDER, AND WHY IT MUST NEVER BE ONE RUNG LONG.
     *
     * [ShellInstall] first: it runs `pm install` as uid 2000, which holds
     * INSTALL_PACKAGES, so it shows nothing at all and gives Play Protect no
     * prompt to stack. That is the ONLY dialog-free path and it is the one we
     * want whenever it can be established.
     *
     * [SessionInstall] second, ALWAYS: a PackageInstaller commit by an
     * ordinary app costs one confirmation (platform behaviour — no flag, no
     * permission short of system INSTALL_PACKAGES, and USER_ACTION_NOT_REQUIRED
     * only applies to packages we are already the installer of record for). One
     * tap is worse than no tap. It is enormously better than no updates.
     *
     * This used to be a compile-time `ALLOW_PROMPTING_FALLBACK = false`, which
     * dropped SessionInstall for EVERY device in the fleet in order to suppress
     * a dialog on ONE developer's phone. One APK ships to thousands of devices,
     * so a compile-time flag is necessarily a fleet-wide flag: the overwhelming
     * majority have never paired Wireless debugging and have no Shizuku, so
     * they were left with no install path whatsoever — including no path to
     * receive the fix for it. Silence is an OPTIMISATION for capable devices,
     * never a PRECONDITION for updating.
     *
     * The suppression survives, but as what it always was: a per-device
     * preference ([AutoUpdatePrefs.requireSilent], default false) that the one
     * device with a live privileged channel can opt in to. It can only ever
     * subtract the fallback from the device whose owner asked for it, and it
     * is reversible without a release.
     *
     * Nothing about verification changes: every install still commits a
     * [VerifiedApk], and ApkIntegrity/VerifiedApk are untouched.
     */
    private fun channels(ctx: Context): List<InstallChannel> =
        if (AutoUpdatePrefs.requireSilent(ctx)) listOf(ShellInstall)
        else listOf(ShellInstall, SessionInstall)


    /** Which apps installAll acts on. UPDATES = only apps ALREADY installed
     *  that have a newer image ("Update all" — never touches apps the user
     *  hasn't installed). MISSING = only not-yet-installed apps ("Install
     *  all"). ALL = both.
     *
     *  AUTO = what the unattended background pass wants, and the reason libs
     *  used to be invisible to it. It is UPDATES for `kind == "app"` and ALL
     *  for `kind == "lib"`. Both workers used to pass UPDATES, which drops
     *  every [State.Missing] — and a lib is Missing on any device that never
     *  installed it, which is most of them, so all 36 lib entries were skipped
     *  on every pass forever.
     *
     *  Treating a missing lib as installable is not a loosening of the
     *  "never auto-install what the user didn't choose" rule; it is that rule
     *  applied correctly. A lib APK (ab_cloud-libs-shared/lib-apks, one
     *  product flavor per module, applicationId com.diegonmarcos.cloudlib.*)
     *  has no launcher entry and no UI: it is a dependency payload that apps
     *  reach through AIDL behind a signature-level permission. The user never
     *  chose it and never can — the app that needs it did. */
    enum class Mode { UPDATES, MISSING, ALL, AUTO }

    /**
     * What one pass actually did, and — when it did nothing — WHY.
     *
     * installAll used to return a bare Int, so "everything is up to date",
     * "the PackageInstaller session budget is exhausted", "there is no
     * privileged channel so I refuse to spam you with 40 dialogs" and "another
     * batch holds the lease" were all the same observable event: `acted on 0
     * app(s)`. That is indistinguishable from the feature being broken, which
     * is exactly how it was read. [reason] is a one-line, always-logged
     * explanation; [silent] and [channel] say whether the pass could act
     * without user interaction at all.
     */
    data class Pass(
        val acted: Int,
        /** Entries that needed work before any cap was applied. */
        val considered: Int,
        /** Entries this pass was actually allowed to attempt. */
        val batched: Int,
        val silent: Boolean,
        val channel: String?,
        val reason: String,
    )

    /**
     * Install/update fleet apps, filtered by [mode] (see [Mode]). Sequential
     * (PackageInstaller sessions mustn't collide); per-app failures don't abort
     * the rest. Returns how many were acted on.
     */
    /** One batch at a time, process-wide. FIVE callers reach this — the
     *  Update-All action, two Constellation buttons, and TWO periodic
     *  auto-update workers (UpdateWorker and ConstellationWorker) — and none
     *  of them knew about the others. Two overlapping batches were measured on
     *  2026-08-31: one thread installing cloud-ide → cloud-wallet at ~35s an
     *  app while a second re-downloaded mail, news and wallet underneath it.
     *
     *  Duplicated downloads were the cheap half of that. UpdateProgress is a
     *  single global, so the second batch's beginBatch() relabelled the
     *  overlay mid-install and its endBatch() CLEARED it while the first was
     *  still running — the update list emptying with nothing visibly
     *  installed, which is exactly what a silent failure looks like from the
     *  outside even though every install was succeeding.
     *
     *  A losing caller returns 0 rather than queuing: every trigger asks for
     *  the same thing ("bring the fleet up to date"), so the batch already in
     *  flight IS that request being served. Auto-update and Update-All stop
     *  being two functions here and become one machine with two triggers. */
    /**
     * Whether an install can complete with NO user interaction at all.
     *
     * True only when a shell channel (Shizuku / embedded adb) is live: that
     * runs `pm install` as uid 2000, which holds INSTALL_PACKAGES, so nothing
     * is shown and no PackageInstaller session is opened.
     *
     * USER_ACTION_NOT_REQUIRED on the session path is NOT the same thing and is
     * why "silent" can look broken: Android honours it only for a package this
     * app is already the installer of record for, and silently prompts for
     * every other one. An app the user installed by hand therefore always costs
     * one confirmation before it can ever update quietly.
     *
     * This is also what decides whether the unattended pass needs a cap — see
     * [installAll]'s callers.
     */
    fun silentCapable(ctx: Context): Boolean = activeShellChannel(ctx) != null

    /** Name of the live shell channel, for status lines. */
    fun silentChannelName(ctx: Context): String? = activeShellChannel(ctx)?.name()

    /**
     * The batch lease: WHO holds the single-flight slot, and SINCE WHEN.
     *
     * This was an AtomicBoolean with neither an owner nor a bound. A
     * foreground "Update all" that finished its downloads and then sat on
     * unanswered PackageInstaller prompts kept the flag set for as long as the
     * user ignored the notifications, and every auto pass in between took the
     * `return 0` at the top and logged nothing a human would act on. The
     * feature was starvable by doing nothing, indefinitely.
     *
     * The lease fixes both halves: it names the holder, so the skip line says
     * who is holding it, and it expires, so a stale holder is taken over
     * instead of winning forever. Identity CAS on the Lease instance means the
     * taken-over owner's release is a harmless no-op rather than a slot it
     * hands back while someone else is using it.
     */
    private class Lease(val owner: String, val startedAt: Long)

    private val lease = java.util.concurrent.atomic.AtomicReference<Lease?>(null)

    /** A real batch is N downloads plus N sequential installs. 30 minutes is
     *  far past the slowest honest one on a bad network and far short of
     *  "the user left the prompts unanswered until tomorrow". */
    private const val BATCH_STALE_MS = 30L * 60L * 1000L

    private fun acquireLease(owner: String): Lease? {
        while (true) {
            val mine = Lease(owner, android.os.SystemClock.elapsedRealtime())
            val held = lease.get()
            if (held == null) {
                if (lease.compareAndSet(null, mine)) return mine
                continue
            }
            val ageMs = mine.startedAt - held.startedAt
            if (ageMs < BATCH_STALE_MS) return null
            if (lease.compareAndSet(held, mine)) {
                Log.w(TAG, "batch lease held by '${held.owner}' for ${ageMs / 1000}s — past " +
                           "the ${BATCH_STALE_MS / 1000}s stale bound, so '$owner' is taking " +
                           "it over rather than being starved by it")
                return mine
            }
        }
    }

    private fun releaseLease(mine: Lease) { lease.compareAndSet(mine, null) }

    fun installAll(
        ctx: Context,
        apps: List<App>,
        mode: Mode = Mode.ALL,
        limit: Int = Int.MAX_VALUE,
    ): Int = installAllPass(ctx, apps, mode, limit).acted

    /**
     * [installAll] with the pass kept instead of reduced to a count.
     *
     * `acted == 0` is ambiguous and the UI was reading it as good news: it
     * means either "nothing needed doing" or "everything was blocked", and
     * the Constellation page printed "Everything up to date" for both. A
     * device with no session headroom therefore reported success while
     * installing nothing, for every app and every lib at once. [Pass.reason]
     * already says which of the two happened — this just stops throwing it
     * away on the way out.
     */
    fun installAllPass(
        ctx: Context,
        apps: List<App>,
        mode: Mode = Mode.ALL,
        limit: Int = Int.MAX_VALUE,
    ): Pass = runBatch(ctx, apps, mode, limit, owner = "installAll($mode)")

    /**
     * THE unattended entry point. Both periodic workers call this and nothing
     * else, so the pass they run is one machine with two triggers instead of
     * two callers that each re-derived the mode and the cap and disagreed
     * about them (whichever worker won the lease race used to decide whether
     * three apps or the whole fleet installed).
     *
     * Silent here means no visible progress: [UpdateProgress.quiet] is held
     * for the duration so nothing is drawn over whatever the user is doing.
     * Every event still goes to logcat under [TAG], ungated.
     */
    /**
     * #804 The host's persisted auto chain, when it has one. libs:appstore sets
     * it (StoreAuto: refresh → Download ALL → install libs-then-apps → clear,
     * resumable across process death) and both unattended workers keep calling
     * [autoPass], so whichever wakes first runs the ONE chain. A host without
     * the store keeps [runBatch]. libs:updater cannot name the store (appstore
     * depends on updater), hence a seam — the same shape as [downgradePolicy].
     */
    @Volatile
    var autoChain: ((Context, List<App>, String) -> Pass)? = null

    /**
     * #804 How many installs ONE unattended pass may start now: unbounded with a
     * privileged shell channel (no session, nothing shown), 0 when there is none
     * and the device opted out of prompting, else [BuildConfig.AU_MAX_PER_PASS]
     * held to the PackageInstaller session headroom — the same three rules
     * [installAllLocked] applies, for a caller that downloads everything first.
     */
    fun unattendedInstallBudget(ctx: Context): Int = when {
        ensureShellChannel(ctx) != null -> Int.MAX_VALUE
        AutoUpdatePrefs.requireSilent(ctx) -> 0
        else -> minOf(BuildConfig.AU_MAX_PER_PASS, UpdateInstaller(ctx).freeSessionSlots())
    }

    fun autoPass(ctx: Context, apps: List<App>, owner: String): Pass {
        // The cap exists for ONE reason: a SessionInstall leaves a
        // tap-to-confirm notification holding a PackageInstaller session until
        // the user answers it, and Android refuses new sessions past 50 per
        // UID. A shell install opens no session and shows nothing, so with a
        // privileged channel live there is nothing to cap.
        // #804 the host's chain applies its own budget per install, after
        // downloading everything — so no channel probe here when it has one.
        val chain = autoChain
        val limit = if (chain != null || silentCapable(ctx)) Int.MAX_VALUE else BuildConfig.AU_MAX_PER_PASS
        UpdateProgress.quiet = true
        // The same intent, PERSISTED, for the one consumer that cannot see the
        // field: PackageInstallerReceiver. Install results land tens of seconds
        // after commit(), off this stack entirely, and it was toasting EVERY
        // result — including plain success — so an unattended pass over the
        // fleet popped one toast per app. That is exactly the "progress
        // appearing" that auto-update:ON is supposed to mean the absence of.
        AutoUpdatePrefs.setUnattendedPass(ctx, true)
        PassLedgerStore.begin(ctx)   // #894 one summary per pass
        return try {
            chain?.invoke(ctx, apps, owner) ?: runBatch(ctx, apps, Mode.AUTO, limit, owner)
        } finally {
            UpdateProgress.quiet = false
            AutoUpdatePrefs.setUnattendedPass(ctx, false)
            PassLedgerStore.end(ctx)
        }
    }

    private fun runBatch(
        ctx: Context,
        apps: List<App>,
        mode: Mode,
        limit: Int,
        owner: String,
    ): Pass {
        val mine = acquireLease(owner)
        if (mine == null) {
            val holder = lease.get()?.owner ?: "?"
            Log.i(TAG, "$owner: batch '$holder' is already in flight — this trigger is " +
                       "already being served, skipping")
            return Pass(0, 0, 0, silentCapable(ctx), silentChannelName(ctx),
                "skipped: batch '$holder' already in flight")
        }
        return try {
            installAllLocked(ctx, apps, mode, limit)
        } finally {
            releaseLease(mine)
        }
    }

    private fun installAllLocked(
        ctx: Context,
        apps: List<App>,
        mode: Mode,
        limit: Int,
    ): Pass {
        // Decide the work-list FIRST (status checks, no overlay yet) so the
        // batch header can show a correct "N/total" — otherwise the overlay's
        // bar just resets 0→100 per app with no context (scrambled-progress bug).
        // ESTABLISH the channel here, once, before deciding the pass is
        // unattended-incapable. silentCapable() is a probe, so a paired phone
        // whose embedded adb client had not reconnected since the last app
        // start reported "no privileged channel" and BLOCKED the whole batch
        // below without ever trying to bring the channel up.
        val established = ensureShellChannel(ctx)
        val silent = established != null
        val channel = established?.name()
        // #785 the version each selected app is moving to, for the Store bar.
        val versions = HashMap<String, String>()
        val todo = apps.filter { app ->
            if (app.blocked) return@filter false
            // THE HOST IS NOT A BATCH ENTRY, AND BOTH CALLERS ALREADY SAY SO.
            //
            // UpdateWorker.doWork and ConstellationFragment.updateAll each
            // carry the same comment: the self-update goes LAST, because
            // installing our own APK replaces it and Android kills this
            // process — taking whatever is left of the batch with it. Neither
            // could honour that, because the fleet list CONTAINS this package
            // (constellation-fleet.json id=aa_cloud-superapp) and it is the
            // FIRST member of the `apps` group, so a batch that walks the list
            // in order reached it before anything else. The ordering was
            // written in both callers and made unenforceable by the data.
            //
            // That is #99/#274 ("Update all fails for every app and lib",
            // "auto-update ON and yet no app has updated"): the pass was not
            // failing, it was being killed at entry one.
            //
            // Self stays in the fleet for DISPLAY — the Constellation page
            // must still show this app's own row — and is excluded only from
            // the ACT. Display and identity are separate questions; keying the
            // batch on our own package name is the identity answer, and it is
            // the manifest's applicationId, not a second list to maintain.
            if (app.pkg == ctx.packageName) {
                Log.i(TAG, "$mode skips ${app.id} (${app.pkg}): this is the host — the " +
                           "self-update path owns it and runs after this batch")
                return@filter false
            }
            if (mode == Mode.AUTO) heldAtInstall(ctx, app)?.let { n ->
                Log.i(TAG, "AUTO stops at Install for ${app.id} (${app.pkg}): the last install did " +
                           "not finish (${n.message}) — the cached APK is kept and the Store row " +
                           "offers Install; not re-prompting from an unattended pass")
                return@filter false
            }
            val state = status(ctx, app)
            val take = when (state) {
                is State.UpdateAvailable -> mode != Mode.MISSING
                // AUTO installs a MISSING lib but never a missing app. See
                // [Mode.AUTO]: a lib has no launcher entry and is reached over
                // AIDL by whichever app needs it, so "the user never chose it"
                // is not a reason to skip it — it is the reason it exists.
                is State.Missing ->
                    mode == Mode.MISSING || mode == Mode.ALL ||
                        (mode == Mode.AUTO && app.kind == "lib")
                else -> false
            }
            // Always-on, ungated: a silent pass must still be fully readable
            // with `logcat -s Fleet`. Ids, packages and digests only — nothing
            // here is a secret, and no URL or token is ever printed.
            if (take) Log.i(TAG, "$mode selects ${app.kind} ${app.id} (${app.pkg}): " +
                                 describe(state))
            (state as? State.UpdateAvailable)?.versionName?.let { versions[app.id] = it }
            take
        }
        Log.i(TAG, "$mode scanned ${apps.size} fleet entries → ${todo.size} need work " +
                   "(unattended-capable: $silent" +
                   (if (channel != null) " via $channel" else ", no privileged channel") + ")")
        if (todo.isEmpty()) {
            return Pass(0, 0, 0, silent, channel,
                "nothing to do — all ${apps.size} fleet entries are current")
        }
        // NO PRIVILEGED CHANNEL IS A DEGRADE, NOT A STOP.
        //
        // The full chain is: privileged silent install → if none can be
        // established, download + verify here in the background anyway and let
        // [SessionInstall] commit a session. A session committed from the
        // background does NOT throw a dialog over whatever the user is doing:
        // PackageInstaller answers STATUS_PENDING_USER_ACTION, and
        // PackageInstallerReceiver posts a tap-to-install notification instead
        // (notifyConfirm — it goes straight to NotificationManager, so
        // [UpdateProgress.quiet], which only gates the in-app overlay, does not
        // suppress it). So every device still gets its updates; devices without
        // a privileged channel pay one tap for each.
        //
        // Only a device that has explicitly opted in to "never prompt" stops
        // here, and it stops BEFORE downloading: with no channel that can
        // accept the bytes, pulling tens of MB over mobile data every pass to
        // install none of it is pure waste, and it would report as N failed
        // installs rather than as the one thing actually wrong.
        if (!silent && AutoUpdatePrefs.requireSilent(ctx)) {
            val diagnosis = shellChannelDiagnosis(ctx)
            Log.w(TAG, "NO PRIVILEGED INSTALL CHANNEL: ${todo.size} update(s) ready and this " +
                       "device has opted in to \"never prompt\" (AutoUpdatePrefs.requireSilent), " +
                       "so the tap-to-install fallback is not used. Bring up the embedded adb " +
                       "channel (Wireless debugging → pair) or Shizuku and the next pass " +
                       "installs all of them silently; or turn that setting off to update with " +
                       "one tap each. Ladder said: $diagnosis")
            return Pass(0, todo.size, 0, silent, channel,
                "BLOCKED BY CHOICE: ${todo.size} update(s) ready but no privileged shell " +
                "channel came up, and this device opted out of the confirmation-dialog " +
                "fallback — nothing downloaded, nothing installed. $diagnosis")
        }
        // The honest chain, said out loud once per pass: no privileged channel,
        // so these will be downloaded here and finished by one tap each.
        if (!silent) {
            Log.i(TAG, "no privileged install channel (${shellChannelDiagnosis(ctx)}) — " +
                       "degrading to the universal PackageInstaller: ${todo.size} update(s) " +
                       "will be downloaded and verified now, then offered as tap-to-install " +
                       "notifications. Pair Wireless debugging or install Shizuku to make " +
                       "these fully unattended")
        }
        // Cap the batch. [limit] is Int.MAX_VALUE for user-initiated "Update
        // all"/"Install all" - those run in the foreground, the system dialog
        // appears, and each session resolves within seconds. A BACKGROUND pass
        // passes a real limit, and is additionally held to whatever session
        // headroom actually remains: its installs cannot show a dialog, so each
        // leaves a notification holding a PackageInstaller session until the
        // user answers it. Unbounded over a 40-entry fleet that is 40 sessions
        // from one pass, and Android refuses new ones past 50 with "Too many
        // active sessions for UID".
        val slots = UpdateInstaller(ctx).freeSessionSlots()
        // The budget applies to EVERY caller, with no exemption for "Update
        // all". That branch passed limit = Int.MAX_VALUE, took the first arm,
        // and opened one session per fleet entry — up to 58 against Android's
        // 50-per-UID cap. The overflow then failed inside phase 2's catch, so
        // `acted` stayed 0 and the page rendered "Everything up to date": a
        // total failure printed as success, for every app and every lib at
        // once. A tap that installs in waves is a far smaller cost than a
        // tap that silently installs nothing.
        val batch = todo.take(minOf(limit, slots))
        // THE SILENT ZERO. take(0) used to return an empty batch and the pass
        // reported "acted on 0 app(s)" — the same line it prints when there is
        // genuinely nothing to do. These are opposite situations: one is
        // healthy, the other is a stuck queue that only the user can unstick,
        // and they were indistinguishable from outside.
        if (batch.isEmpty()) {
            return Pass(0, todo.size, 0, silent, channel,
                "CANNOT ACT: ${todo.size} update(s) waiting but 0 installable right now — " +
                "no privileged shell channel, and PackageInstaller session headroom is " +
                "$slots. Every unanswered tap-to-install notification holds a session " +
                "against the 50-per-UID cap, so answer or dismiss the pending ones, or " +
                "bring up the embedded adb / Shizuku channel for truly unattended installs")
        }
        // Never a silent cap: the rest are picked up by the next pass, and a
        // log line alone reads as "3 needed it" instead of "3 of N could run
        // now" — this note rides along on every beginBatch label below so the
        // overlay says so too, not just logcat.
        val capNote = if (batch.size < todo.size) {
            Log.i(TAG, "installAll capped at ${batch.size} of ${todo.size} app(s) " +
                       "(limit=$limit, session headroom decides the rest); " +
                       "remainder deferred to the next pass")
            " (capped at ${batch.size} of ${todo.size})"
        } else ""
        UpdateProgress.beginDownload() // disarm any stale cancel before the batch

        // ── PHASE 1: download everything, install nothing ───────────────────
        // An install prompt blocks on the user. Interleaved, every tap was
        // followed by a wait for the NEXT app's blob, so a ten-app batch spent
        // most of its time with the user watching a spinner between dialogs.
        // Fetching first means phase 2 is pure prompts, back to back.
        //
        // It also makes the batch atomic in the way that matters: a network
        // that dies half way now fails BEFORE anything was installed, instead
        // of leaving the fleet half-updated.
        // Pair<App, VerifiedApk>, not Pair<App, File>: the batch downloads
        // everything first and installs afterwards, so the evidence has to
        // survive that gap. Carrying a File here would have been the one
        // remaining way to reach commit() with something unverified.
        val staged = ArrayList<Pair<App, VerifiedApk>>(batch.size)
        batch.forEachIndexed { i, app ->
            if (UpdateProgress.cancelRequested) {
                Log.i(TAG, "installAll cancelled by user during download")
                UpdateProgress.update(UpdateProgress.State.Cancelled)
                // Same endBatch the CancellationException path below already
                // does. Without it batchLabel outlives the cancelled batch and
                // every observer keeps drawing a phantom "app · 2/5" row for
                // work that stopped.
                UpdateProgress.endBatch()
                return Pass(0, todo.size, batch.size, silent, channel,
                    "cancelled by the user during download")
            }
            UpdateProgress.beginBatch("↓ ${app.label}$capNote", i + 1, batch.size)
            UpdateProgress.beginJob(UpdateProgress.Job(app.id, app.pkg, app.label,
                UpdateProgress.STAGE_DOWNLOADING, versions[app.id].orEmpty(), i + 1, batch.size,
                batch.getOrNull(i + 1)?.label))
            try {
                staged += app to download(ctx, app)
            } catch (c: java.util.concurrent.CancellationException) {
                Log.i(TAG, "download ${app.label} cancelled")
                UpdateProgress.update(UpdateProgress.State.Cancelled)
                UpdateProgress.endBatch()
                return Pass(0, todo.size, batch.size, silent, channel,
                    "cancelled by the user while downloading ${app.label}")
            } catch (t: Throwable) {
                // One dead image must not cost the other nine their update.
                Log.w(TAG, "installAll download ${app.label}: ${t.message}")
            }
        }
        if (staged.isEmpty()) {
            UpdateProgress.endBatch()
            UpdateProgress.update(UpdateProgress.State.Failed(
                "all ${batch.size} download(s) failed — nothing was installed"))
            return Pass(0, todo.size, batch.size, silent, channel,
                "no install attempted: all ${batch.size} download(s) failed — see the " +
                "per-entry 'installAll download' warnings above for the reason")
        }

        // ── PHASE 2: install, strictly one at a time ────────────────────────
        // Sequential is not a style choice: PackageInstaller sessions collide,
        // and each unanswered prompt holds a session against the 50-session cap.
        var acted = 0
        // Committed but NOT installed — the session came back needing a human
        // (STATUS_PENDING_USER_ACTION) or died after handover. Counted apart
        // from [acted] because conflating the two is what made "auto-update is
        // on and nothing happens" unreadable from the log for two issues running.
        var pending = 0
        val usedChannels = linkedSetOf<String>()
        staged.forEachIndexed { i, (app, apk) ->
            if (UpdateProgress.cancelRequested) {
                Log.i(TAG, "installAll cancelled by user after $acted install(s)")
                UpdateProgress.update(UpdateProgress.State.Cancelled)
                UpdateProgress.endBatch()
                return Pass(acted, todo.size, batch.size, silent, channel,
                    "cancelled by the user after $acted install(s)")
            }
            UpdateProgress.beginBatch("${app.label}$capNote", i + 1, staged.size)
            UpdateProgress.beginJob(UpdateProgress.Job(app.id, app.pkg, app.label,
                UpdateProgress.STAGE_INSTALLING, versions[app.id].orEmpty(), i + 1, staged.size,
                staged.getOrNull(i + 1)?.first?.label))
            try {
                // commit() blocks until this install settles: UpdateInstaller
                // runs every install through InstallGate, so the batch does not
                // arm/await itself any more. That moved the guarantee to the
                // one place EVERY caller passes through - including the
                // per-row install buttons, which never came through here.
                // NAME THE CHANNEL THAT DID IT, NOT THE ONE WE HOPED FOR.
                // This used to print `via $channel` whenever a privileged
                // channel merely EXISTED — so a `pm install` that failed on
                // every single app and fell through to PackageInstaller was
                // reported, thirty-two times, as a silent shell install. One
                // wrong word made a completely dead code path look healthy.
                // READ THE CANDIDATE BEFORE COMMITTING IT. A confirmed install
                // reaps the staged file (PackageInstallerReceiver deletes it by
                // path), so after commit there is nothing left to identify and
                // the only honest answer would be "unknown".
                val candidateCode = ApkIntegrity.identify(ctx, apk.file)?.versionCode
                val candidateSha = runCatching { ApkIntegrity.sha256(apk.file) }.getOrNull()
                // #975 ONCE PER ACTUAL CHANGE. These exact bytes already wait on a tap from an
                // earlier unattended pass: committing them again only re-posts the same
                // "tap to finish installing". A new build (another sha256) is offered again.
                if (mode == Mode.AUTO && candidateSha != null &&
                    PassLedgerStore.offeredBefore(ctx, app.pkg, candidateSha)) {
                    pending++
                    // Same entry as last pass, so the one summary keeps its signature and stays put.
                    PassLedgerStore.record(ctx, PassLedger.Outcome.NEEDS_TAP, app.pkg, (candidateCode ?: 0L).toString())
                    Log.i(TAG, "AUTO does not re-offer ${app.id} (${app.pkg}): build " +
                               "${candidateSha.take(12)} already waits on a tap")
                    return@forEachIndexed
                }
                val used = commit(ctx, app, apk)
                usedChannels += used
                // ASK THE PACKAGE MANAGER, DO NOT BELIEVE THE COMMIT.
                //
                // commit() returns on HANDOVER. InstallGate then waits for the
                // session to settle — but it opens the gate on
                // STATUS_PENDING_USER_ACTION too (deliberately: a background
                // pass cannot show the dialog and must not stall the batch).
                // So "the gate released" means "we may start the next one", NOT
                // "this one installed". Counting it as an install is what
                // produced "auto-update: installed 3 of 3 staged" on a pass
                // that installed zero.
                //
                // The device is the only witness that cannot be talked into a
                // false green, so re-read the installed versionCode and compare.
                val nowCode = installedInfo(ctx, app)?.versionCode
                // #789 the sha256 is the identity: a constant-versionCode fork
                // "reaches" the candidate's code before installing anything.
                // The versionCode rule stays only for unreadable (split) installs.
                val nowSha = installedId(ctx, app)?.let { ApkCache.installedSha256(ctx, it) }
                val landed = if (candidateSha != null && nowSha != null) candidateSha == nowSha
                             else VersionOrder.landed(candidateCode, nowCode)
                if (landed) {
                    acted++
                    PassLedgerStore.record(ctx, PassLedger.Outcome.INSTALLED, app.pkg, (candidateCode ?: 0L).toString())
                    PassLedgerStore.clearFailure(ctx, app.pkg)
                    PassLedgerStore.clearOffered(ctx, app.pkg)
                    // #774 STAGE 3, CLEAR. The session path is reaped by
                    // PackageInstallerReceiver on success; a shell install has
                    // no receiver, so its cached APK used to stay forever. Same
                    // proven-install rule either way — a no-op once reaped.
                    ApkCache.reapIfInstalled(ctx, apk.file)
                    Log.i(TAG, "installed ${app.kind} ${app.id} (${app.pkg}) " +
                               "[${i + 1}/${staged.size}] via $used")
                } else {
                    pending++
                    PassLedgerStore.record(ctx, PassLedger.Outcome.NEEDS_TAP, app.pkg, (candidateCode ?: 0L).toString())
                    if (mode == Mode.AUTO && candidateSha != null) PassLedgerStore.markOffered(ctx, app.pkg, candidateSha)
                    Log.w(TAG, "NOT installed: ${app.kind} ${app.id} (${app.pkg}) " +
                               "[${i + 1}/${staged.size}] committed via $used but the device " +
                               "still reports ${nowCode ?: "no build"} (candidate " +
                               "${candidateCode ?: "unreadable"}) — the session is waiting on " +
                               "a confirmation this pass cannot give it")
                }
            } catch (t: Throwable) {
                // The APK stays in the cache, so a retry reuses it - the whole
                // reason downloads are content-addressed.
                Log.w(TAG, "installAll commit ${app.label}: ${t.message}")
                PassLedgerStore.record(ctx, PassLedger.Outcome.FAILED, app.pkg, "0", t.message.orEmpty())
            }
        }
        // Clear the batch context; the async install commits still drive the
        // overlay to Done/Failed via PackageInstallerReceiver.
        UpdateProgress.endBatch()
        val deferred = todo.size - batch.size
        return Pass(acted, todo.size, batch.size, silent, channel,
            "installed $acted of ${staged.size} staged (${todo.size} needed work" +
            (if (pending > 0) ", $pending awaiting your confirmation — tap the " +
                "notification; nothing was installed for those" else "") +
            (if (deferred > 0) ", $deferred deferred to the next pass" else "") + ") " +
            // Observed, not intended — see the per-app log line above.
            (if (usedChannels.isEmpty()) "via nothing: no channel accepted any of them"
             else "via ${usedChannels.joinToString(" + ")}"))
    }

    /**
     * #774 The auto chain STOPS where a human said no. A cancelled install sheet
     * (or any install that did not finish) leaves a note and the cached APK;
     * the unattended pass leaves that app alone until the user takes over from
     * the Store row — Install from the cache, or Clear — instead of re-prompting
     * every pass. Downloads are not held: a failed download resumes from its
     * `.part` next pass, which is continuing, not restarting.
     */
    fun heldAtInstall(ctx: Context, app: App): ApkCache.Note? =
        ApkCache.noteOf(ctx, app.pkg)?.takeIf {
            // #1551 a session that merely did not finish (abandoned, timed out) is not a reason to stop: the Store queue retries
            // it from the cache with backoff and reports the final failure itself. Only a decline or a permanent fact holds.
            it.stage == ApkCache.STAGE_INSTALL &&
                !com.diegonmarcos.superapp.updater.install.InstallFailure.transient(it.message)
        }

    /** #1551 The install-stage note of [app] when it says the session did not finish: the queue's retry signal. */
    fun transientAtInstall(ctx: Context, app: App): ApkCache.Note? =
        ApkCache.noteOf(ctx, app.pkg)?.takeIf {
            it.stage == ApkCache.STAGE_INSTALL && com.diegonmarcos.superapp.updater.install.InstallFailure.transient(it.message)
        }

    /** One-line, log-safe rendering of a [State]. No URLs, no tokens. */
    private fun describe(s: State): String = when (s) {
        is State.UpdateAvailable ->
            "update ${s.versionName ?: "?"} → ${s.remoteDigest12} (${s.bytes} bytes)"
        is State.Missing -> "not installed (${s.bytes} bytes)"
        is State.Installed -> "current ${s.versionName} (code ${s.versionCode}, ${s.sha12})"
        is State.Blocked -> "blocked"
        is State.Error -> "check failed: ${s.message}"
    }

    /** Uninstall [pkg] via PackageInstaller (system confirm dialog). */
    fun uninstall(ctx: Context, pkg: String) {
        val intent = Intent(ctx, PackageInstallerReceiver::class.java)
            .setPackage(ctx.packageName)
            .putExtra(PackageInstallerReceiver.EXTRA_OP, PackageInstallerReceiver.OP_UNINSTALL)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        val pi = PendingIntent.getBroadcast(ctx, pkg.hashCode(), intent, flags)
        ctx.packageManager.packageInstaller.uninstall(pkg, pi.intentSender)
    }

    private data class Installed(val versionName: String, val versionCode: Long, val sha: String, val bytes: Long)

    /**
     * Every fact about the INSTALLED build a detail screen would want, beyond
     * the four [installedInfo] already carries for every other caller.
     * versionName/versionCode/sha/bytes still come from that one PackageInfo
     * read — this layers the extra fields on top rather than reading them a
     * second, separate way, so there remains exactly one place that decides
     * what "installed" means.
     */
    data class InstalledDetails(
        val pkg: String,
        val versionName: String,
        val versionCode: Long,
        val firstInstallAtMs: Long,
        val lastUpdateAtMs: Long,
        val bytes: Long,
        val sha256: String,
        /** Hex sha256 of the signing certificate, or null when it could not
         *  be read (no signature, or the read itself failed). */
        val signingCertSha256: String?,
        /** Package that installed this APK (Play Store, our own installer,
         *  `adb`…), or null when the platform will not say. */
        val installerPackage: String?,
        /** ABI folders actually present under the APK's `lib/` directory,
         *  e.g. ["arm64-v8a"]. Empty for a pure-JVM APK with no native code. */
        val abis: List<String>,
        val minSdk: Int,
        val targetSdk: Int,
    )

    /** [InstalledDetails] for [app], or null when it is not on the device. */
    fun installedDetails(ctx: Context, app: App): InstalledDetails? {
        val pkg = installedId(ctx, app) ?: return null
        return try {
            @Suppress("DEPRECATION")
            val pi = ctx.packageManager.getPackageInfo(pkg, signingFlags())
            val ai = pi.applicationInfo ?: return null
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
                       else @Suppress("DEPRECATION") pi.versionCode.toLong()
            val file = ai.sourceDir?.let { File(it) }
            InstalledDetails(
                pkg = pkg,
                versionName = pi.versionName ?: "—",
                versionCode = code,
                firstInstallAtMs = pi.firstInstallTime,
                lastUpdateAtMs = pi.lastUpdateTime,
                bytes = file?.length() ?: 0L,
                sha256 = file?.let { ApkIntegrity.sha256(it) } ?: "",
                signingCertSha256 = signingCertSha256(pi),
                installerPackage = installerPackage(ctx, pkg),
                abis = file?.let { ApkIntegrity.abis(it) } ?: emptyList(),
                // Public since API 24; this module's minSdk is 26, so no
                // version gate is needed to reach it safely.
                minSdk = ai.minSdkVersion,
                targetSdk = ai.targetSdkVersion,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    /** Hex sha256 of the current signing certificate, however this platform
     *  version makes it available. Null rather than thrown: a detail screen
     *  losing one field must not lose the rest of it. */
    private fun signingCertSha256(pi: android.content.pm.PackageInfo): String? = runCatching {
        val cert = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pi.signingInfo ?: return null
            (if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory)
                ?.firstOrNull()
        } else {
            @Suppress("DEPRECATION") pi.signatures?.firstOrNull()
        } ?: return null
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Package that installed [pkg], or null when the platform will not say. */
    private fun installerPackage(ctx: Context, pkg: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            ctx.packageManager.getInstallSourceInfo(pkg).installingPackageName
        else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(pkg)
    }.getOrNull()

    /** The package actually on the device for this app — pkg if present, else
     *  the stock upstream altId. null when neither is installed. Used by the UI
     *  for Open / Uninstall so they target the real installed package. */
    fun installedId(ctx: Context, app: App): String? =
        listOfNotNull(app.pkg, app.altId).firstOrNull { pkgInstalled(ctx, it) }

    private fun pkgInstalled(ctx: Context, pkg: String): Boolean = try {
        @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    private fun installedInfo(ctx: Context, app: App): Installed? {
        val pkg = installedId(ctx, app) ?: return null
        return try {
            @Suppress("DEPRECATION")
            val pi = ctx.packageManager.getPackageInfo(pkg, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
            val path = pi.applicationInfo?.sourceDir
            Installed(pi.versionName ?: "—", code,
                if (path != null) ApkIntegrity.sha256(File(path)) else "",
                if (path != null) File(path).length() else 0L)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
