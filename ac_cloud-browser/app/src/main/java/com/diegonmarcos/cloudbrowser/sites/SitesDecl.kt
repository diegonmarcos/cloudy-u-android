package com.diegonmarcos.cloudbrowser.sites

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1493 the declaration behind Weby, Cloudy and Domains: build.json::ui.weby / ui.cloudy / ui.domains plus the sibling
 * declarations app/build.gradle reads at BUILD time (the SuperApp phone apps, the declared repos, the Cloud-Webserver's
 * serving map), baked as BuildConfig.SITES_DECL_B64. Nothing a page says or lists is typed in Kotlin.
 */
class SitesDecl(val root: JSONObject) {
    private fun block(name: String) = root.optJSONObject(name) ?: JSONObject()

    val weby: JSONObject get() = block("weby")
    val cloudy: JSONObject get() = block("cloudy")
    val domains: JSONObject get() = block("domains")

    /** The words of one block (`texts`); a missing key answers itself, so a gap shows up on screen rather than as a blank. */
    fun texts(of: JSONObject): (String) -> String {
        val t = of.optJSONObject("texts") ?: JSONObject()
        return { k -> t.optString(k, k) }
    }

    companion object {
        fun parseBase64(b64: String): SitesDecl =
            SitesDecl(runCatching { JSONObject(String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)) }.getOrDefault(JSONObject()))

        fun ids(arr: JSONArray?, key: String = "id"): List<String> = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it)?.optString(key) }
    }
}
