package app.sterna.send.delivery

import app.sterna.send.delivery.DeliveryStage.LEFT_PHONE
import app.sterna.send.delivery.DeliveryStage.SENT
import app.sterna.send.delivery.DeliveryStage.SERVER_RECEIVED
import app.sterna.send.delivery.DeliveryStage.VALID_SENDER
import app.sterna.send.delivery.ServerDeliveryReport.SenderAuth
import app.sterna.send.delivery.ServerDeliveryReport.Submission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1802: the stage state machine. Every stage, every failure, the ordering. Each case builds the
 * phone's state and the server's records and checks all four marks, so a regression in one stage's
 * rule cannot hide behind another stage's.
 */
class DeliveryStateMachineTest {

    private val left = PhoneDelivery(queuedInOutbox = false, handedOffAtMillis = 1_000)
    private val accepted = Submission(accepted = true, atMillis = 2_000, smtpCode = 250, messageId = "<m1@fleet>")
    private val signed = SenderAuth(true, true, true, true, atMillis = 3_000, dkimSelector = "s1")
    private fun rcpt(a: String, s: RecipientState, at: Long? = null, code: Int? = null, detail: String? = null) =
        RecipientDelivery(a, s, atMillis = at, smtpCode = code, remoteMx = "mx.$a", detail = detail)

    private fun marks(t: DeliveryTimeline) = t.stages.map { it.mark }

    // ── ordering ──────────────────────────────────────────────────────────────────────────────

    @Test fun `a timeline always holds the four stages in declared order`() {
        val t = DeliveryStateMachine.timeline(left, null)
        assertEquals(listOf(LEFT_PHONE, SERVER_RECEIVED, VALID_SENDER, SENT), t.stages.map { it.stage })
    }

