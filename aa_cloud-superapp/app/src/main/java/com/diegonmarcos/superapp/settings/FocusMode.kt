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

    /** Is Focus on? The one boolean other parts read. False before any pick. */
    fun active(ctx: Context): Boolean = sp(ctx).getBoolean("active", false)

    /** Turn Focus on or off. Committed with apply(); the readout is derived, never cached. */
    fun setActive(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("active", on).apply()
    }
}
