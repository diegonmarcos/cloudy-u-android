package com.diegonmarcos.superapp.profile

import com.diegonmarcos.superapp.fleetendpoints.SignInContract

/**
 * #1693 THE SHORT-LIVED AUTHELIA SESSION Cloud Account holds for the fleet apps that must present one (Drive's Cloud git rung).
 * The portal login runs only here (WebAuthDialog -> [remember]); the `session` provider method hands the cookie, with its
 * expiry, to a caller holding the fleet signature permission and to nobody else.
 *
 * Memory only: no preference, file or keystore entry, so a killed Cloud Account forgets it and the owner signs in again. The
 * cookie value is NEVER logged, echoed or put in an error: the one place it leaves is [answer]'s bundle JSON, and
 * [SignInContract.Session.toString] hides it. Refreshing = [keepFresh]: a probe of the portal with the cookie extends the
 * lease while Authelia still accepts it, and drops it when Authelia refuses.
 */
object SessionLease {
    /** Hard cap of one lease; the portal's own expiry applies sooner when the probe is refused. */
    const val TTL_MS = 55L * 60 * 1000
    /** Within this window of expiry a `session` call refreshes before answering. */
    const val REFRESH_WINDOW_MS = 10L * 60 * 1000

    private class Held(val cookie: String, var expiresAtMs: Long)
    @Volatile private var held: Held? = null
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    /** The portal login earned [cookie]: hold it for [TTL_MS]. A blank cookie clears. */
    fun remember(cookie: String, ttlMs: Long = TTL_MS) {
        val v = cookie.trim()
        held = if (v.isBlank()) null else Held(v, clock() + ttlMs)
    }

    fun forget() { held = null }

    /** Epoch ms the lease in hand runs out, or 0 when there is none (never the cookie). */
    fun expiresAt(): Long = live()?.expiresAtMs ?: 0L

    private fun live(): Held? {
        val h = held ?: return null
        if (h.expiresAtMs <= clock()) { held = null; return null }
        return h
    }

    /**
     * Extend the lease when it is inside [REFRESH_WINDOW_MS] of expiry: [probe] presents the cookie to the portal and says
     * whether Authelia still accepts it (true = extend by [TTL_MS], false = drop). Returns whether a lease remains.
     */
    fun keepFresh(probe: (String) -> Boolean): Boolean {
        val h = live() ?: return false
        if (h.expiresAtMs - clock() > REFRESH_WINDOW_MS) return true
        val ok = runCatching { probe(h.cookie) }.getOrDefault(false)
        if (ok) h.expiresAtMs = clock() + TTL_MS else held = null
        return held != null
    }

    /**
     * The `session` answer. [allowed] is the caller's signature-permission check, computed by the provider; a caller that is
     * not allowed gets a refusal that carries no state about the session at all.
     */
    fun answer(provider: String, allowed: Boolean, probe: (String) -> Boolean = { true }): SignInContract.Session {
        if (!allowed) return SignInContract.noSession(provider, SignInContract.State.UNKNOWN, "caller is not a fleet app")
        if (provider != SignInContract.PROVIDER_AUTHELIA) return SignInContract.noSession(provider, SignInContract.State.UNKNOWN, "no session is held for $provider")
        keepFresh(probe)
        val h = live() ?: return SignInContract.noSession(provider, SignInContract.State.SIGNED_OUT, "not signed in to Authelia in Cloud Account")
        return SignInContract.Session(provider, SignInContract.State.SIGNED_IN, "Cookie", h.cookie, h.expiresAtMs, "signed in to Authelia in Cloud Account")
    }
}
