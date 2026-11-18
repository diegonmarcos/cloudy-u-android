package app.sterna.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import app.sterna.R
import app.sterna.send.delivery.DeliveryReason
import app.sterna.send.delivery.DeliveryStage
import app.sterna.send.delivery.DeliveryTimeline
import app.sterna.send.delivery.RecipientDelivery
import app.sterna.send.delivery.RecipientState
import app.sterna.send.delivery.SenderCheck
import app.sterna.send.delivery.StageMark
import app.sterna.send.delivery.StageView
import app.sterna.ui.theme.MailMetrics
import app.sterna.util.MailDates
import java.time.Instant

/** Test tags: the ticker, and each mark by stage (`delivery-mark-SENT`, ...). */
object DeliveryTickerTags {
    const val TICKER = "delivery-ticker"
    fun mark(stage: DeliveryStage) = "delivery-mark-${stage.name}"
    const val TIMELINE = "delivery-timeline"
}

/**
 * The glyph each mark draws. Characters, not icons, so the ticker stays one text run wide and the
 * render tests read it back: lit, open, half (deferred), X (the first failure), dim dot (not yet).
 */
internal fun markGlyph(mark: StageMark): String = when (mark) {
    is StageMark.Confirmed -> "●"
    StageMark.Pending -> "○"
    StageMark.NotReached -> "·"
    is StageMark.Deferred -> "◐"
    is StageMark.Failed -> "✕"
}

@Composable
internal fun markColor(mark: StageMark, ink: Color): Color = when (mark) {
    is StageMark.Confirmed -> MaterialTheme.colorScheme.primary
    is StageMark.Failed -> MaterialTheme.colorScheme.error
    is StageMark.Deferred -> MaterialTheme.colorScheme.tertiary
    StageMark.Pending -> ink
    StageMark.NotReached -> ink.copy(alpha = 0.4f)
}

@Composable
internal fun stageName(stage: DeliveryStage): String = stringResource(
    when (stage) {
        DeliveryStage.LEFT_PHONE -> R.string.delivery_stage_left_phone
        DeliveryStage.SERVER_RECEIVED -> R.string.delivery_stage_server_received
        DeliveryStage.VALID_SENDER -> R.string.delivery_stage_valid_sender
        DeliveryStage.SENT -> R.string.delivery_stage_sent
    },
)

@Composable
internal fun markWord(mark: StageMark): String = stringResource(
    when (mark) {
        is StageMark.Confirmed -> R.string.delivery_mark_confirmed
        StageMark.Pending -> R.string.delivery_mark_pending
        StageMark.NotReached -> R.string.delivery_mark_not_reached
        is StageMark.Deferred -> R.string.delivery_mark_deferred
        is StageMark.Failed -> R.string.delivery_mark_failed
    },
)

@Composable
internal fun reasonText(reason: DeliveryReason): String = when (reason) {
    is DeliveryReason.PhoneFailed -> listOfNotNull(stringResource(R.string.delivery_reason_phone), reason.detail).joinToString(": ")
    is DeliveryReason.SubmissionRefused -> listOfNotNull(
        stringResource(R.string.delivery_reason_refused),
        reason.smtpCode?.let { stringResource(R.string.delivery_smtp_code, it) },
        reason.detail,
    ).joinToString(": ")
    is DeliveryReason.SenderRejected -> listOfNotNull(
        stringResource(R.string.delivery_reason_sender),
        reason.failed.map { checkText(it) }.joinToString(", ").ifEmpty { null },
        reason.detail,
    ).joinToString(": ")
    is DeliveryReason.Bounced -> stringResource(R.string.delivery_reason_bounced) + ": " +
        reason.recipients.joinToString(", ") { it.address }
    is DeliveryReason.DeferredBy -> stringResource(R.string.delivery_reason_deferred) + ": " +
        reason.recipients.joinToString(", ") { it.address }
}

