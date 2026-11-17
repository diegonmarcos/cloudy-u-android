// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

/**
 * The ONE model of what the input view is showing (#843). Cloud Keyboard addition, not upstream.
 *
 * WHY THIS EXISTS. Which of the input view's pieces is visible used to be decided by whichever
 * method ran last: KeyboardSwitcher.setMainKeyboardFrame / setEmojiKeyboard /
 * setClipboardKeyboard / onToggleKeyboard each wrote its own list of setVisibility calls,
 * "which panel is open" was answered by asking a View whether it isShown(), the clipboard
 * panel built its close key ONCE in its constructor from the settings of that instant, and the
 * #776 toolbar self-heal ran on every start without knowing a panel was open. A config change
 * while the keyguard was locked (SettingsValues: toolbar HIDDEN, secondary strip hidden)
 * re-inflated a clipboard panel with ZERO strip keys - no CLOSE_HISTORY, for the life of that
 * input view - and #776 then healed the toolbar row back, making the clipboard key reachable
 * again and dropping the user into a panel that had lost its way out.
 *
 * Now: KeyboardSwitcher holds one [KeyboardViewState]; every transition changes [mode] here
 * and then calls its single applyViewState(), which renders [plan] onto the views. Nothing else
 * may toggle those views (test-keyboard-view-state-exit.sh enforces it). The invariant, checked
 * by KeyboardViewStateTest for every mode and every settings combination:
 *
 *     every non-typing state renders at least one visible, working exit
 *     (the panel's close key and/or the bottom-row ABC key), and system back
 *     and the panel's own toolbar key both lead back to [Mode.Typing].
 *
 * Pure Kotlin on purpose (no android.* imports): the whole decision is JVM-testable.
 */
class KeyboardViewState {

    /** AUTOCOMPLETE (#1103) is its own panel: it shares the clipboard's strip and bottom row, nothing of its content. */
    enum class PanelKind { EMOJI, CLIPBOARD, AUTOCOMPLETE, HANDWRITE }

    sealed class Mode {
        object Typing : Mode() { override fun toString() = "TYPING" }
        data class Panel(val kind: PanelKind) : Mode() { override fun toString() = "PANEL($kind)" }
    }

    /** The views applyViewState() owns. Nothing outside it may change their visibility. */
    enum class Piece {
        MAIN_FRAME, KEYBOARD_WRAPPER, MAIN_KEYBOARD, STRIP_CONTAINER, SUGGESTION_STRIP,
        EMOJI_TAB_STRIP, CLIPBOARD_STRIP, EMOJI_PALETTES, CLIPBOARD_HISTORY, AUTOCOMPLETE_VIEW, HANDWRITE_VIEW,
    }

    enum class Exit {
        /** CLOSE_HISTORY in the clipboard strip (guaranteed by [clipboardStripKeys]). */
        PANEL_CLOSE_KEY,
        /** The ABC key of the panel's own bottom row (clip_bottom_row / emoji_bottom_row). */
        BOTTOM_ROW_ALPHA,
    }

    /** What the views depend on besides the mode: read fresh from the settings at every render. */
    data class Inputs(
        val stripAvailable: Boolean,        // LatinIME.hasSuggestionStripView()
        val secondaryStripVisible: Boolean, // SettingsValues.mSecondaryStripVisible (false while locked)
        val imeSuppressed: Boolean,         // hardware keyboard hides the soft one
        val showToolbarOnly: Boolean,       // Settings.readShowToolbarOnly()
    )

    data class Plan(val mode: Mode, val visible: Set<Piece>) {
        fun shows(piece: Piece) = piece in visible

        /** The exits the user can actually SEE in this plan - derived from visibility, never declared. */
        val exits: Set<Exit> get() {
            val panel = mode as? Mode.Panel ?: return emptySet()
            val out = HashSet<Exit>()
            val panelView = when (panel.kind) {
                PanelKind.CLIPBOARD -> Piece.CLIPBOARD_HISTORY
                PanelKind.AUTOCOMPLETE -> Piece.AUTOCOMPLETE_VIEW
                PanelKind.EMOJI -> Piece.EMOJI_PALETTES
                PanelKind.HANDWRITE -> Piece.HANDWRITE_VIEW
            }
            if (shows(Piece.MAIN_FRAME) && shows(Piece.KEYBOARD_WRAPPER) && shows(panelView))
                out.add(Exit.BOTTOM_ROW_ALPHA)
            if (panel.kind != PanelKind.EMOJI && panel.kind != PanelKind.HANDWRITE && shows(Piece.STRIP_CONTAINER) && shows(Piece.CLIPBOARD_STRIP))
                out.add(Exit.PANEL_CLOSE_KEY)
            return out
        }
    }

