package app.sterna.ui.message

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailHeader
import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The reading panel of #1661: the two tag families, the order of the panel, what a tap on the
 * subject shows, what "Copy all" copies, and that nothing the previous panel had is gone.
 *
 * The decisions are EXECUTED (they are pure functions). The layout is held as source, like
 * [MessageTagRowDensityTest]: there is no device in CI, so what is asserted is the order in which
 * the header draws its blocks and the wiring of the two taps, never a pixel.
 */
class MessageReadingPanelTest {

    // ---- 1. classification -------------------------------------------------------------------

    @Test fun `a name starting with a letter is a meta tag`() {
        listOf("A", "Archive", "Fa Gov and Official", "Inbox", "Z-last", "zebra", "  Padded").forEach {
            assertEquals(it, TagClass.META, classifyTagName(it))
        }
    }

    @Test fun `a name starting with a digit 1 to 9 is a project tag`() {
        listOf("1", "1 Website", "9-Ops", "42 - Backend", "5*", " 7 padded").forEach {
            assertEquals(it, TagClass.PROJECT, classifyTagName(it))
        }
    }

    @Test fun `everything else is neither, and nothing throws`() {
        listOf(null, "", "   ", "0 Zero", "01 - Inbox (Alerts)", "*star", "_x", "💰 Finance", "Été", "\$seen").forEach {
            assertEquals("$it", TagClass.OTHER, classifyTagName(it))
        }
    }

    // ---- 2. the two sections and their default states ------------------------------------------

    private fun tag(label: String, kind: TagKind = TagKind.MAILBOX) = MessageTag(kind, "id-$label", label)

    @Test fun `project tags come first and open, meta tags second and closed`() {
        val sections = tagSections(listOf(tag("Inbox"), tag("2 Backend"), tag("Fa Gov"), tag("1 Website")))
        assertEquals(listOf(TagSectionKind.PROJECT, TagSectionKind.META), sections.map { it.kind })
        assertEquals(listOf("2 Backend", "1 Website"), sections[0].tags.map { it.label })
        assertEquals(listOf("Inbox", "Fa Gov"), sections[1].tags.map { it.label })
        assertTrue("Project tags must open expanded", tagSectionExpandedByDefault(TagSectionKind.PROJECT))
        assertFalse("Meta tags must open collapsed", tagSectionExpandedByDefault(TagSectionKind.META))
    }

    @Test fun `no tag is ever lost - an unclassified one rides with the project section`() {
        val tags = listOf(tag("0 Zero"), tag("Inbox"), tag("💰 Finance"), tag("3 Ops"), tag("urgent", TagKind.KEYWORD))
        val sections = tagSections(tags)
        assertEquals(tags.map { it.label }.sorted(), sections.flatMap { it.tags }.map { it.label }.sorted())
        assertEquals(listOf("3 Ops", "0 Zero", "💰 Finance"), sections.first { it.kind == TagSectionKind.PROJECT }.tags.map { it.label })
    }

    @Test fun `a family with no tag is no section, and no tags is nothing at all`() {
        assertTrue(tagSections(emptyList()).isEmpty())
        assertEquals(listOf(TagSectionKind.META), tagSections(listOf(tag("Inbox"))).map { it.kind })
        assertEquals(listOf(TagSectionKind.PROJECT), tagSections(listOf(tag("1 Website"))).map { it.kind })
    }

    // ---- 3. the order of the panel, and the two taps ---------------------------------------------

    @Test fun `the header draws title, sender, tag sections, icons - in that order`() {
        val header = topLevel("private fun MessageHeader(")
        val title = header.indexOf("R.string.message_no_subject")
        val sender = header.indexOf(".combinedClickable(")
        val tags = header.indexOf("MessageTagSections(tags, msg.id)")
        val icons = header.indexOf("readingActions()")
        val summary = header.indexOf("ResumeBox(")
        assertTrue("a block of the panel is gone: $title $sender $tags $icons $summary", listOf(title, sender, tags, icons, summary).all { it >= 0 })
        assertTrue("order is title@$title sender@$sender tags@$tags icons@$icons summary@$summary", title < sender && sender < tags && tags < icons && icons < summary)
    }

