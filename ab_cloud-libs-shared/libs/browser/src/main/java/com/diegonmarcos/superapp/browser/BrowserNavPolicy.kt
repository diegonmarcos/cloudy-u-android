package com.diegonmarcos.superapp.browser

/**
 * #893 what a WebView must do for Google sign-in (and any OAuth / form-POST login) to finish, as pure
 * rules a JVM test can pin. Free of android.* on purpose.
 *
 * Three real causes lived here:
 *  - ERR_CACHE_MISS on accounts.google.com/gsi/transform: that page is the RESULT OF A POST. #886 stored
 *    it as the tab's url and saved it in the tab's WebView state; the next rebuild of the WebView (leaving
 *    for the grid, pause/stop, a tab switch) restored or re-loaded it, which asks the cache for a POST
 *    response that was never cached. [isFormPostResult] pages are never committed, never saved and never
 *    restored; the tab falls back to the last page that can be loaded again.
 *  - #1420 Google refuses OAuth in ANY embedded WebView (disallowed_useragent, "This browser or app may not be
 *    secure"). Stripping `; wv` (the old cleanUserAgent, #893) did not fix it: the WebView still sends the
 *    X-Requested-With: <package> header and has no FedCM / Credential Manager, and Google's embedded-webview
 *    policy forbids disguising the agent anyway. So the user agent is left as WebView sends it and every
 *    accounts.google.com navigation is handed to a Custom Tab ([isGoogleOAuth], [Decision.CustomTab]); the tab
 *    reloads when the user comes back.
 *  - popups: accounts.google.com / OAuth open a child window and post back through window.opener, which
 *    needs setSupportMultipleWindows + onCreateWindow; links the page hands to another app (intent:,
 *    market:) must not leave the browser ([decide]).
 */
object BrowserNavPolicy {

    /** Pages that exist only as the response to a POST (or a one-shot token exchange): reloading them cannot work. */
    fun isFormPostResult(url: String?): Boolean {
        val u = url?.trim()?.lowercase().orEmpty()
        if (!u.startsWith("http")) return false
        val rest = u.substringAfter("://")
        val host = rest.substringBefore('/').substringBefore('?')
        val path = "/" + rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
        if (host == "accounts.google.com" || host.endsWith(".accounts.google.com")) {
            return path.startsWith("/gsi/") || path.startsWith("/signin/") || path.startsWith("/v3/signin/") ||
                path.startsWith("/o/oauth2/") || path.startsWith("/accountchooser") || path.contains("servicelogin")
        }
        return false
    }

    /** #1420 a Google sign-in / OAuth page: these never load in the WebView, they go to a Custom Tab. */
    fun isGoogleOAuth(url: String?): Boolean {
        val u = url?.trim()?.lowercase().orEmpty()
        if (!u.startsWith("https://")) return false
        val oauthHost = u.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        return oauthHost == "accounts.google.com" || oauthHost.endsWith(".accounts.google.com")
    }

    fun hasWebViewToken(ua: String): Boolean = Regex(";\\s*wv\\b").containsMatchIn(ua) || Regex("Version/\\d").containsMatchIn(ua)

    sealed class Decision {
        /** Let WebView load it. */
        object Load : Decision()
        /** Load [url] instead (an intent: link's browser_fallback_url). */
        data class Redirect(val url: String) : Decision()
        /** Swallow it: the page asked for another app or the Store; the browser stays where it is. */
        object Block : Decision()
        /** #1420 hand [url] to a Custom Tab (Google OAuth): never rendered in the embedded WebView. */
        data class CustomTab(val url: String) : Decision()
    }

    /** What shouldOverrideUrlLoading does with [url]: web pages load, Google OAuth goes to a Custom Tab, app links never leave. */
    fun decide(url: String?): Decision {
        val u = url?.trim().orEmpty()
        val low = u.lowercase()
        if (isGoogleOAuth(u)) return Decision.CustomTab(u)
        if (low.startsWith("http://") || low.startsWith("https://") || low.startsWith("about:") ||
            low.startsWith("file:") || low.startsWith("data:") || low.startsWith("blob:") || low.startsWith("javascript:")) return Decision.Load
        if (low.startsWith("intent:")) {
            val fb = Regex("S\\.browser_fallback_url=([^;]+)").find(u)?.groupValues?.get(1)
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            return if (fb != null && fb.lowercase().startsWith("http")) Decision.Redirect(fb) else Decision.Block
        }
        return Decision.Block
    }
}
