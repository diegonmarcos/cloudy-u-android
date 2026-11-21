package com.diegonmarcos.superapp.browser

import java.net.URLEncoder

/**
 * A search engine the address bar can hand a non-URL entry to.
 *
 * [template] carries the query at [QUERY] rather than being concatenated,
 * so an engine whose query parameter is not last still works.
 */
data class BrowserSearchEngine(
    val id: String,
    val label: String,
    val template: String,
    /** #886 the engine's query-suggestion feed (https, `{q}`), or null when it has none; see [BrowserRemoteSuggest]. */
    val suggest: String? = null,
) {
    companion object {
        const val QUERY = "{q}"
    }
}

/**
 * What the address bar does with what was typed.
 *
 * Pure, and separated from the engine LIST on purpose: which engines
 * exist and which one is default is per-app configuration
 * ([BrowserConfig]); how an entry becomes a destination is mechanism and
 * lives here, shared.
 */
object BrowserSearch {

    /**
     * Hosts with no TLS at the other end, so a bare "localhost:8000"
     * must not be sent to https:// where it would simply fail.
     */
    private val PRIVATE_HOST = Regex("^(localhost|127\\.0\\.0\\.1|10\\..*|192\\.168\\..*|172\\.(1[6-9]|2\\d|3[01])\\..*)$")

    /**
     * Does this read as a destination rather than something to search for?
     *
     * Whitespace settles most of it: no URL the user types by hand has a
     * space in it, and almost every real query does. What is left is the
     * single-token case, where a dot or a private host is the signal.
     */
    fun isUrlLike(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty()) return false
        if (s.contains("://")) return true
        if (s.any { it.isWhitespace() }) return false
        val host = s.substringBefore("/").substringBefore(":")
        if (PRIVATE_HOST.matches(host)) return true
        if (host.endsWith(".local") || host.endsWith(".lan")) return true
        // A dot with something on both sides — "qwant.com", "a.b.co.uk".
        // "2.5" is excluded: a numeric tail is not a TLD.
        val dot = host.lastIndexOf('.')
        if (dot <= 0 || dot == host.length - 1) return false
        val tld = host.substring(dot + 1)
        return tld.length >= 2 && tld.all { it.isLetter() }
    }

    /**
     * Scheme for a bare host. Private hosts get http:// because nothing
     * on the phone's own loopback or the mesh terminates TLS.
     */
    fun normalizeUrl(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        if (s.contains("://")) return s
        val host = s.substringBefore("/").substringBefore(":")
        val isPrivate = PRIVATE_HOST.matches(host) ||
            host.endsWith(".local") || host.endsWith(".lan")
        return if (isPrivate) "http://$s" else "https://$s"
    }

    /** The search URL [engine] would use for [query]. */
    fun searchUrl(query: String, engine: BrowserSearchEngine): String =
        engine.template.replace(
            BrowserSearchEngine.QUERY,
            URLEncoder.encode(query.trim(), "UTF-8"),
        )

    /**
     * THE address-bar decision: a URL is navigated, anything else is
     * searched with [engine]. Returns "" for a blank entry so callers
     * can early-out.
     *
     * This is the function that decides which engine a non-URL query
     * reaches. Asserting the string "qwant" appears somewhere in the
     * source proves nothing about it — item 4 pins qwant.com as a URL
     * and would satisfy that grep against a Google default.
     */
    fun resolve(input: String, engine: BrowserSearchEngine): String {
        // #1841 the decision is the browser core's (Rust omnibox::resolve; its Kotlin twin when the library is not loaded); the pure
        // helpers above stay as the readable rules and the parity tests' reference
        return com.diegonmarcos.cloudlib.browsercore.BrowserCore.resolve(input, engine.template)
    }
}
