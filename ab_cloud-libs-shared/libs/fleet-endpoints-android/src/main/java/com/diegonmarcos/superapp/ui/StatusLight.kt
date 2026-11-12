package com.diegonmarcos.superapp.ui

import android.content.Context
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.fleetendpoints.android.R

/**
 * The one place a reading about whether something is WORKING becomes something
 * on screen.
 *
 * ── Why this is an object and not four literals in a fragment ─────────────
 * A status light makes a promise — this thing is working, or it is not — and
 * the owner starts trusting it and stops checking. Two screens promising that
 * in two slightly different greens is not a style choice, it is one of them
 * being wrong about which green means healthy. So the states live here, once,
 * and the pages point at them.
 *
 * ── FOUR states, because reality has four ─────────────────────────────────
 * Red and green are two. The third is NOT YET KNOWN — the check has not run,
 * is running, threw, the platform declined to answer, or its answer has aged
 * out. The fourth is NOT KNOWABLE: there is no way to look at the thing this
 * row controls, only at a preference this app wrote down about it, and a
 * preference is what was ASKED FOR, never what is true. A firewall preference
 * says on while the VpnService behind it is dead and nothing in the store
 * knows; painting that green is precisely the false green this whole page was
 * written to prevent.
 *
 * Four states, THREE appearances. [UNKNOWN] and [UNVERIFIABLE] draw the same
 * — same glyph, same colour — because to the owner they are the same fact:
 * nobody can currently say. They differ only in the WORD, which explains
 * whether the answer is late or impossible. Two honest colours plus an honest
 * uncertainty beats three colours where one of them lies.
 *
 * ── Colour is never the only channel ──────────────────────────────────────
 * Red and green are the pair colour-blind users most often cannot separate,
 * and both are grey in a screenshot. Every state therefore carries a [glyph]
 * whose SHAPE differs — filled disc, hollow ring, question mark — and a word
 * from the string table, so the light reads correctly with no colour at all.
 * All three glyphs are already this app's vocabulary: ● / ○ are what
 * NetworkInfoPopup draws for connected / disconnected, ? is what
 * PermissionsFragment draws for a grant it cannot determine. [description]
 * is the same fact again for a screen reader, which gets neither.
 *
 * ── Why the colours are resources and not constants ───────────────────────
 * This app ships a black→purple gradient theme and two OLED themes that paint
 * the page pure black, and a status light is the last thing on screen that
 * may become hard to see. The values here are the ones that clear WCAG AA
 * (4.5:1) against BOTH of those surfaces — see colors.xml, and the tester
 * that recomputes the ratios rather than trusting this sentence. Keeping them
 * in colors.xml is also what lets a future theme override them without a
 * Kotlin edit; a colour compiled into a constant cannot be re-themed at all.
 */
object StatusLight {

    enum class State { ON, OFF, UNKNOWN, UNVERIFIABLE }

    /**
     * A reading, and whether it was a look at the thing itself.
     *
     * @param reading as every state source in this app already returns it.
     *   null is UNKNOWN and MUST NOT collapse to OFF: "the platform would not
     *   say" and "the platform said no" are different facts, and a light that
     *   merges them is the lie this type exists to prevent.
     * @param observed false ⇒ [reading] came from a preference this app
     *   stored, not from the mechanism it describes, so it cannot support a
     *   green OR a red no matter which way it reads. Defaults true because a
     *   caller that reads the platform directly has nothing to declare; the
     *   callers that do not are the ones that must say so.
     */
    fun of(reading: Boolean?, observed: Boolean = true): State =
        if (!observed) State.UNVERIFIABLE
        else when (reading) {
            true -> State.ON
            false -> State.OFF
            null -> State.UNKNOWN
        }

    /** Shape first — this is what survives greyscale and colour blindness. */
    fun glyph(state: State): String = when (state) {
        State.ON -> "●"             // ● filled
        State.OFF -> "○"            // ○ hollow
        State.UNKNOWN -> "?"
        State.UNVERIFIABLE -> "?"   // same appearance, different word
    }

    fun labelRes(state: State): Int = when (state) {
        State.ON -> R.string.status_light_on
        State.OFF -> R.string.status_light_off
        State.UNKNOWN -> R.string.status_light_unknown
        State.UNVERIFIABLE -> R.string.status_light_unverifiable
    }

    fun label(ctx: Context, state: State): String = ctx.getString(labelRes(state))

    /** Glyph and word together — what a row actually prints. */
    fun text(ctx: Context, state: State): String = glyph(state) + " " + label(ctx, state)

    fun colourRes(state: State): Int = when (state) {
        State.ON -> R.color.status_light_on
        State.OFF -> R.color.status_light_off
        State.UNKNOWN -> R.color.status_light_unknown
        State.UNVERIFIABLE -> R.color.status_light_unknown
    }

    fun colour(ctx: Context, state: State): Int = ContextCompat.getColor(ctx, colourRes(state))

    /**
     * What a screen reader says for one row's light.
     *
     * The glyph is deliberately absent: TalkBack pronounces "●" as "black
     * circle", which is a description of the decoration rather than of the
     * device. The row's own name is included because the light sits at the
     * end of a row and is otherwise announced as a bare "On" belonging to
     * nothing.
     */
    fun description(ctx: Context, rowLabel: String, state: State): String =
        ctx.getString(R.string.status_light_description, rowLabel, label(ctx, state))
}
