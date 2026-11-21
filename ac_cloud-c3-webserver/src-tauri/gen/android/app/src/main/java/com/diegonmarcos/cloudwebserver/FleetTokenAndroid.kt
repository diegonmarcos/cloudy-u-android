package com.diegonmarcos.cloudwebserver

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM under a key that never leaves the Android Keystore (the fleet-data KeystoreCodec pattern). Stored form: 12-byte IV + ciphertext. */
class KeystoreTokenCodec(private val alias: String = "c3-fleet-token") : TokenCodec {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    override fun encode(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    override fun decode(stored: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, stored.copyOfRange(0, 12))) }
        return c.doFinal(stored, 12, stored.size - 12)
    }
}

object FleetTokens {
    /** The SuperApp's fleet provider: the existing read path, used ONLY by the one-time migration. */
    private const val SUPERAPP_TOKEN_URI = "content://com.diegonmarcos.superapp.fleet/token"

    @Volatile private var vault: FleetTokenVault? = null

    fun of(ctx: Context): FleetTokenVault = vault ?: synchronized(this) {
        vault ?: run {
            val sp = ctx.applicationContext.getSharedPreferences("fleet_token_vault", Context.MODE_PRIVATE)
            FleetTokenVault(object : Kv {
                override fun get(key: String) = sp.getString(key, null)
                override fun put(key: String, value: String) = sp.edit().putString(key, value).apply()
                override fun remove(key: String) = sp.edit().remove(key).apply()
            }, KeystoreTokenCodec()).also { vault = it }
        }
    }

    /** Off the main thread (a cross-process query): import the SuperApp's token once if this app has none. */
    fun migrate(ctx: Context): Boolean = of(ctx).migrate {
        runCatching {
            ctx.applicationContext.contentResolver.query(Uri.parse(SUPERAPP_TOKEN_URI), null, null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }
}

/**
 * The owner's door: `content://<package>.fleettoken/token`, signature-guarded like the fleet provider (CONSTELLATION_DATA). Read by this
 * app's own Rust mesh listener (core bridge read_token) and by every fleet member's FleetToken. Read-only; the value is never logged.
 */
class FleetTokenOwnerProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cur = MatrixCursor(arrayOf("token"))
        val ctx = context ?: return cur
        FleetTokens.migrate(ctx)
        FleetTokens.of(ctx).reveal()?.let { cur.addRow(arrayOf(it)) }
        return cur
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/fleet-token-owner"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
