package com.diegonmarcos.superapp.perms

import android.content.Context

/**
 * #1822 "Apps from unverified developers": the Android Developer Verifier (com.google.android.verifier) and the owner's
 * allowance in Developer options. The app cannot read either itself (package visibility, a system setting it has no
 * permission for), so the shell channel measures them: [probe] runs four read-only commands through the caller's `exec`
 * (the privileged plane at every connect, and the Perms row's Shell action) and remembers the answer with a timestamp.
 * [status] only reads that memory; with nothing measured, or an answer it cannot interpret, it says UNKNOWN. Never green by default.
 *
 * The settings key behind Developer options > "Apps from unverified developers" is NOT in AOSP (checked: Settings.java,
 * PackageInstaller, the Settings app's development_settings.xml and strings; the verifier is a Google Play system component).
 * So [parse] does not guess one key: it scans `settings list global|secure` for key names that mention developer verification
 * ([KEY_HINT]) and maps only values it can read unambiguously; anything else is UNKNOWN and carries the raw `key=value`
 * so the on-phone check can name the key and tighten [interpret]. Installs over adb are not gated by the verifier.
 */
object DevVerifier {
    const val ID = "developer_verifier"
    const val PKG = "com.google.android.verifier"

    /** Opens Developer options, where the owner completes PIN, confirmation, reboot and the 24 h wait Android reserves for the user. */
    const val OPEN_CMD = "cmd activity start-activity -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS"

    val PROBE_PRESENT = "pm list packages $PKG 2>&1"
    val PROBE_DISABLED = "pm list packages -d $PKG 2>&1"
    val PROBE_GLOBAL = "settings list global 2>&1"
    val PROBE_SECURE = "settings list secure 2>&1"
    val PROBE_DEV_OPTIONS = "settings get global development_settings_enabled 2>&1"
    val PROBE_BOOT_ID = "cat /proc/sys/kernel/random/boot_id 2>&1"
    val PROBE_UPTIME = "cat /proc/uptime 2>&1"
    val PROBES = listOf(PROBE_PRESENT, PROBE_DISABLED, PROBE_GLOBAL, PROBE_SECURE, PROBE_DEV_OPTIONS, PROBE_BOOT_ID, PROBE_UPTIME)

    enum class State { NOT_PRESENT, DISABLED, ALLOWED_INDEFINITELY, ALLOWED_UNTIL, PENDING_24H, NOT_ALLOWED, UNKNOWN }

    /** [until] = epoch ms for ALLOWED_UNTIL (expiry) and PENDING_24H (end of the wait); 0 otherwise. */
    data class Reading(val state: State, val until: Long = 0L, val detail: String = "")

    /** Key names that mention developer verification; the legacy package_verifier_* / verifier_verify_adb_installs keys do not match. */
    val KEY_HINT = Regex("developer_?verif|unverified_?(dev|app)|verifier_?(allow|bypass|developer)|dev_?verification", RegexOption.IGNORE_CASE)

    private fun packageLine(out: String?) = out.orEmpty().lineSequence().any { it.trim() == "package:$PKG" }

