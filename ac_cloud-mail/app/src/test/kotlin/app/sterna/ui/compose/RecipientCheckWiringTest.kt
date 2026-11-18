package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1803 SOURCE LINT of the deliverability wiring in the composer: every chip of To, Cc and Bcc gets the
 * mark, the chip's own tap still edits it (#94), Send rechecks and asks (never blocks), and nothing the
 * screen had before is gone (rule 16: the QR button stays ahead of the Cc/Bcc toggle, one send site).
 */
class RecipientCheckWiringTest {

    private fun src(name: String): String =
        listOf("src/main/kotlin/app/sterna/ui/compose/$name", "app/src/main/kotlin/app/sterna/ui/compose/$name")
            .map(::File).first { it.exists() }.readText()

    private val screen by lazy { src("ComposeScreen.kt") }

    private fun fieldBody(): String {
        val start = screen.indexOf("private fun RecipientChipsField(")
        val end = screen.indexOf("\n@", start + 1).let { if (it < 0) screen.length else it }
        assertTrue(start >= 0)
        return screen.substring(start, end)
    }

    @Test fun `every chip carries the mark, and the chip's own tap still edits the address`() {
        val body = fieldBody()
        assertTrue(body.contains("leadingIcon = {"))
        assertTrue(body.contains("RecipientStatusMark(status(chip), onClick = { onStatusTap(chip) })"))
        assertTrue("#94 tap-to-edit kept", body.contains("onValueChange(recipientsWithChipEdited(value, index))"))
        assertTrue("the remove icon kept", body.contains("R.string.compose_remove"))
        assertTrue("the invalid/missing-key colouring kept", body.contains("val valid = isValidEmail(chip) && !missingKey(chip)"))
        assertTrue("the folded summary sums the marks up", body.contains("worstVerdict(chips.map(status))"))
    }

    @Test fun `To, Cc and Bcc are all wired to the statuses and the sheet`() {
        assertEquals(3, Regex("""status = recipientStatusFor""").findAll(screen).count())
        assertEquals(3, Regex("""onStatusTap = viewModel::showRecipientDetail""").findAll(screen).count())
    }

    @Test fun `the QR button still comes before the Cc-Bcc toggle`() {
        val qr = screen.indexOf("R.string.compose_scan_qr)")
        val toggle = screen.indexOf("R.string.compose_hide_cc_bcc else R.string.compose_show_cc_bcc")
        assertTrue(qr in 0 until toggle)
    }

    @Test fun `committed chips are checked as they change`() {
        assertTrue(screen.contains("listOf(to, cc, bcc).flatMap { splitRecipients(it).first }"))
        assertTrue(screen.contains("viewModel.checkRecipients(committedRecipients, body.text.length.toLong())"))
    }

    @Test fun `Send rechecks, asks about will-fail recipients, and can still send from the question`() {
        assertTrue(screen.contains("viewModel.recheckBeforeSend(allRecipients, body.text.length.toLong())"))
        assertTrue(screen.contains("if (failing.isEmpty()) sendChecked() else viewModel.askUndeliverable(failing)"))
        val code = screen.lines().map { it.trim() }
        val clears = code.indices.filter { code[it] == "viewModel.clearUndeliverable()" }
        assertTrue("Send anyway lowers the flag, then sends", clears.any { code.getOrNull(it + 1) == "sendChecked()" })
        assertTrue(screen.contains("if (undeliverable.isNotEmpty()) {"))
        assertEquals("still exactly one send site", 1, Regex("""viewModel\.send\(""").findAll(screen).count())
        // the earlier guards are unchanged links of the same chain
        assertTrue(screen.contains("if (recipientCount >= MANY_RECIPIENTS) viewModel.askManyRecipients() else sendNow()"))
    }

    @Test fun `the sheet lists every check, and the question names each failing reason`() {
        val ui = src("RecipientStatusUi.kt")
        RecipientCheckKind.entries.forEach { assertTrue(it.name, ui.contains("RecipientCheckKind.${it.name} ->")) }
        RecipientVerdict.entries.forEach { assertTrue(it.name, ui.contains("RecipientVerdict.${it.name} ->")) }
        SyntaxProblem.entries.forEach { assertTrue(it.name, ui.contains("SyntaxProblem.${it.name} ->")) }
        assertTrue(ui.contains("shown.checks.forEach"))
        assertTrue(ui.contains("R.string.compose_recipient_check_again"))
        assertTrue(ui.contains("R.string.compose_recipient_check_send_anyway"))
        assertTrue(ui.contains("R.string.compose_recipient_check_not_connected"))
        assertTrue(ui.contains("R.string.compose_recipient_check_mailbox_accepts_all"))
    }

    @Test fun `the view model rechecks before Send for a bounded time`() {
        val vm = src("ComposeViewModel.kt")
        assertTrue(vm.contains("withTimeoutOrNull(SEND_RECHECK_TIMEOUT_MILLIS)"))
        assertTrue(vm.contains("maxAgeMillis = SEND_RECHECK_MAX_AGE_MILLIS"))
        assertTrue(vm.contains("RecipientChecks.checker"))
    }
}
