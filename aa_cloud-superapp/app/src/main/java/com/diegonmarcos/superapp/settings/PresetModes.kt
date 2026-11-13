package com.diegonmarcos.superapp.settings

import android.content.Context
import com.diegonmarcos.superapp.bgpolicy.BgAudit
import com.diegonmarcos.superapp.bgpolicy.BgHost
import com.diegonmarcos.superapp.bgpolicy.BgKv
import com.diegonmarcos.superapp.bgpolicy.BgPrefsKv
import com.diegonmarcos.superapp.bgpolicy.BgShell
import com.diegonmarcos.superapp.bgpolicy.SsResult
import com.diegonmarcos.superapp.bgpolicy.SsState
import com.diegonmarcos.superapp.bgpolicy.SuperSaving
import com.diegonmarcos.superapp.bgpolicy.SuperSavingRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * #1720 THE single state source of Quick actions > Presets: one value per group, so two active choices in a group cannot be
 * represented. Sandbox = LauncherProfilePrefs, Theme = LauncherThemePrefs, Mode = LauncherModePrefs (key `mode`, null = none).
 * Every surface reads here: the Presets tiles, the Notify > Actions "Presets" badge, Focus ([FocusMode.active]), the Super
 * Saving indicator ([SuperSavingRuntime.active]), the debug API. Reads are three in-memory preference lookups: no shell, no IO
 * after the first load.
 *
 * Before this, "which mode is on" had three stores: the picked tile (launcher_mode_prefs), Super Saving's snapshot (bg_policy)
 * and Focus's own boolean (focus_mode_prefs), so Power Mode and Super Saving could both show as active.
 */
object PresetState {
    data class Now(val sandbox: String, val theme: String, val mode: String?)

    fun now(ctx: Context): Now = Now(LauncherProfilePrefs(ctx).profile.id, LauncherThemePrefs(ctx).theme.id, mode(ctx))

    /** The one active mode, or null. */
    fun mode(ctx: Context): String? = LauncherModePrefs(ctx).selected

    /** Written by the mode switch only ([ModeSwitch]); a pick goes through [PresetModes.select], never here. */
    fun setMode(ctx: Context, id: String?) { LauncherModePrefs(ctx).selected = id; changed(ctx) }

    fun setSandbox(ctx: Context, id: String) { LauncherProfilePrefs(ctx).profile = LauncherProfile.fromId(id); changed(ctx) }

    /** A theme pick: the chrome and the switches it declares, through the one [LauncherThemes.apply]. */
    fun setTheme(ctx: Context, id: String) { LauncherThemes.apply(ctx, LauncherTheme.fromId(id)); changed(ctx) }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Told after every change (on the changing thread: a UI listener posts itself to its own thread). Returns the unsubscribe. */
    fun observe(l: () -> Unit): () -> Unit { listeners += l; return { listeners -= l } }

    fun changed(ctx: Context) {
        for (l in listeners) runCatching { l() }
        runCatching { com.diegonmarcos.superapp.notificationcenter.PresetsBadge.refresh(ctx.applicationContext) }
    }
}

/**
 * #1720 The one-active-mode state machine. Pure (a key-value store, a selection getter / setter and an engine factory), so the
 * single-choice rule, the restore-before-apply order, the migration and the pending journal are unit-tested on a fake phone.
 *
 *  - [select]: the picked mode is already on -> re-apply it (idempotent). Another mode is on -> its Off runs first (restore
 *    from its journal, reverse order); what could not be restored is parked under a pending key with the reason, never lost
 *    and never blocking; then the new mode is applied and becomes the selection.
 *  - [off]: restore the active mode. Steps left unrestored keep the mode selected (the journal is the truth) unless [off] is
 *    forced, which parks them like a switch does.
 *  - [migrate]: repairs a store written before the single state source (a journal of one mode while another is selected).
 */
