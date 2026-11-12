package com.diegonmarcos.superapp.configs

import com.diegonmarcos.superapp.perms.PermEntry
import com.diegonmarcos.superapp.perms.PermItem
import com.diegonmarcos.superapp.perms.PermState
import com.diegonmarcos.superapp.perms.PermStatus
import com.diegonmarcos.superapp.perms.PermsActions
import com.diegonmarcos.superapp.perms.PermsGrant
import com.diegonmarcos.superapp.perms.PermsView

/**
 * #1688 one ADB-path toggle on the Perms page: "Run on ADB" (enable OR disable, chosen by the REAL current state) and
 * "Copy command" (the exact shell lines of that same direction). Pure: the commands are never typed here, they come
 * from ONE declaration per row:
 *  - a catalogue permission: libs:perms PermsActions.commands (enable) / PermsActions.resetCommands (disable);
 *  - #1687 Power saving: build.json::ui.control_panel controls[id=power_saving].adb {enable, disable}.
 * The run goes through the declared ADB owner channel (the shell broker, [BrokerGrantChannel]); the caller re-reads
 * the real state after it and shows [Outcome.message].
 */
data class AdbToggle(
    val id: String,
    val label: String,
    /** The real state now: true = enabled / granted, false = off, null = unknown (Run then enables). */
    val on: Boolean?,
    val enable: List<String>,
    val disable: List<String>,
    /** Why a direction cannot run by adb (shown instead of a silent no-op); "" when it can. */
    val enableCannot: String = "",
    val disableCannot: String = "",
) {
    enum class Direction(val word: String) { ENABLE("Enable"), DISABLE("Disable") }

    /** What Run would do now: disable only when the real state says it is on. */
    val direction: Direction get() = if (on == true) Direction.DISABLE else Direction.ENABLE

    /** The exact lines of the current direction. */
    val commands: List<String> get() = if (direction == Direction.ENABLE) enable else disable

    val cannot: String get() = if (direction == Direction.ENABLE) enableCannot else disableCannot

    /** Run is offered only when the current direction has commands. */
    val runnable: Boolean get() = commands.isNotEmpty() && cannot.isEmpty()

    /** "Run on ADB: Disable" — the button word says which way it goes. */
    val runWord: String get() = "Run on ADB: ${direction.word}"

    /** Clipboard text: one `adb shell` line per command, or the reason as a comment. */
    fun copyText(): String =
        if (commands.isEmpty()) "# $label: ${cannot.ifEmpty { "no adb command declared" }}"
        else commands.joinToString("\n") { "adb shell $it" }

    data class Outcome(val ok: Boolean, val message: String, val output: String, val channelDown: Boolean = false)

    companion object {
        /** Shown when no ADB owner channel is up; the page pairs it with the ADB Shell link. */
        const val CHANNEL_DOWN = "No ADB channel is connected (ADB Shell is the owner of the shell channel). Open ADB Shell and press Connect."

        /** A catalogue row: both directions from libs:perms' one declaration. */
        fun forPerm(pkg: String, e: PermEntry, s: PermStatus, components: Map<String, String> = emptyMap(), a11y: String = ""): AdbToggle? {
            if (s.state == PermState.NOT_APPLICABLE) return null
            val item: PermItem = PermsView.item(e, PermStatus(PermState.DENIED, s.reason))
            val en = PermsActions.commands(pkg, item, components, a11y)
            val dis = PermsActions.resetCommands(pkg, item, components, a11y)
            val enCmds = (en as? PermsActions.Grant.Shell)?.commands.orEmpty()
            val disCmds = (dis as? PermsActions.Grant.Shell)?.commands.orEmpty()
            if (enCmds.isEmpty() && disCmds.isEmpty()) return null // not an ADB-path row
            val on = when (s.state) { PermState.GRANTED -> true; PermState.UNKNOWN -> null; else -> false }
            return AdbToggle(e.id, e.label, on, enCmds, disCmds,
                (en as? PermsActions.Grant.Cannot)?.reason.orEmpty(), (dis as? PermsActions.Grant.Cannot)?.reason.orEmpty())
        }

        /**
         * Run [t]'s current direction through [channel], then re-read with [reread]. Never throws; a down channel
         * is an outcome with [Outcome.channelDown], never a silent failure.
         */
        fun run(t: AdbToggle, channel: PermsGrant.GrantChannel, reread: () -> Boolean?): Outcome {
            val dir = t.direction
            if (!t.runnable) return Outcome(false, "${t.label}: ${t.cannot.ifEmpty { "no adb command for ${dir.word.lowercase()}" }}", "")
            val up = runCatching { channel.up() }.getOrDefault(false)
            if (!up) return Outcome(false, "${t.label}: $CHANNEL_DOWN", "", channelDown = true)
            val out = StringBuilder()
            for (c in t.commands) {
                val o = runCatching { channel.exec(c) }.getOrNull()
                out.append("$ ").append(c).append('\n').append(o?.ifBlank { "ok" } ?: "no answer").append('\n')
                val why = PermsGrant.failure(o)
                if (why != null) return Outcome(false, "${t.label}: the shell refused it ($why)", out.toString().trim())
            }
            val now = runCatching { reread() }.getOrNull()
            val want = dir == Direction.ENABLE
            val ok = now == want
            val word = when (now) { true -> "on"; false -> "off"; null -> "unknown" }
            return Outcome(ok, "${t.label}: ${dir.word.lowercase()} ran, state is now $word" +
                (if (ok) "" else " (the device did not follow)"), out.toString().trim())
        }
    }
}
