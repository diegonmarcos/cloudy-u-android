package app.sterna.ui.connect

import android.content.Context
import androidx.annotation.StringRes
import app.sterna.R
import app.sterna.core.jmap.DeviceTokenResult
import app.sterna.core.jmap.OAuthMetadata

/** A message to show, as the resource to look up plus the one argument it formats, if any. */
data class OAuthMessage(@StringRes val resId: Int, val arg: String? = null)

/** The decision behind [oauthFailureMessage]. [error] is the protocol's own word for the refusal
 *  (RFC 6749 §5.2) plus `http_<status>` and `network_error` of ours: a closed vocabulary, safe to
 * show. [description] is read but never shown — free text from an untrusted server. */
fun oauthFailureSpec(
    error: String,
    description: String,
    aadstsCode: String?,
    @StringRes fallbackRes: Int = R.string.connect_oauth_denied,
): OAuthMessage = when {
    error == "authorization_declined" || error == "access_denied" ->
        OAuthMessage(R.string.connect_oauth_declined)
    error == "expired_token" ->
        OAuthMessage(R.string.connect_oauth_expired)
    // #1693 the grant runs in Cloud Account: not installed, not granted or too old.
    error == "cloud_account_unavailable" ->
        OAuthMessage(R.string.connect_oauth_cloud_account)
    // Must stay gated on an AADSTS code: this sentence names Microsoft, while `description` is
    // prose from whatever server answered — a Keycloak saying "invalid_client, contact your
    // administrator" matches `admin`. Real refusals carry a code.
    aadstsCode != null && (
        aadstsCode == "AADSTS650051" || aadstsCode == "AADSTS90094" || aadstsCode == "AADSTS65001" ||
            description.contains("admin", ignoreCase = true) ||
            description.contains("consent", ignoreCase = true) ||
            description.contains("verified publisher", ignoreCase = true) ||
            description.contains("not been approved", ignoreCase = true)
        ) ->
        OAuthMessage(R.string.connect_oauth_admin_consent)
    aadstsCode != null -> OAuthMessage(R.string.connect_oauth_error_code, aadstsCode)
    error == "network_error" -> OAuthMessage(R.string.connect_oauth_network)
    // Say why, but only in the protocol's own words: `error` is server text of arbitrary length
    // wired to the screen, so what does not look like a keyword falls back.
    OAUTH_ERROR_TOKEN.matches(error) -> OAuthMessage(R.string.connect_oauth_error_reason, error)
    else -> OAuthMessage(fallbackRes)
}

/** Which sentence a device-grant wait that ran out of time gets. "Expired, try again" goes only to
 *  someone whose polls were answered: the code expired either way, but for someone who reached
 *  nothing for half an hour the fact is her connection, not the delay (#55). */
@StringRes
fun oauthRanOutMessage(everReachedAServer: Boolean): Int =
    if (everReachedAServer) R.string.connect_oauth_expired else R.string.connect_oauth_network

/** RFC 6749 §5.2 error codes are `*( %x20-21 / %x23-5B / %x5D-7E )` in practice lower snake_case,
 *  plus the `http_<status>` tokens this app makes up when the server sent no `error` field. */
private val OAUTH_ERROR_TOKEN = Regex("""^[a-z][a-z0-9_]{0,39}$|^http_\d{3}$""")

/** The grant a discovered server is signed into with, in the order this app prefers them. */
enum class OAuthGrant {
    /** RFC 8628. Nothing leaves the app: a code is typed on another screen. */
    DEVICE_CODE,

    /** RFC 6749 §4.1 + PKCE. A browser round-trip, coming back on `<applicationId>://oauth`. */
    AUTHORIZATION_CODE,

    /** Neither: the server advertises OAuth this app cannot drive. */
    NONE,
}

/** Which grant to drive against [metadata]. The device flow wins whenever a server offers both: it
 *  never leaves the app, so no browser, no redirect, no coming back into a dead process. */
fun chooseOAuthGrant(metadata: OAuthMetadata): OAuthGrant = when {
    metadata.supportsDeviceFlow -> OAuthGrant.DEVICE_CODE
    metadata.supportsCodeFlow -> OAuthGrant.AUTHORIZATION_CODE
    else -> OAuthGrant.NONE
}

/** Probe [candidates] in order and collect what answers, feeding [chooseOAuthHost]. Collect, don't
 *  stop at the first: the bare domain is tried first, so a site publishing an OAuth document would
 *  end the search before `mail.<domain>` is asked. Only a device flow ends it. */
suspend fun collectOAuthHosts(
    candidates: List<String>,
    discover: suspend (String) -> OAuthMetadata?,
): List<Pair<String, OAuthMetadata>> {
    val discovered = mutableListOf<Pair<String, OAuthMetadata>>()
    for (host in candidates) {
        val metadata = runCatching { discover(host) }.getOrNull() ?: continue
        discovered += host to metadata
        if (metadata.supportsDeviceFlow) break
    }
    return discovered
}

/** Which discovered host the sign-in goes to: the first that speaks the device flow, then the first
 *  that speaks the code grant, and failing both the first that answered at all. The fallback is not
 *  decoration — the bare domain is asked first and is least likely to be the mail server. */
fun chooseOAuthHost(discovered: List<Pair<String, OAuthMetadata>>): Pair<String, OAuthMetadata>? =
    discovered.firstOrNull { chooseOAuthGrant(it.second) == OAuthGrant.DEVICE_CODE }
        ?: discovered.firstOrNull { chooseOAuthGrant(it.second) == OAuthGrant.AUTHORIZATION_CODE }
        ?: discovered.firstOrNull()

/** null when discovery found a server this app can actually sign into; otherwise what to show.
 *  Three failures, three facts: no OAuth at all; OAuth but no flow this app can drive; and OAuth
 *  document whose device endpoint alone was refused still signs in. */
@StringRes
fun oauthDiscoveryFailure(metadata: OAuthMetadata?): Int? = when {
    metadata == null -> R.string.connect_oauth_unsupported
    metadata.endpointsOffDomain && chooseOAuthGrant(metadata) == OAuthGrant.NONE ->
        R.string.connect_oauth_elsewhere
    chooseOAuthGrant(metadata) == OAuthGrant.NONE -> R.string.connect_oauth_flow_unsupported
    else -> null
}

/** Map a device-flow failure to an actionable message. Microsoft refusals carry an AADSTS code in
 *  [DeviceTokenResult.Failed]; admin-consent ones point at an admin or an app password instead of
 *  a dead-end "declined". */
fun oauthFailureMessage(context: Context, failure: DeviceTokenResult.Failed): String {
    val spec = oauthFailureSpec(
        error = failure.error,
        description = failure.description,
        aadstsCode = failure.aadstsCode,
    )
    return if (spec.arg == null) context.getString(spec.resId)
    else context.getString(spec.resId, spec.arg)
}