@Composable
private fun checkText(check: SenderCheck): String = stringResource(
    when (check) {
        SenderCheck.ACCOUNT_MAY_USE_FROM -> R.string.delivery_check_from
        SenderCheck.DKIM_SIGNED -> R.string.delivery_check_dkim
        SenderCheck.SPF_ALIGNED -> R.string.delivery_check_spf
        SenderCheck.DMARC_ALIGNED -> R.string.delivery_check_dmarc
    },
)

@Composable
private fun recipientStateWord(state: RecipientState): String = stringResource(
    when (state) {
        RecipientState.PENDING -> R.string.delivery_recipient_pending
        RecipientState.DELIVERED -> R.string.delivery_recipient_delivered
        RecipientState.DEFERRED -> R.string.delivery_recipient_deferred
        RecipientState.BOUNCED -> R.string.delivery_recipient_bounced
    },
)

private fun StageMark.atMillis(): Long? = when (this) {
    is StageMark.Confirmed -> atMillis
    is StageMark.Deferred -> atMillis
    is StageMark.Failed -> atMillis
    else -> null
}

private fun StageMark.reason(): DeliveryReason? = when (this) {
    is StageMark.Deferred -> reason
    is StageMark.Failed -> reason
    else -> null
}

private fun stamp(millis: Long?): String? = millis?.let { MailDates.formatFull(Instant.ofEpochMilli(it).toString()) }

/**
 * #1802: the Sent row's four marks, drawn just right of the sent time. Tapping opens the stage
 * detail (the first failure's reason, a deferral's reason, every recipient's outcome).
 */
@Composable
fun DeliveryTicker(timeline: DeliveryTimeline, ink: Color, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = modifier
            .testTag(DeliveryTickerTags.TICKER)
            .clickable { open = true }
            .padding(horizontal = MailMetrics.s2),
        horizontalArrangement = Arrangement.spacedBy(MailMetrics.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (view in timeline.stages) {
            val a11y = stringResource(R.string.delivery_mark_a11y, stageName(view.stage), markWord(view.mark))
            Text(
                text = markGlyph(view.mark),
                style = MaterialTheme.typography.labelMedium,
                color = markColor(view.mark, ink),
                fontWeight = if (view.mark is StageMark.Failed) FontWeight.Bold else null,
                modifier = Modifier
                    .testTag(DeliveryTickerTags.mark(view.stage))
                    .semantics { contentDescription = a11y },
            )
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.delivery_close)) } },
            title = { Text(stringResource(R.string.delivery_title)) },
            text = { DeliveryTimelineView(timeline) },
        )
    }
}

/**
 * #1802: the message view's timeline: each stage with its mark, word, timestamp and reason; the
 * server's message id under stage 2; every recipient's outcome under stage 4. The same view is the
 * ticker's tap detail, so the two can never disagree.
 */
@Composable
fun DeliveryTimelineView(timeline: DeliveryTimeline, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Column(modifier.testTag(DeliveryTickerTags.TIMELINE), verticalArrangement = Arrangement.spacedBy(MailMetrics.s6)) {
        for (view in timeline.stages) StageLine(view, ink)
    }
}

@Composable
private fun StageLine(view: StageView, ink: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Text(markGlyph(view.mark), color = markColor(view.mark, ink), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(MailMetrics.s8))
        Column {
            Text(
                text = stageName(view.stage) + " — " + markWord(view.mark),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            stamp(view.mark.atMillis())?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            view.mark.reason()?.let { Text(reasonText(it), style = MaterialTheme.typography.bodySmall, color = markColor(view.mark, ink)) }
            view.messageId?.let { Text(stringResource(R.string.delivery_message_id, it), style = MaterialTheme.typography.bodySmall) }
            for (r in view.recipients) RecipientLine(r)
        }
    }
}

@Composable
private fun RecipientLine(r: RecipientDelivery) {
    val parts = listOfNotNull(
        r.address,
        recipientStateWord(r.state),
        r.smtpCode?.let { stringResource(R.string.delivery_smtp_code, it) },
        r.remoteMx,
        stamp(r.atMillis),
        r.detail,
    )
    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = MailMetrics.s8))
}
