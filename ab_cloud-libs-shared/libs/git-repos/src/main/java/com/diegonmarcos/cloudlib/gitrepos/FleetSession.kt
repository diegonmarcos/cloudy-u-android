package com.diegonmarcos.cloudlib.gitrepos

/**
 * #653 THE FLEET SESSION, IN MEMORY AND NOWHERE ELSE.
 *
 * What the owner's Authelia browser login earns is a session cookie. This app needs it
 * for exactly one purpose — presenting it to git-proxy-api so the GitHub work happens on
 * the server — and it must not survive beyond the process.
 *
 * WHY THIS IS AN OBJECT WITH A VAR AND NOT A STORE. There is deliberately no
 * SharedPreferences, no EncryptedSharedPreferences, no file and no keystore entry here.
 * `test-drive-configs-sign-in.sh` asserts that the drive host stores no bearer, and that
 * guard enforces the owner's standing rule that the phone does not persist a credential
 * it did not have to. A process-lifetime field satisfies it by construction rather than by
 * promise: there is no code path that could write this value anywhere, because there is no
 * writer. Killing the app forgets it and the owner signs in again — which is the correct
 * trade for a credential that authorises reading a private repository list.
 *
 * NOTE WHAT THIS IS NOT. It is not a GitHub credential and cannot become one. It proves
 * the owner's identity TO THE FLEET; the GitHub token stays on the server, which is the
 * whole point of ranking the fleet rung first. The one GitHub credential this app may
 * hold is the vault import's, and that lives in libs:git-sync's own store under the one
 * declared id — never here.
 */
object FleetSession {

    /**
     * #1693 THE SESSION IS CLOUD ACCOUNT'S: a short-lived lease (cookie + expiry) read over the fleet IPC and held here in
     * process memory only. No sign-in code lives in the app that holds it; an expired lease reads as no session.
     */
    data class Lease(val cookie: String, val expiresAtMs: Long)

    @Volatile private var held: Lease? = null

    /** The blocking read of Cloud Account's session lease (null: none / refused / not installed). Set once by the host app. */
    @Volatile var source: (() -> Lease?)? = null

    /** The clock; a test hands in its own. */
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    /** The cookie of the lease in hand, or "" when there is none or it has expired. Never blocks: [sync] fetches. */
    val cookie: String
        get() {
            val l = held ?: return ""
            if (l.expiresAtMs <= clock()) { held = null; return "" }
            return l.cookie
        }

    /** Remember a session for this process only (the debug route injects one; the lease is capped at [SHORT_LIVED_MS]). */
    fun remember(value: String) {
        val v = value.trim()
        held = if (v.isBlank()) null else Lease(v, clock() + SHORT_LIVED_MS)
    }

    /** Take what Cloud Account holds, when the lease in hand is gone. BLOCKING: call it off the main thread. */
    fun sync(): Boolean {
        if (cookie.isNotBlank()) return true
        val l = runCatching { source?.invoke() }.getOrNull()
        held = l?.takeIf { it.cookie.isNotBlank() && it.expiresAtMs > clock() }
        return held != null
    }

    /** [sync] then the cookie: for a caller already off the main thread. */
    fun fresh(): String { sync(); return cookie }

    /** Forget it, when the fleet refuses it as stale. */
    fun forget() {
        held = null
    }

    val present: Boolean get() = cookie.isNotBlank()

    const val SHORT_LIVED_MS = 60L * 60 * 1000
}