    /** `key=value` lines of `settings list` whose key matches [KEY_HINT]. */
    fun matches(listOut: String?): List<Pair<String, String>> = listOut.orEmpty().lineSequence()
        .mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }
        .filter { (k, _) -> KEY_HINT.containsMatchIn(k) }.toList()

    /** One setting reading; only unambiguous values map to a state. Epoch-ms numbers (>= 1e12) are expiries. */
    fun interpret(key: String, value: String, now: Long): Reading {
        val v = value.trim().lowercase()
        val raw = "$key=$value"
        val ms = v.toLongOrNull()?.takeIf { it >= 1_000_000_000_000L }
        val waiting = Regex("pending|wait|cooldown", RegexOption.IGNORE_CASE).containsMatchIn(key)
        return when {
            v in setOf("indefinitely", "indefinite", "forever", "allowed_indefinitely", "allow_indefinitely") ->
                Reading(State.ALLOWED_INDEFINITELY, 0L, raw)
            ms != null && waiting -> if (ms > now) Reading(State.PENDING_24H, ms, raw) else Reading(State.UNKNOWN, 0L, "wait over, final choice not readable: $raw")
            ms != null -> if (ms > now) Reading(State.ALLOWED_UNTIL, ms, raw) else Reading(State.NOT_ALLOWED, 0L, "allowance expired: $raw")
            v in setOf("0", "false", "off", "none", "not_allowed", "disallowed", "disabled", "blocked") -> Reading(State.NOT_ALLOWED, 0L, raw)
            else -> Reading(State.UNKNOWN, 0L, "unrecognised value: $raw")
        }
    }

    /**
     * The state from the four probe answers (null = the shell could not answer that command). Order: package, then the allowance.
     * No answer at all, or no matching key in either list, is UNKNOWN: an absent key is not "not allowed".
     */
    fun parse(present: String?, disabled: String?, global: String?, secure: String?, now: Long): Reading {
        if (present == null) return Reading(State.UNKNOWN, 0L, "no shell channel")
        // a refusal or error text is not "no such package": only blank or package: lines are an answer to pm list
        if (present.lineSequence().any { it.isNotBlank() && !it.trim().startsWith("package:") })
            return Reading(State.UNKNOWN, 0L, "pm list answered: ${present.trim().take(80)}")
        if (!packageLine(present)) return Reading(State.NOT_PRESENT, 0L, "$PKG is not installed")
        if (packageLine(disabled)) return Reading(State.DISABLED, 0L, "$PKG is disabled for this user")
        if (global == null && secure == null) return Reading(State.UNKNOWN, 0L, "settings could not be listed")
        val hits = matches(global) + matches(secure)
        if (hits.isEmpty()) return Reading(State.UNKNOWN, 0L, "verifier present; no developer-verification key in settings (key unconfirmed)")
        val rs = hits.map { (k, v) -> interpret(k, v, now) }
        return rs.firstOrNull { it.state != State.UNKNOWN } ?: rs.first().copy(detail = rs.joinToString("; ") { it.detail })
    }

    private const val PREFS = "dev_verifier"

    /** `settings get` text -> on/off; null when it is not 1 or 0 (e.g. "null" on a device without the key). */
    fun parseFlag(out: String?): Boolean? = when (out?.trim()) { "1" -> true; "0" -> false; else -> null }

    /** `/proc/uptime` text ("12345.67 8910.11") -> ms. */
    fun parseUptimeMs(out: String?): Long? = out?.trim()?.substringBefore(' ')?.toDoubleOrNull()?.let { Math.round(it * 1000) }

    /** Boot id text, or null when the answer is not a uuid-like line. */
    fun parseBootId(out: String?): String? = out?.trim()?.takeIf { Regex("[0-9a-fA-F-]{16,}").matches(it) }

    private fun saved(ctx: Context): DevVerifierFlow.Saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { p ->
        DevVerifierFlow.Saved(p.getString("boot_at_request", "").orEmpty(), p.getLong("window_start", 0L), p.getBoolean("was_allowed", false), p.getBoolean("restart_prompted", false))
    }

    /**
     * One measurement round through [exec] (the plane at connect, the periodic and boot re-check, the row's Shell action): runs the
     * probes, advances the flow and remembers everything. A silent shell (no answer to the package probe) changes nothing and the
     * last measurement is returned. The caller raises the notifications ([DevVerifierFlow.immediate], [DevVerifierFlow.alarms]).
     */
    fun measure(ctx: Context, exec: (String) -> String?, now: Long = System.currentTimeMillis()): DevVerifierFlow.Result {
        val present = exec(PROBE_PRESENT) ?: return flow(ctx, now)
        val reading = parse(present, exec(PROBE_DISABLED), exec(PROBE_GLOBAL), exec(PROBE_SECURE), now)
        val facts = DevVerifierFlow.Facts(reading, parseFlag(exec(PROBE_DEV_OPTIONS)), parseBootId(exec(PROBE_BOOT_ID)), parseUptimeMs(exec(PROBE_UPTIME)))
        val r = DevVerifierFlow.advance(facts, saved(ctx), now)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("state", reading.state.name).putLong("until", reading.until).putString("detail", reading.detail.take(240)).putLong("at", now)
            .putInt("dev_options", when (facts.devOptionsOn) { true -> 1; false -> 0; null -> -1 })
            .putString("boot_at_request", r.saved.bootAtRequest).putLong("window_start", r.saved.windowStart)
            .putBoolean("was_allowed", r.saved.wasAllowed).putBoolean("restart_prompted", r.saved.restartPrompted)
            .putString("step", r.step.name).putLong("end", r.end).putString("note", r.note).apply()
        return r
    }

    /** The reboot prompt was raised: never raise it twice for one request. */
    fun markRestartPrompted(ctx: Context) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("restart_prompted", true).apply() }

    /** Back-compat name: the reading of [measure]. */
    fun probe(ctx: Context, exec: (String) -> String?, now: Long = System.currentTimeMillis()): Reading = measure(ctx, exec, now).reading

    /** The last measurement; UNKNOWN when none yet. An allowance whose expiry has passed since reads as NOT_ALLOWED. */
    fun recall(ctx: Context, now: Long = System.currentTimeMillis()): Reading {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val st = State.values().firstOrNull { it.name == p.getString("state", null) }
            ?: return Reading(State.UNKNOWN, 0L, "not measured yet: needs the adb channel")
        val r = Reading(st, p.getLong("until", 0L), p.getString("detail", "").orEmpty())
        return if (r.state == State.ALLOWED_UNTIL && r.until <= now) Reading(State.NOT_ALLOWED, 0L, "allowance expired") else r
    }

    /** The flow at [now] from what is remembered (no shell): the row text, countdown and the receivers' reschedule read this. */
    fun flow(ctx: Context, now: Long = System.currentTimeMillis()): DevVerifierFlow.Result {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dev = when (p.getInt("dev_options", -1)) { 1 -> true; 0 -> false; else -> null }
        // the remembered boot id stands in for "no reboot since": a reboot is only ever learned from a probe, so no fresh id here
        val r = DevVerifierFlow.advance(DevVerifierFlow.Facts(recall(ctx, now), dev, saved(ctx).bootAtRequest.ifEmpty { null }, null), saved(ctx), now)
        val note = r.note.ifEmpty { p.getString("note", "").orEmpty().takeIf { p.getString("step", null) == r.step.name }.orEmpty() }
        return r.copy(note = note, reset = false)
    }

    private fun at(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(ms))

    /** The checker's reading alone, as a row state (the flow's [DevVerifierFlow.status] adds the step text). Only allowed indefinitely is GRANTED. */
    fun status(r: Reading): PermStatus = when (r.state) {
        State.ALLOWED_INDEFINITELY -> PermStatus(PermState.GRANTED, "allowed indefinitely")
        State.ALLOWED_UNTIL -> PermStatus(PermState.DENIED, "allowed only until ${at(r.until)}: not permanent, choose Indefinitely")
        State.PENDING_24H -> PermStatus(PermState.UNKNOWN, "24 h wait running, ends ${at(r.until)}")
        State.NOT_ALLOWED -> PermStatus(PermState.DENIED, "not allowed: installs by other installers are checked")
        State.NOT_PRESENT -> PermStatus(PermState.NOT_APPLICABLE, "Android Developer Verifier is not installed")
        State.DISABLED -> PermStatus(PermState.GRANTED, "Android Developer Verifier is disabled for this user")
        State.UNKNOWN -> PermStatus(PermState.UNKNOWN, r.detail.ifBlank { "cannot be read" })
    }

    fun status(ctx: Context): PermStatus = System.currentTimeMillis().let { now -> DevVerifierFlow.status(flow(ctx, now), now) }
}
