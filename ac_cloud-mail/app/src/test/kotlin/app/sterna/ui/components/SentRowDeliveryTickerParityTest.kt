package app.sterna.ui.components

import android.app.Application
import app.sterna.util.MailDates
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.filter
import app.sterna.R
import app.sterna.core.data.settings.AppStyle
import app.sterna.core.data.settings.ThemeMode
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.send.delivery.DeliveryStage
import app.sterna.send.delivery.DeliveryStateMachine
import app.sterna.send.delivery.PhoneDelivery
import app.sterna.send.delivery.RecipientDelivery
import app.sterna.send.delivery.RecipientState
import app.sterna.send.delivery.ServerDeliveryReport
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

/**
 * #1802 parity (rule 16): the Sent row with the delivery ticker against the same row without it,
 * rendered side by side in each list style. Every text, description and tag of the plain row must
 * still be in the ticker row; the ONLY additions are the ticker and its four marks; the ticker sits
 * right of the sent time on the same line; a tap opens the stages with the failure's reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SentRowDeliveryTickerParityTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager)
                    .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private val app get() = RuntimeEnvironment.getApplication()

    private val sent = Email(
        id = "s1",
        accountId = "a",
        subject = "Quarterly figures",
        preview = "Attached are the figures we discussed on Monday.",
        receivedAt = "2026-09-24T10:00:00Z",
        from = listOf(EmailAddress(name = "Me", email = "me@example.org")),
        to = listOf(EmailAddress(name = "Robin", email = "robin@example.net")),
        keywords = mapOf("\$seen" to true),
    )

    // Stage 4 bounced: the ticker shows three lit marks and an X.
    private val bounced = RecipientDelivery("robin@example.net", RecipientState.BOUNCED, 4_000, 550, "mx.example.net", "no such user")
    private val timeline = DeliveryStateMachine.timeline(
        PhoneDelivery(queuedInOutbox = false, handedOffAtMillis = 1_000),
        ServerDeliveryReport(
            ServerDeliveryReport.Submission(accepted = true, atMillis = 2_000, smtpCode = 250, messageId = "<s1@fleet>"),
            ServerDeliveryReport.SenderAuth(true, true, true, true, atMillis = 3_000),
            listOf(bounced),
        ),
    )

    private fun show(style: AppStyle) {
        compose.setContent {
            SternaTheme(themeMode = ThemeMode.DARK, appStyle = style) {
                CompositionLocalProvider(LocalListMonogram provides false) {
                    Column {
                        EmailListItem(email = sent, onClick = {}, showRecipients = true, onLoadMessage = { it },
                            onOpenAttachment = {}, modifier = Modifier.testTag(PLAIN))
                        EmailListItem(email = sent, onClick = {}, showRecipients = true, onLoadMessage = { it },
                            onOpenAttachment = {}, modifier = Modifier.testTag(TICKED), deliveryTimeline = timeline)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Every text, content description and test tag under [root], as "kind:value" strings. */
    private fun fields(root: SemanticsNode): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { out += "text:${it.text}" }
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { out += "desc:$it" }
            n.config.getOrNull(SemanticsProperties.TestTag)?.let { out += "tag:$it" }
            n.children.forEach(::walk)
        }
        root.children.forEach(::walk)
        return out
    }

    private fun row(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()

    private fun assertParity(style: AppStyle) {
        show(style)
        val plain = fields(row(PLAIN))
        val ticked = fields(row(TICKED))
        assertTrue("$style: the plain row must carry fields, or parity proves nothing", plain.size >= 4)
        val lost = plain.groupingBy { it }.eachCount().filter { (f, n) -> ticked.count { it == f } < n }
        assertEquals("$style: fields lost from the Sent row", emptyMap<String, Int>(), lost)

        val added = ticked.toMutableList().apply { plain.forEach { remove(it) } }
        val expected = buildList {
            add("tag:${DeliveryTickerTags.TICKER}")
            for (s in DeliveryStage.entries) add("tag:${DeliveryTickerTags.mark(s)}")
            add("text:●"); add("text:●"); add("text:●"); add("text:✕")
            for (s in DeliveryStage.entries) {
                val word = app.getString(if (s == DeliveryStage.SENT) R.string.delivery_mark_failed else R.string.delivery_mark_confirmed)
                add("desc:" + app.getString(R.string.delivery_mark_a11y, app.getString(stageRes(s)), word))
            }
        }
        assertEquals("$style: the ticker is the only addition", expected.sorted(), added.sorted())

        // Right after the sent time, on its line.
        val time = compose.onAllNodesWithText(sentTime(), useUnmergedTree = true)
            .filter(hasAnyAncestor(hasTestTag(TICKED))).fetchSemanticsNodes().single().boundsInRoot
        val ticker = compose.onAllNodes(hasTestTag(DeliveryTickerTags.TICKER), useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot
        assertTrue("$style: ticker right of the time", ticker.left >= time.right - 0.5f)
        assertTrue("$style: ticker on the time's line", ticker.top < time.bottom && ticker.bottom > time.top)

        // Tap: the stages with the bounce reason and the recipient's detail.
        compose.onNodeWithTag(DeliveryTickerTags.TICKER, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(DeliveryTickerTags.TIMELINE, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.delivery_reason_bounced) + ": robin@example.net", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText("no such user", substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.delivery_message_id, "<s1@fleet>"), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    private fun sentTime() = MailDates.formatListDate(sent.receivedAt)

    private fun stageRes(s: DeliveryStage) = when (s) {
        DeliveryStage.LEFT_PHONE -> R.string.delivery_stage_left_phone
        DeliveryStage.SERVER_RECEIVED -> R.string.delivery_stage_server_received
        DeliveryStage.VALID_SENDER -> R.string.delivery_stage_valid_sender
        DeliveryStage.SENT -> R.string.delivery_stage_sent
    }

    @Test fun `cloud dark Sent row keeps every field and adds only the ticker`() = assertParity(AppStyle.CLOUD_DARK)

    @Test fun `gmail dark Sent row keeps every field and adds only the ticker`() = assertParity(AppStyle.GMAIL_DARK)

    @Test fun `cloud eink Sent row keeps every field and adds only the ticker`() = assertParity(AppStyle.CLOUD_EINK)

    private companion object {
        const val PLAIN = "sent-row-plain"
        const val TICKED = "sent-row-ticked"
    }
}
