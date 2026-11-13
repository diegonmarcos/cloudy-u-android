package com.diegonmarcos.superapp.profile

import com.diegonmarcos.cloudlib.auth.SignIn
import java.net.HttpURLConnection
import java.net.URL

/**
 * #1693 Cloud Account's refresh of the Authelia session it holds ([SessionLease.keepFresh]): one GET of the portal's own
 * state route with the cookie, which both proves Authelia still accepts it and extends its inactivity window. The cookie
 * goes only to the DECLARED portal (https, that one host), is never logged and never put in an exception message.
 */
object SessionProbe {
    /** Pure: does the portal's state answer say the session is authenticated? */
    fun accepted(code: Int, body: String): Boolean =
        code == 200 && !Regex("\"authentication_level\"\\s*:\\s*0\\b").containsMatchIn(body)

    /** The state route of the declared Authelia portal, or null when none is declared or it is not https. */
    fun stateUrl(portalUrl: String): String? {
        val u = runCatching { URL(portalUrl) }.getOrNull() ?: return null
        if (u.protocol != "https" || u.host.isBlank()) return null
        return "https://${u.host}${if (u.port > 0 && u.port != 443) ":${u.port}" else ""}/api/state"
    }

    fun probe(): (String) -> Boolean = { cookie ->
        val url = stateUrl(SignIn.byKind(SignIn.Kind.AUTHELIA_WEB)?.portalUrl.orEmpty())
        if (url == null) true   // nothing declared to ask: the lease simply runs to its own expiry
        else runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 4000; c.readTimeout = 6000
                c.setRequestProperty("Cookie", cookie)
                val code = c.responseCode
                accepted(code, if (code == 200) c.inputStream.bufferedReader().use { it.readText().take(4096) } else "")
            } finally { c.disconnect() }
        }.getOrDefault(true)   // a network failure is not a refusal: keep the lease, it expires on its own
    }
}
