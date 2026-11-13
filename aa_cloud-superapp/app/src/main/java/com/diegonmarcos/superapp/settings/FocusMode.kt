package com.diegonmarcos.superapp.settings

import android.content.Context

/**
 * Focus Mode (#1596) — a user-turned-on mode that limits distractions.
 *
 * This is the one FACT the mode owns: whether Focus is on. It is a readable
 * boolean other parts of the app consult to quieten themselves — today
 * [com.diegonmarcos.superapp.notificationcenter.AlertsNotifier] reads it to keep
 * a non-urgent fleet alert silent (no sound, no heads-up) while Focus is on,
 * urgent alerts still ring. Nothing here draws anything: the page is
 * [com.diegonmarcos.superapp.configs.FocusModeFragment], the label and subtitle
 * are the SAME declaration the Presets ▸ Modes "Focus Mode" tile reads
 * (build.json::ui.launcher_modes[id=focus]), so there is no second copy of the
 * words and no engine of its own.
 *
 * Fixed store name, never a page id, so renaming the page or its tile can never
 * lose the state — the same rule [LauncherModePrefs] keeps.
 */
object FocusMode {
    /** The id of the mirrored declaration row, so the page reads its words from one place. */
    const val MODE_ID = "focus"

    private fun sp(ctx: Context) = ctx.applicationContext
        .getSharedPreferences("focus_mode_prefs", Context.MODE_PRIVATE)

    /**
     * Is Focus on? The one boolean other parts read. #1720: derived from the single state source ([PresetState.mode]), so
     * Focus on this page and the Focus Mode tile of Presets are the same fact and no second mode can be on beside it.
     */
    fun active(ctx: Context): Boolean = PresetState.mode(ctx) == MODE_ID

    /**
     * Turn Focus on or off. #1720: a pick like any other mode ([PresetModes.select] / [PresetModes.off]): the previous mode is
     * restored first, the steps run on the mode worker (never the caller's thread), and [PresetState.observe] tells the page
     * when the state really changed.
     */
    fun setActive(ctx: Context, on: Boolean) {
        val app = ctx.applicationContext
        PresetModes.async {
            if (on) PresetModes.select(app, MODE_ID)
            else if (PresetState.mode(app) == MODE_ID) PresetModes.off(app, force = true)
        }
    }

    /** The boolean this object kept before #1720 (store focus_mode_prefs, key active), read once by [PresetModes.migrate]. */
    internal fun legacyActive(ctx: Context): Boolean = runCatching { sp(ctx).getBoolean("active", false) }.getOrDefault(false)

    internal fun clearLegacy(ctx: Context) { runCatching { if (sp(ctx).contains("active")) sp(ctx).edit().remove("active").apply() } }
}
