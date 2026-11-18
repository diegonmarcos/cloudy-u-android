package app.sterna.send.delivery

/**
 * #1802: turns the phone's outbox state and the server's own records into the four marks. Pure,
 * so every stage, every failure and the ordering are unit-tested (DeliveryStateMachineTest).
 *
 * Rules, in order:
 *  1. Stages are judged first to last. A stage that is not CONFIRMED ends the walk: it shows its
 *     own mark (pending, deferred, or the X), and every stage after it shows [StageMark.NotReached],
 *     whatever its record says. So the ticker always lights left to right, and only the FIRST
 *     failure is an X.
 *  2. Stage 1 (left the phone): confirmed once the message is no longer queued in the outbox and
 *     the outbox did not fail it; failed when the outbox gave up.
 *  3. Stage 2 (server received): the submission log. accepted -> confirmed; refused -> X.
 *  4. Stage 3 (valid sender): confirmed only when ALL four checks are recorded true; any recorded
 *     false -> X naming every false check; any still unrecorded -> pending.
 *  5. Stage 4 (sent): per recipient. Any bounce -> X (bounces win over deferrals); otherwise any
 *     deferral -> deferred mark with the reason; otherwise every recipient delivered -> confirmed
 *     at the latest delivery time; otherwise (none reported, or some still pending) -> pending.
 */
object DeliveryStateMachine {

    fun timeline(phone: PhoneDelivery, server: ServerDeliveryReport?): DeliveryTimeline {
        val report = server ?: ServerDeliveryReport()
        val own = listOf(
            StageView(DeliveryStage.LEFT_PHONE, leftPhone(phone)),
            StageView(
                DeliveryStage.SERVER_RECEIVED,
                serverReceived(report.submission),
                messageId = report.submission?.messageId,
            ),
            StageView(DeliveryStage.VALID_SENDER, validSender(report.sender)),
            StageView(DeliveryStage.SENT, sent(report.recipients), recipients = report.recipients),
        )
        val firstOpen = own.indexOfFirst { it.mark !is StageMark.Confirmed }
        if (firstOpen < 0) return DeliveryTimeline(own)
        return DeliveryTimeline(
            own.mapIndexed { i, view -> if (i <= firstOpen) view else view.copy(mark = StageMark.NotReached) },
        )
    }

    internal fun leftPhone(phone: PhoneDelivery): StageMark = when {
        phone.failed -> StageMark.Failed(phone.handedOffAtMillis, DeliveryReason.PhoneFailed(phone.failure))
        phone.queuedInOutbox -> StageMark.Pending
        else -> StageMark.Confirmed(phone.handedOffAtMillis)
    }

    internal fun serverReceived(submission: ServerDeliveryReport.Submission?): StageMark = when {
        submission == null -> StageMark.Pending
        submission.accepted -> StageMark.Confirmed(submission.atMillis)
        else -> StageMark.Failed(
            submission.atMillis,
            DeliveryReason.SubmissionRefused(submission.smtpCode, submission.detail),
        )
    }

    internal fun validSender(auth: ServerDeliveryReport.SenderAuth?): StageMark {
        if (auth == null) return StageMark.Pending
        val answers = SenderCheck.entries.associateWith { auth.answer(it) }
        val failed = answers.filterValues { it == false }.keys.toList()
        return when {
            failed.isNotEmpty() -> StageMark.Failed(auth.atMillis, DeliveryReason.SenderRejected(failed, auth.detail))
            answers.values.any { it == null } -> StageMark.Pending
            else -> StageMark.Confirmed(auth.atMillis)
        }
    }

    internal fun sent(recipients: List<RecipientDelivery>): StageMark {
        val bounced = recipients.filter { it.state == RecipientState.BOUNCED }
        if (bounced.isNotEmpty()) {
            return StageMark.Failed(bounced.mapNotNull { it.atMillis }.maxOrNull(), DeliveryReason.Bounced(bounced))
        }
        val deferred = recipients.filter { it.state == RecipientState.DEFERRED }
        if (deferred.isNotEmpty()) {
            return StageMark.Deferred(deferred.mapNotNull { it.atMillis }.maxOrNull(), DeliveryReason.DeferredBy(deferred))
        }
        if (recipients.isNotEmpty() && recipients.all { it.state == RecipientState.DELIVERED }) {
            return StageMark.Confirmed(recipients.mapNotNull { it.atMillis }.maxOrNull())
        }
        return StageMark.Pending
    }
}
