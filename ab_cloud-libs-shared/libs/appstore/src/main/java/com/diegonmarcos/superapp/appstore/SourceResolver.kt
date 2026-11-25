package com.diegonmarcos.superapp.appstore

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import com.diegonmarcos.superapp.updater.AbiUpdateTag
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.VersionOrder
import com.diegonmarcos.superapp.updater.apk.VerifiedApk
import com.diegonmarcos.superapp.updater.cache.ApkCache
import com.diegonmarcos.superapp.updater.source.Download
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * #571 WE ARE THE STORE — the ORDERED install sources of an external app, read
 * from the `resolver` block of the one install-source map ([PhoneAppActions.SOURCES_FILE]).
 *
 * Three rungs, always in this order: the vendor's own direct-APK endpoint, the
 * official F-Droid repo ([FDroidIndex]: signed index, per-version sha256), and
 * the Google Play deep-link. Play publishes NO APK URL, so its rung carries
 * nothing but its kind and the row shows a 'needs Play' badge — a URL on a Play
 * rung is a fake, and [source] refuses to parse one. An app the map does not
 * declare still resolves, to the Play rung alone, so every package a vault
 * export or an import names gets a row and an honest answer.
 *
 * Fleet apps are never resolved here: they install through the constellation
 * manifest and the updater's own release path ([FleetInstall]).
 *
 * [check] is the version probe behind the row badges, one rung at a time:
 * a vendor feed (versionCode, or a version name compared numerically), the
 * F-Droid api's suggestedVersionCode, or nothing for Play. [fetch] is the
 * download: the same [Download] the fleet uses, verified by the vendor's
 * digest when it publishes one and by structure otherwise, and always checked
 * to be the package it was asked for before it goes near an installer.
 */
object SourceResolver {

    // The three kinds with BEHAVIOUR in this file. Every other declared kind is
    // a hand-off ([Source.Store]) and needs no constant here — #627's whole
    // point is that adding a store is an entry in the asset, not a line of
    // Kotlin. These three are named because the parser and the fetcher branch
    // on them, not because they are a list of the stores that exist.
    const val KIND_VENDOR = "vendor"
    const val KIND_FDROID = "fdroid"
    /** IzzyOnDroid: an F-Droid-format repo of its own (its own signed index-v1 and signing key), read by
     *  the same [FDroidIndex] code with `resolver.izzy` in place of `resolver.fdroid`. */
    const val KIND_IZZY = "izzy"
    const val KIND_PLAY = "play"
    /** Google Play's own servers, reached with an anonymous session ([PlayAnonFetcher]). */
    const val KIND_PLAY_ANON = "play-anon"

    /**
     * #627 ONE DECLARED KIND. Read from `resolver.kinds`, one per entry in
     * `resolver.order`, and THE source of the page's tab strip: one tab per
     * [Kind], in declared order, labelled [label]. There is no list of stores in
     * Kotlin to disagree with this one.
     *
     * [fetches] false = a HAND-OFF: that store publishes no APK we can fetch, so
     * the row deep-links into [installer]'s own page and says there is no
     * automated version compare rather than implying one.
     *
     * [catalogue] names the kind whose catalogue this kind is a CLIENT for.
     * Aurora Store serves Google Play's index, so `aurora` aliases `play`: its
     * tab is every app with a play rung, because that is genuinely the same app
     * set reached through a different client. A kind that aliases nothing has a
     * catalogue of its own and its tab is exactly the apps that declare it.
     */
    class Kind(
        val id: String,
        val label: String,
        val installer: String?,
        val catalogue: String?,
        val fetches: Boolean,
    ) {
        /** Which declared kind an app must carry to appear under this tab. */
        val member: String get() = catalogue ?: id
    }

