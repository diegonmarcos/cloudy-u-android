package com.diegonmarcos.cloudme

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The portable Buro backup bundle written to / read from an external drive (Buro > Configs >
 * Import / Export), over the Storage Access Framework — a self-contained offline copy, independent
 * of GitHub.
 *
 * A bundle is one JSON envelope:
 *   { "manifest": { schemaVersion, createdAt, commit, packs:[{id, sha256, encrypted}] },
 *     "entries": { "<pack-id>": base64(bytes) },   // secrets entries are ciphertext
 *     "sig": base64(ed25519 over the canonical manifest) , "pub": base64(x509 ed25519 key) }
 *
 * Rules the owner set, encoded here (all unit-tested with SYNTHETIC fixtures in [BuroBackupTest]):
 *  - a secrets pack written to removable media is ALWAYS encrypted with an owner passphrase
 *    (AES-256-GCM, key = PBKDF2WithHmacSHA256); plaintext secrets never touch the drive.
 *  - import verifies the manifest signature and every entry's sha256 before anything is applied;
 *    a tampered byte fails the check and nothing is merged.
 *  - a wrong passphrase fails to decrypt the secrets entry and the import is refused.
 * No secret value is ever logged; this module only moves bytes.
 */
object BuroBackup {
    const val SCHEMA = 1
    private const val PBKDF2_ITERS = 120_000
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val GCM_TAG_BITS = 128

    data class PackEntry(val id: String, val bytes: ByteArray, val encrypted: Boolean)

    sealed interface ImportResult {
        data class Ok(val packs: List<PackEntry>, val commit: String) : ImportResult
        data class Rejected(val reason: String) : ImportResult
    }

    private fun sha256(b: ByteArray): String = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(b), Base64.NO_WRAP)
    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERS, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(key, "AES")
    }

    /** AES-256-GCM: output = salt | iv | ciphertext+tag. */
    fun encrypt(plain: ByteArray, passphrase: CharArray): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = c.doFinal(plain)
        return salt + iv + ct
    }

    /** Throws on a wrong passphrase / tampered ciphertext (GCM tag fails). */
    fun decrypt(blob: ByteArray, passphrase: CharArray): ByteArray {
        val salt = blob.copyOfRange(0, SALT_LEN)
        val iv = blob.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
        val ct = blob.copyOfRange(SALT_LEN + IV_LEN, blob.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        return c.doFinal(ct)
    }

    /** The canonical manifest bytes that are signed and verified (stable key order). */
    private fun canonicalManifest(m: JSONObject): ByteArray {
        val packs = m.getJSONArray("packs")
        val sb = StringBuilder("schemaVersion=${m.getInt("schemaVersion")};commit=${m.optString("commit")};createdAt=${m.optString("createdAt")};")
        for (i in 0 until packs.length()) {
            val p = packs.getJSONObject(i)
            sb.append("${p.getString("id")}:${p.getString("sha256")}:${p.getBoolean("encrypted")};")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Build a bundle. [encryptIds] (e.g. {"me-secrets"}) are encrypted under [passphrase]; the rest
     * are stored verbatim. [sign] signs the canonical manifest and returns (signatureB64, pubX509B64);
     * pass null to leave the bundle unsigned (import then refuses it unless no verifier is required).
     */
    fun export(
        packs: Map<String, ByteArray>,
        encryptIds: Set<String>,
        passphrase: CharArray,
        commit: String,
        createdAt: String,
        sign: ((ByteArray) -> Pair<String, String>)? = null,
    ): ByteArray {
        val entries = JSONObject()
        val packArr = JSONArray()
        for ((id, bytes) in packs) {
            val enc = id in encryptIds
            val stored = if (enc) encrypt(bytes, passphrase) else bytes
            entries.put(id, b64(stored))
            packArr.put(JSONObject().put("id", id).put("sha256", sha256(stored)).put("encrypted", enc))
        }
        val manifest = JSONObject().put("schemaVersion", SCHEMA).put("commit", commit).put("createdAt", createdAt).put("packs", packArr)
        val envelope = JSONObject().put("manifest", manifest).put("entries", entries)
        sign?.invoke(canonicalManifest(manifest))?.let { (sig, pub) -> envelope.put("sig", sig).put("pub", pub) }
        return envelope.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Read a bundle back. [verify] checks (manifestBytes, sigB64, pubB64) and returns true/false;
     * pass null to skip signature checking (sha256 integrity is always enforced). [passphrase]
     * decrypts the encrypted entries; a wrong one makes the import fail without applying anything.
     * [verifyOnly] stops after all checks pass and returns Ok with no decrypted bytes applied.
     */
    fun import(
        bundleBytes: ByteArray,
        passphrase: CharArray,
        verify: ((ByteArray, String, String) -> Boolean)? = null,
        verifyOnly: Boolean = false,
    ): ImportResult {
        val env = runCatching { JSONObject(String(bundleBytes, Charsets.UTF_8)) }.getOrNull()
            ?: return ImportResult.Rejected("not a readable bundle")
        val manifest = env.optJSONObject("manifest") ?: return ImportResult.Rejected("no manifest")
        if (manifest.optInt("schemaVersion", -1) != SCHEMA) return ImportResult.Rejected("unsupported schema ${manifest.optInt("schemaVersion", -1)}")
        if (verify != null) {
            val sig = env.optString("sig"); val pub = env.optString("pub")
            if (sig.isBlank() || pub.isBlank()) return ImportResult.Rejected("bundle is not signed")
            if (!verify(canonicalManifest(manifest), sig, pub)) return ImportResult.Rejected("signature does not verify")
        }
        val entries = env.optJSONObject("entries") ?: return ImportResult.Rejected("no entries")
        val packs = manifest.getJSONArray("packs")
        // 1) integrity: every entry present and its sha256 matches before anything is decrypted
        val raw = HashMap<String, ByteArray>()
        for (i in 0 until packs.length()) {
            val p = packs.getJSONObject(i); val id = p.getString("id")
            val b64v = entries.optString(id, "")
            if (b64v.isBlank()) return ImportResult.Rejected("entry $id missing")
            val bytes = runCatching { unb64(b64v) }.getOrNull() ?: return ImportResult.Rejected("entry $id not base64")
            if (sha256(bytes) != p.getString("sha256")) return ImportResult.Rejected("entry $id checksum mismatch (tampered)")
            raw[id] = bytes
        }
        if (verifyOnly) return ImportResult.Ok(emptyList(), manifest.optString("commit"))
        // 2) decrypt the encrypted entries; a wrong passphrase throws and the whole import is refused
        val out = ArrayList<PackEntry>()
        for (i in 0 until packs.length()) {
            val p = packs.getJSONObject(i); val id = p.getString("id"); val enc = p.getBoolean("encrypted")
            val bytes = raw.getValue(id)
            val plain = if (enc) runCatching { decrypt(bytes, passphrase) }.getOrNull()
                ?: return ImportResult.Rejected("could not decrypt $id — wrong passphrase or corrupt") else bytes
            out.add(PackEntry(id, plain, enc))
        }
        return ImportResult.Ok(out, manifest.optString("commit"))
    }

    /** True when the bytes on disk for a secrets entry are ciphertext (no plaintext secret marker). */
    fun entryLooksCiphertext(bytes: ByteArray): Boolean {
        val s = String(bytes, Charsets.UTF_8)
        return !s.contains("BEGIN OPENSSH PRIVATE KEY") && !s.contains("BEGIN CERTIFICATE") &&
            !s.contains("otpauth://") && !Regex("""eyJ[\w-]+\.eyJ[\w-]+\.[\w-]+""").containsMatchIn(s)
    }
}
