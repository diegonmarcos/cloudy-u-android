package app.sterna.navwalk

import app.sterna.ui.compose.CheckOutcome
import app.sterna.ui.compose.CheckSource
import app.sterna.ui.compose.RecipientCheckItem
import app.sterna.ui.compose.RecipientCheckKind
import app.sterna.ui.compose.RecipientCheckRequest
import app.sterna.ui.compose.RecipientChecker
import app.sterna.ui.compose.RecipientStatus
import app.sterna.ui.compose.verdictOf

/**
 * #1803 emulator proof only (src/navWalk, compiled into the -PtestApp build alone): a checker that answers from the address's
 * DOMAIN, so the walk can shoot every verdict without the fleet endpoint. Bound by `--ez recipientcheck true` on the seed broadcast.
 *
 *   *@ok.example        every check passes                    -> ok
 *   *@catchall.example  mailbox accepts all                   -> risky
 *   *@bounce.example    bounced before                        -> risky
 *   *@nomx.example      domain does not accept mail           -> will fail
 *   *@big.example       message over the 1 KB size limit      -> will fail (once the body grows) / ok
 *   anything else       nothing known                         -> absent (unknown)
 */
object ScriptedRecipientChecker : RecipientChecker {
    override suspend fun check(request: RecipientCheckRequest): Map<String, RecipientStatus> {
        kotlinx.coroutines.delay(1_200) // long enough for the "checking" ring to be on the first shot
        return request.addresses.mapNotNull { a -> answer(a, request.messageSizeBytes)?.let { a to it } }.toMap()
    }

    private fun item(kind: RecipientCheckKind, outcome: CheckOutcome, detail: String? = null, acceptsAll: Boolean = false) =
        RecipientCheckItem(kind, outcome, detail, CheckSource.SERVER, acceptsAll)

    private fun answer(address: String, size: Long?): RecipientStatus? {
        val domain = address.substringAfterLast('@').lowercase()
        val pass = RecipientCheckKind.entries.associateWith { item(it, CheckOutcome.PASS) }.toMutableMap()
        when (domain) {
            "ok.example" -> Unit
            "catchall.example" -> pass[RecipientCheckKind.MAILBOX] =
                item(RecipientCheckKind.MAILBOX, CheckOutcome.WARN, "250 on RCPT for a random address", acceptsAll = true)
            "bounce.example" -> pass[RecipientCheckKind.PAST_BOUNCE] =
                item(RecipientCheckKind.PAST_BOUNCE, CheckOutcome.WARN, "550 5.1.1 user unknown, 3 days ago")
            "nomx.example" -> {
                pass[RecipientCheckKind.DOMAIN] = item(RecipientCheckKind.DOMAIN, CheckOutcome.FAIL, "no MX and no A record")
                pass[RecipientCheckKind.MAILBOX] = item(RecipientCheckKind.MAILBOX, CheckOutcome.UNKNOWN)
            }
            "big.example" -> if ((size ?: 0) > 1024) pass[RecipientCheckKind.SIZE_LIMIT] =
                item(RecipientCheckKind.SIZE_LIMIT, CheckOutcome.FAIL, "server takes at most 1 KB, message is $size bytes")
            else -> return null
        }
        if (address.substringBefore('@').lowercase() in setOf("info", "noreply", "postmaster")) {
            pass[RecipientCheckKind.DISPOSABLE_ROLE] = item(RecipientCheckKind.DISPOSABLE_ROLE, CheckOutcome.WARN, "role mailbox")
        }
        val checks = RecipientCheckKind.entries.map { pass.getValue(it) }
        return RecipientStatus(address, verdictOf(checks), checks, System.currentTimeMillis(), fromServer = true)
    }
}
