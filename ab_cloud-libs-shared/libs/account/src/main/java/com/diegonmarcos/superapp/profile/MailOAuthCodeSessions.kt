package com.diegonmarcos.superapp.profile

import android.os.Bundle
import com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * #1703 THE MAIL OAUTH AUTHORIZATION-CODE GRANT, run by Cloud Account for Cloud Mail (it left Mail's OAuthCodeSignIn / OAuthRedirect / OAuthClient).
 * Cloud Account draws the PKCE verifier and the `state`, builds the authorization URL, opens the browser ([MailOAuthCodeActivity]) and, when Cloud
 * Mail hands the redirect's code + state back ([finish]; the redirect itself lands on Cloud Mail's registered scheme, which the provider's client id is
 * registered for), checks the state, redeems the code with the verifier and answers the tokens. The verifier, the state and the code never leave this
 * process except the tokens in the final answer; nothing here is logged or put in an error. Pure over an injected [MusicDeviceGrant.Post], so every step is
 * JVM-tested. MOVED from Cloud Mail: Pkce, buildAuthorizationUrl, redirectVerdict, tokenExchangeFor, AuthorizationRequestSlot, OAuthClient.exchangeCode.
 */
object MailOAuthCodeSessions {
    // ---- PKCE (RFC 7636) ----
    object Pkce {
        /** The only method announced. `plain` would send the verifier itself through the browser. */
        const val CODE_CHALLENGE_METHOD = "S256"
        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        /** One instance, seeded by the OS: the TYPE is the guarantee (a java.util.Random makes PKCE decoration). */
        @JvmField val random: SecureRandom = SecureRandom()

        /** A fresh `code_verifier`: [length] characters of `unreserved`, 43..128 (RFC 7636 §4.1). */
        fun newCodeVerifier(length: Int = 64): String {
            require(length in 43..128) { "a code verifier is 43 to 128 characters (RFC 7636 §4.1)" }
            val out = StringBuilder(length)
            repeat(length) { out.append(UNRESERVED[random.nextInt(UNRESERVED.length)]) }
            return out.toString()
        }

        /** `BASE64URL(SHA256(ASCII(verifier)))`, unpadded (RFC 7636 §4.2). */
        fun codeChallengeOf(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }

    /** A fresh `state` (RFC 6749 §10.12) from SecureRandom: a guessable one lets any link on the phone hand over a code of its own choosing. ~256 bits. */
    fun newState(): String = Pkce.newCodeVerifier(43)

    // ---- the authorization URL (RFC 6749 §4.1.1) ----
    private val GRANT_PARAMETERS = listOf("response_type", "client_id", "redirect_uri", "scope", "state", "code_challenge", "code_challenge_method")
    private const val HEX = "0123456789ABCDEF"

    /** RFC 3986 percent-encoding over UTF-8: everything outside `unreserved` is `%XX` (uppercase), a space is `%20`, never `+`. */
    fun percentEncode(value: String): String {
        val out = StringBuilder(value.length)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF; val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-._~") out.append(ch) else out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
        }
        return out.toString()
    }

    /** The URL the browser is sent to. https only; the seven grant parameters replace any the endpoint carried, its other query survives. Neither the endpoint nor a value is in the refusal. */
    fun buildAuthorizationUrl(authorizationEndpoint: String, clientId: String, redirectUri: String, scope: String, state: String, codeChallenge: String): String {
        val uri = runCatching { URI(authorizationEndpoint) }.getOrNull()
        require(uri != null && uri.host != null && uri.rawAuthority != null) { "the authorization endpoint is not a usable URL" }
        require(uri.scheme.equals("https", ignoreCase = true)) { "the authorization endpoint must be https" }
        val kept = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.filter { pair ->
            val name = runCatching { URLDecoder.decode(pair.substringBefore('='), "UTF-8") }.getOrDefault(pair)
            name !in GRANT_PARAMETERS
        }
        val ours = listOf("response_type" to "code", "client_id" to clientId, "redirect_uri" to redirectUri, "scope" to scope, "state" to state,
            "code_challenge" to codeChallenge, "code_challenge_method" to Pkce.CODE_CHALLENGE_METHOD).map { (k, v) -> k + "=" + percentEncode(v) }
        return "https://" + uri.rawAuthority + uri.rawPath.orEmpty() + "?" + (kept + ours).joinToString("&")
    }

