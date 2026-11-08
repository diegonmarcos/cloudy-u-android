package com.diegonmarcos.superapp.fleetxray

import java.util.Locale

/**
 * The X-Ray trackers: the thirteen dimensions the profiler reads for THIS app, plus the Device Profile Scan that reads the
 * phone around it. The catalogue (ids and titles) is pure and stable; the live values are read on-device by [FleetXrayPage] and
 * handed to [FleetXrayExport]. Keeping the list here, not in the page, is what lets a test pin it and the exporters share it.
 */
public object FleetXrayProbes {

    public data class Tracker(val id: String, val title: String)

    /** The thirteen per-app trackers, in the order the page and the export draw them. */
    public val TRACKERS: List<Tracker> = listOf(
        Tracker("identity", "Identity"),
        Tracker("build", "Build"),
        Tracker("memory", "Memory"),
        Tracker("storage", "Storage"),
        Tracker("threads", "CPU & threads"),
        Tracker("battery", "Battery"),
        Tracker("network", "Network"),
        Tracker("permissions", "Permissions"),
        Tracker("uptime", "Process & uptime"),
        Tracker("display", "Display"),
        Tracker("locale", "Locale & config"),
        Tracker("runtime", "Runtime flags"),
        Tracker("components", "Components"),
    )

    /** The Device Profile Scan reads the phone (model, SoC, memory, storage, Android) rather than the app. */
    public const val DEVICE_PROFILE_ID: String = "device_profile"
    public const val DEVICE_PROFILE_TITLE: String = "Device Profile Scan"

    /** `1.0 GB`, `512 MB`: the byte formatter every tracker uses, so the number reads the same everywhere. */
    public fun humanBytes(bytes: Long): String {
        if (bytes < 0) return "?"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var u = 0
        while (v >= 1024 && u < units.lastIndex) { v /= 1024; u++ }
        return if (u == 0) "$bytes B" else String.format(Locale.ROOT, "%.1f %s", v, units[u])
    }
}
