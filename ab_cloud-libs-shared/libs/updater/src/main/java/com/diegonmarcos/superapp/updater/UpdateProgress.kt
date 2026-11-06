package com.diegonmarcos.superapp.updater

import android.content.Context

/**
 * Process-wide observable state for the in-app updater. The
 * download/install pipeline writes states here as it advances;
 * the UI shell observes via [setListener] and shows / dismisses
 * an overlay accordingly.
 *
 * Kept as a plain singleton + callback so the updater module
 * doesn't pull in Coroutines / LiveData. The single subscriber is
 * always the main Activity.
 */
object UpdateProgress {

    sealed class State {
        object Idle : State()
        /** Pulling the manifest from GHCR (small, near-instant). */
        object CheckingManifest : State()
        /** An update exists but we're on a metered network, so auto-download was
         *  held back. The overlay prompts the user (Update now / Later) — tapping
         *  Update now calls Updater.downloadNow to fetch over data with consent. */
        data class UpdateAvailable(val totalBytes: Long) : State()
        /**
         * Streaming the APK blob.
         *
         * [bytes] is what has actually been WRITTEN TO DISK, so it survives a
         * resume: a transfer that restarts at 180 MB reports 180 MB, not 0.
         * [total] is -1 (or 0) when the server declined to declare a length —
         * renderers MUST show that as "size unknown" on an indeterminate bar
         * and never as a percentage, because a hard 0% is indistinguishable
         * from stuck and that is precisely the confusion this state caused.
         * [percent] is 0..100, and meaningless whenever [total] is not > 0.
         */
        data class Downloading(val percent: Int, val bytes: Long, val total: Long) : State()
        /**
         * There is work to do and a CONSTRAINT is holding it, not a network
         * that is failing.
         *
         * Task #46 requires that an automatic pass never download over mobile
         * data. Honouring that used to be a log line and an early return, so a
         * pass parked on "waiting for Wi-Fi" was observationally identical to a
         * download that had silently died — same absence of progress, same
         * absence of an error. A withheld download is a state, and the user is
         * entitled to see which one they are in.
         */
        data class Waiting(val reason: String) : State()
        /** APK is on disk; PackageInstaller session in progress. */
        object Installing : State()
        /** Install handed off — system dialog is up OR install completed. */
        object Done : State()
        /**
         * [appId]/[pkg]/[apkPath] are optional context for the Diagnose screen:
         * a fleet install knows which app it was and which file it staged, and
         * without that the report can only describe the host app. Defaulted so
         * every existing `Failed("...")` call site keeps compiling and simply
         * produces a report about this app instead of the target.
         */
        data class Failed(
            val message: String,
            val appId: String = "",
            val pkg: String = "",
            val apkPath: String = "",
            /** #785 the stage it stopped at ([STAGE_DOWNLOADING]…) and the app's
             *  name, so a failure the Store bar draws later — after the job that
             *  raised it has ended — still says WHAT failed WHERE. */
            val stage: String = "",
            val app: String = "",
        ) : State()
        /** User hit Cancel — overlay dismisses, worker is being torn down. */
        object Cancelled : State()
    }

    @Volatile var state: State = State.Idle
        private set

    /**
     * Process-wide cancel flag polled by every download loop — the WorkManager
     * self-update worker AND the raw fleet-install threads (ConstellationFragment
     * uses plain `thread {}`, which WorkManager.cancel can't reach). Armed by the
     * Cancel button via [requestCancel]; stays armed until the next download
     * session clears it with [beginDownload]. That "sticky until next session"
     * shape avoids a race where the overlay's reset clears the flag before a
     * background thread has polled it.
     */
    @Volatile var cancelRequested: Boolean = false
        private set

    /** Cancel button → arm cancellation for any in-flight download loop. */
    fun requestCancel() { cancelRequested = true; stoppedBySystem = false }

    /**
     * #1551 Android stopped the background job (dataSync timeout, a lost constraint, the worker's own stop): the same cooperative
     * stop as [requestCancel], but NOT the user's — the queue reports it as paused and schedules its own resume.
     */
    @Volatile var stoppedBySystem: Boolean = false
        private set

    fun requestSystemStop() { cancelRequested = true; stoppedBySystem = true }

    /**
     * Start of a fresh user-initiated download session — disarm any stale
     * cancel, and un-minimize: the user asking for an update again is the
     * clearest possible request to see it.
     */
    fun beginDownload() { cancelRequested = false; stoppedBySystem = false; minimized = false }

