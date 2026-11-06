package com.diegonmarcos.cloudme

import android.app.Application
import com.diegonmarcos.cloudme.VaultwardenMapping.SecretType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Vaultwarden -> table mapping, on SYNTHETIC fixtures only (never a real secret value).
 * Pins the owner's two rules: authenticator-first column, and secret / huge values rendered as a
 * reveal (file-link popup) cell rather than inline; plus the type classification and folder scope.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VaultwardenMappingTest {

    // A synthetic export: one item exercising each of the seven secret types, across two folders.
    private val longKey = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + "b3BlbnNzaC1rZXk".repeat(12) + "\n-----END OPENSSH PRIVATE KEY-----"
    private val longCert = "-----BEGIN CERTIFICATE-----\n" + "MIIFakeCert".repeat(20) + "\n-----END CERTIFICATE-----"
    private val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJib3QifQ.sig123signaturepartthatislong"

    private val export = """
    {
      "folders": [
        {"id": "f-web", "name": "social_media"},
        {"id": "f-cli", "name": "cloud_infrastructure"}
      ],
      "items": [
        {"type":1,"name":"Example","folderId":"f-web","login":{"username":"alice@example.com","password":"p4ssw0rd!","uris":[{"uri":"https://example.com"}]}},
        {"type":1,"name":"Bank","folderId":"f-web","login":{"username":"alice","password":"hunter2","totp":"JBSWY3DPEHPK3PXP"}},
        {"type":1,"name":"GitHub","folderId":"f-web","login":{"username":"alice","fido2Credentials":[{"userDisplayName":"alice","rpId":"github.com","credentialId":"cred-xyz"}]}},
        {"type":5,"name":"deploy key","folderId":"f-cli","sshKey":{"keyFingerprint":"SHA256:abc","publicKey":"ssh-ed25519 AAAAshort","privateKey":"$longKey"}},
        {"type":1,"name":"Stripe","folderId":"f-cli","login":{"username":"svc"},"fields":[{"name":"API Token","value":"sk_live_tokenvalue_that_is_moderately_long_000"}]},
        {"type":1,"name":"ServiceJWT","folderId":"f-cli","fields":[{"name":"jwt","value":"$jwt"}]},
        {"type":1,"name":"TLS cert","folderId":"f-cli","notes":"$longCert"}
      ]
    }
    """.trimIndent()

    private fun table(t: SecretType) = VaultwardenMapping.table(export, t)

    @Test fun `folders mirror the vaultwarden folders in order`() {
        assertEquals(listOf("social_media", "cloud_infrastructure"), VaultwardenMapping.folders(export))
    }

    @Test fun `every table is authenticator-first and the authenticator is an identifier, never a secret`() {
        for (t in SecretType.entries) {
            val tbl = table(t)
            for (r in tbl.rows) {
                assertFalse("${t.id}: authenticator cell must be inline, not a reveal", r.cells.first().reveal)
                assertTrue("${t.id}: authenticator is non-blank", r.authenticator.isNotBlank())
            }
        }
    }

    @Test fun `logins expose the password only as a reveal cell`() {
        val t = table(SecretType.LOGINS)
        assertEquals(listOf("Account", "Username", "URL", "Password"), t.columns)
        // the two plain logins (Example, Bank) are here; GitHub (passkey-only) and the CLI items are not
        assertEquals(setOf("alice@example.com", "alice"), t.rows.map { it.authenticator }.toSet())
        val pw = t.rows.first().cells.last()
        assertEquals("Password", pw.label); assertTrue("password is reveal-only", pw.reveal)
    }

    @Test fun `TOTP seed and passkey credential never render inline`() {
        val totp = table(SecretType.TOTP)
        assertEquals(1, totp.rows.size)
        assertTrue(totp.rows[0].cells.last().reveal)        // the seed
        assertEquals("Issuer", totp.columns[1])
        val pk = table(SecretType.PASSKEYS)
        assertEquals(1, pk.rows.size)
        assertEquals("github.com", pk.rows[0].cells[1].value)
        assertTrue(pk.rows[0].cells.last().reveal)          // the credential
    }

    @Test fun `a huge value like a private key or certificate is a reveal cell`() {
        val ssh = table(SecretType.SSH)
        assertEquals(1, ssh.rows.size)
        val priv = ssh.rows[0].cells.last()
        assertEquals("Private key", priv.label)
        assertTrue("a private key is huge and must be a reveal cell", priv.reveal)
        assertFalse("the short fingerprint stays inline", ssh.rows[0].cells[1].reveal)
        val cert = table(SecretType.CERTS)
        assertEquals(1, cert.rows.size)
        assertTrue(cert.rows[0].cells.first { it.label == "Certificate" }.reveal)
    }

    @Test fun `api tokens and jwt are classified apart from plain logins`() {
        assertEquals(listOf("Stripe"), table(SecretType.APITOKENS).rows.map { it.cells.first().value })
        assertEquals(1, table(SecretType.JWT).rows.size)
        assertTrue(table(SecretType.JWT).rows[0].cells.last().reveal)
        // Stripe (api token) and the JWT item must NOT also appear as plain logins
        assertFalse("Stripe" in table(SecretType.LOGINS).rows.map { it.cells.first().value })
    }

    @Test fun `scope By-Folders filters rows to one vaultwarden folder`() {
        val allLogins = VaultwardenMapping.table(export, SecretType.LOGINS, onlyFolder = null).rows.size
        val webLogins = VaultwardenMapping.table(export, SecretType.LOGINS, onlyFolder = "social_media").rows.size
        val cliLogins = VaultwardenMapping.table(export, SecretType.LOGINS, onlyFolder = "cloud_infrastructure").rows.size
        assertEquals(2, allLogins); assertEquals(2, webLogins); assertEquals(0, cliLogins)
        assertEquals(1, VaultwardenMapping.table(export, SecretType.SSH, onlyFolder = "cloud_infrastructure").rows.size)
    }

    @Test fun `the Web types and CLI types split as the owner declared`() {
        assertEquals(listOf("logins", "totp", "passkeys"), SecretType.entries.filter { !it.cli }.map { it.id })
        assertEquals(listOf("ssh", "apitokens", "jwt", "certs"), SecretType.entries.filter { it.cli }.map { it.id })
    }
}
