// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.content.edit
import androidx.core.view.forEach
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.GrammarMode
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.EnumMap
import java.util.Locale

/** The icon the toolbar draws for [key]; null when the icon set has none, which makes the key unbuildable. */
fun toolbarKeyIcon(context: Context, key: ToolbarKey): Drawable? =
    KeyboardIconsSet.instance.getNewDrawable(key.name, context)

/**
 * #1103: the Grammar mode icon has one look per mode, so the state is readable at a glance: Auto Correct is the
 * A with a check, Highlight the A with a wavy underline, Off the A struck through. Pure, so a test pins it.
 */
fun grammarIconRes(mode: GrammarMode): Int = when (mode) {
    GrammarMode.AUTO_CORRECT -> R.drawable.ic_toolbar_grammar_auto
    GrammarMode.HIGHLIGHT -> R.drawable.ic_toolbar_grammar_highlight
    GrammarMode.OFF -> R.drawable.ic_toolbar_grammar_off
}

/** The icon the toolbar draws for [key] right now: the state-dependent Grammar icon, else [toolbarKeyIcon]. */
fun toolbarKeyIconNow(context: Context, key: ToolbarKey): Drawable? =
    if (key == GRAMMAR) androidx.core.content.ContextCompat.getDrawable(context, grammarIconRes(GrammarMode.read(context.prefs())))?.mutate()
    else toolbarKeyIcon(context, key)

fun toolbarKeyDescription(context: Context, key: ToolbarKey): String =
    key.name.lowercase().getStringResourceOrName("", context)

/**
 * A toolbar key as a View: what the clipboard panel's strip (a row of Views in the strip container)
 * is built from. The suggestion strip draws its keys itself, from [toolbarKeyIcon].
 */
fun createToolbarKey(context: Context, key: ToolbarKey): ImageButton {
    val button = ImageButton(context, null, R.attr.suggestionWordStyle)
    button.scaleType = ImageView.ScaleType.CENTER
    button.tag = key
    button.contentDescription = toolbarKeyDescription(context, key)
    button.isActivated = isToolbarKeyActivated(context, key)
    button.setImageDrawable(toolbarKeyIconNow(context, key))
    return button
}

/**
 * Settings need to be re-read when these keys change; [refresh] then redraws the toolbar's
 * activated states (a toggle that is on is drawn in the full key colour, one that is off dimmed).
 */
fun setToolbarButtonsActivatedStateOnPrefChange(key: String?, refresh: () -> Unit) {
    // settings need to be updated when buttons change
    if (key != Settings.PREF_AUTO_CORRECTION
        && key != Settings.PREF_GRAMMAR_CHECK_MODE
        && key != Settings.PREF_ALWAYS_INCOGNITO_MODE
        && key != GestureDataGatheringSettings.PREF_BACKGROUND_GATHERING_ENABLED
        && key != GestureDataGatheringSettings.PREF_BACKGROUND_DISABLED_BEFORE_TIME_MILLIS
        && key?.startsWith(Settings.PREF_ONE_HANDED_MODE_PREFIX) == false)
        return

    // On Main: the toolbar reads this state while it draws. GlobalScope's default dispatcher ran
    // this on a worker thread, touching state the UI thread was drawing at the same time (#776).
    GlobalScope.launch(Dispatchers.Main) {
        delay(10) // need to wait until SettingsValues are reloaded
        refresh()
    }
}

/** The clipboard strip's View keys follow the same preferences. */
fun setToolbarButtonsActivatedStateOnPrefChange(buttonsGroup: ViewGroup, key: String?) {
    setToolbarButtonsActivatedStateOnPrefChange(key) {
        buttonsGroup.forEach { button ->
            if (button is ImageButton) (button.tag as? ToolbarKey)?.let { button.isActivated = isToolbarKeyActivated(button.context, it) }
        }
    }
}

/** Whether [key] is a toggle that is currently on (drawn in the full key colour). */
fun isToolbarKeyActivated(context: Context, key: ToolbarKey): Boolean = when (key) {
    INCOGNITO -> context.prefs().getBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, Defaults.PREF_ALWAYS_INCOGNITO_MODE)
    ONE_HANDED -> Settings.getValues().mOneHandedModeEnabled
    SPLIT -> Settings.getValues().mIsSplitKeyboardEnabled
    GRAMMAR -> GrammarMode.read(context.prefs()).checksGrammar // #1077: lit unless the mode is Off
    BACKGROUND_GATHERING -> useBackgroundGathering
    else -> true
}

