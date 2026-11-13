package com.diegonmarcos.superapp.bgpolicy

import org.json.JSONArray
import org.json.JSONObject

/** One declared step of Super Saving (`assets/super-saving.json`): the common fields and the rest of the object for its kind. */
data class SsStep(val id: String, val label: String, val kind: String, val adb: Boolean, val optional: Boolean, val p: JSONObject)

object SsDecl {
    fun parse(json: String): List<SsStep> = parseArray(JSONObject(json).getJSONArray("steps"))
    /** #1720 The same step vocabulary for every mode: a mode of `build.json::ui.launcher_modes` declares its own `steps` array. */
    fun parseArray(a: JSONArray): List<SsStep> =
        (0 until a.length()).map { i -> a.getJSONObject(i).let { SsStep(it.getString("id"), it.getString("label"), it.getString("kind"), it.optBoolean("adb", true), it.optBoolean("optional", false), it) } }
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
    /** #1720 A launcher switch by its declared id; null = this build has no such switch. */
    fun toggle(id: String): Boolean? = null
    fun setToggles(want: Map<String, Boolean>) {}
    /** #1720 Capability detection: the maker (lower case, "" = unknown) and the API level of this device. */
    fun manufacturer(): String = ""
    fun sdk(): Int = Int.MAX_VALUE
}

enum class SsState { APPLIED, SKIPPED, FAILED }
data class SsResult(val id: String, val label: String, val state: SsState, val note: String, val needsAdb: Boolean)

/**
 * #1506 Super Saving Battery Mode: the ordered, data-declared list of reversible steps, with a snapshot of each step's prior
 * state taken just before the step changes it. On applies the steps in order; Off puts them back in reverse order from the
 * snapshot, so the phone returns to exactly what it was. A step that needs the shell is skipped with a note when there is none
 * (and is not in the snapshot, so Off has nothing to undo for it). Each step writes an audit entry. Runs on a tap only.
 *
 * #1720 The same engine now carries every Presets mode ([modeId]; Power, Focus, Saving Energy declare their steps in
 * build.json::ui.launcher_modes). What changed, and why:
 *  - THE JOURNAL (the snapshot) holds a step only when On captured something to put back. A step that failed before it read
 *    anything used to be journaled with nothing in it, and its restore then failed on every Off: the mode could never be left
 *    (root cause of "0 steps done, 1 did not take": the auto-sync step, the app held no sync-settings permission).
 *    Off now answers "nothing was changed, nothing to put back" for such an entry and drops it.
 *  - first capture wins: [reapply] (idempotent, the mode is already on) never overwrites a captured prior value.
 *  - shell calls are batched: one exec reads every declared setting, each step writes and reads back in one exec.
 *  - capability detection: `when` (samsung / not_samsung), `min_sdk`, `skip_if_unset`, an unreadable state: skipped with the reason.
 *  - the journal names its mode; [moveTo] parks what Off could not restore under a pending key so another mode can start clean.
 */
