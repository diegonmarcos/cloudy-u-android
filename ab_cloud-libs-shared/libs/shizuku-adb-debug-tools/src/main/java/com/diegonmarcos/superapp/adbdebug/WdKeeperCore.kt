package com.diegonmarcos.superapp.adbdebug

/**
 * #1467 the decisions of "Keep wireless debugging on", pure (no Android): what to do about `adb_wifi_enabled` given what
 * is known now, and a guess at why it changed. [WdKeeper] gathers the facts and acts; this file only decides, so the
 * state machine is JVM-tested (WdKeeperCoreTest).
 */
object WdKeeperCore {
    enum class Action {
        /** The owner's switch is off: the keeper does nothing at all. */
        DISARMED,
        /** The setting is already on. */
        NOTHING,
        /** Off, but this app may not write it (WRITE_SECURE_SETTINGS missing): only the owner or the channel can grant it. */
        WAIT_PERMISSION,
        /** Off with no Wi-Fi: Android rejects the write without a network, so wait for the Wi-Fi callback. */
        WAIT_WIFI,
        /** Off, but another armed app on this phone is the one that re-arms (see [WdKeeperLeader]). */
        NOT_LEADER,
        /** Off and the owner demonstrably turned it off (our own panel switched it): honour it, never fight it. */
        OWNER_OFF,
        /** Write 1. */
        REARM,
    }

    data class Facts(
        val armed: Boolean,
        val canWrite: Boolean,
        val settingOn: Boolean,
        val wifiConnected: Boolean,
        val leader: Boolean,
        /** True only when the keeper can tell the owner turned it off. Android gives no such signal for its own settings page, so this stays false there and the keeper re-arms, which is the owner's explicit wish. */
        val ownerOff: Boolean = false,
    )

    fun decide(f: Facts): Action = when {
        !f.armed -> Action.DISARMED
        f.ownerOff -> Action.OWNER_OFF
        f.settingOn -> Action.NOTHING
        !f.canWrite -> Action.WAIT_PERMISSION
        !f.wifiConnected -> Action.WAIT_WIFI
        !f.leader -> Action.NOT_LEADER
        else -> Action.REARM
    }

    /** Why `adb_wifi_enabled` changed, as far as the phone can say. A guess, labelled as one in the log. */
    enum class Cause(val label: String) {
        SELF("our own write"),
        BOOT("boot"),
        WIFI_LOST("Wi-Fi lost"),
        NETWORK_CHANGED("network or access point changed"),
        OWNER_OR_SYSTEM("no network event: the owner or the system"),
        UNKNOWN("unknown"),
    }

    const val NETWORK_WINDOW_MS = 15_000L
    const val BOOT_WINDOW_MS = 120_000L

    /**
     * [sinceNetworkEventMs] is the age of the last Wi-Fi available / lost event (null = none seen), [sinceBootMs] the
     * time since boot, [selfWriteRecent] whether this or a peer keeper wrote the setting a moment ago.
     */
    fun guessCause(wifiConnected: Boolean, sinceNetworkEventMs: Long?, sinceBootMs: Long, selfWriteRecent: Boolean): Cause = when {
        selfWriteRecent -> Cause.SELF
        sinceBootMs in 0..BOOT_WINDOW_MS -> Cause.BOOT
        !wifiConnected -> Cause.WIFI_LOST
        sinceNetworkEventMs != null && sinceNetworkEventMs in 0..NETWORK_WINDOW_MS -> Cause.NETWORK_CHANGED
        else -> Cause.OWNER_OR_SYSTEM
    }
}

/**
 * Leader election between the armed keeper apps on one phone (Cloud Store, SuperApp): the one that re-arms is chosen the same
 * way by every app from the same claims, so two apps never both write. A claim is a heartbeat; a stale one (process killed,
 * switch off) drops out, so whichever app is alive takes over.
 */
object WdKeeperLeader {
    const val TTL_MS = 150_000L

    data class Claim(val pkg: String, val armed: Boolean, val canWrite: Boolean, val heartbeatAt: Long)

    /** Armed and fresh claims, the ones that can write first, then by package name (a stable tiebreak). Null when nobody is eligible. */
    fun elect(claims: List<Claim>, now: Long, ttl: Long = TTL_MS): String? =
        claims.filter { it.armed && now - it.heartbeatAt in -ttl..ttl }
            .sortedWith(compareByDescending<Claim> { it.canWrite }.thenBy { it.pkg })
            .firstOrNull()?.pkg

    fun isLeader(self: String, claims: List<Claim>, now: Long, ttl: Long = TTL_MS): Boolean = elect(claims, now, ttl) == self
}
