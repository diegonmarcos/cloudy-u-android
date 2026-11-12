package com.diegonmarcos.superapp.system

import com.diegonmarcos.superapp.xray.XrayAnalyze
import java.util.Locale

/** One label/value line of a dropdown group. */
data class DdLine(val label: String, val value: String)

/**
 * Pure parsers for the dumpsys text the status-bar dropdowns show (the shell broker supplies the text; nothing here touches a
 * device). Each is tolerant: a format it does not recognise yields no lines, never an exception.
 */
object DumpsysParsers {

    private fun f1(d: Double) = String.format(Locale.US, "%.1f", d)

    /** Bytes as B / kB / MB / GB (the one formatter; SystemGroups.bytes delegates here so the parsers stay JVM-pure). */
    fun bytes(b: Long): String = when {
        b >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", b / 1_073_741_824.0)
        b >= 1_048_576L -> String.format(Locale.US, "%.1f MB", b / 1_048_576.0)
        b >= 1024L -> String.format(Locale.US, "%.0f kB", b / 1024.0)
        else -> "$b B"
    }

    // ── dumpsys activity services (Developer options > Running services) ──

    data class Svc(val pkg: String, val cls: String, val foreground: Boolean, val pid: Int?, val process: String?, val since: String?)

    /** Every ServiceRecord of `dumpsys activity services`. */
    fun services(text: String): List<Svc> {
        val out = ArrayList<Svc>()
        for (block in text.split("* ServiceRecord{").drop(1)) {
            val head = block.lineSequence().first()
            val cmp = Regex("""u\d+\s+(\S+?)(?:\}|\s)""").find(head)?.groupValues?.get(1) ?: continue
            val pkg = cmp.substringBefore('/')
            val cls = cmp.substringAfter('/', "").let { if (it.startsWith(".")) pkg + it else it }
            val app = Regex("""app=ProcessRecord\{\S+ (\d+):([^/}]+)""").find(block)
            out += Svc(pkg, cls, Regex("""isForeground=true""").containsMatchIn(block),
                app?.groupValues?.get(1)?.toIntOrNull(), app?.groupValues?.get(2),
                Regex("""createTime=(\S+)""").find(block)?.groupValues?.get(1)?.trimStart('-'))
        }
        return out
    }

    /** Header line (processes and services), then one line per package: its services, foreground count, pid, uptime; busiest first. */
    fun runningServices(text: String, rows: Int): List<DdLine> {
        val all = services(text)
        if (all.isEmpty()) return emptyList()
        val byPkg = all.groupBy { it.pkg }
        val procs = all.mapNotNull { it.pid }.toSet().size
        val head = DdLine("Total", "${all.size} services in ${byPkg.size} apps, $procs processes, ${all.count { it.foreground }} foreground")
        val lines = byPkg.entries.sortedWith(compareByDescending<Map.Entry<String, List<Svc>>> { e -> e.value.count { it.foreground } }.thenByDescending { it.value.size }.thenBy { it.key })
            .take(rows).map { (pkg, v) ->
                val fg = v.count { it.foreground }
                val pid = v.firstNotNullOfOrNull { it.pid }
                val up = v.firstNotNullOfOrNull { it.since }
                DdLine(pkg.removePrefix("com.diegonmarcos."), "${v.size} svc" + (if (fg > 0) " · $fg fg" else "") + (pid?.let { " · pid $it" } ?: "") + (up?.let { " · $it" } ?: ""))
            }
        return listOf(head) + lines
    }

    // ── dumpsys procstats --hours 3 (Developer options > Memory) ──

    /** Process, average PSS in MB, and the "min-avg-max" PSS text of its TOTAL line. */
    data class ProcMem(val process: String, val avgMb: Double, val range: String)

