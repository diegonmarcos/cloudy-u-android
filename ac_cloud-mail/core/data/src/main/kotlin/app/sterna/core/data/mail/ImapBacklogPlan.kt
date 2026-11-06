package app.sterna.core.data.mail

/** Most new messages (UIDs above the highest cached one) one sync fetches beyond its newest window. */
internal const val IMAP_NEW_CAP = 1_000

/** Most OLDER missing messages one sync backfills, newest first: a big Inbox lands over a few syncs. */
internal const val IMAP_BACKFILL_BATCH = 400

/** UIDs one backlog fetch asks for; a longer plan is fetched in chunks. */
internal const val IMAP_BACKLOG_CHUNK = 200

/** Most UIDs read from the server to find what is missing; past it the OLDEST are the ones seen. */
internal const val IMAP_SNAPSHOT_CAP = 200_000

/**
 * What an IMAP folder sync fetches next, from the folder's UIDs on the server and the UIDs held:
 * first everything ABOVE the highest cached UID (the sync resumes from the last UID it saw: new mail
 * is never left behind a backlog), then the newest [backfill] of the missing OLDER UIDs. Repeated,
 * it converges on the whole folder; each call is bounded, so no sync is a full re-walk.
 */
internal object ImapBacklogPlan {
    fun next(
        serverUids: Collection<Long>,
        cachedUids: Set<Long>,
        newCap: Int = IMAP_NEW_CAP,
        backfill: Int = IMAP_BACKFILL_BATCH,
    ): List<Long> {
        val missing = serverUids.filter { it !in cachedUids }.distinct().sortedDescending()
        if (missing.isEmpty()) return emptyList()
        val highest = cachedUids.maxOrNull() ?: Long.MIN_VALUE
        val newer = missing.filter { it > highest }.take(newCap)
        val older = missing.filter { it < highest }.take(backfill)
        return newer + older
    }
}