class SuperSaving(
    private val steps: List<SsStep>,
    private val shell: BgShell?,
    private val host: SsHost,
    private val bg: BgEngine,
    private val cfg: BgConfig,
    private val kv: BgKv,
    private val audit: (String) -> Unit = {},
    val modeId: String = MODE_ID,
    private val label: String = "Super Saving Battery Mode",
    private val tag: String = "super-saving",
    private val journalKey: String = K_SNAP,
) {
    /** How many shell round trips this instance made (the batching is asserted on it). */
    var execs = 0
        private set

    private fun sh(cmd: String): String? {
        val s = shell ?: return null
        execs++
        return runCatching { s.exec(cmd) }.getOrNull()
    }
    private fun snapshot(): JSONObject? = kv.get(journalKey)?.let { runCatching { JSONObject(it) }.getOrNull() }
    private fun save(s: JSONObject) = kv.put(journalKey, s.toString())

    val active: Boolean get() = snapshot() != null

    private fun result(s: SsStep, st: SsState, note: String): SsResult {
        audit("$tag ${s.id}: ${st.name.lowercase()}${if (note.isNotEmpty()) " ($note)" else ""}")
        return SsResult(s.id, s.label, st, note, s.adb)
    }

    // ── batched settings ──
    private val cache = HashMap<String, String>()
    private fun firstLine(v: String): String? = v.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

    private fun declaredKeys(): List<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        for (s in steps) when (s.kind) {
            "setting" -> s.p.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { out += it.optString("ns") to it.optString("key") } }
            "setting_cap" -> out += s.p.optString("ns") to s.p.optString("key")
        }
        return out.toList()
    }

    /** One exec (a few on a long list) for every prior value the steps will ask for. */
    private fun prefetch() {
        if (shell == null) return
        val keys = declaredKeys()
        if (keys.isEmpty()) return
        val cmds = runCatching { BgCmd.settingsRead(keys) }.getOrNull() ?: return // a bad name: the step that owns it reports it
        for (c in cmds) sh(c)?.let { o -> for ((k, v) in BgCmd.parseMarked(o)) firstLine(v)?.let { cache[k] = it } }
    }

    private fun getSetting(ns: String, key: String): String? {
        val m = BgCmd.markOf(ns, key)
        cache[m]?.let { return it }
        return sh(BgCmd.settingGet(ns, key))?.trim()?.takeIf { it.isNotEmpty() }?.also { cache[m] = it }
    }

    /** Write (value null = delete) and read back, one exec: marker → the value the device reports now (null = no answer). */
    private fun writeSettings(items: List<Triple<String, String, String?>>): Map<String, String?> {
        val now = HashMap<String, String?>()
        for (c in BgCmd.settingsWrite(items)) { val o = sh(c) ?: continue; for ((k, v) in BgCmd.parseMarked(o)) now[k] = firstLine(v) }
        for (it in items) { val m = BgCmd.markOf(it.first, it.second); val v = now[m]; if (v != null) cache[m] = v else cache.remove(m) }
        return now
    }

    /** Capability detection: why this device cannot run the step, or null. */
    private fun unsupported(s: SsStep): String? {
        val w = s.p.optString("when")
        val m = host.manufacturer().lowercase()
        if (w == "samsung" && m != "samsung") return "Samsung (One UI) only; this device is ${m.ifEmpty { "not identified as one" }}"
        if (w == "not_samsung" && m == "samsung") return "plain-Android fallback; the Samsung step covers this device"
        val min = s.p.optInt("min_sdk", 0)
        if (min > 0 && host.sdk() < min) return "needs Android API $min, this device is API ${host.sdk()}"
        return null
    }

    private fun strings(a: JSONArray?): List<String> = a?.let { x -> (0 until x.length()).map { x.getString(it) } }.orEmpty()

    // ── On ──
    fun on(): List<SsResult> {
        val open = snapshot()
        if (open != null) return listOf(SsResult("mode", label, SsState.SKIPPED,
            if (modeOf(open) == modeId) "already on, turn it off first" else "another mode holds the restore journal, turn it off first", false))
        return runSteps(JSONObject().put("mode", modeId).put("steps", JSONObject()).put("at", System.currentTimeMillis()))
    }

    /** #1720 Idempotent: the mode is on, apply it again. Only what drifted is written; a captured prior value is never replaced. */
    fun reapply(): List<SsResult> {
        val open = snapshot() ?: return on()
        if (modeOf(open) != modeId) return on()
        if (open.optJSONObject("steps") == null) open.put("steps", JSONObject())
        return runSteps(open)
    }

    private fun runSteps(snap: JSONObject): List<SsResult> {
        save(snap) // a crash half way still leaves a snapshot that Off can read
        prefetch()
        val out = ArrayList<SsResult>()
        val keep = snap.getJSONObject("steps")
        for (s in steps) {
            if (s.adb && shell == null) { out += result(s, SsState.SKIPPED, BgOutcome.NO_ADB); continue }
            val why = unsupported(s)
            if (why != null) { out += result(s, SsState.SKIPPED, why); continue }
            val existed = keep.optJSONObject(s.id)
            val entry = existed ?: JSONObject()
            val r = runCatching { applyStep(s, entry, out) }.getOrElse { result(s, SsState.FAILED, it.message ?: it.javaClass.simpleName) }
            // journaled only when there is something to put back: an applied step, or a failed one that captured a prior value
            val captured = entry.keys().asSequence().any { it != "state" }
            if (existed != null || r.state == SsState.APPLIED || (r.state == SsState.FAILED && captured)) {
                if (existed == null || r.state != SsState.SKIPPED) entry.put("state", r.state.name)
                keep.put(s.id, entry); save(snap)
            }
            out += r
        }
        if (keep.length() == 0) clear() // nothing applied: no mode to leave
        return out
    }

    private fun applyStep(s: SsStep, entry: JSONObject, done: List<SsResult>): SsResult = when (s.kind) {
        "bg_policy" -> {
            if (!entry.has("already")) entry.put("already", JSONArray(bg.restrictedChoices().toList()))
            val outs = bg.restrictAll(host.packages(), cfg)
            val ok = outs.count { it.ok }; val bad = outs.count { !it.ok && !it.skipped }
            result(s, if (bad == 0) SsState.APPLIED else SsState.FAILED, "$ok restricted, $bad did not take, ${outs.count { it.skipped }} essential or skipped")
        }
        "setting" -> applySettings(s, entry)
        "setting_cap" -> {
            val ns = s.p.getString("ns"); val key = s.p.getString("key"); val max = s.p.getInt("max")
            val old = getSetting(ns, key) ?: return result(s, SsState.FAILED, "could not read $key")
            val cur = old.toDoubleOrNull()
            if (cur == null || cur <= max) result(s, SsState.SKIPPED, "$key is ${if (cur == null) "automatic or unset" else old}, within the cap")
            else {
                val o = entry.optJSONObject("old") ?: JSONObject().also { entry.put("old", it) }
                if (!o.has("$ns/$key")) o.put("$ns/$key", old)
                val now = writeSettings(listOf(Triple(ns, key, max.toString())))[BgCmd.markOf(ns, key)]
                if (now == max.toString()) result(s, SsState.APPLIED, "$key $old to $max") else result(s, SsState.FAILED, "$key reads $now after the write")
            }
        }
        "shell_toggle" -> applyToggle(s, entry)
        "launcher_toggles" -> applyLauncher(s, entry)
        "sync" -> {
            val old = host.masterSync()
            if (!old) result(s, SsState.SKIPPED, "auto-sync was already off")
            else { if (!entry.has("old")) entry.put("old", true); host.setMasterSync(false); if (!host.masterSync()) result(s, SsState.APPLIED, "") else result(s, SsState.FAILED, "auto-sync is still on") }
        }
        "idle_screen_off" -> {
            if (!entry.has("old")) entry.put("old", host.idleArmed())
            host.armIdle(true)
            if (host.idleArmed()) result(s, SsState.APPLIED, "force-idle on every screen-off until Off") else result(s, SsState.FAILED, "the screen-off hook did not arm")
        }
        "keeper" -> {
            val adbApplied = done.any { it.needsAdb && it.state == SsState.APPLIED }
            if (adbApplied) result(s, SsState.SKIPPED, "kept: Off needs the ADB shell")
            else if (!host.keeperEnabled()) result(s, SsState.SKIPPED, "the keeper was already off")
            else { if (!entry.has("old")) entry.put("old", true); host.setKeeper(false); if (!host.keeperEnabled()) result(s, SsState.APPLIED, "") else result(s, SsState.FAILED, "the keeper is still on") }
        }
        "fleet_stop" -> {
            val installed = host.packages().map { it.pkg }.toSet()
            val outs = strings(s.p.getJSONArray("packages")).filter { it in installed }.map { bg.forceStop(it) }
            val bad = outs.count { !it.ok && !it.skipped }
            result(s, if (bad == 0) SsState.APPLIED else SsState.FAILED, "${outs.count { it.ok }} engines stopped, $bad did not stop, ${outs.count { it.skipped }} kept")
        }
        else -> result(s, SsState.FAILED, "unknown step kind ${s.kind}")
    }

    private fun applySettings(s: SsStep, entry: JSONObject): SsResult {
        val items = s.p.getJSONArray("items")
        val old = entry.optJSONObject("old") ?: JSONObject()
        val notes = ArrayList<String>(); var bad = false; var did = 0
        val writes = ArrayList<Triple<String, String, String?>>()
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val ns = it.getString("ns"); val key = it.getString("key"); val v = it.getString("value")
            val prior = getSetting(ns, key)
            if (prior == null) { bad = true; notes += "could not read $key"; continue }
            if (prior == "null" && it.optBoolean("skip_if_unset", false)) { notes += "$key not present on this device"; continue }
            if (prior == v) { notes += "$key already $v"; continue }
            if (!old.has("$ns/$key")) old.put("$ns/$key", prior) // first capture wins
            writes += Triple(ns, key, v)
        }
        if (old.length() > 0) entry.put("old", old)
        if (writes.isNotEmpty()) {
            val now = writeSettings(writes)
            for (w in writes) {
                val n = now[BgCmd.markOf(w.first, w.second)]
                if (n == w.third) did++ else { bad = true; notes += if (n == null) "${w.second}: the shell did not answer the write" else "${w.second} reads $n, wanted ${w.third}" }
            }
        }
        val st = when { bad -> SsState.FAILED; did > 0 -> SsState.APPLIED; else -> SsState.SKIPPED }
        return result(s, st, notes.joinToString("; "))
    }

    private fun applyToggle(s: SsStep, entry: JSONObject): SsResult {
        s.p.optString("require_cmd").takeIf { it.isNotEmpty() }?.let { c ->
            val o = sh(c).orEmpty()
            val re = s.p.optString("require_re")
            val met = if (re.isEmpty()) o.isNotBlank() else Regex(re).containsMatchIn(o)
            if (!met) return result(s, SsState.SKIPPED, s.p.optString("skip_note", "precondition not met"))
        }
        s.p.optString("skip_if_cmd").takeIf { it.isNotEmpty() }?.let { c ->
            val o = sh(c).orEmpty().trim()
            val re = s.p.optString("skip_if_re")
            val hit = if (re.isEmpty()) o.isNotEmpty() && o != "0" else Regex(re, RegexOption.IGNORE_CASE).containsMatchIn(o)
            if (hit) return result(s, SsState.SKIPPED, s.p.optString("skip_note", "condition met"))
        }
        val re = Regex(s.p.getString("read_re"), RegexOption.MULTILINE)
        val read = s.p.getString("read")
        val old = sh(read)?.let { re.find(it)?.groupValues?.get(1) }
            ?: return result(s, SsState.SKIPPED, "the current state cannot be read on this device")
        if (old in strings(s.p.optJSONArray("skip_if_old"))) return result(s, SsState.SKIPPED, "already $old")
        if (!entry.has("old")) entry.put("old", old)
        val verify = strings(s.p.optJSONArray("verify"))
        val after = sh(BgCmd.thenRead(s.p.getString("apply"), read))
        val now = after?.let { BgCmd.parseMarked(it)[BgCmd.READ_MARK] }?.let { re.find(it)?.groupValues?.get(1) }
        return when {
            verify.isEmpty() || now in verify -> result(s, SsState.APPLIED, "$old to ${now ?: "?"}")
            after == null -> result(s, SsState.FAILED, "the shell refused the command or did not answer")
            else -> result(s, SsState.FAILED, "reads ${now ?: "nothing"} after the change")
        }
    }

    /** #1720 The mode's launcher switches: only those that differ are written, and the value each had is journaled. */
    private fun applyLauncher(s: SsStep, entry: JSONObject): SsResult {
        val want = s.p.optJSONObject("toggles") ?: JSONObject()
        val old = entry.optJSONObject("old") ?: JSONObject()
        val set = entry.optJSONObject("set") ?: JSONObject()
        val change = LinkedHashMap<String, Boolean>()
        for (id in want.keys()) {
            val w = want.getBoolean(id)
            val cur = host.toggle(id) ?: continue
            if (cur != w) { if (!old.has(id)) old.put(id, cur); set.put(id, w); change[id] = w }
        }
        if (change.isEmpty()) return result(s, SsState.SKIPPED, "every switch already matches")
        entry.put("old", old); entry.put("set", set)
        host.setToggles(change)
        val bad = change.filter { host.toggle(it.key) != it.value }.keys
        return if (bad.isEmpty()) result(s, SsState.APPLIED, "${change.size} switches set") else result(s, SsState.FAILED, "did not take: ${bad.joinToString()}")
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
            val r = runCatching { revertStep(s, entry) }.getOrElse { result(s, SsState.FAILED, "restore: ${it.message ?: it.javaClass.simpleName}") }
            if (r.state == SsState.APPLIED) { keep.remove(s.id); save(snap) }
            out += r
        }
        // an entry whose step is no longer declared has no step to restore it: dropped, with a line in the audit
        val ids = steps.map { it.id }.toSet()
        for (k in keep.keys().asSequence().toList()) if (k !in ids) { keep.remove(k); save(snap); audit("$tag $k: dropped from the journal, the step is no longer declared") }
        if (keep.length() == 0) clear()
        return out
    }

    private fun clear() { kv.put(journalKey, "") }

    /** #1720 The steps still waiting to be restored (journal entries Off could not put back yet). */
    fun remaining(): List<String> = snapshot()?.optJSONObject("steps")?.keys()?.asSequence()?.toList().orEmpty()

    /** #1720 Park what is left of this journal under [toKey] (an entry already parked there wins: it is the older prior) and close this one. */
    fun moveTo(toKey: String) {
        val snap = snapshot() ?: return
        val dst = kv.get(toKey)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject().put("mode", modeOf(snap)).put("at", snap.optLong("at"))
        val d = dst.optJSONObject("steps") ?: JSONObject().also { dst.put("steps", it) }
        val src = snap.optJSONObject("steps") ?: JSONObject()
        for (k in src.keys()) if (!d.has(k)) d.put(k, src.get(k))
        if (d.length() > 0) kv.put(toKey, dst.toString())
        clear()
    }

    private val nothing = "nothing was changed by On, nothing to put back"

    private fun revertStep(s: SsStep, entry: JSONObject): SsResult = when (s.kind) {
        "bg_policy" -> {
            val already = strings(entry.optJSONArray("already")).toSet()
            val outs = bg.restoreBatched(skip = already)
            val bad = outs.count { !it.ok }
            result(s, if (bad == 0) SsState.APPLIED else SsState.FAILED, "${outs.count { it.ok }} apps put back, $bad differ")
        }
        "setting", "setting_cap" -> {
            val old = entry.optJSONObject("old") ?: JSONObject()
            val items = old.keys().asSequence().toList().map { k ->
                val parts = k.split('/', limit = 2)
                Triple(parts[0], parts.getOrElse(1) { "" }, old.getString(k).takeIf { it != "null" })
            }
            if (items.isEmpty()) result(s, SsState.APPLIED, nothing)
            else {
                val now = writeSettings(items)
                val bad = items.mapNotNull { w ->
                    val n = now[BgCmd.markOf(w.first, w.second)]
                    when { n == (w.third ?: "null") -> null; n == null -> "${w.second}: the shell did not answer the restore"; else -> "${w.second} reads $n, was ${w.third ?: "unset"}" }
                }
                result(s, if (bad.isEmpty()) SsState.APPLIED else SsState.FAILED, bad.joinToString("; "))
            }
        }
        "shell_toggle" -> {
            val old = entry.optString("old")
            val cmd = s.p.optJSONObject("revert_by_old")?.optString(old).orEmpty()
            if (!entry.has("old")) result(s, SsState.APPLIED, nothing)
            else if (cmd.isEmpty()) result(s, SsState.APPLIED, "nothing to put back for $old")
            else {
                val re = Regex(s.p.getString("read_re"), RegexOption.MULTILINE)
                val after = sh(BgCmd.thenRead(cmd, s.p.getString("read")))
                val now = after?.let { BgCmd.parseMarked(it)[BgCmd.READ_MARK] }?.let { re.find(it)?.groupValues?.get(1) }
                when {
                    now == old || (now == "turning_on" && old == "on") -> result(s, SsState.APPLIED, "back to $old")
                    after == null -> result(s, SsState.FAILED, "the shell refused the restore command or did not answer; was $old")
                    else -> result(s, SsState.FAILED, "reads ${now ?: "nothing"}, was $old")
                }
            }
        }
        "launcher_toggles" -> {
            val old = entry.optJSONObject("old") ?: JSONObject(); val set = entry.optJSONObject("set") ?: JSONObject()
            val back = LinkedHashMap<String, Boolean>(); var left = 0
            for (id in old.keys()) {
                val cur = host.toggle(id)
                // put back only what is still as the mode set it: a switch moved by hand (or by a theme) since is the owner's choice
                if (cur != null && set.has(id) && cur == set.getBoolean(id)) back[id] = old.getBoolean(id) else left++
            }
            if (back.isNotEmpty()) host.setToggles(back)
            val bad = back.filter { host.toggle(it.key) != it.value }.keys
            if (bad.isEmpty()) result(s, SsState.APPLIED, "${back.size} switches put back" + if (left > 0) ", $left changed since and left as they are" else "")
            else result(s, SsState.FAILED, "did not go back: ${bad.joinToString()}")
        }
        "sync" ->
            if (!entry.has("old")) result(s, SsState.APPLIED, nothing)
            else { host.setMasterSync(entry.optBoolean("old", true)); if (host.masterSync() == entry.optBoolean("old", true)) result(s, SsState.APPLIED, "") else result(s, SsState.FAILED, "auto-sync did not come back") }
        "idle_screen_off" ->
            if (!entry.has("old")) result(s, SsState.APPLIED, nothing)
            else {
                host.armIdle(entry.optBoolean("old", false)); sh(BgCmd.UNFORCE_IDLE)
                if (host.idleArmed() == entry.optBoolean("old", false)) result(s, SsState.APPLIED, "unforced") else result(s, SsState.FAILED, "the screen-off hook is still armed")
            }
        "keeper" ->
            if (!entry.has("old")) result(s, SsState.APPLIED, nothing)
            else { host.setKeeper(entry.optBoolean("old", true)); if (host.keeperEnabled() == entry.optBoolean("old", true)) result(s, SsState.APPLIED, "") else result(s, SsState.FAILED, "the keeper did not come back") }
        "fleet_stop" -> result(s, SsState.APPLIED, "nothing to put back: the engines start again on use, background rights return with the policy")
        else -> result(s, SsState.FAILED, "unknown step kind ${s.kind}")
    }

    companion object {
        const val K_SNAP = "ss_snapshot"
        /** The mode a journal without a `mode` field belongs to (every journal written before #1720). */
        const val MODE_ID = "super_saving"
        /** Whether a snapshot is held, i.e. the mode is on (a cheap read for the tile and the indicator). */
        fun isActive(kv: BgKv): Boolean = !kv.get(K_SNAP).isNullOrEmpty()
        private fun modeOf(snap: JSONObject): String = snap.optString("mode").ifEmpty { MODE_ID }
        /** #1720 The mode the open journal belongs to, or null when no journal is open. */
        fun journalMode(kv: BgKv): String? = kv.get(K_SNAP)?.takeIf { it.isNotEmpty() }?.let { runCatching { modeOf(JSONObject(it)) }.getOrNull() }
        /** #1720 Where the unrestored rest of a mode's journal is parked when another mode takes over. */
        fun pendingKey(mode: String) = "ss_pending_$mode"
    }
}
