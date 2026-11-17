package com.diegonmarcos.superapp.apps

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import com.diegonmarcos.superapp.shellclient.ShellClient
import com.diegonmarcos.superapp.shellclient.ShellResult

/**
 * #1769 Phone > Apps "Active Apps": the apps that are really running or open now, never a recency ranking.
 *
 * Root cause of the old list (AppUsageProvider.activeNow): it replayed 7 days of UsageStats events per PACKAGE, counting an
 * ACTIVITY_RESUMED with no ACTIVITY_STOPPED as "alive". A process the system killed, a reboot, or a second activity of the same app
 * (stop of A removed the app although B was still open) never produce the matching event, so dead apps stayed "active" for a week,
 * while the launcher and System UI (always resumed) were listed too.
 *
 * Sources, best first, and the result names the one used:
 *  1. ADB shell through the fleet's ShellClient: `dumpsys activity activities` (live ActivityRecords, most recent task first), with
 *     `dumpsys activity recents` as a second reading when the first yields nothing.
 *  2. Usage events, replayed per ACTIVITY and per foreground service, cleared at the last boot, 24 h window (ADB down).
 * Everything here is pure (text and event lists in, packages out) so it is unit tested; only [load] touches the device.
 */
object RunningApps {
    const val SRC_ADB_ACTIVITIES = "ADB shell: dumpsys activity activities"
    const val SRC_ADB_RECENTS = "ADB shell: dumpsys activity recents"
    const val SRC_USAGE = "usage events (ADB down)"
    /** UsageEvents.Event.ACTIVITY_DESTROYED is hidden API; its value is 24. */
    const val ACTIVITY_DESTROYED = 24

    data class Result(val packages: List<String>, val source: String, val note: String = "") {
        /** The line shown under the section heading. */
        fun line(): String = "source: $source" + if (note.isNotBlank()) " ($note)" else ""
    }

    /** One usage event, reduced to what the replay needs. */
    data class Ev(val type: Int, val pkg: String, val cls: String, val ts: Long)

    private val SYSTEM_ALWAYS = setOf("android", "com.android.systemui", "com.android.launcher", "com.android.launcher3",
        "com.google.android.apps.nexuslauncher", "com.sec.android.app.launcher", "com.samsung.android.app.taskedge")

    /** Launcher itself, System UI and the given [extra] packages (home apps, constellation) are never listed. */
    fun excluded(self: String, homes: Set<String>, extra: Set<String> = emptySet()): Set<String> = SYSTEM_ALWAYS + self + homes + extra

    fun filter(pkgs: List<String>, exclude: Set<String>): List<String> =
        pkgs.asSequence().filter { it.isNotBlank() && it !in exclude }.distinct().toList()

    private val ACTIVITY_RECORD = Regex("""ActivityRecord\{[0-9a-f]+ u(\d+) ([\w.]+)/[\w.$]+ t-?\d+""")

