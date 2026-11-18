package com.diegonmarcos.superapp.torrent

import java.net.HttpURLConnection
import java.net.URL

/**
 * #1790 fresh web seeds for an Internet Archive torrent (BEP 19).
 *
 * An archive.org `_archive.torrent` bakes the data server that held the item WHEN THE TORRENT WAS MADE into its `url-list`
 * (for RondoAllaTurca_201401 that is `http://ia902704.us.archive.org/28/items/`). Items move between data servers, and a moved
 * item's baked seed points at a host that no longer answers: on the CI emulator every connect to ia902704 timed out after 15-39 s
 * for the whole run, while the item lives on ia601607 / ia801607 under `/19/items/`. The torrent's other seed,
 * `https://archive.org/download/`, redirects to the live server but goes over TLS, and libtorrent's TLS setup for web seeds
 * failed on the emulator (`asio.ssl init fail (BIO routines)`). So nothing ever downloaded: 0 B and 0 peers.
 *
 * The fix asks archive.org which servers hold the item NOW (`archive.org/metadata/<identifier>`: `workable_servers` and `dir`,
 * falling back to `d1` / `d2` / `server`) and adds each as a plain-HTTP web seed, `http://<server><dir without the identifier>/`.
 * libtorrent appends the torrent's name (the identifier) and the file path, which is exactly archive.org's item layout.
 */
object ArchiveSeeds {
    private val ARCHIVE_HOST = Regex("""^[a-z]+://([a-z0-9-]+\.)*archive\.org(:\d+)?(/|$)""", RegexOption.IGNORE_CASE)
    private val IDENT = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$""")
    private val HOST = Regex("""^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$""", RegexOption.IGNORE_CASE)

    /** The archive.org identifier of a torrent whose web seeds are archive.org's (its name is the identifier), else null. */
    fun identifier(meta: Magnet.MetaInfo): String? {
        if (meta.webSeeds.none { ARCHIVE_HOST.containsMatchIn(it.trim()) }) return null
        return meta.name.trim().takeIf { IDENT.matches(it) }
    }

    /**
     * The web seeds an archive.org metadata document names for [identifier]: one `http://<server><parent>/` per live server, in
     * the document's order, no duplicates. Empty when the document has no usable `dir` or no server.
     */
    fun fromMetadata(json: String, identifier: String): List<String> {
        val dir = str(json, "dir") ?: return emptyList()
        if (!dir.startsWith("/") || ".." in dir) return emptyList()
        val parent = dir.trimEnd('/').let { if (it.endsWith("/$identifier")) it.removeSuffix(identifier) else return emptyList() }
        val hosts = (list(json, "workable_servers") + listOfNotNull(str(json, "d1"), str(json, "d2"), str(json, "server")))
            .map { it.trim().lowercase() }.filter { HOST.matches(it) && (it == "archive.org" || it.endsWith(".archive.org")) }.distinct()
        return hosts.map { "http://$it$parent" }
    }

    /** [fromMetadata] for a torrent, fetching the metadata through [get] (an https GET returning the body or null). */
    fun resolve(meta: Magnet.MetaInfo, get: (String) -> String? = ::httpGet): List<String> {
        val id = identifier(meta) ?: return emptyList()
        val body = runCatching { get("https://archive.org/metadata/$id") }.getOrNull() ?: return emptyList()
        return fromMetadata(body, id)
    }

    private const val MAX_BYTES = 4 * 1024 * 1024

    fun httpGet(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "CloudStore-Torrent")
            if (c.responseCode != 200) return null
            val bytes = c.inputStream.use { s ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
                while (true) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > MAX_BYTES) return null }
                out.toByteArray()
            }
            String(bytes, Charsets.UTF_8)
        } finally { c.disconnect() }
    }

    // A tiny reader for the three top-level shapes used here (string, list of strings); the metadata is plain JSON from archive.org.
    private fun str(json: String, key: String): String? =
        Regex(""""${Regex.escape(key)}"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)?.groupValues?.get(1)?.replace("\\/", "/")

    private fun list(json: String, key: String): List<String> {
        val body = Regex(""""${Regex.escape(key)}"\s*:\s*\[([^\]]*)]""").find(json)?.groupValues?.get(1) ?: return emptyList()
        return Regex(""""((?:[^"\\]|\\.)*)"""").findAll(body).map { it.groupValues[1].replace("\\/", "/") }.toList()
    }
}
