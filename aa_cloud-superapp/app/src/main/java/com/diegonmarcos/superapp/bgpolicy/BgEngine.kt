package com.diegonmarcos.superapp.bgpolicy

/**
 * #1506 The Background policy engine: every action runs through the privileged shell and is VERIFIED by reading the state back
 * (an action that the device did not take reports a mismatch, never ok). Without a shell every action is skipped with a note
 * (nothing is written, nothing half-applies). The essential allowlist is checked here, in the one place every caller (the
 * per-app buttons, the bulk action, the post-update re-apply, Super Saving, the c3 tool) goes through.
 */
class BgEngine(
    private val shell: BgShell?,
    private val essential: BgEssential,
    private val store: BgStore,
    private val audit: (String) -> Unit = {},
    /** The app-side read of a component's enabled flag (true = enabled); the read-back for pm enable/disable. */
    private val componentEnabled: (String, String) -> Boolean? = { _, _ -> null },
) {
    val available get() = shell != null

    private fun run(cmd: String): String? = runCatching { shell?.exec(cmd) }.getOrNull()

    private fun say(o: BgOutcome): BgOutcome { audit("bg-policy ${o.action.name.lowercase()} ${o.pkg}: ${if (o.ok) "ok" else if (o.skipped) "skipped" else "FAILED"} ${o.note}".trim()); return o }

    // ── reads ──
    /** The state of every package, fleet first then the rest (alphabetical inside each). Null without a shell. */
    fun readAll(pkgs: List<BgPkg>, cfg: BgConfig): List<Pair<BgPkg, BgState>>? {
        val g = BgCmd.parseGlobal(run(BgCmd.READ_GLOBAL) ?: return null)
        val per = HashMap<String, Triple<String, String, String>>()
        for (chunk in pkgs.map { it.pkg }.chunked(BgCmd.CHUNK)) per += BgCmd.parsePkgs(run(BgCmd.readPkgs(chunk)) ?: continue)
        val rows = pkgs.mapNotNull { p -> per[p.pkg]?.let { p to stateOf(p.pkg, it, g) } }
        return rows.sortedWith(compareBy({ !cfg.isFleet(it.first.pkg) }, { it.first.label.lowercase() }))
    }

    /** One package's state (the read-back after an action). Null when the shell cannot be reached or read. */
    fun read(pkg: String): BgState? {
        val g = BgCmd.parseGlobal(run(BgCmd.READ_GLOBAL) ?: return null)
        val t = BgCmd.parsePkgs(run(BgCmd.readPkgs(listOf(pkg))) ?: return null)[pkg] ?: return null
        return stateOf(pkg, t, g)
    }

    private fun stateOf(pkg: String, t: Triple<String, String, String>, g: BgCmd.Global) =
        BgState(pkg, BgCmd.opMode(t.first, OP_ANY), BgCmd.opMode(t.second, OP_IN), BgCmd.bucketOf(t.third), pkg in g.idle, g.running(pkg))

    // ── per-app actions ──
    fun allow(pkg: String): BgOutcome {
        if (!available) return say(BgOutcome.noAdb(pkg, BgAction.ALLOW))
        run(BgCmd.allow(pkg))
        val s = read(pkg) ?: return say(BgOutcome(pkg, BgAction.ALLOW, false, false, "could not read the state back"))
        val bad = ArrayList<String>()
        if (s.runAny == "ignore" || s.runAny == "deny") bad += "background op is ${s.runAny}"
        if (s.runIn == "ignore" || s.runIn == "deny") bad += "run-in-background op is ${s.runIn}"
        if (s.bucket != Bucket.ACTIVE && s.bucket != Bucket.EXEMPTED) bad += "bucket is ${Bucket.name(s.bucket)}"
        if (bad.isEmpty()) store.setChoice(pkg, BgChoice.ALLOW)
        return say(BgOutcome(pkg, BgAction.ALLOW, bad.isEmpty(), false, bad.joinToString("; "), s))
    }

    fun restrict(pkg: String): BgOutcome {
        if (essential.isEssential(pkg)) return say(BgOutcome(pkg, BgAction.RESTRICT, false, true, "essential app, never restricted"))
        if (!available) return say(BgOutcome.noAdb(pkg, BgAction.RESTRICT))
        // The prior state is what Restore brings back, so without a readable one nothing is changed.
        val before = read(pkg) ?: return say(BgOutcome(pkg, BgAction.RESTRICT, false, false, "could not read the prior state, nothing changed"))
        store.capture(pkg, BgPrior.of(before))
        run(BgCmd.restrict(pkg))
        val s = read(pkg) ?: return say(BgOutcome(pkg, BgAction.RESTRICT, false, false, "could not read the state back"))
        val bad = ArrayList<String>()
        if (s.runAny != "ignore") bad += "background op is ${s.runAny}"
        if (s.runIn != "ignore") bad += "run-in-background op is ${s.runIn}"
        if (s.bucket != Bucket.RESTRICTED) bad += "bucket is ${Bucket.name(s.bucket)}"
        if (s.idleWhitelisted) bad += "still doze-exempt"
        if (bad.isEmpty()) store.setChoice(pkg, BgChoice.RESTRICT)
        return say(BgOutcome(pkg, BgAction.RESTRICT, bad.isEmpty(), false, bad.joinToString("; "), s))
    }

    fun forceStop(pkg: String): BgOutcome {
        if (essential.isEssential(pkg)) return say(BgOutcome(pkg, BgAction.FORCE_STOP, false, true, "essential app, never stopped"))
        if (!available) return say(BgOutcome.noAdb(pkg, BgAction.FORCE_STOP))
        run(BgCmd.forceStop(pkg))
        val s = read(pkg) ?: return say(BgOutcome(pkg, BgAction.FORCE_STOP, false, false, "could not read the state back"))
        return say(BgOutcome(pkg, BgAction.FORCE_STOP, !s.running, false, if (s.running) "a process is still running" else "", s))
    }

    /** Disable or enable one receiver or service component; the verdict is the component's flag read back. */
    fun component(pkg: String, comp: String, enable: Boolean): BgOutcome {
        val a = if (enable) BgAction.ENABLE_COMPONENT else BgAction.DISABLE_COMPONENT
        if (!enable && essential.isEssential(pkg)) return say(BgOutcome(pkg, a, false, true, "essential app, its components stay enabled"))
        if (!available) return say(BgOutcome.noAdb(pkg, a))
        run(BgCmd.component(pkg, comp, enable))
        val now = componentEnabled(pkg, comp)
        return when {
            now == null -> say(BgOutcome(pkg, a, false, false, "could not read the component back"))
            now == enable -> say(BgOutcome(pkg, a, true, false, comp.substringAfterLast('.')))
            else -> say(BgOutcome(pkg, a, false, false, "$comp is still ${if (now) "enabled" else "disabled"}"))
        }
    }

    // ── bulk ──
    /** Restrict every non-essential package (fleet included). Essential ones are listed as skipped, never touched. */
    fun restrictAll(pkgs: List<BgPkg>, cfg: BgConfig): List<BgOutcome> =
        pkgs.filter { cfg.bulkIncludesSystem || !it.system }.map { restrict(it.pkg) }

    /** Put every captured prior back (except [skip]), then forget the choice. Exact: the op modes, the bucket and the doze whitelist as they were. */
    fun restore(skip: Set<String> = emptySet()): List<BgOutcome> {
        if (!available) return listOf(say(BgOutcome.noAdb("*", BgAction.RESTORE)))
        return store.priors().filterKeys { it !in skip }.map { (pkg, prior) ->
            run(BgCmd.putBack(pkg, prior))
            val s = read(pkg)
            val ok = s != null && sameMode(s.runAny, prior.runAny) && sameMode(s.runIn, prior.runIn) && s.idleWhitelisted == prior.idleWhitelisted
            if (ok) { store.forgetPrior(pkg); store.setChoice(pkg, null) }
            say(BgOutcome(pkg, BgAction.RESTORE, ok, false, if (ok) "" else "state differs after restore", s))
        }
    }

    /** The packages the persisted policy currently restricts (Super Saving keeps these restricted on Off). */
    fun restrictedChoices(): Set<String> = store.choices().filterValues { it == BgChoice.RESTRICT }.keys

    /** An op with no explicit mode behaves as allow. */
    private fun sameMode(a: String, b: String) = (if (a == "default") "allow" else a) == (if (b == "default") "allow" else b)

    /** After an app update: apply the persisted choice to that one package once. Null when nothing is chosen for it. */
    fun reapply(pkg: String): BgOutcome? = when (store.choice(pkg)) {
        BgChoice.RESTRICT -> restrict(pkg)
        BgChoice.ALLOW -> allow(pkg)
        null -> null
    }
}
