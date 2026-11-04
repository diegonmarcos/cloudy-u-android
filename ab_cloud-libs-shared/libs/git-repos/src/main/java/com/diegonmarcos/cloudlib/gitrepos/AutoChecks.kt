package com.diegonmarcos.cloudlib.gitrepos

/**
 * The auto-behaviours of the table pages (Git, Fsync, Mounts), as pure rules the JVM suite pins. EVERY flag is OFF
 * until the owner turns it on: a status read, a fetch or a size walk costs battery, storage reads and network, so
 * nothing runs by itself unless asked. A page always renders from its cache; the explicit refresh buttons (and these
 * flags, when on) are the only things that refresh it.
 *
 * Persistence: one boolean per (page, flag) in `cloud-drive-prefs` (DrivePrefs), the file Auto Backup already
 * carries, plus one interval per page. [GIT_PULL_KEY] is the very key the older "Auto pull on open" switch wrote.
 */
object AutoChecks {
    enum class Page(val id: String) { GIT("git"), FSYNC("fsync"), MOUNTS("mounts") }

    enum class Flag(val id: String) {
        /** Read every item's status once when the page opens. */
        STATUS_ON_OPEN("status_on_open"),
        /** Git: re-read the local dirty (changed files) fact. */
        DIRTY("dirty"),
        /** Git: re-read the local ahead / behind counts. */
        BEHIND_AHEAD("behind_ahead"),
        /** Git: fetch every clone (network). */
        FETCH("fetch"),
        /** Git: plain pull every clone (network, Wi-Fi only). */
        PULL("pull"),
        /** Git: recompute the size on disk of every clone. */
        SIZE("size"),
        /** Fsync / Mounts: re-read the jobs', pairs', remotes' and mounts' state. */
        STATE("state"),
    }

    /** The flags each page offers, in the order its settings sheet lists them. */
    fun flags(page: Page): List<Flag> = when (page) {
        Page.GIT -> listOf(Flag.STATUS_ON_OPEN, Flag.DIRTY, Flag.BEHIND_AHEAD, Flag.FETCH, Flag.PULL, Flag.SIZE)
        Page.FSYNC, Page.MOUNTS -> listOf(Flag.STATUS_ON_OPEN, Flag.STATE)
    }

    /** The interval choices in minutes; 0 = no periodic check. */
    val INTERVALS: List<Int> = listOf(0, 5, 15, 30, 60)
    const val DEFAULT_INTERVAL = 0

    /** The pref key the older Git "Auto pull on open" switch already used; the Pull flag shares it so there is one store. */
    const val GIT_PULL_KEY = "git.auto_pull_on_open"

    fun key(page: Page, flag: Flag): String = if (page == Page.GIT && flag == Flag.PULL) GIT_PULL_KEY else "auto.${page.id}.${flag.id}"
    fun intervalKey(page: Page): String = "auto.${page.id}.interval_min"

    /** What a page's settings say: the raw prefs map in, typed answers out. Anything absent or of another type is OFF / 0. */
    data class Settings(val page: Page, private val raw: Map<String, Any?> = emptyMap()) {
        fun on(flag: Flag): Boolean = raw[key(page, flag)] == true
        val intervalMinutes: Int get() = (raw[intervalKey(page)] as? Int)?.takeIf { it in INTERVALS } ?: DEFAULT_INTERVAL
        val anyOn: Boolean get() = flags(page).any { on(it) } || intervalMinutes > 0
    }

    /** The background work a trigger asks for. */
    enum class Work { READ_STATUS, FETCH_ALL, PULL_ALL, REFRESH_SIZES, REFRESH_STATE }

    enum class Trigger { OPEN, TICK }

    /**
     * What to run on [trigger] under [s]. Nothing, when nothing is switched on. A TICK only happens when an interval
     * is set (the page schedules it), and never repeats a pull: pull and fetch ride the open and the tick the same way.
     */
    fun plan(s: Settings, trigger: Trigger): Set<Work> {
        if (trigger == Trigger.TICK && s.intervalMinutes <= 0) return emptySet()
        val out = linkedSetOf<Work>()
        val statusOn = (trigger == Trigger.OPEN && s.on(Flag.STATUS_ON_OPEN)) || s.on(Flag.DIRTY) || s.on(Flag.BEHIND_AHEAD)
        if (s.page == Page.GIT) {
            if (statusOn) out += Work.READ_STATUS
            if (s.on(Flag.FETCH)) out += Work.FETCH_ALL
            if (s.on(Flag.PULL) && trigger == Trigger.OPEN) out += Work.PULL_ALL
            if (s.on(Flag.SIZE)) out += Work.REFRESH_SIZES
        } else {
            if ((trigger == Trigger.OPEN && s.on(Flag.STATUS_ON_OPEN)) || s.on(Flag.STATE)) out += Work.REFRESH_STATE
        }
        return out
    }
}
