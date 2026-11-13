package com.diegonmarcos.superapp.profile

import android.content.Context
import com.diegonmarcos.superapp.settings.AccountVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * #1693 THE MUSIC DEVICE GRANT now runs in Cloud Account only (it left libs:scraper's MusicAuth): the OAuth 2.0 device grant (RFC
 * 8628) of a music service that signs in that way (YouTube Music), driven with the client id / secret filed in App credentials,
 * its tokens filed beside them (`music.<id>.access|refresh|expires`) for Cloud Browser to take over the provider's per-credential
 * grant. Pure over an injected [Post], so every step is JVM-tested; no value is logged and none is in an error message.
 */
object MusicDeviceGrant {
    data class Spec(val id: String, val label: String, val deviceUrl: String, val tokenUrl: String, val scopes: String)

    /** The services that sign in with a device grant (the same declaration libs:scraper's catalogue held). */
    val SPECS: Map<String, Spec> = mapOf(
        "ytmusic" to Spec("ytmusic", "YouTube Music", "https://oauth2.googleapis.com/device/code", "https://oauth2.googleapis.com/token",
            "https://www.googleapis.com/auth/youtube"),
    )

    data class Code(val deviceCode: String, val userCode: String, val url: String, val intervalSec: Long, val expiresInSec: Long) {
        override fun toString() = "Code(userCode=$userCode, url=$url, intervalSec=$intervalSec, expiresInSec=$expiresInSec)"
    }
    data class Tokens(val access: String, val refresh: String?, val expiresInSec: Long) {
        override fun toString() = "Tokens(access=<redacted>, refresh=${if (refresh == null) "none" else "<redacted>"}, expiresInSec=$expiresInSec)"
    }
    sealed class Outcome {
        data class Connected(val tokens: Tokens) : Outcome()
        data class Failed(val why: String) : Outcome()
    }

    /** A form POST: the body of the answer, or an [Http] failure carrying the provider's error body. */
    fun interface Post { fun form(url: String, fields: Map<String, String>): String }
    class Http(val status: Int, val body: String) : Exception("HTTP $status")

    fun parseCode(json: String): Code {
        val o = JSONObject(json)
        return Code(o.getString("device_code"), o.getString("user_code"),
            o.optString("verification_url").ifBlank { o.optString("verification_uri", "https://www.google.com/device") },
            o.optLong("interval", 5), o.optLong("expires_in", 900))
    }

    fun parseTokens(json: String): Tokens {
        val o = JSONObject(json)
        return Tokens(o.getString("access_token"), o.optString("refresh_token").ifBlank { null }, o.optLong("expires_in"))
    }

    /** The provider's "not yet" answers: keep polling. */
    fun stillPending(body: String): Boolean = body.contains("authorization_pending") || body.contains("slow_down")

    fun start(post: Post, spec: Spec, clientId: String): Code = parseCode(post.form(spec.deviceUrl, mapOf("client_id" to clientId, "scope" to spec.scopes)))

    /** The tokens once the person approved, null while pending; any other provider error is thrown ([Http]). */
    fun poll(post: Post, spec: Spec, clientId: String, secret: String, deviceCode: String): Tokens? = try {
        parseTokens(post.form(spec.tokenUrl, mapOf("client_id" to clientId, "client_secret" to secret, "device_code" to deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code")))
    } catch (e: Http) { if (stillPending(e.body)) null else throw e }

    /** Files [t] where Cloud Browser takes it (`music.<id>.access|refresh|expires`); `expires` is an absolute epoch-ms, 0 = never. */
    fun file(vault: AccountVault, spec: Spec, t: Tokens, nowMs: Long) {
        AppCredentialsStore.put(vault, "music.${spec.id}.access", t.access)
        t.refresh?.let { AppCredentialsStore.put(vault, "music.${spec.id}.refresh", it) }
        AppCredentialsStore.put(vault, "music.${spec.id}.expires", if (t.expiresInSec > 0) (nowMs + t.expiresInSec * 1000).toString() else "0")
    }

    /**
     * The whole grant, owner only: asks for a code, reports it ([onCode]: the screen shows it and opens the page), polls until approved or
     * expired, files the tokens. Cancelling the caller's coroutine stops the polling at once.
     */
    suspend fun run(ctx: Context, spec: Spec, post: Post = HttpPost, nowMs: () -> Long = { System.currentTimeMillis() }, onCode: (Code) -> Unit): Outcome {
        if (!SignInOwner.isOwner(ctx)) return Outcome.Failed("the music sign-in runs in Cloud Account only")
        val vault = AccountVault(ctx)
        val id = AppCredentialsStore.get(vault, "music.${spec.id}.client_id")
        val secret = AppCredentialsStore.get(vault, "music.${spec.id}.client_secret")
        if (id.isBlank() || secret.isBlank()) return Outcome.Failed("file the ${spec.label} client id and secret above first")
        return try {
            val code = withContext(Dispatchers.IO) { start(post, spec, id) }
            onCode(code)
            val deadline = nowMs() + code.expiresInSec * 1000
            var every = code.intervalSec * 1000
            while (nowMs() < deadline) {
                delay(every)
                val t = withContext(Dispatchers.IO) { poll(post, spec, id, secret, code.deviceCode) }
                if (t != null) { file(vault, spec, t, nowMs()); return Outcome.Connected(t) }
            }
            Outcome.Failed("the code expired: start again")
        } catch (e: Http) {
            Outcome.Failed("${spec.label} refused (HTTP ${e.status})")
        } catch (e: Exception) {
            Outcome.Failed("${spec.label}: ${e.javaClass.simpleName}")
        }
    }

    private val HttpPost = Post { url, fields ->
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(fields.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }.toByteArray()) }
            val ok = c.responseCode in 200..299
            val body = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (!ok) throw Http(c.responseCode, body)
            body
        } finally { c.disconnect() }
    }
}
