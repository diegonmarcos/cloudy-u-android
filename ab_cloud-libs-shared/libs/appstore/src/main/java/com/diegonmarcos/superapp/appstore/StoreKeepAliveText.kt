package com.diegonmarcos.superapp.appstore

/**
 * The body of the Store's keep-alive notification: update state, never an empty badge. Pure, so a test executes it.
 *
 *   running      the Store bar line (e.g. "Auto > 2/4 download all  ·  App 1.2  ·  downloading  ·  3 of 12"), then "N updates pending"
 *   idle         "N updates pending" or "All up to date", with "last check 2m ago" when a pass has finished
 *   no ledger    "Checking..."
 */
object StoreKeepAliveText {

    fun lines(progress: String?, pending: Int?, lastPassAt: Long, now: Long): List<String> {
        val ago = if (lastPassAt > 0L) "last check " + ago(now - lastPassAt) else null
        val waiting = when {
            pending == null -> null
            pending == 0 -> "All up to date"
            else -> "$pending update${if (pending == 1) "" else "s"} pending"
        }
        if (!progress.isNullOrBlank()) return listOfNotNull(progress, waiting?.takeIf { pending != 0 })
        if (waiting == null) return listOf("Checking…")
        return listOf(listOfNotNull(waiting, ago).joinToString(" · "))
    }

    fun ago(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60}m ago"
            s < 86_400 -> "${s / 3600}h ago"
            else -> "${s / 86_400}d ago"
        }
    }
}