    /** One rung of an app's ladder. */
    sealed class Source(val kind: String) {
        /** True when this rung cannot hand us bytes — the row deep-links into
         *  somebody else's store instead of downloading. [External.direct] is
         *  defined by this, so a new hand-off kind is excluded from "we can
         *  install it ourselves" by construction rather than by being listed. */
        open val handoff: Boolean get() = false
        class Vendor(
            /** URL template — `{version}` from the feed, `{asset}` from [abis]. Null when [apkKey] names it. */
            val apk: String?,
            val abis: Map<String, String>,
            val feed: String?,
            val versionName: String?,
            val versionCode: String?,
            val apkKey: String?,
            val sha256Key: String?,
            /** Sidecar URL template: bare hex or the sha256sum(1) form. */
            val sha256: String?,
            val versionRe: Regex?,
        ) : Source(KIND_VENDOR)
        object FDroid : Source(KIND_FDROID)
        /** Fetches like [FDroid], from IzzyOnDroid's signed index, verified against its own pinned key. */
        object Izzy : Source(KIND_IZZY)
        /** Fetches: the bytes come from Play's CDN, each split checked against
         *  the sha256 Play's delivery gives. NOT a hand-off, so an app with this
         *  rung is installable by this store and carries no 'needs Play' badge. */
        object PlayAnon : Source(KIND_PLAY_ANON)
        object Play : Source(KIND_PLAY) {
            override val handoff: Boolean get() = true
        }
        /** #627 any other declared kind with `fetches: false` — Galaxy Store,
         *  Aurora. It carries nothing but its identity and the store app it
         *  hands off to, because that is all such a source HAS. */
        class Store(kind: String, val installer: String) : Source(kind) {
            override val handoff: Boolean get() = true
        }
    }

    /**
     * Does the app refuse to run unless Google Play installed it? [PLAY] keeps
     * its play-anon rung — the bytes install fine — but the row and the plan say
     * it may refuse to run. [UNKNOWN] is never rounded to [NONE].
     */
    enum class Integrity(val id: String) {
        NONE("none"), PLAY("play"), UNKNOWN("unknown");
        companion object { fun of(id: String): Integrity? = values().firstOrNull { it.id == id } }
    }

    class External(
        val pkg: String, val label: String, val sources: List<Source>, val declared: Boolean,
        /** Declared reason no public source exists (`"unresolved": "<why>"`). Its
         *  ladder is empty: nothing to install from, and the row says why. */
        val unresolved: String? = null,
        /** An unresolved row's official download page (`"official_page"`, https only): a page a
         *  person opens, never a URL this store fetches from. */
        val officialPage: String? = null,
        /** Declared `integrity` of the row; [Integrity.UNKNOWN] for an undeclared package. */
        val integrity: Integrity = Integrity.UNKNOWN,
    ) {
        /** The rungs this store can download from itself. */
        val direct: List<Source> get() = sources.filter { !it.handoff }
        /** Open source: listed in the official F-Droid repo, or released by its publisher on GitHub.
         *  // ponytail: derived from the ladder, not declared; add a `licence` field when a GPL app
         *  // with neither rung needs to count. */
        val openSource: Boolean get() = sources.any {
            it is Source.FDroid || it is Source.Izzy || (it is Source.Vendor && listOfNotNull(it.feed, it.apk).any { u -> "github.com" in u })
        }
        /** No rung we can serve: the badge, and no Install of our own. */
        val needsPlay: Boolean get() = direct.isEmpty()
        val hasPlay: Boolean get() = sources.any { it is Source.Play }
        /** #627 does this app's ladder put it under the tab for [kind]? */
        fun inTab(kind: Kind): Boolean = sources.any { it.kind == kind.member }
        /** #627 the store this app must be installed FROM when no rung here can
         *  serve it. The first hand-off in the declared ladder — Play for most,
         *  Galaxy Store for an app only Samsung publishes. Was hardcoded to Play,
         *  which would have told the user "needs Play" about an app that needs
         *  Samsung and sent them to the wrong store. */
        val handoff: Source? get() = sources.firstOrNull { it.handoff }

        /** The shape [Fleet.commit] takes, so an external APK goes through the
         *  fleet's install channels — the ONE installer — and nothing else. */
        fun asFleetApp() = Fleet.App(
            id = pkg, label = label, pkg = pkg, altId = null, registry = "", namespace = "", image = "",
            tag = "", asset = "", assets = emptyMap(), releaseUrl = "", repoUrl = "", ghcrPage = "",
            blocked = false, kind = "app",
        )
    }

    class Config(
        val order: List<String>,
        /** #627 the declared kinds, in [order]. The tab strip IS this list. */
        val kinds: List<Kind>,
        val fdroid: JSONObject,
        /** The installer package whose store page is the Play rung — looked up in the #564 map, never named in code. */
        val playInstaller: String,
        val apps: Map<String, External>,
        /** `resolver.play_anon`; null when the map declares no play-anon kind. */
        val playAnon: PlayAnonFetcher.Config? = null,
        /** `resolver.izzy` (same shape as [fdroid]); null when the map declares no izzy kind. */
        val izzy: JSONObject? = null,
    ) {
        /** #627 one declared kind by id, for a label or an installer. */
        fun kind(id: String?): Kind? = kinds.firstOrNull { it.id == id }
    }