fun getCodeForToolbarKey(key: ToolbarKey) = Settings.getInstance().getCustomToolbarKeyCode(key) ?: when (key) {
    VOICE -> KeyCode.VOICE_INPUT
    CLIPBOARD -> KeyCode.CLIPBOARD
    NUMPAD -> KeyCode.NUMPAD
    UNDO -> KeyCode.UNDO
    REDO -> KeyCode.REDO
    SETTINGS -> KeyCode.SETTINGS
    SELECT_ALL -> KeyCode.CLIPBOARD_SELECT_ALL
    SELECT_WORD -> KeyCode.CLIPBOARD_SELECT_WORD
    COPY -> KeyCode.CLIPBOARD_COPY
    CUT -> KeyCode.CLIPBOARD_CUT
    PASTE -> KeyCode.CLIPBOARD_PASTE
    ONE_HANDED -> KeyCode.TOGGLE_ONE_HANDED_MODE
    INCOGNITO -> KeyCode.TOGGLE_INCOGNITO_MODE
    CLEAR_CLIPBOARD -> KeyCode.CLIPBOARD_CLEAR_HISTORY
    CLOSE_HISTORY -> KeyCode.ALPHA
    EMOJI -> KeyCode.EMOJI
    LEFT -> KeyCode.ARROW_LEFT
    RIGHT -> KeyCode.ARROW_RIGHT
    UP -> KeyCode.ARROW_UP
    DOWN -> KeyCode.ARROW_DOWN
    WORD_LEFT -> KeyCode.WORD_LEFT
    WORD_RIGHT -> KeyCode.WORD_RIGHT
    PAGE_UP -> KeyCode.PAGE_UP
    PAGE_DOWN -> KeyCode.PAGE_DOWN
    FULL_LEFT -> KeyCode.MOVE_START_OF_LINE
    FULL_RIGHT -> KeyCode.MOVE_END_OF_LINE
    PAGE_START -> KeyCode.MOVE_START_OF_PAGE
    PAGE_END -> KeyCode.MOVE_END_OF_PAGE
    SPLIT -> KeyCode.SPLIT_LAYOUT
    FLOATING -> KeyCode.TOGGLE_FLOATING_WINDOW
    BACKGROUND_GATHERING -> KeyCode.BACKGROUND_GATHERING
    TRANSLATE -> KeyCode.TRANSLATE // SuperApp addition (patch 0001)
    GRAMMAR -> KeyCode.GRAMMAR_MODE // #1077: Grammar mode, tap cycles Highlight / Auto Correct / Off
    AUTOCOMPLETE -> KeyCode.AUTOCOMPLETE // #1077
    ENHANCE -> KeyCode.ENHANCE_BAR // SuperApp addition — Text Enhancements: tap opens the bar
    ANSWER -> KeyCode.ANSWER_BAR // #1384 Draft Answer: tap opens the Enhance panel in reply mode
    LANGUAGE_SWITCH -> KeyCode.LANGUAGE_SWITCH // cloud-keyboard: same code the globe key emits → InputLogic → LatinIME.switchToNextSubtype
    VAULT -> KeyCode.VAULT // cloud-keyboard: launches Cloud Vault
    ANDROID_SETTINGS -> KeyCode.ANDROID_IME_SETTINGS // #1103: the system's input-method settings
}

fun getCodeForToolbarKeyLongClick(key: ToolbarKey) = Settings.getInstance().getCustomToolbarLongpressCode(key) ?: when (key) {
    CLIPBOARD -> KeyCode.CLIPBOARD_PASTE
    UNDO -> KeyCode.REDO
    REDO -> KeyCode.UNDO
    SELECT_ALL -> KeyCode.CLIPBOARD_SELECT_WORD
    SELECT_WORD -> KeyCode.CLIPBOARD_SELECT_ALL
    COPY -> KeyCode.CLIPBOARD_CUT
    PASTE -> KeyCode.CLIPBOARD
    LEFT -> KeyCode.WORD_LEFT
    RIGHT -> KeyCode.WORD_RIGHT
    UP -> KeyCode.PAGE_UP
    DOWN -> KeyCode.PAGE_DOWN
    WORD_LEFT -> KeyCode.MOVE_START_OF_LINE
    WORD_RIGHT -> KeyCode.MOVE_END_OF_LINE
    PAGE_UP -> KeyCode.MOVE_START_OF_PAGE
    PAGE_DOWN -> KeyCode.MOVE_END_OF_PAGE
    BACKGROUND_GATHERING -> KeyCode.BACKGROUND_GATHERING_TEMP_OFF
    TRANSLATE -> KeyCode.TRANSLATE_BAR // SuperApp addition (patch 0001) — long-press opens the live translate bar
    GRAMMAR -> KeyCode.GRAMMAR         // #1077: long-press runs the whole-field check (what tap used to do)
    ENHANCE -> KeyCode.ENHANCE         // SuperApp addition — long-press rewrites in place, no bar
    LANGUAGE_SWITCH -> KeyCode.SYSTEM_INPUT_METHOD_PICKER // cloud-keyboard: globe long-press = picker (PointerTracker → showInputPickerDialog); same dialog
    else -> KeyCode.UNSPECIFIED
}

