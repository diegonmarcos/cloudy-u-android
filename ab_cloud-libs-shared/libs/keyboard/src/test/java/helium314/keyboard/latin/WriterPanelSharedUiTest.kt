// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import com.diegonmarcos.superapp.translate.InputSource
import com.diegonmarcos.superapp.translate.WriterAction
import com.diegonmarcos.superapp.translate.WriterPanelLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1818 the three Writer modes use ONE shared panel composable (not three copies), with framed input and output boxes. */
class WriterPanelSharedUiTest {
    private val translateSrc = "ab_cloud-libs-shared/libs/translate/src/main/java/com/diegonmarcos/superapp/translate"
    private val kbSrc = "ab_cloud-libs-shared/libs/keyboard/src/main/java/helium314/keyboard/latin"
    private val panel = repoFile("$translateSrc/WriterPanelUi.kt").readText()
    private val translateBar = repoFile("$translateSrc/TranslateBarView.kt").readText()
    private val enhanceBar = repoFile("$kbSrc/EnhanceBarView.kt").readText()

    @Test fun theFiveActionsAndTheThreeSourcesAreDeclaredInOrder() {
        assertEquals(listOf("generate", "auto_replace", "replace", "paste", "copy"), WriterPanelLayout.ACTION_ORDER.map { it.id })
        assertEquals(listOf(InputSource.OWN, InputSource.CLIPBOARD, InputSource.APP_BOX), WriterPanelLayout.SOURCE_ORDER)
        assertEquals(WriterAction.values().toList(), WriterPanelLayout.ACTION_ORDER)
    }

    @Test fun theSharedPanelDrawsBothBoxesInsideAVisibleBorder() {
        assertTrue(WriterPanelLayout.FRAMED)
        val body = panel.substringAfter("fun WriterPanel(").substringBefore("fun WriterChip(")
        val input = body.substringAfter("// INPUT box, framed").substringBefore("Text(stringResource(R.string.writer_panel_output)")
        val output = body.substringAfter("// OUTPUT box, framed").substringBefore("if (spec.status")
        for ((name, part) in listOf("input" to input, "output" to output)) {
            assertTrue("$name box is framed with a border", part.contains(".border(1.dp, c.outline, frame)"))
            assertTrue("$name box hosts its editor", part.contains("$name()"))
        }
        assertTrue("the config selectors", body.contains("spec.selectors.forEach"))
        assertTrue("the source buttons", body.contains("WriterPanelLayout.SOURCE_ORDER.forEach"))
        assertTrue("the action buttons", body.contains("WriterPanelLayout.ACTION_ORDER.forEach"))
    }

    @Test fun everyModeCallsTheSameComposableAndOnlySuppliesItsSelectorsAndItsGenerator() {
        assertTrue(translateBar.contains("WriterPanel(\n"))
        assertTrue(enhanceBar.contains("WriterPanel(\n"))
        assertTrue(translateBar.contains("WriterPanelController(\n        WriterMode.TRANSLATE"))
        assertTrue(enhanceBar.contains("WriterPanelController(WriterMode.ENHANCE"))
        assertTrue(enhanceBar.contains("WriterPanelController(WriterMode.DRAFT"))
        // one definition of the panel in the whole tree: no per-mode copy
        val roots = listOf(translateSrc, kbSrc).map { repoFile("$it/${if (it == translateSrc) "WriterPanelUi.kt" else "EnhanceBarView.kt"}").parentFile }
        val defs = roots.flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
            .filter { it.readText().contains("fun WriterPanel(") }
        assertEquals(listOf("WriterPanelUi.kt"), defs.map { it.name })
    }

    @Test fun eachModeSuppliesItsOwnConfigSelectors() {
        // Translate: from / to languages (and the swap between them)
        assertTrue(translateBar.contains("ConfigSelector(\"from\"") && translateBar.contains("ConfigSelector(\"to\""))
        // Enhance: its enhance options; Draft Answer: its reply options (the same lists the Settings screens write)
        assertTrue(enhanceBar.contains("(if (answerMode) answerOptions else options).map { o -> ConfigSelector("))
    }

    @Test fun theModesAreNotThreeCopiesOfTheBoxesOrTheButtons() {
        // the old per-mode action rows (Chip(...) strips) and the dim source line are gone; the buttons come from the one layout
        assertTrue(!enhanceBar.contains("// ── Row 5: actions"))
        assertTrue(!translateBar.contains("// ── Row 4: actions"))
        assertTrue(panel.contains("class WriterPanelSpec("))
    }

    @Test fun strings() {
        val en = repoFile("ab_cloud-libs-shared/libs/translate/src/main/res/values/strings.xml").readText()
        val es = repoFile("ab_cloud-libs-shared/libs/translate/src/main/res/values-es/strings.xml").readText()
        for (k in listOf("writer_panel_input", "writer_panel_output", "writer_source_own", "writer_source_clipboard", "writer_source_app",
            "writer_action_generate", "writer_action_auto_replace", "writer_action_replace", "writer_action_paste", "writer_action_copy",
            "writer_mode_translate")) {
            assertTrue("$k in en", en.contains("name=\"$k\""))
            assertTrue("$k in es", es.contains("name=\"$k\""))
        }
    }
}