    @Test fun `the header is laid over the top of the body, so the body comes last`() {
        val conversation = topLevel("private fun ConversationBody(")
        assertTrue("the header must be the collapsing overlay above the document", conversation.contains(".onSizeChanged { headerHeightPx = it.height }"))
        assertTrue("the document must still reserve the header's height above the body", conversation.contains("bodySpacerCss(headerHeightPx, bodyViewportPx)"))
    }

    @Test fun `the sections are drawn through the one guarded strip, in the decided state`() {
        val sections = topLevel("private fun MessageTagSections(")
        assertTrue(sections.contains("tagSections(tags)"))
        assertTrue("the state a section opens in must be the decided default", sections.contains("mutableStateOf(tagSectionExpandedByDefault(section.kind))"))
        val section = topLevel("private fun MessageTagSection(")
        assertTrue("a closed Meta section must say See more", section.contains("if (!expanded && kind == TagSectionKind.META)") && section.contains("R.string.message_tags_see_more"))
        assertTrue("the title line must open and close the section", section.contains(".clickable(onClick = onToggle)"))
        assertTrue("the pills are drawn only while the section is open", section.contains("if (expanded) {"))
    }

    @Test fun `tapping the subject opens the full details, and the sender tap is still there`() {
        val header = topLevel("private fun MessageHeader(")
        assertTrue(
            "the subject must open the details",
            header.contains(".clickable(onClickLabel = stringResource(R.string.message_details_title)) { showDetails = true }"),
        )
        assertTrue(header.contains("if (showDetails) {") && header.contains("MessageDetailsSheet("))
        assertTrue("the details must ask for the headers when opened", header.contains("onOpened = onSenderPanelOpened,"))
        assertTrue("the sender tap must still open the participants panel", header.contains("onClick = { showParticipants = true },"))
    }

    @Test fun `Copy all is the last thing in the sheet and copies the whole block`() {
        val sheet = File(ROOT, "$UI/MessageDetailsSheet.kt").readText()
        val groups = sheet.indexOf("for (section in sections)")
        val button = sheet.indexOf("OutlinedButton(")
        assertTrue(groups in 0 until button)
        assertTrue(sheet.contains("clipboard.setText(AnnotatedString(detailsText(sections)))"))
        assertTrue(sheet.contains("R.string.message_details_copy_all"))
        assertTrue("nothing may be drawn after the button", sheet.indexOf("for (section in sections)", button) < 0)
    }

    // ---- 4. what the details show ------------------------------------------------------------------

    private val time: (Long) -> String = { "T$it" }
    private val size: (Long) -> String = { "${it}B" }
    private val names = mapOf("m1" to "Inbox", "m2" to "1 Website", "m3" to "Fa Gov")
    private val mailboxes = names.map { (id, name) -> Mailbox(id = id, name = name) }

    private val email = Email(
        id = "e1",
        blobId = "blob1",
        threadId = "t1",
        subject = "Quarterly report",
        receivedAt = "2026-11-02T10:20:00Z",
        from = listOf(EmailAddress("News Desk", "news@news.example")),
        replyTo = listOf(EmailAddress(null, "answers@news.example")),
        to = listOf(EmailAddress("Reader", "reader@fleet.example")),
        cc = listOf(EmailAddress(null, "copy@fleet.example")),
        messageId = listOf("abc@news.example"),
        inReplyTo = listOf("prev@fleet.example"),
        references = listOf("root@fleet.example", "prev@fleet.example"),
        keywords = mapOf("\$seen" to true, "urgent" to true, "dropped" to false),
        htmlBody = listOf(EmailBodyPart(partId = "1", size = 1000, type = "text/html")),
        textBody = listOf(EmailBodyPart(partId = "1", size = 1000, type = "text/html")),
        attachments = listOf(EmailBodyPart(partId = "2", size = 2048, type = "application/pdf", name = "report.pdf")),
    )

