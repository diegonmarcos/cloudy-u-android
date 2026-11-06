package com.diegonmarcos.superapp.updater.install

/**
 * #1551 WHAT AN INSTALL FAILURE MEANS FOR THE QUEUE. Pure Kotlin, no android: a JVM test pins every marker.
 *
 * The install-stage note ([com.diegonmarcos.superapp.updater.cache.ApkCache.note]) used to hold an app off the unattended pass
 * forever, whatever it said ([com.diegonmarcos.superapp.updater.Fleet.heldAtInstall]). That is right for a decline and for a fact
 * about the bytes, and wrong for a session that simply did not finish: INSTALL_FAILED_ABORTED "Session was abandoned" is a session
 * abandoned by its installer (the reaper at the session cap, or a superseded duplicate), not an answer from the user — the user's
 * own "Cancel" on the system sheet reads "User rejected permissions". A transient failure is retried from the cache with backoff;
 * only a decline or a permanent fact stops the queue.
 */
object InstallFailure {

    enum class Kind {
        /** The session did not finish (abandoned, timed out, no room at that moment, system busy): retry from the cache. */
        TRANSIENT,
        /** The user answered No on the system sheet: never re-prompted unattended (#774). */
        DECLINED,
        /** A fact about these bytes on this phone (signature, uid, downgrade, invalid APK): retrying cannot change it. */
        PERMANENT,
    }

    /** The user's own No. Checked first: it is also reported as INSTALL_FAILED_ABORTED. */
    val DECLINED_MARKERS = listOf("User rejected", "rejected permissions", "declined")

    /** A session that did not finish. Matched case-insensitively against the status message and the legacy code name. */
    val TRANSIENT_MARKERS = listOf(
        "Session was abandoned",
        "abandoned or cancelled",
        "STATUS_FAILURE_TIMEOUT", "TIMEOUT (",
        "did not settle",
        "INSTALL_FAILED_INTERNAL_ERROR",
        "INSTALL_FAILED_INSUFFICIENT_STORAGE", "STORAGE (",
        "INSTALL_FAILED_MEDIA_UNAVAILABLE",
        "INSTALL_FAILED_SESSION_INVALID",
        "Too many active sessions",
        "truncated or removed mid-install",
    )

    fun classify(message: String?): Kind {
        val m = message.orEmpty()
        if (m.isBlank()) return Kind.PERMANENT
        if (DECLINED_MARKERS.any { m.contains(it, ignoreCase = true) }) return Kind.DECLINED
        if (TRANSIENT_MARKERS.any { m.contains(it, ignoreCase = true) }) return Kind.TRANSIENT
        return Kind.PERMANENT
    }

    fun transient(message: String?): Boolean = classify(message) == Kind.TRANSIENT
}
