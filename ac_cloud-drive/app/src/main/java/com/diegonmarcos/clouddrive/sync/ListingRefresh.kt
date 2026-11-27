package com.diegonmarcos.clouddrive.sync

/**
 * #1934 THE LIST'S AGE, ROOT-CAUSED. The repo list is cached (#1679) and was re-read ONLY on a tap (Refresh, pull-to-refresh): nothing re-read it by itself,
 * there was no time limit on the cache, and a refresh that found no credential (or failed) said nothing and left the old time on screen, so a phone could show a
 * list ten days old. Now: a read older than [TTL_MINUTES] is re-read once per page session, never on a fresh cache (the cache stays drawn at once), a refresh that cannot read says why
 * next to the time, a failed read keeps the cached rows, and every successful read stamps its own time ([record]).
 */
internal object ListingRefresh {
    /** A cached list older than this is re-read when the page opens (6 h). */
    const val TTL_MINUTES = 360L

    /** Never read, or read [ttlMinutes] ago or longer. */
    fun stale(newestMs: Long, nowMs: Long, ttlMinutes: Long = TTL_MINUTES): Boolean = newestMs <= 0L || nowMs - newestMs >= ttlMinutes * 60_000L

    /** A successful read lands in [cache] under [slot], stamped [nowMs]; the returned slot is what the page shows as "List as of". */
    fun record(cache: GitListingCache, slot: String, repos: List<GitHubRepos.Repo>, complete: Boolean, viaGh: Boolean, rungId: String, nowMs: Long): GitListingCache.Slot {
        val v = GitListingCache.Slot(repos, nowMs, complete, viaGh, rungId)
        cache.put(slot, v)
        return v
    }

    /** Run one refresh over [read] (the network read of one slot): a success is recorded, a failure leaves the cache as it was and returns its reason. */
    fun refresh(cache: GitListingCache, slot: String, nowMs: Long, read: () -> Result<List<GitHubRepos.Repo>>): Result<GitListingCache.Slot> =
        read().map { record(cache, slot, it, true, false, "", nowMs) }
}
