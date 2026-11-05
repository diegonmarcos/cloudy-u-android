package com.diegonmarcos.superapp.core

/**
 * #1502 Who may keep itself alive or wake itself in the background. Pure: every gate (Updater, ConstellationWorker,
 * HostShell, WdKeeper, the peer readers) asks here, and the PACKAGE decides.
 *  - Cloud Account: NEVER. No periodic work, no boot receiver, no keep-alive service. It runs in the foreground, on a
 *    user action, or because the system bound a provider for a caller (an on-demand binding that ends with the call).
 *  - Cloud Store: only with auto-update ON and on an unmetered network (Wi-Fi). Otherwise zero background wakes.
 *  - Every other app: unchanged ([Mode.FREE]).
 * Guards: BackgroundPolicyTest, ac_cloud-account/test/test-no-background-wake.sh.
 */
object BackgroundPolicy {
    const val ACCOUNT_PKG = "com.diegonmarcos.cloudaccount"
    const val STORE_PKG = "com.diegonmarcos.cloudstore"

    const val RETRY_MIN_MS = 30_000L // #1502 no retry, reconnect or restart loop repeats sooner (libs:devtools RetryBackoff.MIN_MS is the same number)

    enum class Mode { NEVER, AUTO_UPDATE_UNMETERED, FREE }

    /** Apps no other app may start from the background (provider query, binder call, "wake"). */
    val NO_WAKE_PEERS: Set<String> = setOf(ACCOUNT_PKG, STORE_PKG)

    fun modeOf(packageName: String): Mode = when (packageName) {
        ACCOUNT_PKG -> Mode.NEVER
        STORE_PKG -> Mode.AUTO_UPDATE_UNMETERED
        else -> Mode.FREE
    }

    /** May [packageName] run (or keep) background work right now? */
    fun mayBackground(packageName: String, autoUpdateOn: Boolean, unmetered: Boolean): Boolean = when (modeOf(packageName)) {
        Mode.NEVER -> false
        Mode.AUTO_UPDATE_UNMETERED -> autoUpdateOn && unmetered
        Mode.FREE -> true
    }

    /** May another app start [packageName] on its own (not on a user tap)? */
    fun mayWakePeer(packageName: String): Boolean = packageName !in NO_WAKE_PEERS
}
