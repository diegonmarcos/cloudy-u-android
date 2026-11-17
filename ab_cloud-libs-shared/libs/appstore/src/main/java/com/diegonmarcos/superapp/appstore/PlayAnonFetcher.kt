package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.util.Base64
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.apk.VerifiedApk
import com.diegonmarcos.superapp.updater.cache.ApkCache
import com.diegonmarcos.superapp.updater.source.Download
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Properties

/**
 * The `play-anon` rung: install an app from GOOGLE PLAY'S OWN SERVERS with an
 * anonymous Play session, the way Aurora Store does — no Google account on the
 * phone, no Play app, no Aurora app.
 *
 * The protocol, re-implemented here from what AuroraOSS/gplayapi (GPL-3.0, see
 * ab_cloud-libs-shared/libs/gplayapi) does, with the project's own HTTP client
 * and a forty-line protobuf reader instead of the generated classes:
 *
 *  1. TOKEN — POST the declared device profile (gplayapi's own .properties,
 *     vendored) as JSON to the first declared dispenser that answers. A
 *     dispenser hands back a ready AuthData: an anonymous account's auth token
 *     plus the gsfId, check-in consistency token, device config token and DFE
 *     cookie that account was registered with. Cached for `token_ttl_minutes`.
 *  2. DETAILS  — GET  {api}details?doc=pkg  → versionCode, offerType.
 *  3. PURCHASE — POST {api}purchase (ot, doc, vc) → the delivery token. Free
 *     apps only; a paid app has no free offer and fails here, said as such.
 *  4. DELIVERY — GET  {api}delivery → base APK URL + every split's URL, each
 *     with a sha256, all on Google's own download CDN.
 *
 * HONESTY RULES, enforced in code and mutation-tested by the Store testers:
 *  - Every file is verified against the sha256 the delivery response gives,
 *    by [VerifiedApk.byDigest]. No sha in the response = REFUSED; this rung
 *    never falls back to "structure only".
 *  - A dispenser outage is [TokenUnavailable], whose message starts with
 *    [TOKEN_UNAVAILABLE] — "Play token unavailable" — and never reads as
 *    "not installable": the app IS installable, the key to the door is not
 *    being handed out right now.
 *  - No dispenser URL lives in this file. They are declared, in order, in
 *    `resolver.play_anon.dispensers` of the install-source map.
 */
object PlayAnonFetcher {

    const val TOKEN_UNAVAILABLE = "Play token unavailable"

    class TokenUnavailable(why: String) : IllegalStateException("$TOKEN_UNAVAILABLE — $why")

    class Dispenser(val url: String, val userAgent: String)

    class Config(
        val api: String,
        val dispensers: List<Dispenser>,
        val deviceProfile: String,
        val tokenTtlMinutes: Long,
        val locale: String,
    )

    fun config(o: JSONObject): Config {
        val arr = o.getJSONArray("dispensers")
        val ds = (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            Dispenser(d.getString("url"), d.getString("user_agent"))
        }
        require(ds.isNotEmpty()) { "play_anon.dispensers is empty: a play-anon rung with no dispenser can never get a token" }
        ds.forEach { require(it.url.startsWith("https://")) { "dispenser is not https: ${it.url}" } }
        val api = o.getString("api")
        require(api.startsWith("https://") && api.endsWith("/")) { "play_anon.api must be an https base ending in '/': $api" }
        return Config(api, ds, o.getString("device_profile"), o.getLong("token_ttl_minutes"),
            o.optString("locale").ifEmpty { "en_US" })
    }

    // ── token ────────────────────────────────────────────────────────────

    class Auth(
        val authToken: String, val gsfId: String, val checkinToken: String,
        val configToken: String, val dfeCookie: String, val at: Long,
    )

    @Volatile private var cached: Auth? = null
    private const val PREFS = "store_play_anon"

    fun profile(ctx: Context, cfg: Config): Properties =
        Properties().apply { ctx.assets.open(cfg.deviceProfile).use { load(it) } }