// names need to be aligned with resources strings (using lowercase of key.name)
enum class ToolbarKey {
    VOICE, CLIPBOARD, NUMPAD, UNDO, REDO, SETTINGS, SELECT_ALL, SELECT_WORD, COPY, CUT, PASTE, ONE_HANDED, FLOATING, SPLIT,
    INCOGNITO, CLEAR_CLIPBOARD, CLOSE_HISTORY, EMOJI, LEFT, RIGHT, UP, DOWN, WORD_LEFT, WORD_RIGHT,
    PAGE_UP, PAGE_DOWN, FULL_LEFT, FULL_RIGHT, PAGE_START, PAGE_END, BACKGROUND_GATHERING,
    TRANSLATE, // SuperApp addition (patch 0001) — on-device ML Kit translate to active subtype language
    GRAMMAR,   // #1077: "Grammar mode" - Auto Correction and Grammar Check merged; tap cycles the mode, long-press checks the field
    AUTOCOMPLETE, // #1077: opens the list of Cloud Account / Cloud Vault autocomplete fields (tap pastes, long-press copies)
    ENHANCE,   // SuperApp addition — Text Enhancements: rewrite selection/field via AI Model Routing
    LANGUAGE_SWITCH, // cloud-keyboard: toolbar twin of the globe key (tap = next subtype/IME per "Language switch key behavior", long-press = picker). In the default first row.
    VAULT, // cloud-keyboard: opens Cloud Vault.
    ANDROID_SETTINGS, // #1103: shortcut to Android's keyboard (input method) settings; sits right before VAULT in the default row
    ANSWER // #1384: Draft Answer, a reply to the newest clipboard item, drafted by the Text Enhancement model
}

/**
 * Keys that must start a page of their own. [ToolbarPaging.gaps] gives such a key a left gap sized from the page
 * width it measured, so it starts a page at any screen width, icon size or rotation — the page count is measured,
 * never a constant. #1103: none today, Vault now sits right after "Switch language" in the flow of the row; the
 * mechanism stays declared here for the next destination key.
 */
val toolbarKeysOnOwnPage: Set<ToolbarKey> = emptySet()

enum class ToolbarMode {
    EXPANDABLE, TOOLBAR_KEYS, SUGGESTION_STRIP, HIDDEN,
}

val toolbarKeyStrings = entries.associateWithTo(EnumMap(ToolbarKey::class.java)) { it.toString().lowercase(Locale.US) }

// SuperApp default FIRST ROW (the scrolling toolbar), left -> right. Writing tools
// come first because they are what the toolbar is for; the caret and clipboard keys
// trail behind and are reached by scrolling. FULL_LEFT/FULL_RIGHT map to
// MOVE_START/END_OF_LINE. SETTINGS (Config) is intentionally NOT here — it is the
// whole of the SECOND ROW below, so it never scrolls out of reach.
// NOTE: only fresh installs get this list; upgradeToolbarPref appends a NEW key
// as disabled for users who already customised their toolbar (upstream contract).
// A change of THIS order reaches existing phones only via TOOLBAR_LAYOUT_REVISION.
val defaultToolbarPref by lazy {
    val default = listOf(
        // #1103 page one: Autocomplete and Translate swapped, Clipboard and Numpad swapped; Android keyboard settings
        // and Cloud Vault follow "Switch language" (settings first, Vault right after it).
        // #1384: Draft Answer takes Emoji's place on page one; Emoji opens page two right after Vault.
        FULL_LEFT, GRAMMAR, TRANSLATE, ENHANCE, AUTOCOMPLETE, CLIPBOARD, NUMPAD, ANSWER, VOICE, LANGUAGE_SWITCH,
        ANDROID_SETTINGS, VAULT, EMOJI,
        FULL_RIGHT, SELECT_ALL, WORD_RIGHT, WORD_LEFT, REDO, UNDO, SELECT_WORD, COPY, PASTE, CUT, PAGE_START,
    )
    val others = entries.filterNot { it in default || it == CLOSE_HISTORY }
    default.joinToString(Separators.ENTRY) { it.name + Separators.KV + true } + Separators.ENTRY +
            others.joinToString(Separators.ENTRY) { it.name + Separators.KV + false }
}

