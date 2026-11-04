package com.diegonmarcos.superapp.appstore

import android.content.Context
import kotlin.math.roundToInt

/**
 * #869 THE density declaration of the Store - Cloud Store's screens and every
 * libs:appstore shelf, row, bar and sheet read their sizes from here and from
 * nowhere else (the #621 cloud-drive approach, DriveMetrics). The Store is a
 * data list, not a launcher: rows are compact, the type ramp is small, buttons
 * are not Material's 48dp boxes.
 *
 * No other Kotlin file of the Store holds a dp or sp literal: spacing is one of
 * the steps below, type is one of the T_* ramp entries, and a fixed box is a
 * named size. Change the feel of the whole Store by editing this file only.
 */
object StoreDensity {
    /** Every dp value of the Store is multiplied by this (1.0 = the old, launcher-sized look). */
    const val SCALE = com.diegonmarcos.superapp.uikit.KitStoreRow.SCALE
    /** Every sp value of the Store is multiplied by this. */
    const val TEXT_SCALE = com.diegonmarcos.superapp.uikit.KitStoreRow.TEXT_SCALE

    // Spacing steps (dp, before SCALE): the six-step set every old pad collapses into.
    const val S1 = 1
    const val S2 = 2
    const val S4 = 4
    const val S6 = 6
    const val S8 = 8
    const val S12 = 12
    /** Row glyph / icon box. */
    const val GLYPH = 16
    /** Height of the detail sheet's body. */
    const val SHEET = 380
    /** A tab's / control's touch height; the tap target stays usable. */
    const val TAP = 36

    // Type ramp (sp, before TEXT_SCALE).
    const val T_MICRO = 10f * TEXT_SCALE
    const val T_CAPTION = 11f * TEXT_SCALE
    const val T_META = 12f * TEXT_SCALE
    const val T_BODY = 13f * TEXT_SCALE
    const val T_TITLE = 14f * TEXT_SCALE
    const val T_HEAD = 18f * TEXT_SCALE

    /** [step] dp (one of the steps / sizes above) in pixels, scaled once. */
    fun dp(ctx: Context, step: Int): Int =
        (step * SCALE * ctx.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

    /** A control's outline width, in dp (unscaled: a hairline stays a hairline). */
    const val STROKE = 1f

    /** The same, for Compose (dp as a plain number). */
    fun dpValue(step: Int): Float = step * SCALE
}