    @Test fun `the timeline refuses stages out of order`() {
        val ok = DeliveryStateMachine.timeline(left, null).stages
        val thrown = runCatching { DeliveryTimeline(ok.reversed()) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test fun `a later record never lights a mark while an earlier stage is open`() {
        // The server reports everything delivered, but the phone still holds the message: only
        // stage 1 may show, pending, and the rest are not reached.
        val t = DeliveryStateMachine.timeline(
            PhoneDelivery(queuedInOutbox = true),
            ServerDeliveryReport(accepted, signed, listOf(rcpt("a@x", RecipientState.DELIVERED, 4_000))),
        )
        assertEquals(listOf(StageMark.Pending, StageMark.NotReached, StageMark.NotReached, StageMark.NotReached), marks(t))
    }

    @Test fun `a missing submission record holds stages 3 and 4 back even when their records exist`() {
        val t = DeliveryStateMachine.timeline(left, ServerDeliveryReport(null, signed, listOf(rcpt("a@x", RecipientState.DELIVERED))))
        assertEquals(listOf(StageMark.Confirmed(1_000), StageMark.Pending, StageMark.NotReached, StageMark.NotReached), marks(t))
    }

    @Test fun `only the first failure is an X and every later stage is not reached`() {
        val t = DeliveryStateMachine.timeline(
            left,
            ServerDeliveryReport(
                Submission(accepted = false, atMillis = 2_000, smtpCode = 554, detail = "relay denied"),
                SenderAuth(false, false, false, false),
                listOf(rcpt("a@x", RecipientState.BOUNCED)),
            ),
        )
        assertEquals(SERVER_RECEIVED, t.failedStage?.stage)
        assertEquals(1, t.stages.count { it.mark is StageMark.Failed })
        assertEquals(StageMark.NotReached, t[VALID_SENDER].mark)
        assertEquals(StageMark.NotReached, t[SENT].mark)
    }

    // ── stage 1: left the phone ───────────────────────────────────────────────────────────────

    @Test fun `stage 1 pending while queued in the outbox`() {
        val t = DeliveryStateMachine.timeline(PhoneDelivery(queuedInOutbox = true), null)
        assertEquals(listOf(StageMark.Pending, StageMark.NotReached, StageMark.NotReached, StageMark.NotReached), marks(t))
        assertFalse(t.isTerminal)
    }

    @Test fun `stage 1 confirmed once out of the outbox, stage 2 then waits on the server`() {
        val t = DeliveryStateMachine.timeline(left, null)
        assertEquals(listOf(StageMark.Confirmed(1_000), StageMark.Pending, StageMark.NotReached, StageMark.NotReached), marks(t))
    }

    @Test fun `stage 1 X when the outbox gave up, with its own text`() {
        val t = DeliveryStateMachine.timeline(PhoneDelivery(queuedInOutbox = true, failure = "auth failed"), null)
        assertEquals(StageMark.Failed(null, DeliveryReason.PhoneFailed("auth failed")), t[LEFT_PHONE].mark)
        assertTrue(t.isTerminal)
    }

    @Test fun `stage 1 X without text when the outbox failed silently`() {
        val t = DeliveryStateMachine.timeline(PhoneDelivery(queuedInOutbox = false, failed = true), null)
        assertEquals(StageMark.Failed(null, DeliveryReason.PhoneFailed(null)), t[LEFT_PHONE].mark)
    }

    // ── stage 2: server received ──────────────────────────────────────────────────────────────

    @Test fun `stage 2 confirmed by SMTP 250 and carries the message id`() {
        val t = DeliveryStateMachine.timeline(left, ServerDeliveryReport(submission = accepted))
        assertEquals(StageMark.Confirmed(2_000), t[SERVER_RECEIVED].mark)
        assertEquals("<m1@fleet>", t[SERVER_RECEIVED].messageId)
        assertEquals(StageMark.Pending, t[VALID_SENDER].mark)
        assertEquals(StageMark.NotReached, t[SENT].mark)
    }

    @Test fun `stage 2 X when the submission was refused, code and text kept`() {
        val t = DeliveryStateMachine.timeline(left, ServerDeliveryReport(Submission(false, 2_000, 535, detail = "bad auth")))
        assertEquals(StageMark.Failed(2_000, DeliveryReason.SubmissionRefused(535, "bad auth")), t[SERVER_RECEIVED].mark)
    }

    // ── stage 3: valid sender ─────────────────────────────────────────────────────────────────

    @Test fun `stage 3 confirmed only when all four checks are recorded true`() {
        val t = DeliveryStateMachine.timeline(left, ServerDeliveryReport(accepted, signed))
        assertEquals(StageMark.Confirmed(3_000), t[VALID_SENDER].mark)
        assertEquals(StageMark.Pending, t[SENT].mark)
    }

    @Test fun `stage 3 pending while any check is unrecorded`() {
        for (check in SenderCheck.entries) {
            // This one check unrecorded (null), the other three recorded true.
            val auth = SenderAuth(
                accountMayUseFrom = true.takeIf { check != SenderCheck.ACCOUNT_MAY_USE_FROM },
                dkimSigned = true.takeIf { check != SenderCheck.DKIM_SIGNED },
                spfAligned = true.takeIf { check != SenderCheck.SPF_ALIGNED },
                dmarcAligned = true.takeIf { check != SenderCheck.DMARC_ALIGNED },
            )
            assertEquals(check.name, StageMark.Pending, DeliveryStateMachine.validSender(auth))
        }
    }

    @Test fun `stage 3 X for each failing check alone`() {
        for (check in SenderCheck.entries) {
            val auth = SenderAuth(
                accountMayUseFrom = check != SenderCheck.ACCOUNT_MAY_USE_FROM,
                dkimSigned = check != SenderCheck.DKIM_SIGNED,
                spfAligned = check != SenderCheck.SPF_ALIGNED,
                dmarcAligned = check != SenderCheck.DMARC_ALIGNED,
                atMillis = 3_000,
                detail = "d",
            )
            val t = DeliveryStateMachine.timeline(left, ServerDeliveryReport(accepted, auth, listOf(rcpt("a@x", RecipientState.DELIVERED))))
            assertEquals(check.name, StageMark.Failed(3_000, DeliveryReason.SenderRejected(listOf(check), "d")), t[VALID_SENDER].mark)
            assertEquals(StageMark.NotReached, t[SENT].mark)
        }
    }

    @Test fun `stage 3 X names every failing check in order, a false wins over an unrecorded one`() {
        val auth = SenderAuth(accountMayUseFrom = true, dkimSigned = false, spfAligned = null, dmarcAligned = false)
        val mark = DeliveryStateMachine.validSender(auth)
        assertEquals(
            StageMark.Failed(null, DeliveryReason.SenderRejected(listOf(SenderCheck.DKIM_SIGNED, SenderCheck.DMARC_ALIGNED), null)),
            mark,
        )
    }

    // ── stage 4: sent ─────────────────────────────────────────────────────────────────────────

    private fun stage4(vararg r: RecipientDelivery) =
        DeliveryStateMachine.timeline(left, ServerDeliveryReport(accepted, signed, r.toList()))

    @Test fun `stage 4 pending with no recipient reported`() {
        assertEquals(StageMark.Pending, stage4()[SENT].mark)
    }

    @Test fun `stage 4 pending while any recipient is still pending`() {
        val t = stage4(rcpt("a@x", RecipientState.DELIVERED, 4_000), rcpt("b@y", RecipientState.PENDING))
        assertEquals(StageMark.Pending, t[SENT].mark)
        assertFalse(t.isTerminal)
    }

    @Test fun `stage 4 confirmed when every recipient was accepted, at the latest acceptance`() {
        val t = stage4(rcpt("a@x", RecipientState.DELIVERED, 4_000, 250), rcpt("b@y", RecipientState.DELIVERED, 5_000, 250))
        assertEquals(StageMark.Confirmed(5_000), t[SENT].mark)
        assertEquals(2, t[SENT].recipients.size)
        assertTrue(t.isTerminal)
        assertNull(t.failedStage)
    }

    @Test fun `stage 4 deferred mark with the deferred recipients as reason`() {
        val d = rcpt("b@y", RecipientState.DEFERRED, 6_000, 451, "greylisted")
        val t = stage4(rcpt("a@x", RecipientState.DELIVERED, 4_000), d)
        assertEquals(StageMark.Deferred(6_000, DeliveryReason.DeferredBy(listOf(d))), t[SENT].mark)
        assertFalse("a deferral keeps polling", t.isTerminal)
    }

    @Test fun `stage 4 X on a bounce, and a bounce wins over a deferral`() {
        val b = rcpt("c@z", RecipientState.BOUNCED, 7_000, 550, "no such user")
        val t = stage4(rcpt("a@x", RecipientState.DELIVERED), rcpt("b@y", RecipientState.DEFERRED), b)
        assertEquals(StageMark.Failed(7_000, DeliveryReason.Bounced(listOf(b))), t[SENT].mark)
        assertEquals(SENT, t.failedStage?.stage)
        assertTrue(t.isTerminal)
    }

    @Test fun `all four lit, in order, for a fully delivered message`() {
        val t = stage4(rcpt("a@x", RecipientState.DELIVERED, 4_000))
        assertEquals(
            listOf(StageMark.Confirmed(1_000), StageMark.Confirmed(2_000), StageMark.Confirmed(3_000), StageMark.Confirmed(4_000)),
            marks(t),
        )
    }
}
