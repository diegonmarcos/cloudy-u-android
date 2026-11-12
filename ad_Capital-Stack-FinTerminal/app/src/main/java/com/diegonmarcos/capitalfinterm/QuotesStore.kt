package com.diegonmarcos.capitalfinterm

import android.content.Context
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #1539 where the price boards read their quotes: the baked snapshot (data/quotes-snapshot.json) with the quotes the last refresh got over it (the later timestamp wins).
 * A refresh runs when a page opens or is pulled, never on a timer; a symbol asked within the declared TTL is not asked again. Only the source's own answers are kept: a symbol
 * that does not answer stays as it was (the baked quote with its timestamp, or n/a).
 */
object QuotesStore {
    const val DECL = "quotes.json"
    const val SNAPSHOT = "quotes-snapshot.json"

    private val live = ConcurrentHashMap<String, Quote>()
    private val askedAt = ConcurrentHashMap<String, Long>()

    fun decl(ctx: Context): QuotesDecl = QuotesDecl.parse(AssetData.read(ctx, DECL))

    fun snapshot(ctx: Context): QuotesSnapshot = QuotesSnapshot.parse(AssetData.read(ctx, SNAPSHOT)).overlay(live)

    /** #1663 the quotes the source answered with in this process, by symbol (the index holdings: nothing baked stands behind them). */
    fun liveQuotes(): Map<String, Quote> = HashMap(live)

    /** Asks the source for [symbols] (those not asked within the TTL unless [force]); returns how many answered. Blocking: call off the main thread. */
    fun refresh(decl: QuotesDecl, symbols: List<String>, force: Boolean, http: Http = UrlHttp(), now: () -> Long = System::currentTimeMillis): Int {
        val t = now()
        val todo = symbols.distinct().filter { force || t - (askedAt[it] ?: 0L) >= decl.ttlSeconds * 1000L }
        if (todo.isEmpty()) return 0
        val pool = Executors.newFixedThreadPool(6)
        val answered = java.util.concurrent.atomic.AtomicInteger()
        todo.forEach { s ->
            pool.execute {
                val url = decl.source.url.replace("{symbol}", URLEncoder.encode(s, "UTF-8"))
                val q = runCatching { Quotes.parseChart(http.send(url, mapOf("User-Agent" to decl.source.userAgent), null)) }.getOrNull()
                askedAt[s] = t
                if (q != null) { live[s] = q; answered.incrementAndGet() }
            }
        }
        pool.shutdown(); pool.awaitTermination(2, TimeUnit.MINUTES)
        return answered.get()
    }
}
