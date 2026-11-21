package com.diegonmarcos.cloudwebserver

import java.security.SecureRandom

/**
 * The fleet bearer token, OWNED by this app (owner decision on #1835; it used to be minted by the SuperApp). The value lives only
 * encrypted at rest ([TokenCodec], Android Keystore AES-256-GCM in production) and is never logged: [Status] carries the last 4
 * characters and the set date, [reveal] is the only way to the full value and the UI gates it behind the device credential.
 *
 * Pure Kotlin over two seams ([Kv], [TokenCodec]) so the unit tests run on the JVM with in-memory fakes.
 */
interface Kv {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

interface TokenCodec {
    fun encode(plain: ByteArray): ByteArray
    fun decode(stored: ByteArray): ByteArray
}

object FleetTokenLogic {
    /** Last 4 characters only, behind a fixed mask; shorter values show nothing of themselves. */
    fun mask(token: String?): String = when {
        token.isNullOrEmpty() -> ""
        token.length < 8 -> "••••"
        else -> "••••" + token.takeLast(4)
    }

    fun last4(token: String?): String = if (token != null && token.length >= 8) token.takeLast(4) else ""

    /** 256 random bits as 64 lowercase hex characters. */
    fun generate(rnd: SecureRandom = SecureRandom()): String {
        val b = ByteArray(32).also { rnd.nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    private val PASTE = Regex("^[A-Za-z0-9._~+/=-]{16,512}$")

    /** A pasted value, trimmed, or null when it cannot be a bearer token (whitespace inside, too short, odd characters). */
    fun cleanPaste(raw: String?): String? = raw?.trim()?.takeIf { PASTE.matches(it) }

    enum class Migration { NONE, IMPORT }

    /** Where the held value came from: [VAULT] = delivered by Cloud Account from the owner's vault; the others are NOT in the vault. */
    const val VAULT = "vault"
    const val PHONE = "phone"
    const val SUPERAPP = "superapp"

    enum class Delivery { NONE, IMPORT, MARK_VAULT, REPLACE, KEEP_LOCAL }

    /**
     * The declarative vault delivery (the vault's value is the owner's): nothing delivered = nothing to do; nothing held = import it;
     * the same value = mark it as in the vault; a different value over a vault-sourced one = the vault was rotated, replace;
     * a different value over a phone-made one = keep it (Generate / Rotate on the phone), which stays "not in vault".
     */
    fun deliveryPlan(held: String?, source: String?, delivered: String?): Delivery = when {
        delivered.isNullOrBlank() -> Delivery.NONE
        held.isNullOrBlank() -> Delivery.IMPORT
        held == delivered -> if (source == VAULT) Delivery.NONE else Delivery.MARK_VAULT
        source == VAULT -> Delivery.REPLACE
        else -> Delivery.KEEP_LOCAL
    }

    /**
     * One-time import from the SuperApp: only while this app has no token, the migration has not run, and the SuperApp has one.
     * Once anything is set, generated, pasted or cleared here, [migrated] is true and the SuperApp is never read again.
     */
    fun migrationPlan(hasOwn: Boolean, migrated: Boolean, superappToken: String?): Migration =
        if (!hasOwn && !migrated && !superappToken.isNullOrBlank()) Migration.IMPORT else Migration.NONE
}

class FleetTokenVault(private val kv: Kv, private val codec: TokenCodec, private val now: () -> Long = System::currentTimeMillis) {
    data class Status(val isSet: Boolean, val last4: String, val setAtMs: Long?, val migrated: Boolean, val source: String? = null) {
        val inVault: Boolean get() = isSet && source == FleetTokenLogic.VAULT
    }

    /** The full value. Callers: the guarded provider (peers, the mesh listener) and the credential-gated Reveal / Copy. */
    fun reveal(): String? = kv.get(K_CT)?.let { runCatching { String(codec.decode(hex(it)), Charsets.UTF_8) }.getOrNull() }?.takeIf { it.isNotBlank() }

    fun status(): Status {
        val t = reveal()
        return Status(t != null, FleetTokenLogic.last4(t), kv.get(K_AT)?.toLongOrNull().takeIf { t != null }, kv.get(K_MIG) == "1", kv.get(K_SRC).takeIf { t != null })
    }

    fun set(token: String, source: String = FleetTokenLogic.PHONE): Boolean {
        val t = FleetTokenLogic.cleanPaste(token) ?: return false
        kv.put(K_CT, unhex(codec.encode(t.toByteArray(Charsets.UTF_8))))
        kv.put(K_AT, now().toString())
        kv.put(K_MIG, "1")
        kv.put(K_SRC, source)
        return true
    }

    fun generate(): String = FleetTokenLogic.generate().also { set(it) }

    /** Rotate = a fresh 256-bit value replacing the old one; peers read the owner on their next request. */
    fun rotate(): String = generate()

    fun clear() {
        kv.remove(K_CT); kv.remove(K_AT); kv.remove(K_SRC)
        kv.put(K_MIG, "1")   // a cleared token must not be re-imported from the SuperApp
    }

    /** The one-time migration. [superapp] reads the SuperApp's token (the existing provider path) and is only called when it can matter. */
    fun migrate(superapp: () -> String?): Boolean {
        val s = status()
        if (s.isSet || s.migrated) return false
        val got = superapp()
        return if (FleetTokenLogic.migrationPlan(s.isSet, s.migrated, got) == FleetTokenLogic.Migration.IMPORT) set(got!!.trim(), FleetTokenLogic.SUPERAPP) else false
    }

    /** The value Cloud Account delivered from the owner's vault (see [FleetTokenLogic.deliveryPlan]); true when the held token changed or was confirmed. */
    fun deliver(delivered: String?): Boolean {
        val s = status()
        val d = delivered?.trim()
        return when (FleetTokenLogic.deliveryPlan(reveal(), s.source, d)) {
            FleetTokenLogic.Delivery.IMPORT, FleetTokenLogic.Delivery.REPLACE -> set(d!!, FleetTokenLogic.VAULT)
            FleetTokenLogic.Delivery.MARK_VAULT -> { kv.put(K_SRC, FleetTokenLogic.VAULT); true }
            else -> false
        }
    }

    private fun unhex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val K_CT = "ct"
        const val K_AT = "set_at"
        const val K_MIG = "migrated"
        const val K_SRC = "source"
    }
}
