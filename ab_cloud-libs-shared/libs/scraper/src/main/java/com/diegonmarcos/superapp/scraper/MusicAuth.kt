package com.diegonmarcos.superapp.scraper

import android.content.Context
import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** How a service signs in: OAuth with PKCE, OAuth implicit, the OAuth device flow, or the logged-in web player. */
enum class AuthKind { PKCE, IMPLICIT, DEVICE, WEB }

/** The selectors that read a track list off a logged-in web player page (CSS, relative to [row]). */
data class WebSpec(val url: String, val row: String, val title: String, val artist: String, val album: String, val duration: String)

data class ServiceSpec(
    val id: String,
    val label: String,
    val auth: AuthKind,
    val api: String = "",
    val authUrl: String = "",
    val tokenUrl: String = "",
    val deviceUrl: String = "",
    val scopes: String = "",
    val redirect: String = "",
    val needsSecret: Boolean = false,
    val write: Boolean = false,
    val isrc: Boolean = false,
    val help: String = "",
    val web: WebSpec? = null,
)

/** #1459 the declared services: adding one is a JSON entry plus a connector, no UI code. */
object MusicCatalog {
    const val NOTICE = "Reads your own playlists through each service's official API, or from your logged-in web player. " +
        "No DRM is bypassed and no audio is downloaded. Follow each service's terms."

    val DEFAULT_JSON = """
{"services":[
 {"id":"spotify","label":"Spotify","auth":"pkce","api":"https://api.spotify.com/v1",
  "authUrl":"https://accounts.spotify.com/authorize","tokenUrl":"https://accounts.spotify.com/api/token",
  "scopes":"playlist-read-private playlist-read-collaborative user-library-read playlist-modify-private",
  "redirect":"http://127.0.0.1:8765/callback","write":true,"isrc":true,
  "help":"Create an app at developer.spotify.com, add the redirect URI below to it, paste its client id."},
 {"id":"ytmusic","label":"YouTube Music","auth":"device","api":"https://www.googleapis.com/youtube/v3",
  "deviceUrl":"https://oauth2.googleapis.com/device/code","tokenUrl":"https://oauth2.googleapis.com/token",
  "scopes":"https://www.googleapis.com/auth/youtube","needsSecret":true,"write":true,"isrc":false,
  "help":"Google Cloud console: enable YouTube Data API v3, make a TVs and Limited Input OAuth client, paste its id and secret. Matching uses title, artist and length (the API has no ISRC)."},
 {"id":"deezer","label":"Deezer","auth":"implicit","api":"https://api.deezer.com",
  "authUrl":"https://connect.deezer.com/oauth/auth.php","scopes":"basic_access,manage_library,offline_access",
  "redirect":"http://127.0.0.1:8765/callback","write":true,"isrc":true,
  "help":"Create an app at developers.deezer.com, set its redirect to the URI below, paste its app id."},
 {"id":"apple","label":"Apple Music","auth":"web","write":false,"isrc":false,
  "help":"No public sign-in for a phone app: open the web player, sign in, open a playlist and tap Read this page.",
  "web":{"url":"https://music.apple.com/library/playlists","row":".songs-list-row","title":".songs-list-row__song-name",
   "artist":".songs-list-row__by-line","album":".songs-list-row__album-name","duration":".songs-list-row__length"}}
]}"""

    fun parse(json: String): List<ServiceSpec> {
        val arr: JSONArray = JSONObject(json).getJSONArray("services")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val w = o.optJSONObject("web")
            ServiceSpec(
                id = o.getString("id"), label = o.getString("label"), auth = AuthKind.valueOf(o.getString("auth").uppercase()),
                api = o.optString("api"), authUrl = o.optString("authUrl"), tokenUrl = o.optString("tokenUrl"),
                deviceUrl = o.optString("deviceUrl"), scopes = o.optString("scopes"), redirect = o.optString("redirect"),
                needsSecret = o.optBoolean("needsSecret"), write = o.optBoolean("write"), isrc = o.optBoolean("isrc"), help = o.optString("help"),
                web = w?.let { WebSpec(it.getString("url"), it.getString("row"), it.getString("title"), it.optString("artist"), it.optString("album"), it.optString("duration")) },
            )
        }
    }

    val services: List<ServiceSpec> by lazy { parse(DEFAULT_JSON) }
}

interface TokenStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** The only place tokens live on disk: SafeEncryptedPrefs (AES-256), never a plain file; an unreadable store resets to memory, not plaintext. */
class EncryptedTokenStore(ctx: Context, name: String = "music_tokens") : TokenStore {
    private val sp = SafeEncryptedPrefs.prefs(ctx.applicationContext, name)
    override fun get(key: String): String? = sp.getString(key, null)
    override fun put(key: String, value: String) { sp.edit().putString(key, value).apply() }
    override fun remove(key: String) { sp.edit().remove(key).apply() }
}

class MemoryTokenStore : TokenStore {
    private val m = HashMap<String, String>()
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String) { m[key] = value }
    override fun remove(key: String) { m.remove(key) }
}

enum class ConnectStatus(val label: String) {
    CONNECTED("Connected"), EXPIRED("Expired, reconnect"), DISCONNECTED("Not connected"), WEB("Web player")
}

data class TokenSet(val access: String, val refresh: String? = null, val expiresInSec: Long = 0)

