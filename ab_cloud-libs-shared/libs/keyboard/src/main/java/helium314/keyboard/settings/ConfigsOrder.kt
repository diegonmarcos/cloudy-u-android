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

    /**
     * #1665 where this former All settings page lives in the app's nav: exactly one home (a bottom tab, or Configs) and the id of
     * the page there (a [NavPageDecl.id] of that [KbTab], or a row of the Configs page's keyboard tab, [KbHome.CONFIGS]).
     */
    val home: Pair<KbHome, String> get() = when (this) {
        TOOLBAR -> KbHome.KEYBOARD to "toolbar"
        DEV_KEYBOARD -> KbHome.KEYBOARD to "themes_dev"
        LANGUAGES -> KbHome.KEYBOARD to "languages"
        SECONDARY_LAYOUTS -> KbHome.KEYBOARD to "secondary_layouts"
        APPEARANCE -> KbHome.KEYBOARD to "appearance"
        PREFERENCES -> KbHome.KEYBOARD to "preferences"
        GESTURE_TYPING -> KbHome.KEYBOARD to "gesture_typing"
        DATA_GATHERING -> KbHome.KEYBOARD to "data_gathering"
        ADVANCED -> KbHome.KEYBOARD to "advanced"
        EMOJI -> KbHome.MEDIABOARDS to "emojis"
        CLIPBOARD -> KbHome.TEXTBOARDS to "clipboard"
        AUTOCOMPLETE -> KbHome.TEXTBOARDS to "autocomplete"
        TEXT_CORRECTION -> KbHome.TEXTBOARDS to "text_correction"
        DICTIONARIES -> KbHome.WRITER to "dictionary"
        TRANSLATION -> KbHome.WRITER to "kb_translator"
        TEXT_ENHANCE -> KbHome.WRITER to "kb_writing_tools"
        DRAFT_ANSWER -> KbHome.WRITER to "kb_drafter"
        GRAMMAR_CHECK -> KbHome.WRITER to "kb_grammar"
        VOICE_TRANSCRIPT -> KbHome.WRITER to "voice_transcription_settings"
        AI_ROUTING -> KbHome.WRITER to "kb_ai_routing"
        ANDROID_SETTINGS -> KbHome.CONFIGS to "android_settings"
        UPDATE -> KbHome.CONFIGS to "update"
        HOST_EXTRAS -> KbHome.CONFIGS to "host_extras"
        ABOUT -> KbHome.CONFIGS to "about"
    }
}

/** #1665 the homes of the app's nav: the four bottom tabs plus Configs (the top bar's Configs icon, fleet default page). */
enum class KbHome { KEYBOARD, WRITER, MEDIABOARDS, TEXTBOARDS, CONFIGS;
    /** The bottom tab of this home; null for Configs. */
    val tab: KbTab? get() = KbTab.entries.firstOrNull { it.name == name }
}

/** #1665 the rows of the Configs page's keyboard tab (KeyboardConfigsTabBody): the Configs-homed pages, then the grouped All settings. */
val keyboardConfigsRows: List<String> = listOf("android_settings", "update", "about", "host_extras", "all_settings")

/** #1653 one page of the settings nav: [route] is an existing settings screen; [children] are its sub-pages (the top bar's dropdown). */
class NavPageDecl(val id: String, val label: String, val route: String, val children: List<NavPageDecl> = emptyList()) {
    fun flatten(): List<NavPageDecl> = listOf(this) + children.flatMap { it.flatten() }
}

/**
 * #1653 the settings' nav, declared once: FOUR bottom tabs in order, Keyboard / Writer / Mediaboards / Textboards (supersedes the
 * #1651 five). Each tab's [pages] are its sub-pages, reached from the fleet top bar; every page opens an existing screen, so
 * nothing is removed, only re-homed. [icon] is a libs:ui-icons catalog key. The Writer tab holds the keyboard's own writing tools
 * from libs:writer-ui (cloud-writer's pages, #1652), plus Voice Transcription and Dictionary.
 */
