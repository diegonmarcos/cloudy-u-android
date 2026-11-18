package com.diegonmarcos.superapp.decisions.link

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The client of the DECISIONS ENGINE (#881): the OpenRouter token, the budget, the consent, the answer
 * cache and the call itself live in Cloud-Lib-Decisions-Engine.apk, so an app that asks a decision model
 * something holds NO KEY and an edit to how it is asked republishes that APK and no app.
 *
 *     val v = DecisionsLink.decide(context, "writer_route", state, questions) ?: return todaysBehaviour()
 *
 * ABSENT, OLD, REFUSED OR BROKEN ENGINE = NO OPINION. [decide] answers null for everything that is not an
 * answer (no engine installed, a contract below the needed one, no consent, offline, over budget, a
 * timeout, a malformed reply) and never throws; [outcome] keeps the reason for a screen that wants to say
 * why. The app's own path is the fallback, always, and a use's static deny list runs before this call.
 *
 * THE HANDSHAKE COMES FIRST AND COSTS NO BIND: it resolves the declared action in the declared package
 * (engine-client.json, resolved from the fleet manifest at build time and queried in this module's
 * manifest) and reads the CONTRACT it declares. THE ENGINE IS BOUND ONLY FOR THE CALL.
 *
 * Blocking: call from a background thread.
 */
object DecisionsLink {

    /** Ask [use] about [state] ([state] is any JSON value: object, array or string). */
    fun decide(context: Context, use: String, state: Any?, questions: JSONObject): Verdict? =
        outcome(context, use, state, questions).verdictOrNull

    fun outcome(context: Context, use: String, state: Any?, questions: JSONObject): Outcome {
        val request = JSONObject().put("use", use).put("state", state ?: JSONObject.NULL).put("questions", questions)
        return Outcome.parse(ask("decide", context, request))
    }

    /**
     * Report what really happened to the decision [call] ([Outcome.callOrNull] of the [outcome] that asked), read
     * from the observed result: [label] is the option the user ended up with, true/false for a yes/no, or a
     * JSONObject {question: label}. The engine joins it to its answer, which is how a use's precision, and the
     * gates that wait on it, are measured without a manual label. Never throws; blocking.
     */
    fun report(context: Context, use: String, call: String, label: Any): Boolean =
        ask("outcome", context, JSONObject().put("use", use).put("call", call).put("label", label)).optBoolean("ok", false)

    /** What the engine reports about itself and the calling app's uses: names and flags, never content. */
    fun status(context: Context): JSONObject = ask("status", context, JSONObject())

    /**
     * Record the user's choice for [app]'s [use]. Only the app the engine's policy names as a consent
     * setter (the one hosting the switch) is obeyed; for anyone else the answer is {ok:false}.
     */
    fun setConsent(context: Context, app: String, use: String, granted: Boolean): JSONObject =
        ask("consent", context, JSONObject().put("app", app).put("use", use).put("granted", granted))

    /**
     * #1799 one image call of the declared image [use]: [picture] and [prompt] to [model], a picture back, with the key
     * held by the engine (contract 2). The pictures travel as files in this app's cache, never in the binder buffer.
     */
    fun image(context: Context, use: String, model: String, prompt: String, picture: ByteArray): ImageReply {
        val ctx = context.applicationContext
        val link = Conn()
        val dir = File(ctx.cacheDir, "decisions-image").apply { mkdirs() }
        // createTempFile refuses a prefix under three characters (IllegalArgumentException).
        val src = File.createTempFile("image-in", ".img", dir)
        val out = File.createTempFile("image-out", ".img", dir)
        return try {
            src.writeBytes(picture)
            val why = link.check(ctx, BuildConfig.DECISIONS_IMAGE_CONTRACT)
            val r = link.remote
            if (why != null || r == null) return ImageReply.parse(fail(why ?: "the decisions engine is not ready"), null)
            val request = JSONObject().put("use", use).put("model", model).put("prompt", prompt).toString()
            val answer = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY).use { i ->
                ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE).use { o -> r.image(request, i, o) }
            }
            val reply = answer?.let { runCatching { JSONObject(it) }.getOrNull() } ?: fail("the decisions engine answered image with something that is not JSON")
            ImageReply.parse(reply, if (reply.optBoolean("ok")) out.readBytes() else null)
        } catch (e: Exception) {
            ImageReply.parse(fail(e.message ?: e.javaClass.simpleName), null)
        } finally {
            link.release(ctx)
            src.delete()
            out.delete()
        }
    }

    /**
     * #1801 one relayed provider call of the declared relay [use]: [body] is the request this app used to POST itself (an
     * OpenAI-shape chat body or a Decisions API request) to the [endpoint] NAME its provider declares ("chat", "decisions"),
     * and the reply carries the provider's status code and answer body untouched, so the app parses and words it as before.
     * [get] true sends no body (a provider's key-usage read: Calc's Configs › Token › Test).
     * The ENGINE adds the key (contract 3): this app never holds, reads, stores or sends it. The body and the answer travel
     * as files in this app's cache, never in the binder buffer. Never throws; blocking.
     */
    fun relay(context: Context, use: String, endpoint: String, body: String, get: Boolean = false): RelayReply {
        val ctx = context.applicationContext
        val link = Conn()
        val dir = File(ctx.cacheDir, "decisions-relay").apply { mkdirs() }
        val src = File.createTempFile("relay-in", ".json", dir)
        val out = File.createTempFile("relay-out", ".json", dir)
        return try {
            src.writeText(body)
            val why = link.check(ctx, BuildConfig.DECISIONS_RELAY_CONTRACT)
            val r = link.remote
            if (why != null || r == null) return RelayReply.parse(fail(why ?: "the decisions engine is not ready"), null)
            val request = JSONObject().put("use", use).put("endpoint", endpoint).apply { if (get) put("method", "GET") }.toString()
            val answer = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY).use { i ->
                ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE).use { o -> r.relay(request, i, o) }
            }
            val reply = answer?.let { runCatching { JSONObject(it) }.getOrNull() } ?: fail("the decisions engine answered relay with something that is not JSON")
            RelayReply.parse(reply, if (reply.optBoolean("ok")) out.readText() else null)
        } catch (e: Exception) {
            RelayReply.parse(fail(e.message ?: e.javaClass.simpleName), null)
        } finally {
            link.release(ctx)
            src.delete()
            out.delete()
        }
    }

    /**
     * #1801 which provider keys the engine holds ({ok, providers:[{id, label, key_present, key_hint, overrides:[{app,
     * key_present, key_hint}]}], settings_action}): presence and the last four characters, NEVER a key. {ok:false, reason}
     * when no contract-3 engine answers. Blocking.
     */
    fun keys(context: Context): JSONObject = ask("keys", context, JSONObject(), BuildConfig.DECISIONS_KEYS_CONTRACT)

    /**
     * #1801 the engine's AI keys screen, where the fleet's provider keys (and a per-app override for [app]) are entered: what
     * every consumer's settings link to, now that no app holds a key. [provider] preselects a provider. The activity is the
     * engine's own (signature-guarded), so the key is typed into the engine's process and never into this app's.
     */
    fun keySettings(provider: String? = null, app: String? = null): Intent =
        Intent(BuildConfig.DECISIONS_ENGINE_PACKAGE + KEYS_ACTION_SUFFIX).setPackage(BuildConfig.DECISIONS_ENGINE_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { provider?.let { putExtra(EXTRA_PROVIDER, it) }; app?.let { putExtra(EXTRA_APP, it) } }

    /** Open [keySettings]; false (and nothing opened) when no engine with the screen is installed. */
    fun openKeySettings(context: Context, provider: String? = null, app: String? = null): Boolean = runCatching {
        val i = keySettings(provider, app)
        if (context.packageManager.resolveActivity(i, 0) == null) return false
        context.startActivity(i)
        true
    }.getOrDefault(false)

    /** The action suffix and extras of the engine's AI keys screen (libs/decisions-engine KeysActivity). */
    const val KEYS_ACTION_SUFFIX = ".AI_KEYS"
    const val EXTRA_PROVIDER = "provider"
    const val EXTRA_APP = "app"

    private fun ask(method: String, context: Context, request: JSONObject, needed: Int = BuildConfig.DECISIONS_ENGINE_MIN_CONTRACT): JSONObject {
        val ctx = context.applicationContext
        val link = Conn()
        return try {
            val why = link.check(ctx, needed)
            val r = link.remote
            if (why != null || r == null) return fail(why ?: "the decisions engine is not ready")
            val answer = r.call(method, request.toString()) ?: return fail("the decisions engine did not answer $method")
            runCatching { JSONObject(answer) }.getOrNull() ?: fail("the decisions engine answered $method with something that is not JSON")
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        } finally {
            link.release(ctx)
        }
    }

    private fun fail(why: String) = JSONObject().put("ok", false).put("reason", Outcome.NO_ENGINE).put("detail", why)

    private class Conn : ServiceConnection {
        @Volatile var remote: IDecisionsEngine? = null
        @Volatile private var latch: CountDownLatch? = null
        @Volatile private var bound = false

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = binder?.takeIf { it.pingBinder() }?.let { IDecisionsEngine.Stub.asInterface(it) }
            latch?.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { remote = null }

        /** Null when an engine serving contract [needed] is ready (and bound), else the sentence that says what to do. */
        fun check(ctx: Context, needed: Int = BuildConfig.DECISIONS_ENGINE_MIN_CONTRACT): String? {
            val pm = ctx.packageManager
            val pkg = BuildConfig.DECISIONS_ENGINE_PACKAGE
            val service = pm.resolveService(Intent(BuildConfig.DECISIONS_ENGINE_ACTION).setPackage(pkg), PackageManager.GET_META_DATA)
                ?.serviceInfo
            if (service == null) {
                val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
                return if (installed) "$pkg has no decisions service — update it from Store ▸ Cloud Constellation ▸ Libs"
                       else "the decisions engine $pkg is not installed — install it from Store ▸ Cloud Constellation ▸ Libs"
            }
            val found = service.metaData?.getInt(CONTRACT_KEY, 0) ?: 0
            if (found < needed) return "$pkg serves contract $found, this build needs $needed — update it from Store ▸ Cloud Constellation ▸ Libs"
            val l = CountDownLatch(1)
            latch = l
            bound = runCatching {
                ctx.bindService(Intent().setClassName(service.packageName, service.name), this, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) return "$pkg did not accept the bind"
            runCatching { l.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            return if (remote != null) null else "$pkg did not accept the bind"
        }

        fun release(ctx: Context) {
            if (bound) runCatching { ctx.unbindService(this) }
            bound = false
            remote = null
        }
    }

    /** The engine CONTRACT meta-data key (libs/decisions-engine's manifest). */
    private const val CONTRACT_KEY = "com.diegonmarcos.cloud.engine.CONTRACT"

    /** Long enough for a cold engine process to start; the caller is on a worker thread. */
    private const val BIND_TIMEOUT_MS = 4000L
}
