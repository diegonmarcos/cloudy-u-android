package com.diegonmarcos.cloudme

import android.app.Application
import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** The external-drive backup bundle on SYNTHETIC fixtures: round-trip, tamper rejection, passphrase. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BuroBackupTest {

    private val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val signer: (ByteArray) -> Pair<String, String> = { msg ->
        val s = Signature.getInstance("Ed25519"); s.initSign(kp.private); s.update(msg)
        Base64.encodeToString(s.sign(), Base64.NO_WRAP) to Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
    }
    private val verifier: (ByteArray, String, String) -> Boolean = { msg, sig, pub ->
        runCatching {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP)))
            val v = Signature.getInstance("Ed25519"); v.initVerify(key); v.update(msg); v.verify(Base64.decode(sig, Base64.NO_WRAP))
        }.getOrDefault(false)
    }

    // synthetic packs; the secrets "pack" carries a recognisable plaintext marker we must never see on disk
    private val financial = "SQLITEish-financial-bytes".toByteArray()
    private val secretsPlain = "PLAINTEXT_TOTP_SEED_JBSWY3DPEHPK3PXP".toByteArray()
    private val packs = mapOf("me-financial" to financial, "me-secrets" to secretsPlain)
    private val pass = "correct horse battery staple".toCharArray()

    private fun bundle(sign: Boolean = true) = BuroBackup.export(
        packs, encryptIds = setOf("me-secrets"), passphrase = pass, commit = "abc123", createdAt = "2026-01-01T00:00:00Z",
        sign = if (sign) signer else null,
    )

    @Test fun `export then import returns the source packs`() {
        val res = BuroBackup.import(bundle(), pass, verifier)
        assertTrue(res is BuroBackup.ImportResult.Ok)
        val ok = res as BuroBackup.ImportResult.Ok
        assertEquals("abc123", ok.commit)
        assertArrayEquals(financial, ok.packs.first { it.id == "me-financial" }.bytes)
        assertArrayEquals(secretsPlain, ok.packs.first { it.id == "me-secrets" }.bytes)
    }

    @Test fun `the secrets entry on disk is ciphertext, never the plaintext seed`() {
        val env = JSONObject(String(bundle()))
        val stored = Base64.decode(env.getJSONObject("entries").getString("me-secrets"), Base64.NO_WRAP)
        assertFalse("plaintext secret written to the drive", String(stored).contains("JBSWY3DPEHPK3PXP"))
        assertTrue(BuroBackup.entryLooksCiphertext(stored))
        // the non-secret pack may be stored verbatim
        val fin = Base64.decode(env.getJSONObject("entries").getString("me-financial"), Base64.NO_WRAP)
        assertArrayEquals(financial, fin)
    }

    @Test fun `a tampered byte is rejected before anything is applied`() {
        val b = bundle()
        val env = JSONObject(String(b))
        val entries = env.getJSONObject("entries")
        val fin = Base64.decode(entries.getString("me-financial"), Base64.NO_WRAP)
        fin[0] = (fin[0] + 1).toByte()
        entries.put("me-financial", Base64.encodeToString(fin, Base64.NO_WRAP))
        val res = BuroBackup.import(env.toString().toByteArray(), pass, verifier)
        assertTrue(res is BuroBackup.ImportResult.Rejected)
    }

    @Test fun `the wrong passphrase refuses the import`() {
        val res = BuroBackup.import(bundle(), "wrong".toCharArray(), verifier)
        assertTrue(res is BuroBackup.ImportResult.Rejected)
    }

    @Test fun `verify only passes checks without returning decrypted bytes`() {
        val res = BuroBackup.import(bundle(), pass, verifier, verifyOnly = true)
        assertTrue(res is BuroBackup.ImportResult.Ok)
        assertTrue((res as BuroBackup.ImportResult.Ok).packs.isEmpty())
    }

    @Test fun `an unsigned bundle is refused when a verifier is required`() {
        val res = BuroBackup.import(bundle(sign = false), pass, verifier)
        assertTrue(res is BuroBackup.ImportResult.Rejected)
    }
}
