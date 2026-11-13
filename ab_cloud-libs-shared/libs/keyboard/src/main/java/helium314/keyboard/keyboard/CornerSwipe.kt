// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import android.content.Context
import helium314.keyboard.latin.utils.prefs
import kotlin.math.hypot

/**
 * #1413 corner swipes for the "unexpected" main layout (assets/layouts/main/unexpected.json), in the style of
 * Unexpected Keyboard. Original implementation, no code taken from Unexpected Keyboard (GPL-3.0).
 * Each letter key may carry up to four corner actions, in the order NW, NE, SW, SE. A swipe that starts on the
 * key and ends at least [minDistance] away picks the corner by direction; the action is sent through the existing
 * KeyCode path (Ctrl/Alt are one-shot modifiers, arrows are DPAD key events). The same corners are also the key's
 * popup keys, so a long-press still offers them.
 */
object CornerSwipe {
    const val LAYOUT = "unexpected"
    /** Configs ▸ Dev Keyboard (#1413): corner swipes and corner labels on/off, OFF by default; the DEV_KEYBOARD toolbar key flips it. */
    const val PREF = "pref_dev_keyboard"
    const val DEFAULT = false
    @JvmField var enabled = DEFAULT

    /** #1606: functional-keys layout with an fn key, used while Dev Keyboard is on */
    const val FUNCTIONAL_LAYOUT = "functional_keys_dev"

    /** The functional layout [FUNCTIONAL_LAYOUT] is made from: it has no file of its own (#1547: no second baked copy of the same keys). */
    const val FUNCTIONAL_BASE = "functional_keys"

    /** The one key [FUNCTIONAL_LAYOUT] adds, right after the symbol/alpha switch of the bottom row. */
    private const val FN_KEY = ",\n    {\n      \"label\": \"fn\",\n      \"width\": 0.1\n    }"

    /**
     * #1606 The content of [FUNCTIONAL_LAYOUT]: [base] (the [FUNCTIONAL_BASE] layout, json) with the fn key inserted after its
     * symbol_alpha key. Every key of the base stays, in its place; a base without that key is returned unchanged.
     */
    @JvmStatic
    fun devFunctional(base: String): String {
        val at = base.indexOf("\"symbol_alpha\"")
        val end = if (at < 0) -1 else base.indexOf('}', at)
        return if (end < 0) base else base.substring(0, end + 1) + FN_KEY + base.substring(end + 1)
    }

    /** #1665 the alphabet MAIN layout to load: the Dev layout while the Dev theme is on, else the subtype's [own] (pure, testable). */
    @JvmStatic
    fun mainLayoutFor(devOn: Boolean, alphabet: Boolean, own: String): String =
        if (devOn && alphabet) (if (UnexpectedKb.active) (if (UnexpectedKb.fn) UnexpectedKb.MAIN_FN else UnexpectedKb.MAIN_BASE) else LAYOUT) else own

    @JvmStatic
    fun refreshed(context: Context): Boolean { refresh(context); return enabled }

    @JvmStatic
    fun refresh(context: Context) {
        val prefs = context.prefs()
        enabled = prefs.getBoolean(PREF, DEFAULT)
        // the Unexpected theme (its own keyboard from assets/unexpected_kb/layout.json) rides on the same flag
        val on = enabled && prefs.getBoolean(UnexpectedKb.PREF, false)
        if (on) UnexpectedKb.load(context)
        UnexpectedKb.active = on && UnexpectedKb.loaded() != null
        if (!UnexpectedKb.active) UnexpectedKb.fn = false
    }

    /** #1413: the DEV_KEYBOARD toolbar key: flip the pref and redraw the keyboard. */
    @JvmStatic
    fun toggle(context: Context) {
        // #1665 Dev Keyboard IS the Dev theme: the toolbar key switches the whole theme (colours + layout + fn key), the same as
        // picking Dev / Default in Keyboard > Themes, so the two can never disagree.
        helium314.keyboard.settings.KbThemePreset.setDev(context, !enabled)
    }

    /**
     * Corner swipes apply on the alphabet keyboard while Dev Keyboard is on. The alphabet main
     * layout is forced to [LAYOUT] by LayoutParser whenever [enabled], so this is just the flag
     * plus "is this the alphabet keyboard" — it no longer requires the subtype itself to already
     * be the "unexpected" layout (which is why enabling Dev Keyboard used to do nothing on a
     * normal qwerty subtype: the layout was never switched in).
     */
    @JvmStatic
    fun isActive(keyboard: Keyboard?): Boolean =
        enabled && keyboard != null && keyboard.mId.isAlphabetKeyboard

    private val display = mapOf("left" to "←", "right" to "→", "up" to "↑", "down" to "↓")

    /** small label drawn in a key corner */
    @JvmStatic
    fun labelOf(action: String): String = display[action] ?: action

    const val NW = 0; const val NE = 1; const val SW = 2; const val SE = 3