    /** `dumpsys activity activities`: the packages of live ActivityRecords of user 0, in order of appearance (top task first), once each. */
    fun parseActivities(dump: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in ACTIVITY_RECORD.findAll(dump)) if (m.groupValues[1] == "0") out.add(m.groupValues[2])
        return out.toList()
    }

    private val RECENT_LINE = Regex("""Recent #\d+:.*""")
    private val TASK_PKG = Regex("""\bA=(?:\d+:)?([\w.]+)""")
    private val REAL_ACTIVITY = Regex("""(?:realActivity|cmp)=([\w.]+)/""")
    private val USER = Regex("""\bU=(\d+)""")

    /** `dumpsys activity recents`: packages of the recent tasks of user 0 (home-type tasks dropped), most recent first, once each. */
    fun parseRecents(dump: String): List<String> {
        val out = LinkedHashSet<String>()
        val lines = dump.lines()
        for ((i, l) in lines.withIndex()) {
            if (!RECENT_LINE.containsMatchIn(l)) continue
            if ("type=home" in l) continue
            val u = USER.find(l)?.groupValues?.get(1) ?: "0"
            if (u != "0") continue
            val pkg = TASK_PKG.find(l)?.groupValues?.get(1)
                ?: lines.subList(i + 1, minOf(i + 8, lines.size)).takeWhile { !RECENT_LINE.containsMatchIn(it) }
                    .firstNotNullOfOrNull { REAL_ACTIVITY.find(it)?.groupValues?.get(1) }
            if (pkg != null) out.add(pkg)
        }
        return out.toList()
    }

    /** Usage-event replay for the ADB-down fallback: an app is active while one of its activities is resumed/paused-not-stopped or a
     *  foreground service is started and not stopped; a device shutdown/startup event clears everything; events before [bootMs] are ignored. */
    fun replayActive(events: List<Ev>, bootMs: Long): List<String> {
        val open = HashMap<String, Long>()   // "pkg/cls" -> ts, activity or service still open
        for (e in events.sortedBy { it.ts }) {
            if (e.ts < bootMs) continue
            val key = e.pkg + "/" + e.cls
            when (e.type) {
                UsageEvents.Event.ACTIVITY_RESUMED -> open["a:$key"] = e.ts
                UsageEvents.Event.ACTIVITY_PAUSED -> open["a:$key"] = e.ts
                UsageEvents.Event.ACTIVITY_STOPPED, ACTIVITY_DESTROYED -> open.remove("a:$key")
                UsageEvents.Event.FOREGROUND_SERVICE_START -> open["s:$key"] = e.ts
                UsageEvents.Event.FOREGROUND_SERVICE_STOP -> open.remove("s:$key")
                UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP -> open.clear()
                else -> Unit
            }
        }
        val latest = HashMap<String, Long>()
        for ((k, ts) in open) { val p = k.substring(2).substringBefore('/'); latest[p] = maxOf(latest[p] ?: 0L, ts) }
        return latest.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** "Last open apps": MOVE_TO_FOREGROUND, last time per package, most recent first, deduplicated and filtered. */
    fun lastOpened(events: List<Ev>, exclude: Set<String>): List<String> {
        val last = HashMap<String, Long>()
        for (e in events) if (e.type == UsageEvents.Event.MOVE_TO_FOREGROUND) last[e.pkg] = maxOf(last[e.pkg] ?: 0L, e.ts)
        return filter(last.entries.sortedByDescending { it.value }.map { it.key }, exclude)
    }

    private fun events(ctx: Context, from: Long, to: Long): List<Ev> {
        val u = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyList()
        val out = ArrayList<Ev>()
        runCatching {
            val q = u.queryEvents(from, to)
            val e = UsageEvents.Event()
            while (q.hasNextEvent()) { q.getNextEvent(e); out.add(Ev(e.eventType, e.packageName ?: "", e.className ?: "", e.timeStamp)) }
        }
        return out
    }

    fun homePackages(ctx: Context): Set<String> = runCatching {
        ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())

    /** Blocking (shell round trip): call off the main thread. */
    fun load(ctx: Context, exclude: Set<String>): Result {
        val excl = excluded(ctx.packageName, homePackages(ctx), exclude)
        val downReason: String
        val st = runCatching { ShellClient.status(ctx) }.getOrNull()
        if (st != null && st.armed) {
            val acts = (ShellClient.execOut(ctx, "dumpsys activity activities", 30_000L) as? ShellResult.Ok)?.takeIf { it.success }?.stdout
            val a = acts?.let { filter(parseActivities(it), excl) }.orEmpty()
            if (a.isNotEmpty()) return Result(a, SRC_ADB_ACTIVITIES)
            val rec = (ShellClient.execOut(ctx, "dumpsys activity recents", 30_000L) as? ShellResult.Ok)?.takeIf { it.success }?.stdout
            val r = rec?.let { filter(parseRecents(it), excl) }.orEmpty()
            if (r.isNotEmpty()) return Result(r, SRC_ADB_RECENTS)
            downReason = "ADB up but dumpsys returned no app"
        } else downReason = st?.let { it.reason.ifBlank { it.detail }.ifBlank { "ADB down" } } ?: "ADB down"
        val now = System.currentTimeMillis()
        val boot = now - SystemClock.elapsedRealtime()
        val a = filter(replayActive(events(ctx, now - 24L * 3_600_000L, now), boot), excl)
        return Result(a, SRC_USAGE, downReason)
    }

    /** Last open apps, [windowDays] of events, most recent first. */
    fun loadLastOpened(ctx: Context, exclude: Set<String>, windowDays: Int = 30): List<String> {
        val now = System.currentTimeMillis()
        return lastOpened(events(ctx, now - windowDays * 24L * 3_600_000L, now), excluded(ctx.packageName, homePackages(ctx), exclude))
    }
}