    // ---- what a redirect is worth, decided before any network call ----
    /** The one authorization request in flight, in memory only. No exchange without PKCE: a request without a verifier cannot be built. */
    class Pending(val state: String, val codeVerifier: String, val request: MailOAuthContract.CodeRequest, val authorizationUrl: String, val deadlineMs: Long) {
        init {
            require(codeVerifier.isNotBlank()) { "an authorization request without a code verifier could only be exchanged without PKCE" }
            require(state.isNotBlank()) { "an authorization request without a state cannot be matched" }
        }
        override fun toString() = "Pending(email=${request.email})"
    }

    sealed interface Verdict {
        /** The only verdict that reaches the token endpoint. */
        class Exchange(val code: String, val request: Pending) : Verdict { override fun toString() = "Exchange" }
        /** Nothing was in flight: expired, finished already, or a stale link. */
        data object NoPendingRequest : Verdict
        /** Not our sign-in: the code is not exchanged. */
        data object StateMismatch : Verdict
        /** The server said no (the user declined, or the client is unknown). */
        data class Refused(val error: String) : Verdict
        data object NothingUsable : Verdict
    }

    /** The order IS the guard: something in flight, then the state we drew compared before `code` or `error` are looked at, then the server's answer. Blank never matches. */
    fun redirectVerdict(pending: Pending?, state: String?, code: String?, error: String?): Verdict {
        if (pending == null) return Verdict.NoPendingRequest
        if (state.isNullOrBlank() || state != pending.state) return Verdict.StateMismatch
        if (!error.isNullOrBlank()) return Verdict.Refused(error)
        if (code.isNullOrBlank()) return Verdict.NothingUsable
        return Verdict.Exchange(code, pending)
    }

    /** The five values the token endpoint is called with (RFC 6749 §4.1.3). */
    data class TokenExchange(val tokenEndpoint: String, val clientId: String, val code: String, val redirectUri: String, val codeVerifier: String) {
        override fun toString() = "TokenExchange(tokenEndpoint=$tokenEndpoint)"
    }

    /** What to POST for a redirect that earned an exchange: the verifier is the one THIS request drew (a freshly drawn one passes every guard and makes PKCE decoration). */
    fun tokenExchangeFor(v: Verdict.Exchange): TokenExchange = TokenExchange(v.request.request.tokenEndpoint, v.request.request.clientId, v.code, v.request.request.redirectUri, v.request.codeVerifier)

    /** The in-flight requests by handle, each redeemed once: a replayed redirect finds nothing, except that "not our sign-in" verdicts keep the request (a forged link must not cancel the real one). */
    class Slots {
        private val map = ConcurrentHashMap<String, Pending>()
        @Synchronized fun arm(handle: String, p: Pending) { map[handle] = p }
        @Synchronized fun disarm(handle: String) { map.remove(handle) }
        @Synchronized fun clear() = map.clear()
        @Synchronized fun isArmed(handle: String) = map.containsKey(handle)
        @Synchronized fun size() = map.size
        @Synchronized fun peek(handle: String): Pending? = map[handle]
        @Synchronized fun purge(nowMs: Long) { map.entries.removeIf { it.value.deadlineMs < nowMs } }
        @Synchronized fun redeem(handle: String, state: String?, code: String?, error: String?): Verdict {
            val v = redirectVerdict(map[handle], state, code, error)
            if (v !is Verdict.NoPendingRequest && v !is Verdict.StateMismatch) map.remove(handle)
            return v
        }
    }

    private val slots = Slots()
    private const val MAX = 8
    private const val LIFETIME_MS = 10 * 60 * 1000L
    @Volatile var post: MusicDeviceGrant.Post = MusicDeviceGrant.defaultPost()
    @Volatile var nowMs: () -> Long = { System.currentTimeMillis() }

    private fun ok(json: String) = Bundle().apply { putBoolean(MailOAuthContract.KEY_OK, true); putString(MailOAuthContract.KEY_JSON, json) }
    private fun refuse(why: String) = Bundle().apply { putBoolean(MailOAuthContract.KEY_OK, false); putString(MailOAuthContract.KEY_ERROR, why) }
    private fun failed(error: String, description: String = "", aadsts: String? = null) = MailOAuthContract.PollAnswer("failed", error = error, description = description, aadsts = aadsts)