// SuperApp default SECOND ROW: the fixed row that sits next to the suggestions and
// never scrolls. Config (SETTINGS) alone, so it is always one tap away whatever the
// first row is scrolled to. (Upstream calls this row "pinned keys".)
val defaultSecondRowToolbarPref by lazy {
    val secondRow = listOf(SETTINGS)
    val others = entries.filterNot { it in secondRow || it == CLOSE_HISTORY }
    secondRow.joinToString(Separators.ENTRY) { it.name + Separators.KV + true } + Separators.ENTRY +
            others.joinToString(Separators.ENTRY) { it.name + Separators.KV + false }
}

val defaultClipboardToolbarPref by lazy {
    val default = listOf(CLEAR_CLIPBOARD, UP, DOWN, LEFT, RIGHT, UNDO, CUT, COPY, PASTE, SELECT_WORD, CLOSE_HISTORY)
    val others = entries.filterNot { it in default }
    default.joinToString(Separators.ENTRY) { it.name + Separators.KV + true } + Separators.ENTRY +
            others.joinToString(Separators.ENTRY) { it.name + Separators.KV + false }
}

/**
 * Bump this when the default rows above are reorganised and the new arrangement should reach
 * phones that already have the old one stored. [upgradeToolbarPref] only ever APPENDS keys that
 * the stored value does not know yet, so without this a reordered default would be visible to
 * fresh installs only — the exact trap that made earlier default changes look like no-ops.
 */
const val TOOLBAR_LAYOUT_REVISION = 5 // 5: #1384 Draft Answer on page one, Emoji to page two; 3: #1077 Grammar mode + Autocomplete replace Grammar + Auto Correction; 4: #1103 reorder + Android settings key

/**
 * Adopt the current default first and second rows, once per [TOOLBAR_LAYOUT_REVISION]. This
 * DISCARDS a hand-made arrangement, which is the point: the revision only moves when the shipped
 * layout is meant to win.
 */
fun adoptToolbarLayoutRevision(prefs: SharedPreferences) {
    if (prefs.getInt(Settings.PREF_TOOLBAR_LAYOUT_REVISION, 0) >= TOOLBAR_LAYOUT_REVISION) return
    prefs.edit {
        putString(Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref)
        putString(Settings.PREF_SECOND_ROW_TOOLBAR_KEYS, defaultSecondRowToolbarPref)
        putInt(Settings.PREF_TOOLBAR_LAYOUT_REVISION, TOOLBAR_LAYOUT_REVISION)
    }
}

/** True while the stored rows are older than the shipped arrangement ([TOOLBAR_LAYOUT_REVISION]). */
fun toolbarLayoutRevisionPending(prefs: SharedPreferences): Boolean =
    prefs.getInt(Settings.PREF_TOOLBAR_LAYOUT_REVISION, 0) < TOOLBAR_LAYOUT_REVISION

/** add missing keys, typically because a new key has been added */
fun upgradeToolbarPrefs(prefs: SharedPreferences) {
    adoptToolbarLayoutRevision(prefs)
    upgradeToolbarPref(prefs, Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref)
    upgradeToolbarPref(prefs, Settings.PREF_SECOND_ROW_TOOLBAR_KEYS, defaultSecondRowToolbarPref)
    upgradeToolbarPref(prefs, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS, defaultClipboardToolbarPref)
}

private fun upgradeToolbarPref(prefs: SharedPreferences, pref: String, default: String) {
    if (!prefs.contains(pref)) return
    val list = prefs.getString(pref, default)!!.split(Separators.ENTRY).toMutableList()
    // The row's OWN default, not the first row's: the clipboard row's CLOSE_HISTORY is absent
    // from the first-row default, so it was never appended to an older stored clipboard row.
    val splitDefault = default.split(Separators.ENTRY)
    splitDefault.forEach { entry ->
        val keyWithSeparator = entry.substringBefore(Separators.KV) + Separators.KV
        if (list.none { it.startsWith(keyWithSeparator) })
            list.add("${keyWithSeparator}false")
    }
    // likely not needed, but better prepare for possibility of key removal
    list.removeAll {
        try {
            ToolbarKey.valueOf(it.substringBefore(Separators.KV))
            false
        } catch (_: IllegalArgumentException) {
            true
        }
    }
    prefs.edit { putString(pref, list.joinToString(Separators.ENTRY)) }
}

fun getEnabledToolbarKeys(prefs: SharedPreferences) = getEnabledToolbarKeys(prefs, Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref)

