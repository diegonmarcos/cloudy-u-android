package com.diegonmarcos.superapp.launcher

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * #1673 The launcher's "Permission Access Status": the pure part (no Android), so every rule is a JVM test.
 *
 *  - [Sensor] / [SensorState]: the three live icons under the resources line (Mic, Camera, Location) and how a reading maps
 *    to ACTIVE (coloured), IDLE (grey) or UNKNOWN (grey + "?", the launcher cannot see that sensor's state).
 *  - [GROUPS]: every permission group Android's own Privacy dashboard reports, each with the app-ops that count as a use of it.
 *  - [parseDumpsys]: the system's app-ops history as `dumpsys appops --op <OP>` prints it (read through ShellClient from the ADB owner, Cloud C3 Webserver), into one [Use] per (group, package): the access records in the window and the last time.
 *  - [dashboard]: the 24 h dashboard rows (used groups first by last use, unused groups after, greyed), with the +N overflow.
 *
 * Never fake data: a group nobody used in the window says so; no access to the history is [Dashboard.NoAccess] with the reason.
 */
object PermAccess {

    enum class Sensor(val label: String, val group: String) { MIC("Microphone", "microphone"), CAMERA("Camera", "camera"), LOCATION("Location", "location") }

    enum class SensorState { ACTIVE, IDLE, UNKNOWN }

    /**
     * A sensor's state: [watching] = the launcher has a live source for it (a platform callback registered and allowed to see
     * every app); [active] = what that source last reported. Without a live source the state is UNKNOWN, whatever [active] says,
     * except that an access the launcher DID see (a one-shot app-ops read that found it running) still lights it.
     */
    fun state(watching: Boolean, active: Boolean): SensorState = when {
        active -> SensorState.ACTIVE
        watching -> SensorState.IDLE
        else -> SensorState.UNKNOWN
    }

    /** The 24 h window of the dashboard, like Android's. */
    const val WINDOW_MS = 24L * 60 * 60 * 1000

    /** App icons drawn per group before the "+N" overflow. */
    const val MAX_ICONS = 3

    /** One permission group of Android's Privacy dashboard and the app-ops (dumpsys names) that are a use of it. */
    data class Group(val id: String, val label: String, val ops: List<String>)

    /** Every group Android's Privacy dashboard reports (Android 12-15), in its order. */
    val GROUPS: List<Group> = listOf(
        Group("location", "Location", listOf("FINE_LOCATION", "COARSE_LOCATION")),
        Group("camera", "Camera", listOf("CAMERA")),
        Group("microphone", "Microphone", listOf("RECORD_AUDIO")),
        Group("phone", "Phone", listOf("READ_PHONE_STATE", "CALL_PHONE", "READ_PHONE_NUMBERS", "ANSWER_PHONE_CALLS", "USE_SIP")),
        Group("nearby", "Nearby devices", listOf("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE", "UWB_RANGING", "NEARBY_WIFI_DEVICES")),
        Group("activity", "Physical activity", listOf("ACTIVITY_RECOGNITION")),
        Group("calendar", "Calendar", listOf("READ_CALENDAR", "WRITE_CALENDAR")),
        Group("contacts", "Contacts and accounts", listOf("READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS")),
        Group("calllog", "Call logs", listOf("READ_CALL_LOG", "WRITE_CALL_LOG", "PROCESS_OUTGOING_CALLS")),
        Group("health", "Health / fitness and wellness", listOf("BODY_SENSORS")),
        Group("photos", "Photos and videos", listOf("READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_VISUAL_USER_SELECTED")),
        Group("music", "Music and audio", listOf("READ_MEDIA_AUDIO")),
        Group("notifications", "Notifications", listOf("POST_NOTIFICATION")),
        Group("files", "Files", listOf("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE")),
        Group("sms", "SMS", listOf("READ_SMS", "RECEIVE_SMS", "SEND_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH", "READ_CELL_BROADCASTS")),
    )

    private val GROUP_OF_OP: Map<String, String> = GROUPS.flatMap { g -> g.ops.map { it to g.id } }.toMap()

    /** The location ops the live Location icon reads (one-shot) when no live callback can see other apps. */
    val LOCATION_OPS: List<String> = GROUPS.first { it.id == "location" }.ops

    /** One broker command per op (`;` segments, each matching the SuperApp's reads-1 `dumpsys` rule). */
    fun dumpsysCommand(ops: List<String>): String = ops.joinToString("; ") { "dumpsys appops --op $it" }

    /** The dashboard's read: every op of every group. */
    val DASHBOARD_CMD: String = dumpsysCommand(GROUPS.flatMap { it.ops })

    /** The access records of one package for one group: how many in the window, the last one, and whether one is running now. */
    data class Use(val group: String, val pkg: String, val count: Int, val lastMs: Long, val running: Boolean)

    /** "1d2h3m4s567ms" (dumpsys durations) in ms, or null. */
    fun durationMs(s: String): Long? {
        val m = Regex("""(\d+)(ms|d|h|m|s)""").findAll(s).toList()
        if (m.isEmpty()) return null
        return m.sumOf { val n = it.groupValues[1].toLong(); when (it.groupValues[2]) { "d" -> n * 86_400_000; "h" -> n * 3_600_000; "m" -> n * 60_000; "s" -> n * 1000; else -> n } }
    }

    private val ABS = Regex("""(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)(\.\d{1,3})?""")
    private val REL = Regex("""\(-([\dmsdh]+)\)""")
    private val OLD = Regex("""time=\+([\dmsdh]+) ago""")
    private val PKG = Regex("""^\s*Package ([\w.]+):\s*$""")
    private val OP = Regex("""^\s*([A-Z][A-Z0-9_]+) \(""")

    /** When an `Access:` record happened, from its relative age (preferred: no timezone) or its absolute local time. */
    internal fun accessMs(line: String, nowMs: Long, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): Long? {
        REL.find(line)?.let { r -> durationMs(r.groupValues[1])?.let { return nowMs - it } }
        OLD.find(line)?.let { r -> durationMs(r.groupValues[1])?.let { return nowMs - it } }
        ABS.find(line)?.let { a ->
            val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = tz }
            return runCatching { f.parse(a.groupValues[1])?.time }.getOrNull()?.let { it + (a.groupValues[2].drop(1).padEnd(3, '0').toIntOrNull() ?: 0) }
        }
        return null
    }

    /**
     * `dumpsys appops [--op OP]...` into uses. Understands the Android 11+ form (`Package p:` / `OP (mode):` / `Access: [state]
     * <date> (-age) duration=…` / `Running start at:`) and the older one-line form (`OP (mode): time=+age ago; duration=…`).
     * Records older than [windowMs] are dropped; an op that is running counts whatever its age.
     */
    fun parseDumpsys(text: String?, nowMs: Long, windowMs: Long = WINDOW_MS): List<Use> {
        if (text.isNullOrBlank()) return emptyList()
        val acc = LinkedHashMap<Pair<String, String>, Acc>()
        var pkg: String? = null
        var group: String? = null
        fun note(g: String, p: String, at: Long?, running: Boolean) {
            val inWindow = at != null && at >= nowMs - windowMs && at <= nowMs + 60_000
            if (!inWindow && !running) return
            val a = acc.getOrPut(g to p) { Acc() }
            if (inWindow) a.count++
            a.last = maxOf(a.last, if (inWindow) at!! else nowMs)
            if (running) a.running = true
        }
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            val t = line.trim()
            if (t.startsWith("Uid ") || t.startsWith("Current AppOps")) { pkg = null; group = null; continue }
            val pm = PKG.find(line)
            if (pm != null) { pkg = pm.groupValues[1]; group = null; continue }
            val p = pkg ?: continue
            val opM = OP.find(line)
            if (opM != null) {
                group = GROUP_OF_OP[opM.groupValues[1]]
                // The older one-line form carries the access on the op line itself.
                val g = group
                if (g != null && line.contains("time=")) note(g, p, accessMs(line, nowMs), line.contains("duration=-"))
                continue
            }
            val g = group ?: continue
            when {
                t.startsWith("Access:") -> note(g, p, accessMs(t, nowMs), t.contains("duration=-"))
                t.startsWith("Running start", ignoreCase = true) -> note(g, p, null, true)
            }
        }
        return acc.map { (k, a) -> Use(k.first, k.second, a.count, a.last, a.running) }
    }

    private class Acc(var count: Int = 0, var last: Long = 0L, var running: Boolean = false)

    /** True when any location op is running for any package (the one-shot seed of the Location icon). */
    fun locationRunning(uses: List<Use>): Boolean = uses.any { it.group == "location" && it.running }

    /** One dashboard row: a group, its apps (newest first), the overflow past [MAX_ICONS], the total records and the last use. */
    data class Row(val group: Group, val apps: List<Use>, val total: Int, val lastMs: Long) {
        val used: Boolean get() = apps.isNotEmpty()
        val shown: List<Use> get() = apps.take(MAX_ICONS)
        val overflow: Int get() = (apps.size - MAX_ICONS).coerceAtLeast(0)
    }

    /** The dashboard as the page draws it. */
    sealed class Dashboard {
        /** The history could not be read: [reason] says why, the page offers Configs > Perms. */
        data class NoAccess(val reason: String) : Dashboard()
        /** The history was read; [rows] has every group (unused ones greyed, never hidden). */
        data class Ready(val rows: List<Row>, val readAtMs: Long) : Dashboard() {
            val empty: Boolean get() = rows.none { it.used }
        }
    }

    /** Every group of [GROUPS], used ones first (most recent use first), then the unused ones in Android's order. */
    fun dashboard(uses: List<Use>, nowMs: Long): Dashboard.Ready {
        val by = uses.groupBy { it.group }
        val rows = GROUPS.map { g ->
            val apps = by[g.id].orEmpty().sortedByDescending { it.lastMs }
            Row(g, apps, apps.sumOf { it.count.coerceAtLeast(if (it.running) 1 else 0) }, apps.maxOfOrNull { it.lastMs } ?: 0L)
        }
        val (used, unused) = rows.partition { it.used }
        return Dashboard.Ready(used.sortedByDescending { it.lastMs } + unused, nowMs)
    }

    /** "12 accesses · last 14:05" for a used row, "Not used in the past 24 hours" for an unused one. */
    fun summary(row: Row, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): String =
        if (!row.used) "Not used in the past 24 hours"
        else "${row.total} access${if (row.total == 1) "" else "es"} · ${row.apps.size} app${if (row.apps.size == 1) "" else "s"} · last ${clock(row.lastMs, tz)}"

    fun clock(ms: Long, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): String =
        SimpleDateFormat("HH:mm", Locale.ROOT).apply { timeZone = tz }.format(java.util.Date(ms))

    /** The explanation the row and the dashboard show when the launcher has no access to the history / a sensor. */
    const val NO_ACCESS_HELP = "Android keeps the permission history in its app-ops service, which only the system or an ADB " +
        "shell can read. The ADB shell belongs to Cloud C3 Webserver: arm it there (Webserver > ADB Shell / Wireless debugging) and open this again."
}
