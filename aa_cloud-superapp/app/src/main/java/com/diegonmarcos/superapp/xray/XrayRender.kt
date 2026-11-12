package com.diegonmarcos.superapp.xray

import com.diegonmarcos.superapp.system.DdLine
import com.diegonmarcos.superapp.system.DumpsysParsers
import java.util.Locale

/** A traffic light per tracker. NA = not read (no adb, or the probe could not be served). */
enum class XStatus(val word: String) { OK("ok"), WARN("warn"), BAD("bad"), NA("na") }

/** A horizontal meter: [value] of [max], with its printed [text]. */
data class XBar(val label: String, val value: Double, val max: Double, val text: String)

/** What one tracker found: status, one-line headline, key:value lines, meters. Built once from the report, rendered two ways. */
data class XDigest(val name: String, val num: Int, val status: XStatus, val headline: String, val lines: List<DdLine>, val bars: List<XBar>)

/**
 * The data model behind both lightweight exports. [XrayDigest.build] parses the probe sections of an [XReport] (by [XSection.id]) into
 * per-tracker signals; [XrayRender.compact] (terse Markdown for an LLM) and [XrayRender.html] (styled single file for a person) render the
 * same digests in the same macro order: Device Profile Scan, trackers 01..12, 13 Suspects. Pure and tolerant: a dump it cannot read yields NA or no lines.
 */
object XrayDigest {

    private fun f1(d: Double) = String.format(Locale.US, "%.1f", d)
    private fun n(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else f1(v)

    private class Ctx(val r: XReport) {
        val text = HashMap<String, String>()
        val tried = HashMap<String, Boolean>()
        init { for (s in r.sections) if (s.id.isNotEmpty()) { tried[s.id] = (tried[s.id] ?: false) || s.ok; if (s.ok) text[s.id] = s.text } }
        fun t(id: String) = text[id]
        fun read(vararg ids: String) = ids.any { text.containsKey(it) }
    }

    /** The static Device Profile Scan as key:value lines (model, Android, SoC, CPU, display, doze, a few services). */
    fun profile(r: XReport): List<DdLine> {
        val c = Ctx(r)
        val out = ArrayList<DdLine>()
        val p = c.t("profile").orEmpty()
        val kv = p.lineSequence().mapNotNull { l -> l.substringBefore('=', "").takeIf { k -> k.startsWith("ro.") }?.let { it to l.substringAfter('=') } }.toMap()
        if (kv.isNotEmpty()) {
            out += DdLine("model", "${kv["ro.product.manufacturer"].orEmpty()} ${kv["ro.product.model"].orEmpty()}".trim())
            out += DdLine("android", "${kv["ro.build.version.release"] ?: "?"} sdk ${kv["ro.build.version.sdk"] ?: "?"} build ${kv["ro.build.fingerprint"]?.substringAfter(':')?.substringBefore(':') ?: "?"}")
            out += DdLine("soc", listOfNotNull(kv["ro.soc.manufacturer"], kv["ro.soc.model"]).joinToString(" ") + " / " + (kv["ro.board.platform"] ?: kv["ro.hardware"] ?: "?"))
            out += DdLine("gpu", "egl ${kv["ro.hardware.egl"] ?: "?"} vk ${kv["ro.hardware.vulkan"] ?: "none"} gles ${kv["ro.opengles.version"]?.toIntOrNull()?.let { "${it shr 16}.${it and 0xffff}" } ?: "?"}")
        }
        val cpus = Regex("""cpu(\d+) max=(\d*) gov=(\S*)""").findAll(p).toList()
        if (cpus.isNotEmpty()) {
            val clusters = cpus.groupingBy { it.groupValues[2].toLongOrNull()?.div(1000)?.toString() ?: "?" }.eachCount().entries.joinToString(" ") { "${it.value}x${it.key}MHz" }
            out += DdLine("cpu", "${cpus.size} cores $clusters gov ${cpus.first().groupValues[3].ifEmpty { "?" }}")
        }
        Regex("""Physical size: (\S+)""").find(p)?.let { m ->
            val dens = Regex("""Override density: (\d+)""").find(p)?.groupValues?.get(1) ?: Regex("""Physical density: (\d+)""").find(p)?.groupValues?.get(1)
            out += DdLine("display", m.groupValues[1] + (dens?.let { " ${it}dpi" } ?: ""))
        }
        Regex("""up ([^,]+),.*load average: ([\d.]+), ([\d.]+), ([\d.]+)""").find(p)?.let { out += DdLine("uptime", "${it.groupValues[1].trim()} load ${it.groupValues[2]}/${it.groupValues[3]}/${it.groupValues[4]}") }
        c.t("packages")?.let { out += DdLine("packages", Regex("(?m)^package:").findAll(it).count().toString()) }
        c.t("deviceidle")?.let { d -> Regex("""deep=(\S+) light=(\S+)""").find(d)?.let { out += DdLine("doze", "deep ${it.groupValues[1]} light ${it.groupValues[2]}") } }
        c.t("buckets")?.let { b ->
            val fleet = b.substringBefore("---").lines().count { it.isNotBlank() }
            if (fleet > 0) out += DdLine("standby", "$fleet fleet apps bucketed")
        }
        c.t("appops")?.let { a ->
            fun cnt(part: String) = part.lines().count { it.isNotBlank() && !it.contains("No operations", true) }
            out += DdLine("bg-restricted", "ignore ${cnt(a.substringAfter("ignore:").substringBefore("deny:"))} deny ${cnt(a.substringAfter("deny:"))}")
        }
        for ((id, label) in listOf("listeners" to "notif-listeners", "a11y" to "a11y", "autofill" to "autofill", "credential" to "credential"))
            c.t(id)?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it != "null" }?.let { out += DdLine(label, it.take(100)) }
        return out
    }