    /**
     * Manual pass only: the user hit Minimize and wants the screen back.
     *
     * A latch rather than a one-off fragment removal, because the host
     * re-attaches the overlay on the very next progress tick — removing the
     * view alone would put it straight back a fraction of a second later.
     * It suppresses the UI and NOTHING ELSE: no cancel, no pause, the pass
     * never learns this happened. Cleared by [restore], by [beginDownload]
     * and by [reset], so it can never outlive the pass that set it.
     */
    @Volatile var minimized: Boolean = false
        private set

    fun minimize() { minimized = true }

    /**
     * Bring a minimized overlay back showing LIVE state — the replay below
     * reads the current [state] field, so what returns is where the pass is
     * now, not a snapshot from when it was minimized.
     */
    fun restore() {
        minimized = false
        if (quiet) return
        listener?.invoke(state)
        observers.toList().forEach { it(state) }
    }

    /**
     * True when [state] must not reach the screen.
     *
     * Two independent reasons, one question, asked at the point that actually
     * draws: an unattended pass is in flight (nobody asked to watch), or the
     * user minimized a manual one (they asked NOT to watch).
     *
     * The unattended half reads the PERSISTED flag rather than [quiet].
     * [quiet] is a plain field, so it is only observable inside the process
     * that set it; the overlay is attached by an Activity that is a different
     * process for every satellite app, and a field gate there is not a gate at
     * all — it silently does nothing, which is exactly how this bug survived
     * being "fixed" more than once.
     *
     * [State.Failed] is deliberately never suppressed. Suppress progress,
     * never errors — silence about work that did not happen is hiding, not
     * quietness. [NotificationStore] push stays unconditional either way.
     */
    fun suppressed(ctx: Context, state: State): Boolean = when (state) {
        // Suppress progress, never errors.
        is State.Failed -> false
        // A deferral is page-level information, never a full-screen
        // interruption: nothing is running, nothing is wrong, and the only
        // useful response is one the user takes when they next go looking. The
        // Constellation page's inline row shows it; the overlay must not.
        //
        // Suppressed UNCONDITIONALLY rather than via unattendedPass, because a
        // worker publishes this BEFORE it raises the unattended flag — the flag
        // is set inside the pass it decided not to run. Reading it here would
        // have every deferred wake-up throw an overlay over whatever the user
        // was doing, which is the exact failure the flag exists to prevent.
        is State.Waiting -> true
        else -> minimized || AutoUpdatePrefs.unattendedPass(ctx)
    }

    /**
     * During a multi-app "Update all", names the current app + position
     * ("Chat · 2/5") so the overlay shows a stable header instead of a bar that
     * silently resets 0→100 per app — the "messy scrambling progress" bug.
     * null for single installs (nothing extra rendered).
     */
    @Volatile var batchLabel: String? = null
        private set

    /**
     * Unattended passes set this for their duration.
     *
     * "Silent" means NO VISIBLE PROGRESS: no overlay, no bar, no dialog. It
     * does NOT mean quiet — the pipeline keeps writing [state] and keeps
     * logging every event to logcat exactly as before, so a silent pass is
     * still fully readable with `logcat -s Fleet`. Only the listener (the
     * Activity's overlay) is left undriven, because an auto-update that pops a
     * progress bar over whatever the user is doing is not unattended.
     */
    @Volatile var quiet: Boolean = false

    /** Set before each app in a batch; total==1 ⇒ no prefix (single install). */
    fun beginBatch(label: String, index: Int, total: Int) {
        if (quiet) return
        batchLabel = if (total > 1) "$label · $index/$total" else null
    }

    /** Also ends the [job]: every batch, quiet or not, ends here. */
    fun endBatch() { job = null; if (quiet) return; batchLabel = null }

    // ── #785 WHICH app, WHICH stage ─────────────────────────────────────────

    const val STAGE_DOWNLOADING = "downloading"
    const val STAGE_VERIFYING = "verifying"
    const val STAGE_INSTALLING = "installing"
    const val STAGE_CLEARING = "clearing"

    /**
     * What [state] is ABOUT. The Store's bar under its buttons drew
     * "Downloading 42%" with no app, no stage and no position, so in a 12-app
     * Update all nobody could tell which app was moving, what came next, or
     * which one had failed. The pipeline doing the work names it here and
     * StoreStages.progress turns state + job into the one line the bar and
     * `/api/store/progress` both show.
     *
     * [stage] is a STAGE_* word; [index]/[total] is the batch position (1/1 for
     * one row); [next] is the label of the app after this one, null at the end.
     * NOT gated by [quiet]: the overlay never reads it, and an inline observer
     * is a screen the user opened to watch exactly this.
     */
    data class Job(
        val appId: String,
        val pkg: String,
        val app: String,
        val stage: String,
        val version: String = "",
        val index: Int = 1,
        val total: Int = 1,
        val next: String? = null,
    )

