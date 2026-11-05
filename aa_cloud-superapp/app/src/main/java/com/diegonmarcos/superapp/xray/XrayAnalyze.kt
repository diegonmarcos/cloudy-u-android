package com.diegonmarcos.superapp.xray

import java.util.Locale

/**
 * #1504 The parsers behind the ranked suspects and the fleet view: pure text in, numbers out, so every one is unit-tested on a
 * fixture. They read the raw shell outputs by probe id ([XrayProbes]); a probe that is missing (no adb) just contributes nothing.
 */
object XrayAnalyze {

    const val FLEET_PREFIX = "com.diegonmarcos."

    // ── helpers ──────────────────────────────────────────────────────────
    private val DUR = Regex("(\\d+)(ms|d|h|m|s)")

    /** "1h 2m 3s 4ms" / "+1h2m3s4ms" → milliseconds. */
    fun durMs(s: String): Long = DUR.findAll(s).sumOf { m ->
        val n = m.groupValues[1].toLong()
        when (m.groupValues[2]) { "d" -> n * 86_400_000L; "h" -> n * 3_600_000L; "m" -> n * 60_000L; "s" -> n * 1_000L; else -> n }
    }

    /** "u0a123" → 10123, "u10a5" → 1010005, "1000" → 1000; null when it is neither. */
    fun uidOf(name: String): Int? {
        name.toIntOrNull()?.let { return it }
        Regex("u(\\d+)a(\\d+)").matchEntire(name)?.let { return it.groupValues[1].toInt() * 100_000 + 10_000 + it.groupValues[2].toInt() }
        Regex("u(\\d+)s(\\d+)").matchEntire(name)?.let { return it.groupValues[1].toInt() * 100_000 + 99_000 + it.groupValues[2].toInt() }
        return null
    }

    /** `cmd package list packages -U` → uid → packages. */
    fun uidPackages(out: String): Map<Int, List<String>> =
        Regex("package:(\\S+)\\s+uid:(\\d+)").findAll(out).groupBy({ it.groupValues[2].toInt() }, { it.groupValues[1] })

    private fun label(uidName: String, pk: Map<Int, List<String>>): String =
        uidOf(uidName)?.let { u -> pk[u]?.joinToString("+") } ?: uidName

    // ── batterystats --charged ───────────────────────────────────────────
    data class Power(val uidName: String, val mAh: Double, val parts: Map<String, Double>)

    private val POWER = Regex("^\\s+Uid (\\S+): ([\\d.]+)(?: \\((.*)\\))?\\s*$")

    fun estimatedPower(dump: String): List<Power> {
        val out = ArrayList<Power>()
        var inBlock = false
        for (line in dump.lineSequence()) {
            if (line.contains("Estimated power use")) { inBlock = true; continue }
            if (!inBlock) continue
            val m = POWER.matchEntire(line)
            if (m != null) {
                val parts = HashMap<String, Double>()
                m.groupValues[3].split(' ').forEach { kv ->
                    val i = kv.indexOf('='); if (i > 0) kv.substring(i + 1).toDoubleOrNull()?.let { parts[kv.substring(0, i)] = it }
                }
                out += Power(m.groupValues[1], m.groupValues[2].toDouble(), parts)
            } else if (line.isBlank() && out.isNotEmpty()) break
        }
        return out.sortedByDescending { it.mAh }
    }

    /** "Capacity: 5000, Computed drain: 210, actual drain: 200-220" line of the same block, or "". */
    fun powerHeader(dump: String): String =
        dump.lineSequence().firstOrNull { it.contains("Computed drain") }?.trim().orEmpty()

    data class UidStat(
        val uidName: String, var wakeMs: Long = 0, var wakeCount: Long = 0, var topWake: String = "", var topWakeMs: Long = 0,
        var jobMs: Long = 0, var syncMs: Long = 0, var cpuMs: Long = 0, var radioMs: Long = 0, var wifiMs: Long = 0,
        var fgsMs: Long = 0, var gpsMs: Long = 0, var sensorMs: Long = 0, var wakeups: Long = 0,
    )

