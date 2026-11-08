package com.diegonmarcos.superapp.xray

import com.diegonmarcos.superapp.system.DumpsysParsers
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
        var topWakeCount: Long = 0, var topWakeByCount: String = "", var fgActMs: Long = 0,
    ) {
        /** CPU time beyond the time the uid was on screen: the background share (an approximation; batterystats does not split it). */
        val bgCpuMs: Long get() = (cpuMs - fgActMs).coerceAtLeast(0)
    }

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
    private val FGACT = Regex("^\\s+Foreground activit(?:y|ies): ((?:\\d+(?:ms|[dhms]) ?)+)")
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
                val n = m.groupValues[3].toLongOrNull() ?: 0
                if (n > s.topWakeCount) { s.topWakeCount = n; s.topWakeByCount = m.groupValues[1] }
            }
            FGACT.find(line)?.let { s.fgActMs += durMs(it.groupValues[1]) }
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

    /** One wakelock of one uid: [count] acquires, [ms] held. */
    data class Wake(val uidName: String, val name: String, val ms: Long, val count: Long)

    /** Every per-uid wakelock of the dump (the dropdown ranks them by count and by duration). */
    fun wakelocks(dump: String): List<Wake> {
        val out = ArrayList<Wake>()
        var cur: String? = null
        for (line in dump.lineSequence()) {
            val head = UID_HEAD.matchEntire(line)
            if (head != null) { cur = head.groupValues[1]; continue }
            val u = cur ?: continue
            if (!line.startsWith("    ")) { if (line.isNotBlank()) cur = null; continue }
            WAKELOCK.find(line)?.let { m -> out += Wake(u, m.groupValues[1], durMs(m.groupValues[2]), m.groupValues[3].toLongOrNull() ?: 0) }
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

    /** The composite score's inputs: a family's share is `value / REF`, at most 1, and counts with its weight in the noisy-OR. */
    const val W_POWER = 0.5
    const val W_WAKES = 0.6
    const val W_POSTS = 0.8
    const val W_BG_CPU = 0.5
    const val W_JANK = 0.6
    const val REF_MAH = 50.0
    const val REF_WAKES = 2_000.0
    const val REF_POSTS = 1_000.0
    const val REF_BG_CPU_MIN = 15.0
    const val REF_JANK_PCT = 25.0
    private val SUPERAPP = FLEET_PREFIX + "superapp"

    private class Cand(val uidName: String?, val pkgs: List<String>, val name: String) {
        var mAh = 0.0
        var power: Power? = null
    }

    private class Sig(val key: String, val share: Double, val weight: Double, val why: String) { val pts get() = weight * share.coerceIn(0.0, 1.0) }

    /**
     * The ranked suspects, a composite across families, not power alone (a notification storm costs almost no mAh and still destroys
     * responsiveness). Per candidate (a uid of the battery stats, or a package that only the notification stats name), each family
     * gives a share 0..1 (value / its reference, saturating): power (mAh), wakelock acquire COUNT, notification post rate, background
     * CPU time, and jank % (the frame stats are the SuperApp's own). Each family has a weight (power .5, wakelock count .6,
     * notification posts .8, background cpu .5, jank .6) and the score is 100 x the noisy-OR (1 - prod(1 - weight x share)), so one
     * saturated family scores its weight x 100 (a notification storm 80, more than any single power hog) and several families stack. The dominant family is the row's
     * "why"; the other signals follow in the detail. Ties go to the higher mAh.
     */
    fun suspects(raw: Map<String, String>, top: Int = 15): List<Suspect> {
        val dump = raw["batterystats"].orEmpty()
        val pk = uidPackages(raw["packages"].orEmpty())
        val stats = uidStats(dump)
        val alarms = alarmWakeups(raw["alarm"].orEmpty())
        val svc = services(raw["services"].orEmpty()).associateBy { it.pkg }
        val starts = startReasons(raw["startproc"].orEmpty())
        val jobsBy = jobs(raw["jobs"].orEmpty())
        val notif = DumpsysParsers.notificationStats(raw["notification"].orEmpty()).associateBy { it.pkg }
        val jank = raw["gfxinfo"]?.let { DumpsysParsers.gfxinfo(it) }?.jankyPct

        val cands = ArrayList<Cand>()
        val seen = HashSet<String>()
        fun uidCand(uidName: String): Cand? {
            if (!seen.add(uidName)) return null
            val pkgs = uidOf(uidName)?.let { pk[it] }.orEmpty()
            return Cand(uidName, pkgs, pkgs.joinToString("+").ifEmpty { uidName }).also { cands += it }
        }
        val powers = estimatedPower(dump)
        for (p in powers) uidCand(p.uidName)?.let { it.power = p; it.mAh = p.mAh }
        for ((u, st) in stats) if (st.wakeCount > 0 || st.cpuMs > 0) uidCand(u)
        // a package only the notification stats (or the frame stats) name gets a candidate of its own
        for (extra in notif.keys + listOfNotNull(SUPERAPP.takeIf { jank != null })) if (cands.none { extra in it.pkgs }) cands += Cand(null, listOf(extra), extra)

        val scored = cands.map { c ->
            val st = c.uidName?.let { stats[it] }
            val sigs = ArrayList<Sig>()
            if (c.mAh > 0) sigs += Sig("power", c.mAh / REF_MAH, W_POWER, String.format(Locale.US, "%.1f mAh", c.mAh))
            st?.takeIf { it.wakeCount > 0 }?.let {
                sigs += Sig("wakelock count", it.wakeCount / REF_WAKES, W_WAKES,
                    "${it.wakeCount} wakelock acquires" + (if (it.topWakeByCount.isNotEmpty()) " (top: ${it.topWakeByCount} x${it.topWakeCount})" else ""))
            }
            val posts = c.pkgs.sumOf { notif[it]?.total ?: 0L }
            if (posts > 0) sigs += Sig("notification storm", posts / REF_POSTS, W_POSTS, "$posts notification posts")
            st?.takeIf { it.bgCpuMs > 0 }?.let { sigs += Sig("background cpu", it.bgCpuMs / 60_000.0 / REF_BG_CPU_MIN, W_BG_CPU, "background cpu ${mins(it.bgCpuMs)}") }
            if (jank != null && SUPERAPP in c.pkgs) sigs += Sig("jank", jank / REF_JANK_PCT, W_JANK, String.format(Locale.US, "%.1f%% janky frames", jank))
            var rest = 1.0
            for (g in sigs) rest *= 1.0 - g.pts
            Triple(c, sigs, 100.0 * (1.0 - rest))
        }.filter { it.third > 0.0 }.sortedWith(compareByDescending<Triple<Cand, List<Sig>, Double>> { it.third }.thenByDescending { it.first.mAh })

        return scored.take(top).mapIndexed { i, (c, sigs, score) ->
            val pkgs = c.pkgs
            val st = c.uidName?.let { stats[it] }
            val sig = ArrayList<String>()
            val dominant = sigs.maxByOrNull { it.pts }
            dominant?.let { sig += "why: ${it.why}" }
            sigs.filter { it !== dominant }.sortedByDescending { it.pts }.forEach { sig += it.why }
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
            val partDominant = c.power?.parts.orEmpty().filter { it.key != "screen" }.maxByOrNull { it.value }?.takeIf { it.value > 0.0 }?.let { COMPONENT_CAUSE[it.key] ?: it.key }
            val powerCause = when {
                partDominant != null && partDominant != "cpu (background work)" -> partDominant
                sv.any { it.foreground > 0 } -> "foreground service"
                (st?.wakeMs ?: 0) > 0 -> "wakelock"
                aw >= 20 -> "alarm"
                (st?.jobMs ?: 0) > 0 -> "job"
                reasons.keys.any { it.contains("provider") } -> "content provider"
                reasons.keys.any { it.contains("bind") || it.contains("service") } -> "service / binding"
                else -> partDominant ?: "unknown"
            }
            val cause = if (dominant == null || dominant.key == "power") powerCause else dominant.key
            Suspect(i + 1, c.name, score, "pts", cause, sig.joinToString("; "), pkgs.any { it.startsWith(FLEET_PREFIX) }, c.mAh)
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
