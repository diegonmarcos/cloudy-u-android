package com.diegonmarcos.superapp.system

import java.util.Locale

/** One label/value line of a dropdown group. */
data class DdLine(val label: String, val value: String)

/**
 * Pure parsers for the dumpsys text the status-bar dropdowns show (the shell broker supplies the text; nothing here touches a
 * device). Each is tolerant: a format it does not recognise yields no lines, never an exception.
 */
object DumpsysParsers {

    private fun f1(d: Double) = String.format(Locale.US, "%.1f", d)

    // ── dumpsys activity services (Developer options > Running services) ──

    data class Svc(val pkg: String, val cls: String, val foreground: Boolean, val pid: Int?, val process: String?, val since: String?)

    /** Every ServiceRecord of `dumpsys activity services`. */
    fun services(text: String): List<Svc> {
        val out = ArrayList<Svc>()
        for (block in text.split("* ServiceRecord{").drop(1)) {
            val head = block.lineSequence().first()
            val cmp = Regex("""u\d+\s+(\S+?)\}""").find(head)?.groupValues?.get(1) ?: continue
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
}
