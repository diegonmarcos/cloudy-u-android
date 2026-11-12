package app.sterna.core.data.mail

/** Most ids read from the server's Inbox query to find what the cache lacks. */
internal const val JMAP_SNAPSHOT_CAP = 200_000

/** Most new (newer than the newest cached) missing messages one JMAP Inbox sync fetches. */
internal const val JMAP_NEW_CAP = 1_000

/** Most OLDER missing messages one JMAP Inbox sync backfills, newest first. */
internal const val JMAP_BACKFILL_BATCH = 400

/** Ids one backlog `Email/get` asks for; a longer plan is fetched in chunks. */
internal const val JMAP_BACKLOG_CHUNK = 200

/**
 * #1527: the JMAP Inbox lists EVERY message, not the newest window. Same shape as
 * [ImapBacklogPlan], over the server's Inbox ids in its own order (receivedAt, newest first):
 * first the missing ids NEWER than the newest cached one (new mail never waits behind a backlog),
 * then the newest [backfill] of the missing OLDER ones. Repeated per refresh it converges on the
 * whole Inbox; each call is bounded, so no refresh is a full re-download.
 */
internal object JmapBacklogPlan {
    fun next(
        serverIdsNewestFirst: List<String>,
        cachedIds: Set<String>,
        newCap: Int = JMAP_NEW_CAP,
        backfill: Int = JMAP_BACKFILL_BATCH,
    ): List<String> {
        val ordered = serverIdsNewestFirst.distinct()
        val firstCached = ordered.indexOfFirst { it in cachedIds }
        if (firstCached < 0) return ordered.take(newCap + backfill)
        val newer = ordered.subList(0, firstCached).take(newCap)
        val older = ordered.subList(firstCached, ordered.size).filter { it !in cachedIds }.take(backfill)
        return newer + older
    }
}

/**
 * Every id of a mailbox query, paged by position (ids only, no headers), newest first, at most [cap].
 * [page] is `(position, limit) -> (ids, total)`. Stops when a page comes back empty, the reported
 * total is reached or the cap is hit. Throws whatever [page] throws: a caller that cannot read the
 * whole list must not reconcile against a part of it.
 */
internal suspend fun walkAllIds(
    pageSize: Int,
    cap: Int = JMAP_SNAPSHOT_CAP,
    page: suspend (position: Int, limit: Int) -> Pair<List<String>, Int?>,
): List<String> {
    val size = pageSize.coerceAtLeast(1)
    val out = ArrayList<String>()
    while (out.size < cap) {
        val (ids, total) = page(out.size, minOf(size, cap - out.size))
        if (ids.isEmpty()) break
        out += ids
        if (total != null && out.size >= total) break
    }
    return out
}
