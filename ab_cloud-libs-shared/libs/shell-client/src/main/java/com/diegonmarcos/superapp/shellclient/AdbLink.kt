package com.diegonmarcos.superapp.shellclient

/**
 * #1550 THE adb transport state every "ADB" indicator in the fleet shows (SuperApp home dot, SuperApp ADB Shell page, Cloud
 * Store's Wireless debugging chip). It is NOT the shell broker's state: the broker can still run `id -u` as uid 2000 through
 * the local app_process server or Shizuku after Wireless debugging was switched off, and before #1550 that made every ADB
 * indicator green while adb was down. Only a real round trip over the embedded adb client counts as [CONNECTED].
 */
enum class AdbLink(val id: String, val label: String) {
    UNKNOWN("unknown", "ADB: checking"),
    ADB_OFF("adb_off", "ADB: USB debugging (adb) is off"),
    WIRELESS_OFF("wireless_off", "ADB: Wireless debugging is off"),
    NOT_PAIRED("not_paired", "ADB: Wireless debugging on, not paired"),
    UNREACHABLE("unreachable", "ADB: paired, but adbd does not answer"),
    CONNECTED("connected", "ADB: connected");

    /** Green only here. */
    val up: Boolean get() = this == CONNECTED

    /** null = not known yet (grey), else up/down. */
    val verdict: Boolean? get() = if (this == UNKNOWN) null else up

    companion object {
        fun of(id: String?): AdbLink = values().firstOrNull { it.id == id } ?: UNKNOWN

        /**
         * Pure rule. [adbEnabled] = Settings.Global adb_enabled == 1, [wireless] = adb_wifi_enabled == 1 (null = unreadable),
         * [paired] = this app's pairing record, [roundTrip] = a `getprop` answered over the embedded adb session within the timeout
         * (null = not attempted). A successful round trip is the truth whatever the switches read (adb tcpip, for instance).
         */
        fun classify(adbEnabled: Boolean?, wireless: Boolean?, paired: Boolean, roundTrip: Boolean?): AdbLink = when {
            roundTrip == true -> CONNECTED
            wireless == false -> WIRELESS_OFF
            adbEnabled == false -> ADB_OFF
            wireless == null -> UNKNOWN
            !paired -> NOT_PAIRED
            else -> UNREACHABLE
        }
    }
}
