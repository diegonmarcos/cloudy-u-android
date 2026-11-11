package com.diegonmarcos.clouddrive

/**
 * #629 WHAT THE FIRST-RUN SEED DID, per repository — the part that was missing and that made a
 * truncated store look like a finished one.
 *
 * The defect this exists for: [StoreSeedWorker] logged a failure with `Log.w` and carried on, and
 * then returned `Result.success()` whatever the tally was. WorkManager's execution window is ten
 * minutes; twelve shallow clones do not fit it, so the worker was stopped mid-clone, every
 * remaining repository failed instantly against the torn-down thread, and the unique work ended
 * TERMINALLY GREEN with nine of twelve repositories present — the exact measured state of the
 * owner's device, missing the alphabetical tail.
 *
 * So the pass is now a REPORT: one [SeedOutcome] per DECLARED repository (no repository is ever
 * absent from it — a silent skip is impossible by construction), [complete] is the honest verdict,
 * and the worker turns "not complete" into `Result.retry()` so the tail is resumed instead of
 * being lost. Pure Kotlin, no Android: SeedReportTest runs it on the JVM.
 */
data class SeedOutcome(val name: String, val kind: String, val detail: String) {
    /** The one line the log and the persisted report both carry. */
    fun line(): String = "$kind $name${if (detail.isBlank()) "" else " — $detail"}"

    companion object {
        /** Already cloned before this pass — nothing to do, and not a failure. */
        const val PRESENT = "present"
        /** Cloned by this pass. */
        const val SEEDED = "seeded"
        /** The clone was attempted and failed. The pass is incomplete and must be retried. */
        const val FAILED = "failed"
        /** Not attempted because the worker was stopped. The pass is incomplete and must be retried. */
        const val DEFERRED = "deferred"
        /** The manifest names it but declares no upstream to clone from. Retrying cannot help. */
        const val UNDECLARED = "undeclared"

        /**
         * #683 The provider demanded a credential and this device holds none the declared chain
         * (vault import first, then `auth.git_chain`) can answer with. TERMINAL, like
         * [UNDECLARED]: a retry cannot mint a credential, so looping on it would burn battery
         * and quota against a clone that can never succeed. Distinct from [FAILED] because the
         * way out is different — not another pass, but the vault import or a fleet sign-in.
         */
        const val NEEDS_CREDENTIAL = "needs-credential"

        /**
         * #730 This app does not hold all-files access yet, so NOTHING could be written to the
         * store. The fresh-phone state, read from the code: the seed is scheduled in the same onCreate
         * that sends the user to the grant screen, so its first pass runs before the toggle is
         * flipped and every clone failed with a bare I/O error. RESUMABLE (granting it turns the
         * next pass into clones) and named for its way out, never a per-repository "failed".
         */
        const val NEEDS_STORAGE = "needs-storage"

        /**
         * The shared store itself did not answer (cld.termux absent, CLOUD_EXEC unbound, the folder
         * not creatable), so no clone was tried. Resumable but BOUNDED by [SeedPolicy]: it used to be
         * retried without limit, with no word to the owner about the store being the cause.
         */
        const val NEEDS_STORE = "needs-store"

        /**
         * #1643 Declared, absent, and deliberately NOT cloned: the pass was automatic (app start,
         * update, first run). Terminal, never retried; the user clones it on purpose.
         */
        const val NOT_CLONED = "not-cloned"

        /** The kinds a repository can carry and still leave the store finished. */
        val SETTLED = setOf(PRESENT, SEEDED, UNDECLARED, NOT_CLONED)
        /** The kinds another pass could still turn into a clone. */
        val RESUMABLE = setOf(FAILED, DEFERRED, NEEDS_STORAGE, NEEDS_STORE)
        /** #683 Terminal but NOT finished: no retry can help, and the store still lacks the repository. */
        val BLOCKED = setOf(NEEDS_CREDENTIAL)
    }
}

/** [declared] is the size of the declared seed set, so a missing outcome is itself a failure. */
data class SeedReport(val declared: Int, val outcomes: List<SeedOutcome>) {

    val present: List<SeedOutcome> get() = outcomes.filter { it.kind in SeedOutcome.SETTLED && it.kind != SeedOutcome.NOT_CLONED }
    val resumable: List<SeedOutcome> get() = outcomes.filter { it.kind in SeedOutcome.RESUMABLE }
    /** #683 the repositories no pass can reach without a credential this device does not hold. */
    val blocked: List<SeedOutcome> get() = outcomes.filter { it.kind in SeedOutcome.BLOCKED }

