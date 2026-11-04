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
     * The cookie from the last completed fleet browser login, or "" when there has been
     * none in this process. Written only by the sign-in host's `onWebSession`.
     */
    @Volatile
    var cookie: String = ""
        private set

    /** Remember the session libs:auth's browser login just earned. Memory only. */
    fun remember(value: String) {
        cookie = value.trim()
    }

    /** Forget it — on an explicit sign-out, or when the fleet refuses it as stale. */
    fun forget() {
        cookie = ""
    }

    val present: Boolean get() = cookie.isNotBlank()
}