    private val headers = listOf(
        EmailHeader("Return-Path", " <bounce@news.example>"),
        EmailHeader("Delivered-To", " reader@fleet.example"),
        EmailHeader("Received", " from relay.example by mx.fleet.example; Mon, 02 Nov 2026 10:20:00 +0000"),
        EmailHeader("Received", " from app.news.example by relay.example; Mon, 02 Nov 2026 10:00:05 +0000"),
        EmailHeader("Authentication-Results", " relay.example; spf=fail smtp.mailfrom=forged.example"),
        EmailHeader(
            "Authentication-Results",
            " mx.fleet.example; spf=pass smtp.mailfrom=bounce@news.example;\r\n dkim=pass header.d=news.example header.s=sel1; dmarc=pass header.from=news.example",
        ),
        EmailHeader("Received-SPF", " pass (mx.fleet.example: domain of bounce@news.example)"),
        EmailHeader("DKIM-Signature", " v=1; a=rsa-sha256; d=news.example; s=sel1;\r\n h=from:to; bh=abc; b=def"),
        EmailHeader("List-Id", " <news.news.example>"),
        EmailHeader("Date", " Mon, 02 Nov 2026 10:00:00 +0000"),
        EmailHeader("X-Custom", " kept"),
    )

    private fun details(e: Email = email, h: List<EmailHeader> = headers, ids: Set<String> = names.keys): List<DetailSection> =
        messageDetails(
            email = e,
            headers = h,
            tags = everyTagOf(e, ids, mailboxes) { it.name },
            mailboxIds = ids,
            mailboxNames = names,
            projectLabel = "Project tags",
            metaLabel = "Meta tags",
            formatTime = time,
            formatSize = size,
        )

    private fun List<DetailSection>.value(title: String, label: String): String? =
        firstOrNull { it.title == title }?.lines?.firstOrNull { it.label == label }?.value

    @Test fun `the details name every group, in order`() {
        assertEquals(
            listOf(
                DetailTitles.MESSAGE, DetailTitles.TAGS, DetailTitles.DATES, DetailTitles.SIZE,
                DetailTitles.AUTHENTICATION, DetailTitles.IDENTIFIERS, DetailTitles.HEADERS,
            ),
            details().map { it.title },
        )
    }

    @Test fun `the tags are split into the two families, and the Inbox tag is kept`() {
        val d = details()
        assertEquals("1 Website", d.value(DetailTitles.TAGS, "Project tags"))
        assertEquals("Fa Gov, Inbox, urgent", d.value(DetailTitles.TAGS, "Meta tags"))
        assertEquals("\$seen, urgent", d.value(DetailTitles.TAGS, DetailLabels.KEYWORDS))
        assertEquals("m1 (Inbox), m2 (1 Website), m3 (Fa Gov)", d.value(DetailTitles.IDENTIFIERS, DetailLabels.MAILBOX_IDS))
    }

    @Test fun `every date, the size and every identifier are shown`() {
        val d = details()
        assertEquals("T${parseIsoMillis("2026-11-02T10:20:00Z")} [2026-11-02T10:20:00Z]", d.value(DetailTitles.DATES, DetailLabels.RECEIVED_AT))
        assertTrue(d.value(DetailTitles.DATES, DetailLabels.SENT_AT)!!.endsWith("[Mon, 02 Nov 2026 10:00:00 +0000]"))
        assertEquals("Mon, 02 Nov 2026 10:20:00 +0000", d.value(DetailTitles.DATES, "${DetailLabels.RECEIVED_HOP} 1"))
        assertEquals("Mon, 02 Nov 2026 10:00:05 +0000", d.value(DetailTitles.DATES, "${DetailLabels.RECEIVED_HOP} 2"))
        // The html part is listed under two bodies and counted once.
        assertEquals(3048L, messageSizeBytes(email))
        assertEquals("3048B (3048 bytes)", d.value(DetailTitles.SIZE, DetailLabels.SIZE))
        assertEquals("<abc@news.example>", d.value(DetailTitles.IDENTIFIERS, DetailLabels.MESSAGE_ID))
        assertEquals("<prev@fleet.example>", d.value(DetailTitles.IDENTIFIERS, DetailLabels.IN_REPLY_TO))
        assertEquals("<root@fleet.example> <prev@fleet.example>", d.value(DetailTitles.IDENTIFIERS, DetailLabels.REFERENCES))
        assertEquals("t1", d.value(DetailTitles.IDENTIFIERS, DetailLabels.THREAD_ID))
        assertEquals("e1", d.value(DetailTitles.IDENTIFIERS, DetailLabels.EMAIL_ID))
        assertEquals("blob1", d.value(DetailTitles.IDENTIFIERS, DetailLabels.BLOB_ID))
        assertEquals("report.pdf (2048B)", d.value(DetailTitles.MESSAGE, DetailLabels.ATTACHMENTS))
    }

