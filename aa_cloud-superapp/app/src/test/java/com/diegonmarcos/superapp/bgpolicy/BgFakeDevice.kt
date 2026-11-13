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

    // ── the rest of the phone, for Super Saving ──
    val settings = HashMap<String, String>()
    var nfc: String? = "on"
    var night = "no"
    var wifiConnected = true
    var btConnected = false
    var idleForced = false
    /** Settings keys ("ns/key") the device silently ignores a write to. */
    val refuseSettings = HashSet<String>()

    override fun exec(cmd: String): String? {
        log += cmd
        // #1720 a batch (several statements in one exec) is answered statement by statement, like `sh -c` does
        if (!cmd.contains(';') && !cmd.contains('\n')) special(cmd)?.let { return it }
        val out = StringBuilder()
        for (line in cmd.split("\n")) for (stmt in line.split(";").map { it.trim() }.filter { it.isNotEmpty() }) {
            val sp = special(stmt)
            if (sp != null) out.append(sp) else run(stmt, out)
        }
        return out.toString()
    }

    private fun special(c: String): String? {
        val t = c.split(" ")
        return when {
            c.startsWith("settings get ") -> (settings["${t[2]}/${t[3]}"] ?: "null") + "\n"
            c.startsWith("settings put ") -> { if ("${t[2]}/${t[3]}" !in refuseSettings) settings["${t[2]}/${t[3]}"] = t[4]; "" }
            c.startsWith("settings delete ") -> { settings.remove("${t[2]}/${t[3]}"); "" }
            c == "cmd uimode night" -> "Night mode: $night\n"
            c.startsWith("cmd uimode night ") -> { night = t[3]; "" }
            c.startsWith("dumpsys nfc") -> nfc?.let { "mState=$it\n" } ?: ""
            c == "svc nfc disable" -> { nfc = "off"; "" }
            c == "svc nfc enable" -> { nfc = "on"; "" }
            c == "svc bluetooth disable" -> { settings["global/bluetooth_on"] = "0"; "" }
            c == "svc bluetooth enable" -> { settings["global/bluetooth_on"] = "1"; "" }
            c.startsWith("dumpsys bluetooth_manager") -> if (btConnected) "  state: ON\n  ConnectionState: STATE_CONNECTED\n" else "  state: ON\n  ConnectionState: STATE_DISCONNECTED\n"
            // #1720 the commands the broker allowlist carries (no pipes, no svc): cmd nfc / bluetooth_manager / phone data
            c == "cmd nfc disable-nfc" -> { nfc = "off"; "" }
            c == "cmd nfc enable-nfc" -> { nfc = "on"; "" }
            c == "cmd bluetooth_manager disable" -> { settings["global/bluetooth_on"] = "0"; "" }
            c == "cmd bluetooth_manager enable" -> { settings["global/bluetooth_on"] = "1"; "" }
            c == "cmd phone data disable" -> { settings["global/mobile_data"] = "0"; "" }
            c == "cmd phone data enable" -> { settings["global/mobile_data"] = "1"; "" }
            c.startsWith("cmd notification set_dnd ") -> { settings["global/zen_mode"] = mapOf("off" to "0", "priority" to "1", "none" to "2", "alarms" to "3")[t[3]] ?: "0"; "" }
            c.startsWith("ip route get") -> if (wifiConnected) "dev wlan\n" else ""
            c == "svc data disable" -> { settings["global/mobile_data"] = "0"; "" }
            c == "svc data enable" -> { settings["global/mobile_data"] = "1"; "" }
            c == BgCmd.FORCE_IDLE -> { idleForced = true; "" }
            c == BgCmd.UNFORCE_IDLE -> { idleForced = false; "" }
            else -> null
        }
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
