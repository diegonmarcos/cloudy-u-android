package com.diegonmarcos.superapp.decisions.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.diegonmarcos.superapp.decisions.UrlHttp
import com.diegonmarcos.superapp.decisions.core.AnswerCache
import com.diegonmarcos.superapp.decisions.core.CircuitBreaker
import com.diegonmarcos.superapp.decisions.core.ConsentBook
import com.diegonmarcos.superapp.decisions.core.Engine
import com.diegonmarcos.superapp.decisions.core.ImageEngine
import com.diegonmarcos.superapp.decisions.core.Journal
import com.diegonmarcos.superapp.decisions.core.Ledger
import com.diegonmarcos.superapp.decisions.core.Policy
import com.diegonmarcos.superapp.texttools.TextToolsClient
import org.json.JSONObject
import java.io.File

/**
 * The one [Engine] of this process, built on first use from the baked policy (assets/decisions.json) and
 * the engine's own state files. A policy that does not parse leaves no engine at all: every call then
 * answers `no_policy`, and the reason is logged once (never anything that was asked).
 */
object EngineHolder {
    private const val TAG = "DecisionsEngine"
    const val ASSET = "decisions.json"

    @Volatile private var built: Holder? = null

    class Holder(val engine: Engine, val images: ImageEngine, val journal: Journal, val self: String)

    fun get(context: Context): Engine? = holder(context)?.engine

    /** #1799 the image half, built with the same consent book and journal as the decisions. */
    fun images(context: Context): ImageEngine? = holder(context)?.images

    fun holder(context: Context): Holder? {
        built?.let { return it }
        return synchronized(this) { built ?: build(context.applicationContext)?.also { built = it } }
    }

    private fun build(ctx: Context): Holder? {
        val policy = try {
            Policy.parse(JSONObject(ctx.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }))
        } catch (e: Exception) {
            Log.w(TAG, "decisions.json did not load: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (policy.rejected.isNotEmpty()) Log.w(TAG, "uses not served: ${policy.rejected}")
        policy.imageProblem?.let { Log.w(TAG, "image block not served: $it") }
        policy.image?.rejected?.takeIf { it.isNotEmpty() }?.let { Log.w(TAG, "image uses not served: $it") }
        val dir = File(ctx.filesDir, "decisions")
        val clock = System::currentTimeMillis
        val journal = Journal(FileSink(File(dir, "journal.jsonl")), policy.journalMax, clock)
        val consent = ConsentBook(FileKv(File(dir, "consent.json")))
        val engine = Engine(
            policy = policy,
            http = UrlHttp,
            token = { accountToken(ctx, policy.provider) },
            env = AndroidEnv(ctx),
            ledger = Ledger(FileKv(File(dir, "ledger.json")), policy.budget, clock),
            cache = AnswerCache(FileKv(File(dir, "cache.json")), policy.cacheMax, clock),
            breaker = CircuitBreaker(policy.breaker.failures, policy.breaker.openS * 1000, clock),
            consent = consent,
            journal = journal,
            clock = clock,
        )
        // #1799 its own ledger and breaker (an image costs a hundred decisions); the same token, consent and journal.
        val img = policy.image
        val images = ImageEngine(
            policy = img,
            http = UrlHttp,
            token = { accountToken(ctx, policy.provider) },
            env = AndroidEnv(ctx),
            ledger = img?.let { Ledger(FileKv(File(dir, "image-ledger.json")), it.budget, clock) },
            breaker = img?.let { CircuitBreaker(it.breaker.failures, it.breaker.openS * 1000, clock) },
            consent = consent,
            journal = journal,
            clock = clock,
        )
        return Holder(engine, images, journal, ctx.packageName)
    }

    @Volatile private var tools: TextToolsClient? = null

    /** The fleet Account's token, the one the SuperApp's Profile pushes to the text-tools service. It is
     *  returned to the engine's one POST and kept nowhere. */
    private fun accountToken(ctx: Context, provider: String): String? {
        val c = tools ?: synchronized(this) { tools ?: TextToolsClient(ctx).also { tools = it } }
        if (c.isServingAppInstalled()) {
            val until = SystemClock.elapsedRealtime() + ACCOUNT_WAIT_MS
            while (!c.isConnected() && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
        }
        return c.revealAiKey(provider).text?.takeIf { it.isNotBlank() }
    }

    /** How long a first call waits for the text-tools bind before saying "no token". */
    private const val ACCOUNT_WAIT_MS = 3000L
}
