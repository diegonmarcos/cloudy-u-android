package com.diegonmarcos.superapp.fleetendpoints

import org.json.JSONObject

/**
 * #1693 THE MAIL OAUTH DEVICE GRANT RUNS IN CLOUD ACCOUNT. Cloud Mail discovers the provider's endpoints exactly as before, then asks
 * Cloud Account to run the grant: [METHOD_START] (`arg` = [Request] json) answers a [StartAnswer] (the code to show, and a `handle`; the
 * device code stays in Cloud Account), and [METHOD_POLL] (`arg` = the handle) answers one [PollAnswer] per call, the tokens once the
 * person approved. Grant [GRANT_REQUEST] (default deny) on top of the fleet signature permission; the endpoints are refused unless
 * [endpointAllowed] (https, and the host the sign-in asked or the address's own domain). Cloud Mail adds the account through its own writer.
 */
object MailOAuthContract {
    const val METHOD_START = "mail_oauth_start"
    const val METHOD_POLL = "mail_oauth_poll"
    const val KEY_JSON = "mail_oauth_json"
    const val GRANT_REQUEST = "mail.oauth"
    const val KEY_OK = FleetEndpointsContract.KEY_OK
    const val KEY_ERROR = FleetEndpointsContract.KEY_ERROR
    const val PROVIDER = "mail_oauth"
    /** What an expiry is: absolute epoch milliseconds, 0 = never. */
    const val FIELD_ACCESS = "access"
    const val FIELD_REFRESH = "refresh"
    const val FIELD_EXPIRES = "expires"

    data class Request(val email: String, val host: String, val deviceEndpoint: String, val tokenEndpoint: String, val clientId: String, val scope: String) {
        fun json(): String = JSONObject().put("v", 1).put("email", email).put("host", host).put("device_endpoint", deviceEndpoint)
            .put("token_endpoint", tokenEndpoint).put("client_id", clientId).put("scope", scope).toString()
    }

    /** The request, or null when it is not one of this version, names no address, or an endpoint is not allowed. */
    fun parse(text: String?): Request? {
        val o = runCatching { JSONObject(text ?: return null) }.getOrNull() ?: return null
        if (o.optInt("v", 0) != 1) return null
        val email = o.optString("email").trim()
        if (!email.contains('@') || email.length > 254) return null
        val host = o.optString("host").trim()
        val domain = email.substringAfter('@')
        val dev = o.optString("device_endpoint").trim(); val tok = o.optString("token_endpoint").trim()
        if (!endpointAllowed(dev, host, domain) || !endpointAllowed(tok, host, domain)) return null
        val client = o.optString("client_id").trim()
        if (client.isBlank() || client.length > 256) return null
        return Request(email, host, dev, tok, client, o.optString("scope").trim().take(512))
    }

    /** https only, and the endpoint's host is the host asked or the address's domain (or a subdomain of either). */
    fun endpointAllowed(endpoint: String, host: String, addressDomain: String): Boolean {
        if (!endpoint.startsWith("https://")) return false
        val h = endpoint.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').lowercase()
        if (h.isBlank() || h.contains('@')) return false
        val owners = listOf(host.removePrefix("https://").substringBefore('/').substringBefore(':'), addressDomain).map { it.lowercase() }.filter { it.isNotBlank() }
        return owners.any { h == it || h.endsWith(".$it") }
    }

    fun credId(email: String, field: String) = "mail.oauth." + AppCredentialsContract.slug(email.trim().lowercase()) + "." + field

    data class StartAnswer(val handle: String, val userCode: String, val verificationUri: String, val verificationUriComplete: String?, val interval: Int, val expiresIn: Int) {
        override fun toString() = "StartAnswer(userCode=$userCode, verificationUri=$verificationUri, interval=$interval, expiresIn=$expiresIn)"
        fun json(): String = JSONObject().put("v", 1).put("handle", handle).put("user_code", userCode).put("verification_uri", verificationUri)
            .also { o -> verificationUriComplete?.let { o.put("verification_uri_complete", it) } }.put("interval", interval).put("expires_in", expiresIn).toString()
    }

    fun parseStart(text: String?): StartAnswer? {
        val o = runCatching { JSONObject(text ?: return null) }.getOrNull() ?: return null
        if (o.optInt("v", 0) != 1 || o.optString("handle").isBlank() || o.optString("user_code").isBlank()) return null
        return StartAnswer(o.getString("handle"), o.getString("user_code"), o.optString("verification_uri"), o.optString("verification_uri_complete").ifBlank { null },
            o.optInt("interval", 5), o.optInt("expires_in", 900))
    }

    /** One poll: pending / slow_down / success (tokens) / failed (the protocol's `error` word, never a body). */
    data class PollAnswer(val state: String, val accessToken: String = "", val refreshToken: String? = null, val expiresIn: Long = 0, val idToken: String? = null,
                          val scope: String? = null, val error: String = "", val description: String = "", val aadsts: String? = null) {
        override fun toString() = "PollAnswer(state=$state, error=$error)"
        fun json(): String = JSONObject().put("v", 1).put("state", state).put("access_token", accessToken).put("expires_in", expiresIn)
            .also { o -> refreshToken?.let { o.put("refresh_token", it) }; idToken?.let { o.put("id_token", it) }; scope?.let { o.put("scope", it) }; aadsts?.let { o.put("aadsts", it) } }
            .put("error", error).put("description", description).toString()
    }

    fun parsePoll(text: String?): PollAnswer? {
        val o = runCatching { JSONObject(text ?: return null) }.getOrNull() ?: return null
        if (o.optInt("v", 0) != 1) return null
        val state = o.optString("state")
        if (state !in listOf("pending", "slow_down", "success", "failed")) return null
        return PollAnswer(state, o.optString("access_token"), o.optString("refresh_token").ifBlank { null }, o.optLong("expires_in"), o.optString("id_token").ifBlank { null },
            o.optString("scope").ifBlank { null }, o.optString("error"), o.optString("description"), o.optString("aadsts").ifBlank { null })
    }

    fun refusal(error: String?) = "Cloud Account did not run the sign-in: ${error.orEmpty().ifBlank { "no reason given" }}"
}
