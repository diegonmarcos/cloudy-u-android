package app.sterna.fleet

import android.content.Context
import android.net.Uri
import app.sterna.core.data.mail.OAuthCodeBroker
import app.sterna.core.data.mail.OAuthDeviceBroker
import app.sterna.core.jmap.DeviceAuthorization
import app.sterna.core.jmap.DeviceTokenResult
import app.sterna.core.jmap.JmapException
import app.sterna.core.jmap.OAuthTokens
import com.diegonmarcos.superapp.fleetendpoints.FleetEndpointsContract
import com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #1693 Cloud Mail does not run an OAuth device grant: Cloud Account does, over its provider (grant `mail.oauth`, default deny). This maps
 * the two wire answers ([MailOAuthContract.StartAnswer], [MailOAuthContract.PollAnswer]) onto the types the Connect walk already speaks
 * ([DeviceAuthorization], [DeviceTokenResult]), so what the screen says is unchanged. A missing, refusing or older Cloud Account is an
 * exception with the `cloud_account_unavailable` word (one sentence on screen), never a crash; no token is in any message.
 *
 * #1703 the authorization-code grant goes the same way ([OAuthCodeBroker]): [start] below answers a handle (Cloud Account keeps the PKCE verifier, the state
 * and the URL and opens the browser itself), and [finish] hands the redirect's code + state back and gets the tokens. This app never holds either secret.
 */
class CloudAccountOAuthBroker(private val ctx: Context) : OAuthDeviceBroker, OAuthCodeBroker {
    private fun call(method: String, arg: String): android.os.Bundle? {
        if (!MailEndpointSync.accountInstalled(ctx)) return null
        return runCatching { ctx.contentResolver.call(Uri.parse("content://${FleetEndpointsContract.AUTHORITY}"), method, arg, null) }.getOrNull()
    }

    override suspend fun start(email: String, host: String, deviceEndpoint: String, tokenEndpoint: String, clientId: String, scope: String): DeviceAuthorization =
        withContext(Dispatchers.IO) {
            val reply = call(MailOAuthContract.METHOD_START, MailOAuthContract.Request(email, host, deviceEndpoint, tokenEndpoint, clientId, scope).json())
                ?: throw JmapException("Cloud Account did not answer", oauthError = "cloud_account_unavailable")
            if (!reply.getBoolean(MailOAuthContract.KEY_OK)) throw JmapException(MailOAuthContract.refusal(reply.getString(MailOAuthContract.KEY_ERROR)), oauthError = "cloud_account_unavailable")
            toDevice(MailOAuthContract.parseStart(reply.getString(MailOAuthContract.KEY_JSON)))
                ?: throw JmapException("Cloud Account answered something that is not a device authorization", oauthError = "cloud_account_unavailable")
        }

    override suspend fun poll(handle: String): DeviceTokenResult = withContext(Dispatchers.IO) {
        val reply = call(MailOAuthContract.METHOD_POLL, handle) ?: return@withContext DeviceTokenResult.Failed("network_error")
        if (!reply.getBoolean(MailOAuthContract.KEY_OK)) return@withContext DeviceTokenResult.Failed("cloud_account_unavailable")
        toResult(MailOAuthContract.parsePoll(reply.getString(MailOAuthContract.KEY_JSON)))
    }

    override suspend fun start(email: String, host: String, authorizationEndpoint: String, tokenEndpoint: String, clientId: String, scope: String, redirectUri: String): String =
        withContext(Dispatchers.IO) {
            val reply = call(MailOAuthContract.METHOD_CODE_START, MailOAuthContract.CodeRequest(email, host, authorizationEndpoint, tokenEndpoint, clientId, scope, redirectUri).json())
                ?: throw JmapException("Cloud Account did not answer", oauthError = "cloud_account_unavailable")
            if (!reply.getBoolean(MailOAuthContract.KEY_OK)) throw JmapException(MailOAuthContract.refusal(reply.getString(MailOAuthContract.KEY_ERROR)), oauthError = "cloud_account_unavailable")
            toHandle(MailOAuthContract.parseCodeStart(reply.getString(MailOAuthContract.KEY_JSON)))
                ?: throw JmapException("Cloud Account answered something that is not a sign-in handle", oauthError = "cloud_account_unavailable")
        }

    override suspend fun finish(handle: String, code: String?, state: String?, error: String?): DeviceTokenResult = withContext(Dispatchers.IO) {
        val reply = call(MailOAuthContract.METHOD_CODE_FINISH, MailOAuthContract.CodeFinish(handle, code, state, error).json()) ?: return@withContext DeviceTokenResult.Failed("cloud_account_unavailable")
        if (!reply.getBoolean(MailOAuthContract.KEY_OK)) return@withContext DeviceTokenResult.Failed("cloud_account_unavailable")
        toResult(MailOAuthContract.parsePoll(reply.getString(MailOAuthContract.KEY_JSON)))
    }

    companion object {
        /** The wire code-start answer as the handle the screen carries; null for no answer. */
        fun toHandle(a: MailOAuthContract.CodeStartAnswer?): String? = a?.handle
        /** The wire start answer as the screen's [DeviceAuthorization]; [DeviceAuthorization.deviceCode] is Cloud Account's handle. Null for no answer. */
        fun toDevice(a: MailOAuthContract.StartAnswer?): DeviceAuthorization? =
            a?.let { DeviceAuthorization(it.handle, it.userCode, it.verificationUri, it.verificationUriComplete, it.expiresIn, it.interval) }

        /** The wire poll answer as the screen's [DeviceTokenResult]; an unreadable one is a failure with a fixed word, never an exception. */
        fun toResult(a: MailOAuthContract.PollAnswer?): DeviceTokenResult = when (a?.state) {
            "pending" -> DeviceTokenResult.Pending
            "slow_down" -> DeviceTokenResult.SlowDown
            "success" -> DeviceTokenResult.Success(OAuthTokens(accessToken = a.accessToken, refreshToken = a.refreshToken, expiresIn = a.expiresIn, idToken = a.idToken, scope = a.scope))
            "failed" -> DeviceTokenResult.Failed(a.error.ifBlank { "unknown_error" }, a.description, a.aadsts)
            else -> DeviceTokenResult.Failed("unreadable_answer")
        }
    }
}
