package com.diegonmarcos.superapp.adbdebug

/**
 * Which shell channel runs the app's commands, as pure rules (no Android): the owner's
 * "Privileged channel" setting, and the selection it drives.
 *
 * Every mode but AUTO is ONE self-contained channel and never touches another's:
 *  - SHIZUKU: the external Shizuku app's binder, and nothing of adb.
 *  - EMBEDDED_ONLY: the app's own adb client connected to adbd (wireless debugging), and nothing else.
 *  - LOCAL_SERVER: that same adbd connection on 127.0.0.1 is the channel; commands run directly over
 *    it. The loopback app_process server, when its own button started it, answers first (it outlives a
 *    dropped adb session). Nothing of Shizuku.
 *  - AUTO: the declared ladder of every channel, first one that answers.
 * No mode starts anything to get a channel: selection only reads what is up.
 */
enum class ChannelMode(val id: String) {
    /** The adbd connection on 127.0.0.1 (the loopback server first, when it was started and answers). Never Shizuku. */
    LOCAL_SERVER("local"),
    /** Embedded adb, then the local server, then the external providers. */
    AUTO("auto"),
    /** Embedded adb only; the server is skipped. */
    EMBEDDED_ONLY("embedded"),
    /** The external Shizuku app's binder only. */
    SHIZUKU("shizuku");

    companion object {
        fun parse(id: String?, default: ChannelMode): ChannelMode = values().firstOrNull { it.id == id } ?: default
    }
}

object ChannelSelector {
    const val EMBEDDED = "embedded-adb"
    const val LOCAL = "local-server"
    const val SHIZUKU = "shizuku"
    /** The SuperApp's loopback exec route, a terminal's way to the shell (build.json::shizuku_client.providers). */
    const val BRIDGE = "superapp-bridge"

    /** Channel names allowed to RUN COMMANDS in [mode], in order, out of [ladder] (preference order). */
    fun execOrder(mode: ChannelMode, ladder: List<String>, ownsServer: Boolean = true): List<String> = when (mode) {
        ChannelMode.AUTO -> ladder
        // An app with no server of its own (a client of the owner) keeps the owner route behind the local layers.
        ChannelMode.LOCAL_SERVER ->
            listOf(LOCAL, EMBEDDED).filter { it in ladder } + (if (ownsServer) emptyList() else ladder.filter { it != LOCAL && it != EMBEDDED })
        ChannelMode.EMBEDDED_ONLY -> ladder.filter { it == EMBEDDED }
        ChannelMode.SHIZUKU -> ladder.filter { it == SHIZUKU }
    }

    /**
     * Channel names the "Restart server" BUTTON may send its one launch line over. Only that explicit
     * tap uses it; no connect, reconnect, auto-fix or channel lookup ever launches the server.
     * LOCAL_SERVER: the embedded adb session alone (never Shizuku). AUTO: any other channel of its ladder.
     */
    fun launchSources(mode: ChannelMode, ladder: List<String>): List<String> = when (mode) {
        ChannelMode.EMBEDDED_ONLY, ChannelMode.SHIZUKU -> emptyList()
        ChannelMode.LOCAL_SERVER -> ladder.filter { it == EMBEDDED }
        ChannelMode.AUTO -> ladder.filter { it != LOCAL }
    }

    /** Every channel [mode] may touch at all (run commands on, or launch the server through). */
    fun touches(mode: ChannelMode, ladder: List<String>): Set<String> =
        (execOrder(mode, ladder) + launchSources(mode, ladder)).toSet()

    /**
     * The channel that runs commands, or null: the first of [execOrder] that is [usable] (ready, and
     * for the server, answering). A pure read: it starts nothing, so a mode can never reach into
     * another channel (or launch a server) as a side effect of being asked what is up.
     */
    fun <T> select(mode: ChannelMode, ladder: List<T>, name: (T) -> String, usable: (T) -> Boolean, ownsServer: Boolean = true): T? {
        val byName = ladder.associateBy(name)
        return execOrder(mode, ladder.map(name), ownsServer).mapNotNull { byName[it] }.firstOrNull(usable)
    }

    /** The control's label in Shizuku mode. */
    fun shizukuLabel(state: ShizukuState): String = "Shizuku: " + when (state) {
        ShizukuState.UP -> "up"
        ShizukuState.NOT_RUNNING -> "not running"
        ShizukuState.PERMISSION_NEEDED -> "permission needed"
        ShizukuState.NOT_INSTALLED -> "not installed"
    }

    /** The control's label for the combined status. [via] = the channel that answered. */
    fun label(via: String?, down: Boolean): String = when {
        via != null -> "Wireless Dbg: up ($via)"
        down -> "Wireless Dbg: down - Reconnect"
        else -> "Wireless Dbg: not paired - Pair"
    }
}

/** Where the external Shizuku app stands, from three facts. */
enum class ShizukuState {
    UP, NOT_RUNNING, PERMISSION_NEEDED, NOT_INSTALLED;

    companion object {
        fun of(installed: Boolean, running: Boolean, granted: Boolean): ShizukuState = when {
            !installed && !running -> NOT_INSTALLED
            !running -> NOT_RUNNING
            !granted -> PERMISSION_NEEDED
            else -> UP
        }
    }
}

/** Display names of the four modes; the one place they are spelled. */
fun ChannelMode.title(): String = when (this) {
    ChannelMode.LOCAL_SERVER -> "Local server"
    ChannelMode.EMBEDDED_ONLY -> "Embedded adb"
    ChannelMode.SHIZUKU -> "Shizuku"
    ChannelMode.AUTO -> "Auto"
}

/** What a mode does, one line. */
fun ChannelMode.hint(): String = when (this) {
    ChannelMode.LOCAL_SERVER -> "The app starts the loopback server by itself (embedded adb launches it, then closes; Shizuku only as the fallback) and every command runs through it."
    ChannelMode.EMBEDDED_ONLY -> "Embedded adb only; the local server is skipped."
    ChannelMode.SHIZUKU -> "The external Shizuku app only."
    ChannelMode.AUTO -> "Embedded adb, then the local server, then Shizuku."
}
