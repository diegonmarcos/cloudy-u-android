package app.sterna.push

/**
 * How long a dropped push connection waits before the next attempt (PushService, ForegroundPush).
 *
 * WHY. Both loops retried every 5 s for ever, and every PushService attempt starts with a full
 * baseline pass (FetchAndNotify over each watched account) before it opens the socket. With the
 * server unreachable or a connection that drops right after opening (Doze, a dead mesh route, a
 * network switch), the always-on foreground service ran a sync every few seconds all night: the
 * largest per-app drain on the phone's battery table. The wait now doubles per failure up to
 * [MAX_MS], and starts over only after a connection that held for [STABLE_MS] — a connect-then-drop
 * flap is a failure, not a success.
 */
internal object ReconnectBackoff {
    const val BASE_MS = 5_000L
    const val MAX_MS = 5 * 60_000L
    const val STABLE_MS = 60_000L

    /** 5 s, 10 s, 20 s … capped at 5 min, for [failures] consecutive failures. */
    fun delayMs(failures: Int): Long =
        if (failures <= 0) BASE_MS else (BASE_MS shl failures.coerceAtMost(MAX_SHIFT)).coerceAtMost(MAX_MS)

    /** The failure count after a drop: [openedForMs] = how long the connection held (null = it never opened). */
    fun next(failures: Int, openedForMs: Long?): Int =
        if (openedForMs != null && openedForMs >= STABLE_MS) 0 else failures + 1

    private const val MAX_SHIFT = 6
}
