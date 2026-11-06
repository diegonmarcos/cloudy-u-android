package com.diegonmarcos.superapp.fleetdata.provider

import java.net.HttpURLConnection
import java.net.URL

/** HTTPS only, If-None-Match, bounded body. The one place a provider touches the network; apps never do. */
class HttpFetcher(private val maxBytes: Int = 32 * 1024 * 1024) : Fetcher {
    override fun get(url: String, etag: String?, headers: Map<String, String>): Reply {
        require(url.startsWith("https://")) { "https only" }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("Accept", "application/json")
            if (etag != null) c.setRequestProperty("If-None-Match", etag)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            val tag = c.getHeaderField("ETag")
            if (code != 200) return Reply(code, null, tag)
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { i ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = i.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > maxBytes) return Reply(413, null, tag) }
            }
            return Reply(200, out.toByteArray(), tag)
        } finally { c.disconnect() }
    }
}
