package com.diegonmarcos.superapp.adbdebug

import org.json.JSONArray
import org.json.JSONObject

/**
 * #1467 the audit log of `adb_wifi_enabled`: a bounded ring, one entry per change of the setting (and per re-arm), kept as
 * JSON in the app's preferences so it survives a process kill. Pure apart from org.json: persistence is the [Store].
 */
class WdAuditLog(private val store: Store, private val capacity: Int = 200) {

    interface Store { fun load(): String?; fun save(json: String) }

    /** [on] the setting's new value, [wifi] whether a Wi-Fi network was connected, [cause] the guess, [by] who re-armed it ("-" when nobody did). */
    data class Entry(val at: Long, val on: Boolean, val wifi: Boolean, val cause: String, val by: String)

    private val lock = Any()
    private val ring = ArrayDeque<Entry>().also { r -> read(store.load()).forEach { r.addLast(it) } }

    fun add(e: Entry) = synchronized(lock) {
        ring.addLast(e)
        while (ring.size > capacity) ring.removeFirst()
        store.save(encode(ring.toList()))
    }

    /** Oldest first. */
    fun entries(): List<Entry> = synchronized(lock) { ring.toList() }

    fun lastRearm(): Entry? = synchronized(lock) { ring.lastOrNull { it.by != "-" } }

    fun asText(stamp: (Long) -> String): String = entries().joinToString("\n") { row(it, stamp) }

    companion object {
        /** One readable line per event: time, on/off, Wi-Fi, cause, who re-armed it. */
        fun row(e: Entry, stamp: (Long) -> String): String =
            "${stamp(e.at)}  ${if (e.on) "ON " else "OFF"}  wifi ${if (e.wifi) "yes" else "no"}  ${e.cause}  by ${if (e.by == "-") "nobody" else e.by}"


        fun encode(list: List<Entry>): String = JSONArray().also { a ->
            list.forEach { a.put(JSONObject().put("at", it.at).put("on", it.on).put("wifi", it.wifi).put("cause", it.cause).put("by", it.by)) }
        }.toString()

        /** A damaged or missing blob is an empty log, never a crash. */
        fun read(json: String?): List<Entry> = runCatching {
            val a = JSONArray(json ?: "[]")
            (0 until a.length()).map { i ->
                a.getJSONObject(i).let { Entry(it.getLong("at"), it.getBoolean("on"), it.getBoolean("wifi"), it.optString("cause"), it.optString("by", "-")) }
            }
        }.getOrDefault(emptyList())
    }
}

/** The chips of Configs > Keep wireless debugging on: what is held, and what is not, in one place for the page and the debug API. */
object WdKeeperStatus {
    enum class Level { OK, WARN, BAD, UNKNOWN }

    data class StatusChip(val id: String, val label: String, val value: String, val level: Level)

    data class Snapshot(
        val armed: Boolean,
        val canWrite: Boolean,
        val batteryExempt: Boolean,
        val serviceRunning: Boolean,
        val wifiConnected: Boolean,
        val settingOn: Boolean,
        val lastRearmAt: Long?,
        val lastRearmBy: String?,
    )

    fun chips(s: Snapshot, ago: (Long) -> String): List<StatusChip> = listOf(
        StatusChip("permission", "Permission", if (s.canWrite) "held" else "missing", if (s.canWrite) Level.OK else Level.BAD),
        StatusChip("battery", "Battery", if (s.batteryExempt) "unrestricted" else "optimised", if (s.batteryExempt) Level.OK else Level.WARN),
        StatusChip("service", "Service", if (s.serviceRunning) "running" else if (s.armed) "stopped" else "off",
            if (s.serviceRunning) Level.OK else if (s.armed) Level.BAD else Level.UNKNOWN),
        StatusChip("wifi", "Wi-Fi", if (s.wifiConnected) "connected" else "none", if (s.wifiConnected) Level.OK else Level.WARN),
        StatusChip("setting", "Wireless debugging", if (s.settingOn) "on" else "off", if (s.settingOn) Level.OK else Level.WARN),
        StatusChip("rearm", "Last re-arm", s.lastRearmAt?.let { ago(it) + (s.lastRearmBy?.let { b -> " by $b" } ?: "") } ?: "never",
            if (s.lastRearmAt != null) Level.OK else Level.UNKNOWN),
    )

    fun json(s: Snapshot, log: List<WdAuditLog.Entry>): String = JSONObject()
        .put("armed", s.armed).put("can_write", s.canWrite).put("battery_exempt", s.batteryExempt)
        .put("service_running", s.serviceRunning).put("wifi", s.wifiConnected).put("setting_on", s.settingOn)
        .put("last_rearm_at", s.lastRearmAt ?: JSONObject.NULL).put("last_rearm_by", s.lastRearmBy ?: JSONObject.NULL)
        .put("log", JSONArray(WdAuditLog.encode(log))).toString()
}
