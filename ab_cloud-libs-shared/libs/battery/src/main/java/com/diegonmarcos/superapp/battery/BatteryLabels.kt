package com.diegonmarcos.superapp.battery

/**
 * The name a battery row shows for a uid, pure (BatteryRepository.appLabel feeds it the PackageManager answer).
 * Never a bare number or a cut label: a system uid gets its role, an app its label AND package, and a uid
 * nothing owns says so. uid 2000 is the shell: the SuperApp's own privileged channel (app_process server /
 * embedded adb) runs there, so its batterystats row is the SuperApp's cost, and it says so.
 */
object BatteryLabels {

    const val SHELL_UID = 2000

    /** The role of a uid below the app range (per user), or null for an app uid. */
    fun system(uid: Int): String? {
        val id = uid % 100_000
        return when {
            id == 0 -> "Android system (root, uid 0)"
            id == 1000 -> "Android system (uid 1000)"
            id == 1001 -> "Phone / radio (uid 1001)"
            id == 1002 -> "Bluetooth (uid 1002)"
            id == 1010 -> "Wi-Fi (uid 1010)"
            id == 1013 -> "Media server (uid 1013)"
            id == 1021 -> "GPS (uid 1021)"
            id == 1041 -> "Audio server (uid 1041)"
            id == 1047 -> "Camera server (uid 1047)"
            id == SHELL_UID -> "Cloud SuperApp privileged channel · adb shell (uid 2000)"
            id in 99_000..99_999 -> "Isolated process (uid $uid)"
            id < 10_000 -> "Android system service (uid $uid)"
            else -> null
        }
    }

    /** What a row shows: the system role, else "Label · package" (+N when packages share the uid). */
    fun display(uid: Int, label: String?, packages: List<String>): String {
        system(uid)?.let { return it }
        val pkg = packages.firstOrNull() ?: return "Unknown app (uid $uid)"
        val more = if (packages.size > 1) " (+${packages.size - 1})" else ""
        val name = label?.trim().orEmpty()
        return if (name.isEmpty() || name == pkg) "$pkg$more" else "$name · $pkg$more"
    }
}
