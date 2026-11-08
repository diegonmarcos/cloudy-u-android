package com.diegonmarcos.superapp.fleetxray

/**
 * The X-Ray profile model and its two-way export: an HTML page a human reads and a token-optimized Markdown an agent reads,
 * the same style the Logcat tab uses. Pure (no Android), so a plain JVM test covers the formatting and the on-device page only
 * fills the sections from live probes.
 */
public object FleetXrayExport {

    public data class Row(val key: String, val value: String)
    public data class Section(val id: String, val title: String, val rows: List<Row>)
    public data class Profile(
        val app: String,
        val pkg: String,
        val sha: String,
        val capturedAt: String,
        val sections: List<Section>,
    )

    private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** A self-contained HTML page: identity header and one card per section (tracker). */
    public fun html(p: Profile): String {
        val cards = p.sections.joinToString("\n") { s ->
            val rows = s.rows.joinToString("\n") {
                "<tr><td class=\"k\">${esc(it.key)}</td><td class=\"v\">${esc(it.value)}</td></tr>"
            }
            "<section><h2>${esc(s.title)}</h2><table>\n$rows\n</table></section>"
        }
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>X-Ray — ${esc(p.app)}</title>
<style>
 :root{color-scheme:dark light}
 body{font:13px/1.5 system-ui,sans-serif;margin:0;background:#0d1117;color:#e6edf3}
 header{padding:14px 16px;border-bottom:1px solid #30363d}
 h1{font-size:16px;margin:0 0 6px}.meta{color:#8b949e;font-size:12px}
 section{padding:10px 16px;border-bottom:1px solid #161b22}
 h2{font-size:13px;margin:0 0 6px;color:#58a6ff}
 table{width:100%;border-collapse:collapse;font-size:12px}
 td{padding:2px 8px;vertical-align:top}.k{color:#8b949e;white-space:nowrap;width:40%}.v{white-space:pre-wrap;word-break:break-word}
</style></head><body>
<header><h1>X-Ray — ${esc(p.app)}</h1>
<div class="meta">${esc(p.pkg)} · build ${esc(p.sha.ifBlank { "local" })} · ${esc(p.capturedAt)} · ${p.sections.size} tracker(s)</div></header>
$cards
</body></html>
"""
    }

    /** Token-optimized Markdown: a compact header, then `## Title` + `- key: value` per section. */
    public fun markdown(p: Profile): String {
        val head = buildString {
            append("# X-Ray — ").append(p.app).append('\n')
            append("- pkg: `").append(p.pkg).append("`\n")
            append("- build: `").append(p.sha.ifBlank { "local" }).append("`\n")
            append("- captured: ").append(p.capturedAt).append('\n')
            append("- trackers: ").append(p.sections.size).append('\n')
        }
        val body = p.sections.joinToString("\n\n") { s ->
            "## " + s.title + "\n" + s.rows.joinToString("\n") { "- " + it.key + ": " + it.value }
        }
        return head + "\n" + body + "\n"
    }
}