    /** action label -> code: named actions use the existing KeyCode, single characters their code point. */
    private val named = mapOf(
        "esc" to KeyCode.ESCAPE, "tab" to KeyCode.TAB, "ctrl" to KeyCode.CTRL, "alt" to KeyCode.ALT,
        "left" to KeyCode.ARROW_LEFT, "right" to KeyCode.ARROW_RIGHT, "up" to KeyCode.ARROW_UP, "down" to KeyCode.ARROW_DOWN,
        // the Unexpected theme's actions (assets/unexpected_kb/layout.json)
        "insert" to KeyCode.INSERT, "home" to KeyCode.MOVE_START_OF_LINE, "end" to KeyCode.MOVE_END_OF_LINE,
        "pgup" to KeyCode.PAGE_UP, "pgdn" to KeyCode.PAGE_DOWN, "fdel" to KeyCode.FORWARD_DELETE,
        "clipboard" to KeyCode.CLIPBOARD, "symbols" to KeyCode.SYMBOL, "emoji" to KeyCode.EMOJI, "settings" to KeyCode.SETTINGS,
        "voice" to KeyCode.VOICE_INPUT, "ime_picker" to KeyCode.SYSTEM_INPUT_METHOD_PICKER, "select" to KeyCode.CLIPBOARD_SELECT_WORD,
        "sel_left" to KeyCode.SELECT_LEFT, "sel_right" to KeyCode.SELECT_RIGHT, "sel_up" to KeyCode.SELECT_UP, "sel_down" to KeyCode.SELECT_DOWN,
        "f1" to KeyCode.F1, "f2" to KeyCode.F2, "f3" to KeyCode.F3, "f4" to KeyCode.F4, "f5" to KeyCode.F5, "f6" to KeyCode.F6,
        "f7" to KeyCode.F7, "f8" to KeyCode.F8, "f9" to KeyCode.F9, "f10" to KeyCode.F10, "f11" to KeyCode.F11, "f12" to KeyCode.F12,
    )

    /** key label -> [NW, NE, SW, SE]; "" = no action. Mirrored by the popups of unexpected.json (a test pins it). */
    val corners: Map<String, List<String>> = linkedMapOf(
        "q" to listOf("esc", "1", "tab", "`"),
        "w" to listOf("~", "2", "", "@"),
        "e" to listOf("!", "3", "", "#"),
        "r" to listOf("", "4", "", "$"),
        "t" to listOf("", "5", "", "%"),
        "y" to listOf("", "6", "", "^"),
        "u" to listOf("", "7", "", "&"),
        "i" to listOf("", "8", "", "*"),
        "o" to listOf("", "9", "(", ")"),
        "p" to listOf("", "0", "-", "="),
        "a" to listOf("ctrl", "alt", "", "\\"),
        "s" to listOf("", "", "-", "["),
        "d" to listOf("", "", "", "]"),
        "f" to listOf("", "", "", "{"),
        "g" to listOf("G", "", "", "}"),
        "h" to listOf("", "+", "left", ""),
        "j" to listOf("", "_", "down", ""),
        "k" to listOf("", "|", "up", ""),
        "l" to listOf("'", ";", "right", ":"),
        "z" to listOf("", "", "", "<"),
        "x" to listOf("", "", "", ">"),
        "c" to listOf("", "", "", "/"),
        "v" to listOf("", "", "", "?"),
        "b" to listOf("", "", "", "\""),
        "n" to listOf("", "", "", ","),
        "m" to listOf("", "", "", "."),
    )

    /** corner for a move of (dx, dy) in screen coordinates (y grows downwards), or -1 if the move is too short. */
    @JvmStatic
    fun cornerOf(dx: Int, dy: Int, minDistance: Float): Int {
        if (hypot(dx.toFloat(), dy.toFloat()) < minDistance) return -1
        return if (dy < 0) (if (dx < 0) NW else NE) else (if (dx < 0) SW else SE)
    }

    @JvmStatic
    fun codeOf(action: String): Int = named[action] ?: action.codePointAt(0)

    /** code to send for a swipe of (dx, dy) on the key labelled [keyLabel], or null for none (then the tap stands). */
    @JvmStatic
    fun codeFor(keyLabel: String?, dx: Int, dy: Int, minDistance: Float): Int? {
        val actions = corners[keyLabel?.lowercase() ?: return null] ?: return null
        val corner = cornerOf(dx, dy, minDistance)
        if (corner < 0) return null
        return actions[corner].takeIf { it.isNotEmpty() }?.let { codeOf(it) }
    }

    // ---- the Unexpected theme's table (data: assets/unexpected_kb/layout.json) -------------------------------------------

    /** Table id of [key]'s label / code: the Unexpected key table while that theme is on, else the Dev [corners] row. */
    @JvmStatic
    fun idOf(label: String?, code: Int): String? =
        if (UnexpectedKb.active) UnexpectedKb.idOf(label, code)
        else label?.lowercase()?.takeIf { it in corners }

    /** Eight display labels (NW NE SW SE N S W E) of the corner legends to draw for [id], or null for none. */
    @JvmStatic
    fun legendsOf(id: String?): List<String>? {
        if (id == null) return null
        if (UnexpectedKb.active) return UnexpectedKb.labelsOf(id).takeIf { it.isNotEmpty() }
        return corners[id]?.map { if (it.isEmpty()) "" else labelOf(it) }?.let { it + List(4) { "" } }
    }

    /** True for the compass key: 8-way swipes, no tap, repeats while held. */
    @JvmStatic
    fun isCompass(id: String?): Boolean = UnexpectedKb.active && UnexpectedKb.keyDef(id)?.id == "compass"

    /** Code to send for a swipe of (dx, dy) on table key [id], or null (then the tap stands). */
    @JvmStatic
    fun codeForId(id: String?, dx: Int, dy: Int, minDistance: Float): Int? {
        if (id == null) return null
        if (!UnexpectedKb.active) return codeFor(id, dx, dy, minDistance)
        val slot = UnexpectedKb.slotOf(dx, dy, minDistance, isCompass(id))
        if (slot < 0) return null
        val action = UnexpectedKb.keyDef(id)?.corners?.getOrNull(slot)?.action
        return action?.takeIf { it.isNotEmpty() }?.let { codeOf(it) }
    }

    /** Centre legend (e.g. the space bar's glyph) of table key [id], or null. */
    @JvmStatic
    fun centerLegendOf(id: String?): String? = if (UnexpectedKb.active) UnexpectedKb.keyDef(id)?.centerLegend else null
}
