package com.diegonmarcos.superapp.network

/**
 * The strip's "Data" icon and the popup's Data section as pure rules over the sticky
 * ACTION_USB_STATE extras (booleans keyed by the AOSP names: connected, configured,
 * host_connected, unlocked, and one per USB function), the charger type and the attached
 * OTG devices. One definition of "a USB-C cable in DATA mode", so the icon and the section
 * can never disagree: a function that moves data (MTP/PTP/RNDIS/NCM/MIDI/...) is active, or
 * the phone is the USB host. A cable that only charges (no function, or only adb) is not data.
 */
object UsbDataModel {

    /** USB functions that carry user data, in display order, with their labels. adb is listed but not data: it has its own icon. */
    val FUNCTIONS: List<Pair<String, String>> = listOf(
        "mtp" to "MTP (file transfer)", "ptp" to "PTP (photo)",
        "rndis" to "RNDIS (USB tethering)", "ncm" to "NCM (USB tethering)", "midi" to "MIDI",
        "mass_storage" to "Mass storage", "accessory" to "Accessory", "audio_source" to "Audio source",
        "uvc" to "Webcam (UVC)",
    )
    private val DATA_KEYS = FUNCTIONS.map { it.first }.toSet()

    /** Every key [State] reads out of the USB_STATE intent. */
    val EXTRA_KEYS: List<String> = listOf("connected", "configured", "host_connected", "unlocked", "adb") + DATA_KEYS

    enum class Role { NONE, DEVICE, HOST }

    /** #1684 the TypC indicator's three states. */
    enum class Cable(val label: String) { NONE("not connected"), CHARGE("charge-only"), DATA("data") }

    data class State(
        val connected: Boolean,
        val configured: Boolean,
        val host: Boolean,
        /** Labels of the active data functions. */
        val functions: List<String>,
        val adbOverUsb: Boolean,
        /** BatteryManager.EXTRA_PLUGGED, mapped: "AC", "USB", "Wireless", "Dock" or null. */
        val power: String?,
        /** Attached OTG devices, already described ("Name (vid:pid)"). */
        val otgDevices: List<String>,
    ) {
        val role: Role get() = when {
            host || otgDevices.isNotEmpty() -> Role.HOST
            connected -> Role.DEVICE
            else -> Role.NONE
        }
        /** The icon is lit: a data function in device mode, or the phone is the host of an OTG device. */
        val data: Boolean get() = role == Role.HOST || (connected && functions.isNotEmpty())

        fun headline(): String = when {
            role == Role.HOST -> "● Data · OTG host" + if (otgDevices.isEmpty()) "" else " (${otgDevices.size} device${if (otgDevices.size == 1) "" else "s"})"
            data -> "● Data · device mode"
            connected -> "○ Cable, no data (charge-only" + (if (adbOverUsb) " + adb" else "") + ")"
            power == "Wireless" -> "○ Wireless charging (no cable)"
            power != null -> "○ Charging only (no data)"
            else -> "○ Disconnected"
        }

        /**
         * #1684 the strip's TypC indicator: the USB-C cable as NONE (no cable; wireless charging is no cable),
         * CHARGE (a cable that powers but moves no data: charger, or a computer port with no data function) or DATA.
         */
        val cable: Cable get() = when {
            data -> Cable.DATA
            connected || power == "AC" || power == "USB" || power == "Dock" -> Cable.CHARGE
            else -> Cable.NONE
        }

        fun roleLabel(): String = when (role) {
            Role.HOST -> "host (phone powers + drives the device)"
            Role.DEVICE -> "device (phone is the peripheral)"
            Role.NONE -> "—"
        }
    }

    /** [extras]: USB_STATE booleans by key (absent = false); [plugged]: EXTRA_PLUGGED. */
    fun state(extras: Map<String, Boolean>, plugged: Int, otgDevices: List<String> = emptyList()): State = State(
        connected = extras["connected"] == true,
        configured = extras["configured"] == true,
        host = extras["host_connected"] == true,
        functions = FUNCTIONS.filter { extras[it.first] == true }.map { it.second },
        adbOverUsb = extras["adb"] == true,
        power = when (plugged) {
            1 -> "AC"          // BatteryManager.BATTERY_PLUGGED_AC
            2 -> "USB"         // BATTERY_PLUGGED_USB
            4 -> "Wireless"    // BATTERY_PLUGGED_WIRELESS
            8 -> "Dock"        // BATTERY_PLUGGED_DOCK (API 33)
            else -> null
        },
        otgDevices = otgDevices,
    )

    /** /sys/class/udc/<udc>/current_speed ("high-speed", "super-speed", "UNKNOWN") -> a label, or null. */
    fun udcSpeed(raw: String?): String? {
        val s = raw?.trim()?.lowercase() ?: return null
        return when (s) {
            "", "unknown" -> null
            "low-speed" -> "USB 1.x low speed (1.5 Mbps)"
            "full-speed" -> "USB 1.1 full speed (12 Mbps)"
            "high-speed" -> "USB 2.0 (480 Mbps)"
            "super-speed" -> "USB 3.x (5 Gbps)"
            "super-speed-plus" -> "USB 3.x (10+ Gbps)"
            else -> s
        }
    }

    /** /sys/bus/usb/devices/<dev>/speed (Mbps as text: "1.5", "12", "480", "5000"...) -> a label, or null. */
    fun hostSpeed(raw: String?): String? {
        val mbps = raw?.trim()?.toDoubleOrNull() ?: return null
        return when {
            mbps >= 1000 -> String.format(java.util.Locale.US, "%.0f Gbps", mbps / 1000)
            mbps >= 1 -> "${if (mbps % 1.0 == 0.0) mbps.toLong().toString() else mbps.toString()} Mbps"
            else -> null
        }
    }
}
