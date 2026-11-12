package com.diegonmarcos.superapp.configs

import com.diegonmarcos.superapp.adbdebug.ChannelFacts
import com.diegonmarcos.superapp.adbdebug.ChannelMode
import com.diegonmarcos.superapp.adbdebug.SetupAction
import com.diegonmarcos.superapp.adbdebug.ShizukuState

/**
 * #1676 the ADB Shell Perms page: ONLY what ADB Shell needs, as one ordered chain from the first human step to the last automatic one.
 * Read off the code, not guessed:
 *  - SetupChecklist / ConnectPlan (#1647 chain): Developer options -> Wireless debugging -> Wi-Fi -> notifications (the pairing code is
 *    typed into AdbPairingService's notification) -> pairing -> the embedded adb echo round trip -> (Local Server) the uid-2000 server probe.
 *  - HostShell.selfGrant: WRITE_SECURE_SETTINGS over adb (WdKeeper / WirelessDebugging.set turn Wireless debugging back on after reboot).
 *  - the lib manifest: DUMP via the shell grant (in-process dumpsys reads of the shell broker), REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (WdKeeper).
 *  - NOT needed: NEARBY_WIFI_DEVICES / location (adb mDNS discovery is NsdManager, INTERNET only); those stay in Configs > Perms.
 * Shizuku mode swaps the adb steps for Shizuku's own two. Pure: the page feeds it real reads ([Live]) and draws exactly this.
 */
internal object AdbPrereqs {

    enum class Owner(val word: String) { HUMAN("Human"), AUTO("Automatic") }
    enum class Status(val word: String) { ON("on"), OFF("off"), UNKNOWN("unknown") }

    /**
     * What the row's button does: open the exact Settings page, run the Perms catalogue's own adb lines for [Grant.entry] over the armed
     * shell broker (the fleet's one grant path, #1515), run the Connect chain, or open Configs > Perms at [InPerms.entry].
     */
    sealed class Action(val label: String) {
        class Open(val setup: SetupAction, label: String) : Action(label)
        class Grant(val entry: String) : Action("Grant via adb")
        object Connect : Action("Connect")
        class InPerms(val entry: String, label: String) : Action(label)
    }

    /** The Perms catalogue entries this page can grant over adb (each is also a row of Configs > Perms). */
    val GRANT_ENTRIES = listOf("notifications", "write_secure_settings", "dump", "battery_opt")

    data class Row(val id: String, val label: String, val owner: Owner, val status: Status, val why: String, val actions: List<Action>)

    /** Real reads the facts do not carry: each null = could not be read (unknown, never guessed). */
    data class Live(val writeSecure: Boolean?, val dump: Boolean?, val batteryExempt: Boolean?, val sdk: Int = 34)

    /** Every id this page can show, in chain order (the declared prerequisites). */
    val ADB_CHAIN = listOf("dev-options", "wireless-debugging", "wifi", "notifications", "paired", "adb-connected", "local-server",
        "write-secure-settings", "dump", "battery")
    val SHIZUKU_CHAIN = listOf("shizuku-running", "shizuku-permission", "shizuku-connected", "write-secure-settings", "dump", "battery")

    private fun st(v: Boolean?) = when (v) { true -> Status.ON; false -> Status.OFF; null -> Status.UNKNOWN }

