package app.sterna.fleet

import com.diegonmarcos.superapp.fleetendpoints.AppCredentialsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1693 the ids Cloud Mail asks Cloud Account for are catalogue ids it is granted by default; no value is in any log form. */
class CloudSecretsTest {
    @Test fun `the ids are flat, lower case and the catalogue's`() {
        assertEquals("mail.password.me_example_org", CloudSecrets.passwordId("Me@Example.org "))
        assertEquals("mail.token.me_example_org", CloudSecrets.tokenId(" ME@example.org"))
        for (id in listOf(CloudSecrets.passwordId("a@b.c"), CloudSecrets.tokenId("a@b.c"), CloudSecrets.GATEWAY_TOKEN_ID)) assertNotNull(id, AppCredentialsContract.entry(id))
    }

    @Test fun `Mail is granted each of them by default`() {
        val g = AppCredentialsContract.defaultGrants(AppCredentialsContract.PKG_MAIL)
        assertTrue(g.containsAll(listOf("cred.mail.password.*", "cred.mail.token.*", "cred.mail.gateway_token")))
    }

    @Test fun `a read is a value or a sentence naming the fix, and the value is redacted`() {
        val v = CloudSecrets.taken(AppCredentialsContract.Credential("mail.password.x", "S3CRET", "from Cloud Account"))
        assertTrue(v is CloudSecrets.Taken.Value)
        assertFalse(v.toString().contains("S3CRET"))
        val m = CloudSecrets.taken(AppCredentialsContract.Credential("mail.password.x", "", AppCredentialsContract.missing("Mailbox password"))) as CloudSecrets.Taken.Missing
        assertTrue(m.line.contains("App credentials"))
    }

    @Test fun `no address cannot name a credential`() {
        assertTrue(CloudSecrets.needAddress("  ") is CloudSecrets.Taken.Missing)
        assertEquals(null, CloudSecrets.needAddress("a@b.c"))
    }
}
