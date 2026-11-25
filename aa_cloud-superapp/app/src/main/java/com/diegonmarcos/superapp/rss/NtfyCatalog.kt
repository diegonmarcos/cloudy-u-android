package com.diegonmarcos.superapp.rss

import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
import org.json.JSONObject

/**
 * Human names for ntfy topics, and the address of the advisory channel.
 *
 * ## Why a label table exists at all
 * A snake_case topic is an ADDRESS, not a name. `sec_yara` says nothing about
 * what arrives there, and a screen listing twenty-six of them is an inventory
 * nobody reads — which is the same as not publishing it. The fleet's own
 * registry (`cloud-infra/a_solutions/infra-obs_ntfy/src/build.json::topics`)
 * has carried a human `title` per topic all along; this mirrors it so the app
 * can show the sentence and keep the address as the subtitle.
 *
 * ## Missing labels degrade, they do not hide
 * [labelOf] returns the raw topic when nothing is declared. Same rule as
 * [NtfyScopes]'s last-scope fallback and for the same reason: an unlabelled
 * channel is merely less readable, an omitted one has stopped being watched
 * and nobody finds out. Adding a channel stays a data edit in
 * `build.json::ui.ntfy` (FIRE RULE #6), never a code change.
 */
object NtfyCatalog {

    @Volatile private var cachedLabels: Map<String, String>? = null

    private fun config(): JSONObject = runCatching {
        JSONObject(String(Base64.decode(BuildConfig.UI_NTFY_B64, Base64.NO_WRAP)))
    }.getOrDefault(JSONObject())

    private fun labels(): Map<String, String> {
        cachedLabels?.let { return it }
        val o = config().optJSONObject("labels") ?: JSONObject()
        val out = HashMap<String, String>(o.length())
        for (k in o.keys()) out[k] = o.optString(k)
        cachedLabels = out
        return out
    }

    /** Human title for [topic]; the topic itself when none is declared. */
    fun labelOf(topic: String): String = labels()[topic]?.takeIf { it.isNotBlank() } ?: topic

    @Volatile private var cachedTaxon: Map<String, String>? = null

    private fun taxons(): Map<String, String> {
        cachedTaxon?.let { return it }
        val o = config().optJSONObject("taxon") ?: JSONObject()
        val out = HashMap<String, String>(o.length())
        for (k in o.keys()) out[k] = o.optString(k)
        cachedTaxon = out
        return out
    }

    /**
     * The phone-taxonomy SECTION PREFIX this cloud publisher belongs to —
     * the same one-character vocabulary `ui.phone_sections` declares, so the
     * Notify tabs can narrow a cloud stream by the categories they were
     * built from ("_" Setup, "@" Communication, "." Data, "=" Primary, "~" Me, "+" Services · City, "%" Services · Leisure, "*" AGI, "#" Projects).
     *
     * Cloud messages carry no package and therefore no classifier can be run
     * on them: a topic or an in-app producer name is all there is. Without
     * this map every cloud publisher passed unfiltered and the SuperApp
     * updater showed up on all six tabs — an obviously system-level stream
     * appearing under Inboxes and under Data Apps.
     *
     * Blank when the key is not declared, and a blank prefix is treated as
     * "All tab only": a publisher nobody has categorised should not silently
     * claim a category.
     */
    fun taxonOf(key: String): String = taxons()[key].orEmpty()