    fun rows(f: ChannelFacts, live: Live, pkg: String): List<Row> {
        val out = ArrayList<Row>()
        if (f.mode == ChannelMode.SHIZUKU) {
            val s = f.shizuku
            out += Row("shizuku-running", "Shizuku installed and running", Owner.HUMAN,
                if (s == ShizukuState.NOT_INSTALLED || s == ShizukuState.NOT_RUNNING) Status.OFF else Status.ON,
                "Shizuku mode runs every command through Shizuku's binder", listOf(Action.Open(SetupAction.OPEN_SHIZUKU, "Open Shizuku")))
            out += Row("shizuku-permission", "Shizuku permission for this app", Owner.HUMAN,
                when (s) { ShizukuState.UP -> Status.ON; ShizukuState.PERMISSION_NEEDED -> Status.OFF; else -> Status.UNKNOWN },
                "Shizuku only answers apps you approved", listOf(Action.Open(SetupAction.REQUEST_SHIZUKU, "Allow")))
            out += Row("shizuku-connected", "Shizuku answers (echo round trip)", Owner.AUTO, st(f.shizukuAnswers),
                "green only after a real echo over the binder", listOf(Action.Connect))
        } else {
            out += Row("dev-options", "Developer options enabled", Owner.HUMAN, st(f.devOptions),
                "Wireless debugging lives in Developer options; tap Build number 7 times (no app can do it)",
                listOf(if (f.devOptions == true) Action.Open(SetupAction.OPEN_DEVELOPER_OPTIONS, "Developer options")
                       else Action.Open(SetupAction.OPEN_ABOUT_PHONE, "About phone")))
            out += Row("wireless-debugging", "Wireless debugging ON", Owner.HUMAN, st(f.wirelessDebug),
                "the on-device adbd the app talks to; Android turns it off on reboot and when Wi-Fi drops",
                listOf(Action.Open(SetupAction.OPEN_WIRELESS_DEBUGGING, "Wireless debugging")))
            out += Row("wifi", "On Wi-Fi", Owner.HUMAN, st(f.onWifi),
                "Wireless debugging only runs on a Wi-Fi network", listOf(Action.Open(SetupAction.OPEN_WIFI, "Wi-Fi")))
            out += Row("notifications", "Notifications allowed", Owner.HUMAN, st(f.notificationsAllowed),
                "the pairing code is typed into a notification; the pairing service runs in the foreground",
                buildList {
                    add(Action.Open(SetupAction.OPEN_NOTIFICATION_SETTINGS, "Notification settings"))
                    if (live.sdk >= 33) add(Action.Grant("notifications"))
                })
            out += Row("paired", "Paired with this phone's adb", Owner.HUMAN, if (f.adbPaired) Status.ON else Status.OFF,
                "one pairing code, once; the app reconnects by itself after", listOf(Action.Open(SetupAction.PAIR, "Pair")))
            out += Row("adb-connected", "adb connection (round trip)", Owner.AUTO, if (f.adbConnected) Status.ON else Status.OFF,
                "green only after a live echo over the embedded adb link", listOf(Action.Connect))
            if (f.mode != ChannelMode.EMBEDDED_ONLY && f.ownsServer)
                out += Row("local-server", "Local server answers (uid 2000)", Owner.AUTO, if (f.serverRunning) Status.ON else Status.OFF,
                    "Local Server mode runs commands on the shell-uid server adb starts", listOf(Action.Connect))
        }
        out += Row("write-secure-settings", "WRITE_SECURE_SETTINGS", Owner.AUTO, st(live.writeSecure),
            "lets the app turn Wireless debugging back on after a reboot without you",
            listOf(Action.Grant("write_secure_settings")))
        out += Row("dump", "DUMP (dumpsys reads)", Owner.AUTO, st(live.dump),
            "the shell broker's dumpsys / appops reads run in-process with it",
            listOf(Action.Grant("dump")))
        out += Row("battery", "Battery optimisation exemption", Owner.AUTO, st(live.batteryExempt),
            "keeps the adb link and its keeper alive in the background",
            listOf(Action.Grant("battery_opt"), Action.InPerms("battery_opt", "In Configs > Perms")))
        return out
    }

    /** The first row that is not on, in chain order; null when every step is on. */
    fun firstFailing(rows: List<Row>): Row? = rows.firstOrNull { it.status != Status.ON }

    /** Grant via adb needs a channel already up (the adb or Shizuku step on). */
    fun canGrant(rows: List<Row>): Boolean =
        rows.any { (it.id == "adb-connected" || it.id == "shizuku-connected" || it.id == "local-server") && it.status == Status.ON }
}
