// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.FakePrefs
import helium314.keyboard.keyboard.CornerSwipe
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1665 every designed sub-page is its own page: Keyboard (Themes Default / Dev, Settings, Languages, Toolbar), Writer,
 * Mediaboards (Emojis, GIF, Stickers), Textboards (Autocomplete, Clipboard), Configs. No two nav entries draw the same screen,
 * every former All settings page has exactly one home, the Themes are Default and Dev and picking one switches the layout,
 * and no setting key was lost.
 */
class KeyboardNavPagesTest {
    private val nav get() = File("src/main/java/helium314/keyboard/settings/SettingsNavHost.kt").readText()
    private val screens = File("src/main/java/helium314/keyboard/settings/screens")
    private val app = File("../../../ac_cloud-keyboard")

    /** route constant value -> the statement its composable block draws (the screen call, with its arguments). */
    private fun drawnBy(): Map<String, String> {
        val consts = Regex("""const val (\w+) = "([^"]*)"""").findAll(nav).associate { it.groupValues[1] to it.groupValues[2] }
        return Regex("""composable\(SettingsDestination\.(\w+)(?: \+ "[^"]*")?\) \{[^\n]*\n\s*([^\n]+)""").findAll(nav)
            .associate { (consts[it.groupValues[1]] ?: it.groupValues[1]) to it.groupValues[2].trim() }
    }

    private val declared get() = KbTab.entries.flatMap { it.allPages }

    @Test fun noTwoNavEntriesShareARouteOrDrawTheSameScreen() {
        val routes = declared.map { it.route }
        assertEquals("a route is declared twice: " + routes.groupBy { it }.filterValues { it.size > 1 }.keys, routes.size, routes.toSet().size)
        val drawn = drawnBy()
        val local = routes.filterNot { it.startsWith("host/") }
        local.forEach { assertTrue("no screen for $it", drawn.containsKey(it)) }
        val stmts = local.map { drawn.getValue(it) }
        assertEquals("two routes draw the same screen: " + stmts.groupBy { it }.filterValues { it.size > 1 }.keys, stmts.size, stmts.toSet().size)
        // the Writer host pages are distinct cloud-writer page ids
        val host = routes.filter { it.startsWith("host/") }
        assertEquals(host.size, host.toSet().size)
    }

    @Test fun keyboardTabSubPagesAreDistinctPages() {
        val k = KbTab.KEYBOARD.pages.associateBy { it.label }
        assertEquals(listOf("Themes", "Settings", "Languages", "Toolbar"), KbTab.KEYBOARD.pages.map { it.label })
        assertEquals(SettingsDestination.Themes, k.getValue("Themes").route)
        assertEquals(SettingsDestination.KeyboardSettings, k.getValue("Settings").route)
        assertEquals(SettingsDestination.Languages, k.getValue("Languages").route)
        assertEquals(SettingsDestination.Toolbar, k.getValue("Toolbar").route)
        val themes = k.getValue("Themes").children
        assertEquals(listOf("Default", "Dev", "Unexpected"), themes.map { it.label })
        assertEquals(listOf(SettingsDestination.ThemeDefault, SettingsDestination.ThemeDev, SettingsDestination.ThemeUnexpected), themes.map { it.route })
        assertTrue(drawnBy().getValue(SettingsDestination.ThemeUnexpected).contains("ThemePageScreen(KbThemePreset.UNEXPECTED"))
        assertTrue(drawnBy().getValue(SettingsDestination.ThemeDefault).contains("ThemePageScreen(KbThemePreset.DEFAULT"))
        assertTrue(drawnBy().getValue(SettingsDestination.ThemeDev).contains("ThemePageScreen(KbThemePreset.DEV"))
        assertTrue(drawnBy().getValue(SettingsDestination.KeyboardSettings).contains("KeyboardSettingsScreen("))
    }

    @Test fun mediaboardsHasThreeOwnPagesAndTextboardsTwo() {
        val m = KbTab.MEDIABOARDS.pages
        assertEquals(listOf("Emojis", "GIF", "Stickers"), m.map { it.label })
        assertEquals(listOf(SettingsDestination.Emoji, SettingsDestination.Gif, SettingsDestination.Stickers), m.map { it.route })
        val d = drawnBy()
        assertTrue(d.getValue(SettingsDestination.Emoji).startsWith("EmojiInfoScreen("))
        assertTrue(d.getValue(SettingsDestination.Gif).startsWith("GifScreen("))
        assertTrue(d.getValue(SettingsDestination.Stickers).startsWith("StickersScreen("))
        val src = File(screens, "EmojiInfoScreen.kt").readText()
        assertTrue(src.contains("GifProvider.from(gif).fetch(")) // the GIF page runs a real search
        assertTrue(src.contains("StickerRepo.packs(ctx)")) // the Stickers page lists the real packs
        assertEquals(listOf("Clipboard"), KbTab.TEXTBOARDS.pages.map { it.label })
        assertEquals(listOf(SettingsDestination.Clipboard), KbTab.TEXTBOARDS.pages.map { it.route })
        // Autocomplete (with Text correction) moved under Writer > Configs Writer: the same screens, a new home
        val auto = KbTab.WRITER.allPages.first { it.id == "autocomplete" }
        assertEquals(SettingsDestination.Autocomplete, auto.route)
        assertEquals(listOf(SettingsDestination.TextCorrection), auto.children.map { it.route })
    }

    /** Rule 16: Default and Dev stay (labels and stored ids), and Unexpected is added after them with its own stored id. */
    @Test fun themesAreDefaultDevAndUnexpected_noneRemoved() {
        assertEquals(listOf("DEFAULT", "DEV", "UNEXPECTED"), KbThemePreset.entries.map { it.name })
        assertEquals(listOf("default", "unexpected", "unexpected_kb"), KbThemePreset.entries.map { it.id }) // Dev keeps its legacy stored id
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"kb_theme_dev\">Dev</string>"))
        assertTrue(strings.contains("<string name=\"kb_theme_default\">Default</string>"))
        assertTrue(strings.contains("<string name=\"kb_theme_unexpected\">Unexpected</string>"))
        val screen = File(screens, "ThemesScreen.kt").readText()
        assertTrue(screen.contains("KbThemePreset.entries.forEach")) // the picker draws every preset
    }

    @Test fun pickingATheme_switchesTheLayout() {
        val p = FakePrefs(mapOf("theme_colors" to "light"))
        assertEquals(KbThemePreset.DEFAULT, KbThemePreset.current(p))
        KbThemePreset.applyTo(p, KbThemePreset.DEV)
        assertEquals(KbThemePreset.DEV, KbThemePreset.current(p))
        assertEquals(true, p.data[CornerSwipe.PREF])
        assertEquals("unexpected", p.data["theme_colors"])
        assertEquals(CornerSwipe.LAYOUT, CornerSwipe.mainLayoutFor(p.getBoolean(CornerSwipe.PREF, false), true, "qwerty"))
        KbThemePreset.applyTo(p, KbThemePreset.DEFAULT)
        assertEquals(KbThemePreset.DEFAULT, KbThemePreset.current(p))
        assertEquals(false, p.data[CornerSwipe.PREF])
        assertEquals("light", p.data["theme_colors"]) // the user's own colours come back
        assertEquals("qwerty", CornerSwipe.mainLayoutFor(p.getBoolean(CornerSwipe.PREF, false), true, "qwerty"))
        // a phone that only had the Dev Keyboard flag on reads as the Dev theme
        assertEquals(KbThemePreset.DEV, KbThemePreset.current(FakePrefs(mapOf(CornerSwipe.PREF to true))))
        // the toolbar key and the Configs switch go through the theme, and the parser loads the layout from the flag
        assertTrue(File("src/main/java/helium314/keyboard/keyboard/CornerSwipe.kt").readText().contains("KbThemePreset.setDev(context, !enabled)"))
        assertTrue(File("src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/LayoutParser.kt").readText().contains("CornerSwipe.mainLayoutFor(CornerSwipe.enabled"))
        // the Unexpected theme is its own preset: dark colours, corner swipes and its own layout flag on, Default restores everything
        val u = FakePrefs(mapOf("theme_colors" to "light", "theme_style" to "Material", "toolbar_mode" to "TOOLBAR_KEYS"))
        KbThemePreset.applyTo(u, KbThemePreset.UNEXPECTED)
        assertEquals("HIDDEN", u.data["toolbar_mode"]) // the toolbar / clipboard strip is hidden so the rows get the design's height
        assertEquals("Rounded", u.data["theme_style"]) // rounded, bordered keys as designed
        assertEquals(true, u.data["theme_key_borders"])
        assertEquals(KbThemePreset.UNEXPECTED, KbThemePreset.current(u))
        assertEquals(true, u.data[CornerSwipe.PREF])
        assertEquals(true, u.data[helium314.keyboard.keyboard.UnexpectedKb.PREF])
        assertEquals("unexpected", u.data["theme_colors"])
        KbThemePreset.applyTo(u, KbThemePreset.DEV) // Dev keeps working from Unexpected
        assertEquals("TOOLBAR_KEYS", u.data["toolbar_mode"]) // and the user's own toolbar comes back
        assertEquals(false, u.data[helium314.keyboard.keyboard.UnexpectedKb.PREF])
        KbThemePreset.applyTo(u, KbThemePreset.DEFAULT)
        assertEquals("light", u.data["theme_colors"])
        assertEquals("Material", u.data["theme_style"]) // the user's own style comes back
        assertEquals(false, u.data[CornerSwipe.PREF])
        // the Dev theme always has its number row on letters and on symbols
        val sv = File("src/main/java/helium314/keyboard/latin/settings/SettingsValues.java").readText()
        assertTrue(sv.contains("mShowsNumberRow = !unexpectedTheme && (devTheme ||") && sv.contains("mShowsNumberRowInSymbols = devTheme ||"))
    }

    @Test fun everyFormerAllSettingsPageHasExactlyOneHome() {
        val ids = declared.map { it.id }.toSet()
        val chrome = File(app, "app/src/main/java/com/diegonmarcos/cloudkeyboard/KeyboardChrome.kt").let { if (it.exists()) it.readText() else null }
        AllSettingsPage.entries.forEach { page ->
            val (home, id) = page.home
            val tab = home.tab
            if (tab != null) {
                assertTrue("$page -> $home/$id", tab.allPages.any { it.id == id })
                KbTab.entries.filter { it != tab }.forEach { other -> assertFalse("$page also in $other", other.allPages.any { it.id == id }) }
            } else {
                assertTrue("$page -> Configs/$id", id in keyboardConfigsRows)
                assertFalse(id in ids)
                chrome?.let { assertTrue("Configs row $id drawn", it.contains("\"$id\" ->")) }
            }
        }
        assertEquals(AllSettingsPage.entries.size, AllSettingsPage.entries.map { it.home }.toSet().size)
        // the grouped All settings (#1650) stays reachable from Configs
        assertTrue("all_settings" in keyboardConfigsRows)
        assertEquals(SettingsDestination.Settings, SettingsDestination.externalRoute("all_settings"))
        listOf("about", "update").forEach { assertTrue(SettingsDestination.externalRoute(it) != null) }
    }

    /** Field parity: every setting key defined before #1665 is still defined; the new pages only show existing settings. */
    @Test fun everySettingKeyBeforeIsStillDefined() {
        val defined = screens.walkTopDown().filter { it.extension == "kt" }
            .flatMap { Regex("""Setting\(\s*context,\s*([A-Za-z_.]+(?:\([A-Za-z_.]*\))?)""").findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet()
        assertEquals(emptySet<String>(), before - defined)
        assertEquals(before, defined) // nothing added or renamed either: the new pages reuse settings
        listOf("KeyboardSettingsScreen.kt" to "keyboardSettingKeys", "EmojiInfoScreen.kt" to "emojiSettingKeys").forEach { (f, fn) ->
            val src = File(screens, f).readText()
            val body = src.substring(src.indexOf("fun $fn("), src.indexOf("\n)\n", src.indexOf("fun $fn(")))
            val used = Regex("""(Settings\.PREF_\w+|SettingsWithoutKey\.\w+)""").findAll(body).map { it.value }.toSet()
            assertTrue(used.isNotEmpty())
            used.forEach { assertTrue("$f uses $it, which no screen defines", it in defined) }
        }
    }

    @Test fun keyboardSettingsCoversTheThemeAndKeyboardSettings() {
        val src = File(screens, "KeyboardSettingsScreen.kt").readText()
        listOf("PREF_THEME_STYLE", "PREF_THEME_COLORS", "PREF_THEME_KEY_BORDERS", "PREF_KEYBOARD_HEIGHT_SCALE_PREFIX",
            "PREF_SHOW_NUMBER_ROW", "PREF_SHOW_NUMBER_ROW_IN_SYMBOLS", "PREF_ENABLE_SPLIT_KEYBOARD").forEach { assertTrue(it, src.contains("Settings.$it")) }
        listOf("Appearance", "Preferences", "GestureTyping", "DataGathering", "Advanced").forEach { assertTrue(it, src.contains("SettingsDestination.$it")) }
        assertNotEquals(SettingsDestination.Settings, SettingsDestination.KeyboardSettings)
    }

    private val before = setOf(
        "AiRoutingRows.CATALOG",
        "AiRoutingRows.ENHANCE_MODEL",
        "AiRoutingRows.table(AiTask.ENHANCE)",
        "AiRoutingRows.table(AiTask.GRAMMAR)",
        "AiRoutingRows.table(AiTask.TRANSLATE)",
        "AiTaskRouting.key(AiTask.GRAMMAR)",
        "AiTaskRouting.key(AiTask.TRANSLATE)",
        "DebugSettings.PREF_DEBUG_MODE",
        "DebugSettings.PREF_FORCE_NON_DISTINCT_MULTITOUCH",
        "DebugSettings.PREF_KEY_DUMP_DICT_PREFIX",
        "DebugSettings.PREF_SHOW_DEBUG_SETTINGS",
        "DebugSettings.PREF_SHOW_SUGGESTION_INFOS",
        "DebugSettings.PREF_SLIDING_KEY_INPUT_PREVIEW",
        "Settings.PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD",
        "Settings.PREF_ABC_AFTER_CLIP",
        "Settings.PREF_ABC_AFTER_EMOJI",
        "Settings.PREF_ABC_AFTER_NUMPAD_SPACE",
        "Settings.PREF_ABC_AFTER_SYMBOL_SPACE",
        "Settings.PREF_ADD_TO_PERSONAL_DICTIONARY",
        "Settings.PREF_AI_MODEL_PREFIX",
        "Settings.PREF_AI_PREAMBLE",
        "Settings.PREF_AI_PRICING_PREFIX",
        "Settings.PREF_AI_PROVIDER",
        "Settings.PREF_AI_TOKEN_PREFIX",
        "Settings.PREF_ALWAYS_INCOGNITO_MODE",
        "Settings.PREF_ALWAYS_SHOW_SUGGESTIONS",
        "Settings.PREF_ALWAYS_SHOW_SUGGESTIONS_EXCEPT_WEB_TEXT",
        "Settings.PREF_ANSWER_LANGUAGE",
        "Settings.PREF_ANSWER_LENGTH",
        "Settings.PREF_ANSWER_MODEL",
        "Settings.PREF_ANSWER_PROMPT",
        "Settings.PREF_ANSWER_TONE",
        "Settings.PREF_ANSWER_TOOLBAR_KEY",
        "Settings.PREF_AUTOCORRECT_SHORTCUTS",
        "Settings.PREF_AUTOSPACE_AFTER_GESTURE_TYPING",
        "Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION",
        "Settings.PREF_AUTOSPACE_AFTER_SUGGESTION",
        "Settings.PREF_AUTOSPACE_BEFORE_GESTURE_TYPING",
        "Settings.PREF_AUTO_CAP",
        "Settings.PREF_AUTO_CORRECT_CONFIDENCE",
        "Settings.PREF_AUTO_HIDE_TOOLBAR",
        "Settings.PREF_AUTO_SHOW_TOOLBAR",
        "Settings.PREF_BACKSPACE_REVERTS_AUTOCORRECT",
        "Settings.PREF_BIGRAM_PREDICTIONS",
        "Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE",
        "Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX",
        "Settings.PREF_BOTTOM_ROW_SCALE_PREFIX",
        "Settings.PREF_CENTER_SUGGESTION_TEXT_TO_ENTER",
        "Settings.PREF_CLIPBOARD_FILES_SIZE_LIMIT",
        "Settings.PREF_CLIPBOARD_FONT_SCALE",
        "Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST",
        "Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME",
        "Settings.PREF_CLIPBOARD_TOOLBAR_KEYS",
        "Settings.PREF_CLIPBOARD_USE_FILES",
        "Settings.PREF_CUSTOM_CURRENCY_KEY",
        "Settings.PREF_CUSTOM_ICON_NAMES",
        "Settings.PREF_DELETE_SWIPE",
        "Settings.PREF_EMOJI_FONT_SCALE",
        "Settings.PREF_EMOJI_KEY_FIT",
        "Settings.PREF_EMOJI_MAX_SDK",
        "Settings.PREF_EMOJI_SKIN_TONE",
        "Settings.PREF_ENABLE_CLIPBOARD_HISTORY",
        "Settings.PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY",
        "Settings.PREF_ENABLE_SPLIT_KEYBOARD",
        "Settings.PREF_ENHANCE_AUTO_PASTE",
        "Settings.PREF_ENHANCE_LANGUAGE",
        "Settings.PREF_ENHANCE_LENGTH",
        "Settings.PREF_ENHANCE_PROMPT",
        "Settings.PREF_ENHANCE_SCOPE",
        "Settings.PREF_ENHANCE_STYLE",
        "Settings.PREF_ENHANCE_TEST",
        "Settings.PREF_ENHANCE_TONE",
        "Settings.PREF_ENHANCE_TOOLBAR_KEY",
        "Settings.PREF_FONT_SCALE",
        "Settings.PREF_GESTURE_FAST_TYPING_COOLDOWN",
        "Settings.PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC",
        "Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT",
        "Settings.PREF_GESTURE_INPUT",
        "Settings.PREF_GESTURE_PREVIEW_TRAIL",
        "Settings.PREF_GESTURE_SPACE_AWARE",
        "Settings.PREF_GESTURE_TRAIL_FADEOUT_DURATION",
        "Settings.PREF_GRAMMAR_CHECK_MODE",
        "Settings.PREF_GRAMMAR_FIX_CAPITALIZE_I",
        "Settings.PREF_GRAMMAR_FIX_REPEATED_WORDS",
        "Settings.PREF_GRAMMAR_FIX_SENTENCE_CAPS",
        "Settings.PREF_GRAMMAR_MODE",
        "Settings.PREF_GRAMMAR_NGRAM_URL",
        "Settings.PREF_GRAMMAR_PT_VARIANT",
        "Settings.PREF_GRAMMAR_REMOTE_URL",
        "Settings.PREF_ICON_STYLE",
        "Settings.PREF_INLINE_EMOJI_SEARCH",
        "Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX",
        "Settings.PREF_KEYPRESS_SOUND_VOLUME",
        "Settings.PREF_KEY_GAP_SCALE_PREFIX",
        "Settings.PREF_KEY_LONGPRESS_TIMEOUT",
        "Settings.PREF_KEY_USE_DOUBLE_SPACE_PERIOD",
        "Settings.PREF_KEY_USE_PERSONALIZED_DICTS",
        "Settings.PREF_LANGUAGE_SWIPE_DISTANCE",
        "Settings.PREF_LANGUAGE_SWITCH_KEY",
        "Settings.PREF_LAYOUT_PREFIX",
        "Settings.PREF_LOCALIZED_NUMBER_ROW",
        "Settings.PREF_MORE_AUTO_CORRECTION",
        "Settings.PREF_MORE_POPUP_KEYS",
        "Settings.PREF_NAVBAR_COLOR",
        "Settings.PREF_POPUP_KEYS_HINT_ORDER",
        "Settings.PREF_POPUP_KEYS_ORDER",
        "Settings.PREF_POPUP_ON",
        "Settings.PREF_PUNCTUATION_SUGGESTIONS",
        "Settings.PREF_QUICK_PIN_TOOLBAR_KEYS",
        "Settings.PREF_REMOVE_REDUNDANT_POPUPS",
        "Settings.PREF_SAVE_SUBTYPE_PER_APP",
        "Settings.PREF_SECOND_ROW_TOOLBAR_KEYS",
        "Settings.PREF_SHIFT_REMOVES_AUTOSPACE",
        "Settings.PREF_SHOW_EMOJI_DESCRIPTIONS",
        "Settings.PREF_SHOW_EMOJI_KEY",
        "Settings.PREF_SHOW_HINTS",
        "Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY",
        "Settings.PREF_SHOW_NUMBER_ROW",
        "Settings.PREF_SHOW_NUMBER_ROW_HINTS",
        "Settings.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS",
        "Settings.PREF_SHOW_ONLY_TOOLBAR_WITH_HARDWARE_KEYBOARD",
        "Settings.PREF_SHOW_POPUP_HINTS",
        "Settings.PREF_SHOW_SETUP_WIZARD_ICON",
        "Settings.PREF_SHOW_SUGGESTIONS",
        "Settings.PREF_SHOW_TLD_POPUP_KEYS",
        "Settings.PREF_SIDE_PADDING_SCALE_PREFIX",
        "Settings.PREF_SOUND_ON",
        "Settings.PREF_SPACE_BAR_TEXT",
        "Settings.PREF_SPACE_HORIZONTAL_SWIPE",
        "Settings.PREF_SPACE_TO_CHANGE_LANG",
        "Settings.PREF_SPACE_VERTICAL_SWIPE",
        "Settings.PREF_SPELLCHECK_SUGGEST",
        "Settings.PREF_SPLIT_SPACER_SCALE_PREFIX",
        "Settings.PREF_SUGGEST_CLIPBOARD_CONTENT",
        "Settings.PREF_SUGGEST_EMOJIS",
        "Settings.PREF_SUGGEST_PUNCTUATION",
        "Settings.PREF_SUMMARY_PROMPT",
        "Settings.PREF_SUMMARY_STYLE",
        "Settings.PREF_SUMMARY_TEST",
        "Settings.PREF_THEME_COLORS",
        "Settings.PREF_THEME_COLORS_NIGHT",
        "Settings.PREF_THEME_DAY_NIGHT",
        "Settings.PREF_THEME_KEY_BORDERS",
        "Settings.PREF_THEME_STYLE",
        "Settings.PREF_TIMESTAMP_FORMAT",
        "Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES",
        "Settings.PREF_TOOLBAR_HIDING_GLOBAL",
        "Settings.PREF_TOOLBAR_KEYS",
        "Settings.PREF_TOOLBAR_MODE",
        "Settings.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE",
        "Settings.PREF_TOUCHPAD_EDGE_SCROLL",
        "Settings.PREF_TOUCHPAD_SENSITIVITY",
        "Settings.PREF_URL_DETECTION",
        "Settings.PREF_USE_APPS",
        "Settings.PREF_USE_CONTACTS",
        "Settings.PREF_VARIABLE_TOOLBAR_DIRECTION",
        "Settings.PREF_VIBRATE_IN_DND_MODE",
        "Settings.PREF_VIBRATE_ON",
        "Settings.PREF_VIBRATION_DURATION_SETTINGS",
        "SettingsWithoutKey.APP",
        "SettingsWithoutKey.BACKGROUND_IMAGE",
        "SettingsWithoutKey.BACKGROUND_IMAGE_LANDSCAPE",
        "SettingsWithoutKey.BACKUP_RESTORE",
        "SettingsWithoutKey.CLIPBOARD_CLEAR_HISTORY",
        "SettingsWithoutKey.CLIPBOARD_EXPORT_JSON",
        "SettingsWithoutKey.CLIPBOARD_IMPORT_JSON",
        "SettingsWithoutKey.CLIPBOARD_RENAME_LIST",
        "SettingsWithoutKey.CUSTOM_EMOJI_FONT",
        "SettingsWithoutKey.CUSTOM_FONT",
        "SettingsWithoutKey.DEBUG_SETTINGS",
        "SettingsWithoutKey.EDIT_PERSONAL_DICTIONARY",
        "SettingsWithoutKey.GITHUB",
        "SettingsWithoutKey.GITHUB_WIKI",
        "SettingsWithoutKey.HIDDEN_FEATURES",
        "SettingsWithoutKey.LICENSE",
        "SettingsWithoutKey.LOAD_GESTURE_LIB",
        "SettingsWithoutKey.SAVE_LOG",
        "SettingsWithoutKey.SAVE_LOG_INTERNAL",
        "SettingsWithoutKey.VERSION",
        "TranslatePrefs.KEY_APPLY_MODE",
        "TranslatePrefs.KEY_AUTO_DETECT",
        "TranslatePrefs.KEY_DEFAULT_TARGET",
        "TranslatePrefs.KEY_LIVE_COMMIT",
    )
}
