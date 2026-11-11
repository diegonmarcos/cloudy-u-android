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

/** #1653 one page of the settings nav: [route] is an existing settings screen; [children] are its sub-pages (the top bar's dropdown). */
class NavPageDecl(val id: String, val label: String, val route: String, val children: List<NavPageDecl> = emptyList()) {
    fun flatten(): List<NavPageDecl> = listOf(this) + children.flatMap { it.flatten() }
}

/**
 * #1653 the settings' nav, declared once: FOUR bottom tabs in order, Keyboard / Writer / Mediaboards / Textboards (supersedes the
 * #1651 five). Each tab's [pages] are its sub-pages, reached from the fleet top bar; every page opens an existing screen, so
 * nothing is removed, only re-homed. [icon] is a libs:ui-icons catalog key. The Writer tab holds the keyboard's own writing tools
 * until cloud-writer's pages arrive from libs:writer-ui (#1652), plus Voice Transcription and Dictionary.
 */
enum class KbTab(val id: String, val label: String, val icon: String, val pages: List<NavPageDecl>) {
    KEYBOARD("keyboard", "Keyboard", "apps", listOf(
        NavPageDecl("themes", "Themes", SettingsDestination.Themes, listOf(
            NavPageDecl("themes_default", "Default", SettingsDestination.Themes),
            NavPageDecl("themes_dev", "Dev", SettingsDestination.Themes))),
        // Settings = the grouped All settings (#1650), where every theme / keyboard configuration lives; Appearance and Advanced fold under it
        NavPageDecl("settings", "Settings", SettingsDestination.Settings, listOf(
            NavPageDecl("appearance", "Appearance", SettingsDestination.Appearance),
            NavPageDecl("advanced", "Advanced", SettingsDestination.Advanced),
            NavPageDecl("preferences", "Preferences", SettingsDestination.Preferences),
            NavPageDecl("gesture_typing", "Gesture typing", SettingsDestination.GestureTyping))),
        NavPageDecl("languages", "Languages", SettingsDestination.Languages, listOf(
            NavPageDecl("secondary_layouts", "Secondary layouts", SettingsDestination.Layouts))),
        NavPageDecl("toolbar", "Toolbar", SettingsDestination.Toolbar),
    )),
    WRITER("writer", "Writer", "edit", listOf(
        NavPageDecl("enhancer", "Enhancer", SettingsDestination.TextEnhance),
        NavPageDecl("drafter", "Drafter", SettingsDestination.DraftAnswer),
        NavPageDecl("translator", "Translator", SettingsDestination.Translation),
        NavPageDecl("grammar", "Grammar", SettingsDestination.GrammarCheck),
        NavPageDecl("summarise", "Summarise", SettingsDestination.TextResume),
        NavPageDecl("ai_routing", "AI routing", SettingsDestination.AiRouting),
        NavPageDecl("voice_transcription", "Voice Transcription", SettingsDestination.VoiceTranscript),
        NavPageDecl("dictionary", "Dictionary", SettingsDestination.Dictionaries, listOf(
            NavPageDecl("personal_dictionary", "Personal dictionary", SettingsDestination.PersonalDictionaries))),
    )),
    MEDIABOARDS("mediaboards", "Mediaboards", "layers", listOf(
        // one page covers the three panels today (EmojiInfoScreen: Emoji, Stickers, GIFs)
        NavPageDecl("emojis", "Emojis", SettingsDestination.Emoji),
        NavPageDecl("gif", "GIF", SettingsDestination.Emoji),
        NavPageDecl("stickers", "Stickers", SettingsDestination.Emoji),
    )),
    TEXTBOARDS("textboards", "Textboards", "content_paste", listOf(
        NavPageDecl("autocomplete", "Autocomplete", SettingsDestination.Autocomplete),
        NavPageDecl("clipboard", "Clipboard", SettingsDestination.Clipboard),
    ));

    val allPages: List<NavPageDecl> get() = pages.flatMap { it.flatten() }
    /** The tab's landing route: its first page. */
    val route: String get() = pages.first().route

    companion object {
        fun forRoute(route: String?): KbTab? = entries.firstOrNull { t -> t.allPages.any { it.route == route } }
        fun byId(id: String): KbTab? = entries.firstOrNull { it.id == id }
    }
}
