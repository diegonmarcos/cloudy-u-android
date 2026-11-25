package com.diegonmarcos.superapp.decisions.engine

import android.content.Context
import android.util.Log
import com.diegonmarcos.superapp.decisions.UrlHttp
import com.diegonmarcos.superapp.decisions.core.AnswerCache
import com.diegonmarcos.superapp.decisions.core.CircuitBreaker
import com.diegonmarcos.superapp.decisions.core.ConsentBook
import com.diegonmarcos.superapp.decisions.core.Engine
import com.diegonmarcos.superapp.decisions.core.ImageEngine
import com.diegonmarcos.superapp.decisions.core.Journal
import com.diegonmarcos.superapp.decisions.core.KeyBook
import com.diegonmarcos.superapp.decisions.core.Ledger
import com.diegonmarcos.superapp.decisions.core.Policy
import com.diegonmarcos.superapp.decisions.core.RelayEngine
import com.diegonmarcos.superapp.fleetdata.FleetData
import org.json.JSONObject
import java.io.File

/**
 * The one [Engine] of this process (#1801: with its [RelayEngine] and the [KeyBook] that holds the fleet's AI keys), built on first use from the served policy (fleet/decisions, last-known-good; not served = no engine, retried on the next call) and
 * the engine's own state files. A policy that does not parse leaves no engine at all: every call then
 * answers `no_policy`, and the reason is logged once (never anything that was asked).
 */
object EngineHolder {
    private const val TAG = "DecisionsEngine"
    /** #1566 the engine declaration is a fleet-private served dataset (Cloud Account's private route), never an APK asset. */
    const val DATASET = "fleet/decisions"

    @Volatile private var built: Holder? = null

    class Holder(val engine: Engine, val images: ImageEngine, val relay: RelayEngine, val keys: KeyBook, val journal: Journal, val self: String)

    fun get(context: Context): Engine? = holder(context)?.engine

    /** #1799 the image half, built with the same consent book and journal as the decisions. */
    fun images(context: Context): ImageEngine? = holder(context)?.images

    /** #1801 the relay: an app's request body to a declared provider endpoint, with the key added here. */
    fun relay(context: Context): RelayEngine? = holder(context)?.relay

    /**
     * #1801 THE fleet's AI provider keys, held in this process only. Built even when the policy does not load, so the AI
     * keys screen can always set or clear a key.
     */
    fun keys(context: Context): KeyBook = holder(context)?.keys ?: keyBook(context.applicationContext)

    @Volatile private var book: KeyBook? = null

    private fun keyBook(ctx: Context): KeyBook = book ?: synchronized(this) {
        book ?: KeyBook(SealedKv(File(File(ctx.filesDir, "decisions"), "keys.sealed"))).also { book = it }
    }

    fun holder(context: Context): Holder? {
        built?.let { return it }
        return synchronized(this) { built ?: build(context.applicationContext)?.also { built = it } }
    }

    private fun build(ctx: Context): Holder? {
        val policy = try {
            Policy.parse(JSONObject(FleetData.get(ctx, DATASET) { it }
                ?: run { Log.w(TAG, "decisions is not served (no engine until it is)"); return null }))
        } catch (e: Exception) {
            Log.w(TAG, "fleet/decisions did not load: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (policy.rejected.isNotEmpty()) Log.w(TAG, "uses not served: ${policy.rejected}")
        policy.imageProblem?.let { Log.w(TAG, "image block not served: $it") }
        policy.image?.rejected?.takeIf { it.isNotEmpty() }?.let { Log.w(TAG, "image uses not served: $it") }
        val dir = File(ctx.filesDir, "decisions")
        val clock = System::currentTimeMillis
        val journal = Journal(FileSink(File(dir, "journal.jsonl")), policy.journalMax, clock)
        val consent = ConsentBook(FileKv(File(dir, "consent.json")))
        val keys = keyBook(ctx)
        val engine = Engine(
            policy = policy,
            http = UrlHttp,
            token = { keys.get(policy.provider) },
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
            token = { keys.get(policy.provider) },
            env = AndroidEnv(ctx),
            ledger = img?.let { Ledger(FileKv(File(dir, "image-ledger.json")), it.budget, clock) },
            breaker = img?.let { CircuitBreaker(it.breaker.failures, it.breaker.openS * 1000, clock) },
            consent = consent,
            journal = journal,
            clock = clock,
        )
        // #1801 the relay: its own ledger and breaker; the same key book, consent and journal.
        val rel = policy.relay
        policy.relayProblem?.let { Log.w(TAG, "relay block not served: $it") }
        rel?.rejected?.takeIf { it.isNotEmpty() }?.let { Log.w(TAG, "relay uses not served: $it") }
        val relay = RelayEngine(
            policy = rel,
            http = UrlHttp,
            keys = keys,
            env = AndroidEnv(ctx),
            ledger = rel?.let { Ledger(FileKv(File(dir, "relay-ledger.json")), it.budget, clock) },
            breaker = rel?.let { CircuitBreaker(it.breaker.failures, it.breaker.openS * 1000, clock) },
            consent = consent,
            journal = journal,
            clock = clock,
        )
        return Holder(engine, images, relay, keys, journal, ctx.packageName)
    }
}
