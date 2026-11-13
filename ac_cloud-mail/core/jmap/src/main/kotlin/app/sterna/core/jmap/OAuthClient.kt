package app.sterna.core.jmap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** OAuth 2.0 authorization-server metadata (RFC 8414), trimmed to what we use. */
@Serializable
data class OAuthMetadata(
    val issuer: String = "",
    @SerialName("authorization_endpoint") val authorizationEndpoint: String? = null,
    @SerialName("token_endpoint") val tokenEndpoint: String = "",
    @SerialName("device_authorization_endpoint") val deviceAuthorizationEndpoint: String? = null,
        /**
         * Set by [OAuthClient.discoverMetadata] when a named endpoint was blanked FOR ITS HOST — the
         */
    @kotlinx.serialization.Transient val endpointsOffDomain: Boolean = false,
) {
    /** True when this server supports the Device Authorization Grant (RFC 8628). */
    val supportsDeviceFlow: Boolean
        get() = !deviceAuthorizationEndpoint.isNullOrBlank() && tokenEndpoint.isNotBlank()

    /** True when this server supports the Authorization Code Grant (RFC 6749 §4.1). */
    val supportsCodeFlow: Boolean
        get() = !authorizationEndpoint.isNullOrBlank() && tokenEndpoint.isNotBlank()
}

/** Response to a device-authorization request (RFC 8628 §3.2). */
@Serializable
data class DeviceAuthorization(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Int = 1800,
    val interval: Int = 5,
)

/** A successful token grant (RFC 6749 §5.1). */
@Serializable
data class OAuthTokens(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("token_type") val tokenType: String = "Bearer",
    /** Present when the `openid` scope was requested; carries the signed-in identity. */
    @SerialName("id_token") val idToken: String? = null,
    /** The scopes actually granted (may differ from those requested). */
    @SerialName("scope") val scope: String? = null,
)

/** A parsed OAuth error response (RFC 6749 §5.2). [aadstsCode] is the first AADSTS code
 *  found in [description], when present (Microsoft), e.g. "AADSTS650051". */
data class OAuthError(
    val error: String,
    val description: String = "",
    val aadstsCode: String? = null,
)

/** One device-token poll outcome (RFC 8628 §3.5). */
sealed interface DeviceTokenResult {
    data class Success(val tokens: OAuthTokens) : DeviceTokenResult
    /** `authorization_pending` — the user hasn't approved yet; keep polling. */
    data object Pending : DeviceTokenResult
    /** `slow_down` — poll less often (increase the interval by 5s, per RFC). */
    data object SlowDown : DeviceTokenResult
    /** Terminal failure: `expired_token`, `access_denied`, or an HTTP/transport error.
     *  [description] and [aadstsCode] carry the server's `error_description` detail when present. */
    data class Failed(
        val error: String,
        val description: String = "",
        val aadstsCode: String? = null,
    ) : DeviceTokenResult
}

// #1703 PKCE and the authorization URL left this module with the authorization-code grant: Cloud Account runs it (libs:account MailOAuthCodeSessions).

    /**
     * Add `login_hint` (OIDC Core §3.1.2.1) to a device-flow verification URL, so the approval page
     */
fun withLoginHint(verificationUri: String, loginHint: String): String {
    if (loginHint.isBlank()) return verificationUri
    val url = verificationUri.toHttpUrlOrNull() ?: return verificationUri
    if (url.scheme != "https") return verificationUri
    if (LOGIN_HINT in url.queryParameterNames) return verificationUri
    return url.newBuilder()
        .addEncodedQueryParameter(LOGIN_HINT, percentEncode(loginHint))
        .build()
        .toString()
}

/** The hint's name (OIDC Core §3.1.2.1) — also the one the server may have written itself. */
private const val LOGIN_HINT = "login_hint"

/**
 * RFC 3986 percent-encoding: everything outside `unreserved` becomes `%XX` over its UTF-8 bytes.
 * A space is `%20` and never `+` (the same rule the authorization URL follows in Cloud Account). Uppercase hex, no locale involved.
 */
private fun percentEncode(value: String): String {
    val out = StringBuilder(value.length)
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~") {
            out.append(char)
        } else {
            out.append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
        }
    }
    return out.toString()
}

private const val HEX = "0123456789ABCDEF"

    /**
     * The client the four OAuth POSTs use — built here, and NOT the shared JMAP one. These POSTs go to
     */
internal fun defaultOAuthHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

    /**
     * Whether a discovery document may name [endpoint]: only over https, and only on [queriedHost]
     */
internal fun oauthEndpointAllowed(endpoint: String, queriedHost: String, addressDomain: String): Boolean {
    val url = endpoint.toHttpUrlOrNull() ?: return false
    if (url.scheme != "https") return false
    val host = url.host.lowercase()
    return underOwner(host, queriedHost) || underOwner(host, addressDomain)
}

    /**
     * Whether [endpoint] names a host that is neither under [queriedHost] nor under [addressDomain] —
     */
