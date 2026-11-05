package com.diegonmarcos.superapp.headless

import java.security.MessageDigest
import java.security.SecureRandom

/** #1498 the bearer gate of the loopback listener: one random token per service run; constant-time compare; fails closed. */
class HeadlessTokenGate(val token: String) {
    /** Null when [authorization] is `Bearer <token>`, else the refusal ("unauthorized"). */
    fun check(authorization: String?): String? {
        if (token.isEmpty()) return "server token not configured"
        val m = Regex("^Bearer (.+)$").find(authorization.orEmpty()) ?: return "bearer required"
        return if (MessageDigest.isEqual(m.groupValues[1].toByteArray(), token.toByteArray())) null else "bad token"
    }

    companion object {
        /** 32 random bytes as hex; [random] is injectable for tests. */
        fun generate(random: SecureRandom = SecureRandom()): HeadlessTokenGate = HeadlessTokenGate(ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) })

        /** The listener binds loopback only. */
        fun loopbackOnly(host: String): Boolean = host == "127.0.0.1" || host == "::1" || host == "localhost"
    }
}
