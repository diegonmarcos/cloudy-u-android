package com.diegonmarcos.superapp.bgpolicy

import java.io.File

/** A fake phone behind the [BgShell] seam: it interprets exactly the commands [BgCmd] builds and answers the way `cmd appops`, `am` and `dumpsys` do. */
class BgFakeDevice(vararg apps: String) : BgShell {
    override val channel = "fake"
    class App(var any: String = "default", var inn: String = "default", var bucket: Int = Bucket.WORKING_SET, var idle: Boolean = false, var running: Boolean = true)

    val state = LinkedHashMap<String, App>().apply { apps.forEach { put(it, App()) } }
    val log = ArrayList<String>()
    val comps = HashMap<String, Boolean>()
    /** Packages whose standby bucket the "device" refuses to change. */
    val refuseBucket = HashSet<String>()

    override fun exec(cmd: String): String? {
        log += cmd
        val out = StringBuilder()
        for (line in cmd.split("\n")) for (stmt in line.split(";").map { it.trim() }.filter { it.isNotEmpty() }) run(stmt, out)
        return out.toString()
    }

    private fun run(s: String, out: StringBuilder) {
        val t = s.split(" ")
        when {
            s.startsWith("echo ") -> out.append(s.removePrefix("echo ").trim('\'')).append('\n')
            s.startsWith("cmd appops set ") -> state[t[3]]?.let { if (t[4] == OP_ANY) it.any = t[5] else it.inn = t[5] }
            s.startsWith("cmd appops get ") -> state[t[3]]?.let {
                val m = if (t[4] == OP_ANY) it.any else it.inn
                out.append(if (m == "default") "No operations.\n" else "${t[4]}: $m; time=+1d\n")
            }
            s.startsWith("am set-standby-bucket ") -> state[t[2]]?.let { if (t[1] !in refuseBucket && t[2] !in refuseBucket) it.bucket = mapOf("active" to 10, "restricted" to 45, "working_set" to 20, "frequent" to 30, "rare" to 40, "exempted" to 5)[t[3]] ?: it.bucket }
            s.startsWith("am get-standby-bucket ") -> state[t[2]]?.let { out.append(it.bucket).append('\n') }
            s.startsWith("cmd deviceidle whitelist ") -> t[3].let { a -> state[a.drop(1)]?.idle = a.startsWith("+") }
            s.startsWith("am force-stop ") -> state[t[2]]?.running = false
            s == "dumpsys deviceidle whitelist" -> state.filter { it.value.idle }.forEach { out.append("user,${it.key},10001\n") }
            s == "ps -A -o NAME" -> { out.append("NAME\n"); state.filter { it.value.running }.forEach { out.append(it.key).append('\n') } }
            s.startsWith("pm disable ") || s.startsWith("pm enable ") -> comps[t[2].trim('\'')] = s.startsWith("pm enable ")
        }
    }

    companion object {
        fun cfg() = BgConfig.parse(File("src/main/assets/bg-policy.json").readText())
        fun kv() = object : BgKv {
            val m = HashMap<String, String>()
            override fun get(key: String) = m[key]
            override fun put(key: String, value: String) { m[key] = value }
        }
    }
}