    fun config(sources: JSONObject): Config {
        val r = sources.getJSONObject("resolver")
        val order = r.getJSONArray("order").let { a -> (0 until a.length()).map { a.getString(it) } }
        // #627 CLOSED AT BOTH ENDS. A kind ranked in `order` with no `kinds`
        // entry would be a source with no tab and no label; a `kinds` entry
        // missing from `order` would be a tab with no rank. Either is a
        // declaration that only half exists, and half a declaration is how a
        // store ends up invisible on the page that is supposed to list it.
        val declared = r.getJSONObject("kinds")
        val named = declared.keys().asSequence().toSet()
        require(named == order.toSet()) {
            "resolver.order $order and resolver.kinds $named must name the SAME kinds — " +
                "a kind in one and not the other is a store with no tab, or a tab with no rank"
        }
        val kinds = order.map { id ->
            val k = declared.getJSONObject(id)
            val catalogue = k.optString("catalogue").ifEmpty { null }
            require(catalogue == null || catalogue in named) {
                "$id declares catalogue '$catalogue', which is not a declared kind"
            }
            Kind(
                id = id,
                label = k.getString("label"),
                installer = k.optString("installer").ifEmpty { null },
                catalogue = catalogue,
                fetches = k.getBoolean("fetches"),
            )
        }
        val byId = kinds.associateBy { it.id }
        val apps = r.getJSONObject("apps")
        val parsed = apps.keys().asSequence().sorted().associateWith { pkg -> external(pkg, apps.getJSONObject(pkg), order, byId) }
        // A play-anon kind with no play_anon block is a fetcher with no dispenser,
        // and a play_anon block with no kind is configuration nothing reads.
        require((KIND_PLAY_ANON in named) == r.has("play_anon")) {
            "resolver.kinds names '$KIND_PLAY_ANON' iff resolver.play_anon is declared"
        }
        val playAnon = r.optJSONObject("play_anon")?.let { PlayAnonFetcher.config(it) }
        // Same closed-at-both-ends rule for IzzyOnDroid: a kind with no repo, or a repo nothing ranks.
        require((KIND_IZZY in named) == r.has(KIND_IZZY)) {
            "resolver.kinds names '$KIND_IZZY' iff resolver.$KIND_IZZY is declared"
        }
        return Config(order, kinds, r.getJSONObject("fdroid"), r.getJSONObject("play").getString("installer"), parsed, playAnon,
            r.optJSONObject(KIND_IZZY))
    }

    /** The declared ladder, or the Play rung alone for a package the map does not know. */
    fun resolve(cfg: Config, pkg: String): External =
        cfg.apps[pkg] ?: External(pkg, pkg, listOf(Source.Play), declared = false)

    private fun external(pkg: String, o: JSONObject, order: List<String>, kinds: Map<String, Kind>): External {
        // Every declared row says whether the app needs Play's installer; a row
        // that does not say is half a declaration.
        val integrity = Integrity.of(o.optString("integrity"))
            ?: error("$pkg: integrity must be one of ${Integrity.values().map { it.id }}, got '${o.optString("integrity")}'")
        val unresolved = o.optString("unresolved").ifEmpty { null }
        if (unresolved != null) {
            // An app with no public source says so, and claims no rung.
            require((o.optJSONArray("sources")?.length() ?: 0) == 0) { "$pkg: unresolved carries no sources" }
            val page = o.optString("official_page").ifEmpty { null }
            require(page == null || page.startsWith("https://")) { "$pkg: official_page must be https" }
            return External(pkg, o.optString("label").ifEmpty { pkg }, emptyList(), declared = true, unresolved = unresolved,
                officialPage = page, integrity = integrity)
        }
        val arr = o.getJSONArray("sources")
        val list = (0 until arr.length()).map { i -> source(arr.getJSONObject(i), kinds) }
        // The ladder must be a subsequence of `order`: a Play rung ABOVE a
        // direct one would send the user to a store this store replaces.
        val ranks = list.map { order.indexOf(it.kind) }
        require(list.isNotEmpty() && ranks.all { it >= 0 } && ranks == ranks.sorted() && ranks.distinct().size == ranks.size) {
            "$pkg: sources ${list.map { it.kind }} are not a subsequence of $order"
        }
        return External(pkg, o.optString("label").ifEmpty { pkg }, list, declared = true, integrity = integrity)
    }

