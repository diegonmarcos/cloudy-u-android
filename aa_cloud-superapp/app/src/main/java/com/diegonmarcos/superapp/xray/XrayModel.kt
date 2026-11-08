package com.diegonmarcos.superapp.xray

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * #1504 The X-Ray export model: what one "Export All" produced, and its two renderings (Markdown, JSON). Pure; every text that
 * enters a section has been through [XrayRedact] before it gets here, and the renderers scrub once more (belt and braces).
 */
const val XRAY_SCHEMA = "xray-report/1"
const val SRC_SHELL = "shell"
const val SRC_APP = "app sandbox"
const val SRC_SKIPPED = "skipped (needs adb)"
const val PARTIAL_LABEL = "partial: no adb"

/** One block of the report: the X-Ray group it belongs to, its title, where it was read from, and the text. */
data class XSection(val group: String, val title: String, val source: String, val text: String, val ok: Boolean = true)

/** One ranked suspect: the composite [score] in [unit] (points, 0-100), its dominant [cause], the signals behind it, and its estimated [mAh] (power is one input). */
data class Suspect(val rank: Int, val pkg: String, val score: Double, val unit: String, val cause: String, val detail: String, val fleet: Boolean, val mAh: Double = 0.0)

data class XReport(
    val device: String,
    val appVersion: String,
    val generatedAt: Long,
    val partial: Boolean,
    val partialReason: String,
    val adbChannel: String,
    val suspects: List<Suspect>,
    val fleet: List<String>,
    val sections: List<XSection>,
)

object XrayFormat {

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    fun fileStem(r: XReport): String =
        "xray-" + r.device.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-') + "-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(r.generatedAt))

    fun markdown(r: XReport): String {
        val sb = StringBuilder()
        sb.append("# X-Ray report · ").append(r.device).append("\n\n")
        sb.append("- schema: ").append(XRAY_SCHEMA).append("\n")
        sb.append("- generated: ").append(iso(r.generatedAt)).append("\n")
        sb.append("- app: ").append(r.appVersion).append("\n")
        sb.append("- adb: ").append(r.adbChannel).append("\n")
        sb.append("- order: ① Profile → ② Runtime → ③ Diagnosis by family → ④ Suspects (last)\n")
        if (r.partial) sb.append("\n> **").append(PARTIAL_LABEL).append("** — ").append(r.partialReason).append("\n")
        var group = ""
        for (s in r.sections) {
            if (s.group != group) { group = s.group; sb.append("\n# ").append(group).append("\n") }
            sb.append("\n## ").append(s.title).append("\n\n_source: ").append(s.source).append(if (s.ok) "" else " · failed").append("_\n\n```text\n")
                .append(s.text.trimEnd()).append("\n```\n")
        }
        sb.append("\n# ④ SUSPECTS (verdict)\n\n## Suspects (ranked)\n\n")
        if (r.suspects.isEmpty()) sb.append("none ranked (no per-uid battery or notification stats in this report)\n")
        else {
            sb.append("| # | App | Score | Cause (dominant signal) | Why and other signals |\n|---|---|---|---|---|\n")
            for (s in r.suspects) sb.append("| ${s.rank} | ${if (s.fleet) "**${s.pkg}** (fleet)" else s.pkg} | ")
                .append(String.format(Locale.US, "%.1f", s.score)).append(" | ").append(esc(s.cause)).append(" | ").append(esc(s.detail)).append(" |\n")
        }
        if (r.fleet.isNotEmpty()) {
            sb.append("\n## Fleet apps\n\n")
            for (l in r.fleet) sb.append("- ").append(l).append("\n")
        }
        return XrayRedact.scrub(sb.toString())
    }

    private fun esc(s: String) = s.replace("|", "/").replace("\n", " ")

    fun json(r: XReport): JSONObject {
        val o = JSONObject()
        o.put("schema", XRAY_SCHEMA).put("device", r.device).put("app_version", r.appVersion)
            .put("generated_at", r.generatedAt).put("generated_iso", iso(r.generatedAt))
            .put("partial", r.partial).put("partial_label", if (r.partial) PARTIAL_LABEL else "")
            .put("partial_reason", r.partialReason).put("adb", r.adbChannel)
        o.put("suspects", JSONArray().also { a ->
            for (s in r.suspects) a.put(JSONObject().put("rank", s.rank).put("pkg", s.pkg).put("score", s.score).put("unit", s.unit)
                .put("cause", s.cause).put("detail", XrayRedact.scrub(s.detail)).put("fleet", s.fleet))
        })
        o.put("fleet", JSONArray(r.fleet.map { XrayRedact.scrub(it) }))
        val groups = JSONArray()
        for ((g, l) in r.sections.groupBy { it.group }) {
            groups.put(JSONObject().put("group", g).put("sections", JSONArray().also { a ->
                for (s in l) a.put(JSONObject().put("title", s.title).put("source", s.source).put("ok", s.ok).put("text", XrayRedact.scrub(s.text)))
            }))
        }
        o.put("groups", groups)
        return o
    }

    fun jsonText(r: XReport): String = json(r).toString(2)
}