    /** The cached session while it is younger than the declared TTL, else a fresh one. */
    fun token(ctx: Context, cfg: Config, fresh: Boolean = false): Auth {
        val ttl = cfg.tokenTtlMinutes * 60_000L
        val now = System.currentTimeMillis()
        if (!fresh) {
            cached?.takeIf { now - it.at < ttl }?.let { return it }
            stored(ctx)?.takeIf { now - it.at < ttl }?.let { cached = it; return it }
        }
        val body = JSONObject().apply {
            val p = profile(ctx, cfg); p.stringPropertyNames().forEach { put(it, p.getProperty(it)) }
        }.toString().toByteArray()
        val declined = mutableListOf<String>()
        for (d in cfg.dispensers) {
            try {
                val c = URL(d.url).openConnection() as HttpURLConnection
                try {
                    c.requestMethod = "POST"; c.doOutput = true
                    c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
                    c.setRequestProperty("Content-Type", "application/json")
                    c.setRequestProperty("User-Agent", d.userAgent)
                    c.outputStream.use { it.write(body) }
                    val code = c.responseCode
                    if (code !in 200..299) { declined += "${host(d.url)} answered HTTP $code"; continue }
                    val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    val a = Auth(
                        authToken = j.optString("authToken"), gsfId = j.optString("gsfId"),
                        checkinToken = j.optString("deviceCheckInConsistencyToken"),
                        configToken = j.optString("deviceConfigToken"), dfeCookie = j.optString("dfeCookie"),
                        at = now,
                    )
                    if (a.authToken.isEmpty() || a.gsfId.isEmpty()) {
                        declined += "${host(d.url)} answered without an auth token / gsfId"; continue
                    }
                    cached = a; store(ctx, a)
                    return a
                } finally { c.disconnect() }
            } catch (t: Throwable) {
                // Never echo a body or a header here: only the host and the failure.
                declined += "${host(d.url)} → ${t.javaClass.simpleName}: ${t.message}"
            }
        }
        throw TokenUnavailable("no declared dispenser handed out an anonymous session (" +
            declined.joinToString(" | ") + "). The app is still installable from Play; retry later")
    }

    private fun host(url: String) = runCatching { URL(url).host }.getOrDefault(url)

