package com.diegonmarcos.superapp.adbdebug

import android.content.Context

/** The "Privileged channel" setting: stored in the already-declared adb_shell prefs. */
object ChannelModePrefs {
    private const val PREFS = "adb_shell"
    private const val KEY = "channel_mode"

    /** True when this app declares its own local server (build.json::shizuku_diagnostics.local_server). */
    fun ownsServer(): Boolean = BuildConfig.ADB_SHELL_SERVER_OWN

    /**
     * Local server for EVERY app (#1709): new installs and anyone who never chose a profile get it; a stored choice wins.
     * An app with no server of its own reaches the owner's server (the shell-client / bridge route, kept as the
     * fallback behind the local layers by [ChannelSelector.execOrder] with ownsServer = false).
     */
    fun default(): ChannelMode = ChannelMode.LOCAL_SERVER

    fun current(ctx: Context): ChannelMode = runCatching {
        ChannelMode.parse(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null), default())
    }.getOrDefault(default())

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(ChannelMode) -> Unit>()

    /** Told right after the owner changes the mode, so a status can re-probe at once. */
    fun addListener(l: (ChannelMode) -> Unit) { listeners.addIfAbsent(l) }

    fun set(ctx: Context, mode: ChannelMode) {
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.id).commit() }
        // The next ShellChannels.active() already reads the new mode (the install route follows).
        listeners.forEach { runCatching { it(mode) } }
    }
}

/**
 * Who launched the local server. No longer a user choice (the person picks only the profile): the launcher is automatic,
 * Embedded adb first, Shizuku only as the fallback. The `launch_via` key stays readable for migration; [set] is kept for
 * old callers and nothing in the UI calls it.
 */
object LaunchViaPrefs {
    private const val PREFS = "adb_shell"
    private const val KEY = "launch_via"

    fun current(ctx: Context): LaunchVia = runCatching {
        LaunchVia.parse(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
    }.getOrDefault(LaunchVia.ADB)

    fun set(ctx: Context, via: LaunchVia) {
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, via.id).commit() }
    }

    /** [sources] (channels allowed to launch the server), Embedded adb first, Shizuku only after it. */
    @Suppress("UNUSED_PARAMETER")
    fun order(ctx: Context, sources: List<ShellChannel>): List<ShellChannel> = orderNames(sources) { it.name() }

    /** Pure: the automatic launcher order. */
    fun <T> orderNames(sources: List<T>, name: (T) -> String): List<T> =
        sources.sortedBy { if (name(it) == ChannelSelector.EMBEDDED) 0 else 1 }
}
