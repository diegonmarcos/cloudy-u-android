package com.diegonmarcos.superapp.network

/**
 * The ONE order of the network tools: the status strip's left-cluster icons and the network
 * popup's sections are both laid out from [ORDER] through [inOrder], so the two cannot drift.
 * Tapping an icon still opens the popup on that icon's own section (the key is the link).
 * Each section also has ONE settings target ([SETTINGS]), the "Settings ›" link at its header.
 */
object NetworkSections {
    const val CELLULAR = "cellular"; const val WIFI = "wifi"; const val BLUETOOTH = "bluetooth"; const val MESH = "mesh"
    const val KDE = "kde"; const val ADB = "adb"; const val DATA = "data"; const val HOTSPOT = "hotspot"; const val GPS = "gps"
    /** #1684 Cloud Webserver's HTTP server and the phone's SSH door(s): green only from a real local round trip (LocalDoors). */
    const val WEBS = "webs"; const val SSHD = "ssh"

    /**
     * #1684 the owner's order, in four groups drawn with a separator between them (strip and dropdown alike):
     * radios + location | mesh + peers | local servers + debugging | the USB-C cable. DATA keeps its key (prefs, pets, links)
     * and is shown as "TypC".
     */
    val GROUPS: List<List<String>> = listOf(
        listOf(CELLULAR, WIFI, BLUETOOTH, HOTSPOT, GPS),
        listOf(MESH, KDE),
        listOf(WEBS, ADB, SSHD),
        listOf(DATA),
    )

    /** 5G, WiFi, BT, HS, GPS | WG, KDE | WebS, ADB, SSHd | TypC. */
    val ORDER: List<String> = listOf(CELLULAR, WIFI, BLUETOOTH, HOTSPOT, GPS, MESH, KDE, WEBS, ADB, SSHD, DATA)

    /** The group index of [key] (0..3), or null for a key that is not a network section. */
    fun groupOf(key: String): Int? = GROUPS.indexOfFirst { key in it }.takeIf { it >= 0 }

    /** True when a separator belongs between [prev] and [next]: both are network sections of different groups. */
    fun separatorBetween(prev: String?, next: String): Boolean {
        val a = prev?.let { groupOf(it) } ?: return false
        val b = groupOf(next) ?: return false
        return a != b
    }

    /** The strip's label for each key (the mobile one is replaced live by MobileLink: "5G", "Call", "SOS"...). */
    val LABELS: Map<String, String> = mapOf(
        CELLULAR to MobileLink.OFF_LABEL, WIFI to "WiFi", BLUETOOTH to "BT", MESH to "WG",
        KDE to "KDE", ADB to "ADB", DATA to "TypC", HOTSPOT to "HS", GPS to "GPS",
        WEBS to "WebS", SSHD to "SSHd",
    )

    /** #1684 the label DATA had before it became TypC (rule 16: renamed, not removed; the parity test reads this). */
    const val DATA_FORMER_LABEL = "Data"

    /** [byKey]'s values in [ORDER]; a key missing or unknown is a programming error, caught by tests. */
    fun <T> inOrder(byKey: Map<String, T>): List<T> {
        require(byKey.keys == ORDER.toSet()) { "network sections ${byKey.keys} != $ORDER" }
        return ORDER.map { byKey.getValue(it) }
    }

    /** One step of the popup's layout: a section, or the divider drawn between two of them. */
    sealed class Item {
        data class Section(val key: String) : Item()
        object Divider : Item()
        /** #1684 the stronger line between two [GROUPS] (the strip's separator, in the dropdown). */
        object GroupDivider : Item()
    }

    /**
     * The popup's layout: every section in [ORDER], a divider between each two (a [Item.GroupDivider] where the group
     * changes), and one before the trailing Network block.
     */
    fun layout(trailing: Boolean = true): List<Item> {
        val out = mutableListOf<Item>()
        var prev: String? = null
        for (k in ORDER) {
            if (out.isNotEmpty()) out += if (separatorBetween(prev, k)) Item.GroupDivider else Item.Divider
            out += Item.Section(k); prev = k
        }
        if (trailing) out += Item.Divider
        return out
    }

    /**
     * Where a section's "Settings ›" link goes. [Target.System] = Android screens tried in order
     * (the first that resolves on this build wins: AOSP and Samsung name them differently), each
     * "action:<Intent action>" or "component:<package>/<class>"; perSim adds the data SIM's
     * Settings.EXTRA_SUB_ID. [Target.InApp] = a SuperApp page, opened through its section/page route.
     */
    sealed class Target {
        abstract val label: String
        data class System(val tries: List<String>, val perSim: Boolean = false, override val label: String = "Settings ›") : Target()
        data class InApp(val section: String, val page: String, override val label: String) : Target()
        /** #1684 another fleet app, opened by its own launcher ([fleetId] in the fleet manifest), e.g. Cloud Webserver. */
        /** #1682 the ADB Shell page is the ADB owner's (AdbReadiness.openAdbShell), never a SuperApp page. */
        data class AdbOwner(override val label: String) : Target()
        data class FleetApp(val fleetId: String, override val label: String) : Target()
    }

    private const val SETTINGS_PKG = "com.android.settings"
    private const val ANY_SETTINGS = "action:android.settings.SETTINGS"

    val SETTINGS: Map<String, Target> = mapOf(
        // The Mobile network page (data, roaming, VoLTE, preferred type) first; operator choice next.
        CELLULAR to Target.System(listOf(
            "action:android.settings.DATA_ROAMING_SETTINGS",
            "action:android.settings.NETWORK_OPERATOR_SETTINGS",
            "action:android.settings.WIRELESS_SETTINGS",
            ANY_SETTINGS), perSim = true),
        WIFI to Target.System(listOf("action:android.settings.WIFI_SETTINGS", "action:android.settings.WIRELESS_SETTINGS", ANY_SETTINGS)),
        BLUETOOTH to Target.System(listOf("action:android.settings.BLUETOOTH_SETTINGS", ANY_SETTINGS)),
        MESH to Target.InApp("config", "wg", "Cloud Mesh ›"),
        KDE to Target.InApp("config", "kde", "Peer Control ›"),
        ADB to Target.AdbOwner("ADB Shell ›"),
        DATA to Target.System(listOf(
            "component:$SETTINGS_PKG/$SETTINGS_PKG.Settings\$UsbDetailsActivity",
            "action:android.settings.CONNECTED_DEVICE_SETTINGS",
            ANY_SETTINGS)),
        HOTSPOT to Target.System(listOf(
            "component:$SETTINGS_PKG/$SETTINGS_PKG.TetherSettings",
            "component:$SETTINGS_PKG/$SETTINGS_PKG.Settings\$TetherSettingsActivity",
            "action:android.settings.WIRELESS_SETTINGS",
            ANY_SETTINGS)),
        GPS to Target.System(listOf("action:android.settings.LOCATION_SOURCE_SETTINGS", ANY_SETTINGS)),
        // #1684 both doors are run (and switched) by Cloud C3 Webserver: its own app, its SSHd tab.
        WEBS to Target.FleetApp("c3-webserver", "Cloud Webserver ›"),
        SSHD to Target.FleetApp("c3-webserver", "Cloud Webserver ›"),
    )

    /** The page every "grant" action opens: Configs › Permissions (the app's one permission flow). */
    val PERMISSIONS = Target.InApp("config", "perms", "Grant ›")
}
