package com.diegonmarcos.superapp.launcher

import android.content.Context
import kotlin.math.abs

/**
 * #1794 the All page's persistent-badge rows have THREE states, in this order:
 *
 *   COLLAPSED  the one-line summary (icon, name, key value, live dot) the page drew before;
 *   FIRST      the badge's expanded state as the notification centre shows it (the platform's big content view of
 *              the badge's own posted notification, action row included);
 *   SECOND     SUPER EXPANDED (#1935): exactly the view the Persistents page shows, drawn by the same function
 *              ([BadgeCardBody]) from the same model, every field and every action.
 *
 * Only two inputs move a row, and both take the same step ([next]): the arrow on the row's right, and a swipe DOWN on the
 * row's box ([isSwipeDown]). From SECOND that step folds the row back to COLLAPSED, so the arrow alone reaches every state
 * and back. Every other tap on the row keeps its meaning (it does what the badge's notification does). The stage is the
 * owner's per badge and survives restarts.
 *
 * Pure apart from [stage]/[set]; the unit test drives the machine directly (BadgeExpansionTest).
 */
internal object BadgeExpansion {

    enum class Stage { COLLAPSED, FIRST, SECOND }

    /** The one transition: arrow tap and swipe down both take it. */
    fun next(s: Stage): Stage = when (s) {
        Stage.COLLAPSED -> Stage.FIRST
        Stage.FIRST -> Stage.SECOND
        Stage.SECOND -> Stage.COLLAPSED
    }

    /** The arrow's glyph: down while there is more to open, up on the last state (its next step folds the row). */
    fun glyph(s: Stage): String = if (s == Stage.SECOND) "▴" else "▾"

    /** The arrow's spoken name, by what its next step does. */
    fun describe(s: Stage): String = if (s == Stage.SECOND) "Collapse" else "Expand"

    /**
     * A finished gesture is a swipe DOWN when it travelled at least [thresholdPx] downwards and was mostly vertical (at
     * least twice its sideways travel), so a tap, a sideways swipe or an upward scroll is not one.
     */
    fun isSwipeDown(dy: Float, dx: Float, thresholdPx: Float): Boolean = dy >= thresholdPx && abs(dy) >= 2 * abs(dx)

    /** A stored stage name back to a stage; anything unknown reads as COLLAPSED (the row's first, default state). */
    fun decode(raw: String?): Stage = Stage.values().firstOrNull { it.name == raw } ?: Stage.COLLAPSED

    private const val PREFS = "notify_all_badge_stage"
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun stage(ctx: Context, id: String): Stage = runCatching { decode(p(ctx).getString(id, null)) }.getOrDefault(Stage.COLLAPSED)

    fun set(ctx: Context, id: String, s: Stage) {
        runCatching { p(ctx).edit().putString(id, s.name).commit() }
    }
}