    private fun sevHigh(v: Double, warn: Double, bad: Double) = if (v >= bad) XStatus.BAD else if (v >= warn) XStatus.WARN else XStatus.OK
    private fun worse(a: XStatus, b: XStatus) = if (a == XStatus.NA) b else if (b == XStatus.NA) a else if (a.ordinal >= b.ordinal) a else b
    private fun lines(vararg kv: Pair<String, String?>) = kv.mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { DdLine(k, it) } }
    private fun short(p: String) = p.removePrefix("com.diegonmarcos.")
    private fun mb(kb: Long) = if (kb >= 1_048_576) f1(kb / 1_048_576.0) + "GB" else f1(kb / 1024.0) + "MB"

    /** One digest per tracker of [XrayProbes.trackers], in order (the last is Suspects). */
    fun build(r: XReport): List<XDigest> {
        val c = Ctx(r)
        val cores = Regex("""cpu\d+ max=""").findAll(c.t("profile").orEmpty()).count().coerceAtLeast(1)
        val out = ArrayList<XDigest>()
        for (t in XrayProbes.trackers) {
            val probeIds = XrayProbes.all.filter { it.group == t.group }.map { it.id }
            val extraSecs = r.sections.filter { it.group == t.group && it.id.isEmpty() && it.ok }
            val d = when (t.num) {
                1 -> performance(c, t, cores)
                2 -> render(c, t)
                3 -> storage(c, t)
                4 -> battery(c, t)
                5 -> thermal(c, t)
                6 -> memory(c, t)
                7 -> notif(c, t)
                8 -> resp(c, t)
                9 -> crash(c, t)
                10 -> network(c, t)
                11 -> process(c, t)
                12 -> security(c, t)
                else -> suspects(c, t)
            }
            val extra = extraSecs.map { s -> DdLine(s.title.take(40), s.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(100).orEmpty()) }
            val read = t.num == 13 || probeIds.any { c.tried[it] == true }
            out += if (!read) XDigest(t.label, t.num, XStatus.NA, if (r.partial) "needs adb" else "not read", extra, emptyList())
            else d.copy(lines = d.lines + extra)
        }
        return out
    }

    private fun d(t: XrayProbes.Tracker, st: XStatus, head: String, lines: List<DdLine>, bars: List<XBar> = emptyList()) = XDigest(t.label, t.num, st, head, lines, bars)

    private fun performance(c: Ctx, t: XrayProbes.Tracker, cores: Int): XDigest {
        val top = c.t("top") ?: return d(t, XStatus.NA, "no data", emptyList())
        val m = Regex("""(\d+)%cpu\s+(\d+)%user.*?(\d+)%idle""").find(top)
        val busy = m?.let { (it.groupValues[1].toInt() - it.groupValues[3].toInt()) * 100.0 / it.groupValues[1].toInt().coerceAtLeast(1) }
        val load = Regex("""load average: ([\d.]+)""").find(c.t("profile").orEmpty())?.groupValues?.get(1)?.toDoubleOrNull()
        val perCore = load?.div(cores)
        val procs = DumpsysParsers.topProcs(top).take(6)
        var st = XStatus.OK
        busy?.let { st = worse(st, sevHigh(it, 60.0, 85.0)) }
        perCore?.let { st = worse(st, sevHigh(it, 1.5, 3.0)) }
        val head = listOfNotNull(busy?.let { "cpu busy ${n(Math.round(it).toDouble())}%" }, perCore?.let { "load/core ${f1(it)}" }).joinToString(" ").ifEmpty { "${procs.size} procs" }
        return d(t, st, head, lines("tasks" to Regex("""Tasks: (\d+) total""").find(top)?.groupValues?.get(1)),
            procs.map { XBar(short(it.name).take(32), it.cpu, 100.0, "${n(it.cpu)}%") })
    }

    private fun render(c: Ctx, t: XrayProbes.Tracker): XDigest {
        val g = c.t("gfxinfo")?.let { DumpsysParsers.gfxinfo(it.split("** Graphics info").let { b -> if (b.size > 1) "** Graphics info" + b[1] else it }) }
        val sf = c.t("surfaceflinger")?.lines()?.filter { Regex("""missed|jank""", RegexOption.IGNORE_CASE).containsMatchIn(it) }?.take(3)?.map { it.trim().take(80) }.orEmpty()
        if (g == null && sf.isEmpty()) return d(t, XStatus.NA, "no data", emptyList())
        val pct = g?.jankyPct
        val st = pct?.let { sevHigh(it, 5.0, 15.0) } ?: XStatus.OK
        val bars = ArrayList<XBar>()
        g?.percentiles?.toSortedMap()?.forEach { (k, v) -> bars += XBar("p$k frame ms", v, 50.0, n(v)) }
        g?.slow?.filter { it.second > 0 }?.sortedByDescending { it.second }?.take(4)?.forEach { bars += XBar(it.first.take(28), it.second.toDouble(), (g.total ?: it.second).toDouble().coerceAtLeast(1.0), it.second.toString()) }
        val ls = ArrayList<DdLine>()
        g?.total?.let { ls += DdLine("frames", "$it janky ${g.janky ?: "?"}") }
        g?.histogram?.filter { it.second > 0 }?.takeIf { it.isNotEmpty() }?.let { h -> ls += DdLine("hist ms:n", h.sortedByDescending { it.second }.take(6).joinToString(" ") { "${it.first}:${it.second}" }) }
        g?.extras?.take(4)?.forEach { ls += it }
        sf.forEachIndexed { i, l -> ls += DdLine("sf${i + 1}", l) }
        return d(t, st, pct?.let { "jank ${f1(it)}%" + (g.percentiles[90]?.let { p -> " p90 ${n(p)}ms" } ?: "") } ?: "frames n/a", ls, bars)
    }

    private fun storage(c: Ctx, t: XrayProbes.Tracker): XDigest {
        val df = c.t("df"); val ds = c.t("diskstats")
        if (df == null && ds == null) return d(t, XStatus.NA, "no data", emptyList())
        val dfLines = df?.let { DumpsysParsers.dfLines(it, 6) }.orEmpty()
        val used = df?.let { DumpsysParsers.dfDataPercent(it) }
            ?: ds?.let { Regex("""Data-Free: \d+K / \d+K total = (\d+)% free""").find(it)?.groupValues?.get(1)?.toIntOrNull()?.let { f -> 100 - f } }
        val st = used?.let { sevHigh(it.toDouble(), 80.0, 92.0) } ?: XStatus.OK
        val ls = dfLines + (ds?.let { DumpsysParsers.diskstats(it, 12) }.orEmpty())
        val sizes = ds?.let { DumpsysParsers.appSizes(it) }.orEmpty().take(6)
        val maxSize = sizes.firstOrNull()?.total?.toDouble() ?: 1.0
        return d(t, st, used?.let { "data $it% used" } ?: "sizes only", ls, sizes.map { XBar(short(it.pkg).take(30), it.total.toDouble(), maxSize, DumpsysParsers.bytes(it.total)) })
    }

    private fun battery(c: Ctx, t: XrayProbes.Tracker): XDigest {
        val bat = c.t("battery").orEmpty(); val bs = c.t("batterystats"); val alarm = c.t("alarm"); val jobs = c.t("jobs")
        if (bat.isEmpty() && bs == null && alarm == null) return d(t, XStatus.NA, "no data", emptyList())
        val level = Regex("""level: (\d+)""").find(bat)?.groupValues?.get(1)
        val temp = Regex("""temperature: (\d+)""").find(bat)?.groupValues?.get(1)?.toDoubleOrNull()?.div(10)
        val ls = ArrayList<DdLine>()
        ls += lines("level" to level?.let { "$it%" }, "temp" to temp?.let { "${f1(it)}C" })
        bs?.let { s ->
            val bsx = DumpsysParsers.batteryStats(s)
            ls += bsx.times.filter { it.label.startsWith("Time on battery") && !it.label.contains("screen", true) || it.label.contains("discharge", true) }.take(3)
        }
        alarm?.let { a -> DumpsysParsers.alarmLines(a, 4).forEach { ls += DdLine("alarm " + it.label, it.value) } }
        jobs?.let { j -> DumpsysParsers.jobLines(j, 3).forEach { ls += DdLine("jobs " + it.label, it.value) } }
        val wl = bs?.let { XrayAnalyze.wakelocks(it) }.orEmpty().filter { it.count > 0 || it.ms > 0 }.sortedWith(compareByDescending<XrayAnalyze.Wake> { it.ms }.thenByDescending { it.count }).take(5)
        val maxC = wl.firstOrNull()?.ms?.toDouble()?.coerceAtLeast(1.0) ?: 1.0
        var st = temp?.let { sevHigh(it, 40.0, 45.0) } ?: XStatus.OK
        val top = r(c).suspects.firstOrNull { causeTracker(it.cause) == 4 }
        top?.let { st = worse(st, sevHigh(it.score, 30.0, 60.0)) }
        val head = listOfNotNull(level?.let { "$it%" }, temp?.let { "${f1(it)}C" }, wl.firstOrNull()?.let { "top wakelock ${it.name.take(24)} ${f1(it.ms / 60_000.0)}min ${it.count}x" }).joinToString(" ")
        return d(t, st, head.ifEmpty { "no signals" }, ls, wl.map { XBar(it.name.take(30), it.ms.toDouble(), maxC, "${f1(it.ms / 60_000.0)}min ${it.count}x") })
    }

    private fun r(c: Ctx) = c.r

    private fun causeTracker(cause: String) = when {
        cause.contains("notification") -> 7
        cause.contains("jank") -> 2
        else -> 4
    }

    private fun thermal(c: Ctx, t: XrayProbes.Tracker): XDigest {
        val th = c.t("thermal") ?: return d(t, XStatus.NA, "no data", emptyList())
        val status = Regex("""Thermal Status: (\d+)""").find(th)?.groupValues?.get(1)?.toIntOrNull()
        val temps = Regex("""mValue=(-?[\d.]+), mType=\d+, mName=([^,}]+)""").findAll(th).mapNotNull { m -> m.groupValues[1].toDoubleOrNull()?.takeIf { it > -50 && it < 200 }?.let { m.groupValues[2] to it } }.distinctBy { it.first }.toList()
        val hot = temps.sortedByDescending { it.second }.take(5)
        var st = when { status == null -> XStatus.OK; status >= 3 -> XStatus.BAD; status >= 1 -> XStatus.WARN; else -> XStatus.OK }
        hot.firstOrNull()?.let { st = worse(st, sevHigh(it.second, 45.0, 55.0)) }
        val ls = lines("status" to status?.let { "$it (0 none 1 light 2 moderate 3 severe 4+ critical)" },
            "sensors" to temps.size.toString().takeIf { temps.isNotEmpty() }, "cooling-devices" to Regex("CoolingDevice\\{").findAll(th).count().toString().takeIf { it != "0" })
        return d(t, st, "status ${status ?: "?"}" + (hot.firstOrNull()?.let { " max ${it.first} ${n(it.second)}C" } ?: ""), ls, hot.map { XBar(it.first.take(28), it.second, 60.0, "${n(it.second)}C") })
    }

    private fun memory(c: Ctx, t: XrayProbes.Tracker): XDigest {
        val mi = c.t("meminfo"); val ps = c.t("procstats")
        if (mi == null && ps == null) return d(t, XStatus.NA, "no data", emptyList())
        fun ram(k: String) = mi?.let { Regex("""$k RAM:\s*([\d,]+)K""").find(it)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull() }
        val total = ram("Total"); val free = ram("Free"); val lost = ram("Lost")
        val topMem = c.t("top")?.let { Regex("""Mem:\s*(\d+)K total,\s*\d+K used,\s*(\d+)K free""").find(it) }
        val cached = c.t("top")?.let { Regex("""(\d+)K cached""").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
        val swap = c.t("top")?.let { Regex("""Swap:\s*(\d+)K total,\s*(\d+)K used""").find(it) }
        val tot = total ?: topMem?.groupValues?.get(1)?.toLongOrNull()
        val fr = free ?: topMem?.groupValues?.get(2)?.toLongOrNull()?.plus(cached)
        val availPct = if (tot != null && tot > 0 && fr != null) fr * 100.0 / tot else null
        val swapPct = swap?.let { it.groupValues[2].toDouble() * 100 / it.groupValues[1].toDouble().coerceAtLeast(1.0) }
        var st = availPct?.let { if (it < 8) XStatus.BAD else if (it < 15) XStatus.WARN else XStatus.OK } ?: XStatus.OK
        swapPct?.let { st = worse(st, sevHigh(it, 50.0, 80.0)) }
        val ls = lines("ram" to tot?.let { "total ${mb(it)} avail ${mb(fr ?: 0)}" + (lost?.let { l -> " lost ${mb(l)}" } ?: "") },
            "swap" to swap?.let { "${mb(it.groupValues[2].toLong())} of ${mb(it.groupValues[1].toLong())}" })
        val pss = mi?.let { DumpsysParsers.meminfoProcs(it) }.orEmpty().take(6)
        val tri = ps?.let { DumpsysParsers.memory3h(it, 5) }.orEmpty().map { DdLine("3h " + it.label.take(30), it.value) }
        val maxK = pss.firstOrNull()?.kb?.toDouble() ?: 1.0
        return d(t, st, availPct?.let { "avail ${f1(it)}%" + (swapPct?.let { s -> " swap ${n(Math.round(s).toDouble())}%" } ?: "") } ?: "procstats only", ls + tri, pss.map { XBar(short(it.name).take(30), it.kb.toDouble(), maxK, mb(it.kb)) })
    }

    private fun notif(c: Ctx, t: XrayProbes.Tracker): XDigest {
        val nt = c.t("notification"); val bd = c.t("binder")
        if (nt == null && bd == null) return d(t, XStatus.NA, "no data", emptyList())
        val ns = nt?.let { DumpsysParsers.notificationStats(it) }.orEmpty().sortedByDescending { it.total }.take(6)
        val st = ns.firstOrNull()?.let { sevHigh(it.total.toDouble(), 1000.0, 5000.0) } ?: XStatus.OK
        val maxT = ns.firstOrNull()?.total?.toDouble() ?: 1.0
        val ls = bd?.let { DumpsysParsers.binder(it, 4) }.orEmpty().map { DdLine("binder " + it.label, it.value) }
        return d(t, st, ns.firstOrNull()?.let { "top ${short(it.pkg)} ${it.total} posts" } ?: "quiet", ls,
            ns.map { XBar(short(it.pkg).take(30), it.total.toDouble(), maxT, "${it.total} (act ${it.active})") })
    }

    private fun resp(c: Ctx, t: XrayProbes.Tracker): XDigest {
        val a = c.t("anr") ?: return d(t, XStatus.NA, "no data", emptyList())
        val all = DumpsysParsers.anrs(a)
        return d(t, sevHigh(all.size.toDouble(), 1.0, 3.0), "${all.size} ANR", all.takeLast(4).reversed().map { DdLine(it.whenText, (it.pkg?.let { p -> short(p) + " " } ?: "") + it.detail) })
    }

    private fun crash(c: Ctx, t: XrayProbes.Tracker): XDigest {
        val db = c.t("dropbox"); val lc = c.t("logcat")
        if (db == null && lc == null) return d(t, XStatus.NA, "no data", emptyList())
        val cr = db?.let { DumpsysParsers.crashes(it) }.orEmpty()
        val errs = lc?.lineSequence()?.mapNotNull { Regex("""^\S+\s+\S+\s+\d+\s+\d+\s+([EWF])\s+(\S+?)\s*:""").find(it) }?.toList().orEmpty()
        val e = errs.count { it.groupValues[1] != "W" }; val w = errs.size - e
        val tags = errs.filter { it.groupValues[1] != "W" }.groupingBy { it.groupValues[2] }.eachCount().entries.sortedByDescending { it.value }.take(4)
        val st = worse(sevHigh(cr.size.toDouble(), 1.0, 3.0), sevHigh(e.toDouble(), 50.0, 200.0))
        val ls = cr.takeLast(5).reversed().map { DdLine(it.whenText, it.tag + (it.proc?.let { p -> " " + short(p) } ?: "")) } +
            (if (lc != null) lines("logcat" to "E/F $e W $w", "top-err-tags" to tags.joinToString(" ") { "${it.key}:${it.value}" }) else emptyList())
        return d(t, st, listOfNotNull(db?.let { "${cr.size} crash/anr" }, lc?.let { "logcat E/F $e" }).joinToString(", "), ls)
    }

    private fun network(c: Ctx, t: XrayProbes.Tracker): XDigest {
        val ns = c.t("netstats"); val cn = c.t("connectivity"); val tel = c.t("telephony")
        if (ns == null && cn == null && tel == null) return d(t, XStatus.NA, "no data", emptyList())
        val use = ns?.let { DumpsysParsers.netUsage(it) }.orEmpty().filter { it.total > 0 }.take(6)
        val sig = tel?.let { DumpsysParsers.signal(it, 6) }.orEmpty()
        val con = cn?.let { DumpsysParsers.connectivity(it, 6) }.orEmpty()
        val notVal = con.firstOrNull { it.label == "Validation" }?.value?.substringAfter("validated · ")?.substringBefore(" ")?.toIntOrNull() ?: 0
        val level = sig.firstOrNull { it.label == "Signal" }?.value?.let { Regex("""level (\d)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val st = if (notVal > 0 || (level != null && level <= 1)) XStatus.WARN else XStatus.OK
        val maxU = use.firstOrNull()?.total?.toDouble() ?: 1.0
        val pk = XrayAnalyze.uidPackages(c.t("packages").orEmpty())
        fun nm(uid: String) = uid.toIntOrNull()?.let { pk[it]?.firstOrNull() }?.let { short(it) } ?: "uid $uid"
        fun sz(b: Long) = DumpsysParsers.bytes(b)
        return d(t, st, listOfNotNull(level?.let { "signal $it/4" }, use.firstOrNull()?.let { "top ${nm(it.uid)} ${sz(it.total)}" }).joinToString(" ").ifEmpty { "no signals" },
            (con + sig).map { DdLine(it.label.lowercase(Locale.US), it.value) }, use.map { XBar(nm(it.uid).take(30), it.total.toDouble(), maxU, "dn ${sz(it.rx)} up ${sz(it.tx)}") })
    }

    private fun process(c: Ctx, t: XrayProbes.Tracker): XDigest {
        val sv = c.t("services"); val sp = c.t("startproc"); val pr = c.t("procs")
        if (sv == null && sp == null && pr == null) return d(t, XStatus.NA, "no data", emptyList())
        val ls = ArrayList<DdLine>()
        sv?.let { s -> val all = DumpsysParsers.services(s); ls += DdLine("services", "${all.size} (${all.count { it.foreground }} fg)") }
        pr?.let { DumpsysParsers.oomLines(it, 6).forEach { l -> ls += DdLine("oom " + l.label, l.value) } }
        val starts = sp?.let { s -> XrayAnalyze.startReasons(s).entries.groupBy({ it.key.first }, { it.value }).mapValues { it.value.sum() } }.orEmpty().entries.sortedByDescending { it.value }.take(5)
        val maxS = starts.firstOrNull()?.value?.toDouble() ?: 1.0
        val st = starts.firstOrNull()?.let { sevHigh(it.value.toDouble(), 20.0, 60.0) } ?: XStatus.OK
        return d(t, st, starts.firstOrNull()?.let { "top restarts ${short(it.key)} ${it.value}x" } ?: "stable", ls, starts.map { XBar(short(it.key).take(30), it.value.toDouble(), maxS, "${it.value} starts") })
    }

    private val SENSITIVE = setOf("CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "READ_SMS", "READ_CONTACTS", "READ_CALL_LOG", "READ_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW", "BIND_ACCESSIBILITY_SERVICE", "READ_PHONE_STATE")

    private fun security(c: Ctx, t: XrayProbes.Tracker): XDigest {
        val pp = c.t("pkgperms"); val pm = c.t("perms")
        if (pp == null && pm == null) return d(t, XStatus.NA, "no data", emptyList())
        val granted = (pp?.let { DumpsysParsers.grantedPerms(it) }.orEmpty() + pm?.let { DumpsysParsers.grantedPerms(it) }.orEmpty()).distinct()
        val sens = granted.filter { it in SENSITIVE }
        if (granted.isEmpty() && pp?.contains("requested permissions") != true) return d(t, XStatus.OK, "no data", emptyList())
        val req = pp?.let { DumpsysParsers.permLines(it, 0).firstOrNull { l -> l.label == "Requested" }?.value }
        val ls = lines("requested" to req, "granted" to granted.size.toString(), "sensitive" to sens.joinToString(" "), "other" to granted.filter { it !in SENSITIVE }.take(12).joinToString(" "))
        // #1666 location use: the system's app-ops verdict and the app's own ledger (newest call site).
        val loc = lines("location (system)" to c.t("locops")?.let { LocationUseView.summary(it) },
            "location (app)" to c.t("locledger")?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(140))
        return d(t, sevHigh(sens.size.toDouble(), 5.0, 9.0), "${granted.size} granted, ${sens.size} sensitive", ls + loc)
    }

    private fun suspects(c: Ctx, t: XrayProbes.Tracker): XDigest {
        val s = c.r.suspects
        if (s.isEmpty()) return d(t, if (c.r.partial) XStatus.NA else XStatus.OK, if (c.r.partial) "needs adb" else "none ranked", emptyList())
        return d(t, sevHigh(s.first().score, 30.0, 60.0), "#1 ${short(s.first().pkg)} ${n(s.first().score)}pts ${s.first().cause}",
            s.take(8).map { DdLine("#${it.rank} ${short(it.pkg)}${if (it.fleet) "*" else ""}", "${n(it.score)} ${it.cause}") }, s.take(6).map { XBar(short(it.pkg).take(30), it.score, 100.0, n(it.score)) })
    }
}

/** The two light renderings of the same digests. */
object XrayRender {

    private val PREFIX = listOf("com.diegonmarcos." to "dm.", "com.google.android." to "g.", "com.android." to "a.", "com.samsung.android." to "sx.")
    private fun cell(s: String) = s.replace("|", "/").replace("\n", " ").trim()

    /**
     * Token-optimised Markdown for an LLM. Rules: no prose, no emoji, no repeated headers; `key:val` lines and pipe tables; verdict and
     * status first; a tracker with nothing to say is ONE line; only parsed signals (no raw dumps); package prefixes abbreviated (legend in line 2).
     */
    fun compact(r: XReport): String {
        val dg = XrayDigest.build(r)
        val sb = StringBuilder()
        sb.append("xray/2 ").append(r.device).append('|').append(XrayFormat.fileStem(r).substringAfterLast("xray-").takeLast(15)).append("|app ").append(r.appVersion)
            .append("|adb ").append(r.adbChannel).append(if (r.partial) "|PARTIAL " + r.partialReason else "").append('\n')
        sb.append("legend: ").append(PREFIX.joinToString(" ") { "${it.second}=${it.first}" }).append(" *=fleet st=ok|warn|bad|na order=P,T01-T12,T13\n")
        sb.append("## VERDICT\n")
        if (r.suspects.isEmpty()) sb.append("none\n") else {
            sb.append("#|app|pts|cause|why\n")
            for (s in r.suspects.take(8)) sb.append("${s.rank}|${s.pkg}${if (s.fleet) "*" else ""}|${XrayRender.num(s.score)}|${cell(s.cause)}|${cell(s.detail.removePrefix("why: "))}\n")
            if (r.suspects.size > 8) sb.append("+${r.suspects.size - 8} more <1pt\n")
        }
        sb.append("## STATUS\n").append(dg.joinToString(" ") { "T%02d:%s".format(Locale.US, it.num, it.status.word) }).append('\n')
        sb.append("## P device\n")
        val prof = XrayDigest.profile(r)
        if (prof.isEmpty()) sb.append("na\n") else for (l in prof) sb.append(l.label).append(':').append(cell(l.value)).append('\n')
        for (x in dg) {
            val head = "## T%02d %s st:%s".format(Locale.US, x.num, x.name, x.status.word)
            if (x.status == XStatus.NA || (x.lines.isEmpty() && x.bars.isEmpty())) { sb.append(head).append(' ').append(x.headline).append('\n'); continue }
            if (x.num == 13) { sb.append("## T13 Suspects st:").append(x.status.word).append(" see VERDICT\n"); continue }
            sb.append(head).append(' ').append(cell(x.headline)).append('\n')
            for (l in x.lines) sb.append(l.label).append(':').append(cell(l.value)).append('\n')
            if (x.bars.isNotEmpty()) { sb.append("top:"); sb.append(x.bars.joinToString(";") { "${cell(it.label)}=${cell(it.text)}" }).append('\n') }
        }
        val walked = r.sections.map { it.group }.distinct().filter { it.startsWith("X-Ray") }
        if (walked.isNotEmpty()) sb.append("omitted(raw; use verbose md): ").append(walked.joinToString(",")).append('\n')
        if (r.fleet.isNotEmpty()) sb.append("fleet:").append(r.fleet.joinToString(";") { cell(it.replace(": woken by ", ":")).replace(" · ", ",") }).append('\n')
        var s = XrayRedact.scrub(sb.toString())
        for ((a, b) in PREFIX) s = s.replace(a, b)
        // the legend line itself must keep the long forms
        s = s.replace(Regex("(?m)^legend: .*$")) { "legend: " + PREFIX.joinToString(" ") { "${it.second}=${it.first}" } + " *=fleet st=ok|warn|bad|na order=P,T01-T12,T13" }
        return s
    }

    private fun num(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

    private fun h(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val CSS = """
:root{--bg:#10141b;--card:#171d27;--ink:#e8edf5;--dim:#8d99ab;--rule:#273042;--ok:#3ecf6f;--warn:#f0b429;--bad:#f0524f;--na:#5b6577;--accent:#7fb8ff}
*{box-sizing:border-box}body{margin:0;padding:16px;background:var(--bg);color:var(--ink);font:13px/1.45 ui-monospace,Menlo,Consolas,monospace}
h1{font-size:18px;margin:0 0 4px}h2{font-size:15px;margin:0}.dim{color:var(--dim)}a{color:var(--accent);text-decoration:none}
.tiles{display:flex;flex-wrap:wrap;gap:6px;margin:12px 0}.tile{padding:6px 10px;border-radius:6px;background:var(--card);border-left:6px solid var(--na);font-size:12px}
.tile.ok,.dot.ok{border-color:var(--ok);background-color:var(--card)}.tile.warn{border-color:var(--warn)}.tile.bad{border-color:var(--bad)}
.dot{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:8px;background:var(--na)}.dot.ok{background:var(--ok)}.dot.warn{background:var(--warn)}.dot.bad{background:var(--bad)}
details{background:var(--card);border:1px solid var(--rule);border-radius:8px;margin:8px 0;padding:8px 12px}details.macro{border-color:var(--accent)}
summary{cursor:pointer;list-style:none}summary::-webkit-details-marker{display:none}.head{color:var(--dim);margin-left:8px;font-weight:normal;font-size:12px}
table{border-collapse:collapse;width:100%;margin:6px 0}td,th{padding:2px 8px;border-bottom:1px solid var(--rule);text-align:left;vertical-align:top}th{color:var(--dim);font-weight:normal}
.bar{display:flex;align-items:center;gap:8px;margin:3px 0}.bar .l{width:240px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.bar .m{flex:1;height:10px;background:var(--rule);border-radius:5px;overflow:hidden}
.bar .m i{display:block;height:100%;background:var(--accent)}.bar.warn .m i{background:var(--warn)}.bar.bad .m i{background:var(--bad)}.bar .t{width:170px;text-align:right}
.k{color:var(--dim);width:210px}
"""

    private fun bar(b: XBar, st: XStatus): String {
        val pct = if (b.max <= 0) 0.0 else (b.value / b.max * 100).coerceIn(0.0, 100.0)
        val cls = if (st == XStatus.OK || st == XStatus.NA) "" else " " + st.word
        return "<div class=\"bar$cls\"><span class=\"l\">${h(b.label)}</span><span class=\"m\"><i style=\"width:${String.format(Locale.US, "%.0f", pct)}%\"></i></span><span class=\"t\">${h(b.text)}</span></div>"
    }

    private fun kv(ls: List<DdLine>) = if (ls.isEmpty()) "" else "<table>" + ls.joinToString("") { "<tr><td class=\"k\">${h(it.label)}</td><td>${h(it.value)}</td></tr>" } + "</table>"

    /** Self-contained styled HTML: summary tiles and verdict on top, then collapsible macro sections ① Device Profile Scan, ② Profiling Trackers 01..12, 13 Suspects. */
    fun html(r: XReport): String {
        val dg = XrayDigest.build(r)
        val sb = StringBuilder()
        sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>X-Ray ")
            .append(h(r.device)).append("</title><style>").append(CSS).append("</style></head><body>")
        sb.append("<h1>X-Ray report · ").append(h(r.device)).append("</h1><div class=\"dim\">")
            .append(h("${XrayFormat.fileStem(r).substringAfterLast("xray-").takeLast(15)} UTC · app ${r.appVersion} · adb ${r.adbChannel}"))
            .append(if (r.partial) " · <b>" + h(PARTIAL_LABEL) + "</b>" else "").append("</div>")
        sb.append("<div class=\"tiles\">")
        for (x in dg) sb.append("<a class=\"tile ${x.status.word}\" href=\"#t${x.num}\">${"%02d".format(Locale.US, x.num)} ${h(x.name)}<br><span class=\"dim\">${h(x.status.word)}</span></a>")
        sb.append("</div>")
        sb.append("<details class=\"macro\" open><summary><h2 style=\"display:inline\">Verdict (summary)</h2></summary>")
        if (r.suspects.isEmpty()) sb.append("<p class=\"dim\">none ranked</p>") else {
            val mx = r.suspects.maxOf { it.score }.coerceAtLeast(1.0)
            r.suspects.take(5).forEach { sb.append(bar(XBar("#${it.rank} ${it.pkg}${if (it.fleet) " (fleet)" else ""}", it.score, mx, "${num(it.score)} · ${it.cause}"), if (it.score >= 60) XStatus.BAD else if (it.score >= 30) XStatus.WARN else XStatus.OK)) }
        }
        sb.append("</details>")
        sb.append("<details class=\"macro\" open id=\"profile\"><summary><span class=\"dot ok\"></span><h2 style=\"display:inline\">").append(h(XrayProbes.G_DEVICE)).append("</h2><span class=\"head\">static snapshot, read once</span></summary>")
        val prof = XrayDigest.profile(r)
        sb.append(if (prof.isEmpty()) "<p class=\"dim\">not read</p>" else kv(prof)).append("</details>")
        sb.append("<h2 style=\"margin-top:18px\">② Profiling Trackers</h2>")
        for (x in dg) {
            val open = if (x.num == 13 || x.status == XStatus.WARN || x.status == XStatus.BAD) " open" else ""
            sb.append("<details id=\"t${x.num}\"$open><summary><span class=\"dot ${x.status.word}\"></span><h2 style=\"display:inline\">${"%02d".format(Locale.US, x.num)} ${h(x.name)}</h2><span class=\"head\">${h(x.headline)}</span></summary>")
            if (x.num == 13 && r.suspects.isNotEmpty()) {
                sb.append("<table><tr><th>#</th><th>app</th><th>pts</th><th>cause</th><th>why</th></tr>")
                for (s in r.suspects) sb.append("<tr><td>${s.rank}</td><td>${h(s.pkg)}${if (s.fleet) " <span class=\"dim\">(fleet)</span>" else ""}</td><td>${num(s.score)}</td><td>${h(s.cause)}</td><td>${h(s.detail.removePrefix("why: "))}</td></tr>")
                sb.append("</table>")
            } else {
                x.bars.forEach { sb.append(bar(it, x.status)) }
                sb.append(kv(x.lines))
                if (x.bars.isEmpty() && x.lines.isEmpty()) sb.append("<p class=\"dim\">${h(x.headline)}</p>")
            }
            sb.append("</details>")
        }
        if (r.fleet.isNotEmpty()) sb.append("<details><summary><h2 style=\"display:inline\">Fleet apps</h2></summary><table>").append(r.fleet.joinToString("") { "<tr><td>${h(it)}</td></tr>" }).append("</table></details>")
        sb.append("</body></html>")
        return XrayRedact.scrub(sb.toString())
    }
}
