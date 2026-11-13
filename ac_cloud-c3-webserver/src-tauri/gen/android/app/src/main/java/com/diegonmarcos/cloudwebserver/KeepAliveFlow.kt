package com.diegonmarcos.cloudwebserver

/**
 * #1724 the keep-alive start contract as pure rules (no Android types, so a JVM test holds it).
 *
 * Android kills the app with ForegroundServiceDidNotStartInTimeException when a service that was started with
 * startForegroundService() does not call startForeground() within ~5 s, on EVERY start request (not only the one that
 * creates the service). So the service enters the foreground first thing in onCreate AND at the top of every
 * onStartCommand, before any branch or early return; when it must not run (Server or Keep alive OFF, a Stop action) it
 * goes foreground first and only then stops. [onStartCommand] is that one flow; KeepAliveService only supplies the [Host].
 *
 * The other half is the sender: [decideStart] says whether a start request may be made at all, so the app never calls
 * startForegroundService for a service that will not (or may not) go foreground; the reason is kept in [KeepAliveReason]
 * and shown on Home (Keep-alive health).
 */
object KeepAliveFlow {
    const val STICKY = 1       // Service.START_STICKY
    const val NOT_STICKY = 2   // Service.START_NOT_STICKY

    /** What the service does for one start command. Every method must never throw. */
    interface Host {
        /** startForeground with the notification; true = the service is foreground now. Records the reason when it is not. */
        fun enterForeground(): Boolean
        fun serviceAllowed(): Boolean
        /** Stop action: release the ports (off the main thread). */
        fun stopServers()
        /** Leave the foreground (remove the notification) and stopSelf. */
        fun stopSelfNow()
        /** Bind the servers and refresh the notification (off the main thread). */
        fun startServers()
    }

    const val ACTION_STOP = "com.diegonmarcos.cloudwebserver.STOP"

    fun onStartCommand(host: Host, action: String?): Int {
        // 1. ALWAYS first: the foreground promise of this start request, whatever follows.
        val foreground = host.enterForeground()
        if (action == ACTION_STOP) {
            host.stopServers()
            host.stopSelfNow()
            return NOT_STICKY
        }
        if (!foreground) {
            // Android refused (or the notification could not be built): a service that is not foreground must not linger.
            host.stopSelfNow()
            return NOT_STICKY
        }
        if (!host.serviceAllowed()) {
            host.stopSelfNow()
            return NOT_STICKY
        }
        host.startServers()
        return STICKY
    }

    /**
     * Runs [goForeground] (the startForeground call); a refusal is caught and recorded with its real reason, never thrown.
     * Returns true when the service is foreground.
     */
    fun guarded(goForeground: () -> Unit, record: (String) -> Unit): Boolean = try {
        goForeground(); true
    } catch (t: Throwable) {
        record(reasonOf(t)); false
    }

    /** The reason text of a refused foreground start, by exception class name (so it needs no API-level class). */
    fun reasonOf(t: Throwable): String {
        val n = t.javaClass.name
        val m = t.message?.take(160).orEmpty()
        return when {
            n.endsWith("ForegroundServiceStartNotAllowedException") -> "Android refused the foreground start (app in the background, or battery restriction): $m"
            n.endsWith("MissingForegroundServiceTypeException") || n.endsWith("InvalidForegroundServiceTypeException") -> "foreground service type not accepted: $m"
            t is SecurityException -> "missing permission for the foreground service (FOREGROUND_SERVICE_SPECIAL_USE?): $m"
            else -> "${t.javaClass.simpleName}: $m"
        }
    }

    enum class Origin { ACTIVITY, BOOT, CORE }

    /** [start] false = skip startForegroundService; [reason] is what Home shows (null = nothing to report, e.g. keep-alive off). */
    data class Decision(val start: Boolean, val reason: String?)

    /**
     * Whether a start request may be made. [appForeground] = the process is visible or has a foreground service (an Android 12+
     * app in the background may not start one), except from the boot receiver (BOOT_COMPLETED is exempt for specialUse).
     */
    fun decideStart(allowed: Boolean, sdk: Int, appForeground: Boolean, origin: Origin): Decision = when {
        !allowed -> Decision(false, null)
        sdk >= 31 && origin != Origin.BOOT && !appForeground ->
            Decision(false, "not started: the app is in the background and Android 12+ forbids starting a foreground service from there; it starts when the app is opened")
        else -> Decision(true, null)
    }
}

/** The last reason the keep-alive service did not go foreground (or "" when it did): a one-line file, read by Home. */
object KeepAliveReason {
    private fun file(ctx: android.content.Context) = java.io.File(ctx.filesDir, "keepalive-reason.txt")
    fun set(ctx: android.content.Context, reason: String) { runCatching { file(ctx).writeText(reason) } }
    fun clear(ctx: android.content.Context) { runCatching { file(ctx).delete() } }
    fun get(ctx: android.content.Context): String = runCatching { file(ctx).takeIf { it.exists() }?.readText().orEmpty() }.getOrDefault("")
}
