package com.diegonmarcos.cloudme

/**
 * #1980 which HTTP answers the page cache may store as a "known-missing" tombstone, served to the WebView as a 404 for the whole ttl.
 * Only a definite "not there" (404, 410) qualifies. A 403 (jsDelivr answers it for a file over its size limit AND under request volume),
 * a 401, 451 or any other 4xx is not definite: storing it blanked every image the CDN refused once (hundreds of parallel jsDelivr requests
 * of the media pages) for seven days, so those answers stay unstored and the WebView's own network gets another try next time.
 */
object Tombstone1980 {
    fun definite(code: Int): Boolean = code == 404 || code == 410
}
