package app.sterna.navwalk

import app.sterna.send.delivery.DeliveryKey
import app.sterna.send.delivery.DeliveryStatusSource
import app.sterna.send.delivery.RecipientDelivery
import app.sterna.send.delivery.RecipientState
import app.sterna.send.delivery.ServerDeliveryReport
import app.sterna.send.delivery.ServerDeliveryReport.SenderAuth
import app.sterna.send.delivery.ServerDeliveryReport.Submission

/**
 * #1802 emulator proof: answers the Sent rows' delivery status by the seeded Message-ID's local part, one scene per
 * stage, so the screenshot shows every mark the ticker can draw. Bound by `--ez deliverystatus true` in the
 * `-PtestApp` build only; the real app always asks the fleet mail API.
 */
object ScriptedDeliveryStatus : DeliveryStatusSource {

    /** The seeded Sent mails, newest first: Message-ID local part -> subject. */
    val SCENES = listOf(
        "delivered" to "Delivered to every recipient",
        "relay" to "Accepted by the relay, awaiting confirmation",
        "deferred" to "Deferred by the recipient's server",
        "bounced" to "Bounced: no such user",
        "badsender" to "Sender not authorised for this From",
        "refused" to "Refused at submission",
        "norecord" to "No server record yet",
    )

    private const val T0 = 1_763_460_000_000L

    private val ok = Submission(accepted = true, atMillis = T0 + 2_000, smtpCode = 250, messageId = null, detail = "queue Q-navwalk")
    private val signed = SenderAuth(true, true, true, true, atMillis = T0 + 3_000, dkimSelector = "navwalk", spfGating = false)

    override suspend fun fetch(key: DeliveryKey): ServerDeliveryReport? {
        val scene = key.messageId.trim('<', '>').substringBefore('@')
        val sub = ok.copy(messageId = key.messageId)
        return when (scene) {
            "delivered" -> ServerDeliveryReport(sub, signed, listOf(
                RecipientDelivery("robin@example.net", RecipientState.DELIVERED, T0 + 4_000, 250, "mx.example.net", "250 2.0.0 OK", "mx"),
                RecipientDelivery("sam@example.com", RecipientState.DELIVERED, T0 + 5_000, 250, "mx.example.com", "250 2.0.0 OK", "mx"),
            ))
            "relay" -> ServerDeliveryReport(sub, signed, listOf(
                RecipientDelivery("alex@example.org", RecipientState.PENDING, T0 + 4_000, 250, "relay.oci", "250 accepted by relay", "relay"),
            ))
            "deferred" -> ServerDeliveryReport(sub, signed, listOf(
                RecipientDelivery("kim@example.io", RecipientState.DEFERRED, T0 + 4_000, 451, "mx.example.io", "451 4.7.1 greylisted, try again later", "queue"),
            ))
            "bounced" -> ServerDeliveryReport(sub, signed, listOf(
                RecipientDelivery("nobody@example.net", RecipientState.BOUNCED, T0 + 4_000, 550, "mx.example.net", "550 5.1.1 no such user", "dsn"),
            ))
            "badsender" -> ServerDeliveryReport(sub, SenderAuth(
                accountMayUseFrom = false, dkimSigned = true, spfAligned = true, dmarcAligned = true,
                atMillis = T0 + 3_000, spfGating = false, serverFailed = true, detail = "the account may not send as ceo@example.org",
            ))
            "refused" -> ServerDeliveryReport(Submission(accepted = false, atMillis = T0 + 2_000, smtpCode = 554, detail = "554 5.7.1 relay access denied"))
            else -> null
        }
    }
}