class ModeSwitch(
    private val modes: () -> List<String>,
    private val getSel: () -> String?,
    private val setSel: (String?) -> Unit,
    private val kv: BgKv,
    /** The engine of a declared mode over the journal at the given key; null = the id is not declared. */
    private val engine: (id: String, journalKey: String) -> SuperSaving?,
    /** A mode that is nothing but its journal (Super Saving): not selected when no step applied. */
    private val journalOnly: (String) -> Boolean = { it == SuperSaving.MODE_ID },
    private val audit: (String) -> Unit = {},
) {
    data class Outcome(
        val mode: String?, val op: String, val results: List<SsResult>, val restored: List<SsResult>, val ok: Boolean, val message: String,
    ) {
        /** Every step that did not take, with its reason (the restore of the previous mode first). */
        val failures: List<SsResult> get() = (restored + results).filter { it.state == SsState.FAILED }
    }

    private fun journalIs(id: String) = SuperSaving.journalMode(kv) == id

    /** Repair the double state; returns what was repaired, or null when the store was already single. */
    @Synchronized fun migrate(legacyFocus: Boolean = false): String? {
        val j = SuperSaving.journalMode(kv)
        val sel = getSel()
        val known = modes()
        val note = when {
            j != null && sel != j -> { setSel(j); "${sel ?: "no mode"} was shown as picked while $j held the restore journal: $j is the one active mode" }
            j == null && sel != null && journalOnly(sel) -> { setSel(null); "$sel was picked with nothing to restore: no mode is active" }
            j == null && sel != null && known.isNotEmpty() && sel !in known -> { setSel(null); "the picked mode $sel is no longer declared: no mode is active" }
            j == null && sel == null && legacyFocus -> { setSel(FOCUS); "Focus was on in its own store: it is now the one active mode" }
            else -> null
        }
        if (note != null) audit("presets state repaired: $note")
        return note
    }

    @Synchronized fun select(id: String): Outcome {
        migrate()
        val target = engine(id, SuperSaving.K_SNAP) ?: return done(Outcome(getSel(), "on", emptyList(), emptyList(), false, "Mode $id is not declared."))
        val cur = getSel()
        if (cur == id) {
            val r = target.reapply()
            if (journalOnly(id) && !journalIs(id)) setSel(null)
            return done(outcome("reapply", r, emptyList()))
        }
        val restored = if (cur != null) leave(cur) else emptyList()
        val r = target.on()
        if (journalIs(id) || !journalOnly(id)) setSel(id)
        return done(outcome("on", r, restored))
    }

    /** Deactivate [cur]: its Off, then whatever is left of its journal parked as pending. The selection is empty afterwards. */
    private fun leave(cur: String): List<SsResult> {
        val e = engine(cur, SuperSaving.K_SNAP)
        val out = e?.off().orEmpty()
        if (SuperSaving.isActive(kv)) {
            if (e != null) e.moveTo(SuperSaving.pendingKey(cur))
            else { kv.put(SuperSaving.pendingKey(cur), kv.get(SuperSaving.K_SNAP).orEmpty()); kv.put(SuperSaving.K_SNAP, "") }
            audit("presets $cur: ${out.count { it.state != SsState.APPLIED }} step(s) not restored, parked as pending")
        }
        setSel(null)
        return out
    }

    @Synchronized fun off(force: Boolean = false): Outcome {
        migrate()
        val cur = getSel() ?: return done(Outcome(null, "off", emptyList(), emptyList(), true, "No mode is on."))
        if (force) return done(outcome("off", emptyList(), leave(cur)))
        val out = engine(cur, SuperSaving.K_SNAP)?.off().orEmpty()
        if (journalIs(cur)) return done(Outcome(cur, "off", emptyList(), out, false, "Off did not finish: " + counts(out) + ". " + reasons(out)))
        setSel(null)
        return done(outcome("off", emptyList(), out))
    }

    /** Modes with a parked (pending) restore journal. */
    fun pending(): List<String> = modes().filter { !kv.get(SuperSaving.pendingKey(it)).isNullOrEmpty() }

    /** The parked steps of [id], by step id. */
    fun pendingSteps(id: String): List<String> = pendingSteps(kv, id)

    @Synchronized fun retryPending(id: String): Outcome {
        val e = engine(id, SuperSaving.pendingKey(id)) ?: return Outcome(getSel(), "retry", emptyList(), emptyList(), false, "Mode $id is not declared.")
        val out = e.off()
        val ok = !e.active
        return done(Outcome(getSel(), "retry:$id", emptyList(), out, ok, (if (ok) "Restored: " else "Still pending: ") + counts(out) + (if (ok) "" else ". " + reasons(out))))
    }

    @Synchronized fun forgetPending(id: String) {
        kv.put(SuperSaving.pendingKey(id), "")
        audit("presets $id: pending restore forgotten on request")
    }

    private fun outcome(op: String, results: List<SsResult>, restored: List<SsResult>): Outcome {
        val bad = (results + restored).count { it.state == SsState.FAILED }
        val head = when (op) { "on" -> "On: "; "reapply" -> "Re-applied: "; else -> "Off: " }
        val msg = buildString {
            if (restored.isNotEmpty() && op != "off") append("previous mode restored (").append(counts(restored)).append("); ")
            append(head).append(counts(if (op == "off") restored else results))
            if (bad > 0) append(". ").append(reasons(results + restored))
        }
        return Outcome(getSel(), op, results, restored, bad == 0, msg)
    }

    /** The last outcome is kept (one small JSON) so the page shows each step's reason after the toast is gone. */
    private fun done(o: Outcome): Outcome {
        runCatching {
            fun rows(l: List<SsResult>) = JSONArray(l.map { JSONObject().put("id", it.id).put("label", it.label).put("state", it.state.name).put("note", it.note).put("adb", it.needsAdb) })
            kv.put(K_REPORT, JSONObject().put("mode", o.mode ?: "").put("op", o.op).put("ok", o.ok).put("message", o.message)
                .put("at", System.currentTimeMillis()).put("steps", rows(o.results)).put("restored", rows(o.restored)).toString())
        }
        return o
    }

    companion object {
        const val FOCUS = "focus"
        const val K_REPORT = "mode_report"

        /** The parked step ids of [id], read straight from the store (no engine, no shell: cheap enough for a repaint). */
        fun pendingSteps(kv: BgKv, id: String): List<String> {
            val raw = kv.get(SuperSaving.pendingKey(id))?.takeIf { it.isNotEmpty() } ?: return emptyList()
            return runCatching { JSONObject(raw).optJSONObject("steps")?.keys()?.asSequence()?.toList() }.getOrNull().orEmpty()
        }

        fun counts(r: List<SsResult>): String {
            val a = r.count { it.state == SsState.APPLIED }; val s = r.count { it.state == SsState.SKIPPED }; val f = r.count { it.state == SsState.FAILED }
            return "$a done, $s skipped" + if (f > 0) ", $f did not take" else ""
        }

        /** The failed steps, each with its reason. */
        fun reasons(r: List<SsResult>): String =
            r.filter { it.state == SsState.FAILED }.joinToString("; ") { "${it.label}: ${it.note.ifBlank { "no reason given" }}" }

        /** The stored last outcome, or null when no mode operation ran yet. */
        fun report(kv: BgKv): ModeReport? {
            val raw = kv.get(K_REPORT)?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching {
                val o = JSONObject(raw)
                fun rows(k: String): List<SsResult> {
                    val a = o.optJSONArray(k) ?: return emptyList()
                    return (0 until a.length()).map { i ->
                        val r = a.getJSONObject(i)
                        SsResult(r.optString("id"), r.optString("label"), SsState.valueOf(r.optString("state")), r.optString("note"), r.optBoolean("adb"))
                    }
                }
                ModeReport(o.optString("mode"), o.optString("op"), o.optBoolean("ok"), o.optString("message"), o.optLong("at"), rows("steps"), rows("restored"))
            }.getOrNull()
        }
    }
}

