package app.sterna.ui.rss

import app.sterna.core.data.rss.AlertRssAccount
import app.sterna.core.data.rss.AlertRssChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The default Alert (RSS) Account (#1624): the c3-notify RSS system. It ships with the app — present
 * out of the box, not removable — but, unlike mail accounts, its channels are NOT baked into the APK
 * (the runtime-data guard forbids new baked JSON / BuildConfig data: an APK is an engine). Instead the
 * channel list is SYNCED from the fleet at runtime, the same shape as Cloud-chat's fleet-provided
 * account: the account is declared in code (its endpoints), its channels come from the server.
 *
 * The source is the rss-gateway's anonymous channel registry ([CHANNELS_URL]); each channel is read
 * through its anonymous per-channel Atom route ([CHANNEL_TEMPLATE]), reachable from any device without
 * the mesh. When the registry cannot be reached, the account degrades to the single "all" fan-out feed
 * ([ALL_FEED], every channel merged) so the Alerts view is never empty — never a silent stub.
 */
object DefaultAlertRssAccount {

    const val ID = "c3-notify"
    const val NAME = "Fleet Alerts (c3)"

    private const val BASE = "https://rss.diegonmarcos.com"
    private const val CHANNELS_URL = "$BASE/feed/channels.json"
    private const val CHANNEL_TEMPLATE = "$BASE/feed/c/{topic}.atom"
    /** The fan-out of every channel — the honest fallback when the registry cannot be listed. */
    private const val ALL_FEED = "$BASE/feed/c/all.atom"

    @Volatile private var cached: List<AlertRssChannel>? = null

    /** The default account with its channels as last synced (empty until [syncChannels] has run). */
    fun account(): AlertRssAccount =
        AlertRssAccount(id = ID, name = NAME, channels = cached ?: emptyList(), builtIn = true)

    /** The feed addresses the default account contributes, as last synced; the "all" feed before a
     *  first successful sync so the Alerts view has the fleet's alerts out of the box. */
    fun feedUrls(): List<String> =
        (cached?.map { it.url }).orEmpty().ifEmpty { listOf(ALL_FEED) }

    /**
     * Fetch the channel registry and build one feed per channel, off the main thread. Falls back to
     * the "all" fan-out feed when the registry is unreachable or lists nothing. The result is cached
     * so [account] and [feedUrls] can answer synchronously afterwards.
     */
    suspend fun syncChannels(): List<AlertRssChannel> = withContext(Dispatchers.IO) {
        val topics = runCatching { topicsFrom(fetch(CHANNELS_URL)) }.getOrDefault(emptyList())
        val channels = topics.map { AlertRssChannel(it, CHANNEL_TEMPLATE.replace("{topic}", it)) }
            .ifEmpty { listOf(AlertRssChannel("Everything", ALL_FEED)) }
        cached = channels
        channels
    }

    /** Pull channel topics out of the registry body, defensively: the gateway may answer a bare
     *  array of topic strings, or an object keyed "topics"/"channels" over strings or objects that
     *  carry the topic under "topic"/"id"/"name". Anything else yields nothing (and the fallback). */
    private fun topicsFrom(body: String): List<String> {
        val trimmed = body.trim()
        val arr: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val o = JSONObject(trimmed)
                o.optJSONArray("topics") ?: o.optJSONArray("channels") ?: JSONArray()
            }
            else -> JSONArray()
        }
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val topic = when (val item = arr.opt(i)) {
                is String -> item
                is JSONObject -> item.optString("topic")
                    .ifBlank { item.optString("id") }
                    .ifBlank { item.optString("name") }
                else -> ""
            }.trim()
            if (topic.isNotEmpty()) out.add(topic)
        }
        return out.distinct()
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