fun getSecondRowToolbarKeys(prefs: SharedPreferences) = getEnabledToolbarKeys(prefs, Settings.PREF_SECOND_ROW_TOOLBAR_KEYS, defaultSecondRowToolbarPref)

fun getEnabledClipboardToolbarKeys(prefs: SharedPreferences) = getEnabledToolbarKeys(prefs, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS, defaultClipboardToolbarPref)

fun addSecondRowKey(prefs: SharedPreferences, key: ToolbarKey) {
    // remove the existing version of this key and add the enabled one after the last currently enabled key
    val string = prefs.getString(Settings.PREF_SECOND_ROW_TOOLBAR_KEYS, defaultSecondRowToolbarPref)!!
    val keys = string.split(Separators.ENTRY).toMutableList()
    keys.removeAll { it.startsWith(key.name + Separators.KV) }
    val lastEnabledIndex = keys.indexOfLast { it.endsWith("true") }
    keys.add(lastEnabledIndex + 1, key.name + Separators.KV + "true")
    prefs.edit { putString(Settings.PREF_SECOND_ROW_TOOLBAR_KEYS, keys.joinToString(Separators.ENTRY)) }
}

/**
 * SuperApp addition: flip one key of the (non-pinned) toolbar list without touching its
 * order. Settings → Text Enhancements uses it for its "Show in toolbar" switch, so that
 * switch and Settings → Toolbar edit the same pref instead of a shadow boolean.
 */
fun setToolbarKeyEnabled(prefs: SharedPreferences, key: ToolbarKey, enabled: Boolean) {
    val string = prefs.getString(Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref)!!
    val keys = string.split(Separators.ENTRY).toMutableList()
    val entry = key.name + Separators.KV + enabled
    val i = keys.indexOfFirst { it.startsWith(key.name + Separators.KV) }
    if (i >= 0) keys[i] = entry else keys.add(entry)
    prefs.edit { putString(Settings.PREF_TOOLBAR_KEYS, keys.joinToString(Separators.ENTRY)) }
}

fun removeSecondRowKey(prefs: SharedPreferences, key: ToolbarKey) {
    // just set it to disabled
    val string = prefs.getString(Settings.PREF_SECOND_ROW_TOOLBAR_KEYS, defaultSecondRowToolbarPref)!!
    val result = string.split(Separators.ENTRY).joinToString(Separators.ENTRY) {
        if (it.startsWith(key.name + Separators.KV))
            key.name + Separators.KV + "false"
        else it
    }
    prefs.edit { putString(Settings.PREF_SECOND_ROW_TOOLBAR_KEYS, result) }
}

private fun getEnabledToolbarKeys(prefs: SharedPreferences, pref: String, default: String): List<ToolbarKey> {
    val string = prefs.getString(pref, default)!!
    return string.split(Separators.ENTRY).mapNotNull {
        val split = it.split(Separators.KV)
        if (split.last() == "true") {
            try {
                ToolbarKey.valueOf(split.first())
            } catch (_: IllegalArgumentException) {
                null
            }
        } else null
    }
}

fun writeCustomKeyCodes(prefs: SharedPreferences, codes: EnumMap<ToolbarKey, Pair<Int?, Int?>>) {
    val string = codes.mapNotNull { entry -> entry.value?.let { "${entry.key.name},${it.first},${it.second}" } }.joinToString(";")
    prefs.edit { putString(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, string) }
}

fun readCustomKeyCodes(prefs: SharedPreferences): EnumMap<ToolbarKey, Pair<Int?, Int?>> {
    val map = EnumMap<ToolbarKey, Pair<Int?, Int?>>(ToolbarKey::class.java)
    prefs.getString(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, Defaults.PREF_TOOLBAR_CUSTOM_KEY_CODES)!!
        .split(";").forEach {
            runCatching {
                val s = it.split(",")
                map[ToolbarKey.valueOf(s[0])] = s[1].toIntOrNull() to s[2].toIntOrNull()
            }
        }
    return map
}

fun getCustomKeyCode(key: ToolbarKey, prefs: SharedPreferences): Int? {
    if (customToolbarKeyCodes == null)
        customToolbarKeyCodes = readCustomKeyCodes(prefs)
    return customToolbarKeyCodes!![key]?.first
}

fun getCustomLongpressKeyCode(key: ToolbarKey, prefs: SharedPreferences): Int? {
    if (customToolbarKeyCodes == null)
        customToolbarKeyCodes = readCustomKeyCodes(prefs)
    return customToolbarKeyCodes!![key]?.second
}

fun clearCustomToolbarKeyCodes() {
    customToolbarKeyCodes = null
}

private var customToolbarKeyCodes: EnumMap<ToolbarKey, Pair<Int?, Int?>>? = null
