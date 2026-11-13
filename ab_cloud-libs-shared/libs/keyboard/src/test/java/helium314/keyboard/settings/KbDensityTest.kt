// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import com.diegonmarcos.superapp.uikit.KitDensity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every keyboard settings page with text is dense through ONE shared token object ([KbDensity], derived from the fleet kit's [KitDensity]). */
class KbDensityTest {
    private val settings = File("src/main/java/helium314/keyboard/settings")

    @Test fun tokensAreDerivedFromTheKitAndTighterThanBefore() {
        assertEquals(KitDensity.touch, KbDensity.rowMinHeight)
        assertEquals(KitDensity.rowPadV, KbDensity.rowPadV)
        assertEquals(KitDensity.medium, KbDensity.pageGutter)
        // the values the pages had before (row 10 dp high padding / 44 dp min / section 12 + 8 dp / 16 dp gutters / 8 dp gaps)
        assertTrue(KbDensity.rowMinHeight.value < 44f)
        assertTrue(KbDensity.rowPadV.value < 10f)
        assertTrue(KbDensity.sectionTop.value < 12f)
        assertTrue(KbDensity.sectionBottom.value < 8f)
        assertTrue(KbDensity.pageGutter.value < 16f)
        assertTrue(KbDensity.innerGap.value < 8f)
    }

    @Test fun sharedWidgetsAndTextPagesTakeTheirSpacingFromTheTokens() {
        val pref = File(settings, "preferences/Preference.kt").readText()
        assertTrue(pref.contains("heightIn(min = KbDensity.rowMinHeight)"))
        assertTrue(pref.contains("padding(vertical = KbDensity.rowPadV, horizontal = KbDensity.rowPadH)"))
        assertTrue(pref.contains("top = KbDensity.sectionTop"))
        assertFalse(pref.contains("heightIn(min = 44.dp)"))
        // the setting-row literal that padded every text-tool / AI page is gone from the screens
        settings.walkTopDown().filter { it.extension == "kt" && "/screens/" in it.path }.forEach {
            val t = it.readText()
            assertFalse(it.name, t.contains("padding(vertical = 10.dp, horizontal = 12.dp)"))
            assertFalse(it.name, t.contains("padding(horizontal = 16.dp, vertical = 8.dp)"))
        }
        listOf("AiRoutingScreen", "AiTaskModelTables", "TextEnhanceScreen", "TextResumeScreen", "PromptPreview", "AutocompleteScreen",
            "EmojiInfoScreen", "TranslationInfoScreen", "VoiceTranscriptInfoScreen", "GrammarCheckScreen").forEach {
            assertTrue(it, File(settings, "screens/$it.kt").readText().contains("KbDensity."))
        }
    }
}
