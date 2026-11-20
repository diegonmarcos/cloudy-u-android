package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1827 the Delivery check sheet: every line can be selected (tap and select), and "Copy all" copies the whole report,
 * every line the sheet shows, in its order. Nothing the sheet had before is gone (rule 16).
 */
class RecipientSheetCopyTest {

    private fun src(name: String): String =
        listOf("src/main/kotlin/app/sterna/ui/compose/$name", "app/src/main/kotlin/app/sterna/ui/compose/$name")
            .map(::File).first { it.exists() }.readText()

    private fun res(dir: String): String =
        listOf("src/main/res/$dir/strings.xml", "app/src/main/res/$dir/strings.xml").map(::File).first { it.exists() }.readText()

    private val ui by lazy { src("RecipientStatusUi.kt") }

    private fun sheet(): String {
        val start = ui.indexOf("internal fun RecipientStatusSheet(")
        val end = ui.indexOf("internal data class ReportCheckLine", start)
        assertTrue(start >= 0 && end > start)
        return ui.substring(start, end)
    }

    @Test fun `the report is selectable and Copy all puts the report on the clipboard`() {
        val body = sheet()
        assertTrue(body.contains("SelectionContainer {"))
        assertTrue(body.contains("clipboard.setText(AnnotatedString(report))"))
        assertTrue(body.contains("R.string.compose_recipient_check_copy_all"))
        // In the drawn sheet the selectable block opens before the title and closes before the buttons.
        val drawn = body.substring(body.indexOf("ModalBottomSheet("))
        assertTrue(drawn.indexOf("SelectionContainer {") < drawn.indexOf("R.string.compose_recipient_check_title"))
        assertTrue(drawn.indexOf("R.string.compose_recipient_check_unreachable") < drawn.indexOf("R.string.compose_recipient_check_copy_all"))
        assertTrue(drawn.indexOf("R.string.compose_recipient_check_copy_all") < drawn.indexOf("R.string.compose_recipient_check_again"))
    }

    @Test fun `rule 16 - every line and action the sheet had is still there`() {
        val body = sheet()
        listOf(
            "R.string.compose_recipient_check_title", "Text(address", "verdictLabel(shown.verdict)", "shown.serverReason",
            "R.string.compose_recipient_check_server_cached", "shown.checks.forEach", "checkKindLabel(item.kind)",
            "outcomeLabel(item.outcome)", "checkDetailText(item)", "sourceLabel(item.source)",
            "R.string.compose_recipient_check_checked_at", "R.string.compose_recipient_check_not_connected",
            "R.string.compose_recipient_check_unreachable", "R.string.compose_recipient_check_again", "R.string.compose_recipient_check_close",
        ).forEach { assertTrue("missing: $it", body.contains(it)) }
    }

    @Test fun `the copied text carries every line in the sheet's order`() {
        val text = recipientReportText(
            title = "Delivery check", address = "info@i-live-berlin.de", verdict = "Unknown",
            serverReason = "mailbox: outbound :25 blocked", cachedNote = "Answered from the server's recent checks",
            checks = listOf(
                ReportCheckLine("Address format", "Passed", "state: ok", "Checked by the mail server"),
                ReportCheckLine("Mailbox", "Unknown", "this phone's network cannot reach port 25 either", "Checked on this phone"),
            ),
            checkedAt = "Checked 20/11/26 08:00", notConnected = null, unreachable = null,
        )
        val lines = text.lines()
        assertEquals("Delivery check", lines.first())
        val order = listOf("info@i-live-berlin.de", "Unknown", "mailbox: outbound :25 blocked", "Answered from the server's recent checks",
            "Address format: Passed", "state: ok", "Checked by the mail server", "Mailbox: Unknown",
            "this phone's network cannot reach port 25 either", "Checked on this phone", "Checked 20/11/26 08:00")
        var at = 0
        order.forEach { want ->
            val i = lines.indexOf(want)
            assertTrue("missing or out of order: $want", i > at)
            at = i
        }
    }

    @Test fun `the two new strings exist in every language`() {
        listOf("values", "values-de", "values-es", "values-fr", "values-it", "values-nl", "values-pl", "values-pt", "values-ru").forEach { d ->
            val x = res(d)
            assertTrue("$d copy_all", x.contains("name=\"compose_recipient_check_copy_all\""))
            assertTrue("$d copied", x.contains("name=\"compose_recipient_check_copied\""))
        }
    }
}
