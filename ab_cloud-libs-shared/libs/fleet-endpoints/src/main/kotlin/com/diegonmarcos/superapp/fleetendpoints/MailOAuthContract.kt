package com.diegonmarcos.superapp.fleetendpoints

import org.json.JSONObject

/**
 * #1693 THE MAIL OAUTH HANDOVER: Cloud Mail no longer runs an OAuth device grant, Cloud Account does. Cloud Mail discovers the provider's
 * endpoints exactly as before and files a REQUEST with Cloud Account (method [METHOD_REQUEST], `arg` = [Request] json, grant
 * [GRANT_REQUEST], default deny on top of the fleet signature permission); the person approves it in Cloud Account (App credentials >
 * Mail sign-ins), which runs the grant and files the tokens as App credentials `mail.oauth.<address>.access|refresh|expires`; Cloud Mail
 * then takes them over the per-credential grant (`cred.mail.oauth.*`) and adds the account through its own writer. No token rides the
 * request, and the endpoints are refused unless [endpointAllowed] (https, and the host the sign-in asked or the address's own domain).
 */
object MailOAuthContract {
    const val METHOD_REQUEST = "mail_oauth_request"
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

    /** What Cloud Mail shows when the tokens are not there yet. */
    fun pendingLine(email: String) = "Finish the sign-in for $email in Cloud Account (App credentials > Mail sign-ins), then connect again"
}
