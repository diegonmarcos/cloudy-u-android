package com.diegonmarcos.superapp.bgpolicy

import org.json.JSONArray
import org.json.JSONObject

/** One declared step of Super Saving (`assets/super-saving.json`): the common fields and the rest of the object for its kind. */
data class SsStep(val id: String, val label: String, val kind: String, val adb: Boolean, val optional: Boolean, val p: JSONObject)

object SsDecl {
    fun parse(json: String): List<SsStep> = JSONObject(json).getJSONArray("steps").let { a ->
        (0 until a.length()).map { i -> a.getJSONObject(i).let { SsStep(it.getString("id"), it.getString("label"), it.getString("kind"), it.optBoolean("adb", true), it.optBoolean("optional", false), it) } }
    }
    fun indicator(json: String): Triple<String, String, String> = JSONObject(json).getJSONObject("indicator").let { Triple(it.getString("title"), it.getString("text"), it.getString("off")) }
}

/** The app-side effects Super Saving needs that are not shell commands. Each reads back what it just set. */
interface SsHost {
    fun masterSync(): Boolean
    fun setMasterSync(on: Boolean)
    fun idleArmed(): Boolean
    fun armIdle(on: Boolean)
    fun keeperEnabled(): Boolean
    fun setKeeper(on: Boolean)
    fun packages(): List<BgPkg>
}

enum class SsState { APPLIED, SKIPPED, FAILED }
data class SsResult(val id: String, val label: String, val state: SsState, val note: String, val needsAdb: Boolean)

/**
 * #1506 Super Saving Battery Mode: the ordered, data-declared list of reversible steps, with a snapshot of each step's prior
 * state taken just before the step changes it. On applies the steps in order; Off puts them back in reverse order from the
 * snapshot, so the phone returns to exactly what it was. A step that needs the shell is skipped with a note when there is none
 * (and is not in the snapshot, so Off has nothing to undo for it). Each step writes an audit entry. Runs on a tap only.
 */