    @Test fun `the receiving server's verdicts and the signing key are shown`() {
        val d = details()
        // The LAST Authentication-Results is the reader's own server; the relay's claim is not a verdict.
        assertEquals("spf=pass smtp.mailfrom=bounce@news.example", d.value(DetailTitles.AUTHENTICATION, DetailLabels.SPF))
        assertEquals("dkim=pass header.d=news.example header.s=sel1", d.value(DetailTitles.AUTHENTICATION, DetailLabels.DKIM))
        assertEquals("dmarc=pass header.from=news.example", d.value(DetailTitles.AUTHENTICATION, DetailLabels.DMARC))
        assertEquals("d=news.example; s=sel1; a=rsa-sha256", d.value(DetailTitles.AUTHENTICATION, DetailLabels.DKIM_KEY))
        val auth = d.first { it.title == DetailTitles.AUTHENTICATION }.lines
        assertEquals("both receiver results are listed in full", 2, auth.count { it.label == DetailLabels.AUTHENTICATION_RESULTS })
        assertEquals(1, auth.count { it.label == DetailLabels.RECEIVED_SPF })
    }

    @Test fun `every header is listed, in order, duplicates kept`() {
        val all = details().first { it.title == DetailTitles.HEADERS }.lines
        assertEquals(headers.map { it.name }, all.map { it.label })
        assertEquals("kept", all.last().value)
    }

    @Test fun `Copy all carries every line of every group`() {
        val d = details()
        val text = detailsText(d)
        d.forEach { section ->
            assertTrue(text.contains("== ${section.title} =="))
            section.lines.forEach { assertTrue("${it.label} missing from the copy", text.contains("${it.label}: ${it.value}")) }
        }
    }

    // ---- 5. nothing can crash it ---------------------------------------------------------------

    @Test fun `a message carrying nothing shows only what it has, and no blank line`() {
        val bare = messageDetails(Email(id = "e1"), emptyList(), emptyList(), emptySet(), emptyMap(), "P", "M", time, size)
        assertEquals(listOf(DetailTitles.IDENTIFIERS), bare.map { it.title })
        assertEquals(listOf(DetailLabels.EMAIL_ID), bare.single().lines.map { it.label })
    }

    @Test fun `malformed input yields fewer lines, never an exception`() {
        val broken = listOf(
            EmailHeader("", "orphan"), EmailHeader("Date", "not a date"), EmailHeader("Authentication-Results", ";;;=;"),
            EmailHeader("DKIM-Signature", "garbage"), EmailHeader("Received", "no semicolon"), EmailHeader("List-Id", "   "),
        )
        val odd = Email(id = "", receivedAt = "yesterday", from = listOf(EmailAddress(null, "")), keywords = mapOf("" to true))
        val d = messageDetails(odd, broken, listOf(MessageTag(TagKind.KEYWORD, "", "")), setOf(""), emptyMap(), "P", "M", { error("clock") }, { error("size") })
        assertTrue(d.flatMap { it.lines }.none { it.value.isBlank() || it.label.isBlank() })
        assertEquals("yesterday", d.value(DetailTitles.DATES, DetailLabels.RECEIVED_AT))
        assertEquals("not a date", d.value(DetailTitles.DATES, DetailLabels.SENT_AT))
        assertNull(d.value(DetailTitles.AUTHENTICATION, DetailLabels.DKIM_KEY))
        assertTrue(authVerdicts(null).isEmpty() && authVerdicts("  ").isEmpty())
        assertNull(dkimSigningKey(null))
        assertEquals(0L, messageSizeBytes(Email(id = "x")))
    }

    // ---- 6. field parity with the previous panel (nothing removed) -------------------------------

    /** Every row the sender panel shows is also in the details, with the same value. */
    @Test fun `every row of the sender panel is in the details`() {
        val old = messageMetadata(
            email, headers,
            receivedAtMillis = parseIsoMillis(email.receivedAt),
            sentAtMillis = parseHeaderDateMillis(sentAtHeader(headers)),
            formatTime = time,
        )
        assertEquals("the fixture must exercise every row the sender panel can show", MetadataLabel.values().toSet(), old.map { it.label }.toSet())
        val text = detailsText(details())
        old.forEach { row -> assertTrue("${row.label} (${row.value}) is not in the details", text.contains(row.value)) }
    }

