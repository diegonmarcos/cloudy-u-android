package com.diegonmarcos.superapp.network

import java.net.HttpURLConnection
import java.net.URL

/**
 * #1523 The public IP and the WARP state, from ONE lookup (a Cloudflare trace: `ip=` and `warp=` lines). It is made only when
 * the network dropdown opens and cached for the declared TTL (build.json::home_status_bar.network_speed.public_ip.ttl_ms,
 * 5 min), so reopening the dropdown does not call out again. Never on a timer.
 */
internal object PublicIp {
    data class Info(val ip: String?, val warp: String?, val atMs: Long)

    @Volatile private var cached: Info? = null

    /** `key=value` lines to a map; lines without `=` are skipped. */
    fun parseTrace(text: String): Map<String, String> = text.lineSequence().mapNotNull { l ->
        val i = l.indexOf('='); if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim()
    }.toMap()

    fun infoOf(text: String, nowMs: Long): Info? {
        val m = parseTrace(text)
        val ip = m["ip"]?.takeIf { it.isNotBlank() }
        return if (ip == null) null else Info(ip, m["warp"], nowMs)
    }

    /** Is [i] still inside [ttlMs] at [nowMs]? */
    fun fresh(i: Info?, nowMs: Long, ttlMs: Long): Boolean = i != null && nowMs - i.atMs in 0 until ttlMs

    /** The cached lookup when fresh, else one fetch (blocking, call off the main thread); null when it fails. */
    fun get(url: String, ttlMs: Long, nowMs: Long = System.currentTimeMillis()): Info? {
        cached?.let { if (fresh(it, nowMs, ttlMs)) return it }
        val got = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 4_000; c.readTimeout = 4_000; c.useCaches = false
                if (c.responseCode !in 200..299) null else infoOf(c.inputStream.bufferedReader().use { it.readText().take(4_096) }, nowMs)
            } finally { c.disconnect() }
        }.getOrNull()
        if (got != null) cached = got
        return got
    }

    /** The WARP cell: on / off from `warp=`; null when the lookup gave none. */
    fun warpText(i: Info): String? = when (i.warp) {
        "on" -> "on (Cloudflare WARP)"
        "plus" -> "on (WARP+)"
        "off" -> "off"
        else -> null
    }
}