    private fun mb(token: String): Double? {
        val m = Regex("""([\d.]+)\s*(KB|MB|GB|B)""", RegexOption.IGNORE_CASE).find(token) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2].uppercase()) { "GB" -> v * 1024; "MB" -> v; "KB" -> v / 1024; else -> v / 1048576 }
    }

    fun procstats(text: String): List<ProcMem> {
        val out = ArrayList<ProcMem>()
        var proc: String? = null
        for (line in text.lineSequence()) {
            val h = Regex("""^\s*\*\s+(\S+)\s+/\s+\S+\s+/\s+\S+:""").find(line)
            if (h != null) proc = h.groupValues[1]
            val t = Regex("""^\s*TOTAL:\s*[\d.]+%\s*\(([^)/]*)""").find(line) ?: continue
            val p = proc ?: continue
            val parts = t.groupValues[1].trim().split('-')
            val avg = parts.getOrNull(1)?.let { a -> mb(a) ?: mb(a + (parts.firstOrNull()?.takeLast(2) ?: "MB")) } ?: continue
            out += ProcMem(p, avg, t.groupValues[1].trim())
            proc = null
        }
        return out
    }

    /** Header lines the screen shows (Total RAM, free, used), then the heaviest processes by 3 h average. */
    fun memory3h(text: String, rows: Int): List<DdLine> {
        val head = listOf("Total RAM", "Free RAM", "Used RAM", "Lost RAM").mapNotNull { k ->
            Regex("""^\s*$k:\s*(.+)$""", RegexOption.MULTILINE).find(text)?.let { DdLine(k, it.groupValues[1].trim()) }
        }
        val procs = procstats(text).groupBy { it.process }.map { (_, v) -> v.maxByOrNull { it.avgMb }!! }
            .sortedByDescending { it.avgMb }.take(rows).map { DdLine(it.process.removePrefix("com.diegonmarcos."), "avg ${f1(it.avgMb)} MB · ${it.range}") }
        return head + procs
    }

    private val AVG_MB = Regex("""^avg ([0-9]+(?:\.[0-9]+)?) MB""")

    /**
     * #1638 the Last 3h Memory Usage GRAPH: for each [memory3h] line, its bar as a fraction of the heaviest process's 3 h average
     * PSS (0..1), or null for a header line (Total / Free / Used / Lost RAM) or a value that is not a process average. Never throws.
     */
    fun memory3hBars(lines: List<DdLine>): List<Float?> {
        val mb = lines.map { AVG_MB.find(it.value)?.groupValues?.get(1)?.toDoubleOrNull() }
        val max = mb.filterNotNull().maxOrNull()?.takeIf { it > 0.0 } ?: return lines.map { null }
        return mb.map { v -> v?.let { (it / max).toFloat().coerceIn(0f, 1f) } }
    }

    // ── dumpsys batterystats --charged ──

    data class Drain(val name: String, val mah: Double, val detail: String)
    data class BatteryStats(val times: List<DdLine>, val capacity: String?, val components: List<Drain>, val uids: List<Drain>)

    private val TIME_KEYS = listOf("Time on battery", "Time on battery screen off", "Time on battery screen on", "Start clock time", "Total run time", "Screen on discharge", "Screen off discharge", "Amount discharged")

    fun batteryStats(text: String): BatteryStats {
        val times = ArrayList<DdLine>(); val seen = HashSet<String>()
        var capacity: String? = null
        val comps = ArrayList<Drain>(); val uids = ArrayList<Drain>()
        var inBlock = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (inBlock) {
                if (line.isEmpty()) { inBlock = false; continue }
                val cap = Regex("""^Capacity:\s*(.*)$""").find(line)
                val uid = Regex("""^Uid (\S+):\s*([\d.]+)(.*)$""").find(line)
                val comp = Regex("""^([A-Za-z][A-Za-z0-9 /_-]*?):\s*([\d.]+)(.*)$""").find(line)
                if (cap != null) capacity = cap.groupValues[1]
                else if (uid != null) uids += Drain(uid.groupValues[1], uid.groupValues[2].toDouble(), uid.groupValues[3].trim())
                else if (comp != null) comps += Drain(comp.groupValues[1], comp.groupValues[2].toDouble(), comp.groupValues[3].trim())
            } else if (line.startsWith("Estimated power use (mAh)")) inBlock = true
            else {
                val key = TIME_KEYS.firstOrNull { line.startsWith("$it:") } ?: continue
                if (seen.add(key)) times += DdLine(key, line.substringAfter(':').trim())
            }
        }
        return BatteryStats(times, capacity, comps, uids)
    }

    private val UID_ROW = Regex("""^uid\s+(\S+)$""", RegexOption.IGNORE_CASE)

    /**
     * Since-last-charge overall usage: the time on battery, the capacity and drain, then each component's mAh.
     * A component that is really a uid row ("UID u0a355") keeps its raw uid and gains the name [label] resolves
     * ("UID u0a355 · Cloud Store"); categories (screen, wifi, cpu...) are untouched.
     */
    fun usageInfos(s: BatteryStats, label: (String) -> String = { it }): List<DdLine> =
        s.times + listOfNotNull(s.capacity?.let { DdLine("Capacity (mAh)", it) }) +
            s.components.sortedByDescending { it.mah }.map { DdLine(uidRowName(it.name, label), "${f1(it.mah)} mAh") }

    /** "UID u0a355" -> "UID u0a355 · <name>" when [label] knows it (name == uid or blank: unchanged); other names as is. */
    fun uidRowName(name: String, label: (String) -> String): String {
        val uid = UID_ROW.find(name)?.groupValues?.get(1) ?: return name
        val n = runCatching { label(uid) }.getOrNull()?.trim().orEmpty()
        return if (n.isEmpty() || n == uid) name else "$name · $n"
    }

    /** Since-last-charge per-app (per-uid) usage, heaviest first; [label] turns "u0a143" into an app name. */
    fun perApp(s: BatteryStats, rows: Int, label: (String) -> String): List<DdLine> {
        val total = s.uids.sumOf { it.mah }.takeIf { it > 0 } ?: 1.0
        return s.uids.sortedByDescending { it.mah }.take(rows).map {
            DdLine(label(it.name), "${f1(it.mah)} mAh · ${f1(it.mah * 100 / total)}%" + if (it.detail.isNotEmpty()) " ${it.detail.take(60)}" else "")
        }
    }

    /** "u0a143" -> 10143, "1000" -> 1000, "u10a5" -> 1010005; null for a name that is no uid. */
    fun uidOf(name: String): Int? {
        name.toIntOrNull()?.let { return it }
        val m = Regex("""^u(\d+)a(\d+)$""").find(name) ?: return null
        return m.groupValues[1].toInt() * 100_000 + 10_000 + m.groupValues[2].toInt()
    }

    // ── /proc/meminfo, thermalservice ──

    /** /proc/meminfo as kB by key. */
    fun meminfo(text: String): Map<String, Long> = text.lineSequence().mapNotNull { l ->
        val i = l.indexOf(':'); if (i <= 0) null else l.substring(0, i) to (l.substring(i + 1).trim().substringBefore(' ').toLongOrNull() ?: return@mapNotNull null)
    }.toMap()

    /** `Temperature{mValue=36.5, mType=3, mName=skin, ...}` of `dumpsys thermalservice`. */
    fun thermal(text: String): List<DdLine> =
        Regex("""Temperature\{mValue=(-?[\d.]+), mType=\d+, mName=([^,}]+)""").findAll(text).map { DdLine(it.groupValues[2], "${it.groupValues[1]} C") }.distinctBy { it.label }.toList()

    private fun short(name: String) = name.removePrefix("com.diegonmarcos.")

    /** `dumpsys thermalservice`, in full: the throttling status, every sensor (with its throttle level when above none) and the cooling devices. */
    fun thermalFull(text: String): List<DdLine> {
        val out = ArrayList<DdLine>()
        val names = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")
        Regex("""(?i)(?:current )?thermal status:?\s*(-?\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
            out += DdLine("Throttle status", "${names.getOrNull(it) ?: "unknown"} ($it)")
        }
        Regex("""(?i)thermal headroom\D{0,12}(-?[\d.]+)""").find(text)?.groupValues?.get(1)?.let { out += DdLine("Headroom", it) }
        val types = mapOf(0 to "cpu", 1 to "gpu", 2 to "battery", 3 to "skin", 4 to "usb", 5 to "pa", 6 to "bcl-v", 7 to "bcl-i", 8 to "bcl-%", 9 to "npu")
        val sensors = Regex("""Temperature\{mValue=(-?[\d.]+), mType=(-?\d+), mName=([^,}]+)(?:, mStatus=(-?\d+))?""").findAll(text)
            .map { m ->
                val st = m.groupValues[4].toIntOrNull() ?: 0
                DdLine(m.groupValues[3], "${m.groupValues[1]} C" + (types[m.groupValues[2].toIntOrNull()]?.let { " · $it" } ?: "") + if (st > 0) " · THROTTLE ${names.getOrNull(st) ?: st}" else "")
            }.distinctBy { it.label }.toList()
        out += sensors
        out += Regex("""CoolingDevice\{mValue=(-?\d+), mType=-?\d+, mName=([^,}]+)""").findAll(text)
            .map { DdLine("cool " + it.groupValues[2], "level ${it.groupValues[1]}") }.distinctBy { it.label }.toList()
        return out
    }

    // ── dumpsys gfxinfo <pkg> framestats ──

    /** The summary of `dumpsys gfxinfo`: frames, jank, percentiles (ms), the slow-frame counters, the frame-time histogram, and any draw/triangle/GPU-memory lines this build prints. */
    data class GfxStats(
        val total: Long?, val janky: Long?, val jankyPct: Double?, val percentiles: Map<Int, Double>,
        val slow: List<Pair<String, Long>>, val histogram: List<Pair<Int, Long>>, val extras: List<DdLine>,
    ) { val isEmpty get() = total == null && percentiles.isEmpty() && slow.isEmpty() && histogram.isEmpty() && extras.isEmpty() }

    private val GFX_EXTRA = Regex("""(?im)^[ \t]*([^:=\n]*(?:draw ?calls?|triangles?|vertices|vertex|gpu memory|total memory usage|texture memory|layer memory|path cache|font cache)[^:=\n]*?)[ \t]*[:=][ \t]*(\S[^\n]*)$""")
    private val GFX_SLOW = Regex("""(?m)^\s*Number ([^:(\n]+?):\s*(\d+)\s*$""")

    /** Null when [text] holds no gfxinfo at all. Draw-call / triangle / GPU-memory lines exist only on some builds: parsed when present, skipped silently otherwise. */
    fun gfxinfo(text: String): GfxStats? {
        val total = Regex("""Total frames rendered:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
        val jm = Regex("""Janky frames:\s*(\d+)\s*\(([\d.]+)%\)""").find(text)
        val janky = jm?.groupValues?.get(1)?.toLongOrNull()
        val pct = jm?.groupValues?.get(2)?.toDoubleOrNull() ?: if (total != null && total > 0 && janky != null) janky * 100.0 / total else null
        val perc = Regex("""(\d+)th percentile:\s*([\d.]+)\s*ms""").findAll(text).mapNotNull { m -> m.groupValues[2].toDoubleOrNull()?.let { m.groupValues[1].toInt() to it } }.distinctBy { it.first }.toMap()
        val slow = GFX_SLOW.findAll(text).mapNotNull { m -> m.groupValues[2].toLongOrNull()?.let { m.groupValues[1].trim() to it } }.distinctBy { it.first }.toList()
        val hist = Regex("""(?m)^\s*HISTOGRAM:(.*)$""").find(text)?.groupValues?.get(1)?.let { h ->
            Regex("""(\d+)ms=(\d+)""").findAll(h).mapNotNull { m -> m.groupValues[2].toLongOrNull()?.let { m.groupValues[1].toInt() to it } }.toList()
        }.orEmpty()
        val extras = GFX_EXTRA.findAll(text).map { DdLine(it.groupValues[1].trim(), it.groupValues[2].trim().take(60)) }.distinctBy { it.label }.take(12).toList()
        return GfxStats(total, janky, pct, perc, slow, hist, extras).takeUnless { it.isEmpty }
    }

    /** Live graphics lines: frames and jank, the percentiles, then the draw-call / triangle / GPU-memory lines when the build prints them. */
    fun gfxinfoLines(text: String): List<DdLine> {
        val g = gfxinfo(text) ?: return emptyList()
        val out = ArrayList<DdLine>()
        if (g.total != null) out += DdLine("Frames", "${g.total} rendered" + (g.janky?.let { " · $it janky" + (g.jankyPct?.let { p -> " (${f1(p)}%)" } ?: "") } ?: ""))
        if (g.percentiles.isNotEmpty()) out += DdLine("Frame time", g.percentiles.toSortedMap().entries.joinToString(" · ") { "p${it.key} ${f1(it.value)} ms" })
        out += g.extras
        return out
    }

    /** Jank diagnosis: the slow-frame causes, biggest first, then the frame-time histogram (non-empty buckets). */
    fun gfxinfoJank(text: String): List<DdLine> {
        val g = gfxinfo(text) ?: return emptyList()
        val out = ArrayList<DdLine>()
        if (g.jankyPct != null) out += DdLine("Jank", "${f1(g.jankyPct)}%" + (g.janky?.let { " · $it frames" } ?: ""))
        out += g.slow.sortedByDescending { it.second }.filter { it.second > 0 }.map { DdLine(it.first, it.second.toString()) }
        val buckets = g.histogram.filter { it.second > 0 }
        if (buckets.isNotEmpty()) out += DdLine("Histogram", buckets.take(14).joinToString(" ") { "${it.first}ms:${it.second}" })
        return out
    }

    // ── top / dumpsys cpuinfo ──

    data class TopProc(val pid: Int, val cpu: Double, val name: String)

    private val TOP_ROW = Regex("""^\s*(\d+)\s+(\S+)\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+[A-Za-z]\s+(\d+(?:\.\d+)?)\s+\d+(?:\.\d+)?\s+\S+\s+(.+?)\s*$""")
    private val CPUINFO_ROW = Regex("""^\s*([\d.]+)%\s+(\d+)/([^:]+):""")

    /** Processes by CPU, busiest first: toybox `top -b -n 1` rows, or `dumpsys cpuinfo` rows as the fallback. */
    fun topProcs(text: String): List<TopProc> {
        val out = ArrayList<TopProc>()
        for (l in text.lineSequence()) {
            val t = TOP_ROW.find(l)
            if (t != null) { out += TopProc(t.groupValues[1].toInt(), t.groupValues[3].toDouble(), t.groupValues[4]); continue }
            val c = CPUINFO_ROW.find(l) ?: continue
            out += TopProc(c.groupValues[2].toInt(), c.groupValues[1].toDouble(), c.groupValues[3].trim())
        }
        return out.filter { it.name != "top" }.sortedByDescending { it.cpu }
    }

    /** The load line (when printed), then the top [rows] processes: name, %cpu, pid. */
    fun topLines(text: String, rows: Int): List<DdLine> {
        val procs = topProcs(text)
        if (procs.isEmpty()) return emptyList()
        val out = ArrayList<DdLine>()
        Regex("""Load:\s*([\d.]+)\s*/\s*([\d.]+)\s*/\s*([\d.]+)""").find(text)?.let { out += DdLine("Load 1/5/15m", "${it.groupValues[1]} / ${it.groupValues[2]} / ${it.groupValues[3]}") }
        Regex("""(\d+)%cpu\s+(\d+)%user.*?(\d+)%idle""").find(text)?.let { m ->
            val all = m.groupValues[1].toInt(); val idle = m.groupValues[3].toInt()
            out += DdLine("CPU busy", "${all - idle}% of ${all}%")
        }
        out += procs.take(rows).map { DdLine("top " + short(it.name), "${f1(it.cpu)}% · pid ${it.pid}") }
        return out
    }

    // ── dumpsys notification ──

    /** Notifications an app posted ([posts] new + [updates] re-posts, from the usage stats) and how many are active now. */
    data class NotifStat(val pkg: String, val posts: Long, val updates: Long, val active: Int) { val total get() = posts + updates }

    /** Per-app notification counts, busiest first: `AggregatedStats{key=..., numPostedByApp=..., numUpdatedByApp=...}` blocks plus the active `NotificationRecord(... pkg=...)` ones. */
    fun notificationStats(text: String): List<NotifStat> {
        val posted = HashMap<String, Long>(); val updated = HashMap<String, Long>(); val active = HashMap<String, Int>()
        for (chunk in text.split("AggregatedStats{").drop(1)) {
            val key = Regex("""key='?([A-Za-z0-9_.]+)'?""").find(chunk)?.groupValues?.get(1)?.takeIf { '.' in it } ?: continue
            // the chunk runs to the next AggregatedStats, so it holds this key's counters only
            Regex("""numPostedByApp=(\d+)""").find(chunk)?.groupValues?.get(1)?.toLongOrNull()?.let { posted.merge(key, it, Long::plus) }
            Regex("""numUpdatedByApp=(\d+)""").find(chunk)?.groupValues?.get(1)?.toLongOrNull()?.let { updated.merge(key, it, Long::plus) }
        }
        for (m in Regex("""NotificationRecord\([^)\n]*?pkg=([A-Za-z0-9_.]+)""").findAll(text)) active.merge(m.groupValues[1], 1, Int::plus)
        return (posted.keys + updated.keys + active.keys).map { NotifStat(it, posted[it] ?: 0, updated[it] ?: 0, active[it] ?: 0) }
            .sortedWith(compareByDescending<NotifStat> { it.total }.thenByDescending { it.active }.thenBy { it.pkg })
    }

    fun notifications(text: String, rows: Int): List<DdLine> {
        val all = notificationStats(text)
        if (all.isEmpty()) return emptyList()
        val head = DdLine("Total posts", "${all.sumOf { it.total }} from ${all.size} apps · ${all.sumOf { it.active }} active")
        return listOf(head) + all.take(rows).map {
            DdLine(short(it.pkg), "${it.total} posts (${it.posts} new + ${it.updates} updates)" + if (it.active > 0) " · ${it.active} active" else "")
        }
    }

    // ── dumpsys binder_calls_stats (what a non-root shell can get; absent or empty on many builds) ──

    data class BinderStat(val uid: String, val calls: Long)

    fun binderCalls(text: String): List<BinderStat> {
        val by = HashMap<String, Long>()
        for (l in text.lineSequence()) {
            val m = Regex("""(?i)\buid[=: ]+(\d+)\b.*?\b(?:call_?count|calls?|count|transactions?)[=: ]+(\d+)""").find(l) ?: continue
            m.groupValues[2].toLongOrNull()?.let { by.merge(m.groupValues[1], it, Long::plus) }
        }
        return by.map { BinderStat(it.key, it.value) }.sortedByDescending { it.calls }
    }

    fun binder(text: String, rows: Int, label: (String) -> String = { it }): List<DdLine> {
        val all = binderCalls(text)
        if (all.isEmpty()) return emptyList()
        return listOf(DdLine("Total calls", "${all.sumOf { it.calls }} from ${all.size} uids")) +
            all.take(rows).map { DdLine(runCatching { label(it.uid) }.getOrDefault(it.uid), "${it.calls} calls") }
    }

    // ── dumpsys meminfo (full) ──

    data class PssProc(val name: String, val pid: Int?, val kb: Long)

    /** The "Total PSS by process:" table: `123,456K: system_server (pid 1234 / activities)`. */
    fun meminfoProcs(text: String): List<PssProc> {
        val out = ArrayList<PssProc>()
        var inTable = false
        for (l in text.lineSequence()) {
            if (l.trimStart().startsWith("Total PSS by process")) { inTable = true; continue }
            if (!inTable) continue
            if (l.isBlank()) { if (out.isNotEmpty()) break else continue }
            val m = Regex("""^\s*([\d,]+)K:\s+(\S+)(?:\s+\(pid (\d+))?""").find(l) ?: if (out.isNotEmpty()) break else continue
            m.groupValues[1].replace(",", "").toLongOrNull()?.let { out += PssProc(m.groupValues[2], m.groupValues[3].toIntOrNull(), it) }
        }
        return out
    }

    /** RAM totals of the dump (Total / Free / Used / Lost RAM, ZRAM), then the heaviest processes by PSS. */
    fun meminfoDetail(text: String, rows: Int): List<DdLine> {
        val head = listOf("Total RAM", "Free RAM", "Used RAM", "Lost RAM", "ZRAM").mapNotNull { k ->
            Regex("""^\s*$k:\s*(.+)$""", RegexOption.MULTILINE).find(text)?.let { DdLine(k, it.groupValues[1].trim()) }
        }
        val procs = meminfoProcs(text).sortedByDescending { it.kb }.take(rows).map { DdLine(short(it.name), "${f1(it.kb / 1024.0)} MB PSS" + (it.pid?.let { p -> " · pid $p" } ?: "")) }
        return head + procs
    }

    // ── ANR / responsiveness ──

    data class Anr(val whenText: String, val pkg: String?, val detail: String)

    /** `am_anr` events of the events log (`[user,pid,package,flags,reason]`) and `ls -l /data/anr` trace files. */
    fun anrs(text: String): List<Anr> {
        val out = ArrayList<Anr>()
        for (l in text.lineSequence()) {
            val e = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d)\S*.*?am_anr\W+\[(-?\d+),(\d+),([^,\]]+),(-?\d+),([^\]]*)\]""").find(l)
            if (e != null) { out += Anr(e.groupValues[1], e.groupValues[4], e.groupValues[6].trim().take(80)); continue }
            val f = Regex("""^\S+\s+\d+\s+\S+\s+\S+\s+(\d+)\s+(\d{4}-\d\d-\d\d \d\d:\d\d)\s+(\S+)\s*$""").find(l) ?: continue
            if (f.groupValues[3].contains("anr", true)) out += Anr(f.groupValues[2], null, "trace ${f.groupValues[3]} (${f.groupValues[1].toLong() / 1024} kB)")
        }
        return out
    }

    /** A healthy device has none: a readable dump with no ANR says so, an empty/unreadable one yields nothing. */
    fun anrLines(text: String, rows: Int): List<DdLine> {
        val all = anrs(text)
        if (all.isEmpty()) return if (text.isNotBlank() && !text.contains("No such file", true) && !text.contains("Permission denied", true)) listOf(DdLine("ANRs", "none recorded")) else emptyList()
        return listOf(DdLine("ANRs", "${all.size} recorded")) + all.takeLast(rows).reversed().map { DdLine(it.whenText, (it.pkg?.let { p -> short(p) + " · " } ?: "") + it.detail) }
    }

    // ── batterystats diagnosis rows (power family) ──

    /** Wakelocks of the batterystats dump: the top by acquire COUNT, then the top by held DURATION. */
    fun wakelockLines(text: String, rows: Int, label: (String) -> String = { it }): List<DdLine> {
        val all = XrayAnalyze.wakelocks(text)
        if (all.isEmpty()) return emptyList()
        fun row(w: XrayAnalyze.Wake) = DdLine(w.name.take(34), "${w.count}x · ${f1(w.ms / 60_000.0)} min · ${runCatching { label(w.uidName) }.getOrDefault(w.uidName)}")
        val n = (rows / 2).coerceAtLeast(1)
        return listOf(DdLine("By count", "top $n")) + all.sortedByDescending { it.count }.take(n).map(::row) +
            listOf(DdLine("By duration", "top $n")) + all.sortedByDescending { it.ms }.take(n).map(::row)
    }

    /** CPU per uid (background share = cpu beyond foreground time), foreground services, GPS, sensors and alarm wakeups; busiest first. */
    fun uidActivityLines(text: String, rows: Int, label: (String) -> String = { it }): List<DdLine> =
        XrayAnalyze.uidStats(text).values.filter { it.cpuMs + it.gpsMs + it.sensorMs + it.fgsMs > 0 }
            .sortedByDescending { it.cpuMs + it.gpsMs + it.sensorMs }.take(rows).map { s ->
                fun m(ms: Long) = f1(ms / 60_000.0)
                DdLine(runCatching { label(s.uidName) }.getOrDefault(s.uidName),
                    "cpu ${m(s.cpuMs)} min (bg ${m(s.bgCpuMs)})" + (if (s.fgsMs > 0) " · fgs ${m(s.fgsMs)}" else "") +
                        (if (s.gpsMs > 0) " · gps ${m(s.gpsMs)}" else "") + (if (s.sensorMs > 0) " · sensors ${m(s.sensorMs)}" else "") +
                        (if (s.wakeups > 0) " · ${s.wakeups} wakeups" else ""))
            }

    /** Alarm wakeups per package, from `dumpsys alarm`. */
    fun alarmLines(text: String, rows: Int): List<DdLine> =
        XrayAnalyze.alarmWakeups(text).entries.sortedByDescending { it.value }.take(rows).map { DdLine(short(it.key), "${it.value} wakeups") }

    /** Scheduled jobs per package (WorkManager's share), from `dumpsys jobscheduler`. */
    fun jobLines(text: String, rows: Int): List<DdLine> =
        XrayAnalyze.jobs(text).entries.sortedByDescending { it.value.first }.take(rows).map { DdLine(short(it.key), "${it.value.first} jobs (${it.value.second} WorkManager)") }

    // ── ② Storage tracker: df, dumpsys diskstats ──

    private fun kbText(kb: Long): String = bytes(kb * 1024)

    /** `df -h`: one line per real mount (/data, /storage/emulated, /sdcard, /cache...): size, used, free, use%. Pseudo filesystems are skipped. */
    fun dfLines(text: String, rows: Int): List<DdLine> =
        text.lineSequence().mapNotNull { l ->
            val t = l.trim().split(Regex("\\s+"))
            if (t.size < 6 || !t[4].endsWith("%")) return@mapNotNull null
            val mount = t[5]
            if (!(mount == "/data" || mount == "/" || mount.startsWith("/storage") || mount == "/sdcard" || mount == "/cache" || mount == "/system" || mount == "/product")) return@mapNotNull null
            DdLine(mount, "${t[2]} used of ${t[1]} (${t[4]}) · ${t[3]} free")
        }.distinctBy { it.label }.take(rows).toList()

    /** Use percent of /data (or the first real mount) from `df -h`, null when absent. */
    fun dfDataPercent(text: String): Int? =
        dfLines(text, 99).let { ls -> (ls.firstOrNull { it.label == "/data" } ?: ls.firstOrNull())?.value?.let { Regex("""\((\d+)%\)""").find(it)?.groupValues?.get(1)?.toIntOrNull() } }

    /** The disk-wide lines of `dumpsys diskstats`: Data / Cache / System free, then the media, app and cache sizes. */
    fun diskstats(text: String, rows: Int): List<DdLine> {
        val out = ArrayList<DdLine>()
        for (l in text.lineSequence()) {
            val t = l.trim()
            Regex("""^(Data|Cache|System)-Free: (\d+)K / (\d+)K total = (\d+)% free""").find(t)?.let {
                val (n, free, total, pct) = it.destructured
                out += DdLine("$n free", "${kbText(free.toLong())} of ${kbText(total.toLong())} ($pct% free)")
            }
            Regex("""^(Photos|Videos|Audio|Downloads|Documents|Other|App|App Data|App Cache) Size: (\d+)$""").find(t)?.let {
                out += DdLine(it.groupValues[1] + " size", bytes(it.groupValues[2].toLong()))
            }
        }
        return out.take(rows)
    }

    data class AppSize(val pkg: String, val app: Long, val data: Long, val cache: Long) { val total get() = app + data + cache }

    private fun numArray(text: String, key: String): List<Long> =
        Regex("^" + Regex.escape(key) + """: \[(.*)]""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
            ?.split(',')?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()

    /** Per-app sizes of `dumpsys diskstats` (parallel arrays: Package Names, App Sizes, App Data Sizes, Cache Sizes); biggest total first. */
    fun appSizes(text: String): List<AppSize> {
        val names = Regex("""^Package Names: \[(.*)]""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
            ?.split(',')?.map { it.trim().trim('"') }.orEmpty()
        if (names.isEmpty()) return emptyList()
        val a = numArray(text, "App Sizes"); val d = numArray(text, "App Data Sizes"); val c = numArray(text, "Cache Sizes")
        return names.indices.map { i -> AppSize(names[i], a.getOrElse(i) { 0L }, d.getOrElse(i) { 0L }, c.getOrElse(i) { 0L }) }.sortedByDescending { it.total }
    }

    fun appSizeLines(text: String, rows: Int): List<DdLine> =
        appSizes(text).take(rows).map { DdLine(short(it.pkg).take(30), "${bytes(it.total)} (app ${bytes(it.app)} · data ${bytes(it.data)} · cache ${bytes(it.cache)})") }

    // ── ② Crash tracker: dumpsys dropbox --print ──

    data class Crash(val whenText: String, val tag: String, val proc: String?)

    /** Dropbox entries tagged *crash / *anr (header line `yyyy-MM-dd HH:mm:ss tag (...)`), with the Process: / Package: line that follows. */
    fun crashes(text: String): List<Crash> {
        val out = ArrayList<Crash>()
        var cur: Crash? = null
        for (l in text.lineSequence()) {
            val h = Regex("""^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) (\S*(?:crash|anr)\S*)""", RegexOption.IGNORE_CASE).find(l.trim())
            if (h != null) { cur?.let { out += it }; cur = Crash(h.groupValues[1], h.groupValues[2], null); continue }
            val c = cur ?: continue
            val m = Regex("""^(?:Process|Package): (\S+)""").find(l.trim())
            if (m != null && c.proc == null) cur = c.copy(proc = m.groupValues[1])
        }
        cur?.let { out += it }
        return out
    }

    fun crashLines(text: String, rows: Int): List<DdLine> {
        val all = crashes(text)
        if (all.isEmpty()) return if (text.isNotBlank()) listOf(DdLine("Crashes", "none recorded")) else emptyList()
        return listOf(DdLine("Crashes", "${all.size} recorded")) + all.takeLast(rows).reversed().map { DdLine(it.whenText, it.tag + (it.proc?.let { p -> " · " + short(p) } ?: "")) }
    }

    // ── ② Network tracker: netstats per uid, connectivity, radio signal ──

    data class NetUse(val uid: String, val rx: Long, val tx: Long) { val total get() = rx + tx }

    /** `dumpsys netstats --uid`: sums rb/tb (bytes) of every bucket per `uid=` (untagged, tag=0x0); heaviest first. */
    fun netUsage(text: String): List<NetUse> {
        val rx = LinkedHashMap<String, Long>(); val tx = LinkedHashMap<String, Long>()
        var uid: String? = null
        for (l in text.lineSequence()) {
            val t = l.trim()
            val h = Regex("""\buid=(\d+) set=\w+ tag=(\S+)""").find(t)
            if (h != null) { uid = if (h.groupValues[2] == "0x0") h.groupValues[1] else null; continue }
            val u = uid ?: continue
            val b = Regex("""\brb=(\d+) rp=\d+ tb=(\d+)""").find(t) ?: continue
            rx[u] = (rx[u] ?: 0L) + b.groupValues[1].toLong(); tx[u] = (tx[u] ?: 0L) + b.groupValues[2].toLong()
        }
        return rx.keys.map { NetUse(it, rx.getValue(it), tx[it] ?: 0L) }.sortedByDescending { it.total }
    }

    fun netUsageLines(text: String, rows: Int, label: (String) -> String = { it }): List<DdLine> =
        netUsage(text).filter { it.total > 0 }.take(rows).map {
            DdLine(runCatching { label("u0a" + (it.uid.toInt() - 10000)) }.getOrDefault(it.uid).takeIf { n -> !n.startsWith("u0a-") } ?: "uid ${it.uid}",
                "down ${bytes(it.rx)} · up ${bytes(it.tx)}")
        }

    /** `dumpsys connectivity`: the default network and its transport/validation, as far as the dump shows them. */
    fun connectivity(text: String, rows: Int): List<DdLine> {
        val out = ArrayList<DdLine>()
        Regex("""Active default network: (\S+)""").find(text)?.let { out += DdLine("Default network", "id ${it.groupValues[1]}") }
        val nets = Regex("""NetworkAgentInfo\{.*?type: (\w+)""").findAll(text).map { it.groupValues[1] }.toList()
        if (nets.isNotEmpty()) out += DdLine("Networks up", nets.groupingBy { it }.eachCount().entries.joinToString(" · ") { "${it.key} x${it.value}" })
        val validated = Regex("""\bVALIDATED\b""").findAll(text).count(); val not = Regex("""NOT_VALIDATED""").findAll(text).count()
        if (validated + not > 0) out += DdLine("Validation", "$validated validated · $not not")
        Regex("""Airplane Mode: (\w+)""", RegexOption.IGNORE_CASE).find(text)?.let { out += DdLine("Airplane", it.groupValues[1]) }
        return out.take(rows)
    }

    /** `dumpsys telephony.registry` radio state: signal strength level/dbm, data state, network type, service state. */
    fun signal(text: String, rows: Int): List<DdLine> {
        val out = ArrayList<DdLine>()
        Regex("""mSignalStrength=(.*)""").find(text)?.let { m ->
            val v = m.groupValues[1]
            val dbm = Regex("""rsrp=(-?\d+)""").find(v)?.groupValues?.get(1) ?: Regex("""mDbm=(-?\d+)""").find(v)?.groupValues?.get(1)
            val lvl = Regex("""level=(\d)""").find(v)?.groupValues?.get(1) ?: Regex("""mLevel=(\d)""").find(v)?.groupValues?.get(1)
            out += DdLine("Signal", listOfNotNull(lvl?.let { "level $it/4" }, dbm?.let { "$it dBm" }).joinToString(" · ").ifEmpty { v.take(60) })
        }
        Regex("""mDataConnectionState=(-?\d+)""").find(text)?.let { out += DdLine("Data state", when (it.groupValues[1]) { "2" -> "connected"; "0" -> "disconnected"; "1" -> "connecting"; else -> it.groupValues[1] }) }
        Regex("""mNetworkType=(\w+)""").find(text)?.let { out += DdLine("Network type", it.groupValues[1]) }
        Regex("""mCallState=(\d)""").find(text)?.let { out += DdLine("Call state", if (it.groupValues[1] == "0") "idle" else "active") }
        Regex("""(?:mServiceState|mVoiceRegState)=(.*)""").find(text)?.let { out += DdLine("Service", it.groupValues[1].take(60)) }
        return out.take(rows)
    }

    // ── ② Process & Lifecycle tracker: dumpsys activity processes (oom-adj) ──

    data class Proc(val adj: String, val pid: Int, val name: String)

    /** `Proc #n: adj ... pid:name/uid (reason)` rows of the process LRU list; adj = persistent/fg/vis/svc/cch... as the dump prints it. */
    fun oomProcs(text: String): List<Proc> =
        text.lineSequence().mapNotNull { l ->
            val m = Regex("""Proc #\d+:\s+(\S+)\s+.*?(\d+):([\w.:-]+)/u\d+""").find(l) ?: return@mapNotNull null
            Proc(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3])
        }.toList()

    fun oomLines(text: String, rows: Int): List<DdLine> {
        val all = oomProcs(text)
        if (all.isEmpty()) return emptyList()
        val byClass = all.groupingBy { it.adj.takeWhile { c -> c.isLetter() } }.eachCount().entries.sortedByDescending { it.value }
        return listOf(DdLine("Processes", "${all.size} · " + byClass.take(6).joinToString(" ") { "${it.key} ${it.value}" })) +
            all.filter { !it.adj.startsWith("cch") }.take(rows).map { DdLine(it.adj, short(it.name) + " (pid ${it.pid})") }
    }

    // ── ② Security & Perms tracker: dumpsys package <this app> ──

    /** Permissions of the app: requested count, then the granted runtime/install ones (`android.permission.X: granted=true`). */
    fun grantedPerms(text: String): List<String> =
        Regex("""([\w.]+): granted=true""").findAll(text).map { it.groupValues[1].substringAfterLast('.') }.distinct().toList()

    fun permLines(text: String, rows: Int): List<DdLine> {
        val req = Regex("""requested permissions:""").find(text)?.let { text.substring(it.range.last).lineSequence().drop(1).takeWhile { l -> l.startsWith("      ") || l.startsWith("\t") }.count() }
        val granted = grantedPerms(text)
        if (req == null && granted.isEmpty()) return emptyList()
        return listOfNotNull(req?.let { DdLine("Requested", "$it") }, DdLine("Granted", "${granted.size}")) +
            granted.take(rows).map { DdLine("granted", it) }
    }
}