    private val UID_HEAD = Regex("^  (u\\d+[as]\\d+|\\d+):\\s*$")
    private val WAKELOCK = Regex("^\\s+Wake lock (.+?): ((?:\\d+(?:ms|[dhms]) ?)+)(?: \\((\\d+) times\\))?")
    private val JOB = Regex("^\\s+Job (.+?): ((?:\\d+(?:ms|[dhms]) ?)+)")
    private val SYNC = Regex("^\\s+Sync (.+?): ((?:\\d+(?:ms|[dhms]) ?)+)")
    private val CPU = Regex("^\\s+Cpu: ((?:\\d+(?:ms|[dhms]) ?)+) usr \\+ ((?:\\d+(?:ms|[dhms]) ?)+) krn")
    private val RADIO = Regex("^\\s+Mobile radio active: ((?:\\d+(?:ms|[dhms]) ?)+)")
    private val WIFIRUN = Regex("^\\s+Wifi Running: ((?:\\d+(?:ms|[dhms]) ?)+)")
    private val FGS = Regex("^\\s+Foreground services?: ((?:\\d+(?:ms|[dhms]) ?)+)")
    private val GPS = Regex("^\\s+(?:GPS|Sensor GPS)[^:]*: ((?:\\d+(?:ms|[dhms]) ?)+)")
    private val SENSOR = Regex("^\\s+Sensor (?!GPS)[^:]*: ((?:\\d+(?:ms|[dhms]) ?)+)")
    private val WAKEUP_ALARM = Regex("^\\s+Wakeup alarm .*?: (\\d+) times")

    /** Per-uid activity counters of the dump: wakelocks, jobs, syncs, cpu, radio, wifi, foreground services, gps, sensors, alarm wakeups. */
    fun uidStats(dump: String): Map<String, UidStat> {
        val out = LinkedHashMap<String, UidStat>()
        var cur: UidStat? = null
        for (line in dump.lineSequence()) {
            val head = UID_HEAD.matchEntire(line)
            if (head != null) { cur = UidStat(head.groupValues[1]).also { s -> out[s.uidName] = s }; continue }
            val s = cur ?: continue
            if (!line.startsWith("    ")) { if (line.isNotBlank()) cur = null; continue }
            WAKELOCK.find(line)?.let { m ->
                val ms = durMs(m.groupValues[2]); s.wakeMs += ms; s.wakeCount += m.groupValues[3].toLongOrNull() ?: 0
                if (ms > s.topWakeMs) { s.topWakeMs = ms; s.topWake = m.groupValues[1] }
            }
            JOB.find(line)?.let { s.jobMs += durMs(it.groupValues[2]) }
            SYNC.find(line)?.let { s.syncMs += durMs(it.groupValues[2]) }
            CPU.find(line)?.let { s.cpuMs += durMs(it.groupValues[1]) + durMs(it.groupValues[2]) }
            RADIO.find(line)?.let { s.radioMs += durMs(it.groupValues[1]) }
            WIFIRUN.find(line)?.let { s.wifiMs += durMs(it.groupValues[1]) }
            FGS.find(line)?.let { s.fgsMs += durMs(it.groupValues[1]) }
            GPS.find(line)?.let { s.gpsMs += durMs(it.groupValues[1]) }
            SENSOR.find(line)?.let { s.sensorMs += durMs(it.groupValues[1]) }
            WAKEUP_ALARM.find(line)?.let { s.wakeups += it.groupValues[1].toLong() }
        }
        return out
    }

    // ── dumpsys alarm / activity services / jobscheduler / logcat ────────
    private val ALARM_STAT = Regex("^\\s*(?:u\\d+a\\d+:)?([A-Za-z0-9_.]+) \\+\\S+ running, (\\d+) wakeups:")