    /** Owner only: the grant runs in Cloud Account's process, for a caller the provider already checked ([callerPackage] is that caller). */
    fun start(ctx: android.content.Context, arg: String?, callerPackage: String?): Bundle {
        if (!SignInOwner.isOwner(ctx)) return refuse("the mail sign-in runs in Cloud Account only")
        val r = MailOAuthContract.parseCode(arg, callerPackage) ?: return refuse("the request was refused: check its endpoints, address and redirect")
        return startUnchecked(r)
    }

    /** The grant for a request already checked ([MailOAuthContract.parseCode]); also the seam the tests use. */
    fun startUnchecked(r: MailOAuthContract.CodeRequest): Bundle {
        slots.purge(nowMs())
        if (slots.size() >= MAX) return refuse("too many sign-ins are waiting")
        val verifier = Pkce.newCodeVerifier(); val state = newState()
        val url = try { buildAuthorizationUrl(r.authorizationEndpoint, r.clientId, r.redirectUri, r.scope, state, Pkce.codeChallengeOf(verifier)) }
        catch (e: IllegalArgumentException) { return refuse(e.message ?: "the authorization endpoint is not usable") }
        val handle = ByteArray(18).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        slots.arm(handle, Pending(state, verifier, r, url, nowMs() + LIFETIME_MS))
        return ok(MailOAuthContract.CodeStartAnswer(handle, (LIFETIME_MS / 1000).toInt()).json())
    }

    /** What [MailOAuthCodeActivity] opens in the browser: the URL of a request still in flight, else null. Not consumed: the person may tap the button again. */
    fun authorizationUrl(handle: String?): String? {
        val p = slots.peek(handle.orEmpty()) ?: return null
        if (nowMs() > p.deadlineMs) { slots.disarm(handle.orEmpty()); return null }
        return p.authorizationUrl
    }

    /** Give up on a sign-in: a late redirect then exchanges nothing. */
    fun cancel(handle: String?) = slots.disarm(handle.orEmpty())

    fun finish(ctx: android.content.Context, arg: String?): Bundle {
        if (!SignInOwner.isOwner(ctx)) return refuse("the mail sign-in runs in Cloud Account only")
        val f = MailOAuthContract.parseCodeFinish(arg) ?: return refuse("the redirect was not readable")
        return finishOnce(f)
    }

    /** One redirect: the verdict first, then (only for an earned exchange) one token request (the seam the tests use). */
    fun finishOnce(f: MailOAuthContract.CodeFinish): Bundle {
        slots.purge(nowMs())
        val a = when (val v = slots.redeem(f.handle, f.state, f.code, f.error)) {
            Verdict.NoPendingRequest -> failed(MailOAuthContract.ERR_NO_PENDING)
            Verdict.StateMismatch -> failed(MailOAuthContract.ERR_STATE_MISMATCH)
            is Verdict.Refused -> failed(v.error.take(64))
            Verdict.NothingUsable -> failed(MailOAuthContract.ERR_NOTHING_USABLE)
            is Verdict.Exchange -> exchange(tokenExchangeFor(v))
        }
        return ok(a.json())
    }

    private fun exchange(x: TokenExchange): MailOAuthContract.PollAnswer = try {
        val raw = post.form(x.tokenEndpoint, mapOf("grant_type" to "authorization_code", "code" to x.code, "redirect_uri" to x.redirectUri, "client_id" to x.clientId, "code_verifier" to x.codeVerifier))
        val o = runCatching { JSONObject(raw) }.getOrNull()
        if (o == null || o.optString("access_token").isBlank()) failed("malformed_response")
        else MailOAuthContract.PollAnswer("success", o.optString("access_token"), o.optString("refresh_token").ifBlank { null }, o.optLong("expires_in", 3600),
            o.optString("id_token").ifBlank { null }, o.optString("scope").ifBlank { null })
    } catch (e: MusicDeviceGrant.Http) {
        val body = runCatching { JSONObject(e.body) }.getOrNull()
        // the protocol's `error` word travels; the body and `error_description` never do (this endpoint answers the account's tokens)
        failed(body?.optString("error").orEmpty().ifBlank { "http_${e.status}" })
    } catch (_: IOException) { failed("network_error") }

    /** Test hook: forget every request in flight. */
    fun clear() = slots.clear()
}
