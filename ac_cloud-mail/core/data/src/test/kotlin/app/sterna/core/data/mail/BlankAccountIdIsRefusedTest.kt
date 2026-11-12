package app.sterna.core.data.mail

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The belt behind the connect screen's braces (issue #121).
 */
class BlankAccountIdIsRefusedTest {

    @Test
    fun `refresh refuses a blank account id`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "refresh")
        assertTrue(
            "MailRepository.refresh must refuse a blank account id: every row it writes is tagged " +
                "with credentials.id, and a blank one strands them under an account that does not " +
                "exist (#121). Throw rather than skip — a silent no-op would hide the caller.\n" +
                "Body was:\n$body",
            body.contains("require(credentials.id.isNotBlank())"),
        )
    }

    @Test
    fun `syncMailbox refuses a blank local account id`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "syncMailboxWindow")
        assertTrue(
            "MailRepository.syncMailbox must refuse a blank local account id, for the same reason " +
                "as refresh: it is the other entry point that tags cached rows with the local " +
                "account id (#121).\nBody was:\n$body",
            body.contains("require(localAccountId.isNotBlank())"),
        )
    }
}
