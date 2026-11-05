package com.diegonmarcos.superapp.bgpolicy

/**
 * #1506 Background policy: the vocabulary. Pure (no Android types) so the policy state machine, the snapshot round-trip, the
 * allowlist and the no-ADB degradation are unit-tested on a fake shell.
 */

/** The privileged shell as the policy sees it (the SuperApp's embedded ADB / Shizuku channel). */
interface BgShell {
    val channel: String
    /** Output of `sh -c <cmd>`, or null when the channel could not serve it. */
    fun exec(cmd: String): String?
}

/** One installed app as the Android layer lists it. */
data class BgPkg(val pkg: String, val label: String, val system: Boolean)

/** App-ops modes the policy reads back ("default" = no explicit mode, which behaves as allow). */
const val OP_ANY = "RUN_ANY_IN_BACKGROUND"
const val OP_IN = "RUN_IN_BACKGROUND"

/** Standby buckets as `am get-standby-bucket` prints them. */
object Bucket {
    const val EXEMPTED = 5
    const val ACTIVE = 10
    const val WORKING_SET = 20
    const val FREQUENT = 30
    const val RARE = 40
    const val RESTRICTED = 45
    const val NEVER = 50
    const val UNKNOWN = -1

    fun name(b: Int) = when (b) {
        EXEMPTED -> "exempted"; ACTIVE -> "active"; WORKING_SET -> "working set"; FREQUENT -> "frequent"
        RARE -> "rare"; RESTRICTED -> "restricted"; NEVER -> "never"; else -> "?"
    }

    /** `am set-standby-bucket` accepts the name. */
    fun arg(b: Int) = when (b) {
        EXEMPTED -> "exempted"; ACTIVE -> "active"; WORKING_SET -> "working_set"; FREQUENT -> "frequent"
        RARE -> "rare"; RESTRICTED -> "restricted"; else -> "active"
    }
}

/** What the shell says about one package, read back after every action. */
data class BgState(
    val pkg: String,
    val runAny: String,
    val runIn: String,
    val bucket: Int,
    val idleWhitelisted: Boolean,
    val running: Boolean,
) {
    /** Restricted = background work is refused by app-ops (the bucket alone is only a hint). */
    val restricted get() = runAny == "ignore" || runAny == "deny"
    val summary get() = (if (restricted) "restricted" else "allowed") + " · " + Bucket.name(bucket) +
        (if (idleWhitelisted) " · doze-exempt" else "") + (if (running) " · running" else "")
}

/** The state to put back on Restore or Off, captured the first time an app is restricted. */
data class BgPrior(val runAny: String, val runIn: String, val bucket: Int, val idleWhitelisted: Boolean) {
    companion object { fun of(s: BgState) = BgPrior(s.runAny, s.runIn, s.bucket, s.idleWhitelisted) }
}

enum class BgAction { ALLOW, RESTRICT, FORCE_STOP, ENABLE_COMPONENT, DISABLE_COMPONENT, RESTORE }

/** One action's verdict. [skipped] = not attempted (no channel, or the app is essential); [ok] only after the state was read back. */
data class BgOutcome(
    val pkg: String, val action: BgAction, val ok: Boolean, val skipped: Boolean, val note: String, val after: BgState? = null,
) {
    companion object {
        const val NO_ADB = "needs the ADB shell, which is not connected"
        fun noAdb(pkg: String, a: BgAction) = BgOutcome(pkg, a, ok = false, skipped = true, note = NO_ADB)
    }
}

/** The chosen intent per package, persisted: restricted or explicitly allowed. */
enum class BgChoice { RESTRICT, ALLOW }
