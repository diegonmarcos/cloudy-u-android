package com.diegonmarcos.superapp.decisions.engine

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.diegonmarcos.superapp.decisions.core.KvStore
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * #1801 the key book's document on disk, sealed: AES-256-GCM under a non-exportable Android Keystore key that only this
 * APK's uid can use, in filesDir/decisions/ (the app's own directory, excluded from backup like the rest of this engine's
 * state). The file holds a 12-byte IV and the ciphertext; the plaintext JSON exists only in this process's memory while a
 * call reads it. A document that cannot be opened (a wiped Keystore after a restore, a torn write) reads as empty: the owner
 * sets the key again on the AI keys screen, which is safer than serving a half-read one.
 *
 * Written atomically (a sibling, renamed over). Nothing here logs: the document is the fleet's keys.
 */
class SealedKv(private val file: File) : KvStore {

    @Synchronized
    override fun read(): JSONObject? = runCatching {
        if (!file.isFile) return null
        val raw = file.readBytes()
        if (raw.size <= IV_BYTES) return null
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
        JSONObject(c.doFinal(raw, IV_BYTES, raw.size - IV_BYTES).toString(Charsets.UTF_8))
    }.getOrNull()

    @Synchronized
    override fun write(doc: JSONObject) {
        runCatching {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, key())
            val sealed = c.iv + c.doFinal(doc.toString().toByteArray(Charsets.UTF_8))
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(sealed)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "decisions-engine-keybook"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