    private fun source(o: JSONObject, kinds: Map<String, Kind>): Source = when (val kind = o.getString("kind")) {
        KIND_FDROID -> Source.FDroid
        KIND_IZZY -> {
            require(o.length() == 1) { "an izzy source carries nothing but its kind: $o" }
            Source.Izzy
        }
        KIND_PLAY_ANON -> {
            // The URLs come from Play's delivery at fetch time; a rung that
            // names one is a guess about where Play keeps today's bytes.
            require(o.length() == 1) { "a play-anon source carries nothing but its kind: $o" }
            Source.PlayAnon
        }
        KIND_PLAY -> {
            // Google Play has no public APK URL. A Play rung that names one is
            // a lie about where the bytes come from, so it does not parse.
            require(o.length() == 1) { "a play source carries nothing but its kind: $o" }
            Source.Play
        }
        KIND_VENDOR -> {
            val abis = o.optJSONObject("abis")?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } }.orEmpty()
            val apk = o.optString("apk").ifEmpty { null }
            val apkKey = o.optString("apk_key").ifEmpty { null }
            require(apk != null || apkKey != null) { "vendor source names no apk: $o" }
            require(apk == null || apk.startsWith("https://")) { "vendor apk is not https: $apk" }
            require(apkKey == null || o.has("feed")) { "apk_key needs a feed: $o" }
            Source.Vendor(
                apk = apk, abis = abis,
                feed = o.optString("feed").ifEmpty { null },
                versionName = o.optString("version_name").ifEmpty { null },
                versionCode = o.optString("version_code").ifEmpty { null },
                apkKey = apkKey,
                sha256Key = o.optString("sha256_key").ifEmpty { null },
                sha256 = o.optString("sha256").ifEmpty { null },
                versionRe = o.optString("version_re").ifEmpty { null }?.let { Regex(it) },
            )
        }
        // #627 every other DECLARED kind is a hand-off, and it parses without a
        // branch of its own. An UNdeclared kind still fails — the asset is the
        // authority on what exists, not this when.
        else -> {
            val k = kinds[kind] ?: error("unknown source kind $kind")
            require(!k.fetches) {
                "$kind declares fetches:true but this file has no fetcher for it — a kind that " +
                    "claims to serve bytes must have code that can get them"
            }
            val installer = k.installer
                ?: error("$kind is a hand-off and must declare the installer package it hands off to")
            require(o.length() == 1) { "a $kind source carries nothing but its kind: $o" }
            Source.Store(kind, installer)
        }
    }

    // ── version probe ────────────────────────────────────────────────────

    /** #627 STORE_MANAGES is PLAY_MANAGES for every other hand-off store: that
     *  store owns the update and we have no feed to compare against, said as a
     *  fact rather than rounded to "up to date". */
    enum class Note { NONE, NO_FEED, PLAY_MANAGES, STORE_MANAGES, UNDECLARED, NOT_COMPARABLE }

    /** What a row shows. [via] is the rung that answered (a [Source.kind], or "fleet"). */
    sealed class Check(val via: String?) {
        class NotInstalled(via: String?, val needsPlay: Boolean) : Check(via)
        class Installed(val versionName: String, val versionCode: Long, via: String?, val note: Note) : Check(via)
        class UpdateAvailable(val versionName: String, val remote: String, via: String) : Check(via)
        class Unknown(val versionName: String?, val reason: String) : Check(null)
    }

    const val VIA_FLEET = "fleet"

    /** The fleet's own verdict in the row's vocabulary, so both kinds paint alike. */
    fun ofFleet(s: Fleet.State): Check = when (s) {
        is Fleet.State.Installed -> Check.Installed(s.versionName, s.versionCode, VIA_FLEET, Note.NONE)
        is Fleet.State.UpdateAvailable -> Check.UpdateAvailable(s.versionName ?: "", s.remoteDigest12, VIA_FLEET)
        is Fleet.State.Missing -> Check.NotInstalled(VIA_FLEET, needsPlay = false)
        is Fleet.State.Blocked -> Check.Unknown(null, "unpublished")
        is Fleet.State.Error -> Check.Unknown(null, s.message)
    }

    /** versionName/versionCode of [pkg] as installed, or null. */
    fun installed(ctx: Context, pkg: String): Pair<String, Long>? = runCatching {
        val pi = ctx.packageManager.getPackageInfo(pkg, 0)
        (pi.versionName ?: "") to PackageInfoCompat.getLongVersionCode(pi)
    }.getOrNull()

    /** What the remote rung says is current. Null = this rung has no feed. */
    class Remote(val name: String?, val code: Long?)

    /** Local facts only — what a row shows before Check all has run. */
    fun local(ctx: Context, app: External): Check {
        val have = installed(ctx, app.pkg) ?: return Check.NotInstalled(app.sources.firstOrNull()?.kind, app.needsPlay)
        return Check.Installed(have.first, have.second, null, if (app.declared) Note.NONE else Note.UNDECLARED)
    }

    /** Blocking probe: the first rung that has an opinion decides. */
    fun check(ctx: Context, cfg: Config, app: External): Check {
        val have = installed(ctx, app.pkg) ?: return Check.NotInstalled(app.sources.firstOrNull()?.kind, app.needsPlay)
        val (name, code) = have
        if (!app.declared) return Check.Installed(name, code, null, Note.UNDECLARED)
        for (src in app.sources) {
            if (src is Source.Play) return Check.Installed(name, code, src.kind, Note.PLAY_MANAGES)
            if (src is Source.Store) return Check.Installed(name, code, src.kind, Note.STORE_MANAGES)
            val probed = try { remoteVersion(ctx, cfg, app, src) } catch (t: Throwable) {
                return Check.Unknown(name, "${src.kind}: ${t.message ?: t.javaClass.simpleName}")
            }
            val remote = probed ?: return Check.Installed(name, code, src.kind, Note.NO_FEED)
            return when (VersionOrder.compare(remote.code, code)) {
                VersionOrder.Order.NEWER -> Check.UpdateAvailable(name, remote.name ?: remote.code.toString(), src.kind)
                VersionOrder.Order.SAME, VersionOrder.Order.OLDER -> Check.Installed(name, code, src.kind, Note.NONE)
                VersionOrder.Order.UNKNOWN -> {
                    val c = compareNames(remote.name, name)
                    when {
                        c == null -> Check.Installed(name, code, src.kind, Note.NOT_COMPARABLE)
                        c > 0 -> Check.UpdateAvailable(name, remote.name.orEmpty(), src.kind)
                        else -> Check.Installed(name, code, src.kind, Note.NONE)
                    }
                }
            }
        }
        return Check.Installed(name, code, null, Note.NO_FEED)
    }

    /**
     * Dotted-numeric compare of two version NAMES: the leading `\d+(\.\d+)*`
     * of each, component by component, missing components read as 0. Null
     * when either has no numeric prefix — "not comparable" is an answer, and
     * it must never be rounded to "up to date".
     */
    fun compareNames(remote: String?, installed: String?): Int? {
        fun parts(s: String?): List<Long>? =
            Regex("^(\\d+(?:\\.\\d+)*)").find(s?.trim().orEmpty())?.groupValues?.get(1)?.split('.')?.map { it.toLong() }
        val a = parts(remote) ?: return null
        val b = parts(installed) ?: return null
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = (a.getOrNull(i) ?: 0L).compareTo(b.getOrNull(i) ?: 0L)
            if (d != 0) return d
        }
        return 0
    }

    private fun playAnon(cfg: Config) = cfg.playAnon ?: error("resolver.play_anon is not declared")
    fun izzy(cfg: Config) = cfg.izzy ?: error("resolver.$KIND_IZZY is not declared")

    private fun remoteVersion(ctx: Context, cfg: Config, app: External, src: Source): Remote? = when (src) {
        is Source.Vendor -> src.feed?.let { url ->
            val feed = getJson(url) ?: error("feed $url answered 404")
            val name = (path(feed, src.versionName) as? String)?.let { normalise(src, it) }
            val code = path(feed, src.versionCode)?.toString()?.toLongOrNull()
            Remote(name, code)
        }
        is Source.FDroid -> FDroidIndex.suggested(cfg.fdroid, app.pkg)?.let { Remote(it.first, it.second) }
            ?: error("${app.pkg} is not in the F-Droid api")
        is Source.Izzy -> FDroidIndex.suggested(izzy(cfg), app.pkg)?.let { Remote(it.first, it.second) }
            ?: error("${app.pkg} is not in the IzzyOnDroid api")
        is Source.PlayAnon -> PlayAnonFetcher.details(ctx, playAnon(cfg), app.pkg).let { Remote(it.versionName, it.versionCode) }
        is Source.Play -> null
        // No public version endpoint exists for these — Galaxy Store's API is
        // device-authenticated and app-internal, and Aurora reads Play's index
        // through a client we are not. Null is "this rung has no feed", which
        // the caller turns into a visible note.
        is Source.Store -> null
    }

    private fun normalise(src: Source.Vendor, raw: String): String =
        src.versionRe?.find(raw)?.groupValues?.getOrNull(1) ?: raw

    /** `a.b.c` walked into nested JSON objects. Null for a null/blank path. */
    fun path(o: JSONObject, p: String?): Any? {
        if (p.isNullOrBlank()) return null
        var cur: Any? = o
        for (k in p.split('.')) cur = (cur as? JSONObject)?.opt(k) ?: return null
        return cur
    }

    // ── download ─────────────────────────────────────────────────────────

    /** Blocking. The verified, identity-checked APK from [src], or a throw naming why. */
    fun fetch(ctx: Context, cfg: Config, app: External, src: Source): VerifiedApk = when (src) {
        is Source.Vendor -> fetchVendor(ctx, app, src)
        is Source.FDroid -> identity(ctx, app.pkg, FDroidIndex.fetch(ctx, cfg.fdroid, app.pkg))
        is Source.Izzy -> identity(ctx, app.pkg, FDroidIndex.fetch(ctx, izzy(cfg), app.pkg))
        is Source.PlayAnon -> identity(ctx, app.pkg, PlayAnonFetcher.fetch(ctx, playAnon(cfg), app.pkg))
        is Source.Play -> error("${app.label} publishes no APK outside Google Play")
        is Source.Store -> error("${app.label} publishes no APK outside ${src.kind} — that store " +
            "hands out no URL we can fetch, so the row deep-links into it instead")
    // #625 the external half of the ONE record point ([Fleet.download] is the
    // fleet half): the sha256 + identity that retention is gated on, written the
    // moment the bytes verified, plus the bounded cache's eviction pass. An
    // external APK with no record would simply never be reaped, which is the
    // safe direction but is not the declared policy.
    }.also { ApkCache.keep(ctx, it.file) }

    private fun fetchVendor(ctx: Context, app: External, src: Source.Vendor): VerifiedApk {
        UpdateProgress.update(UpdateProgress.State.CheckingManifest)
        val feed = src.feed?.let { getJson(it) ?: error("feed $it answered 404") }
        val version = feed?.let { path(it, src.versionName) as? String }?.let { normalise(src, it) }
        val asset = if (src.abis.isEmpty()) "" else AbiUpdateTag.currentFrom(src.abis, src.abis.values.first())
        fun fill(t: String) = t.replace("{version}", version.orEmpty()).replace("{asset}", asset)
        val url = src.apkKey?.let { k -> feed?.let { path(it, k) as? String } }
            ?: src.apk?.let { fill(it) }
            ?: error("${app.pkg}: the feed names no APK")
        require(url.startsWith("https://")) { "refusing a non-https APK: $url" }
        val digest = src.sha256Key?.let { k -> feed?.let { path(it, k) as? String } }
            ?: src.sha256?.let { sidecar(fill(it)) }
        val target = ApkCache.file(ctx, "external-${app.pkg}.apk")
        UpdateProgress.update(UpdateProgress.State.Downloading(0, 0L, -1L))
        Download.toFile(url = url, target = target, shouldCancel = { UpdateProgress.cancelRequested }) { written, total ->
            val pct = if (total > 0) ((written * 100) / total).toInt().coerceIn(0, 100) else 0
            UpdateProgress.update(UpdateProgress.State.Downloading(pct, written, total))
        }
        // A vendor that publishes a digest gets the fleet's strongest check; one
        // that does not gets the structural floor and an honest evidence line.
        val verified = (if (digest != null) VerifiedApk.byDigest(target, digest) else VerifiedApk.structural(target))
            ?: run {
                ApkCache.drop(target)
                error("${app.label}: the vendor APK failed verification" + (digest?.let { " against $it" } ?: ""))
            }
        return identity(ctx, app.pkg, verified)
    }

    /** The bytes must BE the package asked for: a vendor URL that serves
     *  something else never reaches an installer. */
    private fun identity(ctx: Context, pkg: String, apk: VerifiedApk): VerifiedApk {
        val id = Fleet.candidateIdentity(ctx, apk.file) ?: return apk
        if (id.pkg != pkg) {
            ApkCache.drop(apk.file)
            error("downloaded ${id.pkg}, wanted $pkg — discarded")
        }
        return apk
    }

    private fun sidecar(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true; c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
        val body = if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        c.disconnect()
        body?.trim()?.substringBefore(' ')?.trim()?.lowercase()
            ?.takeIf { s -> s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' } }
    }.getOrNull()

    /**
     * GET [url] as a JSON body. Null on 404 (the thing does not exist); throws
     * on anything else.
     *
     * Split out of [getJson] for #642's feed reader, which reads endpoints whose
     * top level is an ARRAY — and a second connection helper beside this one is
     * how two fetch paths end up with two sets of timeouts and two redirect
     * policies. ONE request, and the caller picks the parser.
     */
    fun getBody(url: String, headers: Map<String, String> = emptyMap()): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = true; c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("Accept", "application/json")
            // #841 caller headers (the fleet bearer). Values are never echoed
            // into a message below - only the url and the server's own words.
            for ((k, v) in headers) c.setRequestProperty(k, v)
            val code = c.responseCode
            if (code == 404) return null
            // #668 SAY WHAT THE SERVER SAID. "HTTP 403 from <url>" named the
            // request but not the reason, and the reason was sitting unread in
            // the error body: GitHub answers an exhausted quota with 403 and
            // "API rate limit exceeded for <ip>", which is a DIFFERENT problem
            // from a 403 meaning forbidden and leads somewhere different. Read
            // from errorStream, because inputStream throws on a non-2xx.
            //
            // Provider-agnostic on purpose: this lifts whatever `message` the
            // body carries rather than parsing rate-limit headers, so it needs
            // no knowledge of which API it is talking to and cannot rot when a
            // declared source changes.
            if (code !in 200..299) {
                val why = runCatching {
                    c.errorStream?.bufferedReader()?.use { it.readText() }
                        // GitHub says `message`; the fleet git-proxy says {error, code} (#841).
                        ?.let { JSONObject(it).let { j -> j.optString("message").ifEmpty {
                            listOf(j.optString("error"), j.optString("code")).filter { s -> s.isNotEmpty() }.joinToString(" · ") } } }
                }.getOrNull()?.takeIf { it.isNotEmpty() }
                throw HttpStatus(code, url, if (why == null) "HTTP $code from $url" else "HTTP $code from $url — $why",
                    quota = isQuota(code, c), resetEpoch = c.getHeaderField("X-RateLimit-Reset")?.toLongOrNull())
            }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /**
     * #668 a non-2xx, TYPED, so a reader can say WHICH failure it was instead
     * of re-parsing a sentence. Still an IllegalStateException with the same
     * message, so every caller that caught the old error() is unchanged.
     * [quota] is decided from HTTP conventions, not from any one provider:
     * 429 (RFC 6585), a Retry-After (RFC 9110), or the X-RateLimit-Remaining: 0
     * convention GitHub and most public APIs share - GitHub answers an
     * exhausted anonymous quota with 403 plus that header, which is exactly
     * the case that must not read as "forbidden".
     */
    class HttpStatus(val code: Int, val url: String, message: String,
                     val quota: Boolean = false, val resetEpoch: Long? = null) : IllegalStateException(message)

    // A Retry-After on a 503 is maintenance, not quota - so only 403 is read.
    private fun isQuota(code: Int, c: HttpURLConnection) = code == 429 || code == 403 &&
        (c.getHeaderField("Retry-After") != null || c.getHeaderField("X-RateLimit-Remaining") == "0")

    /** GET [url] as a JSON object. Null on 404; throws on anything else. */
    fun getJson(url: String): JSONObject? = getBody(url)?.let { JSONObject(it) }

    private const val TIMEOUT_MS = 15_000
}
