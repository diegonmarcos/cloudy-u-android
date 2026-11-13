package app.sterna.core.data.mail

import app.sterna.core.jmap.DeviceTokenResult

/**
 * #1703 THE OAUTH AUTHORIZATION-CODE GRANT IS RUN BY CLOUD ACCOUNT, not by this app (the sibling of [OAuthDeviceBroker]): the app (which owns the discovery
 * of the provider's endpoints and the redirect scheme it registered) hands the endpoints over and gets a handle; Cloud Account draws the PKCE verifier and
 * the state, opens the browser and keeps them. When the redirect comes back on this app's scheme the app hands the code + state to [finish]; Cloud Account
 * checks the state and redeems the code. No verifier, state or URL is ever in this app. Implemented in the app module (a provider call to Cloud Account).
 */
interface OAuthCodeBroker {
    /** Starts the grant and answers its handle. Throws a [app.sterna.core.jmap.JmapException] (its `oauthError` set) when it cannot start. */
    suspend fun start(
        email: String,
        host: String,
        authorizationEndpoint: String,
        tokenEndpoint: String,
        clientId: String,
        scope: String,
        redirectUri: String,
    ): String

    /** The redirect's values for [handle]: the tokens, or a failure in the protocol's words (or `state_mismatch` / `no_pending_request` / `nothing_usable`). Never [DeviceTokenResult.Pending]. */
    suspend fun finish(handle: String, code: String?, state: String?, error: String?): DeviceTokenResult
}
