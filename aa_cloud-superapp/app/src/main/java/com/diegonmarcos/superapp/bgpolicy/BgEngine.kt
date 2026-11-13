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

    private fun run(cmd: String): String? = com.diegonmarcos.superapp.xray.EngineProfiler.time("bgpolicy", "shell") { runCatching { shell?.exec(cmd) }.getOrNull() }

    private fun say(o: BgOutcome): BgOutcome { audit("bg-policy ${o.action.name.lowercase()} ${o.pkg}: ${if (o.ok) "ok" else if (o.skipped) "skipped" else "FAILED"} ${o.note}".trim()); return o }

    // ── reads ──
    /** The state of every package, fleet first then the rest (alphabetical inside each). Null without a shell. */
    fun readAll(pkgs: List<BgPkg>, cfg: BgConfig): List<Pair<BgPkg, BgState>>? {
        val g = BgCmd.parseGlobal(run(BgCmd.READ_GLOBAL) ?: return null)
        val per = HashMap<String, Triple<String, String, String>>()
        // #1720 chunked by line length (the broker refuses a line over 2048 characters), not by a fixed count
        for (chunk in BgCmd.readPkgsChunks(pkgs.map { it.pkg })) per += BgCmd.parsePkgs(run(chunk) ?: continue)
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
    fun restrictAll(pkgs: List<BgPkg>, cfg: BgConfig): List<BgOutcome> {
        // #1720 batched: the same verdict per package as [restrict] (prior captured first, state read back after), but the
        // whole list costs a few dozen execs instead of five per package (one device-wide read, then reads and writes packed
        // into lines under the broker's cap).
        val list = pkgs.filter { cfg.bulkIncludesSystem || !it.system }.map { it.pkg }
        val out = HashMap<String, BgOutcome>()
        val todo = ArrayList<String>()
        for (p in list) when {
            essential.isEssential(p) -> out[p] = say(BgOutcome(p, BgAction.RESTRICT, false, true, "essential app, never restricted"))
            !available -> out[p] = say(BgOutcome.noAdb(p, BgAction.RESTRICT))
            else -> todo += p
        }
        if (todo.isNotEmpty()) {
            val before = readMany(todo)
            val writable = todo.filter { before[it] != null }
            // The prior state is what Restore brings back, so without a readable one nothing is changed.
            for (p in todo) if (before[p] == null) out[p] = say(BgOutcome(p, BgAction.RESTRICT, false, false, "could not read the prior state, nothing changed"))
            for (p in writable) store.capture(p, BgPrior.of(before.getValue(p)))
            for (c in BgCmd.batch(writable.map { BgCmd.restrict(it) })) run(c)
            val after = if (writable.isEmpty()) emptyMap() else readMany(writable)
            for (p in writable) {
                val s = after[p]
                if (s == null) { out[p] = say(BgOutcome(p, BgAction.RESTRICT, false, false, "could not read the state back")); continue }
                val bad = ArrayList<String>()
                if (s.runAny != "ignore") bad += "background op is ${s.runAny}"
                if (s.runIn != "ignore") bad += "run-in-background op is ${s.runIn}"
                if (s.bucket != Bucket.RESTRICTED) bad += "bucket is ${Bucket.name(s.bucket)}"
                if (s.idleWhitelisted) bad += "still doze-exempt"
                if (bad.isEmpty()) store.setChoice(p, BgChoice.RESTRICT)
                out[p] = say(BgOutcome(p, BgAction.RESTRICT, bad.isEmpty(), false, bad.joinToString("; "), s))
            }
        }
        return list.mapNotNull { out[it] }
    }

    /** #1720 The state of many packages: one device-wide read, then the per-package reads packed into as few execs as fit. */
    private fun readMany(pkgs: List<String>): Map<String, BgState> {
        val g = BgCmd.parseGlobal(run(BgCmd.READ_GLOBAL) ?: return emptyMap())
        val per = HashMap<String, Triple<String, String, String>>()
        for (chunk in BgCmd.readPkgsChunks(pkgs)) per += BgCmd.parsePkgs(run(chunk) ?: continue)
        return pkgs.mapNotNull { p -> per[p]?.let { p to stateOf(p, it, g) } }.toMap()
    }

    /** #1720 [restore], batched (Super Saving Off): every captured prior put back and read back in a few dozen execs, not three per package. */
    fun restoreBatched(skip: Set<String> = emptySet()): List<BgOutcome> {
        if (!available) return listOf(say(BgOutcome.noAdb("*", BgAction.RESTORE)))
        val priors = store.priors().filterKeys { it !in skip }
        if (priors.isEmpty()) return emptyList()
        for (c in BgCmd.batch(priors.map { (pkg, prior) -> BgCmd.putBack(pkg, prior) })) run(c)
        val after = readMany(priors.keys.toList())
        return priors.map { (pkg, prior) ->
            val s = after[pkg]
            val ok = s != null && sameMode(s.runAny, prior.runAny) && sameMode(s.runIn, prior.runIn) && s.idleWhitelisted == prior.idleWhitelisted
            if (ok) { store.forgetPrior(pkg); store.setChoice(pkg, null) }
            say(BgOutcome(pkg, BgAction.RESTORE, ok, false, if (ok) "" else "state differs after restore", s))
        }
    }

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
