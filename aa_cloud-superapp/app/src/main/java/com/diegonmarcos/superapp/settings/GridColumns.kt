package com.diegonmarcos.superapp.settings

import android.content.Context
import android.content.SharedPreferences
import com.diegonmarcos.superapp.BuildConfig

/**
 * Icons per row for the Cloud grids and the Phone grids: one place that answers
 * "how many columns", and the sizing rule that keeps that many columns legible.
 *
 * STORAGE. Two keys in the `launcher_settings` prefs ([Kind.key]); the store is
 * already declared in fleet-config.json, which lists these keys. A key is
 * written ONLY when the owner moves the stepper. Until then it is absent and the
 * answer is the shipped default (build.json ui.tile_columns for Cloud, ui
 * .phone_grid_columns for Phone), which is the whole migration: the Phone
 * default went 6 -> 7 and every phone that never chose a value follows it,
 * while a phone that chose one (even 6) has a key and keeps it. Nothing is
 * copied into the store on upgrade, because a copy of the old default would
 * freeze exactly the users who should move.
 */
object GridColumns {
    const val MIN = 4
    const val MAX = 8
    const val STORE = "launcher_settings"

    enum class Kind(val key: String) {
        CLOUD("grid_cols_cloud"),
        PHONE("grid_cols_phone"),
    }

    fun clamp(v: Int) = v.coerceIn(MIN, MAX)

    /** The shipped default for [kind], from build.json via BuildConfig. */
    fun default(kind: Kind): Int = clamp(
        when (kind) {
            Kind.CLOUD -> BuildConfig.UI_TILE_COLUMNS
            Kind.PHONE -> BuildConfig.UI_PHONE_GRID_COLUMNS
        })

    /** Stored value if the owner set one (clamped: a hand-edited or imported
     *  out-of-range number must not break the grid), else [default]. */
    fun read(sp: SharedPreferences, kind: Kind, fallback: Int = default(kind)): Int =
        if (sp.contains(kind.key)) clamp(sp.getInt(kind.key, fallback)) else clamp(fallback)

    internal fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun get(ctx: Context, kind: Kind): Int = read(sp(ctx), kind)
    fun cloud(ctx: Context): Int = get(ctx, Kind.CLOUD)
    fun phone(ctx: Context): Int = get(ctx, Kind.PHONE)
    fun set(ctx: Context, kind: Kind, v: Int) { sp(ctx).edit().putInt(kind.key, clamp(v)).apply() }

    // ── Sizing: the same arithmetic the grids use and the 360 dp test asserts ──

    // #1893 the sizing arithmetic lives once in libs:tile-grid (TileSizing), which Cloud Calc's Home draws too.
    fun cellPx(availablePx: Int, cols: Int): Int = com.diegonmarcos.superapp.launcher.TileSizing.cellPx(availablePx, cols)
    fun iconPx(wantPx: Int, cellPx: Int, chromePx: Int, floorPx: Int): Int = com.diegonmarcos.superapp.launcher.TileSizing.iconPx(wantPx, cellPx, chromePx, floorPx)
    fun labelSp(cols: Int): Float = com.diegonmarcos.superapp.launcher.TileSizing.labelSp(cols)
    fun cellPadDp(cols: Int): Int = com.diegonmarcos.superapp.launcher.TileSizing.cellPadDp(cols)
}
