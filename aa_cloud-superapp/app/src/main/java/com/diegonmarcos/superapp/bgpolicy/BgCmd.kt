package com.diegonmarcos.superapp.bgpolicy

/** #1506 Every shell command the policy runs, as strings, and the parsers of what comes back. Pure; names are validated before they reach a shell line. */
object BgCmd {
    private val PKG = Regex("[A-Za-z0-9_.]+")
    private val COMP = Regex("[A-Za-z0-9_.\$]+")

    fun safePkg(p: String): String { require(PKG.matches(p)) { "not a package name: $p" }; return p }
    fun safeComp(c: String): String { require(COMP.matches(c)) { "not a component name: $c" }; return c }

    fun allow(p: String) = safePkg(p).let { "cmd appops set $it $OP_ANY allow; cmd appops set $it $OP_IN allow; am set-standby-bucket $it active" }

    fun restrict(p: String) = safePkg(p).let {
        "cmd appops set $it $OP_ANY ignore; cmd appops set $it $OP_IN ignore; am set-standby-bucket $it restricted; cmd deviceidle whitelist -$it"
    }

    /** Put a captured state back: an op that had no explicit mode goes back to `default`. */
    fun putBack(p: String, b: BgPrior) = safePkg(p).let {
        "cmd appops set $it $OP_ANY ${b.runAny}; cmd appops set $it $OP_IN ${b.runIn}; " +
            "am set-standby-bucket $it ${Bucket.arg(b.bucket)}; cmd deviceidle whitelist ${if (b.idleWhitelisted) "+" else "-"}$it"
    }

    const val FORCE_IDLE = "cmd deviceidle force-idle"
    const val UNFORCE_IDLE = "cmd deviceidle unforce"

    private val KEY = Regex("[A-Za-z0-9_.\\-]+")
    private val NS = setOf("global", "system", "secure")
    private fun safeKey(k: String): String { require(KEY.matches(k)) { "not a settings key: $k" }; return k }
    private fun safeNs(n: String): String { require(n in NS) { "not a settings namespace: $n" }; return n }
    fun settingGet(ns: String, key: String) = "settings get ${safeNs(ns)} ${safeKey(key)}"
    fun settingPut(ns: String, key: String, v: String) = "settings put ${safeNs(ns)} ${safeKey(key)} ${safeKey(v)}"
    fun settingDelete(ns: String, key: String) = "settings delete ${safeNs(ns)} ${safeKey(key)}"

    fun forceStop(p: String) = "am force-stop ${safePkg(p)}"

    // ── #1720 batched reads and writes: one exec carries many settings, each value after its own marker line ──
    private val MARK = Regex("[\\w.:-]+")
    /** The marker of one value inside a batched exec (the broker's echo rule allows only these characters, so no slash). */
    fun mark(id: String): String { require(MARK.matches(id)) { "not a marker: $id" }; return "echo '@@$id'" }
    fun markOf(ns: String, key: String) = "$ns:$key"

    /** Longest command line a batch may reach; the broker refuses a line over 2048 characters. */
    const val BATCH_MAX = 1800

