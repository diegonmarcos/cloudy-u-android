package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1800 parity (owner rule 16, nothing removed): making compose WYSIWYG by default and adding the
 * plain-text option must not drop a single compose option. The lists below are every compose string
 * the formatting bar and the compose screen referenced BEFORE #1800; each must still be referenced.
 * The plain-text option is the one addition, and rich (not plain) stays the default.
 */
class ComposeOptionsParityTest {

    private fun src(name: String): String =
        listOf("src/main/kotlin/app/sterna/ui/compose/$name", "app/src/main/kotlin/app/sterna/ui/compose/$name")
            .map(::File).first { it.exists() }.readText()

    private fun referenced(code: String): Set<String> =
        Regex("R\\.string\\.(compose_[a-z_]+)").findAll(code).map { it.groupValues[1] }.toSet()

    private val formattingBarBefore = listOf(
        "compose_format_align",
        "compose_format_align_center",
        "compose_format_align_left",
        "compose_format_align_right",
        "compose_format_bold",
        "compose_format_clear",
        "compose_format_color_blue",
        "compose_format_color_default",
        "compose_format_color_green",
        "compose_format_color_red",
        "compose_format_heading",
        "compose_format_inline_image",
        "compose_format_italic",
        "compose_format_link",
        "compose_format_list_bulleted",
        "compose_format_list_numbered",
        "compose_format_paragraph",
        "compose_format_quote",
        "compose_format_source",
        "compose_format_strikethrough",
        "compose_format_text_color",
        "compose_format_underline",
        "compose_link_apply",
        "compose_link_bad_url",
        "compose_link_cancel",
        "compose_link_remove",
        "compose_link_text",
        "compose_link_title",
        "compose_link_url",
    )

    private val composeScreenBefore = listOf(
        "compose_attach",
        "compose_attachment_fallback",
        "compose_bcc",
        "compose_body_placeholder",
        "compose_cc",
        "compose_choose_sender",
        "compose_choose_signature",
        "compose_contacts_priming_body",
        "compose_contacts_priming_enable",
        "compose_contacts_priming_not_now",
        "compose_contacts_priming_title",
        "compose_could_not_save",
        "compose_could_not_send",
        "compose_delete_draft",
        "compose_delete_draft_body",
        "compose_delete_draft_body_local",
        "compose_delete_draft_title",
        "compose_discard",
        "compose_discard_cancel",
        "compose_discard_changes",
        "compose_discard_discard",
        "compose_discard_message",
        "compose_discard_message_encrypted",
        "compose_discard_message_encrypted_draft",
        "compose_discard_message_outbox",
        "compose_discard_message_outbox_encrypted",
        "compose_discard_save",
        "compose_discard_title",
        "compose_discard_title_changes",
        "compose_discard_title_outbox",
        "compose_emptied_draft_server_copy_kept",
        "compose_forgot_attachment_back",
        "compose_forgot_attachment_message",
        "compose_forgot_attachment_send",
        "compose_forgot_attachment_title",
        "compose_from",
        "compose_hide_cc_bcc",
        "compose_many_recipients_message",
        "compose_many_recipients_send",
        "compose_many_recipients_title",
        "compose_pgp_encrypt",
        "compose_pgp_encrypt_unsigned",
        "compose_pgp_not_encrypted_no_key",
        "compose_pgp_off",
        "compose_pgp_sign",
        "compose_pgp_snack_encrypt",
        "compose_pgp_snack_encrypt_unsigned",
        "compose_pgp_snack_off",
        "compose_pgp_snack_sign",
        "compose_pgp_subject_note",
        "compose_pgp_toggle_desc",
        "compose_remove",
        "compose_request_receipt",
        "compose_save_draft",
        "compose_schedule_send",
        "compose_scheduled_toast",
        "compose_send",
        "compose_show_cc_bcc",
        "compose_signature",
        "compose_subject",
        "compose_title_new",
        "compose_title_reply",
        "compose_to",
    )

    @Test fun `every formatting tool that existed before is still on the bar`() {
        val now = referenced(src("HtmlBodyEditor.kt"))
        assertEquals("removed from the formatting bar", emptyList<String>(), formattingBarBefore.filter { it !in now })
        assertTrue("the plain-text option is offered", "compose_format_plain_text" in now)
    }

    @Test fun `every compose screen option that existed before is still there`() {
        val now = referenced(src("ComposeScreen.kt"))
        assertEquals("removed from the compose screen", emptyList<String>(), composeScreenBefore.filter { it !in now })
    }

    @Test fun `rich text is the default, plain text only an opt-in`() {
        val vm = src("ComposeViewModel.kt")
        assertTrue(vm.contains("private val _plainTextMode = MutableStateFlow(false)"))
        val pane = src("HtmlBodyEditor.kt")
        assertTrue(pane.contains("plainText: Boolean = false,"))
    }
}
