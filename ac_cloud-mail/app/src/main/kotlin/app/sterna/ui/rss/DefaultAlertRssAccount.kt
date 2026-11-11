package app.sterna.ui.rss

import android.util.Base64
import app.sterna.BuildConfig
import app.sterna.core.data.rss.AlertRssAccount
import app.sterna.core.data.rss.AlertRssChannel
import org.json.JSONObject

/**
 * The default Alert (RSS) Account (#1624): the c3-notify RSS system, declared in
 * build.json::alerts_rss and baked as [BuildConfig.ALERTS_RSS_B64]. It ships with the app — present
 * out of the box, not stored, not removable — and its channels MIRROR the fleet's c3 notify registry
 * (aa_cloud-superapp/build.json::ui.ntfy). The per-channel feed is the rss-gateway's anonymous Atom
 * route, so every device can read it without the mesh.
 *
 * A channel marked `anon:false` in the declaration is listed for completeness in the registry but is
 * NOT reachable by the anonymous route (the gateway answers 404), so it is left out of the feed set
 * the Alerts reader fetches. Adding or renaming a channel is a data edit in build.json, never a code
 * change (the same rule the fleet's NtfyCatalog follows).
 */
object DefaultAlertRssAccount {

    @Volatile private var cached: AlertRssAccount? = null

    /** The default account, parsed once from the baked declaration. Degrades to an empty account
     *  (same id and name) rather than throwing, so a malformed bake never crashes the Alerts view. */
    fun account(): AlertRssAccount {
        cached?.let { return it }
        val built = parse().also { cached = it }
        return built
    }

    /** Just the feed addresses the default account contributes to the Alerts reader. */
    fun feedUrls(): List<String> = account().channels.map { it.url }

    private fun parse(): AlertRssAccount {
        val root = runCatching {
            JSONObject(String(Base64.decode(BuildConfig.ALERTS_RSS_B64, Base64.NO_WRAP)))
        }.getOrDefault(JSONObject())
        val acc = root.optJSONObject("default_account") ?: JSONObject()
        val id = acc.optString("id").ifBlank { "c3-notify" }
        val name = acc.optString("name").ifBlank { "Fleet Alerts" }
        val template = acc.optString("channel_feed_template")
        val arr = acc.optJSONArray("channels")
        val channels = ArrayList<AlertRssChannel>()
        if (arr != null && template.contains("{topic}")) {
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                if (!c.optBoolean("anon", true)) continue
                val topic = c.optString("topic").trim()
                if (topic.isEmpty()) continue
                val label = c.optString("label").ifBlank { topic }
                channels.add(AlertRssChannel(label, template.replace("{topic}", topic)))
            }
        }
        return AlertRssAccount(id = id, name = name, channels = channels, builtIn = true)
    }
}