    /** The sender panel itself still shows every row it did. */
    @Test fun `the sender panel keeps every row it had`() {
        val group = topLevel("private fun MetadataGroup(")
        MetadataLabel.values().forEach { assertTrue("$it left the sender panel", group.contains("MetadataLabel.$it ->")) }
        val sheet = topLevel("private fun ParticipantsSheet(")
        listOf(
            "ReceivedAtGroup(deliveredTo)", "OriginalSenderGroup(originalSender)",
            "ParticipantGroup(R.string.participants_from,", "ParticipantGroup(R.string.participants_to,",
            "ParticipantGroup(R.string.participants_cc,", "ParticipantGroup(R.string.participants_bcc,",
            "ParticipantGroup(R.string.participants_reply_to,", "MetadataGroup(metadata)",
        ).forEach { assertTrue("$it left the participants panel", sheet.contains(it)) }
    }

    /** What the reading header drew before #1661, block by block: each one is still drawn. */
    @Test fun `the reading header keeps every block it had`() {
        val header = topLevel("private fun MessageHeader(")
        PREVIOUS_HEADER_BLOCKS.forEach { assertTrue("the header no longer draws: $it", header.contains(it)) }
        val pill = topLevel("private fun TagPill(")
        assertTrue(pill.contains("tag.label") && pill.contains("TagKind.MAILBOX -> Icons.Filled.Folder"))
    }

    /** The toolbar and overflow actions the ticket names are untouched. */
    @Test fun `the reader actions are all still wired`() {
        val screen = SCREEN
        listOf(
            "onReply(\"replyAll\", replyTargetId, accountId)", "viewModel.toggleFlag()", "movePicker = true",
            "labelSheet = true", "LabelSheet(", "viewModel::askUnsubscribe", "viewModel.viewHeaders()",
            "MessageHeadersSheet(", "viewModel.markUnread(onBack)",
        ).forEach { assertTrue("the reader lost: $it", screen.contains(it)) }
    }

    private companion object {
        const val UI = "app/src/main/kotlin/app/sterna/ui/message"

        val ROOT: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "$UI/MessageScreen.kt").isFile }
                ?: error("cannot locate $UI/MessageScreen.kt from ${File("").absolutePath}")
        }

        val SCREEN: String by lazy { File(ROOT, "$UI/MessageScreen.kt").readText() }

        /** A top-level declaration, from its first line to the closing brace at column 0. */
        fun topLevel(declaration: String): String {
            val start = SCREEN.indexOf(declaration)
            check(start >= 0) { "$declaration is gone from MessageScreen.kt" }
            val end = SCREEN.indexOf("\n}\n", start)
            check(end > start) { "cannot find the end of $declaration" }
            return SCREEN.substring(start, end)
        }

        /** The blocks of the reading header as they were before the redesign. */
        val PREVIOUS_HEADER_BLOCKS = listOf(
            "R.string.message_no_subject",
            ".combinedClickable(",
            "onLongClickLabel = stringResource(R.string.message_copy_address),",
            "clipboard.setText(AnnotatedString(formatAddress(it)))",
            "onClick = { showParticipants = true },",
            "Monogram(",
            "formatFull(msg.header.receivedAt)",
            "R.string.message_received_at, deliveredToLine",
            "R.string.a11y_pgp_encrypted",
            "R.string.a11y_pgp_signature",
            "R.string.a11y_flagged",
            "R.string.a11y_has_attachment",
            "readingActions()",
            "ResumeBox(LocalReaderSummary.current, msg.id)",
            "PgpStatusCard(crypto, onCryptoAction)",
            "AttachmentSection(",
            "CalendarEventCard(",
            "UnsubscribeStrip(options, unsubscribeState, onUnsubscribe)",
            "ReadReceiptStrip(",
            "ParticipantsSheet(",
            "metadata = metadata,",
            "onOpened = onSenderPanelOpened,",
            "originalSender = originalSenderToShow(full?.originalSender, msg.header.from),",
        )
    }
}
