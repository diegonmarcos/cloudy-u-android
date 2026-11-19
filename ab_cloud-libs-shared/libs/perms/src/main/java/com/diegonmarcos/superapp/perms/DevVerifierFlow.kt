package com.diegonmarcos.superapp.perms

/**
 * #1822 the Advanced Flow of "Apps from unverified developers" as a pure state machine (DevVerifierFlowTest). Every step comes
 * from a measurement ([DevVerifier.Reading] from the shell checker, Developer options on/off, boot id, uptime), never from a
 * button press. The only things remembered are facts the checker cannot give back later: the boot the request was seen in, and the
 * start of the 24 h window (the boot time of the first boot after the request). A step that went backwards (the allowance was
 * reset, the request vanished before the window ended) returns the flow to Request.
 */
object DevVerifierFlow {
    const val WINDOW_MS = 24L * 60 * 60 * 1000

    enum class Step { NOT_NEEDED, DEV_OPTIONS, REQUEST, RESTART, WAITING, FINISH, DONE, TEMP }

    /** What is remembered between probes. [bootAtRequest] = boot id when the pending request was first seen. */
    data class Saved(
        val bootAtRequest: String = "", val windowStart: Long = 0L, val wasAllowed: Boolean = false,
        val restartPrompted: Boolean = false,
    )

    /** One measurement round. null = could not be read. */
    data class Facts(val reading: DevVerifier.Reading, val devOptionsOn: Boolean?, val bootId: String?, val uptimeMs: Long?)

    data class Result(val step: Step, val saved: Saved, val end: Long = 0L, val expiry: Long = 0L, val note: String = "", val reading: DevVerifier.Reading, val reset: Boolean = false)

    fun advance(f: Facts, saved: Saved, now: Long): Result {
        val r = f.reading
        fun res(step: Step, s: Saved = saved, end: Long = 0L, expiry: Long = 0L, note: String = "", reset: Boolean = false) = Result(step, s, end, expiry, note, r, reset)
        val fresh = Saved(wasAllowed = saved.wasAllowed)
        return when (r.state) {
            DevVerifier.State.NOT_PRESENT, DevVerifier.State.DISABLED -> res(Step.NOT_NEEDED, Saved())
            DevVerifier.State.ALLOWED_INDEFINITELY -> res(Step.DONE, Saved(wasAllowed = true))
            // a 7-day allowance is not the goal: not done, it shows its expiry and sends the owner back to choose Indefinitely
            DevVerifier.State.ALLOWED_UNTIL -> res(Step.TEMP, Saved(), expiry = r.until)
            DevVerifier.State.PENDING_24H -> {
                val boot = f.bootId.orEmpty()
                val first = saved.bootAtRequest.isEmpty()
                val s0 = if (first) saved.copy(bootAtRequest = boot) else saved
                if (s0.windowStart == 0L && (boot.isEmpty() || boot == s0.bootAtRequest)) res(Step.RESTART, s0)
                else {
                    val start = s0.windowStart.takeIf { it > 0 } ?: (now - (f.uptimeMs ?: 0L))
                    val end = if (r.until > 0) r.until else start + WINDOW_MS
                    val s1 = s0.copy(windowStart = start)
                    if (now < end) res(Step.WAITING, s1, end = end) else res(Step.FINISH, s1, end = end)
                }
            }
            DevVerifier.State.NOT_ALLOWED -> {
                val end = saved.windowStart.takeIf { it > 0 }?.let { it + WINDOW_MS } ?: 0L
                when {
                    end > 0 && now >= end -> res(Step.FINISH, saved, end = end)
                    saved.wasAllowed -> res(requestStep(f), Saved(), note = "The allowance was reset (for example by a Play Services or system update): starting again. ", reset = true)
                    end > 0 -> res(requestStep(f), fresh, note = "The request is gone before the wait ended: starting again. ")
                    else -> res(requestStep(f), fresh)
                }
            }
            DevVerifier.State.UNKNOWN -> {
                val end = saved.windowStart.takeIf { it > 0 }?.let { it + WINDOW_MS } ?: 0L
                when {
                    end > 0 -> res(if (now < end) Step.WAITING else Step.FINISH, saved, end = end)   // unreadable now: keep the remembered window, never reset on silence
                    saved.bootAtRequest.isNotEmpty() -> res(Step.RESTART)
                    else -> res(requestStep(f))
                }
            }
        }
    }