    var mode: Mode = Mode.Typing
        private set
    var imeSuppressed = false
        private set
    private var deferredToolbarCheck: String? = null
    /** Emoji panel sub-state: the category strip is hidden while the Sticker/GIF body is up. */
    var emojiCategoryStrip = true
        private set

    val isPanel get() = mode is Mode.Panel
    fun isPanel(kind: PanelKind) = mode == Mode.Panel(kind)

    fun openPanel(kind: PanelKind) { mode = Mode.Panel(kind); emojiCategoryStrip = true }

    fun showEmojiCategoryStrip(shown: Boolean) { emojiCategoryStrip = shown }

    /** Back to typing. Returns the toolbar check deferred while a panel was open, to run now. */
    fun toTyping(suppressed: Boolean): String? {
        mode = Mode.Typing
        imeSuppressed = suppressed
        return deferredToolbarCheck.also { deferredToolbarCheck = null }
    }

    /** What the panel's own toolbar key does: opens it, or - when it is the open one - closes it. */
    fun toggleTarget(kind: PanelKind): Mode = if (isPanel(kind)) Mode.Typing else Mode.Panel(kind)

    /** System back: consumed (and leads to typing) exactly when a panel is open. */
    fun consumesBack() = isPanel

    /** Fresh views from the XML are the typing layout; the model must say the same. */
    fun onInputViewRecreated() { mode = Mode.Typing; deferredToolbarCheck = null }

    /**
     * The #776 toolbar self-heal asks here first. While a panel is open the toolbar row is not
     * on screen, so it is deferred to the moment the panel closes instead of rebuilding Views
     * underneath a panel. Returns true when deferred.
     */
    fun deferToolbarCheck(trigger: String): Boolean {
        if (!isPanel) return false
        deferredToolbarCheck = trigger
        return true
    }

    fun plan(inputs: Inputs): Plan = plan(mode, imeSuppressed, inputs, emojiCategoryStrip)

    companion object {
        fun plan(mode: Mode, imeSuppressed: Boolean, inputs: Inputs, emojiCategoryStrip: Boolean = true): Plan {
            val v = HashSet<Piece>()
            when (mode) {
                Mode.Typing -> {
                    if (!imeSuppressed) { v.add(Piece.MAIN_FRAME); v.add(Piece.MAIN_KEYBOARD) }
                    if (!inputs.showToolbarOnly) v.add(Piece.KEYBOARD_WRAPPER)
                    if (inputs.stripAvailable) { v.add(Piece.STRIP_CONTAINER); v.add(Piece.SUGGESTION_STRIP) }
                }
                is Mode.Panel -> {
                    // A panel lives INSIDE the wrapper: "show toolbar only" must not hide it with its exit.
                    v.add(Piece.MAIN_FRAME); v.add(Piece.KEYBOARD_WRAPPER)
                    when (mode.kind) {
                        PanelKind.EMOJI -> {
                            v.add(Piece.EMOJI_PALETTES)
                            if (emojiCategoryStrip) v.add(Piece.EMOJI_TAB_STRIP)
                            if (inputs.secondaryStripVisible) v.add(Piece.STRIP_CONTAINER)
                        }
                        PanelKind.CLIPBOARD -> {
                            v.add(Piece.CLIPBOARD_HISTORY)
                            v.add(Piece.CLIPBOARD_STRIP)
                            // Always: the strip carries CLOSE_HISTORY even when the user (or the
                            // keyguard) hid the secondary strip - then it carries ONLY that key.
                            v.add(Piece.STRIP_CONTAINER)
                        }
                        PanelKind.AUTOCOMPLETE -> {
                            // #1103: its own view, on the clipboard's strip (which carries the close key) and bottom row
                            v.add(Piece.AUTOCOMPLETE_VIEW)
                            v.add(Piece.CLIPBOARD_STRIP)
                            v.add(Piece.STRIP_CONTAINER)
                        }
                        // #1781: the HandWrite panel carries its own candidate row and bottom row (ABC, language, space, backspace)
                        PanelKind.HANDWRITE -> v.add(Piece.HANDWRITE_VIEW)
                    }
                }
            }
            return Plan(mode, v)
        }

        /**
         * The clipboard strip's keys, built at every open from the CURRENT settings (never once at
         * construction). [close] is always present, last; with the secondary strip hidden it is alone.
         */
        fun <K> clipboardStripKeys(enabled: List<K>, secondaryStripVisible: Boolean, close: K): List<K> =
            if (!secondaryStripVisible) listOf(close)
            else enabled.filter { it != close } + close
    }
}