    private fun stored(ctx: Context): Auth? = runCatching {
        val j = JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("auth", null) ?: return null)
        Auth(j.getString("a"), j.getString("g"), j.getString("c"), j.getString("d"), j.getString("k"), j.getLong("t"))
    }.getOrNull()

    private fun store(ctx: Context, a: Auth) {
        val j = JSONObject().put("a", a.authToken).put("g", a.gsfId).put("c", a.checkinToken)
            .put("d", a.configToken).put("k", a.dfeCookie).put("t", a.at)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("auth", j.toString()).apply()
    }

    // ── fdfe calls ───────────────────────────────────────────────────────

    /** gplayapi's Android-Finsky user agent, built from the declared profile. */
    fun userAgent(p: Properties): String {
        val abis = p.getProperty("Platforms", "").split(',').joinToString(";")
        return "Android-Finsky/${p.getProperty("Vending.versionString")} (api=3," +
            "versionCode=${p.getProperty("Vending.version")},sdk=${p.getProperty("Build.VERSION.SDK_INT")}," +
            "device=${p.getProperty("Build.DEVICE")},hardware=${p.getProperty("Build.HARDWARE")}," +
            "product=${p.getProperty("Build.PRODUCT")},platformVersionRelease=${p.getProperty("Build.VERSION.RELEASE")}," +
            "model=${p.getProperty("Build.MODEL")},buildId=${p.getProperty("Build.ID")},isWideScreen=0," +
            "supportedAbis=$abis)"
    }

    private fun headers(a: Auth, p: Properties, locale: String) = mapOf(
        "Authorization" to "Bearer ${a.authToken}",
        "User-Agent" to userAgent(p),
        "X-DFE-Device-Id" to a.gsfId,
        "Accept-Language" to locale.replace('_', '-'),
        "X-DFE-UserLanguages" to locale,
        "X-DFE-Client-Id" to "am-android-google",
        "X-DFE-Network-Type" to "4",
        "X-DFE-Request-Params" to "timeoutMs=4000",
        "X-DFE-Device-Checkin-Consistency-Token" to a.checkinToken,
        "X-DFE-Device-Config-Token" to a.configToken,
        "X-DFE-Cookie" to a.dfeCookie,
    ).filterValues { it.isNotEmpty() }

    private fun call(url: String, h: Map<String, String>, form: Map<String, String>? = null): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
            for ((k, v) in h) c.setRequestProperty(k, v)
            if (form != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                c.outputStream.use { it.write(query(form).toByteArray()) }
            }
            val code = c.responseCode
            // 401 = the anonymous session expired or was revoked: the TOKEN is
            // what failed, so it is reported as the token, not as the app.
            if (code == 401) throw TokenUnavailable("Play refused the cached anonymous session (HTTP 401)")
            if (code !in 200..299) error("Play answered HTTP $code for ${url.substringBefore('?')}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private fun query(m: Map<String, String>) =
        m.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }

    class Details(val versionCode: Long, val versionName: String?, val offerType: Long)

    /** Play's current version of [pkg] — the version probe behind the row badges. */
    fun details(ctx: Context, cfg: Config, pkg: String): Details = withSession(ctx, cfg) { a, p ->
        val r = call("${cfg.api}details?" + query(mapOf("doc" to pkg)), headers(a, p, cfg.locale))
        val item = Proto.get(r, 1, 2, 4) ?: error("$pkg is not on Google Play (details answered no item)")
        val ad = Proto.get(item, 13, 1) ?: error("$pkg: Play's details carry no app details")
        val vc = Proto.varint(ad, 3) ?: error("$pkg: Play's details carry no versionCode")
        val ot = Proto.get(item, 8)?.let { Proto.varint(it, 8) } ?: 1L
        Details(vc, Proto.get(ad, 4)?.decodeToString(), ot)
    }

    /** One app in Play's catalogue, as search returns it. */
    class Hit(val pkg: String, val title: String, val creator: String, val versionCode: Long?)

    /** Play's catalogue search, as Aurora does it (`search?c=3&q=`): every app document in the answer,
     *  found by walking it (the list nesting changes between Play versions; the document does not). */
    fun search(ctx: Context, cfg: Config, query: String): List<Hit> = withSession(ctx, cfg) { a, p ->
        val r = call("${cfg.api}search?" + query(mapOf("c" to "3", "q" to query, "ksm" to "1")), headers(a, p, cfg.locale))
        Proto.appDocs(r).mapNotNull { d ->
            val pkg = Proto.get(d, 1)?.decodeToString() ?: return@mapNotNull null
            Hit(pkg, Proto.get(d, 5)?.decodeToString() ?: pkg, Proto.get(d, 6)?.decodeToString().orEmpty(),
                Proto.get(d, 13, 1)?.let { Proto.varint(it, 3) })
        }.distinctBy { it.pkg }
    }

    class PlayFile(val name: String, val url: String, val size: Long, val sha256: String?)

    /** details → purchase → delivery: the files Play would install, each with its sha256. */
    fun delivery(ctx: Context, cfg: Config, pkg: String): List<PlayFile> {
        val d = details(ctx, cfg, pkg)
        return withSession(ctx, cfg) { a, p ->
            val h = headers(a, p, cfg.locale)
            val base = mapOf("ot" to d.offerType.toString(), "doc" to pkg, "vc" to d.versionCode.toString())
            val buy = call("${cfg.api}purchase", h, base)
            val dtok = Proto.get(buy, 1, 4, 55)?.decodeToString()
            val q = if (dtok.isNullOrEmpty()) base else base + ("dtok" to dtok)
            val r = call("${cfg.api}delivery?" + query(q), h)
            val dr = Proto.get(r, 1, 21) ?: error("$pkg: Play's delivery answered no delivery response")
            when (val status = Proto.varint(dr, 1) ?: 1L) {
                1L -> Unit
                2L, 9L -> error("$pkg is not supported on the declared device profile (delivery status $status)")
                3L -> error("$pkg is not free: Play will not deliver it without a purchase (delivery status 3)")
                7L -> error("$pkg was removed from Google Play (delivery status 7)")
                else -> error("$pkg: Play's delivery answered status $status")
            }
            val dd = Proto.get(dr, 2) ?: error("$pkg: the delivery carries no app delivery data")
            val files = mutableListOf(PlayFile(
                "base", Proto.get(dd, 3)?.decodeToString() ?: error("$pkg: the delivery names no base APK URL"),
                Proto.varint(dd, 1) ?: -1L, Proto.get(dd, 19)?.let { hex(it) },
            ))
            for (s in Proto.all(dd, 15)) files += PlayFile(
                Proto.get(s, 1)?.decodeToString() ?: "split${files.size}",
                Proto.get(s, 5)?.decodeToString() ?: error("$pkg: a split names no URL"),
                Proto.varint(s, 2) ?: -1L, Proto.get(s, 9)?.let { hex(it) },
            )
            files
        }
    }

    /** Play gives sha256 as URL-safe base64 of the raw digest; [VerifiedApk.byDigest] wants hex. */
    private fun hex(b64: ByteArray): String? = runCatching {
        Base64.decode(b64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            .joinToString("") { "%02x".format(it) }.takeIf { it.length == 64 }
    }.getOrNull()

    /** One retry with a FRESH token when the cached one was refused. */
    private fun <T> withSession(ctx: Context, cfg: Config, block: (Auth, Properties) -> T): T {
        val p = profile(ctx, cfg)
        return try { block(token(ctx, cfg), p) } catch (t: TokenUnavailable) {
            if (t.message?.contains("HTTP 401") != true) throw t
            block(token(ctx, cfg, fresh = true), p)
        }
    }

    // ── download ─────────────────────────────────────────────────────────

    /**
     * Blocking. The base APK with every split attached, EACH verified against
     * the sha256 Play's delivery gave for it, or a throw naming why.
     */
    fun fetch(ctx: Context, cfg: Config, pkg: String): VerifiedApk {
        UpdateProgress.update(UpdateProgress.State.CheckingManifest)
        val files = delivery(ctx, cfg, pkg)
        val total = files.sumOf { maxOf(it.size, 0L) }
        var done = 0L
        val verified = files.map { f ->
            val digest = f.sha256
                ?: error("$pkg: Play's delivery gave no sha256 for ${f.name} — refusing bytes that cannot be checked")
            require(f.url.startsWith("https://")) { "refusing a non-https Play download for ${f.name}" }
            val target = ApkCache.file(ctx, "playanon-$pkg-${f.name}.apk")
            Download.toFile(url = f.url, target = target, expectedBytes = f.size,
                shouldCancel = { UpdateProgress.cancelRequested }) { written, _ ->
                val pct = if (total > 0) (((done + written) * 100) / total).toInt().coerceIn(0, 100) else 0
                UpdateProgress.update(UpdateProgress.State.Downloading(pct, done + written, total))
            }
            done += target.length()
            verify(target, digest) ?: run {
                ApkCache.drop(target)
                error("$pkg: ${f.name} failed verification against Play's sha256 $digest")
            }
        }
        verified.drop(1).forEach { runCatching { ApkCache.keep(ctx, it.file) } }
        return verified.first().withSplits(verified.drop(1))
    }

    /** The ONE verification this rung accepts: a digest match. Never structural. */
    fun verify(file: java.io.File, sha256: String): VerifiedApk? = VerifiedApk.byDigest(file, sha256)

    /** Dispenser list as declared, for the debug API — urls only, never a token. */
    fun describe(cfg: Config): JSONObject = JSONObject().put("api", cfg.api)
        .put("dispensers", JSONArray(cfg.dispensers.map { it.url }))
        .put("device_profile", cfg.deviceProfile).put("token_ttl_minutes", cfg.tokenTtlMinutes)

    private const val TIMEOUT_MS = 30_000

    /** Just enough of the protobuf wire format to walk Play's responses. */
    internal object Proto {
        private class Field(val no: Int, val bytes: ByteArray?, val num: Long?)

        private fun fields(b: ByteArray): List<Field> {
            val out = ArrayList<Field>(); var i = 0
            fun varint(): Long { var r = 0L; var s = 0
                while (true) { val c = b[i++].toInt() and 0xff; r = r or ((c and 0x7f).toLong() shl s); s += 7; if (c < 0x80) return r } }
            while (i < b.size) {
                val key = varint(); val no = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> out += Field(no, null, varint())
                    1 -> { var v = 0L; for (k in 0 until 8) v = v or ((b[i + k].toLong() and 0xff) shl (8 * k)); i += 8; out += Field(no, null, v) }
                    2 -> { val n = varint().toInt(); out += Field(no, b.copyOfRange(i, i + n), null); i += n }
                    5 -> { var v = 0L; for (k in 0 until 4) v = v or ((b[i + k].toLong() and 0xff) shl (8 * k)); i += 4; out += Field(no, null, v) }
                    else -> error("unsupported protobuf wire type in Play's answer")
                }
            }
            return out
        }

        fun get(b: ByteArray, vararg path: Int): ByteArray? {
            var cur = b
            for (n in path) cur = fields(cur).firstOrNull { it.no == n && it.bytes != null }?.bytes ?: return null
            return cur
        }

        fun all(b: ByteArray, no: Int): List<ByteArray> = fields(b).filter { it.no == no }.mapNotNull { it.bytes }

        fun varint(b: ByteArray, no: Int): Long? = fields(b).firstOrNull { it.no == no && it.num != null }?.num

        private val PKG = Regex("^[A-Za-z][\\w]*(\\.[A-Za-z_][\\w]*)+$")

        /** Every app document anywhere in [b]: a message whose field 1 is a package name and that carries
         *  details (field 13). Bytes that do not parse as a message are skipped, never thrown on. */
        fun appDocs(b: ByteArray, depth: Int = 0, out: MutableList<ByteArray> = ArrayList()): List<ByteArray> {
            if (depth > 12) return out
            val fs = runCatching { fields(b) }.getOrNull() ?: return out
            val id = fs.firstOrNull { it.no == 1 && it.bytes != null }?.bytes
                ?.let { runCatching { it.decodeToString() }.getOrNull() }
            if (id != null && PKG.matches(id) && fs.any { it.no == 13 && it.bytes != null }) { out += b; return out }
            for (f in fs) f.bytes?.takeIf { it.size > 8 }?.let { appDocs(it, depth + 1, out) }
            return out
        }
    }
}