internal fun oauthEndpointNamesAnotherHost(
    endpoint: String,
    queriedHost: String,
    addressDomain: String,
): Boolean {
    val url = endpoint.toHttpUrlOrNull() ?: return false
    val host = url.host.lowercase()
    return !underOwner(host, queriedHost) && !underOwner(host, addressDomain)
}

/** [host] is [owner] or a subdomain of it. An empty [owner] owns nothing: never `h.endsWith(".")`. */
private fun underOwner(host: String, owner: String): Boolean {
    val own = owner.lowercase()
    if (own.isEmpty()) return false
    return host == own || host.endsWith(".$own")
}

    /**
     * Minimal OAuth 2.0 client for the Device Authorization Grant (RFC 8628), the Authorization Code
     */
class OAuthClient internal constructor(
    /** Metadata discovery only: follows 3xx, on purpose. See [defaultOAuthHttpClient]. */
    private val discoveryHttpClient: OkHttpClient,
    /** The four POSTs that carry a secret. One field, so none of them can be forgotten. */
    private val tokenHttpClient: OkHttpClient,
    private val json: Json,
) {
    /** Public constructor for app code — shared client for discovery, dedicated one for the POSTs. */
    constructor() : this(JmapClient.defaultHttpClient(), defaultOAuthHttpClient(), JmapClient.DefaultJson)

        /**
         * Fetch the server's OAuth metadata, or null if it advertises none.
         */
    suspend fun discoverMetadata(host: String, addressDomain: String): OAuthMetadata? = withContext(Dispatchers.IO) {
        val metadataUrl = Jmap.oauthMetadataUrlFor(host)
        val request = Request.Builder()
            .url(metadataUrl)
            .header("Accept", "application/json")
            .get()
            .build()
        val found = runCatching {
            discoveryHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.decodeFromString<OAuthMetadata>(response.body?.string().orEmpty())
            }
        }.getOrNull() ?: return@withContext null
        if (found.tokenEndpoint.isBlank()) return@withContext null

            // The host actually asked, read off the URL actually built: [host] may be a bare host or a
            // full URL. Unreadable leaves [addressDomain] as the only owner — the safe side.
        val queriedHost = metadataUrl.toHttpUrlOrNull()?.host.orEmpty()
        fun allowed(endpoint: String?) = oauthEndpointAllowed(endpoint.orEmpty(), queriedHost, addressDomain)
            // The FACT is narrower than the refusal: only a host that is someone else's raises it.
            // A cleartext endpoint on our own host is blanked the same and says nothing about who.
        fun elsewhere(endpoint: String?) = !endpoint.isNullOrBlank() &&
            oauthEndpointNamesAnotherHost(endpoint, queriedHost, addressDomain)
        found.copy(
            authorizationEndpoint = found.authorizationEndpoint?.takeIf { allowed(it) },
            tokenEndpoint = found.tokenEndpoint.takeIf { allowed(it) }.orEmpty(),
            deviceAuthorizationEndpoint = found.deviceAuthorizationEndpoint?.takeIf { allowed(it) },
            endpointsOffDomain = elsewhere(found.tokenEndpoint) ||
                elsewhere(found.authorizationEndpoint) ||
                elsewhere(found.deviceAuthorizationEndpoint),
        )
    }

    // #1693 the OAuth device grant (start + poll) left this client: it runs in Cloud Account (libs:account MailOAuthSessions); the types stay, the app maps its answers onto them.

    // #1703 the authorization-code exchange left this client too: Cloud Account redeems the code (it holds the PKCE verifier).

    /** Exchange a refresh token for a fresh access token (RFC 6749 §6). */
    suspend fun refresh(
        tokenEndpoint: String,
        refreshToken: String,
        clientId: String,
    ): OAuthTokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()
        val request = Request.Builder().url(tokenEndpoint).post(form).header("Accept", "application/json").build()
        tokenHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException("Token refresh failed: HTTP ${response.code}", httpCode = response.code)
            }
            decodeGuarded(body, "token", OAuthTokens.serializer())
        }
    }

        /**
         * Decode an OAuth response WITHOUT letting the payload into the failure.
         */
    private fun <T> decodeGuarded(body: String, what: String, serializer: KSerializer<T>): T =
        try {
            json.decodeFromString(serializer, body)
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException, and so is what
            // decodeFromString raises on a structurally wrong document.
            throw JmapException("Could not decode the $what response")
        }

    internal fun parseError(body: String): OAuthError? = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        val error = obj["error"]?.jsonPrimitive?.content ?: return@runCatching null
        val description = obj["error_description"]?.jsonPrimitive?.content.orEmpty()
        OAuthError(error, description, AADSTS_REGEX.find(description)?.value)
    }.getOrNull()

    private companion object {
        private val AADSTS_REGEX = Regex("AADSTS\\d+")
    }
}
