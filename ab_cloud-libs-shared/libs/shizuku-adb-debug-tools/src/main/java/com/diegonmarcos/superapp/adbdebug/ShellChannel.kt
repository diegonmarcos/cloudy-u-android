package com.diegonmarcos.superapp.adbdebug

import android.content.Context

/**
 * A way to run a shell command in the SHELL SELinux domain (uid 2000) —
 * the only domain that can `dumpsys battery/usb` and read
 * `/sys/class/power_supply/` nodes on a stock, non-rooted device
 * (untrusted_app is denied regardless of the DUMP permission).
 *
 * Three implementations form a ladder (first ready wins):
 *   1. [EmbeddedAdbChannel]  — PRIMARY. Embedded on-device adb client that
 *                              pairs with localhost Wireless-Debugging adbd
 *                              (the LADB approach). Fully self-contained:
 *                              no third-party app, no PC. "We ARE Shizuku."
 *   2. [LocalShellChannel]   — OUR app_process server (AdbShellServer),
 *                              when its own button (or the adb one-liner)
 *                              started it. Optional: nothing waits for it.
 *   3. [ShizukuShellChannel] — optional fallback when the Shizuku app is
 *                              already running + granted.
 */
interface ShellChannel {
    /** Short id surfaced in API responses ("local-server" / "shizuku"). */
    fun name(): String

    /** True when this channel can execute right now (cheap probe). */
    fun isReady(ctx: Context): Boolean

    /** Run `sh -c <command>` in shell context; null if this channel
     *  couldn't serve it. */
    fun exec(ctx: Context, command: String): String?

    /**
     * Cheap round trip that proves the channel EXECUTES, not merely that a
     * socket is up. [isReady] only reports connection state, so a wedged
     * channel used to be discovered by a multi-megabyte install hanging for
     * the whole exec timeout, once per app.
     */
    fun probe(ctx: Context): Boolean =
        exec(ctx, "echo shell-ok")?.contains("shell-ok") == true

    /**
     * Run [command] with the contents of [stdin] piped to its standard input,
     * and capture stdout. Null when this channel cannot carry binary stdin —
     * the caller then has to find another way.
     *
     * This is the only way to hand a file to a shell-domain command: the shell
     * runs as uid 2000 and can read NEITHER our 0700 app-private cache NOR
     * /Android/data (FUSE-restricted from Android 11), and it cannot be given
     * a staging directory either (/data/local/tmp is 0771 root:shell, so an
     * untrusted_app cannot create anything in it). Bytes over the wire, with
     * no filesystem shared with the shell at all.
     */
    fun execWithStdin(ctx: Context, command: String, stdin: java.io.File): String? = null

    /** One-line human status for /api/adb/status. */
    fun status(ctx: Context): String
}

/** The execution ladder. Order = preference. */
object ShellChannels {

    /** Provider id (build.json::shizuku_client.providers[]) -> its channel. */
    private val byProvider: Map<String, ShellChannel> = mapOf(
        "moe.shizuku.privileged.api" to ShizukuShellChannel,
        "com.diegonmarcos.superapp" to SuperappBridgeChannel,
    )

    /**
     * The ladder. The self-contained channels (our embedded adb client, then our
     * app_process server) always lead — they need no other app. After them come
     * the external providers IN THE ORDER build.json::shizuku_client.providers
     * declares them ([RishBridge.providers]), so the data picks whether Shizuku
     * or the SuperApp bridge is tried first. An app with no block (the SuperApp
     * itself) keeps the legacy ladder with Shizuku as the tail fallback.
     */
    val all: List<ShellChannel>
        get() {
            val self = listOf(EmbeddedAdbChannel, LocalShellChannel)
            val declared = RishBridge.providers.mapNotNull { byProvider[it] }
            return if (declared.isEmpty()) self + ShizukuShellChannel else self + declared
        }

    /**
     * Does the chosen mode run commands over the channel named [name] at all? Code that reaches for ONE channel
     * directly (a Shizuku fallback, a status line) asks this first, so it cannot cross into another mode's channel.
     */
    fun modeUses(ctx: Context, name: String): Boolean =
        name in ChannelSelector.execOrder(ChannelModePrefs.current(ctx), all.map { it.name() })

    /**
     * The channel that runs commands in the chosen mode, or null. A pure read of what is up: it
     * launches nothing and reconnects nothing (that used to start the app_process server through
     * whichever channel answered, on every lookup).
     */
    fun active(ctx: Context): ShellChannel? {
        // Our server is the one channel that can WEDGE: a uid-2000 app_process launched from the
        // previous APK keeps its listening socket after an update while every exec dies in it, so
        // isReady (a connect) stays true. It is asked to execute before it is trusted.
        return ChannelSelector.select(ChannelModePrefs.current(ctx), all, { it.name() },
            usable = { it.isReady(ctx) && (it !== LocalShellChannel || it.probe(ctx)) })
    }
}