    /** package → alarm wakeups, from `Alarm Stats:`. */
    fun alarmWakeups(out: String): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (l in out.lineSequence()) ALARM_STAT.find(l)?.let { m.merge(it.groupValues[1], it.groupValues[2].toInt(), Int::plus) }
        return m
    }

    data class Svc(val pkg: String, val total: Int, val foreground: Int, val names: List<String>)

    private val SVC = Regex("ServiceRecord\\{\\S+ u\\d+ ([\\w.]+)/(\\S+?)\\}?\\s*$")

    /** Running services per package, with the foreground ones counted (the `isForeground=true` line under each record). */
    fun services(out: String): List<Svc> {
        val lines = out.lines()
        val by = LinkedHashMap<String, MutableList<Pair<String, Boolean>>>()
        for ((i, l) in lines.withIndex()) {
            val m = SVC.find(l.trim()) ?: continue
            val fg = (i + 1 until minOf(lines.size, i + 30)).takeWhile { !lines[it].contains("ServiceRecord{") }.any { lines[it].contains("isForeground=true") }
            by.getOrPut(m.groupValues[1]) { ArrayList() }.add(m.groupValues[2].removePrefix(m.groupValues[1]) to fg)
        }
        return by.map { (p, l) -> Svc(p, l.size, l.count { it.second }, l.map { it.first }) }.sortedWith(compareByDescending<Svc> { it.foreground }.thenByDescending { it.total })
    }

    private val START = Regex("Start proc (\\d+):([^/\\s]+)/(\\S+) for (.+?)(?: \\{|\\s*$)")

    /** `Start proc ...` lines → (process, reason kind) → count. Reasons are the words after "for" up to the component: service, broadcast, content provider, activity, job... */
    fun startReasons(out: String): Map<Pair<String, String>, Int> {
        val m = HashMap<Pair<String, String>, Int>()
        for (l in out.lineSequence()) START.find(l)?.let { k -> m.merge(k.groupValues[2].substringBefore(':') to k.groupValues[4].trim(), 1, Int::plus) }
        return m
    }

    private val JOB_LINE = Regex("JOB #\\S+/\\d+: \\S+ ([\\w.]+)/(\\S+)")

    /** package → (jobs, of which WorkManager's SystemJobService). */
    fun jobs(out: String): Map<String, Pair<Int, Int>> {
        val m = HashMap<String, Pair<Int, Int>>()
        for (l in out.lineSequence()) JOB_LINE.find(l)?.let { k ->
            val wm = if (k.groupValues[2].contains("SystemJobService") || k.groupValues[2].contains("androidx.work")) 1 else 0
            val o = m[k.groupValues[1]] ?: (0 to 0)
            m[k.groupValues[1]] = (o.first + 1) to (o.second + wm)
        }
        return m
    }

    // ── ranking ──────────────────────────────────────────────────────────
    private val COMPONENT_CAUSE = mapOf(
        "cpu" to "cpu (background work)", "wakelock" to "wakelock", "mobile_radio" to "mobile radio", "wifi" to "wifi", "gps" to "gps",
        "sensors" to "sensor", "camera" to "camera", "flashlight" to "flashlight", "bluetooth" to "bluetooth", "audio" to "audio", "video" to "video",
        "screen" to "screen",
    )

    private fun mins(ms: Long) = String.format(Locale.US, "%.1f min", ms / 60_000.0)

    /** The ranked suspects: estimated mAh per uid, the dominant cause, and every signal found for the package. */
    fun suspects(raw: Map<String, String>, top: Int = 15): List<Suspect> {
        val dump = raw["batterystats"].orEmpty()
        val pk = uidPackages(raw["packages"].orEmpty())
        val stats = uidStats(dump)
        val alarms = alarmWakeups(raw["alarm"].orEmpty())
        val svc = services(raw["services"].orEmpty()).associateBy { it.pkg }
        val starts = startReasons(raw["startproc"].orEmpty())
        val jobsBy = jobs(raw["jobs"].orEmpty())
        return estimatedPower(dump).filter { it.mAh > 0.0 }.take(top).mapIndexed { i, p ->
            val pkgs = uidOf(p.uidName)?.let { pk[it] }.orEmpty()
            val name = pkgs.joinToString("+").ifEmpty { p.uidName }
            val st = stats[p.uidName]
            val sig = ArrayList<String>()
            st?.takeIf { it.wakeMs > 0 }?.let { sig += "wakelock ${mins(it.wakeMs)}" + (if (it.topWake.isNotEmpty()) " (top: ${it.topWake})" else "") }
            st?.takeIf { it.jobMs > 0 }?.let { sig += "jobs ${mins(it.jobMs)}" }
            st?.takeIf { it.syncMs > 0 }?.let { sig += "syncs ${mins(it.syncMs)}" }
            st?.takeIf { it.fgsMs > 0 }?.let { sig += "foreground service ${mins(it.fgsMs)}" }
            st?.takeIf { it.cpuMs > 0 }?.let { sig += "cpu ${mins(it.cpuMs)}" }
            st?.takeIf { it.radioMs > 0 }?.let { sig += "mobile radio ${mins(it.radioMs)}" }
            st?.takeIf { it.gpsMs > 0 }?.let { sig += "gps ${mins(it.gpsMs)}" }
            val aw = pkgs.sumOf { alarms[it] ?: 0 } + (st?.wakeups?.toInt() ?: 0)
            if (aw > 0) sig += "$aw alarm wakeups"
            val sv = pkgs.mapNotNull { svc[it] }
            if (sv.isNotEmpty()) sig += "${sv.sumOf { it.total }} services running (${sv.sumOf { it.foreground }} foreground)"
            val jb = pkgs.mapNotNull { jobsBy[it] }
            if (jb.isNotEmpty()) sig += "${jb.sumOf { it.first }} pending jobs (${jb.sumOf { it.second }} WorkManager)"
            val reasons = starts.filterKeys { it.first in pkgs }.entries.groupBy({ it.key.second }, { it.value }).mapValues { it.value.sum() }
            if (reasons.isNotEmpty()) sig += "process starts: " + reasons.entries.sortedByDescending { it.value }.take(3).joinToString { "${it.value} for ${it.key}" }
            val dominant = p.parts.filter { it.key != "screen" }.maxByOrNull { it.value }?.takeIf { it.value > 0.0 }?.let { COMPONENT_CAUSE[it.key] ?: it.key }
            val cause = when {
                dominant != null && dominant != "cpu (background work)" -> dominant
                sv.any { it.foreground > 0 } -> "foreground service"
                (st?.wakeMs ?: 0) > 0 -> "wakelock"
                aw >= 20 -> "alarm"
                (st?.jobMs ?: 0) > 0 -> "job"
                reasons.keys.any { it.contains("provider") } -> "content provider"
                reasons.keys.any { it.contains("bind") || it.contains("service") } -> "service / binding"
                else -> dominant ?: "unknown"
            }
            Suspect(i + 1, name, p.mAh, "mAh", cause, sig.joinToString("; "), pkgs.any { it.startsWith(FLEET_PREFIX) })
        }
    }

    /** Fleet apps with what woke them: one line per fleet package seen in the stats, services, jobs, alarms or start reasons. */
    fun fleetLines(raw: Map<String, String>): List<String> {
        val pk = uidPackages(raw["packages"].orEmpty())
        val power = estimatedPower(raw["batterystats"].orEmpty()).associate { it.uidName to it.mAh }
        val byPkg = HashMap<String, Double>()
        for ((u, mah) in power) uidOf(u)?.let { pk[it] }?.forEach { if (it.startsWith(FLEET_PREFIX)) byPkg[it] = mah }
        val svc = services(raw["services"].orEmpty()).associateBy { it.pkg }
        val alarms = alarmWakeups(raw["alarm"].orEmpty())
        val jobsBy = jobs(raw["jobs"].orEmpty())
        val starts = startReasons(raw["startproc"].orEmpty())
        val all = (byPkg.keys + svc.keys + alarms.keys + jobsBy.keys + starts.keys.map { it.first }).filter { it.startsWith(FLEET_PREFIX) }.toSortedSet()
        return all.map { p ->
            val parts = ArrayList<String>()
            byPkg[p]?.let { parts += String.format(Locale.US, "%.1f mAh", it) }
            svc[p]?.let { parts += "${it.total} services (${it.foreground} fg)" }
            alarms[p]?.let { parts += "$it alarm wakeups" }
            jobsBy[p]?.let { parts += "${it.first} jobs" }
            val r = starts.filterKeys { it.first == p }.entries.sortedByDescending { it.value }.take(3)
            if (r.isNotEmpty()) parts += "woken by " + r.joinToString { "${it.value}x ${it.key.second}" }
            "$p: " + parts.joinToString(" · ")
        }.sortedByDescending { l -> Regex("([\\d.]+) mAh").find(l)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0 }
    }
}