    /** Pack command lines into as few execs as stay under [BATCH_MAX] each; [sep] joins them (`; ` or a newline, both split by the broker). */
    fun batch(parts: List<String>, sep: String = "; "): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder()
        for (p in parts) {
            if (cur.isNotEmpty() && cur.length + p.length + sep.length > BATCH_MAX) { out += cur.toString(); cur.setLength(0) }
            if (cur.isNotEmpty()) cur.append(sep)
            cur.append(p)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
    private fun chunked(parts: List<String>): List<String> = batch(parts)

    /**
     * [readPkgs] for a long list, split by LENGTH: a fixed 30 packages made a line of several thousand characters, which the
     * broker refuses (over 2048), so the bulk read answered nothing.
     */
    fun readPkgsChunks(pkgs: List<String>): List<String> = batch(pkgs.map { readPkgs(listOf(it)) }, "\n")

    /** Read many settings in as few execs as fit: `mark; get` per key. [keys] = (namespace, key). */
    fun settingsRead(keys: List<Pair<String, String>>): List<String> =
        chunked(keys.map { (ns, k) -> mark(markOf(ns, k)) + "; " + settingGet(ns, k) })

    /** Write many settings and read each one back in the same exec. value null = delete the key (it was unset). */
    fun settingsWrite(items: List<Triple<String, String, String?>>): List<String> =
        chunked(items.map { (ns, k, v) -> (if (v == null) settingDelete(ns, k) else settingPut(ns, k, v)) + "; " + mark(markOf(ns, k)) + "; " + settingGet(ns, k) })

    /** Run [apply], then [read] behind a marker, in one exec. */
    fun thenRead(apply: String, read: String) = "$apply; ${mark(READ_MARK)}; $read"
    const val READ_MARK = "read"

    /** Output of a marked batch → marker → the text that followed it (trimmed). */
    fun parseMarked(out: String): Map<String, String> {
        val res = LinkedHashMap<String, String>()
        var key: String? = null; val buf = StringBuilder()
        fun flush() { key?.let { res[it] = buf.toString().trim() }; buf.setLength(0) }
        for (raw in out.lineSequence()) {
            val l = raw.trimEnd()
            if (l.startsWith("@@")) { flush(); key = l.removePrefix("@@").trim() } else buf.append(l).append('\n')
        }
        flush()
        return res
    }

    fun component(p: String, comp: String, enable: Boolean) =
        "pm ${if (enable) "enable" else "disable"} '${safePkg(p)}/${safeComp(comp)}'"

    /** Per-package read: three sections per package, delimited so one exec can read a chunk. */
    fun readPkgs(pkgs: List<String>): String = pkgs.joinToString("\n") {
        val p = safePkg(it)
        "echo '@@$p'; echo '#ra'; cmd appops get $p $OP_ANY; echo '#ri'; cmd appops get $p $OP_IN; echo '#bk'; am get-standby-bucket $p"
    }

    /** Device-wide read: doze whitelist and the process list (running = a process named for the package). */
    const val READ_GLOBAL = "echo '#idle'; dumpsys deviceidle whitelist; echo '#ps'; ps -A -o NAME"

    const val CHUNK = 30

    // ── parsers ──
    /** `cmd appops get p OP` → the explicit mode, or "default" when none is recorded. */
    fun opMode(out: String, op: String): String =
        Regex("$op:\\s*([a-z_]+)").find(out)?.groupValues?.get(1) ?: "default"

    /** `am get-standby-bucket` prints the number (or, on some builds, the name). */
    fun bucketOf(out: String): Int {
        val t = out.trim().lowercase()
        t.toIntOrNull()?.let { return it }
        return when {
            Regex("exempt").containsMatchIn(t) -> Bucket.EXEMPTED
            Regex("restrict").containsMatchIn(t) -> Bucket.RESTRICTED
            Regex("active").containsMatchIn(t) -> Bucket.ACTIVE
            Regex("working").containsMatchIn(t) -> Bucket.WORKING_SET
            Regex("frequent").containsMatchIn(t) -> Bucket.FREQUENT
            Regex("rare").containsMatchIn(t) -> Bucket.RARE
            Regex("never").containsMatchIn(t) -> Bucket.NEVER
            else -> Bucket.UNKNOWN
        }
    }

    class Global(val idle: Set<String>, val processes: Set<String>) {
        fun running(pkg: String) = processes.any { it == pkg || it.startsWith("$pkg:") }
    }

    fun parseGlobal(out: String): Global {
        val idle = HashSet<String>(); val ps = HashSet<String>()
        var sec = ""
        for (raw in out.lineSequence()) {
            val l = raw.trim()
            if (l == "#idle" || l == "#ps") { sec = l; continue }
            if (l.isEmpty()) continue
            if (sec == "#idle") { val f = l.split(','); if (f.size >= 2 && (f[0] == "user" || f[0].startsWith("system"))) idle += f[1] }
            else if (sec == "#ps" && l != "NAME") ps += l
        }
        return Global(idle, ps)
    }

    /** Output of [readPkgs] → package → (anyOut, inOut, bucketOut). */
    fun parsePkgs(out: String): Map<String, Triple<String, String, String>> {
        val res = LinkedHashMap<String, Triple<String, String, String>>()
        var pkg: String? = null; var sec = ""
        val buf = StringBuilder(); var ra = ""; var ri = ""
        fun flush() {
            val p = pkg ?: return
            val s = buf.toString()
            when (sec) { "#ra" -> ra = s; "#ri" -> ri = s; "#bk" -> res[p] = Triple(ra, ri, s) }
            buf.setLength(0)
        }
        for (raw in out.lineSequence()) {
            val l = raw.trimEnd()
            when {
                l.startsWith("@@") -> { flush(); pkg = l.removePrefix("@@").trim(); sec = ""; ra = ""; ri = "" }
                l == "#ra" || l == "#ri" || l == "#bk" -> { flush(); sec = l }
                else -> buf.append(l).append('\n')
            }
        }
        flush()
        return res
    }
}
