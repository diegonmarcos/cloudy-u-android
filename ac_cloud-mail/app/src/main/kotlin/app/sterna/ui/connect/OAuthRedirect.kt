package app.sterna.ui.connect

import app.sterna.core.jmap.OAuthMetadata

// The authorization-code round trip, Cloud Mail's half (#1703). Cloud Account runs the grant: it draws the PKCE verifier and the state, builds the URL,
// opens the browser and redeems the code. What is left here is what only this app can know: the redirect scheme it registered (the provider's client
// id is registered for it) and the one request waiting for the browser's return. Nothing here touches Android.

/** The host half of the redirect this app registers, i.e. `<applicationId>://oauth`. */
const val OAUTH_REDIRECT_HOST = "oauth"

/** The redirect the authorization server sends the browser back to. Built from [applicationId],
 *  never from the literal `app.sterna`: the test build installs alongside production, and two
 *  packages claiming one scheme make the return non-deterministic. Cloud Account only accepts this scheme from the package that owns it. */
fun oauthRedirectUri(applicationId: String): String = "$applicationId://$OAUTH_REDIRECT_HOST"

/** True when a VIEW intent's [scheme]/[host] are this app's own OAuth redirect and nothing else. */
fun isOAuthRedirect(scheme: String?, host: String?, applicationId: String): Boolean =
    scheme.equals(applicationId, ignoreCase = true) && host.equals(OAUTH_REDIRECT_HOST, ignoreCase = true)

/** The one sign-in waiting for the browser, held in memory only: the [handle] Cloud Account issued (the verifier and the state are THERE, never here)
 *  and what the account is added with once the tokens come back. A process death loses it, and the redirect says so. */
data class PendingHandover(
    val handle: String,
    val host: String,
    val metadata: OAuthMetadata,
    val email: String,
    val accountName: String,
) {
    init {
        require(handle.isNotBlank()) { "a sign-in without Cloud Account's handle cannot be finished" }
    }
}

/** The one request in flight, taken once: the browser can deliver the same redirect twice, so a take empties the slot (a replayed redirect finds nothing). */
class AuthorizationRequestSlot {
    private var pending: PendingHandover? = null

    @Synchronized
    fun arm(request: PendingHandover) {
        pending = request
    }

    @Synchronized
    fun disarm() {
        pending = null
    }

    @Synchronized
    fun isArmed(): Boolean = pending != null

    /** The waiting request, emptying the slot; null when nothing is in flight. */
    @Synchronized
    fun take(): PendingHandover? = pending.also { pending = null }
}

/** What Cloud Account's failure words for a redirect mean here; anything else is the provider's own `error` word (a refusal or an exchange failure). */
enum class RedirectProblem { STATE_MISMATCH, NO_PENDING, NOTHING_USABLE, OTHER }

fun redirectProblemOf(error: String): RedirectProblem = when (error) {
    com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract.ERR_STATE_MISMATCH -> RedirectProblem.STATE_MISMATCH
    com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract.ERR_NO_PENDING -> RedirectProblem.NO_PENDING
    com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract.ERR_NOTHING_USABLE -> RedirectProblem.NOTHING_USABLE
    else -> RedirectProblem.OTHER
}
