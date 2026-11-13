package com.diegonmarcos.superapp.profile

import android.content.Context
import com.diegonmarcos.superapp.settings.AccountVault
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * #1693 THE MUSIC BROWSER SIGN-INS (Spotify: OAuth code + PKCE, Deezer: implicit) now run in Cloud Account only; they left libs:scraper's
 * MusicOAuth / MusicUi. Pure over [MusicDeviceGrant.Post], so each step is JVM-tested. The page that drives it is App credentials > Music
 * (a WebView sheet that captures the redirect); the client id is an App credential, the tokens are filed under `music.<id>.access|refresh|expires`
 * for Cloud Browser to take. No token is logged or put in an error; a refusal carries the provider's `error` word, never its body.
 */
object MusicOAuthFlow {
    data class Spec(val id: String, val label: String, val pkce: Boolean, val authUrl: String, val tokenUrl: String, val scopes: String, val redirect: String, val scopeParam: String)

    val SPECS: Map<String, Spec> = mapOf(
        "spotify" to Spec("spotify", "Spotify", true, "https://accounts.spotify.com/authorize", "https://accounts.spotify.com/api/token",
            "playlist-read-private playlist-read-collaborative user-library-read playlist-modify-private", "http://127.0.0.1:8765/callback", "scope"),
        "deezer" to Spec("deezer", "Deezer", false, "https://connect.deezer.com/oauth/auth.php", "",
            "basic_access,manage_library,offline_access", "http://127.0.0.1:8765/callback", "perms"),
    )

    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun verifier(rnd: SecureRandom = SecureRandom()): String = ByteArray(48).also { rnd.nextBytes(it) }.let { b64.encodeToString(it) }

    fun challenge(verifier: String): String = b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun form(m: Map<String, String>) = m.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

    /** The page the sign-in WebView opens; [challenge] is required for a PKCE service. */
    fun authUrl(spec: Spec, clientId: String, state: String, challenge: String?): String {
        val q = if (spec.pkce) mapOf("response_type" to "code", "client_id" to clientId, "redirect_uri" to spec.redirect, spec.scopeParam to spec.scopes,
            "state" to state, "code_challenge_method" to "S256", "code_challenge" to requireNotNull(challenge))
        else mapOf("app_id" to clientId, "redirect_uri" to spec.redirect, spec.scopeParam to spec.scopes, "response_type" to "token", "state" to state)
        return spec.authUrl + "?" + form(q)
    }

    data class Callback(val code: String? = null, val token: String? = null, val expiresInSec: Long = 0, val error: String? = null)

    private fun params(s: String): Map<String, String> = s.split('&').filter { it.contains('=') }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    /** The result when [url] is the redirect back (query for a code, fragment for a token), null for any other page. */
    fun callback(url: String, spec: Spec, state: String): Callback? {
        if (!url.startsWith(spec.redirect)) return null
        val rest = url.removePrefix(spec.redirect)
        val p = params(rest.substringBefore('#').removePrefix("?")) + params(rest.substringAfter('#', ""))
        if (p["state"] != null && p["state"] != state) return Callback(error = "state mismatch")
        p["error"]?.let { return Callback(error = it.take(40)) }
        return Callback(code = p["code"], token = p["access_token"], expiresInSec = p["expires"]?.toLongOrNull() ?: 0)
    }

    /** The PKCE code exchange; tokens, or a [MusicDeviceGrant.Http] carrying the provider's body (never put in a message). */
    fun exchange(post: MusicDeviceGrant.Post, spec: Spec, clientId: String, code: String, verifier: String): MusicDeviceGrant.Tokens =
        MusicDeviceGrant.parseTokens(post.form(spec.tokenUrl, mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to spec.redirect,
            "client_id" to clientId, "code_verifier" to verifier)))

    /** What the redirect yields, as tokens: the implicit token, or the exchanged code. Throws with a fixed sentence, never a value. */
    fun tokensOf(post: MusicDeviceGrant.Post, spec: Spec, clientId: String, cb: Callback, verifier: String): MusicDeviceGrant.Tokens {
        cb.error?.let { throw IllegalStateException("${spec.label} refused: $it") }
        cb.token?.let { return MusicDeviceGrant.Tokens(it, null, cb.expiresInSec) }
        return exchange(post, spec, clientId, cb.code ?: throw IllegalStateException("${spec.label} sent no code"), verifier)
    }

    /** Files the tokens where Cloud Browser takes them; owner only (the sign-in runs in Cloud Account). False when [ctx] is not the owner. */
    fun file(ctx: Context, spec: Spec, t: MusicDeviceGrant.Tokens, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!SignInOwner.isOwner(ctx)) return false
        MusicDeviceGrant.fileUnder(AccountVault(ctx), "music.${spec.id}", t, nowMs)
        return true
    }
}
