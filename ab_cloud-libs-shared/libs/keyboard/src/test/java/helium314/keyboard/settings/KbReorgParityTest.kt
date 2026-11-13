// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule 16 field parity for the settings reorg (compact pages, Writer reorg, Autocomplete under Writer, Configs Writer group,
 * Emoji lists, Autocomplete tables): the nav BEFORE the reorg is frozen below (every page id with its route, taken from the
 * previous KbTab), and every one of them is still declared, with the same route, somewhere in the new nav. The one removal is
 * the Writer sub page Home (explicit owner decision); its content is proven moved. Every old setting key is held by
 * KeyboardNavPagesTest.everySettingKeyBeforeIsStillDefined; this holds the pages and the old Autocomplete page's fields.
 */
class KbReorgParityTest {
    private val src get() = File("src/main/java/helium314/keyboard/settings")

    /** page id -> route, as declared before the reorg (all four tabs, every depth). */
    private val before: Map<String, String> = mapOf(
        "themes" to "themes", "themes_default" to "themes/default", "themes_dev" to "themes/dev", "themes_unexpected" to "themes/unexpected",
        "settings" to "kb_settings", "appearance" to "appearance", "advanced" to "advanced", "preferences" to "preferences",
        "gesture_typing" to "gesture_typing", "data_gathering" to "data_gathering",
        "languages" to "languages", "secondary_layouts" to "layouts", "toolbar" to "toolbar",
        "writer_enhancer" to "host/enhancer", "writer_drafter" to "host/drafter", "writer_home" to "host/home",
        "writer_translator" to "host/translator", "writer_grammar" to "host/grammar", "writer_tools" to "host/tools",
        "writer_tools_enhance" to "host/tools.enhance", "writer_tools_translation" to "host/tools.translation",
        "writer_tools_grammar" to "host/tools.grammar", "writer_tools_ai_routing" to "host/tools.ai_routing", "writer_tools_routes" to "host/tools.routes",
        "voice_transcription" to "host/dictation", "voice_transcription_settings" to "voice_transcript_info",
        "dictionary" to "dictionaries", "personal_dictionary" to "personal_dictionaries", "writer_configs" to "host/settings",
        "kb_writing_tools" to "text_enhance", "kb_drafter" to "draft_answer", "kb_translator" to "translation_info",
        "kb_grammar" to "grammar_check", "kb_summarise" to "text_resume", "kb_ai_routing" to "ai_routing",
        "emojis" to "emoji_info", "gif" to "gif_info", "stickers" to "stickers_info",
        "autocomplete" to "autocomplete", "text_correction" to "text_correction", "clipboard" to "clipboard",
    )

    private val after: Map<String, String> = KbTab.entries.flatMap { it.allPages }.associate { it.id to it.route }

    @Test fun everyPreviousPageIsStillReachableWithItsRoute_exceptHome() {
        before.filterKeys { it != "writer_home" }.forEach { (id, route) -> assertEquals("page $id", route, after[id]) }
        assertFalse("Home is the one removal", "writer_home" in after)
        assertFalse("host/home" in after.values)
    }

    @Test fun noPageWasAddedThatIsNotAGroupOrAnExistingScreen() {
        assertEquals(emptySet<String>(), after.keys - before.keys) // the reorg adds structure, not pages
    }

    @Test fun autocompleteAndTextCorrectionLiveUnderConfigsWriter() {
        val writer = KbTab.WRITER
        val group = writer.pages.last()
        assertEquals("Configs Writer", group.label)
        assertEquals("writer_configs", group.id)
        assertTrue("autocomplete" in group.flatten().map { it.id })
        assertTrue("text_correction" in group.flatten().map { it.id })
        assertFalse("autocomplete" in KbTab.TEXTBOARDS.allPages.map { it.id })
        // AllSettingsPage homes follow the move and still resolve to a declared page
        assertEquals(KbHome.WRITER to "autocomplete", AllSettingsPage.AUTOCOMPLETE.home)
        assertEquals(KbHome.WRITER to "text_correction", AllSettingsPage.TEXT_CORRECTION.home)
    }

    /** Home's content (the document list: search, import file / scan / image, sort, tags, new document) is not dropped: it moved under Voice Transcription. */
    @Test fun homesContentMovedUnderVoiceTranscription() {
        val surface = File("../writer-ui/src/main/java/com/diegonmarcos/cloudwriter/WriterSurface.kt").takeIf { it.isFile } ?: return
        val t = surface.readText()
        val dict = t.substring(t.indexOf("private fun DictationPage()"), t.indexOf("private fun App()"))
        assertTrue("the dictation page lists the documents", dict.contains("DocumentsSection("))
        assertTrue("a document opens in place from the dictation page", dict.contains("EditorScreen()"))
        listOf("importFile", "importImage", "newDoc()", "DocCard(").forEach { assertTrue("documents section keeps $it", t.substring(t.indexOf("private fun DocumentsSection(")).contains(it)) }
        assertTrue("cloud-writer's own Home is untouched", t.contains("PAGE_HOME, PAGE_EDITOR ->"))
    }

    /** The old Autocomplete page's content (page counts, masked flag, source note, Sync now, Clear) is all still drawn, above the new tables. */
    @Test fun autocompletePageKeepsItsOldFieldsAndAddsTheTables() {
        val s = File(src, "screens/AutocompleteScreen.kt").readText()
        listOf("autocomplete_pages", "AutocompletePage.entries", "autocomplete_masked", "autocomplete_source_note", "autocomplete_actions",
            "autocomplete_sync_now", "AutocompleteSync.pull", "autocomplete_clear", "store.clear()").forEach { assertTrue(it, s.contains(it)) }
        assertTrue(s.indexOf("store.clear()") < s.indexOf("UserDataTablesSection()"))
        UserTable.entries.forEach { assertTrue(it.title, s.contains("UserTable.entries")) }
        assertEquals(listOf("Personal Data", "Keys", "Urls", "Notes", "Others"), UserTable.entries.map { it.title })
    }

    @Test fun emojiPageKeepsItsSettingsAndListsTheEmojisAtTheBottom() {
        val s = File(src, "screens/EmojiInfoScreen.kt").readText()
        val page = s.substring(s.indexOf("fun EmojiInfoScreen("), s.indexOf("internal fun emojiSettingKeys"))
        assertTrue(page.contains("emojiSettingKeys(") && page.contains("mediaboards_emoji_search_hint") && page.contains("mediaboards_emoji_dicts"))
        assertTrue("the lists come after every setting row", page.indexOf("SettingRows(") < page.indexOf("EmojiListsSection()"))
    }
}
