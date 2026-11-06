package com.diegonmarcos.superapp.fleetdata

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Optional ed25519 signing of a dataset (detached signature over the exact bytes served). The verifier is implemented; it is NOT enforced yet.
 * TODO(fleet-data-signing): enforcement needs a trust key shipped to the phone (the fleet vault public key) and the sign step in the
 * publishing side (data/fleet CI, cloudy-c3-api route); until then [verify] reports Unsupported/NoKey and nothing is rejected for lack of one.
 * java.security's Ed25519 exists on JDK 15+ and Android 13+ (API 33); older phones get [Verdict.Unsupported].
 */
object Ed25519Check {
    enum class Verdict { Valid, Invalid, Unsupported, NoKey }

    fun verify(publicKeyX509B64: String?, message: ByteArray, signatureB64: String?): Verdict {
        if (publicKeyX509B64.isNullOrBlank() || signatureB64.isNullOrBlank()) return Verdict.NoKey
        return try {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyX509B64)))
            val s = Signature.getInstance("Ed25519")
            s.initVerify(key); s.update(message)
            if (s.verify(Base64.getDecoder().decode(signatureB64))) Verdict.Valid else Verdict.Invalid
        } catch (e: java.security.NoSuchAlgorithmException) { Verdict.Unsupported }
        catch (e: Exception) { Verdict.Invalid }
    }
}
