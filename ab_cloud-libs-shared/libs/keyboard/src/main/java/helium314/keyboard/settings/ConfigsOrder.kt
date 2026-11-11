// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

/**
 * #1077: the order of the keyboard's own entries at the top of Configs, declared once. The screen draws them in
 * this order and a test pins it, so a page cannot drift to another position by an edit elsewhere.
 * Toolbar is first, Autocomplete sits right after Clipboard. #1384: Draft Answer sits right after Text Enhancement. #1413: Dev Keyboard sits before Android settings.
 */
enum class ConfigsEntry { TOOLBAR, CLIPBOARD, AUTOCOMPLETE, EMOJI, TRANSLATION, TEXT_ENHANCE, DRAFT_ANSWER, GRAMMAR_CHECK, VOICE_TRANSCRIPT, AI_ROUTING, DEV_KEYBOARD, ANDROID_SETTINGS, UPDATE }

/**
 * #1650: every subpage of the "All settings" screen, each under exactly ONE of the three [HomeGroup] sections
 * (Keyboard / Text / Configs). The screen draws section by section from this list, so a page cannot be left
 * ungrouped; a test pins the full set so a reorg can never drop one. [HOST_EXTRAS] stands for the host app's
 * own entries (Cloud Keyboard: Configs > General / About).
 */
enum class AllSettingsPage(val section: HomeGroup) {
    // Keyboard: mechanics, layouts, gestures, look
    TOOLBAR(HomeGroup.KEYBOARD), EMOJI(HomeGroup.KEYBOARD), DEV_KEYBOARD(HomeGroup.KEYBOARD),
    LANGUAGES(HomeGroup.KEYBOARD), SECONDARY_LAYOUTS(HomeGroup.KEYBOARD), APPEARANCE(HomeGroup.KEYBOARD),
    PREFERENCES(HomeGroup.KEYBOARD), GESTURE_TYPING(HomeGroup.KEYBOARD), DATA_GATHERING(HomeGroup.KEYBOARD),
    // Text: behaviour of the text itself
    CLIPBOARD(HomeGroup.TEXT), AUTOCOMPLETE(HomeGroup.TEXT), TEXT_CORRECTION(HomeGroup.TEXT), DICTIONARIES(HomeGroup.TEXT),
    TRANSLATION(HomeGroup.TEXT), TEXT_ENHANCE(HomeGroup.TEXT), DRAFT_ANSWER(HomeGroup.TEXT),
    GRAMMAR_CHECK(HomeGroup.TEXT), VOICE_TRANSCRIPT(HomeGroup.TEXT),
    // Configs: app / meta
    AI_ROUTING(HomeGroup.CONFIGS), ANDROID_SETTINGS(HomeGroup.CONFIGS), UPDATE(HomeGroup.CONFIGS),
    HOST_EXTRAS(HomeGroup.CONFIGS), ADVANCED(HomeGroup.CONFIGS), ABOUT(HomeGroup.CONFIGS);

    companion object {
        fun of(section: HomeGroup): List<AllSettingsPage> = entries.filter { it.section == section }
    }
}
