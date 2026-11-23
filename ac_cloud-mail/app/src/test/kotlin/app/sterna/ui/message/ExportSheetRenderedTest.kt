package app.sterna.ui.message

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.export.ExportFormat
import app.sterna.ui.theme.SternaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The reader's overflow offers "Export…", and the sheet it opens lists every format, MHTML selected,
 * with the "remote images" note, Save… and Share…, and progress + Cancel while a job runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h900dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportSheetRenderedTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private fun content(
        selected: ExportFormat = ExportFormat.MHTML,
        running: ExportUiState? = null,
        enabled: (ExportFormat) -> Boolean = { true },
        onSelect: (ExportFormat) -> Unit = {},
        onSave: () -> Unit = {},
        onShare: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        compose.setContent {
            SternaTheme {
                ExportSheetContent(selected, enabled, running, onSelect, onSave, onShare, onCancel)
            }
        }
        compose.waitForIdle()
    }

    @Test fun `the overflow entry reads Export and clicks through`() {
        var clicked = 0
        compose.setContent { SternaTheme { Column { ExportMenuItem { clicked++ } } } }
        compose.onNodeWithTag(EXPORT_MENU_TAG).assertIsDisplayed()
        compose.onNodeWithText("Export…").assertIsDisplayed()
        compose.onNodeWithTag(EXPORT_MENU_TAG).performClick()
        assertEquals(1, clicked)
    }

    @Test fun `the sheet lists every format with MHTML selected by default`() {
        content()
        EXPORT_FORMATS.forEach { compose.onNodeWithTag(exportFormatTag(it)).assertIsDisplayed() }
        assertEquals(6, EXPORT_FORMATS.size)
        compose.onNodeWithTag(exportFormatTag(ExportFormat.MHTML)).assertIsSelected()
        listOf(
            "Web archive (.mhtml)", "Attachments only (.zip)", "Page as HTML (.html)",
            "Email message (.eml)", "Mailbox (.mbox)", "Markdown (.md)",
        ).forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Remote images are not included.").assertIsDisplayed()
        compose.onNodeWithTag("export_save").assertIsDisplayed()
        compose.onNodeWithTag("export_share").assertIsDisplayed()
    }

    @Test fun `picking a format and the two destinations call back`() {
        val picked = mutableListOf<ExportFormat>()
        var saved = 0
        var shared = 0
        content(onSelect = { picked += it }, onSave = { saved++ }, onShare = { shared++ })
        compose.onNodeWithTag(exportFormatTag(ExportFormat.EML)).performClick()
        compose.onNodeWithTag("export_save").performClick()
        compose.onNodeWithTag("export_share").performClick()
        assertEquals(listOf(ExportFormat.EML), picked)
        assertEquals(1, saved)
        assertEquals(1, shared)
    }

    @Test fun `attachments-only is disabled for a message without files`() {
        val none = Email(id = "e")
        val withFile = Email(
            id = "e2", attachments = listOf(EmailBodyPart(blobId = "b", partId = "2", type = "application/pdf", name = "a.pdf", disposition = "attachment")),
        )
        assertTrue(!exportFormatEnabled(ExportFormat.ATTACHMENTS_ZIP, none))
        assertTrue(exportFormatEnabled(ExportFormat.ATTACHMENTS_ZIP, withFile))
        assertTrue(exportFormatEnabled(ExportFormat.MHTML, none))
        content(enabled = { exportFormatEnabled(it, none) })
        compose.onNodeWithTag(exportFormatTag(ExportFormat.ATTACHMENTS_ZIP)).assertIsNotEnabled()
        compose.onNodeWithTag(exportFormatTag(ExportFormat.HTML)).assertIsEnabled()
    }

    @Test fun `while a job runs the sheet shows Cancel instead of the destinations`() {
        var cancelled = 0
        content(running = ExportUiState(ExportFormat.MHTML, done = 10, total = 100), onCancel = { cancelled++ })
        compose.onNodeWithTag("export_cancel").performClick()
        assertEquals(1, cancelled)
        assertTrue(compose.onAllNodesWithTagCount("export_save") == 0)
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    /** SOURCE LINT: the real overflow menu in the reader carries the entry and the sheet. */
    @Test fun `the reader's DropdownMenu contains the Export entry and opens the sheet`() {
        val src = listOf("src/main/kotlin/app/sterna/ui/message/MessageScreen.kt", "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")
            .map(::File).first { it.exists() }.readText()
        val menu = src.substring(src.indexOf("DropdownMenu(\n        expanded = menuOpen"))
        assertTrue("ExportMenuItem missing from the overflow", menu.contains("ExportMenuItem {"))
        assertTrue("the sheet is never shown", src.contains("ExportSheetHost(") && src.contains("exportSheet = true"))
    }
}