/** #1720 The last mode operation as stored: which mode is on after it, what ran, and every step with its state and reason. */
class ModeReport(val mode: String, val op: String, val ok: Boolean, val message: String, val at: Long, val steps: List<SsResult>, val restored: List<SsResult>)

/**
 * #1720 The Android side of [ModeSwitch]: the declared modes (build.json::ui.launcher_modes), the engines of
 * [SuperSavingRuntime], the bg_policy store, and one worker so no mode work runs on the main thread and two taps cannot
 * interleave. Every call here blocks on the shell: call it from a worker ([async]) only.
 */
object PresetModes {
    private val worker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "preset-modes").apply { isDaemon = true } } }

    /** Run [block] on the one mode worker. */
    fun async(block: () -> Unit) { runCatching { worker.execute { runCatching(block) } } }

    internal fun switch(ctx: Context, shell: BgShell?): ModeSwitch {
        val app = ctx.applicationContext
        return ModeSwitch(
            modes = { LauncherModes.load().map { it.id } },
            getSel = { PresetState.mode(app) },
            setSel = { PresetState.setMode(app, it) },
            kv = BgPrefsKv(app),
            engine = { id, key -> SuperSavingRuntime.engine(app, id, shell, key) },
            journalOnly = { id -> LauncherModes.load().firstOrNull { it.id == id }?.engine?.isNotEmpty() == true },
            audit = { BgAudit.append(app, it) },
        )
    }

    private val LOCK = Any()

    private fun <T> locked(ctx: Context, block: () -> T): T = synchronized(LOCK) {
        try { block() } finally { runCatching { SuperSavingRuntime.syncIndicator(ctx.applicationContext) } }
    }

    /** Pick [id]: the previous mode's Off first, then this one. [shell] null = the steps that need ADB are skipped with that reason. */
    fun select(ctx: Context, id: String, shell: BgShell? = BgHost.shell(ctx)): ModeSwitch.Outcome = locked(ctx) { switch(ctx, shell).select(id) }

    fun off(ctx: Context, shell: BgShell? = BgHost.shell(ctx), force: Boolean = false): ModeSwitch.Outcome = locked(ctx) { switch(ctx, shell).off(force) }

    fun retryPending(ctx: Context, id: String, shell: BgShell? = BgHost.shell(ctx)): ModeSwitch.Outcome = locked(ctx) { switch(ctx, shell).retryPending(id) }

    fun forgetPending(ctx: Context, id: String) = locked(ctx) { switch(ctx, null).forgetPending(id) }

    /** Mode id -> the step ids still waiting for their restore. No shell. */
    fun pending(ctx: Context): Map<String, List<String>> {
        val kv = BgPrefsKv(ctx.applicationContext)
        return LauncherModes.load().map { it.id }.associateWith { ModeSwitch.pendingSteps(kv, it) }.filterValues { it.isNotEmpty() }
    }

    fun report(ctx: Context): ModeReport? = ModeSwitch.report(BgPrefsKv(ctx.applicationContext))

    /** The next mode after the active one in declared order (the badge's Change); from none, the first. */
    fun next(ctx: Context): String? {
        val ids = LauncherModes.load().map { it.id }
        if (ids.isEmpty()) return null
        val cur = PresetState.mode(ctx)
        val i = if (cur == null) -1 else ids.indexOf(cur)
        return ids[(i + 1) % ids.size]
    }

    /**
     * Upgrade repair, once per process start and before every operation: a store that says two things (a mode picked while
     * another mode's journal is open, or Focus on in its own old store) is brought back to one. No shell, nothing is undone on
     * the device: the mode that holds the restore journal is the one that is really applied, so it stays the active one.
     */
    fun migrate(ctx: Context): String? = synchronized(LOCK) {
        val app = ctx.applicationContext
        val note = switch(app, null).migrate(legacyFocus = FocusMode.legacyActive(app))
        FocusMode.clearLegacy(app)
        val prefs = LauncherModePrefs(app)
        if (prefs.stateVersion < STATE_VERSION) prefs.stateVersion = STATE_VERSION
        note
    }

    const val STATE_VERSION = 1
}
