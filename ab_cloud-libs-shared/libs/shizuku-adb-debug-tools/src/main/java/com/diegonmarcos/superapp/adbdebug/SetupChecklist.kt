package com.diegonmarcos.superapp.adbdebug

enum class SetupStatus { OK, TODO, UNKNOWN }

/** What a checklist item's button does; the Android side maps each to the exact settings screen. */
enum class SetupAction {
    OPEN_ABOUT_PHONE, OPEN_DEVELOPER_OPTIONS, OPEN_WIRELESS_DEBUGGING, OPEN_WIFI, OPEN_NOTIFICATION_SETTINGS,
    PAIR, REQUEST_SHIZUKU, OPEN_SHIZUKU,
}

data class SetupItem(
    val id: String, val label: String, val status: SetupStatus, val detail: String,
    val action: SetupAction?, val actionLabel: String,
)

/** The Setup section: which prerequisites the chosen mode has, their real status, and the button for each. */
object SetupChecklist {

    private fun st(v: Boolean?) = when (v) { true -> SetupStatus.OK; false -> SetupStatus.TODO; null -> SetupStatus.UNKNOWN }

    /** [via] is not a prerequisite of any mode (it only orders the Restart-server button's launch in Auto). */
    @Suppress("UNUSED_PARAMETER")
    fun items(f: ChannelFacts, via: LaunchVia = LaunchVia.ADB): List<SetupItem> {
        // One channel per mode: the adb modes list adb's prerequisites, Shizuku mode lists Shizuku's, never both.
        val shizukuPath = f.mode == ChannelMode.SHIZUKU
        val adbPath = !shizukuPath
        val out = ArrayList<SetupItem>()
        if (adbPath) {
            out += SetupItem("dev-options", "Developer options enabled", st(f.devOptions),
                if (f.devOptions == true) "on" else "Settings > About phone > tap Build number seven times",
                if (f.devOptions == true) SetupAction.OPEN_DEVELOPER_OPTIONS else SetupAction.OPEN_ABOUT_PHONE,
                if (f.devOptions == true) "Open" else "About phone")
            out += SetupItem("wireless-debugging", "Wireless debugging enabled", st(f.wirelessDebug),
                if (f.wirelessDebug == true) "on" else "Developer options > Wireless debugging",
                SetupAction.OPEN_WIRELESS_DEBUGGING, "Open")
            out += SetupItem("wifi", "On Wi-Fi", st(f.onWifi),
                when (f.onWifi) { true -> "connected"; false -> "wireless debugging needs a Wi-Fi network"; null -> "unknown" },
                SetupAction.OPEN_WIFI, "Wi-Fi")
            out += SetupItem("paired", "Pairing done", st(f.adbPaired),
                if (f.adbPaired) "paired once; reconnects by itself" else "type the 6-digit code into the notification",
                SetupAction.PAIR, if (f.adbPaired) "Pair again" else "Pair")
            out += SetupItem("notifications", "Notifications allowed (pairing code input)", st(f.notificationsAllowed),
                if (f.notificationsAllowed == true) "allowed" else "the pairing code is typed into a notification",
                SetupAction.OPEN_NOTIFICATION_SETTINGS, "Open")
        }
        if (shizukuPath) {
            out += SetupItem("shizuku-running", "Shizuku installed and running",
                when (f.shizuku) { ShizukuState.NOT_INSTALLED, ShizukuState.NOT_RUNNING -> SetupStatus.TODO; else -> SetupStatus.OK },
                when (f.shizuku) {
                    ShizukuState.NOT_INSTALLED -> "install Shizuku"
                    ShizukuState.NOT_RUNNING -> "installed; start its service in the Shizuku app"
                    else -> "running" + (f.shizukuVersion?.let { " $it" } ?: "")
                }, SetupAction.OPEN_SHIZUKU, "Open Shizuku")
            out += SetupItem("shizuku-permission", "Shizuku permission for this app",
                if (f.shizuku == ShizukuState.UP) SetupStatus.OK else if (f.shizuku == ShizukuState.PERMISSION_NEEDED) SetupStatus.TODO else SetupStatus.UNKNOWN,
                when (f.shizuku) {
                    ShizukuState.UP -> "granted"
                    ShizukuState.PERMISSION_NEEDED -> "approve the Shizuku prompt"
                    else -> "needs Shizuku running first"
                }, SetupAction.REQUEST_SHIZUKU, "Allow")
        }
        return out
    }

    fun summary(items: List<SetupItem>): String = "${items.count { it.status == SetupStatus.OK }}/${items.size} ready"
}