    @Volatile var job: Job? = null
        private set

    fun beginJob(j: Job) {
        tkey.get()?.let { k -> sink?.onJob(k, j) }
        job = j; republish()
    }

    // ── per-job routing ────────────────────────────────────────────────────
    // The Store runs several jobs at once (parallel downloads, an install while
    // others download), so a state with no owner is a state every row fights over.
    // A thread that runs a job declares its key; whatever the pipeline publishes
    // on that thread is ALSO delivered to the [sink] under that key alone. The
    // process-wide [state] and [job] keep following too (last writer wins), for the shell
    // overlay and /api/store/progress; the Store page reads the keyed board instead.

    /** Receives each keyed job's own events. */
    interface JobSink {
        fun onState(key: String, state: State)
        fun onStage(key: String, stage: String)
        fun onJob(key: String, job: Job?)
    }

    @Volatile var sink: JobSink? = null
    private val tkey = ThreadLocal<String?>()

    /** Run [f] as the job [key]: every update, stage and job write on this thread is that job's. */
    fun <T> withKey(key: String, f: () -> T): T {
        val prev = tkey.get()
        tkey.set(key)
        try { return f() } finally { tkey.set(prev) }
    }

    /** The running job moves to [stage]. No job → no-op: the host's own
     *  self-update has no Store row to name. */
    fun stage(stage: String) {
        tkey.get()?.let { k -> sink?.onStage(k, stage) }
        val j = job ?: return
        if (j.stage != stage) { job = j.copy(stage = stage); republish() }
    }

    fun endJob() {
        tkey.get()?.let { k -> sink?.onJob(k, null) }
        job = null; republish()
    }

    /** A job change with no state change still has to reach the inline rows —
     *  and so does a #804 auto-chain phase change (StoreAuto). */
    fun republish() = observers.toList().forEach { it(state) }

    private var listener: ((State) -> Unit)? = null

    /**
     * Extra observers, on top of the shell's single [listener] slot.
     *
     * A screen that wants to show progress inline — the Constellation page's row
     * under its buttons — cannot use [setListener]: that slot belongs to the
     * Activity's overlay, and taking it would silently switch the overlay off for
     * as long as the screen is open. These stack instead, so nothing displaces
     * anything. Same [quiet] and [suppressed] rules apply; the caller decides.
     */
    private val observers = mutableListOf<(State) -> Unit>()

    fun addObserver(observer: (State) -> Unit) {
        observers.add(observer)
        // Replay unconditionally, for the same reason update() drives observers
        // during a quiet pass: a page opened WHILE a background pass is running
        // must show where that pass is, not a blank row.
        observer(state)
    }

    fun removeObserver(observer: (State) -> Unit) { observers.remove(observer) }

    fun setListener(l: ((State) -> Unit)?) {
        listener = l
        // Replay current state so a late subscriber catches up — but honour
        // [quiet], exactly as update() does.
        //
        // THIS WAS THE HOLE. update() has always refused to drive the overlay
        // during an unattended pass, so the pass itself was silent; but the
        // replay below was not gated, and an Activity re-subscribes on every
        // onResume. So a background pass stayed invisible right up until the
        // user opened the app for any reason, at which point the current
        // non-Idle state was replayed straight into a full-screen overlay —
        // once per return to the app, for work nobody asked to watch. The
        // gate has to be on BOTH paths or it is not a gate.
        if (l != null && !quiet) l(state)
    }

    /**
     * QUIET SILENCES THE OVERLAY, NOT THE PAGE.
     *
     * [quiet] means an unattended pass is running and must not throw a
     * full-screen overlay over whatever the user is doing — that is the
     * [listener] slot, and it stays undriven, exactly as the doc above has
     * always claimed. It was ALSO skipping [observers], which is a different
     * thing entirely: an observer is an inline row on a screen the user
     * deliberately opened to watch this. Suppressing that meant the
     * Constellation page showed nothing at all while a background pass
     * downloaded 265 MB, or while one sat parked on [State.Waiting] — the
     * "nothing is happening" reading of work that was, in fact, happening.
     */
    fun update(next: State) {
        tkey.get()?.let { k -> sink?.onState(k, next) }
        state = next
        if (!quiet) listener?.invoke(next)
        observers.toList().forEach { it(next) }
    }

    fun reset() { batchLabel = null; job = null; minimized = false; update(State.Idle) }
}