    private fun requestStep(f: Facts) = if (f.devOptionsOn == false) Step.DEV_OPTIONS else Step.REQUEST

    /** The shell line for [step]: About phone while Developer options are off, else the Developer options page. */
    fun openCmd(step: Step): String =
        if (step == Step.DEV_OPTIONS) "cmd activity start-activity -a android.settings.DEVICE_INFO_SETTINGS" else DevVerifier.OPEN_CMD

    private fun at(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(ms))

    fun remaining(ms: Long): String {
        val m = (ms.coerceAtLeast(0) + 59_999) / 60_000
        return if (m <= 0) "less than a minute" else "${m / 60} h ${"%02d".format(m % 60)} min"
    }

    private const val FALLBACK = "adb installs of fleet APKs keep working meanwhile."

    /** The row text for a step at [now]; the countdown is computed here, so a redraw is all the page needs. */
    fun text(r: Result, now: Long): String = r.note + when (r.step) {
        Step.NOT_NEEDED -> DevVerifier.status(r.reading).reason
        Step.DEV_OPTIONS -> "Step 1 of 4, Request: Developer options are off. Open About phone > Software information and tap Build number 7 times. $FALLBACK"
        Step.REQUEST -> "Step 1 of 4, Request: open Developer options > Apps from unverified developers, tap Allow and confirm with your PIN. $FALLBACK" +
            if (r.reading.state == DevVerifier.State.UNKNOWN) " (State not readable yet: ${r.reading.detail})" else ""
        Step.RESTART -> "Step 2 of 4, Restart: the request is registered. Restart the phone now; the 24 h wait starts after the boot. $FALLBACK"
        Step.WAITING -> "Step 3 of 4, Waiting: ${remaining(r.end - now)} left, ends ${at(r.end)}. $FALLBACK"
        Step.FINISH -> "Step 4 of 4, Finish: the 24 h wait is over. Open Developer options > Apps from unverified developers and choose Indefinitely (only permanent counts)."
        Step.DONE -> "Step 4 of 4, done: allowed indefinitely."
        Step.TEMP -> "Step 4 of 4, Finish: allowed only until ${at(r.expiry)}, which is not permanent. Open Developer options > Apps from unverified developers and choose Indefinitely."
    }

    /** The Perms row's state: GRANTED only for allowed indefinitely (a 7-day allowance is DENIED); Waiting / Restart and an unreadable Request = UNKNOWN (nothing to ask yet); the rest = DENIED. */
    fun status(r: Result, now: Long): PermStatus {
        val reason = text(r, now)
        return when (r.step) {
            Step.NOT_NEEDED -> DevVerifier.status(r.reading)
            Step.DONE -> PermStatus(PermState.GRANTED, reason)
            Step.WAITING, Step.RESTART -> PermStatus(PermState.UNKNOWN, reason)
            Step.REQUEST -> PermStatus(if (r.reading.state == DevVerifier.State.UNKNOWN) PermState.UNKNOWN else PermState.DENIED, reason)
            Step.DEV_OPTIONS, Step.FINISH, Step.TEMP -> PermStatus(PermState.DENIED, reason)
        }
    }

    enum class Kind { RESTART_PROMPT, WINDOW_END, RESET }

    /** A notification due at [at] (epoch ms). */
    data class Alarm(val kind: Kind, val at: Long)

    /** The notifications this result needs scheduled; any kind not listed must be cancelled. Pure. */
    fun alarms(r: Result, now: Long): List<Alarm> = when (r.step) {
        Step.WAITING -> listOf(Alarm(Kind.WINDOW_END, r.end))
        else -> emptyList()
    }

    /** True once per request: the Restart step prompts the reboot right away (an immediate notification, not an alarm). */
    fun shouldPromptRestart(r: Result): Boolean = r.step == Step.RESTART && !r.saved.restartPrompted

    /** The immediate notifications a fresh measurement owes: the reboot prompt (once) and the reset alert (the flow restarted behind the owner's back). */
    fun immediate(r: Result): List<Kind> = buildList { if (r.reset) add(Kind.RESET); if (shouldPromptRestart(r)) add(Kind.RESTART_PROMPT) }
}