    /**
     * The origin every programmatic ntfy READ must use: the service itself on
     * the WireGuard mesh, ahead of the public edge's authorization gate.
     *
     * ## The public hostname has no anonymous poll route — not one that is shut
     * `rss.diegonmarcos.com` and this address are the SAME ntfy container
     * (`10.0.0.6:8090`) reached two ways, but they are not the same SURFACE.
     * Caddy's `mkNtfyBlock`
     * (`cloud-u-containers/infra-sec_caddy/src/caddyfile.nix`) opens exactly
     * one prefix to anonymous callers — `handle /feed* { reverse_proxy … }`,
     * unauthenticated because `feed_auth: "none"` — and that prefix is served
     * by the rss-gateway SIDECAR, not by ntfy. The sidecar
     * (`infra-obs_ntfy/src/code/rss-gateway.py`) publishes `/feed/health`,
     * `/feed/profiles.json`, `/feed/channels.json`, `/feed/<profile>.atom` and
     * `/feed/c/<topic>.atom`. There is no `/feed/<topic>/json` in it, and
     * every path outside `/feed*` falls through to
     * `handle { forward_auth authelia }`. So the poll API is reachable
     * anonymously by NO public path: the gated one bounces to SSO and the
     * ungated one does not implement it. MEASURED anonymously from a mesh
     * peer with the credential stripped (2026-09-09):
     *
     *     GET /feed/channels.json                 -> 200
     *     GET /feed/fleet_advisory/json?poll=1     -> 404  (sidecar: no route)
     *     GET /fleet_advisory/json?poll=1          -> 302  auth.diegonmarcos.com
     *     GET http://10.0.0.6:8090/…/json?poll=1   -> 200  (all 26 channels)
     *
     * ## Why the ungated path rather than a token
     * The gate is CORRECT and stays. ntfy's own access control already grants
     * what a read needs — `auth-default-access: read-write` in
     * `infra-obs_ntfy/src/templates/server.yml.tpl` — and the fleet already
     * treats wg0 as the auth boundary for reading this service, which is the
     * same judgement that made `/feed*` `feed_auth: "none"`. Minting a bearer
     * would add a credential that can expire, on a screen whose whole job is
     * to be trustworthy when other things are broken.
     *
     * `/feed/c/<topic>.atom` is a real anonymous per-channel route and needs
     * no mesh, but it is not a substitute: it answers 404 for
     * `cloud-sa-notifications`, `fleet_advisory` and `infra_mail-health` —
     * the three channels that matter most — because the sidecar's valid-channel
     * set is the union of its profiles config and the RSS taxonomy, and those
     * three are in neither. Twenty-three of twenty-six is not every channel.
     *
     * Human links go to [webBaseUrl] instead: a WebView carries the Authelia
     * session cookie and satisfies the gate, which is the tier built for
     * people.
     *
     * Declared in `build.json::ui.ntfy.base_url` so the origin can move without
     * a code change (FIRE RULE #6). [com.diegonmarcos.superapp.recovery
     * .AdvisoryFeed] deliberately keeps its own copy rather than calling here —
     * it is the escape hatch for a device too stale to update, and a lifeline
     * with one dependency is a lifeline with one fewer way to fail.
     *
     * Cleartext to `10.0.0.6` is permitted by `res/xml/network_security_config
     * .xml`, which enumerates the mesh peers.
     */
    fun readBaseUrl(): String =
        config().optString("base_url").ifBlank { "http://10.0.0.6:8090" }.trimEnd('/')

    /** The origin a HUMAN is sent to — the topic page, opened in the in-app
     *  browser, where the Authelia cookie makes the gate a non-event. Never
     *  used for a programmatic read; see [readBaseUrl] for why. */
    fun webBaseUrl(): String =
        config().optString("web_base_url").ifBlank { "https://rss.diegonmarcos.com" }.trimEnd('/')

    /**
     * How far back a channel card looks, in a unit ntfy actually parses.
     *
     * `since` accepts s/m/h, a Unix timestamp, a message id or `all`. It does
     * NOT accept `d`, and `since=7d` answered
     * `HTTP 400 {"code":40008,"error":"invalid since parameter"}` on every
     * call — so a poll asking in days fails even on the origin that would
     * have answered it. Declared rather than written here because the right
     * window is a product decision that changes with how chatty the fleet is:
     * `health_resources` alone replays 851 KB over 24h and 3.6 MB over 168h,
     * and a shade only ever shows the newest handful.
     */
    fun pollWindow(): String = config().optString("poll_window").ifBlank { "24h" }

    /** THE one place a programmatic ntfy read URL is built. Two files used to
     *  compose this string themselves and they drifted onto different hosts —
     *  which is how the Notify cards kept answering 401 after the advisory
     *  screen was already fixed. */
    fun pollUrl(topic: String, window: String = pollWindow()): String =
        "${readBaseUrl()}/$topic/json?poll=1&since=$window"

    /**
     * Why a read failed, in words that tell the owner what to DO.
     *
     * "unavailable" collapses three different problems with three different
     * responses into one shrug: a channel refusing us needs a credential, a
     * channel we cannot reach needs the mesh back, and a channel that does not
     * exist needs the catalog fixed. A 3xx is its own answer — ntfy replies
     * directly, so a redirect means we are talking to the SSO portal and are
     * on the gated route rather than the open one.
     *
     * ntfy answers 200 with an empty body for a topic nobody ever published
     * to, so a 404 here is always the wrong ADDRESS, never a quiet channel.
     */
    fun readVerdict(code: Int): String = when {
        code == 401 || code == 403 -> "not authorised · this channel needs a credential"
        code == 404                -> "no such topic · check the catalog"
        code in 300..399           -> "login required · gated route, not the open one"
        code in 500..599           -> "server error · HTTP $code"
        else                       -> "HTTP $code"
    }

    /** The verdict when nothing answered at all. Distinct from [readVerdict]
     *  on purpose: no status code means we never got to ask, and off-mesh is
     *  overwhelmingly the reason on a phone. */
    const val UNREACHABLE_VERDICT = "cannot reach · phone off the mesh?"

    /**
     * The topic carrying out-of-band install/repair advisories.
     *
     * Declared rather than hardcoded because it is the one channel a stranded
     * device is told to look at, and the app that would have to be updated to
     * learn a new address is precisely the app that cannot update. Keeping it
     * in `ui.ntfy` at least means the NEXT build can be pointed elsewhere
     * without touching Kotlin.
     */
    fun advisoryTopic(): String =
        config().optString("advisory_topic").ifBlank { "cloud-sa-notifications" }
}
