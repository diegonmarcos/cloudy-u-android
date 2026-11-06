package com.diegonmarcos.superapp.crash

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * #1106 the ONE way the fleet opens an EncryptedSharedPreferences file. The raw call throws (AEADBadTagException,
 * Keystore VERIFICATION_FAILED, InvalidProtocolBufferException ...) when the prefs file survived but the Keystore
 * master key is not the one that sealed it (a shared-uid migration, a restored backup), and it throws where the app
 * is starting up. [create] never throws: on a crypto failure it deletes the file, retries, then also deletes the key
 * alias and retries, and as a last resort hands back an in-memory store. The secrets in a reset file are gone (they
 * could not be read anyway); [Opened.wasReset] says so, and [wasReset] keeps saying so until [acknowledgeReset].
 * 1_cicd/src/scripts/test/safe-encrypted-prefs-guard.sh fails the build on a direct call anywhere else.
 */
object SafeEncryptedPrefs {
    class Opened(val prefs: SharedPreferences, val wasReset: Boolean, val unencryptedFallback: Boolean = false)

    private const val FLAGS = "safe_encrypted_prefs_reset"

    @JvmStatic fun create(context: Context, fileName: String): Opened {
        val app = context.applicationContext ?: context
        // A build without security-crypto on its runtime classpath throws NoClassDefFoundError (an Error, not an
        // Exception): fall back to plain app-private prefs marked "unencrypted fallback", log once, never crash.
        val opened = try { createEncrypted(app, fileName) } catch (e: LinkageError) { return unencryptedFallback(app, fileName, e) }
        if (opened.wasReset) runCatching { app.getSharedPreferences(FLAGS, Context.MODE_PRIVATE).edit().putBoolean(fileName, true).apply() }
        return opened
    }

    private val loggedFallback = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun unencryptedFallback(app: Context, fileName: String, e: Throwable): Opened {
        if (loggedFallback.compareAndSet(false, true)) runCatching { android.util.Log.e("SafeEncryptedPrefs", "security-crypto missing from the runtime classpath, unencrypted fallback for $fileName", e) }
        val plain = runCatching { app.getSharedPreferences("${fileName}_unencrypted_fallback", Context.MODE_PRIVATE) }.getOrElse { memoryFor(fileName) }
        return Opened(plain, false, true)
    }

    private fun createEncrypted(app: Context, fileName: String): Opened {
        val opened = recover(
            open = {
                EncryptedSharedPreferences.create(
                    app, fileName,
                    MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            },
            deleteFile = { app.deleteSharedPreferences(fileName) },
            deleteKey = { deleteMasterKeyAlias() },
            memory = { memoryFor(fileName) },
        )
        return opened
    }

    /** [create]'s store, for the callers that do not show the reset. */
    @JvmStatic fun prefs(context: Context, fileName: String): SharedPreferences = create(context, fileName).prefs

    /** True from the moment [fileName] was reset until [acknowledgeReset] (survives a restart). */
    @JvmStatic fun wasReset(context: Context, fileName: String): Boolean =
        runCatching { (context.applicationContext ?: context).getSharedPreferences(FLAGS, Context.MODE_PRIVATE).getBoolean(fileName, false) }.getOrDefault(false)

    @JvmStatic fun acknowledgeReset(context: Context, fileName: String) {
        runCatching { (context.applicationContext ?: context).getSharedPreferences(FLAGS, Context.MODE_PRIVATE).edit().remove(fileName).apply() }
    }

    private val memoryStores = HashMap<String, SharedPreferences>()

    /**
     * ONE in-memory store per file for the whole process. Callers open the file anew on each use (the sync worker,
     * the seed, the Git page), so a fresh empty store per open made a credential saved at sign-in invisible to the
     * next reader: the sign-in looked lost and every pass asked again.
     */
    internal fun memoryFor(fileName: String): SharedPreferences =
        synchronized(memoryStores) { memoryStores.getOrPut(fileName) { MemoryPrefs() } }

    /** The recovery policy with the Android parts injected, so a JVM test can drive it. Never throws. */
    internal fun recover(
        open: () -> SharedPreferences,
        deleteFile: () -> Unit,
        deleteKey: () -> Unit,
        memory: () -> SharedPreferences = { MemoryPrefs() },
    ): Opened {
        try { return Opened(open(), false) } catch (_: Exception) {}
        runCatching(deleteFile)
        try { return Opened(open(), true) } catch (_: Exception) {}
        runCatching(deleteFile)
        runCatching(deleteKey)
        try { return Opened(open(), true) } catch (_: Exception) {}
        return Opened(memory(), true)
    }

    private fun deleteMasterKeyAlias() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
    }

    /** The last resort: values live until the process ends, nothing touches disk. */
    internal class MemoryPrefs : SharedPreferences {
        private val map = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = synchronized(map) { HashMap(map) }
        override fun getString(k: String, d: String?) = synchronized(map) { map[k] as? String ?: d }
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(k: String, d: MutableSet<String>?) = synchronized(map) { map[k] as? MutableSet<String> ?: d }
        override fun getInt(k: String, d: Int) = synchronized(map) { map[k] as? Int ?: d }
        override fun getLong(k: String, d: Long) = synchronized(map) { map[k] as? Long ?: d }
        override fun getFloat(k: String, d: Float) = synchronized(map) { map[k] as? Float ?: d }
        override fun getBoolean(k: String, d: Boolean) = synchronized(map) { map[k] as? Boolean ?: d }
        override fun contains(k: String) = synchronized(map) { map.containsKey(k) }
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val puts = HashMap<String, Any?>()
            private val removes = HashSet<String>()
            private var clear = false
            override fun putString(k: String, v: String?) = also { puts[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = also { puts[k] = v }
            override fun putInt(k: String, v: Int) = also { puts[k] = v }
            override fun putLong(k: String, v: Long) = also { puts[k] = v }
            override fun putFloat(k: String, v: Float) = also { puts[k] = v }
            override fun putBoolean(k: String, v: Boolean) = also { puts[k] = v }
            override fun remove(k: String) = also { removes.add(k) }
            override fun clear() = also { clear = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply(): Unit = synchronized(map) {
                if (clear) map.clear()
                removes.forEach { map.remove(it) }
                puts.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            }
        }
    }
}
