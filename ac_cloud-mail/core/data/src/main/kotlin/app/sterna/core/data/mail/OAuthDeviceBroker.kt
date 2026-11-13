package app.sterna.core.data.mail

import app.sterna.core.jmap.DeviceAuthorization
import app.sterna.core.jmap.DeviceTokenResult

/**
 * #1693 THE OAUTH DEVICE GRANT IS RUN BY CLOUD ACCOUNT, not by this app: the app (which owns the discovery of the provider's endpoints) hands
 * the endpoints and its client over a broker and gets back the code to show and, per poll, the verdict. The device code never leaves Cloud
 * Account: [DeviceAuthorization.deviceCode] is an opaque handle it issued. The broker is implemented in the app module (a provider call to
 * Cloud Account); core:data links nothing of the fleet's IPC. Every call BLOCKS or suspends on a provider call and never carries a secret.
 */
interface OAuthDeviceBroker {
    /** Starts the grant: the code to show, with [DeviceAuthorization.deviceCode] = the handle to poll with. Throws a [app.sterna.core.jmap.JmapException] (its `oauthError` set) when it cannot start. */
    suspend fun start(
        email: String,
        host: String,
        deviceEndpoint: String,
        tokenEndpoint: String,
        clientId: String,
        scope: String,
    ): DeviceAuthorization

    /** One poll of the handle: pending, slow down, the tokens, or a failure in the protocol's words. */
    suspend fun poll(handle: String): DeviceTokenResult
}