class SuperSaving(
    private val steps: List<SsStep>,
    private val shell: BgShell?,
    private val host: SsHost,
    private val bg: BgEngine,
    private val cfg: BgConfig,
    private val kv: BgKv,
    private val audit: (String) -> Unit = {},
) {
    private fun sh(cmd: String): String? = runCatching { shell?.exec(cmd) }.getOrNull()
    private fun snapshot(): JSONObject? = kv.get(K_SNAP)?.let { runCatching { JSONObject(it) }.getOrNull() }
    private fun save(s: JSONObject) = kv.put(K_SNAP, s.toString())

    val active: Boolean get() = snapshot() != null

    private fun result(s: SsStep, st: SsState, note: String): SsResult {
        audit("super-saving ${s.id}: ${st.name.lowercase()}${if (note.isNotEmpty()) " ($note)" else ""}")
        return SsResult(s.id, s.label, st, note, s.adb)
    }

    // ── On ──
    fun on(): List<SsResult> {
        if (active) return listOf(SsResult("mode", "Super Saving Battery Mode", SsState.SKIPPED, "already on, turn it off first", false))
        val snap = JSONObject().put("steps", JSONObject()).put("at", System.currentTimeMillis())
        save(snap) // a crash half way still leaves a snapshot that Off can read
        val out = ArrayList<SsResult>()
        val keep = snap.getJSONObject("steps")
        for (s in steps) {
            if (s.adb && shell == null) { out += result(s, SsState.SKIPPED, BgOutcome.NO_ADB); continue }
            val entry = JSONObject()
            val r = runCatching { applyStep(s, entry, out) }.getOrElse { result(s, SsState.FAILED, it.message ?: "error") }
            if (r.state != SsState.SKIPPED) { entry.put("state", r.state.name); keep.put(s.id, entry); save(snap) }
            out += r
        }
        if (keep.length() == 0) clear() // nothing applied: no mode to leave
        return out
    }

    private fun applyStep(s: SsStep, entry: JSONObject, done: List<SsResult>): SsResult = when (s.kind) {
        "bg_policy" -> {
            val already = JSONArray(bg.restrictedChoices().toList())
            entry.put("already", already)
            val outs = bg.restrictAll(host.packages(), cfg)
            val ok = outs.count { it.ok }; val bad = outs.count { !it.ok && !it.skipped }
            result(s, if (bad == 0) SsState.APPLIED else SsState.FAILED, "$ok restricted, $bad did not take, ${outs.count { it.skipped }} essential or skipped")
        }
        "setting" -> applySettings(s, entry)
        "setting_cap" -> {
            val ns = s.p.getString("ns"); val key = s.p.getString("key"); val max = s.p.getInt("max")
            val old = sh(BgCmd.settingGet(ns, key))?.trim() ?: return result(s, SsState.FAILED, "could not read $key")
            val cur = old.toDoubleOrNull()
            if (cur == null || cur <= max) result(s, SsState.SKIPPED, "$key is ${if (cur == null) "automatic or unset" else old}, within the cap")
            else {
                entry.put("old", JSONObject().put("$ns/$key", old))
                sh(BgCmd.settingPut(ns, key, max.toString()))
                val now = sh(BgCmd.settingGet(ns, key))?.trim()
                if (now == max.toString()) result(s, SsState.APPLIED, "$key $old to $max") else result(s, SsState.FAILED, "$key reads $now after the write")
            }
        }
        "shell_toggle" -> applyToggle(s, entry)
        "sync" -> {
            val old = host.masterSync()
            if (!old) result(s, SsState.SKIPPED, "auto-sync was already off")
            else { entry.put("old", true); host.setMasterSync(false); if (!host.masterSync()) result(s, SsState.APPLIED, "") else result(s, SsState.FAILED, "auto-sync is still on") }
        }
        "idle_screen_off" -> {
            entry.put("old", host.idleArmed()); host.armIdle(true)
            if (host.idleArmed()) result(s, SsState.APPLIED, "force-idle on every screen-off until Off") else result(s, SsState.FAILED, "the screen-off hook did not arm")
        }
        "keeper" -> {
            val adbApplied = done.any { it.needsAdb && it.state == SsState.APPLIED }
            if (adbApplied) result(s, SsState.SKIPPED, "kept: Off needs the ADB shell")
            else if (!host.keeperEnabled()) result(s, SsState.SKIPPED, "the keeper was already off")
            else { entry.put("old", true); host.setKeeper(false); if (!host.keeperEnabled()) result(s, SsState.APPLIED, "") else result(s, SsState.FAILED, "the keeper is still on") }
        }
        "fleet_stop" -> {
            val installed = host.packages().map { it.pkg }.toSet()
            val outs = s.p.getJSONArray("packages").let { a -> (0 until a.length()).map { a.getString(it) } }.filter { it in installed }.map { bg.forceStop(it) }
            val bad = outs.count { !it.ok && !it.skipped }
            result(s, if (bad == 0) SsState.APPLIED else SsState.FAILED, "${outs.count { it.ok }} engines stopped, $bad did not stop, ${outs.count { it.skipped }} kept")
        }
        else -> result(s, SsState.FAILED, "unknown step kind ${s.kind}")
    }

    private fun applySettings(s: SsStep, entry: JSONObject): SsResult {
        val items = s.p.getJSONArray("items")
        val old = JSONObject(); entry.put("old", old)
        val notes = ArrayList<String>(); var bad = false; var did = 0
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val ns = it.getString("ns"); val key = it.getString("key"); val v = it.getString("value")
            val prior = sh(BgCmd.settingGet(ns, key))?.trim()
            if (prior == null) { bad = true; notes += "could not read $key"; continue }
            if (prior == "null" && it.optBoolean("skip_if_unset", false)) { notes += "$key not present on this device"; continue }
            if (prior == v) { notes += "$key already $v"; continue }
            old.put("$ns/$key", prior)
            sh(BgCmd.settingPut(ns, key, v))
            val now = sh(BgCmd.settingGet(ns, key))?.trim()
            if (now == v) did++ else { bad = true; notes += "$key reads $now, wanted $v" }
        }
        val st = when { bad -> SsState.FAILED; did > 0 -> SsState.APPLIED; else -> SsState.SKIPPED }
        return result(s, st, notes.joinToString("; "))
    }

    private fun applyToggle(s: SsStep, entry: JSONObject): SsResult {
        s.p.optString("require_cmd").takeIf { it.isNotEmpty() }?.let { c ->
            if (sh(c).orEmpty().isBlank()) return result(s, SsState.SKIPPED, s.p.optString("skip_note", "precondition not met"))
        }
        s.p.optString("skip_if_cmd").takeIf { it.isNotEmpty() }?.let { c ->
            val o = sh(c).orEmpty().trim()
            if (o.isNotEmpty() && o != "0") return result(s, SsState.SKIPPED, s.p.optString("skip_note", "condition met"))
        }
        val re = Regex(s.p.getString("read_re"), RegexOption.MULTILINE)
        val old = sh(s.p.getString("read"))?.let { re.find(it)?.groupValues?.get(1) }
            ?: return result(s, SsState.SKIPPED, "the current state cannot be read on this device")
        val skipOld = s.p.optJSONArray("skip_if_old")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        if (old in skipOld) return result(s, SsState.SKIPPED, "already $old")
        entry.put("old", old)
        sh(s.p.getString("apply"))
        val verify = s.p.optJSONArray("verify")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val now = sh(s.p.getString("read"))?.let { re.find(it)?.groupValues?.get(1) }
        return if (verify.isEmpty() || now in verify) result(s, SsState.APPLIED, "$old to ${now ?: "?"}") else result(s, SsState.FAILED, "reads ${now ?: "nothing"} after the change")
    }

    // ── Off ──
    /** Restore every snapshotted step in reverse declared order. A step that cannot be put back (no shell) stays in the snapshot so Off can be run again. */
    fun off(): List<SsResult> {
        val snap = snapshot() ?: return emptyList()
        val keep = snap.optJSONObject("steps") ?: JSONObject()
        val out = ArrayList<SsResult>()
        for (s in steps.asReversed()) {
            val entry = keep.optJSONObject(s.id) ?: continue
            if (s.adb && shell == null) { out += result(s, SsState.SKIPPED, "not restored: ${BgOutcome.NO_ADB}"); continue }
            val r = runCatching { revertStep(s, entry) }.getOrElse { result(s, SsState.FAILED, "restore: ${it.message}") }
            if (r.state == SsState.APPLIED) { keep.remove(s.id); save(snap) }
            out += r
        }
        if (keep.length() == 0) clear()
        return out
    }

    private fun clear() { kv.put(K_SNAP, "") }

    private fun revertStep(s: SsStep, entry: JSONObject): SsResult = when (s.kind) {
        "bg_policy" -> {
            val already = entry.optJSONArray("already")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            val outs = bg.restore(skip = already)
            val bad = outs.count { !it.ok }
            result(s, if (bad == 0) SsState.APPLIED else SsState.FAILED, "${outs.count { it.ok }} apps put back, $bad differ")
        }
        "setting", "setting_cap" -> {
            val old = entry.optJSONObject("old") ?: JSONObject()
            val bad = ArrayList<String>()
            for (k in old.keys()) {
                val (ns, key) = k.split('/', limit = 2)
                val v = old.getString(k)
                sh(if (v == "null") BgCmd.settingDelete(ns, key) else BgCmd.settingPut(ns, key, v))
                val now = sh(BgCmd.settingGet(ns, key))?.trim()
                if (now != v) bad += "$key reads $now, was $v"
            }
            result(s, if (bad.isEmpty()) SsState.APPLIED else SsState.FAILED, bad.joinToString("; "))
        }
        "shell_toggle" -> {
            val old = entry.optString("old")
            val cmd = s.p.optJSONObject("revert_by_old")?.optString(old).orEmpty()
            if (cmd.isEmpty()) result(s, SsState.APPLIED, "nothing to put back for $old")
            else {
                sh(cmd)
                val re = Regex(s.p.getString("read_re"), RegexOption.MULTILINE)
                val now = sh(s.p.getString("read"))?.let { re.find(it)?.groupValues?.get(1) }
                if (now == old || (now == "turning_on" && old == "on")) result(s, SsState.APPLIED, "back to $old") else result(s, SsState.FAILED, "reads ${now ?: "nothing"}, was $old")
            }
        }
        "sync" -> { host.setMasterSync(entry.optBoolean("old", true)); if (host.masterSync() == entry.optBoolean("old", true)) result(s, SsState.APPLIED, "") else result(s, SsState.FAILED, "auto-sync did not come back") }
        "idle_screen_off" -> {
            host.armIdle(entry.optBoolean("old", false)); sh(BgCmd.UNFORCE_IDLE)
            if (host.idleArmed() == entry.optBoolean("old", false)) result(s, SsState.APPLIED, "unforced") else result(s, SsState.FAILED, "the screen-off hook is still armed")
        }
        "keeper" -> { host.setKeeper(entry.optBoolean("old", true)); if (host.keeperEnabled() == entry.optBoolean("old", true)) result(s, SsState.APPLIED, "") else result(s, SsState.FAILED, "the keeper did not come back") }
        "fleet_stop" -> result(s, SsState.APPLIED, "nothing to put back: the engines start again on use, background rights return with the policy")
        else -> result(s, SsState.FAILED, "unknown step kind ${s.kind}")
    }

    companion object {
        const val K_SNAP = "ss_snapshot"
        /** Whether a snapshot is held, i.e. the mode is on (a cheap read for the tile and the indicator). */
        fun isActive(kv: BgKv): Boolean = !kv.get(K_SNAP).isNullOrEmpty()
    }
}