/** Per-service client id / secret and tokens over a [TokenStore]. An expiry of 0 means the token does not expire. */
class MusicAccounts(private val store: TokenStore, private val now: () -> Long = { System.currentTimeMillis() }) {
    fun clientId(s: String) = store.get("$s.client_id").orEmpty()
    fun clientSecret(s: String) = store.get("$s.client_secret").orEmpty()
    fun setClient(s: String, id: String, secret: String = "") {
        store.put("$s.client_id", id.trim())
        if (secret.isNotBlank()) store.put("$s.client_secret", secret.trim())
    }

    fun save(s: String, t: TokenSet) {
        store.put("$s.access", t.access)
        t.refresh?.let { store.put("$s.refresh", it) }
        store.put("$s.expires", if (t.expiresInSec > 0) (now() + t.expiresInSec * 1000).toString() else "0")
    }

    fun refreshToken(s: String) = store.get("$s.refresh")

    /** The access token while it is valid, else null. */
    fun access(s: String): String? {
        val a = store.get("$s.access") ?: return null
        val exp = store.get("$s.expires")?.toLongOrNull() ?: 0L
        return a.takeIf { exp == 0L || exp > now() + 30_000 }
    }

    fun status(spec: ServiceSpec): ConnectStatus = when {
        spec.auth == AuthKind.WEB -> ConnectStatus.WEB
        access(spec.id) != null -> ConnectStatus.CONNECTED
        store.get("${spec.id}.access") != null -> ConnectStatus.EXPIRED
        else -> ConnectStatus.DISCONNECTED
    }

    fun disconnect(s: String) { for (k in listOf("access", "refresh", "expires")) store.remove("$s.$k") }
}

data class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val intervalSec: Long, val expiresInSec: Long)

data class Callback(val code: String? = null, val token: String? = null, val expiresInSec: Long = 0, val error: String? = null)

/** The sign-in protocol steps, pure over [MusicHttp] so each is testable. */
object MusicOAuth {
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun verifier(rnd: SecureRandom = SecureRandom()): String = ByteArray(48).also { rnd.nextBytes(it) }.let { b64.encodeToString(it) }

    fun challenge(verifier: String): String = b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authUrl(spec: ServiceSpec, clientId: String, state: String, challenge: String?): String {
        val q = when (spec.auth) {
            AuthKind.PKCE -> mapOf("response_type" to "code", "client_id" to clientId, "redirect_uri" to spec.redirect, "scope" to spec.scopes,
                "state" to state, "code_challenge_method" to "S256", "code_challenge" to requireNotNull(challenge))
            AuthKind.IMPLICIT -> mapOf("app_id" to clientId, "redirect_uri" to spec.redirect, "perms" to spec.scopes, "response_type" to "token", "state" to state)
            else -> throw IllegalArgumentException("${spec.id} has no browser sign-in")
        }
        return spec.authUrl + "?" + MusicHttp.form(q)
    }

    private fun params(s: String): Map<String, String> = s.split('&').filter { it.contains('=') }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    /** The result when [url] is the redirect back to the app (query for a code, fragment for a token), null for any other page. */
    fun callback(url: String, spec: ServiceSpec, state: String): Callback? {
        if (spec.redirect.isEmpty() || !url.startsWith(spec.redirect)) return null
        val rest = url.removePrefix(spec.redirect)
        val p = params(rest.substringBefore('#').removePrefix("?")) + params(rest.substringAfter('#', ""))
        if (p["state"] != null && p["state"] != state) return Callback(error = "state mismatch")
        p["error"]?.let { return Callback(error = it) }
        return Callback(code = p["code"], token = p["access_token"], expiresInSec = p["expires"]?.toLongOrNull() ?: 0)
    }

    private fun tokens(json: String): TokenSet {
        val o = JSONObject(json)
        return TokenSet(o.getString("access_token"), o.optString("refresh_token").ifBlank { null }, o.optLong("expires_in"))
    }

    fun exchange(http: MusicHttp, spec: ServiceSpec, clientId: String, code: String, verifier: String): TokenSet =
        tokens(http.postForm(spec.tokenUrl, mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to spec.redirect,
            "client_id" to clientId, "code_verifier" to verifier)))

    fun refresh(http: MusicHttp, spec: ServiceSpec, clientId: String, secret: String, refreshToken: String): TokenSet {
        val f = mutableMapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken, "client_id" to clientId)
        if (secret.isNotBlank()) f["client_secret"] = secret
        val t = tokens(http.postForm(spec.tokenUrl, f))
        return t.copy(refresh = t.refresh ?: refreshToken)
    }

    fun startDevice(http: MusicHttp, spec: ServiceSpec, clientId: String): DeviceCode {
        val o = JSONObject(http.postForm(spec.deviceUrl, mapOf("client_id" to clientId, "scope" to spec.scopes)))
        return DeviceCode(o.getString("device_code"), o.getString("user_code"),
            o.optString("verification_url").ifBlank { o.optString("verification_uri", "https://www.google.com/device") },
            o.optLong("interval", 5), o.optLong("expires_in", 900))
    }

    /** The tokens once the user approved, null while the grant is still pending. */
    fun pollDevice(http: MusicHttp, spec: ServiceSpec, clientId: String, secret: String, deviceCode: String): TokenSet? = try {
        tokens(http.postForm(spec.tokenUrl, mapOf("client_id" to clientId, "client_secret" to secret, "device_code" to deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code")))
    } catch (e: MusicHttpException) {
        if (e.message.orEmpty().let { it.contains("authorization_pending") || it.contains("slow_down") }) null else throw e
    }
}