    /**
     * The pass is TERMINAL when EVERY declared repository produced an outcome and none of them is
     * resumable — this is what drives retry-vs-success, and counting only the successes (or only
     * the absence of exceptions) is how a nine-of-twelve store reported itself green. #683 a
     * [blocked] repository is deliberately terminal too: a retry cannot mint the credential it
     * lacks, so the worker must not loop on it — but the TALLY still refuses to call such a
     * store "complete", because it is not.
     */
    val complete: Boolean get() = outcomes.size == declared && resumable.isEmpty()

    /** The one-line verdict the tail of the report carries. */
    fun tally(): String = "seed ${present.size}/$declared " + when {
        !complete -> "INCOMPLETE, will retry: " + resumable.joinToString { it.name }
        blocked.isNotEmpty() -> "settled; needs a credential (no retry — the vault import or a fleet sign-in delivers one): " + blocked.joinToString { it.name }
        outcomes.any { it.kind == SeedOutcome.NOT_CLONED } -> "not cloned automatically: " + outcomes.filter { it.kind == SeedOutcome.NOT_CLONED }.joinToString { it.name } + " (clone them from the Git page)"
        else -> "complete"
    }

    /** One line per repository, then the tally — nothing aggregated away. */
    fun lines(): List<String> = outcomes.map { it.line() } + tally()

    fun text(): String = lines().joinToString("\n")

    companion object {
        /**
         * #730 the gate a fresh phone hits first: without all-files access the pass attempts
         * nothing and reports EVERY declared repository as [SeedOutcome.NEEDS_STORAGE] (an
         * incomplete pass, so the worker retries); with it, null — the clone loop runs.
         */
        fun withoutStorage(names: List<String>, hasStorage: Boolean): SeedReport? =
            if (hasStorage) null
            else SeedReport(names.size, names.map { SeedOutcome(it, SeedOutcome.NEEDS_STORAGE, "Cloud Drive holds no all-files access yet; granting it resumes the seed") })

        /** The store did not verify: every declared repository carries the one actionable [why]. */
        fun withoutStore(names: List<String>, why: String): SeedReport =
            SeedReport(names.size, names.map { SeedOutcome(it, SeedOutcome.NEEDS_STORE, why) })

        /** #730 what the Git page shows BEFORE any pass ran: the seed is queued, and on what it waits. */
        fun pending(declared: Int, unmetered: Boolean): String =
            "seed 0/$declared pending: queued, waiting for " + (if (unmetered) "an unmetered (Wi-Fi) network" else "a network") + " and all-files access"
    }
}

/**
 * The seed worker's retry budget, pure so the JVM suite can run the loop it guards. An incomplete
 * pass used to return `Result.retry()` with no limit: a store that never became reachable (or a
 * clone that could never succeed) re-ran for ever and the page said "will retry" for ever. Now
 * the pass number decides: retry up to [MAX_PASSES], then stop with a report that names the
 * repositories and the way out. A later app launch or the page's Retry starts a fresh budget.
 */
object SeedPolicy {
    const val MAX_PASSES = 6

    enum class Next { DONE, RETRY, GIVE_UP }

    /** [pass] is 1 for the first run of the work. */
    fun decide(report: SeedReport, pass: Int): Next = when {
        report.complete -> Next.DONE
        pass >= MAX_PASSES -> Next.GIVE_UP
        else -> Next.RETRY
    }

    /** The persisted report once the budget is spent; its last line is what the Git page shows. */
    fun stopped(report: SeedReport, pass: Int): String {
        val tally = "seed ${report.present.size}/${report.declared} STOPPED after $pass passes, not retried again: " +
            report.resumable.joinToString { it.name } + " (fix the cause above, then press Retry)"
        return (report.outcomes.map { it.line() } + tally).joinToString("\n")
    }
}

/**
 * #1643 WHO MAY CLONE. A clone is made only on an explicit user action; app start, an update, a
 * first run, the all-files grant and WorkManager retries of those never clone. Pure so the JVM
 * suite proves it.
 */
object CloneTrigger {
    const val EXPLICIT_KEY = "explicit_user_clone"
    fun mayClone(explicit: Boolean): Boolean = explicit
}