enum class KbTab(val id: String, val label: String, val icon: String, val pages: List<NavPageDecl>) {
    KEYBOARD("keyboard", "Keyboard", "apps", listOf(
        // #1665 the picker, then each theme's own page (preview, use it, what it sets)
        NavPageDecl("themes", "Themes", SettingsDestination.Themes, listOf(
            NavPageDecl(KbThemePreset.DEFAULT.pageId, "Default", KbThemePreset.DEFAULT.route),
            NavPageDecl(KbThemePreset.DEV.pageId, "Dev", KbThemePreset.DEV.route))),
        // #1665 Settings = every keyboard / theme setting on one page (KeyboardSettingsScreen); the full pages fold under it
        NavPageDecl("settings", "Settings", SettingsDestination.KeyboardSettings, listOf(
            NavPageDecl("appearance", "Appearance", SettingsDestination.Appearance),
            NavPageDecl("advanced", "Advanced", SettingsDestination.Advanced),
            NavPageDecl("preferences", "Preferences", SettingsDestination.Preferences),
            NavPageDecl("gesture_typing", "Gesture typing", SettingsDestination.GestureTyping),
            NavPageDecl("data_gathering", "Gesture data", SettingsDestination.DataGathering))),
        NavPageDecl("languages", "Languages", SettingsDestination.Languages, listOf(
            NavPageDecl("secondary_layouts", "Secondary layouts", SettingsDestination.Layouts))),
        NavPageDecl("toolbar", "Toolbar", SettingsDestination.Toolbar),
    )),
    WRITER("writer", "Writer", "edit", listOf(
        // #1652 cloud-writer's own pages (libs:writer-ui WriterSurface.Page): its former bottom nav, then Tools, dictation and configs
        // #1698 cloud-writer's own bottom bar, same order and labels (ac_cloud-writer build.json ui.bottom_nav)
        NavPageDecl("writer_enhancer", "Enhancer", SettingsDestination.hostRoute("enhancer")),
        NavPageDecl("writer_drafter", "Drafter", SettingsDestination.hostRoute("drafter")),
        NavPageDecl("writer_home", "Home", SettingsDestination.hostRoute("home")),
        NavPageDecl("writer_translator", "Translator", SettingsDestination.hostRoute("translator")),
        NavPageDecl("writer_grammar", "Grammar", SettingsDestination.hostRoute("grammar")),
        NavPageDecl("writer_tools", "Tools", SettingsDestination.hostRoute("tools"), listOf(
            NavPageDecl("writer_tools_enhance", "Text Enhancements", SettingsDestination.hostRoute("tools.enhance")),
            NavPageDecl("writer_tools_translation", "Translation", SettingsDestination.hostRoute("tools.translation")),
            NavPageDecl("writer_tools_grammar", "Grammar check", SettingsDestination.hostRoute("tools.grammar")),
            NavPageDecl("writer_tools_ai_routing", "AI routing", SettingsDestination.hostRoute("tools.ai_routing")),
            NavPageDecl("writer_tools_routes", "Routes", SettingsDestination.hostRoute("tools.routes")))),
        NavPageDecl("voice_transcription", "Voice Transcription", SettingsDestination.hostRoute("dictation"), listOf(
            NavPageDecl("voice_transcription_settings", "Voice transcription settings", SettingsDestination.VoiceTranscript))),
        NavPageDecl("dictionary", "Dictionary", SettingsDestination.Dictionaries, listOf(
            NavPageDecl("personal_dictionary", "Personal dictionary", SettingsDestination.PersonalDictionaries))),
        NavPageDecl("writer_configs", "Writer configs", SettingsDestination.hostRoute("settings")),
        // the keyboard's own writing-tool pages (Enhancer etc. as keyboard settings) stay reachable
        NavPageDecl("kb_writing_tools", "Keyboard writing tools", SettingsDestination.TextEnhance, listOf(
            NavPageDecl("kb_drafter", "Draft Answer", SettingsDestination.DraftAnswer),
            NavPageDecl("kb_translator", "Translation", SettingsDestination.Translation),
            NavPageDecl("kb_grammar", "Grammar Check", SettingsDestination.GrammarCheck),
            NavPageDecl("kb_summarise", "Summarise", SettingsDestination.TextResume),
            NavPageDecl("kb_ai_routing", "AI routing", SettingsDestination.AiRouting))),
    )),
    MEDIABOARDS("mediaboards", "Mediaboards", "layers", listOf(
        // #1665 three pages of their own: emoji settings, the GIF source + live search, the sticker packs
        NavPageDecl("emojis", "Emojis", SettingsDestination.Emoji),
        NavPageDecl("gif", "GIF", SettingsDestination.Gif),
        NavPageDecl("stickers", "Stickers", SettingsDestination.Stickers),
    )),
    TEXTBOARDS("textboards", "Textboards", "content_paste", listOf(
        NavPageDecl("autocomplete", "Autocomplete", SettingsDestination.Autocomplete, listOf(
            NavPageDecl("text_correction", "Text correction", SettingsDestination.TextCorrection))),
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
