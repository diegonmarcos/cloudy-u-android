package org.fossify.phone.spam

import android.content.Context
import org.fossify.phone.helpers.FleetDataset

/**
 * #1547 the offline spam list is the runtime dataset `spam_patterns` (Cloud Store, repo data/fleet/app/<package>/spam_patterns.json), not an APK asset.
 * Fail-open: empty while it has not been served (never blocks a legit call). Only a non-empty list is cached (process-wide); an empty result is
 * never cached and is retried at most every [RETRY_MS], so the first sync is picked up without a restart.
 */
object SpamRules {
    private const val DATASET = "spam_patterns"
    private const val RETRY_MS = 30_000L
    @Volatile private var cached: List<SpamMatcher.Rule>? = null
    @Volatile private var lastTry = 0L

    fun get(ctx: Context): List<SpamMatcher.Rule> {
        cached?.let { return it }
        val now = System.currentTimeMillis()
        if (now - lastTry < RETRY_MS) return emptyList()
        lastTry = now
        val rules = FleetDataset.get(ctx, DATASET) { SpamMatcher.parse(it) } ?: emptyList()
        if (rules.isNotEmpty()) cached = rules
        return rules
    }
}
